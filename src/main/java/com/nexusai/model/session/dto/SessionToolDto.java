package com.nexusai.model.session.dto;

/**
 * 会话级工具列表条目 · GET/PATCH /api/v1/sessions/{sessionId}/tools（待前端对接 §29）。
 *
 * <p>供前端工具管理 UI：展示当前会话可见工具（经基链过滤（bare/deny/coordinator），
 * 被禁工具保留在列表 disabled=true，前端可恢复）+ 禁用标志。name 为内部唯一标识
 * （{@code Tool.name()}），userFacingName 为展示名
 * （{@code Tool.userFacingName()}，Tool.java:660 default = name），disabled =
 * 是否在该会话禁用集合（sessions.disabled_tools，V34 列）中。
 */
public record SessionToolDto(
    /** 工具名（内部唯一标识，如 "Bash"） */
    String name,
    /** 展示名 = tool.userFacingName()（Tool.java:660 default） */
    String userFacingName,
    /** 是否在该会话禁用集合中（V34 列 disabled_tools） */
    boolean disabled
) {}
