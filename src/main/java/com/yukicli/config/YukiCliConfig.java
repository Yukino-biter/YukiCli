package com.yukicli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YukiCli 配置管理。
 * 两层配置来源（优先级从高到低）：
 *   1. ~/.yukicli/config.json（JSON 持久化配置）
 *   2. 环境变量 / .env 文件（fallback）
 * .env 文件位置：当前目录 .env 和 ~/.env 都会读。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YukiCliConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".yukicli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "glm";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    // --- Getters / Setters ---

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    // --- 配置查询（config.json 优先，fallback 到 env / .env） ---

    public String getApiKey(String provider) {
        ProviderConfig pc = providers.get(provider);
        if (pc != null && pc.getApiKey() != null && !pc.getApiKey().isBlank()) {
            return pc.getApiKey();
        }
        return loadFromEnv(envKey(provider, "API_KEY"));
    }

    public String getModel(String provider) {
        ProviderConfig pc = providers.get(provider);
        if (pc != null && pc.getModel() != null && !pc.getModel().isBlank()) {
            return pc.getModel();
        }
        return loadFromEnv(envKey(provider, "MODEL"));
    }

    public String getBaseUrl(String provider) {
        ProviderConfig pc = providers.get(provider);
        if (pc != null && pc.getBaseUrl() != null && !pc.getBaseUrl().isBlank()) {
            return pc.getBaseUrl();
        }
        return loadFromEnv(envKey(provider, "BASE_URL"));
    }

    /** 生成 provider 专属环境变量名，如 GLM_API_KEY、DEEPSEEK_MODEL */
    private static String envKey(String provider, String suffix) {
        return switch (provider.toLowerCase()) {
            case "glm" -> "GLM_" + suffix;
            case "deepseek" -> "DEEPSEEK_" + suffix;
            case "kimi" -> "KIMI_" + suffix;
            case "openai" -> suffix.equals("API_KEY") ? "OPENAI_API_KEY" : "OPENAI_" + suffix;
            default -> provider.toUpperCase() + "_" + suffix;
        };
    }

    // --- 加载与保存 ---

    public static YukiCliConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), YukiCliConfig.class);
            } catch (IOException e) {
                System.err.println("配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        return new YukiCliConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("配置保存失败: " + e.getMessage());
        }
    }

    // --- .env 文件读取 ---

    private static String loadFromEnv(String key) {
        // 先读系统环境变量
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        // 再读 .env 文件（当前目录和用户目录）
        String dotEnvValue = readFromDotEnv(key);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }
        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
}
