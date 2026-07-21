package com.yukicli.memory;

import com.yukicli.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 管理器 - Memory 系统的门面类。
 *
 * 统一管理短期记忆、长期记忆、上下文压缩和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {

    /** 默认上下文窗口（适用于 8k~32k 模型；大模型可在外部 setContextWindow） */
    private static final int DEFAULT_CONTEXT_WINDOW = 32_000;
    /** 短期记忆默认预算（占窗口的 ~45%） */
    private static final int DEFAULT_SHORT_TERM_BUDGET = 14_000;
    /** 注入 system prompt 的长期记忆 token 上限 */
    private static final int DEFAULT_MEMORY_CONTEXT_TOKENS = 1_000;
    /** 自动压缩触发率（短期记忆占用 / 预算） */
    private static final double DEFAULT_COMPRESSION_TRIGGER_RATIO = 0.9;
    /** 工具结果在记忆中的最大长度 */
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private String currentProject;

    private int contextWindow;
    private int shortTermBudget;
    private int memoryContextTokens;
    private double compressionTriggerRatio;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_SHORT_TERM_BUDGET);
    }

    public MemoryManager(LlmClient llmClient, int contextWindow, int shortTermBudget) {
        this.contextWindow = contextWindow;
        this.shortTermBudget = shortTermBudget;
        this.memoryContextTokens = DEFAULT_MEMORY_CONTEXT_TOKENS;
        this.compressionTriggerRatio = DEFAULT_COMPRESSION_TRIGGER_RATIO;
        this.shortTermMemory = new ConversationMemory(shortTermBudget);
        this.longTermMemory = new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, longTermMemory);
        this.tokenBudget = new TokenBudget(contextWindow);
        this.currentProject = defaultProjectKey();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    /** 添加用户消息到短期记忆 */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /** 添加助手回复到短期记忆 */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /** 添加工具执行结果到短期记忆（截断过长结果） */
    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /** 存储关键事实到长期记忆（项目作用域） */
    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    /** 检索与查询最相关的记忆 */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    /** 构建用于 LLM 的记忆上下文（仅长期记忆，按当前项目过滤） */
    public String buildContextForQuery(String query) {
        return retriever.buildContextForQuery(query, memoryContextTokens, currentProject);
    }

    /**
     * 构建 RAG 上下文（基于 CodeRetriever 的混合检索结果）。
     *
     * Agent 注入 system prompt 时调用：先检索相关代码片段，再格式化为文本。
     * 失败时返回空串，不阻塞主流程（RAG 是增强而非必需）。
     *
     * @param query 用户输入
     * @param maxTokens RAG 上下文 token 上限（用于限制结果数）
     * @return 格式化的 RAG 上下文文本；无索引或失败返回空串
     */
    public String buildRagContext(String query, int maxTokens) {
        return "";  // 由 Agent 通过 CodeRetriever 直接调用，避免在 MemoryManager 引入 RAG 依赖
    }

    /** 记录 token 使用 */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    /**
     * 检查并触发短期记忆压缩。
     *
     * @return 是否执行了压缩
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, compressionTriggerRatio)) {
            return false;
        }
        String summary = compressor.compress(shortTermMemory);
        return summary != null;
    }

    /** 清空短期记忆（保留长期记忆） */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    /** 清空长期记忆 */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /** 获取记忆系统的整体状态 */
    public String getSystemStatus() {
        return "上下文窗口: " + contextWindow + "\n" +
                shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // --- Getter ---

    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public String getCurrentProject() { return currentProject; }
    public int getContextWindow() { return contextWindow; }
    public int getShortTermBudget() { return shortTermBudget; }
    public int getMemoryContextTokens() { return memoryContextTokens; }
    public double getCompressionTriggerRatio() { return compressionTriggerRatio; }
    public int getCompressionTriggerTokens() {
        int compressionBudget = Math.min(shortTermBudget, tokenBudget.getAvailableForConversation());
        return (int) (compressionBudget * compressionTriggerRatio);
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
        this.tokenBudget = new TokenBudget(contextWindow);
    }

    public void setShortTermBudget(int budget) {
        this.shortTermBudget = budget;
        this.shortTermMemory.setMaxTokens(budget);
    }

    public void setMemoryContextTokens(int tokens) {
        this.memoryContextTokens = tokens;
    }

    public void setCompressionTriggerRatio(double ratio) {
        this.compressionTriggerRatio = Math.max(0.5, Math.min(0.99, ratio));
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
