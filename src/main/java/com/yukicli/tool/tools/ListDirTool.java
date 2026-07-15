package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 列出目录内容工具。
 */
public class ListDirTool extends AbstractTool {

    @Override
    public String getName() { return "list_dir"; }

    @Override
    public String getDescription() {
        return "列出指定目录下的文件和子目录。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "path", stringProp("要列出的目录路径，默认为当前目录"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pathStr = (String) args.getOrDefault("path", ".");
        try {
            Path dir = Path.of(pathStr);
            if (!Files.exists(dir)) {
                return "[error] 目录不存在: " + dir;
            }
            if (!Files.isDirectory(dir)) {
                return "[error] 路径不是目录: " + dir;
            }
            List<String> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    String type = Files.isDirectory(p) ? "[DIR] " : "      ";
                    entries.add(type + name);
                });
            }
            if (entries.isEmpty()) {
                return "目录为空: " + dir;
            }
            return "目录内容 (" + dir + "):\n" + String.join("\n", entries);
        } catch (Exception e) {
            return "[error] 列出目录失败: " + e.getMessage();
        }
    }
}
