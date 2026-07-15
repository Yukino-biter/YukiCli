package com.yukicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 对话消息，兼容 OpenAI Chat Completions 格式。
 * 支持 system / user / assistant / tool 四种角色。
 */
public class LlmMessage {

    private String role;
    private String content;
    private List<ToolCall> toolCalls;   // assistant 消息携带的工具调用
    private String toolCallId;          // tool 角色消息对应的 tool_call_id

    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        LlmMessage msg = new LlmMessage("assistant", content);
        msg.toolCalls = toolCalls;
        return msg;
    }

    public static LlmMessage tool(String toolCallId, String content) {
        LlmMessage msg = new LlmMessage("tool", content);
        msg.toolCallId = toolCallId;
        return msg;
    }

    // --- Getters ---

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls != null ? toolCalls : new ArrayList<>(); }
    public String getToolCallId() { return toolCallId; }
}
