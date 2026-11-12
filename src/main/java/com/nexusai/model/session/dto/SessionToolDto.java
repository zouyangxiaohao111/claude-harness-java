package com.nexusai.model.session.dto;

/**
 * 会话级工具列表条目 · GET/PATCH /api/v1/sessions/{sessionId}/tools。
 *
 * <p>供前端工具管理 UI：展示当前会话可见工具（经基链过滤（bare/deny/coordinator），
 * 被禁工具保留在列表 disabled=true，前端可恢复）+ 禁用标志。name 为内部唯一标识
 * （{@code Tool.name()}），userFacingName 为展示名
 * （{@code Tool.userFacingName()}，Tool.java:660 default = name），disabled =
 * 是否在该会话禁用集合（sessions.disabled_tools，V34 列）中。
 *
 * <p><b>[tool-categories] category</b>：工具分类（{@code CU}/{@code KB}/{@code BR}/{@code SYS}），
 * 由 {@link com.nexusai.application.agent.tool.SessionToolCategory#of} 判定
 * （MCP server 名前缀 / 实现类包名），前端据此分组渲染。分类归属只在后端判一次，
 * 前端不写死前缀（否则同一规则多处各抄一份，工具改名必漏）。
 *
 * <p><b>[tool-categories] description</b>：{@code Tool.description()} 原文，<b>不截断</b> ——
 * 截断由前端按行数做（CSS line-clamp），避免后端与前端两套截断口径漂移。
 */
public record SessionToolDto(
    /** 工具名（内部唯一标识，如 "Bash"） */
    String name,
    /** 展示名 = tool.userFacingName()（Tool.java:660 default） */
    String userFacingName,
    /** 分类：CU=电脑操作 / KB=知识库 / BR=浏览器控制 / SYS=系统工具 */
    String category,
    /** 工具描述原文（Tool.description()，不截断） */
    String description,
    /** 是否在该会话禁用集合中（V34 列 disabled_tools） */
    boolean disabled
) {}
