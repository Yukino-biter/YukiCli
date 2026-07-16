package com.yukicli.plan;

import java.util.*;

/**
 * 执行计划 - 包含一组有依赖关系的任务（DAG）。
 *
 * 核心能力：
 *   - 拓扑排序（DFS + 环检测）
 *   - 并行批次划分（同层无依赖任务可并行）
 *   - 进度追踪与可视化
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;                    // 计划目标
    private final Map<String, Task> tasks;        // 所有任务
    private final List<String> executionOrder;    // 执行顺序（拓扑排序后）
    private PlanStatus status;
    private String summary;                       // 计划摘要
    private long startTime;
    private long endTime;

    public enum PlanStatus {
        CREATED,      // 刚创建
        RUNNING,      // 执行中
        COMPLETED,    // 全部完成
        FAILED,       // 有任务失败
        CANCELLED     // 被取消
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setSummary(String summary) { this.summary = summary; }
    public void setStatus(PlanStatus status) { this.status = status; }

    // --- 任务管理 ---

    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    public Task getTask(String id) {
        return tasks.get(id);
    }

    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /** 获取根任务（没有依赖的任务） */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    /** 获取可执行的任务（依赖都已完成） */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    // --- 拓扑排序（DFS + 环检测） ---

    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;  // 有环
                }
            }
        }
        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) {
            return false;  // 检测到环
        }
        if (visited.contains(id)) {
            return true;
        }

        visiting.add(id);

        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) {
                    return false;
                }
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
        return true;
    }

    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    // --- 并行批次 ---

    /**
     * 将任务按依赖关系分成多个批次，同批次内的任务可以并行执行。
     */
    public List<List<Task>> getExecutionBatches() {
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<String, Task> remaining = new LinkedHashMap<>(tasks);
        Set<String> completed = new HashSet<>();
        List<List<Task>> batches = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<Task> batch = remaining.values().stream()
                    .filter(task -> completed.containsAll(task.getDependencies()))
                    .toList();

            if (batch.isEmpty()) {
                break;  // 有环或无法推进
            }

            batches.add(batch);
            for (Task task : batch) {
                remaining.remove(task.getId());
                completed.add(task.getId());
            }
        }

        return batches;
    }

    // --- 进度追踪 ---

    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    // --- 可视化 ---

    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append(String.format("  执行计划: %s%n", goal.length() > 40 ? goal.substring(0, 37) + "..." : goal));
        sb.append("========================================\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" :
                    String.join(",", task.getDependencies());

            sb.append(String.format("  %d. %s [%s] %s%n", i + 1, statusIcon, task.getId(), task.getDescription()));
            sb.append(String.format("     类型: %s | 依赖: %s%n", task.getType(), deps));
        }

        sb.append("========================================\n");
        sb.append(String.format("  进度: %.0f%% | 状态: %s%n", getProgress() * 100, status));
        return sb.toString();
    }

    /** 折叠摘要，避免完整 DAG 占满终端 */
    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        List<Task> readyTasks = getExecutableTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("计划摘要\n");
        sb.append("   - 目标: ").append(compactGoal(goal, 48)).append('\n');
        sb.append("   - 任务数: ").append(tasks.size())
                .append(" | 并行批次: ").append(batches.size())
                .append(" | 当前可执行: ").append(readyTasks.size())
                .append(" | 状态: ").append(status).append('\n');

        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0), 5)).append('\n');
            if (batches.size() > 1) {
                sb.append("   - 最终批次: ")
                        .append(formatTaskList(batches.get(batches.size() - 1), 5))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private String compactGoal(String rawGoal, int maxLength) {
        String single = rawGoal.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim().replaceAll(" {2,}", " ");
        if (single.length() <= maxLength) return single;
        return single.substring(0, maxLength - 3) + "...";
    }

    private String formatTaskList(List<Task> batch, int limit) {
        if (batch.isEmpty()) return "无";
        List<String> ids = batch.stream().map(Task::getId).toList();
        if (ids.size() <= limit) return String.join(", ", ids);
        return String.join(", ", ids.subList(0, limit)) + " 等 " + ids.size() + " 个任务";
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "[ ]";
            case RUNNING -> "[>]";
            case COMPLETED -> "[v]";
            case FAILED -> "[x]";
            case SKIPPED -> "[-]";
        };
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)", id, goal, tasks.size(), status);
    }
}
