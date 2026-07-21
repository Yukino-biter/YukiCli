package com.yukicli.hitl;

import java.util.Set;

/**
 * 审批策略 —— 静态判断哪些工具需要 HITL 审批。
 *
 * 危险等级映射：
 *   - 🔴 高危：execute_command
 *   - 🟡 中危：write_file, create_project
 *   - 🟢 安全：其他（read_file / list_dir / save_memory / search_code 等只读工具）
 */
public final class ApprovalPolicy {

    private ApprovalPolicy() {}

    /** 需要审批的工具集合 */
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file", "execute_command", "create_project"
    );

    /** 高危工具集合（execute_command 专属） */
    private static final Set<String> HIGH_RISK_TOOLS = Set.of("execute_command");

    /** 判断指定工具是否需要审批 */
    public static boolean requiresApproval(String toolName) {
        return toolName != null && DANGEROUS_TOOLS.contains(toolName);
    }

    /** 获取危险等级标签 */
    public static String getDangerLevel(String toolName) {
        if (toolName == null) return "🟢 安全";
        if (HIGH_RISK_TOOLS.contains(toolName)) return "🔴 高危";
        if (DANGEROUS_TOOLS.contains(toolName)) return "🟡 中危";
        return "🟢 安全";
    }

    /** 获取风险说明 */
    public static String getRiskDescription(String toolName) {
        if (toolName == null) return "";
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> "";
        };
    }
}
