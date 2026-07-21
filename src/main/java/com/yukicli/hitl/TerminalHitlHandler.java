package com.yukicli.hitl;

import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端交互式 HITL 处理器。
 *
 * 交互选项：
 *   - y / Enter  批准本次
 *   - a          批准本次会话所有后续同类危险操作
 *   - n          拒绝（可填拒绝原因）
 *   - s          跳过本步骤
 *   - m          修改参数后执行
 *
 * 线程安全：requestApproval 整体 synchronized，避免多 Worker 同时弹审批框。
 */
public class TerminalHitlHandler implements HitlHandler {

    private final Scanner scanner;
    private final Set<String> approvedAllTools = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = false;  // 默认关闭，由 /hitl on 开启

    public TerminalHitlHandler(Scanner scanner) {
        this.scanner = scanner;
    }

    public TerminalHitlHandler() {
        this(new Scanner(System.in, "UTF-8"));
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest req) {
        if (!enabled) {
            return ApprovalResult.approve();
        }
        if (approvedAllTools.contains(req.toolName())) {
            return ApprovalResult.approve();
        }

        // 打印审批请求 box
        System.out.println();
        System.out.println("┌─ " + req.dangerLevel() + " 工具调用审批 ───────────────────────");
        System.out.println("│ 工具: " + req.toolName());
        System.out.println("│ 风险: " + req.riskDescription());
        if (req.callerContext() != null && !req.callerContext().isBlank()) {
            System.out.println("│ 上下文: " + req.callerContext());
        }
        System.out.println("│ 参数: " + truncate(req.arguments(), 200));
        System.out.println("└──────────────────────────────────────────────");
        System.out.print("批准? [y=批准 / a=全部放行 / n=拒绝 / s=跳过 / m=修改参数] (默认 y): ");
        System.out.flush();

        // 失败安全：5 次无效输入后默认拒绝
        for (int attempt = 0; attempt < 5; attempt++) {
            String line = scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase() : "";
            if (line.isEmpty() || line.equals("y") || line.equals("yes")) {
                return ApprovalResult.approve();
            }
            switch (line) {
                case "a", "all" -> {
                    approvedAllTools.add(req.toolName());
                    return ApprovalResult.approveAll();
                }
                case "n", "no" -> {
                    System.out.print("拒绝原因（可留空）: ");
                    System.out.flush();
                    String reason = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                    return ApprovalResult.reject(reason.isEmpty() ? "用户拒绝" : reason);
                }
                case "s", "skip" -> {
                    return ApprovalResult.skip();
                }
                case "m", "modify" -> {
                    System.out.println("当前参数: " + req.arguments());
                    System.out.print("输入新参数 JSON: ");
                    System.out.flush();
                    String modified = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                    if (modified.isEmpty()) {
                        System.out.println("未输入新参数，重试选项。");
                        continue;
                    }
                    // 简单校验 JSON 合法性
                    try {
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(modified);
                    } catch (Exception e) {
                        System.out.println("JSON 不合法: " + e.getMessage() + "，重试选项。");
                        continue;
                    }
                    return ApprovalResult.modify(modified);
                }
                default -> {
                    System.out.print("未识别输入，请选择 [y/a/n/s/m]: ");
                    System.out.flush();
                }
            }
        }
        // 5 次无效输入，保守拒绝
        System.out.println("多次无效输入，默认拒绝。");
        return ApprovalResult.reject("多次无效输入");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return approvedAllTools.contains(toolName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllTools.clear();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
