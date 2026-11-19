package com.nexusai.model.session.dto;

/**
 * 计划审批「退出并执行」请求 · 对齐 CC ExitPlanMode 审批卡的两条写入：
 * 恢复会话权限模式（{@code setMode}）与可选的「清空上下文后执行」。
 *
 * @param plan         用户批准的计划正文（可含用户编辑），作为新会话首条执行输入由前端发送
 * @param mode         执行方式（CC 串：acceptEdits / default / bypassPermissions / auto / dontAsk）
 * @param clearContext true = 新建会话并在其中执行（当前上下文保留为历史）；
 *                     false/null = 在当前会话恢复模式并继续
 */
public record PlanExitRequest(
    String plan,
    String mode,
    Boolean clearContext
) {}
