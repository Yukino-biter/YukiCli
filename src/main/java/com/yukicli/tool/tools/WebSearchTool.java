package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;
import com.yukicli.web.NetworkPolicy;
import com.yukicli.web.SearchProvider;
import com.yukicli.web.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 网页搜索工具 - 让 LLM 通过搜索引擎获取实时信息。
 *
 * 支持 provider 由 {@link com.yukicli.web.SearchProviderFactory} 决定：
 * zhipu（智谱，国内首选） / serpapi（国际通用） / searxng（自托管）。
 *
 * 通过 supplier 注入 SearchProvider（懒加载），由 Main 在初始化时绑定。
 */
public class WebSearchTool extends AbstractTool {

    private Supplier<SearchProvider> providerSupplier;
    private NetworkPolicy networkPolicy;

    public WebSearchTool() {
        super();
    }

    /** 注入 SearchProvider 工厂（懒加载，首次调用时才创建） */
    public void setProviderSupplier(Supplier<SearchProvider> supplier) {
        this.providerSupplier = supplier;
    }

    /** 注入共享的 NetworkPolicy（与 WebFetchTool 共用同一限流窗口） */
    public void setNetworkPolicy(NetworkPolicy policy) {
        this.networkPolicy = policy;
    }

    private NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。"
                + "支持 zhipu / serpapi / searxng 三种 provider，由 SEARCH_PROVIDER 环境变量或配置切换。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "query", stringProp("搜索关键词，例如 'Java 21 新特性'、'Spring Boot 3.3 release notes'"), true);
        addProperty(schema, "top_k", stringProp("返回结果数量（默认 5）"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query")).trim();
        if (query.isEmpty()) {
            return "搜索关键词不能为空";
        }

        if (providerSupplier == null) {
            return "[error] SearchProvider 未初始化";
        }

        int topK = 5;
        Object topKObj = args.get("top_k");
        if (topKObj != null) {
            try {
                topK = Math.max(1, Math.min(50, Integer.parseInt(String.valueOf(topKObj))));
            } catch (NumberFormatException ignored) {}
        }

        // 限流（SSRF 校验对搜索 API 无意义，但限流对搜索也适用）
        NetworkPolicy policy = networkPolicy();
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        SearchProvider provider;
        try {
            provider = providerSupplier.get();
        } catch (Exception e) {
            return "搜索 provider 初始化失败: " + e.getMessage();
        }

        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }

        try {
            List<SearchResult> results = provider.search(query, topK);
            if (results.isEmpty()) {
                return "未找到相关结果。";
            }
            return formatSearchResults(query, results);
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private String formatSearchResults(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索: ").append(query).append("（").append(results.size()).append(" 条结果）\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ");
            sb.append(r.title().isBlank() ? "(无标题)" : r.title()).append("\n");
            if (!r.source().isBlank()) {
                sb.append("   🌐 ").append(r.source()).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url()).append("\n");
            }
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   📄 ").append(snippet).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
