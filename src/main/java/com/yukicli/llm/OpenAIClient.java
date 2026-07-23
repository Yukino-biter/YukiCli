package com.yukicli.llm;

/**
 * OpenAI 客户端。
 *
 * 支持模型：gpt-4o / gpt-4o-mini / gpt-4-turbo / gpt-3.5-turbo
 * 默认 Base URL：https://api.openai.com/v1
 * 默认模型：gpt-4o
 *
 * 上下文窗口：128K（gpt-4o 系列）
 */
public class OpenAIClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public OpenAIClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public OpenAIClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenAIClient(String apiKey, String model, String baseUrl) {
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
        return "openai";
    }

    @Override
    public int maxContextWindow() {
        String lower = model.toLowerCase();
        // gpt-4o 系列支持 128K
        if (lower.contains("gpt-4o") || lower.contains("gpt-4-turbo")) {
            return 128_000;
        }
        // gpt-3.5-turbo 支持 16K
        if (lower.contains("gpt-3.5")) {
            return 16_000;
        }
        return 128_000;
    }

    @Override
    public boolean supportsImageInput() {
        // gpt-4o 系列支持视觉输入
        return model.toLowerCase().contains("gpt-4o");
    }
}
