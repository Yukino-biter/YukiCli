package com.yukicli.render;

/**
 * 纯文本渲染器 —— 直接输出到 stdout。
 * 第一期的默认渲染方式，后续会替换为更丰富的 TUI 形态。
 */
public class PlainRenderer implements Renderer {

    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";

    @Override
    public void info(String message) {
        System.out.println(CYAN + message + RESET);
    }

    @Override
    public void error(String message) {
        System.out.println(RED + "[error] " + message + RESET);
    }

    @Override
    public void assistant(String content) {
        if (content != null && !content.isBlank()) {
            System.out.println(GREEN + content + RESET);
        }
    }

    @Override
    public void toolCall(String toolName, String args) {
        System.out.println(YELLOW + "→ 调用工具: " + toolName + RESET);
        System.out.println(DIM + "  参数: " + args + RESET);
    }

    @Override
    public void toolResult(String toolName, String result) {
        // 结果过长时截断显示
        String display = result.length() > 500 ? result.substring(0, 500) + "\n... (已截断)" : result;
        System.out.println(DIM + "← 结果 (" + toolName + "): " + display + RESET);
    }
}
