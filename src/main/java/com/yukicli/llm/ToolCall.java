package com.yukicli.llm;

/**
 * LLM 返回的工具调用请求。
 * 对应 OpenAI 格式中的 tool_calls[].function。
 */
public class ToolCall {

    private final String id;
    private final String name;
    private final String arguments; // JSON 字符串

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getArguments() { return arguments; }
}
