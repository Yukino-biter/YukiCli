package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 写入文件内容工具。如果父目录不存在会自动创建。
 */
public class WriteFileTool extends AbstractTool {

    @Override
    public String getName() { return "write_file"; }

    @Override
    public String getDescription() {
        return "将内容写入指定文件。如果父目录不存在会自动创建。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "path", stringProp("要写入的文件路径"), true);
        addProperty(schema, "content", stringProp("要写入的文件内容"), true);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        if (pathStr == null || pathStr.isBlank()) {
            return "[error] 缺少参数: path";
        }
        if (content == null) {
            content = "";
        }
        try {
            Path path = Path.of(pathStr);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
            return "文件写入成功: " + path + " (" + content.length() + " 字符)";
        } catch (Exception e) {
            return "[error] 写入文件失败: " + e.getMessage();
        }
    }
}
