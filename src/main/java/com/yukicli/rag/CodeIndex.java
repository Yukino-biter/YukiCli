package com.yukicli.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 代码索引管理器 —— 遍历项目目录，分块 + 抽关系 + embedding + 写入 VectorStore。
 *
 * 遍历规则：
 *   - 跳过 node_modules / target / build / .git / .idea / .vscode / dist / out 和所有以 . 开头的目录
 *   - 只索引支持的扩展名
 *
 * 进度回调：每处理 N 个文件触发一次，便于上层显示进度。
 */
public class CodeIndex {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 跳过的目录名 */
    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", "target", "build", ".git", ".idea", ".vscode", "dist", "out",
        ".gradle", ".mvn", "vendor", "__pycache__", ".cache"
    );

    /** 支持的文件扩展名 */
    private static final Set<String> SUPPORTED_EXT = Set.of(
        ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h", ".hpp",
        ".md", ".xml", ".properties", ".yaml", ".yml", ".json", ".sh",
        ".gradle", ".kt", ".scala"
    );

    private final CodeChunker chunker = new CodeChunker();
    private final CodeAnalyzer analyzer = new CodeAnalyzer();
    private final EmbeddingClient embeddingClient;
    private final ProgressListener listener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);
    }

    public CodeIndex() {
        this(null, null);
    }

    public CodeIndex(ProgressListener listener) {
        this(null, listener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener listener) {
        this.embeddingClient = embeddingClient != null ? embeddingClient : new EmbeddingClient();
        this.listener = listener;
    }

    /** 索引项目 */
    public IndexResult index(String projectPath) {
        long start = System.currentTimeMillis();
        try (VectorStore store = new VectorStore(projectPath)) {
            store.clearProject();

            List<Path> files = collectFiles(projectPath);
            notify("找到 " + files.size() + " 个候选文件");

            if (!embeddingClient.isAvailable()) {
                return new IndexResult(0, 0,
                    "Embedding API Key 未配置，无法索引。请在 .env 中配置 EMBEDDING_API_KEY 或 GLM_API_KEY");
            }

            int chunkCount = 0;
            int relationCount = 0;
            int processed = 0;
            int failed = 0;

            List<VectorStore.CodeChunkEntry> batch = new ArrayList<>();
            List<CodeRelation> relBatch = new ArrayList<>();

            for (Path file : files) {
                try {
                    String content = Files.readString(file);
                    String relPath = relativePath(projectPath, file);

                    // 分块
                    List<CodeChunk> chunks = chunker.chunkFile(file);
                    // 抽关系
                    List<CodeRelation> rels = analyzer.analyze(relPath, content);

                    // embedding
                    for (CodeChunk chunk : chunks) {
                        try {
                            float[] emb = embeddingClient.embed(chunk.toEmbeddingText());
                            batch.add(new VectorStore.CodeChunkEntry(chunk, emb));
                            chunkCount++;
                        } catch (Exception e) {
                            failed++;
                            if (failed <= 3) {
                                notify("Embedding 失败: " + chunk.toSummary() + " - " + e.getMessage());
                            }
                        }
                    }
                    relBatch.addAll(rels);
                    relationCount += rels.size();

                    processed++;
                    if (processed % 10 == 0) {
                        notify("已处理 " + processed + "/" + files.size()
                            + " 个文件，" + chunkCount + " 块，" + relationCount + " 关系");
                        // 分批写入避免内存堆积
                        if (!batch.isEmpty()) {
                            store.insertChunks(batch);
                            batch.clear();
                        }
                    }
                } catch (Exception e) {
                    failed++;
                }
            }

            // 写入剩余批次
            if (!batch.isEmpty()) {
                store.insertChunks(batch);
            }
            if (!relBatch.isEmpty()) {
                store.insertRelations(relBatch);
            }
            store.setIndexedAt(LocalDateTime.now().format(DATETIME_FMT));

            long elapsed = (System.currentTimeMillis() - start) / 1000;
            String msg = String.format("索引完成：%d 块，%d 关系，%d 文件，耗时 %ds",
                chunkCount, relationCount, processed, elapsed);
            if (failed > 0) msg += "（" + failed + " 个失败）";
            notify(msg);
            return new IndexResult(chunkCount, relationCount, msg);
        }
    }

    private List<Path> collectFiles(String projectPath) {
        List<Path> files = new ArrayList<>();
        Path root = Paths.get(projectPath);
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> isSupported(p))
                .filter(p -> !isSkipped(p, root))
                .forEach(files::add);
        } catch (IOException e) {
            notify("遍历目录失败: " + e.getMessage());
        }
        return files;
    }

    private boolean isSupported(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : SUPPORTED_EXT) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private boolean isSkipped(Path path, Path root) {
        Path rel = root.relativize(path);
        for (Path segment : rel) {
            String seg = segment.toString();
            if (seg.startsWith(".") && !seg.equals(".")) return true;
            if (SKIP_DIRS.contains(seg)) return true;
        }
        return false;
    }

    private static String relativePath(String projectPath, Path file) {
        try {
            return Paths.get(projectPath).relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }

    private void notify(String msg) {
        if (listener != null) listener.onProgress(msg);
    }

    public record IndexResult(int chunkCount, int relationCount, String message) {}
}
