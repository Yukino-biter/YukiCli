package com.yukicli.rag;

/**
 * 代码关系 record —— 用于构建简易依赖图。
 *
 * 字段说明：
 *   - fromFile / fromName 起点文件/名字
 *   - toName 终点名（被引用的类/方法）
 *   - relationType 关系类型（uses / implements / extends / calls）
 */
public record CodeRelation(
        String fromFile,
        String fromName,
        String toName,
        String relationType) {
}
