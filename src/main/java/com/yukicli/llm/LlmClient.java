package com.yukicli.llm;

import com.yukicli.tool.Tool;

import java.util.List;

/**
 * LLM 客户端抽象接口。
 * 第 8 期会引入 AbstractOpenAiCompatibleClient 模板基类做多模型适配，
 * 第一期先用单一实现 OpenAiCompatibleClient。
 */
public interface LlmClient {

    /**
     * 发送对话请求（含可用工具列表）。
     *
     * @param messages 对话历史
     * @param tools    可用工具列表
     * @return LLM 响应（可能包含文本和/或工具调用）
     */
    LlmResponse chat(List<LlmMessage> messages, List<Tool> tools);
}
