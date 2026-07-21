package com.yukicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukicli.policy.AuditLog;
import com.yukicli.policy.PathGuard;
import com.yukicli.policy.PolicyException;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 工具注册表 —— 所有工具的注册、查找与执行入口。
 *
 * 集成策略层：
 *   - 持有 {@link PathGuard} 供文件类工具通过 {@link #resolveSafePath(String)} 拿到受围栏约束的路径
 *   - 持有 {@link AuditLog} 记录所有危险工具调用（write_file / execute_command / create_project）
 *   - {@link com.yukicli.policy.PolicyException} 统一在这里捕获，写 denyByPolicy 审计 + 返回错误消息
 *
 * 第 7 期并行执行入口：{@link #executeAllParallel}
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 危险工具集合：成功执行后也要写 allow 审计 */
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project");

    /** 并行执行最大线程数（同时执行的工具调用上限） */
    private static final int MAX_PARALLEL_TOOLS = 4;
    /** 单批次默认超时（秒） */
    private static final long DEFAULT_BATCH_TIMEOUT_SECONDS = 90;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final PathGuard pathGuard;
    private final AuditLog auditLog;

    // 并行执行配置（第 7 期）
    private volatile boolean parallelEnabled = true;
    private volatile long batchTimeoutSeconds = DEFAULT_BATCH_TIMEOUT_SECONDS;

    public ToolRegistry() {
        this(System.getProperty("user.dir"));
    }

    public ToolRegistry(String projectRoot) {
        this.pathGuard = new PathGuard(projectRoot);
        this.auditLog = new AuditLog();
    }

    public void register(Tool tool) {
        // 自动向 AbstractTool 子类注入 PathGuard
        if (tool instanceof AbstractTool at) {
            at.setPathGuard(pathGuard);
        }
        tools.put(tool.getName(), tool);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public PathGuard getPathGuard() {
        return pathGuard;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /** 更新项目根（切换工作目录时调用） */
    public void setProjectPath(String projectRoot) {
        pathGuard.setRootPath(projectRoot);
    }

    /** 文件类工具调用此方法解析路径，越界直接抛 PolicyException */
    public Path resolveSafePath(String input) {
        return pathGuard.resolveSafe(input);
    }

    /**
     * 执行工具调用。
     *
     * @param toolName  工具名称
     * @param arguments JSON 字符串参数
     * @return 执行结果文本
     */
    @SuppressWarnings("unchecked")
    public String execute(String toolName, String arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "[error] 未知工具: " + toolName;
        }
        Map<String, Object> args;
        try {
            args = MAPPER.readValue(arguments, Map.class);
        } catch (Exception e) {
            return "[error] 参数解析失败 (" + toolName + "): " + e.getMessage();
        }

        long start = System.nanoTime();
        try {
            String result = tool.execute(args);
            long elapsedMs = elapsedMs(start);

            // 危险工具成功执行后写 allow 审计
            if (AUDIT_TOOLS.contains(toolName)) {
                auditLog.record(AuditLog.AuditEntry.allow(toolName, arguments, elapsedMs));
            }
            return result;
        } catch (PolicyException e) {
            long elapsedMs = elapsedMs(start);
            auditLog.record(AuditLog.AuditEntry.denyByPolicy(toolName, arguments, e.getMessage(), elapsedMs));
            return "🛡️ 策略拒绝: " + e.getMessage();
        } catch (Exception e) {
            long elapsedMs = elapsedMs(start);
            auditLog.record(AuditLog.AuditEntry.error(toolName, arguments, e.getMessage(), elapsedMs));
            return "[error] 工具执行失败 (" + toolName + "): " + e.getMessage();
        }
    }

    /**
     * 批量执行多个工具调用（同步串行，保留兼容）。
     * 第 7 期推荐使用 {@link #executeAllParallel} 并行执行。
     */
    public Map<String, String> executeAll(List<ToolCallEntry> calls) {
        Map<String, String> results = new LinkedHashMap<>();
        for (ToolCallEntry call : calls) {
            String result = execute(call.name(), call.arguments());
            results.put(call.id(), result);
        }
        return results;
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用（第 7 期）。
     *
     * 特性：
     *   - 单工具调用走快速路径，不走线程池
     *   - 多工具调用用固定线程池（MAX_PARALLEL_TOOLS）+ invokeAll 超时
     *   - 结果按传入顺序返回（与 LLM 的 tool_call 顺序一致，符合 function calling 协议）
     *   - 单个工具超时不影响其他已完成工具；超时工具返回超时结果
     *   - 并行开关关闭时退化为串行
     *
     * @param invocations 工具调用列表
     * @return 有序结果列表（与输入顺序一致）
     */
    public List<ToolExecutionResult> executeAllParallel(List<ToolCallEntry> invocations) {
        if (invocations == null || invocations.isEmpty()) return List.of();

        // 单工具快速路径
        if (invocations.size() == 1) {
            ToolCallEntry inv = invocations.get(0);
            long start = System.nanoTime();
            String result = execute(inv.name(), inv.arguments());
            return List.of(ToolExecutionResult.completed(inv, result, elapsedMs(start)));
        }

        // 并行关闭时退化为串行
        if (!parallelEnabled) {
            List<ToolExecutionResult> results = new ArrayList<>();
            for (ToolCallEntry inv : invocations) {
                long start = System.nanoTime();
                String result = execute(inv.name(), inv.arguments());
                results.add(ToolExecutionResult.completed(inv, result, elapsedMs(start)));
            }
            return results;
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "yukicli-tool-executor");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = new ArrayList<>();
            for (ToolCallEntry inv : invocations) {
                tasks.add(() -> {
                    long start = System.nanoTime();
                    String result = execute(inv.name(), inv.arguments());
                    return ToolExecutionResult.completed(inv, result, elapsedMs(start));
                });
            }

            List<Future<ToolExecutionResult>> futures = executor.invokeAll(tasks, batchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolCallEntry inv = invocations.get(i);
                Future<ToolExecutionResult> f = futures.get(i);
                if (f.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(inv, batchTimeoutSeconds));
                    continue;
                }
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    String msg = e.getCause() == null ? "未知错误" : e.getCause().getMessage();
                    results.add(ToolExecutionResult.failed(inv, msg));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(inv, "工具执行被中断"));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                .map(inv -> ToolExecutionResult.failed(inv, "工具批次执行被中断"))
                .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 工具调用条目（id + name + arguments） */
    public record ToolCallEntry(String id, String name, String arguments) {}

    public boolean isParallelEnabled() { return parallelEnabled; }
    public void setParallelEnabled(boolean enabled) { this.parallelEnabled = enabled; }
    public long getBatchTimeoutSeconds() { return batchTimeoutSeconds; }
    public void setBatchTimeoutSeconds(long seconds) { this.batchTimeoutSeconds = seconds; }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
