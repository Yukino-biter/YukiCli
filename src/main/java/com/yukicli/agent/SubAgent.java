package com.yukicli.agent;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;
import com.yukicli.llm.ToolCall;
import com.yukicli.memory.ConversationHistoryCompactor;
import com.yukicli.tool.ToolRegistry;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 子代理 - 可配置角色的轻量 Agent。
 *
 * 每个 SubAgent 有独立的角色、系统提示词和对话历史，
 * 但共享 LLM 客户端和工具注册表。
 *
 * 关键设计：
 * - 只有 WORKER 角色调工具；PLANNER 和 REVIEWER 只输出分析结果
 * - 通过注入的 PrintStream 输出（支持并行场景下的输出隔离）
 * - 集成 AgentBudget 三道保险阀，避免异常情况下无限循环
 */
public class SubAgent {

    private static final int MAX_ITERATIONS = 20;

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmMessage> conversationHistory;
    private final ConversationHistoryCompactor historyCompactor;

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.conversationHistory.add(LlmMessage.system(getSystemPrompt()));
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    /** 执行任务 */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        refreshSystemPrompt();
        conversationHistory.add(LlmMessage.user(task.content()));

        AgentBudget budget = AgentBudget.fromSystemProperties();
        int iteration = 0;

        while (iteration < MAX_ITERATIONS) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();
            iteration++;

            // 调 LLM 前评估是否需要压缩
            maybeCompactHistory();

            LlmResponse response;
            try {
                List<com.yukicli.tool.Tool> tools = shouldUseTools() ? toolRegistry.getAllTools() : null;
                response = llmClient.chat(conversationHistory, tools);
            } catch (Exception e) {
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }

            if (!response.hasToolCalls()) {
                conversationHistory.add(LlmMessage.assistant(response.getContent(), null));
                return AgentMessage.result(name, role, response.getContent());
            }

            // 有工具调用：执行并回灌
            conversationHistory.add(LlmMessage.assistant(response.getContent(), response.getToolCalls()));
            budget.recordToolCalls(response.getToolCalls());

            if (response.getContent() != null && !response.getContent().isBlank()) {
                out.println("[" + name + "] " + response.getContent());
            }

            for (ToolCall toolCall : response.getToolCalls()) {
                out.println("[" + name + "] → 调用工具: " + toolCall.getName());
                String result = toolRegistry.execute(toolCall.getName(), toolCall.getArguments());
                String display = result.length() > 500
                        ? result.substring(0, 500) + "...(已截断)" : result;
                out.println("[" + name + "] ← 结果 (" + toolCall.getName() + "): " + display);
                conversationHistory.add(LlmMessage.tool(toolCall.getId(), result));
            }
        }

        return AgentMessage.error(name, role, "达到最大迭代次数 (" + MAX_ITERATIONS + ")");
    }

    /** Worker 接收依赖上下文执行任务 */
    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(),
                enrichedContent, task.type());
        return execute(enrichedTask, out);
    }

    /** Reviewer 专用入口 */
    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        return execute(reviewTask, out);
    }

    /** 清空历史（保留 system prompt） */
    public void clearHistory() {
        LlmMessage systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    /** 只有执行者需要工具；规划者和检查者都只输出分析结果。 */
    private boolean shouldUseTools() {
        return role == AgentRole.WORKER;
    }

    private void maybeCompactHistory() {
        // 简化版：根据消息条数粗略判断（>50 条时触发压缩）
        if (conversationHistory.size() > 50) {
            historyCompactor.compactNow(conversationHistory);
        }
    }

    private void refreshSystemPrompt() {
        // 简化版：保留初始 system prompt
    }

    /** 根据角色构建 system prompt */
    private String getSystemPrompt() {
        String base = "你是 YukiCli，一个运行在终端的 AI 编程助手（类似 Claude Code）。\n" +
                "请用中文回复。\n\n";

        return switch (role) {
            case PLANNER -> base + PLANNER_PROMPT;
            case WORKER -> base + WORKER_PROMPT;
            case REVIEWER -> base + REVIEWER_PROMPT;
        };
    }

    private static final String PLANNER_PROMPT = """
            ## Mode: Team Planner

            你是 Multi-Agent 协作中的任务规划专家。你的职责是分析用户需求，将其拆解为清晰的执行步骤。

            请按以下 JSON 格式输出执行计划：

            ```json
            {
              "summary": "任务摘要",
              "steps": [
                {
                  "id": "step_1",
                  "description": "步骤描述，要具体明确",
                  "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
                  "dependencies": []
                }
              ]
            }
            ```

            规则：
            1. 每个步骤必须有唯一 id，如 `step_1`、`step_2`。
            2. `dependencies` 列出依赖的步骤 id。
            3. 步骤描述要具体，让执行者能直接理解。
            4. 简单任务可以只拆成 1-3 步。复杂任务拆成 5-10 步。
            5. 多个步骤可以独立完成时，不要添加依赖，保持 `dependencies` 为空，让编排器并行分配。
            6. 只有后一步确实需要前一步结果时，才写 dependencies。

            只输出 JSON，不要有其他内容。
            """;

    private static final String WORKER_PROMPT = """
            ## Mode: Team Worker

            你是 Multi-Agent 协作中的任务执行专家。你的职责是根据给定任务步骤，调用工具完成具体操作。

            可用工具：read_file / write_file / list_dir / execute_command / create_project

            如果是 `ANALYSIS` 或 `VERIFICATION` 类型任务，且上下文已经足够，请直接输出分析结果。
            用中文回复。基于工具返回结果给出任务结论。
            """;

    private static final String REVIEWER_PROMPT = """
            ## Mode: Team Reviewer

            你是 Multi-Agent 协作中的质量检查专家。你的职责是检查执行结果是否正确、完整和高质量。

            检查要点：
            1. 任务是否按要求完成。
            2. 结果是否正确，有无明显错误。
            3. 是否遗漏重要步骤或细节。
            4. 输出格式是否规范。

            请以 JSON 格式输出检查结果：

            ```json
            {
              "approved": true,
              "summary": "检查摘要",
              "issues": [],
              "suggestions": []
            }
            ```

            如果 `approved` 为 true，`issues` 为空即可。如果 `approved` 为 false，请详细说明问题并给出改进建议。
            只输出 JSON，不要有其他内容。
            """;

    /** 转换为 PrintStream（UTF-8）的便捷方法，用于并行场景下的隔离输出 */
    public static PrintStream utf8PrintStream(java.io.ByteArrayOutputStream baos) {
        return new PrintStream(baos, true, StandardCharsets.UTF_8);
    }
}
