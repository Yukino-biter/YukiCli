package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;
import com.yukicli.web.FetchResult;
import com.yukicli.web.HtmlExtractor;
import com.yukicli.web.NetworkPolicy;
import com.yukicli.web.WebFetcher;

import java.util.Map;

/**
 * 网页抓取工具 - 让 LLM 抓取指定 URL 并提取正文为 Markdown。
 *
 * 流程（顺序很重要）：
 * <ol>
 *   <li>NetworkPolicy.checkUrl：SSRF 校验（scheme 白名单 + 主机黑名单）</li>
 *   <li>NetworkPolicy.acquire：限流（60s 内最多 30 次）</li>
 *   <li>WebFetcher.fetch：HTTP GET + 5MB 截断</li>
 *   <li>HtmlExtractor.extract：HTML → 主正文 Markdown</li>
 *   <li>按 maxChars 截断 → FetchResult 格式化输出</li>
 * </ol>
 *
 * 依赖懒加载：首次调用时才创建 WebFetcher / HtmlExtractor / NetworkPolicy。
 */
public class WebFetchTool extends AbstractTool {

    private static final int DEFAULT_MAX_CHARS = 8_000;

    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;

    public WebFetchTool() {
        super();
    }

    /** 注入共享的 NetworkPolicy（与 WebSearchTool 共用同一限流窗口） */
    public void setNetworkPolicy(NetworkPolicy policy) {
        this.networkPolicy = policy;
    }

    private WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "抓取指定 URL，提取正文转 Markdown。"
                + "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，不重试。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "url", stringProp("完整 URL，需 http 或 https 协议"), true);
        addProperty(schema, "max_chars", stringProp("返回 Markdown 最大字符数（默认 8000，超出截断）"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String url = args.get("url") == null ? "" : String.valueOf(args.get("url")).trim();
        if (url.isEmpty()) {
            return "URL 不能为空";
        }

        int maxChars = DEFAULT_MAX_CHARS;
        Object maxCharsObj = args.get("max_chars");
        if (maxCharsObj != null) {
            try {
                maxChars = Math.max(500, Integer.parseInt(String.valueOf(maxCharsObj)));
            } catch (NumberFormatException ignored) {}
        }

        // 1. SSRF 校验
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        // 2. 限流
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        // 3. fetch → extract → truncate → format
        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url);
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String formatFetchResult(FetchResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 标题: ").append(r.title().isBlank() ? "(无)" : r.title()).append("\n");
        sb.append("🔗 URL: ").append(r.url()).append("\n");
        sb.append("📏 长度: ").append(r.contentLength()).append(" 字符");
        if (r.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n");
        if (r.bodyEmpty()) {
            sb.append("⚠️ ").append(r.hint()).append("\n");
        } else {
            sb.append("\n---\n").append(r.markdown()).append("\n");
        }
        return sb.toString();
    }
}
