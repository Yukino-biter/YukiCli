package com.yukicli.llm;

import com.yukicli.config.YukiCliConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 客户端工厂。
 *
 * 第 8 期改造：用 provider 专属客户端类（GLMClient / DeepSeekClient / KimiClient / OpenAIClient）
 * 替代原来的单一 OpenAiCompatibleClient，让每个 provider 有独立的模型能力描述和扩展点。
 *
 * 支持运行时切换：通过 {@link #create(String, YukiCliConfig)} 按用户指定的 provider 创建客户端，
 * 配合 /model 命令实现运行时模型切换。
 */
public class LlmClientFactory {

    /** 支持的 provider 列表（用于 /model 命令展示） */
    public static final List<String> SUPPORTED_PROVIDERS = List.of("glm", "deepseek", "kimi", "openai");

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
        for (String provider : SUPPORTED_PROVIDERS) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    /**
     * 为指定 provider 创建客户端。
     *
     * @param provider provider 名称（glm / deepseek / kimi / openai）
     * @param config   配置
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

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model, baseUrl);
            case "deepseek" -> new DeepSeekClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            case "openai" -> new OpenAIClient(apiKey, model, baseUrl);
            default -> new OpenAiCompatibleClient(apiKey, baseUrl, model, normalized);
        };
    }

    /** provider 别名归一 */
    public static String normalizeProvider(String provider) {
        String n = provider.trim().toLowerCase();
        return switch (n) {
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "zhipu", "bigmodel" -> "glm";
            default -> n;
        };
    }

    /** 各 provider 的默认 Base URL */
    public static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "kimi" -> "https://api.moonshot.cn/v1";
            case "openai" -> "https://api.openai.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    /** 各 provider 的默认模型 */
    public static String defaultModel(String provider) {
        return switch (provider) {
            case "glm" -> "glm-4-flash";
            case "deepseek" -> "deepseek-chat";
            case "kimi" -> "moonshot-v1-8k";
            case "openai" -> "gpt-4o";
            default -> "gpt-4o";
        };
    }

    /** 列出所有已配置 API Key 的可用 provider */
    public static List<String> listAvailableProviders(YukiCliConfig config) {
        List<String> available = new ArrayList<>();
        for (String provider : SUPPORTED_PROVIDERS) {
            String apiKey = config.getApiKey(provider);
            if (apiKey != null && !apiKey.isBlank()) {
                available.add(provider);
            }
        }
        return available;
    }
}
