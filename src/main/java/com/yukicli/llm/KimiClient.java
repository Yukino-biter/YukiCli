package com.yukicli.llm;

/**
 * Kimi（月之暗面）客户端。
 *
 * 支持模型：moonshot-v1-8k / moonshot-v1-32k / moonshot-v1-128k
 * 默认 Base URL：https://api.moonshot.cn/v1
 * 默认模型：moonshot-v1-8k
 *
 * 上下文窗口：按模型后缀解析（8k / 32k / 128k）
 */
public class KimiClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";
    private static final String DEFAULT_MODEL = "moonshot-v1-8k";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public KimiClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public KimiClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public KimiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
    }

    @Override
    protected String getApiUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/chat/completions";
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "kimi";
    }

    @Override
    public int maxContextWindow() {
        String lower = model.toLowerCase();
        if (lower.contains("128k")) return 128_000;
        if (lower.contains("32k")) return 32_000;
        return 8_000;
    }
}
