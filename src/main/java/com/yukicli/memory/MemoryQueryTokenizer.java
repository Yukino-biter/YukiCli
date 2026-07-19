package com.yukicli.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 检索分词器。
 *
 * 简化实现：中文按单字切分，英文按非字母数字边界切分。
 * 过滤单字符的非中文 token（标点、空白等），保留有意义的关键词用于匹配。
 *
 * 这样可以避免引入 jieba 等外部依赖，同时仍能对中英混合查询做合理的关键词匹配。
 */
final class MemoryQueryTokenizer {

    private MemoryQueryTokenizer() {
    }

    /** 对查询文本进行分词，返回用于检索匹配的 token 集合。 */
    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.toLowerCase(Locale.ROOT).trim();
        StringBuilder englishBuf = new StringBuilder();

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (isChineseChar(c)) {
                // 先把缓冲的英文词 flush
                flushEnglish(englishBuf, tokens);
                englishBuf.setLength(0);
                // 中文字符直接作为单字 token
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                englishBuf.append(c);
            } else {
                flushEnglish(englishBuf, tokens);
                englishBuf.setLength(0);
            }
        }
        flushEnglish(englishBuf, tokens);
        return tokens;
    }

    /** 检查文本中是否包含任意一个 query token（子串匹配）。 */
    static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void flushEnglish(StringBuilder buf, Set<String> tokens) {
        if (buf.length() >= 2) {
            tokens.add(buf.toString());
        }
    }

    private static boolean isChineseChar(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
