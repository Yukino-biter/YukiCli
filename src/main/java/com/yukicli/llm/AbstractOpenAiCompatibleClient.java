package com.yukicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.Tool;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议模板基类。
 *
 * 把请求组装、响应解析、tool_calls 增量合并等通用逻辑抽到模板方法，
 * 子类只需实现 getApiUrl / getModel / getApiKey / getProviderName 等抽象方法，
 * 即可适配 OpenAI / GLM / DeepSeek / Kimi 等 OpenAI 兼容接口。
 *
 * 扩展点：
 *   - {@link #customizeRequestBody(ObjectNode)}：子类可向请求体注入 provider 专属字段
 *   - {@link #customizeRequest(Request.Builder)}：子类可向请求头注入 provider 专属 header
 *   - {@link #httpClient()}：子类可替换 OkHttpClient（默认共享实例）
 *
 * 超时配置：通过系统属性 yukicli.llm.{connect|read|write|call}.timeout.seconds 覆盖默认值。
 */
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("yukicli.llm.connect.timeout.seconds", 30), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("yukicli.llm.read.timeout.seconds", 120), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("yukicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("yukicli.llm.call.timeout.seconds", 300), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** API 完整 URL（如 https://open.bigmodel.cn/api/paas/v4/chat/completions） */
    protected abstract String getApiUrl();

    /** 使用的模型名 */
    protected abstract String getModel();

    /** API Key */
    protected abstract String getApiKey();

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
        try {
            ObjectNode body = buildRequestBody(messages, tools);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(getApiUrl())
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON_TYPE));
            customizeRequest(requestBuilder);
            Request request = requestBuilder.build();

            try (Response response = httpClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    throw new RuntimeException("LLM API error " + response.code() + ": " + errBody);
                }
                String respStr = response.body().string();
                return parseResponse(respStr);
            }
        } catch (IOException e) {
            throw new RuntimeException("LLM 请求失败: " + e.getMessage(), e);
        }
    }

    /** 组装请求体 JSON */
    protected ObjectNode buildRequestBody(List<LlmMessage> messages, List<Tool> tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", getModel());
        body.put("stream", false);

        ArrayNode messagesNode = body.putArray("messages");
        for (LlmMessage msg : messages) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.getRole());
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
            // assistant 消息可能携带 tool_calls
            if ("assistant".equals(msg.getRole()) && !msg.getToolCalls().isEmpty()) {
                ArrayNode tcNode = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.getToolCalls()) {
                    ObjectNode callNode = tcNode.addObject();
                    callNode.put("id", tc.getId());
                    callNode.put("type", "function");
                    ObjectNode fnNode = callNode.putObject("function");
                    fnNode.put("name", tc.getName());
                    fnNode.put("arguments", tc.getArguments());
                }
            }
            // tool 角色消息携带 tool_call_id
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
        }

        // 组装 tools（function calling）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("type", "function");
                ObjectNode fnNode = toolNode.putObject("function");
                fnNode.put("name", tool.getName());
                fnNode.put("description", tool.getDescription());
                fnNode.set("parameters", tool.getParameters());
            }
        }

        customizeRequestBody(body);
        return body;
    }

    /** 子类可覆写：向请求体注入 provider 专属字段 */
    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    /** 子类可覆写：向请求头注入 provider 专属 header */
    protected void customizeRequest(Request.Builder request) {
    }

    /** 子类可覆写：替换 OkHttpClient（默认共享实例） */
    protected OkHttpClient httpClient() {
        return SHARED_HTTP_CLIENT;
    }

    /** 解析响应 JSON */
    protected LlmResponse parseResponse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode choices = root.path("choices");
        if (choices.isEmpty() || !choices.isArray()) {
            return new LlmResponse("[empty response]", null);
        }

        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText("");
        JsonNode toolCallsNode = message.path("tool_calls");

        List<ToolCall> toolCalls = new ArrayList<>();
        if (toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String arguments = tc.path("function").path("arguments").asText("{}");
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }

        return new LlmResponse(content, toolCalls.isEmpty() ? null : toolCalls);
    }
}
