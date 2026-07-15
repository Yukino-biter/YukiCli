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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议客户端。
 * 手拼请求 JSON（Jackson）+ OkHttp 发送，解析 tool_calls 增量。
 * 适用于 OpenAI / GLM / DeepSeek / Kimi 等兼容接口。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String apiBase;
    private final String model;
    private final OkHttpClient http;

    public OpenAiCompatibleClient(String apiKey, String apiBase, String model) {
        this.apiKey = apiKey;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Tool> tools) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("stream", false);

            // 组装 messages
            ArrayNode messagesNode = MAPPER.createArrayNode();
            for (LlmMessage msg : messages) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("role", msg.getRole());
                if (msg.getContent() != null) {
                    msgNode.put("content", msg.getContent());
                }
                // assistant 消息可能携带 tool_calls
                if ("assistant".equals(msg.getRole()) && !msg.getToolCalls().isEmpty()) {
                    ArrayNode tcNode = MAPPER.createArrayNode();
                    for (ToolCall tc : msg.getToolCalls()) {
                        ObjectNode callNode = MAPPER.createObjectNode();
                        callNode.put("id", tc.getId());
                        callNode.put("type", "function");
                        ObjectNode fnNode = MAPPER.createObjectNode();
                        fnNode.put("name", tc.getName());
                        fnNode.put("arguments", tc.getArguments());
                        callNode.set("function", fnNode);
                        tcNode.add(callNode);
                    }
                    msgNode.set("tool_calls", tcNode);
                }
                // tool 角色消息携带 tool_call_id
                if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                    msgNode.put("tool_call_id", msg.getToolCallId());
                }
                messagesNode.add(msgNode);
            }
            body.set("messages", messagesNode);

            // 组装 tools（function calling）
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsNode = MAPPER.createArrayNode();
                for (Tool tool : tools) {
                    ObjectNode toolNode = MAPPER.createObjectNode();
                    toolNode.put("type", "function");
                    ObjectNode fnNode = MAPPER.createObjectNode();
                    fnNode.put("name", tool.getName());
                    fnNode.put("description", tool.getDescription());
                    fnNode.set("parameters", tool.getParameters());
                    toolNode.set("function", fnNode);
                    toolsNode.add(toolNode);
                }
                body.set("tools", toolsNode);
            }

            // 发送请求
            String url = this.apiBase + "/chat/completions";
            RequestBody reqBody = RequestBody.create(MAPPER.writeValueAsString(body), JSON_TYPE);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(reqBody)
                    .build();

            try (Response response = http.newCall(request).execute()) {
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

    private LlmResponse parseResponse(String json) throws IOException {
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
