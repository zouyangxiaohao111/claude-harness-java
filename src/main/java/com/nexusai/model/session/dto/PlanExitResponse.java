package com.nexusai.model.session.dto;

/**
 * 计划审批「退出并执行」响应。
 *
 * @param sessionId 继续执行的目标会话（clearContext=true 时为新建会话，否则为原会话）
 * @param cleared   是否新建了上下文
 */
public record PlanExitResponse(
    String sessionId,
    boolean cleared
) {}
