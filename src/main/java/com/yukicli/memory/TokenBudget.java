package com.yukicli.memory;

import com.yukicli.llm.LlmMessage;

import java.util.List;

/**
 * Token 预算管理器 - 确保对话不会超出模型的上下文窗口。
 *
 * 策略：
 * 1. 设定总 token 预算（系统提示 + 工具定义 + 对话历史 + 回复预留）
 * 2. 每次调用 LLM 前检查预算
 * 3. 超出预算时触发压缩或裁剪
 *
 * 累计统计：调用次数、输入/输出 token、cached token。
 */
public class TokenBudget {

    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.llmCallCount = 0;
    }

    /** 获取对话历史可用的 token 预算 */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /** 检查给定的消息列表是否在预算内 */
    public boolean isWithinBudget(List<LlmMessage> messages) {
        int estimatedTokens = estimateMessagesTokens(messages);
        return estimatedTokens <= getAvailableForConversation();
    }

    /** 检查是否需要压缩，触发率默认 0.9 */
    public boolean needsCompression(ConversationMemory memory) {
        return needsCompression(memory, 0.9);
    }

    public boolean needsCompression(ConversationMemory memory, double triggerRatio) {
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * triggerRatio;
    }

    /** 记录一次 LLM 调用的 token 消耗 */
    public void recordUsage(int inputTokens, int outputTokens) {
        totalInputTokens += Math.max(0, inputTokens);
        totalOutputTokens += Math.max(0, outputTokens);
        llmCallCount++;
    }

    /** 获取 token 使用统计 */
    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getLlmCallCount() { return llmCallCount; }

    /** 估算消息列表的 token 总数 */
    public static int estimateMessagesTokens(List<LlmMessage> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (LlmMessage msg : messages) {
            total += MemoryEntry.estimateTokens(msg.getContent());
            if (msg.getToolCalls() != null) {
                for (com.yukicli.llm.ToolCall tc : msg.getToolCalls()) {
                    total += MemoryEntry.estimateTokens(tc.getArguments());
                }
            }
        }
        // 每条消息额外开销约 4 tokens（role、separator 等）
        total += messages.size() * 4;
        return total;
    }
}
