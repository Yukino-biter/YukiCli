package com.yukicli.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量存储 —— JSON 文件持久化（替代 SQLite，避免引入 JDBC 依赖）。
 *
 * 文件位置：~/.yukicli/rag/{project-hash}.json
 *   - project-hash 取项目根路径 SHA-256 前 8 位
 *   - 同步写入（与 LongTermMemory 一致）
 *
 * 数据结构：
 *   - chunks: List<{ chunk, embedding }>
 *   - relations: List<CodeRelation>
 *   - indexedAt: 时间戳
 *
 * 检索算法：
 *   - semanticSearch: 余弦相似度 + topK
 *   - keywordSearch: 文件名/块名/内容包含关键词
 */
public class VectorStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path storeFile;
    private final String projectPath;

    // 内存索引
    private final List<CodeChunkEntry> chunks = new ArrayList<>();
    private final List<CodeRelation> relations = new ArrayList<>();
    private String indexedAt = "";

    public VectorStore(String projectPath) {
        this.projectPath = projectPath;
        this.storeFile = resolveStoreFile(projectPath);
        load();
    }

    /** 清空当前项目索引 */
    public void clearProject() {
        chunks.clear();
        relations.clear();
        indexedAt = "";
        save();
    }

    /** 插入代码块（含 embedding） */
    public void insertChunks(List<CodeChunkEntry> entries) {
        chunks.addAll(entries);
        save();
    }

    /** 插入关系 */
    public void insertRelations(List<CodeRelation> rels) {
        relations.addAll(rels);
        save();
    }

    /** 设置索引时间戳 */
    public void setIndexedAt(String ts) {
        this.indexedAt = ts;
        save();
    }

    /** 语义检索：余弦相似度 topK */
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        List<SearchResult> results = new ArrayList<>();
        for (CodeChunkEntry entry : chunks) {
            double sim = cosine(queryEmbedding, entry.embedding());
            results.add(new SearchResult(
                entry.chunk().filePath(),
                entry.chunk().chunkType(),
                entry.chunk().name(),
                entry.chunk().content(),
                sim));
        }
        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    /** 关键词检索：文件名/块名/内容匹配 */
    public List<SearchResult> searchByKeyword(String keyword) {
        String kw = keyword.toLowerCase();
        List<SearchResult> results = new ArrayList<>();
        for (CodeChunkEntry entry : chunks) {
            CodeChunk c = entry.chunk();
            double sim = 0.0;
            if (c.filePath().toLowerCase().contains(kw)) sim = Math.max(sim, 0.6);
            if (c.name().toLowerCase().contains(kw)) sim = Math.max(sim, 0.8);
            if (c.content().toLowerCase().contains(kw)) sim = Math.max(sim, 0.4);
            if (sim > 0) {
                results.add(new SearchResult(c.filePath(), c.chunkType(), c.name(), c.content(), sim));
            }
        }
        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return results;
    }

    /** 查询与指定名字相关的关系图 */
    public List<CodeRelation> getRelations(String name) {
        List<CodeRelation> result = new ArrayList<>();
        for (CodeRelation r : relations) {
            if (r.fromName().equals(name) || r.toName().equals(name)) {
                result.add(r);
            }
        }
        return result;
    }

    public IndexStats getStats() {
        return new IndexStats(chunks.size(), relations.size(), indexedAt);
    }

    public boolean hasIndex() {
        return !chunks.isEmpty();
    }

    public String getProjectPath() {
        return projectPath;
    }

    // --- 持久化 ---

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(storeFile)) return;
        try {
            var root = MAPPER.readTree(storeFile.toFile());
            indexedAt = root.path("indexedAt").asText("");
            var chunksNode = root.path("chunks");
            if (chunksNode.isArray()) {
                for (var node : chunksNode) {
                    CodeChunk chunk = new CodeChunk(
                        node.path("filePath").asText(),
                        node.path("chunkType").asText(),
                        node.path("name").asText(),
                        node.path("content").asText(),
                        node.path("startLine").asInt(),
                        node.path("endLine").asInt());
                    float[] emb = parseFloatArray(node.path("embedding"));
                    chunks.add(new CodeChunkEntry(chunk, emb));
                }
            }
            var relsNode = root.path("relations");
            if (relsNode.isArray()) {
                for (var node : relsNode) {
                    relations.add(new CodeRelation(
                        node.path("fromFile").asText(),
                        node.path("fromName").asText(),
                        node.path("toName").asText(),
                        node.path("relationType").asText()));
                }
            }
        } catch (Exception e) {
            System.err.println("[VectorStore] 加载索引失败: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("projectPath", projectPath);
            root.put("indexedAt", indexedAt);

            ArrayNode chunksNode = MAPPER.createArrayNode();
            for (CodeChunkEntry e : chunks) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("filePath", e.chunk().filePath());
                n.put("chunkType", e.chunk().chunkType());
                n.put("name", e.chunk().name());
                n.put("content", e.chunk().content());
                n.put("startLine", e.chunk().startLine());
                n.put("endLine", e.chunk().endLine());
                n.set("embedding", toArrayNode(e.embedding()));
                chunksNode.add(n);
            }
            root.set("chunks", chunksNode);

            ArrayNode relsNode = MAPPER.createArrayNode();
            for (CodeRelation r : relations) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("fromFile", r.fromFile());
                n.put("fromName", r.fromName());
                n.put("toName", r.toName());
                n.put("relationType", r.relationType());
                relsNode.add(n);
            }
            root.set("relations", relsNode);

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(storeFile.toFile(), root);
        } catch (Exception e) {
            System.err.println("[VectorStore] 保存索引失败: " + e.getMessage());
        }
    }

    private static Path resolveStoreFile(String projectPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(projectPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String hashStr = sb.toString(); // 前 8 位
            return Paths.get(System.getProperty("user.home"), ".yukicli", "rag", hashStr + ".json");
        } catch (Exception e) {
            // fallback：用项目名
            String safe = projectPath.replaceAll("[^a-zA-Z0-9]", "_");
            return Paths.get(System.getProperty("user.home"), ".yukicli", "rag", safe + ".json");
        }
    }

    private static float[] parseFloatArray(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.isArray()) return new float[0];
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    private static ArrayNode toArrayNode(float[] arr) {
        ArrayNode node = MAPPER.createArrayNode();
        for (float v : arr) node.add(v);
        return node;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Override
    public void close() {
        // JSON 持久化在每次写操作后立即落盘，close 无需做事
    }

    // --- 内嵌 record ---

    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}

    public record SearchResult(
        String filePath, String chunkType, String name,
        String content, double similarity) {}

    public record IndexStats(int chunkCount, int relationCount, String indexedAt) {}
}
