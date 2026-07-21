package com.yukicli.hitl;

/**
 * 审批请求 record。
 *
 * 字段说明：
 *   - toolName      工具名（write_file / execute_command / create_project）
 *   - arguments     原始 JSON 参数字符串
 *   - dangerLevel   危险等级标签（🔴 高危 / 🟡 中危 / 🟢 安全）
 *   - riskDescription 风险说明
 *   - suggestion    建议提示
 *   - callerContext 调用上下文（哪个 Agent / SubAgent）
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion,
        String callerContext) {

    public static ApprovalRequest of(String toolName, String arguments) {
        String level = ApprovalPolicy.getDangerLevel(toolName);
        String risk = ApprovalPolicy.getRiskDescription(toolName);
        String suggestion = "确认执行请输入 y，拒绝请输入 n";
        return new ApprovalRequest(toolName, arguments, level, risk, suggestion, "");
    }

    public static ApprovalRequest of(String toolName, String arguments, String callerContext) {
        String level = ApprovalPolicy.getDangerLevel(toolName);
        String risk = ApprovalPolicy.getRiskDescription(toolName);
        String suggestion = "确认执行请输入 y，拒绝请输入 n";
        return new ApprovalRequest(toolName, arguments, level, risk, suggestion, callerContext);
    }
}
