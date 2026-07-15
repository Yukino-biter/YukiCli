package com.yukicli.agent;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;
import com.yukicli.llm.ToolCall;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent —— 核心执行循环。
 *
 * ReAct = Reasoning + Acting：
 *   1. 思考（Reasoning）：调用 LLM，LLM 返回文本回复和/或工具调用
 *   2. 行动（Acting）：执行 LLM 请求的工具调用，将结果回灌到对话历史
 *   3. 观察（Observation）：工具结果作为新的上下文，继续下一轮思考
 *
 * 循环终止条件：LLM 不再请求工具调用，返回纯文本回复。
 */
public class Agent {

    private static final int MAX_ITERATIONS = 20; // 防止无限循环

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Renderer renderer;

    private final List<LlmMessage> conversationHistory;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, Renderer renderer, String systemPrompt) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(LlmMessage.system(systemPrompt));
    }

    /**
     * 处理用户输入，运行 ReAct 循环。
     *
     * @param userInput 用户输入文本
     */
    public void run(String userInput) {
        // 将用户输入加入对话历史
        conversationHistory.add(LlmMessage.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // === Reasoning：调用 LLM 思考 ===
            LlmResponse response = llmClient.chat(conversationHistory, toolRegistry.getAllTools());

            // 如果没有工具调用，LLM 给出最终回复，循环结束
            if (!response.hasToolCalls()) {
                conversationHistory.add(LlmMessage.assistant(response.getContent(), null));
                renderer.assistant(response.getContent());
                return;
            }

            // === Acting：执行工具调用 ===
            // 先把 assistant 消息（含 tool_calls）加入历史
            conversationHistory.add(LlmMessage.assistant(response.getContent(), response.getToolCalls()));

            // 如果有文本内容也展示
            if (response.getContent() != null && !response.getContent().isBlank()) {
                renderer.assistant(response.getContent());
            }

            // 执行每个工具调用
            for (ToolCall toolCall : response.getToolCalls()) {
                renderer.toolCall(toolCall.getName(), toolCall.getArguments());

                // 执行工具
                String result = toolRegistry.execute(toolCall.getName(), toolCall.getArguments());

                renderer.toolResult(toolCall.getName(), result);

                // === Observation：工具结果回灌对话历史 ===
                conversationHistory.add(LlmMessage.tool(toolCall.getId(), result));
            }
            // 继续下一轮循环，让 LLM 基于工具结果继续思考
        }

        renderer.error("达到最大迭代次数 (" + MAX_ITERATIONS + ")，强制终止。");
    }

    /** 获取对话历史（用于 /export 等功能） */
    public List<LlmMessage> getHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /** 清空对话历史（保留 system prompt） */
    public void clearHistory() {
        LlmMessage system = conversationHistory.isEmpty() ? null : conversationHistory.get(0);
        conversationHistory.clear();
        if (system != null) {
            conversationHistory.add(system);
        }
    }
}
