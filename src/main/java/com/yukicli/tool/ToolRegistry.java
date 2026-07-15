package com.yukicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 工具注册表 —— 所有工具的注册、查找与执行入口。
 * 第 7 期会在此加入并行执行（ExecutorService + invokeAll 超时），
 * 第一期先做同步串行执行。
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 执行工具调用。
     *
     * @param toolName  工具名称
     * @param arguments JSON 字符串参数
     * @return 执行结果文本
     */
    @SuppressWarnings("unchecked")
    public String execute(String toolName, String arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "[error] 未知工具: " + toolName;
        }
        try {
            Map<String, Object> args = MAPPER.readValue(arguments, Map.class);
            return tool.execute(args);
        } catch (Exception e) {
            return "[error] 工具执行失败 (" + toolName + "): " + e.getMessage();
        }
    }

    /**
     * 批量执行多个工具调用（第一期同步串行）。
     *
     * @param calls 工具调用列表
     * @return 工具调用 ID -> 结果 的映射
     */
    public Map<String, String> executeAll(List<ToolCallEntry> calls) {
        Map<String, String> results = new LinkedHashMap<>();
        for (ToolCallEntry call : calls) {
            String result = execute(call.name(), call.arguments());
            results.put(call.id(), result);
        }
        return results;
    }

    /** 工具调用条目（id + name + arguments） */
    public record ToolCallEntry(String id, String name, String arguments) {}
}
