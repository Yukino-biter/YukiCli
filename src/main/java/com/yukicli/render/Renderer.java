package com.yukicli.render;

/**
 * 渲染器接口 —— 负责所有终端输出。
 * 第 16 期会扩展为三形态（inline 流式 / lanterna 全屏 / plain 兜底），
 * 第一期先用简单的 PlainRenderer。
 */
public interface Renderer {

    /** 输出普通信息 */
    void info(String message);

    /** 输出错误信息 */
    void error(String message);

    /** 输出 LLM 思考内容（assistant 回复） */
    void assistant(String content);

    /** 输出工具调用信息 */
    void toolCall(String toolName, String args);

    /** 输出工具执行结果 */
    void toolResult(String toolName, String result);
}
