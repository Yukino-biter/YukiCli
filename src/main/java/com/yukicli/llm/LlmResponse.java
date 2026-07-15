package com.yukicli.llm;

import java.util.List;

/**
 * LLM 一次 chat 调用的响应。
 * content 为文本回复，toolCalls 为工具调用请求列表（可能同时存在）。
 */
public class LlmResponse {

    private final String content;
    private final List<ToolCall> toolCalls;

    public LlmResponse(String content, List<ToolCall> toolCalls) {
        this.content = content;
        this.toolCalls = toolCalls;
    }

    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls != null ? toolCalls : List.of(); }
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
