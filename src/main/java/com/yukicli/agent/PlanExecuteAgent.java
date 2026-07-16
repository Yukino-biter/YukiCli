package com.yukicli.agent;

import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;
import com.yukicli.llm.ToolCall;
import com.yukicli.plan.ExecutionPlan;
import com.yukicli.plan.Planner;
import com.yukicli.plan.Task;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行。
 *
 * 流程：
 *   1. Planner 用 LLM 将目标分解为 DAG 任务计划
 *   2. 用户审查计划（可执行/补充/取消）
 *   3. 按 DAG 依赖批次执行任务（同批次并行）
 *   4. 失败时自动重新规划（进度 < 50%）
 *   5. 每个任务内部是一个 mini ReAct 循环（可多轮工具调用）
 */
public class PlanExecuteAgent {

    private static final int MAX_TASK_ITERATIONS = 5; // 单任务最大工具调用轮次

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final Renderer renderer;
    private final PrintStream out;

    /** 计划审查回调 */
    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,     // 执行
        SUPPLEMENT,  // 补充要求后重新规划
        CANCEL       // 取消
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Renderer renderer) {
        this(llmClient, toolRegistry, renderer, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Renderer renderer,
                            PlanReviewHandler reviewHandler) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.planner = new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
    }

    private final PlanReviewHandler reviewHandler;

    /**
     * 运行任务（规划 + 执行）。
     */
    public String run(String userInput) {
        try {
            ExecutionPlan plan = planner.createPlan(userInput);
            return reviewAndExecutePlan(plan);
        } catch (Exception e) {
            String msg = "执行失败: " + e.getMessage();
            renderer.error(msg);
            return msg;
        }
    }

    /**
     * 审查并执行计划。
     */
    private String reviewAndExecutePlan(ExecutionPlan plan) throws IOException {
        while (true) {
            // 显示计划摘要
            out.println(plan.summarize());

            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return executePlan(plan);
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return "已取消本次计划执行。";
            }

            // SUPPLEMENT：重新规划
            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return executePlan(plan);
            }

            out.println("已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    /**
     * 执行计划。
     */
    private String executePlan(ExecutionPlan plan) throws IOException {
        out.println("\n开始执行计划...\n");
        plan.markStarted();

        StringBuilder finalResult = new StringBuilder();

        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            // 执行当前批次
            List<TaskExecResult> batchResults = executeTaskBatch(plan, executableTasks);

            for (TaskExecResult result : batchResults) {
                Task task = result.task();

                if (result.error() == null) {
                    // 成功
                    task.markCompleted(result.result());
                    String preview = result.result() == null || result.result().isBlank()
                            ? "" : result.result().substring(0, Math.min(100, result.result().length()));
                    out.println("完成 [" + task.getId() + "]" + (preview.isEmpty() ? "" : ": " + preview) + "\n");
                } else {
                    // 失败
                    task.markFailed(result.error().getMessage());
                    out.println("失败 [" + task.getId() + "]: " + result.error().getMessage() + "\n");

                    // 进度 < 50% 时尝试重新规划
                    if (plan.getProgress() < 0.5) {
                        out.println("尝试重新规划...\n");
                        ExecutionPlan replanned = planner.replan(plan, result.error().getMessage());
                        return reviewAndExecutePlan(replanned);
                    }

                    if (!finalResult.isEmpty()) {
                        finalResult.append("\n");
                    }
                    finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(result.error().getMessage());
                }
            }
        }

        // 收尾
        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty() ? buildFinalResult(plan) : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            return "计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        return "计划执行完成！\n" + (planSummary.isBlank() ? "" : planSummary);
    }

    /** 获取可执行任务（按拓扑顺序） */
    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    /** 执行一个批次（单任务直接执行，多任务并行） */
    private List<TaskExecResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            out.println("执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();
            try {
                return List.of(new TaskExecResult(task, executeTask(plan.getGoal(), plan, task), null));
            } catch (Exception e) {
                return List.of(new TaskExecResult(task, null, e));
            }
        }

        // 多任务并行
        String ids = executableTasks.stream().map(Task::getId).collect(Collectors.joining(", "));
        out.println("本轮并行执行 " + executableTasks.size() + " 个任务: " + ids);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "yukicli-plan-executor");
            t.setDaemon(true);
            return t;
        });

        try {
            // 缓冲各任务输出，避免并行交错
            Map<String, java.io.ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecResult>> futures = new ArrayList<>();

            for (Task task : executableTasks) {
                out.println("  并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

                futures.add(executor.submit(() -> {
                    try {
                        return new TaskExecResult(task, executeTaskWithStream(plan.getGoal(), plan, task, taskOut), null);
                    } catch (Exception e) {
                        return new TaskExecResult(task, null, e);
                    }
                }));
            }

            List<TaskExecResult> results = new ArrayList<>();
            for (Future<TaskExecResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new TaskExecResult(executableTasks.get(results.size()), null, e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception ex ? ex : new RuntimeException(cause);
                    results.add(new TaskExecResult(executableTasks.get(results.size()), null, error));
                }
            }

            // 按顺序 flush 缓冲区
            for (Task task : executableTasks) {
                java.io.ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单任务执行（输出到主 out） */
    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        return executeTaskWithStream(goal, plan, task, out);
    }

    /**
     * 执行单个任务（mini ReAct 循环，支持多轮工具调用）。
     */
    private String executeTaskWithStream(String goal, ExecutionPlan plan, Task task, PrintStream taskOut) throws IOException {
        String systemPrompt = """
                你是 YukiCli 的任务执行 Agent。你正在执行一个计划中的任务。

                可用工具：read_file / write_file / list_dir / execute_command / create_project
                用中文回复。基于工具返回结果给出任务结论。
                """;

        // 构建任务上下文
        String taskContext = buildTaskContext(goal, plan, task);

        List<LlmMessage> messages = new ArrayList<>(Arrays.asList(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(taskContext)
        ));

        StringBuilder allResults = new StringBuilder();

        for (int iteration = 0; iteration < MAX_TASK_ITERATIONS; iteration++) {
            LlmResponse response = llmClient.chat(messages, toolRegistry.getAllTools());

            // 没有工具调用，返回最终结果
            if (!response.hasToolCalls()) {
                if (response.getContent() != null && !response.getContent().isBlank()) {
                    taskOut.println(response.getContent());
                }
                String toolOnly = allResults.toString().trim();
                if (!response.getContent().isBlank() || !toolOnly.isBlank()) {
                    return response.getContent().isBlank() ? toolOnly : response.getContent();
                }
                return toolOnly;
            }

            // 有工具调用：执行并回灌
            if (response.getContent() != null && !response.getContent().isBlank()) {
                taskOut.println(response.getContent());
            }

            messages.add(LlmMessage.assistant(response.getContent(), response.getToolCalls()));

            for (ToolCall toolCall : response.getToolCalls()) {
                renderer.toolCall(toolCall.getName(), toolCall.getArguments());
                String result = toolRegistry.execute(toolCall.getName(), toolCall.getArguments());
                renderer.toolResult(toolCall.getName(), result);
                allResults.append(result).append("\n");
                messages.add(LlmMessage.tool(toolCall.getId(), result));
            }
        }

        return allResults.toString().trim();
    }

    /** 构建任务执行上下文（含依赖任务的结果） */
    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("总目标：").append(goal).append("\n");
        ctx.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            ctx.append("依赖任务：无\n");
        } else {
            ctx.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) continue;
                ctx.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    ctx.append(dep.getResult()).append("\n");
                }
            }
        }

        ctx.append("请执行此任务。");
        return ctx.toString();
    }

    /** 汇总最终结果（取叶子任务的结果） */
    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (task.getResult() == null || task.getResult().isBlank()) continue;
            if (!result.isEmpty()) result.append("\n");
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) return result.toString();

        // fallback：取最后一个有结果的任务
        return plan.getAllTasks().stream()
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

    /** 任务执行结果 */
    private record TaskExecResult(Task task, String result, Exception error) {}
}
