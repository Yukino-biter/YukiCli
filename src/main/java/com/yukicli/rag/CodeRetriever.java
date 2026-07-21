package com.yukicli.rag;

import com.yukicli.memory.MemoryQueryTokenizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代码检索器 —— RAG 检索入口。
 *
 * 三种检索模式：
 *   - semanticSearch  纯语义检索（embedding 余弦相似度）
 *   - keywordSearch   纯关键词检索（文件名/块名/内容包含匹配）
 *   - hybridSearch    混合检索（语义 + 关键词融合，推荐默认）
 *
 * 混合检索算法（与 paicli 对齐）：
 *   1. 语义检索取 topK*2
 *   2. 关键词检索按 token 分词后逐 token 查
 *   3. 合并去重，双重命中 +0.1 加分（只给一次）
 *   4. 关键词命中位置加分：name +0.3，file +0.1，content +0.1
 *   5. 类型加分：method +0.15，class +0.10
 *   6. 同文件最多保留 2 条，总数限 topK
 */
public class CodeRetriever implements AutoCloseable {

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;

    public CodeRetriever(String projectPath) {
        this(projectPath, new EmbeddingClient());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) {
        this.vectorStore = new VectorStore(projectPath);
        this.embeddingClient = embeddingClient;
    }

    public boolean hasIndex() {
        return vectorStore.hasIndex();
    }

    public VectorStore.IndexStats getStats() {
        return vectorStore.getStats();
    }

    public void clearIndex() {
        vectorStore.clearProject();
    }

    /** 语义检索 */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        if (!vectorStore.hasIndex()) return List.of();
        float[] emb = embeddingClient.embed(query);
        return vectorStore.search(emb, topK);
    }

    /** 关键词检索 */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) {
        if (!vectorStore.hasIndex()) return List.of();
        return vectorStore.searchByKeyword(keyword);
    }

    /** 混合检索（推荐默认） */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        if (!vectorStore.hasIndex()) return List.of();

        // 1. 语义检索 topK*2
        List<VectorStore.SearchResult> semantic = semanticSearch(query, topK * 2);

        // 2. 关键词检索（按 token 分词后逐 token 查）
        List<String> tokens = new ArrayList<>(MemoryQueryTokenizer.tokenize(query));
        Set<String> seenKeys = new HashSet<>();
        List<VectorStore.SearchResult> merged = new ArrayList<>();

        // 用 LinkedHashMap 保留插入顺序，key = filePath + name
        java.util.Map<String, VectorStore.SearchResult> byKey = new java.util.LinkedHashMap<>();

        // 把语义结果先放入
        for (VectorStore.SearchResult r : semantic) {
            String key = r.filePath() + "|" + r.name();
            byKey.put(key, r);
        }

        // 关键词命中加分
        for (String token : tokens) {
            if (token.length() < 2) continue;
            List<VectorStore.SearchResult> kwResults = vectorStore.searchByKeyword(token);
            for (VectorStore.SearchResult r : kwResults) {
                String key = r.filePath() + "|" + r.name();
                VectorStore.SearchResult existing = byKey.get(key);
                double bonus = 0.0;
                if (existing != null) {
                    // 双重命中 +0.1（只给一次）
                    if (!seenKeys.contains(key)) {
                        bonus += 0.1;
                        seenKeys.add(key);
                    }
                }
                // 关键词命中位置加分
                if (r.name().toLowerCase().contains(token.toLowerCase())) bonus += 0.3;
                if (r.filePath().toLowerCase().contains(token.toLowerCase())) bonus += 0.1;
                if (r.content().toLowerCase().contains(token.toLowerCase())) bonus += 0.1;
                // 类型加分
                if (r.chunkType().equals("method")) bonus += 0.15;
                else if (r.chunkType().equals("class")) bonus += 0.10;

                double newSim = Math.max(existing != null ? existing.similarity() : 0, r.similarity()) + bonus;
                byKey.put(key, new VectorStore.SearchResult(
                    r.filePath(), r.chunkType(), r.name(), r.content(), newSim));
            }
        }

        merged.addAll(byKey.values());
        merged.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        // 同文件最多保留 2 条
        java.util.Map<String, Integer> perFileCount = new java.util.HashMap<>();
        List<VectorStore.SearchResult> filtered = new ArrayList<>();
        for (VectorStore.SearchResult r : merged) {
            int count = perFileCount.getOrDefault(r.filePath(), 0);
            if (count >= 2) continue;
            perFileCount.put(r.filePath(), count + 1);
            filtered.add(r);
            if (filtered.size() >= topK) break;
        }
        return filtered;
    }

    /** 关系图查询 */
    public List<CodeRelation> getRelationGraph(String name) {
        return vectorStore.getRelations(name);
    }

    @Override
    public void close() {
        vectorStore.close();
    }
}
