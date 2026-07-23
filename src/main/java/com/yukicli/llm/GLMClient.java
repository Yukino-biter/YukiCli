package com.yukicli.llm;

/**
 * GLM（智谱）客户端。
 *
 * 支持模型：glm-4-flash / glm-4 / glm-4-plus / glm-4-long 等。
 * 默认 Base URL：https://open.bigmodel.cn/api/paas/v4
 * 默认模型：glm-4-flash
 *
 * 上下文窗口：128K（glm-4-long 为 1M）
 */
public class GLMClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String DEFAULT_MODEL = "glm-4-flash";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public GLMClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public GLMClient(String apiKey, String model, String baseUrl) {
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
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        // glm-4-long 支持 1M 上下文
        if (model.toLowerCase().contains("long")) {
            return 1_000_000;
        }
        // 其他 glm-4 系列支持 128K
        return 128_000;
    }

    @Override
    public boolean supportsImageInput() {
        // glm-4v 系列支持视觉输入
        return model.toLowerCase().contains("4v") || model.toLowerCase().contains("v-plus");
    }
}
