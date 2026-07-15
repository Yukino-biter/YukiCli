package com.yukicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具基类，提供 JSON Schema 构建辅助方法。
 */
public abstract class AbstractTool implements Tool {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode cachedSchema;

    protected AbstractTool() {
        this.cachedSchema = buildSchema();
    }

    /** 子类实现：构建参数 JSON Schema */
    protected abstract ObjectNode buildSchema();

    @Override
    public final ObjectNode getParameters() {
        return cachedSchema;
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
