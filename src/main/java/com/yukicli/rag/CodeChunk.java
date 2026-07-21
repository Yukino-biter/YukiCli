package com.yukicli.rag;

/**
 * 代码块 record —— RAG 索引的最小单元。
 *
 * 字段说明：
 *   - filePath    源文件相对路径（相对项目根）
 *   - chunkType   块类型（file / class / method）
 *   - name        块名（文件名 / 类名 / 方法名）
 *   - content     块文本内容
 *   - startLine / endLine 起止行号（1-based）
 */
public record CodeChunk(
        String filePath,
        String chunkType,
        String name,
        String content,
        int startLine,
        int endLine) {

    /** 生成用于 embedding 的文本（带类型标签 + 名字前缀，提高语义检索召回率） */
    public String toEmbeddingText() {
        return "[" + chunkType + ":" + name + "] " + content;
    }

    /** 简短摘要（用于显示） */
    public String toSummary() {
        return filePath + ":" + startLine + "-" + endLine + " (" + chunkType + ":" + name + ")";
    }
}
