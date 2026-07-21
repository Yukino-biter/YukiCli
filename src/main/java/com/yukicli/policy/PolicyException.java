package com.yukicli.policy;

/**
 * 策略层异常 —— PathGuard / CommandGuard 拦截到的越界或危险操作时抛出。
 *
 * ToolRegistry 捕获后转为审计日志 + 返回错误消息给 LLM，不中断 Agent 主循环。
 */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
