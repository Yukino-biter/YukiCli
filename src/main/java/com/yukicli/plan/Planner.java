package com.yukicli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmMessage;
import com.yukicli.llm.LlmResponse;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 规划器 - 使用 LLM 将复杂任务分解为执行计划。
 *
 * 流程：
 *   1. 判断是否为简单任务（直接执行无需规划）
 *   2. 构建 planning prompt，调用 LLM 生成 JSON 计划
 *   3. 解析 JSON 为 ExecutionPlan（含 DAG 依赖）
 *   4. 支持基于失败结果重新规划
 */
public class Planner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final PrintStream out;

    public Planner(LlmClient llmClient) {
        this(llmClient, new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out != null ? out : new PrintStream(System.out, true, StandardCharsets.UTF_8);
    }

    /**
     * 为复杂任务创建执行计划。
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        out.println("\n正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        // 构建 planning prompt
        String systemPrompt = """
                你是一个任务规划专家。你的任务是将复杂目标分解为可执行的步骤。

                请返回 JSON 格式的执行计划，结构如下：
                ```json
                {
                  "summary": "计划摘要",
                  "tasks": [
                    {
                      "id": "1",
                      "description": "任务描述",
                      "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
                      "dependencies": ["其他任务的id"]
                    }
                  ]
                }
                ```

                规则：
                1. 每个任务必须有唯一 id（用数字字符串如 "1", "2"）
                2. dependencies 引用其他任务的 id，表示必须先完成那些任务
                3. 不能有循环依赖
                4. 任务类型：FILE_READ(读文件) / FILE_WRITE(写文件) / COMMAND(执行命令) / ANALYSIS(分析) / VERIFICATION(验证)
                5. 只返回 JSON，不要额外解释
                """;

        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.system(systemPrompt),
                LlmMessage.user("请为以下任务制定执行计划：\n" + goal)
        );

        // 调用 LLM 生成计划（不传工具，纯文本规划）
        LlmResponse response = llmClient.chat(messages, null);
        String planJson = response.getContent();

        // 解析 JSON 计划
        return parsePlan(goal, planJson);
    }

    /**
     * 解析 LLM 生成的计划 JSON。
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        // 清理可能的 markdown 代码块
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = MAPPER.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 第一遍：创建所有任务（不处理依赖，因为可能有前向引用）
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();
            Task.TaskType type = parseTaskType(typeStr);

            plan.addTask(new Task(newId, description, type));
        }

        // 第二遍：建立依赖关系
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        // 计算执行顺序（检测环）
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private Task.TaskType parseTaskType(String typeStr) {
        if (typeStr == null) return Task.TaskType.ANALYSIS;
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 根据执行结果重新规划。
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("\n重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");
        return createPlan(context.toString());
    }

    // --- 简单任务快速通道 ---

    private boolean isSimpleGoal(String goal) {
        if (goal == null || goal.trim().isEmpty()) return false;

        String normalized = goal.trim();
        // 有多步线索的不算简单
        boolean hasMultiStepCue = normalized.contains("然后") || normalized.contains("并且")
                || normalized.contains("再") || normalized.contains("最后")
                || normalized.contains("同时") || normalized.contains("先")
                || normalized.contains("之后") || normalized.contains("接着");
        if (hasMultiStepCue) return false;

        // 短文本 + 单一动作才算简单
        if (normalized.length() > 30) return false;

        return normalized.contains("列出") || normalized.contains("查看")
                || normalized.contains("读取") || normalized.contains("显示")
                || normalized.contains("执行") || normalized.contains("运行");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary("直接执行简单任务：" + goal.trim());
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        plan.computeExecutionOrder();
        return plan;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String n = goal == null ? "" : goal.trim();
        if (n.contains("读取") || n.contains("查看") || n.contains("文件")) return Task.TaskType.FILE_READ;
        if (n.contains("写入") || n.contains("修改") || n.contains("创建文件")) return Task.TaskType.FILE_WRITE;
        if (n.contains("分析") || n.contains("总结")) return Task.TaskType.ANALYSIS;
        if (n.contains("验证") || n.contains("检查")) return Task.TaskType.VERIFICATION;
        return Task.TaskType.COMMAND;
    }
}
