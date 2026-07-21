package com.yukicli.hitl;

/**
 * 审批结果 record。
 *
 * Decision 含义：
 *   - APPROVED      批准本次
 *   - APPROVED_ALL  批准本次会话所有后续同类危险操作
 *   - REJECTED      拒绝（可填拒绝原因）
 *   - MODIFIED      修改参数后执行
 *   - SKIPPED       跳过本步骤（视为拒绝但不记拒绝原因）
 */
public record ApprovalResult(Decision decision, String modifiedArguments, String reason) {

    public enum Decision { APPROVED, APPROVED_ALL, REJECTED, MODIFIED, SKIPPED }

    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null, "");
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null, "");
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, null, reason == null ? "" : reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(Decision.MODIFIED, modifiedArguments, "");
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null, "");
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED || decision == Decision.APPROVED_ALL || decision == Decision.MODIFIED;
    }

    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }

    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    /** 返回实际生效的参数：MODIFIED 用 modifiedArguments，其他用原参数 */
    public String effectiveArguments(String original) {
        return decision == Decision.MODIFIED ? modifiedArguments : original;
    }
}
