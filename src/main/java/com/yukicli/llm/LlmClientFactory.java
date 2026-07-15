package com.yukicli.llm;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * LLM 客户端工厂。
 * 从 .env 读取配置，创建 OpenAiCompatibleClient 实例。
 * 第 8 期会扩展为多模型适配（GLM/DeepSeek/Step/Kimi 等）。
 */
public class LlmClientFactory {

    public static LlmClient create() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String apiKey = dotenv.get("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("未配置 API_KEY，请在 .env 文件中设置（参考 .env.example）");
        }

        String apiBase = dotenv.get("API_BASE", "https://api.openai.com/v1");
        String model = dotenv.get("MODEL", "gpt-4o");

        return new OpenAiCompatibleClient(apiKey, apiBase, model);
    }
}
