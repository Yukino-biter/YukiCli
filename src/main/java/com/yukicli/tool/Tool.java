package com.yukicli.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 工具接口 —— Agent 可调用的所有能力都实现此接口。
 * 每个工具声明自己的名称、描述和参数 JSON Schema，由 ToolRegistry 统一注册管理。
 */
public interface Tool {

    /** 工具唯一名称，LLM 通过此名称调用 */
    String getName();

    /** 工具描述，帮助 LLM 理解何时使用 */
    String getDescription();

    /** 参数 JSON Schema（OpenAI function calling 格式） */
    JsonNode getParameters();

    /**
     * 执行工具。
     *
     * @param args 从 LLM arguments JSON 解析出的参数 map
     * @return 执行结果文本（回灌给 LLM 作为 observation）
     */
    String execute(Map<String, Object> args);
}
