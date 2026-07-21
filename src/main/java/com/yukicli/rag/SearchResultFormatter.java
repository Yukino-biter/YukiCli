package com.yukicli.rag;

import java.util.List;

/**
 * 检索结果格式化器 —— 把 SearchResult 列表格式化为可注入 system prompt 的文本。
 *
 * 输出格式（紧凑，便于 LLM 阅读）：
 *
 *   📦 相关代码片段（3 条）：
 *   [1] src/main/java/com/yukicli/agent/Agent.java:59-109 (method:Agent.run)
 *       <内容前 8 行>
 *   [2] ...
 */
public class SearchResultFormatter {

    private static final int MAX_CONTENT_LINES = 8;
    private static final int MAX_CONTENT_CHARS = 400;

    /** 格式化为 system prompt 注入文本 */
    public static String formatForPrompt(List<VectorStore.SearchResult> results) {
        if (results == null || results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("📦 相关代码片段（").append(results.size()).append(" 条）：\n");
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ")
                .append(r.filePath())
                .append(" (").append(r.chunkType()).append(":").append(r.name()).append(")\n");
            sb.append(indent(truncate(r.content()))).append("\n");
        }
        return sb.toString();
    }

    /** 格式化为终端显示文本（带相似度） */
    public static String formatForDisplay(List<VectorStore.SearchResult> results) {
        if (results == null || results.isEmpty()) return "无匹配结果";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ")
                .append(r.filePath())
                .append(" (").append(r.chunkType()).append(":").append(r.name()).append(")")
                .append(String.format("  sim=%.3f", r.similarity())).append("\n");
            sb.append(indent(truncate(r.content()))).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String content) {
        if (content == null) return "";
        // 按行截取
        String[] lines = content.split("\n", -1);
        if (lines.length > MAX_CONTENT_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_CONTENT_LINES; i++) sb.append(lines[i]).append('\n');
            sb.append("// ... (").append(lines.length - MAX_CONTENT_LINES).append(" more lines)");
            return sb.toString();
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return content.substring(0, MAX_CONTENT_CHARS) + "...";
        }
        return content;
    }

    private static String indent(String s) {
        if (s == null) return "";
        return s.indent(4).stripTrailing();
    }
}
