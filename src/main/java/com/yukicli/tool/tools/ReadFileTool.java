package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 读取文件内容工具。
 */
public class ReadFileTool extends AbstractTool {

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() {
        return "读取指定路径的文件内容。路径可以是相对路径或绝对路径。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "path", stringProp("要读取的文件路径"), true);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return "[error] 缺少参数: path";
        }
        try {
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) {
                return "[error] 文件不存在: " + path;
            }
            if (Files.isDirectory(path)) {
                return "[error] 路径是目录，不是文件: " + path;
            }
            return Files.readString(path);
        } catch (Exception e) {
            return "[error] 读取文件失败: " + e.getMessage();
        }
    }
}
