package com.yukicli.tool;

/**
 * 工具执行结果 record（第 7 期并行模块）。
 *
 * 封装单次工具调用的结果，便于 {@link ToolRegistry#executeAllParallel} 返回有序列表。
 *
 * 字段说明：
 *   - id            工具调用 ID（与 LLM 返回的 tool_call.id 对应）
 *   - name          工具名
 *   - arguments     原始 JSON 参数字符串
 *   - result        执行结果文本（成功）/ 错误消息（失败）
 *   - elapsedMillis 耗时
 *   - timedOut      是否因超时被取消
 */
public record ToolExecutionResult(
        String id,
        String name,
        String arguments,
        String result,
        long elapsedMillis,
        boolean timedOut) {

    public static ToolExecutionResult completed(ToolRegistry.ToolCallEntry invocation, String result, long elapsedMs) {
        return new ToolExecutionResult(invocation.id(), invocation.name(), invocation.arguments(),
            result, elapsedMs, false);
    }

    public static ToolExecutionResult failed(ToolRegistry.ToolCallEntry invocation, String message) {
        return new ToolExecutionResult(invocation.id(), invocation.name(), invocation.arguments(),
            "[error] " + message, 0, false);
    }

    public static ToolExecutionResult timedOut(ToolRegistry.ToolCallEntry invocation, long timeoutSeconds) {
        return new ToolExecutionResult(invocation.id(), invocation.name(), invocation.arguments(),
            "[error] 工具执行超时（" + timeoutSeconds + "秒），已取消", 0, true);
    }
}
