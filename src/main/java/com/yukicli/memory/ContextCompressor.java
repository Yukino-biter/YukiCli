package com.yukicli.memory;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 上下文压缩器 - 当短期记忆过长时，用 LLM 压缩旧条目。
 *
 * 压缩策略（Map-Reduce）：
 * 1. Map：将旧消息分片，每片独立调 LLM 生成摘要
 * 2. Reduce：合并多个摘要为整体摘要
 * 3. 保留最近 N 轮完整消息（不压缩）
 * 4. 压缩后的摘要回注到 ConversationMemory
 */
public class ContextCompressor {

    private LlmClient llmClient;
    private final int retainRecentRounds;

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

            各片段摘要：
            %s

            请用中文输出合并摘要，控制在300字以内。
            """;

    public ContextCompressor(LlmClient llmClient) {
        this(llmClient, 3);
    }

    public ContextCompressor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 压缩对话记忆。
     *
     * @param memory 短期记忆
     * @return 压缩后的摘要，无需压缩时返回 null
     */
    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() <= retainRecentRounds) {
            return null;
        }

        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        // Map 阶段
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (chunkSummaries.isEmpty()) {
            return null;
        }

        // Reduce 阶段
        String finalSummary;
        if (chunkSummaries.size() == 1) {
            finalSummary = chunkSummaries.get(0);
        } else {
            finalSummary = reducePhase(chunkSummaries);
        }

        // 清空旧记忆，注入摘要，回注近期记忆
        memory.clear();
        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                "[历史对话摘要] " + finalSummary,
                MemoryEntry.MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(finalSummary)
        );
        memory.store(summaryEntry);

        for (MemoryEntry entry : recentEntries) {
            memory.store(entry);
        }

        return finalSummary;
    }

    /** Map 阶段：将旧消息分片，每片独立摘要 */
    private List<String> mapPhase(List<MemoryEntry> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5;
        List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);

        for (List<MemoryEntry> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            for (MemoryEntry entry : chunk) {
                chunkText.append(entry.getType()).append(": ")
                        .append(entry.getContent()).append("\n\n");
            }

            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<LlmMessage> messages = List.of(
                        LlmMessage.system("你是一个对话摘要助手。"),
                        LlmMessage.user(prompt)
                );
                LlmResponse response = llmClient.chat(messages, null);
                summaries.add(response.getContent());
            } catch (Exception e) {
                // 降级：截取前 200 字作为伪摘要
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }
        return summaries;
    }

    /** Reduce 阶段：合并多个摘要 */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);
        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<LlmMessage> messages = List.of(
                    LlmMessage.system("你是一个摘要合并助手。"),
                    LlmMessage.user(prompt)
            );
            LlmResponse response = llmClient.chat(messages, null);
            return response.getContent();
        } catch (Exception e) {
            return String.join("；", summaries);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
