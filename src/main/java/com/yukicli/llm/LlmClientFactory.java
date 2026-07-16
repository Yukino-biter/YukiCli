package com.yukicli.llm;

import com.yukicli.config.YukiCliConfig;

/**
 * LLM 客户端工厂。
 * 从 YukiCliConfig 加载配置，按 provider 创建 OpenAiCompatibleClient。
 * 支持 GLM / DeepSeek / Kimi / OpenAI 等 OpenAI 兼容接口。
 */
public class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * 从配置创建 LLM 客户端。
     * 优先使用 defaultProvider，找不到则按固定顺序轮询。
     */
    public static LlmClient createFromConfig(YukiCliConfig config) {
        // 先尝试默认 provider
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        // 轮询所有支持的 provider
        for (String provider : new String[]{"glm", "deepseek", "kimi", "openai"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    /**
     * 为指定 provider 创建客户端。
     * @return 客户端实例，找不到 API Key 时返回 null
     */
    public static LlmClient create(String provider, YukiCliConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = config.getModel(normalized);
        String baseUrl = config.getBaseUrl(normalized);
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = defaultBaseUrl(normalized);
        }
        if (model == null || model.isBlank()) {
            model = defaultModel(normalized);
        }

        return new OpenAiCompatibleClient(apiKey, baseUrl, model, normalized);
    }

    /** provider 别名归一 */
    private static String normalizeProvider(String provider) {
        String n = provider.trim().toLowerCase();
        return switch (n) {
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            default -> n;
        };
    }

    /** 各 provider 的默认 Base URL */
    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "kimi" -> "https://api.moonshot.cn/v1";
            case "openai" -> "https://api.openai.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    /** 各 provider 的默认模型 */
    private static String defaultModel(String provider) {
        return switch (provider) {
            case "glm" -> "glm-4-flash";
            case "deepseek" -> "deepseek-chat";
            case "kimi" -> "moonshot-v1-8k";
            case "openai" -> "gpt-4o";
            default -> "gpt-4o";
        };
    }
}
