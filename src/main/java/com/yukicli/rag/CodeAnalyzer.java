package com.yukicli.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码关系抽取器（简化版，无 javaparser）。
 *
 * 抽取四种关系：
 *   - imports：import 语句引用的类
 *   - extends：class A extends B
 *   - implements：class A implements B, C
 *   - uses：方法体中出现的类型名（粗略）
 *
 * 不做完整 AST 分析，只做正则匹配。结果用于增强 RAG 检索时的关系图查询。
 */
public class CodeAnalyzer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    private static final Pattern CLASS_DECL_PATTERN = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+(\\w+)(?:[^{]*?\\bextends\\s+([\\w.]+))?(?:[^{]*?\\bimplements\\s+([\\w.,\\s]+?))?\\s*\\{");

    /** 抽取单个文件的关系 */
    public List<CodeRelation> analyze(String filePath, String content) {
        List<CodeRelation> relations = new ArrayList<>();
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        String className = fileName.contains(".")
            ? fileName.substring(0, fileName.lastIndexOf('.'))
            : fileName;

        // imports
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String imported = m.group(1);
            String simpleName = imported.substring(imported.lastIndexOf('.') + 1);
            if (!simpleName.isEmpty()) {
                relations.add(new CodeRelation(filePath, className, simpleName, "imports"));
            }
        }

        // extends / implements
        Matcher cm = CLASS_DECL_PATTERN.matcher(content);
        while (cm.find()) {
            String cls = cm.group(1);
            if (cm.group(2) != null) {
                String ext = cm.group(2);
                String simple = ext.substring(ext.lastIndexOf('.') + 1);
                relations.add(new CodeRelation(filePath, cls, simple, "extends"));
            }
            if (cm.group(3) != null) {
                for (String impl : cm.group(3).split(",")) {
                    String s = impl.trim();
                    String simple = s.substring(s.lastIndexOf('.') + 1);
                    if (!simple.isEmpty()) {
                        relations.add(new CodeRelation(filePath, cls, simple, "implements"));
                    }
                }
            }
        }

        return relations;
    }
}
