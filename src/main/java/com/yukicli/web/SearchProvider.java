package com.yukicli.web;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎抽象接口。
 *
 * 当前实现：
 * - {@link ZhipuSearchProvider}：智谱 Web Search，与 GLM 推理共用 API Key，国内首选
 * - {@link SerpApiSearchProvider}：商业聚合 API，国际通用，需 API Key
 * - {@link SearxngSearchProvider}：开源元搜索引擎，自托管，免费
 *
 * 让用户根据成本 / 数据合规 / 离线需求自由切换 provider。
 * 后续如果新增 Brave / Tavily / Exa 等实现，只要继续实现这个接口，无需改动调用方。
 */
public interface SearchProvider {

    /** @return provider 名称（如 "zhipu"、"serpapi"、"searxng"），用于错误信息和日志 */
    String name();

    /** @return 是否可用（如必要 API Key 已配置 / 服务地址可访问） */
    boolean isReady();

    /** @return 当 {@link #isReady()} 为 false 时给用户的提示，例如「请配置 GLM_API_KEY」 */
    String unavailableHint();

    /**
     * 执行搜索。
     *
     * @param query 搜索关键词，不可为 null/blank
     * @param topK  期望返回结果数量，实现可酌情截断
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
