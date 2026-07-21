package com.yukicli.agent;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;
import com.yukicli.llm.ToolCall;
import com.yukicli.memory.ConversationHistoryCompactor;
import com.yukicli.memory.MemoryManager;
import com.yukicli.rag.CodeRetriever;
import com.yukicli.rag.SearchResultFormatter;
import com.yukicli.rag.VectorStore;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolExecutionResult;
import com.yukicli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent —— 核心执行循环。
 *
 * ReAct = Reasoning + Acting：
 *   1. 思考（Reasoning）：调用 LLM，LLM 返回文本回复和/或工具调用
 *   2. 行动（Acting）：执行 LLM 请求的工具调用，将结果回灌到对话历史
 *   3. 观察（Observation）：工具结果作为新的上下文，继续下一轮思考
 *
 * 循环终止条件：LLM 不再请求工具调用，返回纯文本回复。
 *
 * 集成 MemoryManager：
 *   - 用户输入写入短期记忆
 *   - 检索长期记忆并注入到 system prompt
 *   - 工具结果写入短期记忆（截断 500 字符）
 *   - 最终回复写入短期记忆
 *   - 调 LLM 前评估 conversationHistory 是否需要压缩
 */
public class Agent {

    private static final int MAX_ITERATIONS = 20; // 防止无限循环

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Renderer renderer;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;

    private final List<LlmMessage> conversationHistory;
    private final String baseSystemPrompt;

    // RAG 检索器（可选；未注入时不启用 RAG 上下文注入）
    private CodeRetriever codeRetriever;
    private String projectPath = System.getProperty("user.dir");

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, Renderer renderer, String systemPrompt) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.baseSystemPrompt = systemPrompt;
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(LlmMessage.system(systemPrompt));
    }

    /** 注入 RAG 检索器（启用 RAG 上下文注入） */
    public void setCodeRetriever(CodeRetriever retriever) {
        this.codeRetriever = retriever;
    }

    /** 设置项目根路径 */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.memoryManager.setProjectPath(projectPath);
        if (toolRegistry != null) {
            toolRegistry.setProjectPath(projectPath);
        }
    }

    /**
     * 处理用户输入，运行 ReAct 循环。
     */
    public void run(String userInput) {
        // === 记忆集成 ===
        // 1. 写入短期记忆
        memoryManager.addUserMessage(userInput);

        // 2. 检索相关长期记忆 + RAG 代码片段，注入到 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput);
        String ragContext = buildRagContext(userInput);
        String combined = joinNonBlank(memoryContext, ragContext);
        updateSystemPromptWithMemory(combined);

        // 3. 用户输入加入对话历史
        conversationHistory.add(LlmMessage.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // === 调 LLM 前评估是否压缩对话历史 ===
            maybeCompactHistory();

            // === Reasoning：调用 LLM 思考 ===
            LlmResponse response = llmClient.chat(conversationHistory, toolRegistry.getAllTools());

            // 如果没有工具调用，LLM 给出最终回复，循环结束
            if (!response.hasToolCalls()) {
                conversationHistory.add(LlmMessage.assistant(response.getContent(), null));
                memoryManager.addAssistantMessage(response.getContent());
                renderer.assistant(response.getContent());
                return;
            }

            // === Acting：执行工具调用 ===
            conversationHistory.add(LlmMessage.assistant(response.getContent(), response.getToolCalls()));

            if (response.getContent() != null && !response.getContent().isBlank()) {
                renderer.assistant(response.getContent());
            }

            List<ToolCall> toolCalls = response.getToolCalls();

            if (toolCalls.size() == 1) {
                // 单工具快速路径：直接执行（不走线程池）
                ToolCall tc = toolCalls.get(0);
                renderer.toolCall(tc.getName(), tc.getArguments());
                String result = toolRegistry.execute(tc.getName(), tc.getArguments());
                renderer.toolResult(tc.getName(), result);

                // === Observation：工具结果回灌对话历史 + 短期记忆 ===
                conversationHistory.add(LlmMessage.tool(tc.getId(), result));
                memoryManager.addToolResult(tc.getName(), result);
            } else {
                // 多工具并行执行（第 7 期）
                // 先批量打印工具调用预告
                for (ToolCall tc : toolCalls) {
                    renderer.toolCall(tc.getName(), tc.getArguments());
                }

                // 构造 invocation 列表
                List<ToolRegistry.ToolCallEntry> entries = new ArrayList<>();
                for (ToolCall tc : toolCalls) {
                    entries.add(new ToolRegistry.ToolCallEntry(tc.getId(), tc.getName(), tc.getArguments()));
                }

                // 并行执行（结果按原顺序返回）
                List<ToolExecutionResult> results = toolRegistry.executeAllParallel(entries);

                // 按原顺序回灌（保证 LLM 看到的 tool_result 顺序与 tool_call 一致）
                for (ToolExecutionResult r : results) {
                    renderer.toolResult(r.name(), r.result());
                    conversationHistory.add(LlmMessage.tool(r.id(), r.result()));
                    memoryManager.addToolResult(r.name(), r.result());
                }
            }
        }

        renderer.error("达到最大迭代次数 (" + MAX_ITERATIONS + ")，强制终止。");
    }

    /** 获取对话历史 */
    public List<LlmMessage> getHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /** 清空对话历史（保留 system prompt） + 清空短期记忆 */
    public void clearHistory() {
        LlmMessage system = conversationHistory.isEmpty() ? null : conversationHistory.get(0);
        conversationHistory.clear();
        if (system != null) {
            conversationHistory.add(system);
        }
        memoryManager.clearShortTerm();
    }

    /** 获取 MemoryManager 引用 */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /** 手动触发对话历史压缩（/compact 命令入口） */
    public boolean compactHistoryNow() {
        return historyCompactor.compactNow(conversationHistory);
    }

    /** 调 LLM 前评估对话历史是否接近窗口上限，超阈值时压缩 */
    private void maybeCompactHistory() {
        int trigger = memoryManager.getCompressionTriggerTokens();
        if (trigger <= 0) return;
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer.info("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            // 压缩失败不影响主流程
        }
    }

    /** 用长期记忆上下文更新 system prompt（替换 conversationHistory[0]） */
    private void updateSystemPromptWithMemory(String memoryContext) {
        if (conversationHistory.isEmpty()) return;
        LlmMessage systemMsg = conversationHistory.get(0);
        String prompt = baseSystemPrompt;
        if (memoryContext != null && !memoryContext.isBlank()) {
            prompt = baseSystemPrompt + "\n\n" + memoryContext;
        }
        // 仅当内容变化时替换
        if (!prompt.equals(systemMsg.getContent())) {
            conversationHistory.set(0, LlmMessage.system(prompt));
        }
    }

    /** 构建 RAG 上下文：通过 CodeRetriever 混合检索 top3 代码片段 */
    private String buildRagContext(String userInput) {
        if (codeRetriever == null) return "";
        try {
            if (!codeRetriever.hasIndex()) return "";
            List<VectorStore.SearchResult> results = codeRetriever.hybridSearch(userInput, 3);
            if (results == null || results.isEmpty()) return "";
            return SearchResultFormatter.formatForPrompt(results);
        } catch (Exception e) {
            // RAG 失败不影响主流程
            return "";
        }
    }

    /** 用空行连接两个非空字符串 */
    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) return a + "\n" + b;
        if (hasA) return a;
        if (hasB) return b;
        return "";
    }
}
