package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.rag.CodeRetriever;
import com.yukicli.rag.SearchResultFormatter;
import com.yukicli.rag.VectorStore;
import com.yukicli.tool.AbstractTool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 代码语义检索工具 - 让 LLM 通过 hybrid 检索查项目代码。
 *
 * 依赖外部注入的 retrieverFactory：BiFunction<projectPath, empty, CodeRetriever>
 * 索引未建立时返回提示让用户运行 /index。
 */
public class SearchCodeTool extends AbstractTool {

    private Function<String, CodeRetriever> retrieverFactory;
    private String projectPath = System.getProperty("user.dir");

    public SearchCodeTool() {
        super();
    }

    /** 注入 retriever 工厂：Function<projectPath, CodeRetriever> */
    public void setRetrieverFactory(Function<String, CodeRetriever> factory) {
        this.retrieverFactory = factory;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    @Override
    public String getName() {
        return "search_code";
    }

    @Override
    public String getDescription() {
        return "在当前项目代码库中语义检索相关代码片段（基于 RAG 混合检索：embedding + 关键词）。"
                + "需要先使用 /index 命令建立索引。返回最相关的代码块及其位置。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "query", stringProp("检索查询：自然语言描述要找的代码功能，或类名/方法名关键词"), true);
        addProperty(schema, "top_k", stringProp("返回结果数，默认 5，最大 30"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.toString().isBlank()) {
            return "[error] 缺少参数: query";
        }
        if (retrieverFactory == null) {
            return "[error] 检索器未初始化";
        }

        int topK = 5;
        Object topKObj = args.get("top_k");
        if (topKObj != null) {
            try {
                topK = Math.max(1, Math.min(30, Integer.parseInt(String.valueOf(topKObj))));
            } catch (NumberFormatException ignored) {}
        }

        try (CodeRetriever retriever = retrieverFactory.apply(projectPath)) {
            if (!retriever.hasIndex()) {
                return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
            }
            List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
            if (results.isEmpty()) {
                return "未找到匹配的代码片段。";
            }
            return SearchResultFormatter.formatForPrompt(results);
        } catch (Exception e) {
            return "[error] 代码检索失败: " + e.getMessage();
        }
    }
}
