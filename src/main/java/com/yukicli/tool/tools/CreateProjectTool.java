package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 创建项目目录结构工具。
 * 在指定路径下创建标准项目骨架（src/main, src/test 等）。
 */
public class CreateProjectTool extends AbstractTool {

    @Override
    public String getName() { return "create_project"; }

    @Override
    public String getDescription() {
        return "在指定路径创建项目目录结构（含 src/main、src/test 等标准目录）。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "name", stringProp("项目名称"), true);
        addProperty(schema, "path", stringProp("创建项目的父目录路径，默认为当前目录"), false);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String name = (String) args.get("name");
        String parentPath = (String) args.getOrDefault("path", ".");

        if (name == null || name.isBlank()) {
            return "[error] 缺少参数: name";
        }
        try {
            Path projectRoot = Path.of(parentPath).resolve(name);
            if (Files.exists(projectRoot)) {
                return "[error] 目录已存在: " + projectRoot;
            }

            // 创建标准项目目录结构
            Files.createDirectories(projectRoot.resolve("src/main/java"));
            Files.createDirectories(projectRoot.resolve("src/main/resources"));
            Files.createDirectories(projectRoot.resolve("src/test/java"));
            Files.createDirectories(projectRoot.resolve("src/test/resources"));

            // 创建 README.md
            Files.writeString(projectRoot.resolve("README.md"), "# " + name + "\n");
            // 创建 .gitignore
            Files.writeString(projectRoot.resolve(".gitignore"), "target/\n*.class\n.idea/\n*.iml\n.env\n");

            return "项目创建成功: " + projectRoot + "\n" +
                   "  ├── src/main/java/\n" +
                   "  ├── src/main/resources/\n" +
                   "  ├── src/test/java/\n" +
                   "  ├── src/test/resources/\n" +
                   "  ├── README.md\n" +
                   "  └── .gitignore";
        } catch (Exception e) {
            return "[error] 创建项目失败: " + e.getMessage();
        }
    }
}
