package com.yukicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.policy.PathGuard;

/**
 * 工具基类，提供 JSON Schema 构建辅助方法 + 路径围栏注入。
 *
 * 子类如需操作文件系统，应通过 {@link #resolveSafePath(String)} 解析路径，
 * 越界时会抛 {@link com.yukicli.policy.PolicyException}，由 ToolRegistry 统一捕获。
 */
public abstract class AbstractTool implements Tool {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode cachedSchema;
    private PathGuard pathGuard;

    protected AbstractTool() {
        this.cachedSchema = buildSchema();
    }

    /** 子类实现：构建参数 JSON Schema */
    protected abstract ObjectNode buildSchema();

    @Override
    public final ObjectNode getParameters() {
        return cachedSchema;
    }

    /** 由 ToolRegistry 在注册时注入 */
    public void setPathGuard(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /** 子类用此方法解析路径，越界抛 PolicyException */
    protected java.nio.file.Path resolveSafePath(String input) {
        if (pathGuard == null) {
            // 未注入时退化到原始 Path.of（向后兼容）
            return java.nio.file.Path.of(input);
        }
        return pathGuard.resolveSafe(input);
    }

    // --- Schema 构建辅助 ---

    protected ObjectNode objectSchema() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "object");
        node.set("properties", MAPPER.createObjectNode());
        node.set("required", MAPPER.createArrayNode());
        return node;
    }

    protected ObjectNode stringProp(String description) {
        ObjectNode prop = MAPPER.createObjectNode();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    protected void addProperty(ObjectNode schema, String name, ObjectNode prop, boolean required) {
        schema.with("properties").set(name, prop);
        if (required) {
            schema.withArray("required").add(name);
        }
    }
}
