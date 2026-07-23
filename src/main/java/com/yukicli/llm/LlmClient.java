package com.yukicli.llm;

import com.yukicli.tool.Tool;

import java.util.List;

/**
 * LLM 客户端抽象接口。
 *
 * 第 8 期扩展：增加 getModelName / getProviderName / maxContextWindow 等能力描述方法，
 * 让上层（Agent / MemoryManager / TokenBudget）能按模型能力做差异化处理。
 *
 * 实现类继承 {@link AbstractOpenAiCompatibleClient} 模板基类，只需实现抽象方法即可
 * 适配 OpenAI / GLM / DeepSeek / Kimi 等 OpenAI 兼容接口。
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

    /** 当前使用的模型名（如 glm-4-flash / deepseek-chat / gpt-4o） */
    default String getModelName() {
        return "unknown";
    }

    /** provider 标识（glm / deepseek / kimi / openai） */
    default String getProviderName() {
        return "unknown";
    }

    /** 模型最大上下文窗口（token 数），用于 TokenBudget 计算 */
    default int maxContextWindow() {
        return 128_000;
    }

    /** 是否支持 function calling（工具调用） */
    default boolean supportsTools() {
        return true;
    }

    /** 是否支持视觉输入（图片） */
    default boolean supportsImageInput() {
        return false;
    }

    /** 是否支持 prompt 缓存（可降低重复请求成本） */
    default boolean supportsPromptCaching() {
        return false;
    }
}
