package com.yukicli.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.config.YukiCliConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Embedding 客户端 —— 调用 OpenAI 兼容的 embedding API。
 *
 * 配置来源（优先级从高到低）：
 *   1. 显式构造参数
 *   2. 环境变量 EMBEDDING_PROVIDER / EMBEDDING_MODEL / EMBEDDING_API_KEY / EMBEDDING_BASE_URL
 *   3. fallback 到 GLM_API_KEY + embedding-3 模型
 *
 * 支持 GLM embedding-3 / OpenAI text-embedding-3-small 等 OpenAI 兼容接口。
 */
public class EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INPUT_CHARS = 2000;

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    public EmbeddingClient() {
        this.provider = envOr("EMBEDDING_PROVIDER", "glm");
        this.model = envOr("EMBEDDING_MODEL", defaultModel(this.provider));
        this.baseUrl = envOr("EMBEDDING_BASE_URL", defaultBaseUrl(this.provider));
        this.apiKey = resolveApiKey(this.provider);
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** 用 YukiCliConfig 创建（便于复用 GLM_API_KEY） */
    public EmbeddingClient(YukiCliConfig config) {
        this.provider = envOr("EMBEDDING_PROVIDER", "glm");
        this.model = envOr("EMBEDDING_MODEL", defaultModel(this.provider));
        this.baseUrl = envOr("EMBEDDING_BASE_URL", defaultBaseUrl(this.provider));
        // 优先用 EMBEDDING_API_KEY，否则 fallback 到对应 provider 的 API key
        String key = System.getenv("EMBEDDING_API_KEY");
        if (key == null || key.isBlank()) {
            key = readDotEnv("EMBEDDING_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = config.getApiKey(this.provider);
        }
        this.apiKey = key;
    }

    /** 计算文本 embedding 向量 */
    public float[] embed(String text) throws IOException, InterruptedException {
        String truncated = text.length() > MAX_INPUT_CHARS
            ? text.substring(0, MAX_INPUT_CHARS) : text;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode inputs = MAPPER.createArrayNode();
        inputs.add(truncated);
        body.set("input", inputs);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/embeddings"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Embedding API 失败 " + resp.statusCode() + ": "
                + truncate(resp.body(), 500));
        }

        // 解析 data[0].embedding
        var root = MAPPER.readTree(resp.body());
        var data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("Embedding API 返回空数据: " + truncate(resp.body(), 200));
        }
        var emb = data.get(0).path("embedding");
        if (!emb.isArray()) {
            throw new IOException("Embedding API 返回格式异常: " + truncate(resp.body(), 200));
        }
        float[] vec = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) {
            vec[i] = (float) emb.get(i).asDouble();
        }
        return vec;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    // --- 辅助 ---

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = readDotEnv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static String readDotEnv(String key) {
        java.io.File[] files = {
            new java.io.File(".env"),
            new java.io.File(System.getProperty("user.home"), ".env")
        };
        for (java.io.File f : files) {
            if (!f.exists()) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static String resolveApiKey(String provider) {
        String key = System.getenv("EMBEDDING_API_KEY");
        if (key == null || key.isBlank()) key = readDotEnv("EMBEDDING_API_KEY");
        if (key == null || key.isBlank()) {
            String envKey = switch (provider.toLowerCase()) {
                case "glm" -> "GLM_API_KEY";
                case "deepseek" -> "DEEPSEEK_API_KEY";
                case "kimi" -> "KIMI_API_KEY";
                case "openai" -> "OPENAI_API_KEY";
                default -> provider.toUpperCase() + "_API_KEY";
            };
            key = System.getenv(envKey);
            if (key == null || key.isBlank()) key = readDotEnv(envKey);
        }
        return key;
    }

    private static String defaultModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "glm" -> "embedding-3";
            case "openai" -> "text-embedding-3-small";
            case "deepseek" -> "text-embedding-3-small";
            default -> "embedding-3";
        };
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "kimi" -> "https://api.moonshot.cn/v1";
            default -> "https://open.bigmodel.cn/api/paas/v4";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
