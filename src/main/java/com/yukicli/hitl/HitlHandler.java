package com.yukicli.hitl;

/**
 * HITL 审批处理器接口。
 *
 * 实现类负责与用户交互（终端 / GUI / 自动批准等），返回 {@link ApprovalResult}。
 * 在 Multi-Agent 并行场景下，实现需自行保证 requestApproval 线程安全（避免多 Worker 同时弹审批框）。
 */
public interface HitlHandler {

    /** 请求审批 */
    ApprovalResult requestApproval(ApprovalRequest request);

    /** 是否启用 HITL 审批 */
    boolean isEnabled();

    /** 开关 HITL 审批（/hitl on / off 命令入口） */
    void setEnabled(boolean enabled);

    /** 指定工具是否已批准"全部放行"（同一会话内） */
    boolean isApprovedAllByTool(String toolName);

    /** 清空"全部放行"集合（/clear 或显式重置时调用） */
    void clearApprovedAll();
}
