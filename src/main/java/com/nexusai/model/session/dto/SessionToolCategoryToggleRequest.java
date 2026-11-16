package com.nexusai.model.session.dto;

/**
 * 分类批量开关请求 · PATCH /api/v1/sessions/{sessionId}/tools（[tool-categories]）。
 *
 * <p>{@code category} 取值只能是 {@code CU} / {@code KB} / {@code BR} / {@code MCP} / {@code SYS}
 * （校验见 {@code SessionToolsController.setCategoryEnabled}，非法值 → 400）。
 * {@code enabled=false} = 该分类下全部工具禁用（后端跳过核心编排工具
 * Agent/TaskStop/SendMessage）；{@code true} = 该分类全部启用。
 *
 * <p><b>[tool-categories] mcpServer（三级联动的中间一级，2026-11-16）</b>：
 * <ul>
 *   <li>{@code null / 空} —— 按分类整体操作：{@code {category:"MCP", enabled:false}} 关掉本会话
 *       全部第三方 MCP 工具；</li>
 *   <li>非空 —— 只操作该 server：{@code {category:"MCP", mcpServer:"rea", enabled:false}}
 *       只关 rea 的工具。⚠️ 仅在 {@code category=MCP} 时合法，与其它分类组合 → 400
 *       （不静默忽略，避免前端以为「按 server 关掉了」而实际整类被关）。</li>
 * </ul>
 */
public record SessionToolCategoryToggleRequest(
    /** 分类（CU / KB / BR / MCP / SYS） */
    String category,
    /** true = 全部启用；false = 全部禁用（跳过核心编排工具） */
    boolean enabled,
    /** 可选 · MCP server 名（null = 按分类整体；非空 = 只操作该 server 的工具，仅 category=MCP 合法） */
    String mcpServer
) {}
