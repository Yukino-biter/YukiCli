package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 保存长期记忆工具 - 让 LLM 主动保存稳定事实到长期记忆。
 *
 * 调用时机：仅当用户明确表达保存意图（"记一下""记住""以后记得"等）时调用。
 * 作用域：默认 project（仅当前项目可见），跨项目偏好才用 global。
 *
 * memorySaver 回调由外部注入（通常为 memoryManager::storeFact）。
 */
public class SaveMemoryTool extends AbstractTool {

    private BiConsumer<String, String> memorySaver;

    public SaveMemoryTool() {
        super();
    }

    /** 注入记忆保存器：BiConsumer<fact, scope> */
    public void setMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return "当且仅当用户明确说\"记一下\"\"记住\"\"以后记得\"或要求保存长期偏好/稳定事实时调用，" +
                "把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；" +
                "不要保存一次性任务请求、临时文件名或模型猜测。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "fact", stringProp("要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用"), true);
        addProperty(schema, "scope", stringProp("记忆作用域：project 或 global。默认 project；跨项目长期偏好才用 global"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String fact = (String) args.get("fact");
        if (fact == null || fact.toString().isBlank()) {
            return "[error] 保存长期记忆失败: fact 不能为空";
        }
        if (memorySaver == null) {
            return "[error] 保存长期记忆失败: 记忆保存器未初始化";
        }
        String normalized = fact.trim();
        String scope = "global".equalsIgnoreCase(String.valueOf(args.get("scope"))) ? "global" : "project";
        memorySaver.accept(normalized, scope);
        return "💾 已保存到长期记忆(" + scope + "): " + normalized;
    }
}
