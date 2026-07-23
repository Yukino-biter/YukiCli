package com.yukicli.llm;

/**
 * DeepSeek 客户端。
 *
 * 支持模型：deepseek-chat / deepseek-coder / deepseek-reasoner
 * 默认 Base URL：https://api.deepseek.com/v1
 * 默认模型：deepseek-chat
 *
 * 上下文窗口：64K（deepseek-reasoner 为 64K）
 */
public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl) {
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
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 64_000;
    }
}
