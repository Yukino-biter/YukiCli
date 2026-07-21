package com.yukicli.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分块器 —— 把源文件切成可被 embedding 的块。
 *
 * 简化策略（无 javaparser 依赖）：
 *   - Java 文件：用正则识别 class / method 签名，按大括号配对提取方法体
 *   - 非 Java 文件：按 MAX_CHUNK_CHARS 字符分段
 *
 * 块类型：
 *   - file   整个文件（仅当文件较短时）
 *   - class  类头（类声明 + 前 5 行）
 *   - method 方法体（含签名行）
 */
public class CodeChunker {

    private static final int MAX_CHUNK_CHARS = 2000;
    private static final int MIN_METHOD_LINES = 3;

    // 类声明：class XXX / interface XXX / enum XXX
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "\\b(?:public|private|protected|abstract|final|static)?\\s*"
        + "(?:class|interface|enum|record)\\s+(\\w+)");

    // 方法签名：可选修饰符 + 返回类型 + 方法名 + (
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "^\\s*(?:public|private|protected|static|final|abstract|synchronized|native|default)?\\s*"
        + "(?:<[^>]+>\\s*)?"
        + "(?:[\\w.]+\\s+)+"
        + "(\\w+)\\s*\\(",
        Pattern.MULTILINE);

    /** 分块单个文件 */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString().replace('\\', '/');
        // 去掉项目根前缀（粗略：取 src/ 之后或最后一个 src 之后的路径）
        String name = filePath.getFileName().toString();

        List<CodeChunk> chunks = new ArrayList<>();

        if (name.endsWith(".java")) {
            chunkJavaFile(relativePath, content, chunks);
        } else {
            chunkPlainFile(relativePath, content, chunks);
        }

        // 兜底：如果什么都没切出来，整文件作为一个 chunk
        if (chunks.isEmpty() && !content.isBlank()) {
            chunks.add(new CodeChunk(relativePath, "file", name, truncate(content, MAX_CHUNK_CHARS), 1, lineCount(content)));
        }
        return chunks;
    }

    private void chunkJavaFile(String path, String content, List<CodeChunk> out) {
        String[] lines = content.split("\n", -1);

        // 类 chunk：取类声明行 + 前 5 行
        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            int startLine = offsetToLine(content, classMatcher.start());
            int endLine = Math.min(lines.length, startLine + 4);
            StringBuilder sb = new StringBuilder();
            for (int i = startLine - 1; i < endLine; i++) sb.append(lines[i]).append('\n');
            out.add(new CodeChunk(path, "class", className, sb.toString(), startLine, endLine));
        }

        // 方法 chunk：从方法签名行开始，按大括号配对找方法体
        Matcher methodMatcher = METHOD_PATTERN.matcher(content);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            int startLine = offsetToLine(content, methodMatcher.start());

            // 跳过字段声明（以 ; 结尾的"假方法"）和 if/for/while/switch 控制语句
            String line = lines[startLine - 1].trim();
            if (line.startsWith("if") || line.startsWith("for") || line.startsWith("while")
                || line.startsWith("switch") || line.startsWith("catch") || line.startsWith("return")) {
                continue;
            }

            int endLine = findMatchingBrace(lines, startLine - 1);
            if (endLine <= startLine) continue;
            if (endLine - startLine + 1 < MIN_METHOD_LINES) continue;

            // 排除接口/抽象方法（以 ; 结尾没有方法体）
            String methodText = sliceLines(lines, startLine - 1, endLine - 1);
            if (!methodText.contains("{")) continue;

            out.add(new CodeChunk(path, "method",
                extractClassName(path) + "." + methodName,
                truncate(methodText, MAX_CHUNK_CHARS),
                startLine, endLine));
        }

        // 小文件兜底：整文件作为一个 chunk
        if (out.isEmpty() && content.length() <= MAX_CHUNK_CHARS) {
            out.add(new CodeChunk(path, "file", path.substring(path.lastIndexOf('/') + 1),
                content, 1, lines.length));
        }
    }

    private void chunkPlainFile(String path, String content, List<CodeChunk> out) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            out.add(new CodeChunk(path, "file", path.substring(path.lastIndexOf('/') + 1),
                content, 1, lineCount(content)));
            return;
        }
        // 大文件按字符分段（尽量在换行处切）
        int start = 0;
        int lineStart = 1;
        int line = 1;
        while (start < content.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, content.length());
            // 往后找最近的换行
            if (end < content.length()) {
                int nl = content.indexOf('\n', end);
                if (nl > 0 && nl < end + 200) end = nl + 1;
            }
            String chunk = content.substring(start, end);
            int lineEnd = line + countChar(chunk, '\n');
            out.add(new CodeChunk(path, "file",
                path.substring(path.lastIndexOf('/') + 1) + "#" + lineStart,
                chunk, lineStart, lineEnd));
            start = end;
            line = lineEnd + 1;
            lineStart = line;
        }
    }

    /** 从 startIdx 行开始找匹配的右大括号，返回结束行号（1-based） */
    private int findMatchingBrace(String[] lines, int startIdx) {
        int depth = 0;
        boolean entered = false;
        for (int i = startIdx; i < lines.length; i++) {
            String line = lines[i];
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') { depth++; entered = true; }
                else if (c == '}') { depth--; }
                if (entered && depth == 0) return i + 1;
            }
        }
        return startIdx + 1;
    }

    private static int offsetToLine(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String sliceLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to && i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private static String extractClassName(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n// ... (truncated)";
    }

    private static int lineCount(String s) {
        int count = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') count++;
        return count;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }
}
