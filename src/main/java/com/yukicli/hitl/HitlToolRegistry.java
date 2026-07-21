package com.yukicli.hitl;

import com.yukicli.policy.AuditLog;
import com.yukicli.tool.ToolRegistry;

/**
 * HITL 装饰器 —— 继承 ToolRegistry，覆写 execute 在执行前插入审批。
 *
 * 装饰器模式而非代理：因为 ToolRegistry 是主入口，继承后 HitlToolRegistry 可直接替换原 ToolRegistry，
 * Agent / SubAgent 无需感知 HITL 存在。
 *
 * 审批流：
 *   1. HITL 关闭 / 工具非危险 / 已 approveAll → 直接走父类 execute
 *   2. 走 HitlHandler.requestApproval
 *   3. APPROVED → 走父类 execute（传 effectiveArguments）
 *   4. REJECTED → 写 denyByHitl audit，返回拒绝消息
 *   5. SKIPPED → 写 denyByHitl audit，返回跳过消息
 *   6. MODIFIED → 走父类 execute（传 modifiedArguments）
 *   7. APPROVED_ALL → 加入 approvedAll 集合，走父类 execute
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    public HitlToolRegistry(HitlHandler hitlHandler, String projectRoot) {
        super(projectRoot);
        this.hitlHandler = hitlHandler;
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }

    @Override
    public String execute(String toolName, String arguments) {
        // HITL 关闭 / 工具非危险 / 已 approveAll → 直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(toolName)) {
            return super.execute(toolName, arguments);
        }
        if (hitlHandler.isApprovedAllByTool(toolName)) {
            return super.execute(toolName, arguments);
        }

        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(toolName, arguments);
        ApprovalResult result;
        try {
            result = hitlHandler.requestApproval(request);
        } catch (Exception e) {
            // 审批器异常，保守拒绝
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                toolName, arguments, "审批器异常: " + e.getMessage(), elapsedMs));
            return "[HITL] 审批器异常，已拒绝: " + e.getMessage();
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        if (result.isApprovedAll()) {
            // approveAll 由 HitlHandler 内部维护集合，这里直接放行
            return super.execute(toolName, result.effectiveArguments(arguments));
        }
        if (result.isApproved()) {
            return super.execute(toolName, result.effectiveArguments(arguments));
        }
        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                toolName, arguments, "用户跳过", elapsedMs));
            return "[HITL] 操作已被跳过";
        }
        // REJECTED
        getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
            toolName, arguments, result.reason(), elapsedMs));
        return "[HITL] 操作已被拒绝：" + result.reason();
    }
}
