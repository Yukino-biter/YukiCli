package com.yukicli.memory;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;
import com.yukicli.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压缩 ReAct 主循环里的 conversationHistory（即 List<LlmMessage>）。
 *
 * 与 ContextCompressor 的区别：
 * - ContextCompressor 压的是 ConversationMemory（短期记忆条目）
 * - 本类压的是 Agent 实际发给 LLM 的消息列表
 *
 * 算法：
 * 1. 估算 conversationHistory 当前 token，未达 trigger 直接返回 false
 * 2. 找出所有 user message 的索引；保留最近 retainRecentRounds 个 user 起算的尾部
 * 3. 把 system 之后、splitIdx 之前的全部消息喂给 LLM 摘要
 * 4. 重建：[system] + [user("[已压缩的历史对话摘要]\n" + summary)] +
 *         [assistant("好的，已了解上下文。请继续。")] + [尾部保留消息]
 *
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call / tool_result 的成对协议。
 */
public class ConversationHistoryCompactor {

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory
     * @param triggerTokens 触发压缩的 token 阈值
     * @return 是否真的压缩了
     */
    public boolean compactIfNeeded(List<LlmMessage> history, int triggerTokens) {
        return compact(history, triggerTokens, false, retainRecentRounds);
    }

    /** 手动压缩 history，跳过 token 阈值判断 */
    public boolean compactNow(List<LlmMessage> history) {
        return compact(history, 0, true, 1);
    }

    private boolean compact(List<LlmMessage> history, int triggerTokens, boolean force, int retainRounds) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (!force && currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).getRole()) ? 1 : 0;

        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).getRole())) {
                userIndices.add(i);
            }
        }
        int effectiveRetainRounds = Math.max(1, retainRounds);
        if (userIndices.size() <= effectiveRetainRounds) {
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - effectiveRetainRounds);
        if (splitIdx <= systemEnd) return false;

        List<LlmMessage> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (Exception e) {
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }

        List<LlmMessage> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmMessage.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmMessage.assistant("好的，我已了解之前的上下文，请继续。", null));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        history.clear();
        history.addAll(rebuilt);
        return true;
    }

    /** 真正调 LLM 摘要 */
    protected String summarize(List<LlmMessage> messages) {
        if (llmClient == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : messages) {
            sb.append(m.getRole().toUpperCase(Locale.ROOT)).append(": ");
            if (m.getContent() != null) {
                sb.append(m.getContent());
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                for (ToolCall tc : m.getToolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.getName())
                            .append(": ").append(tc.getArguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmMessage> req = List.of(
                LlmMessage.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                LlmMessage.user(prompt)
        );
        LlmResponse response = llmClient.chat(req, null);
        return response == null ? null : response.getContent();
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }
}
