package com.nexusai.model.session.dto;

/**
 * 分类批量开关请求 · PATCH /api/v1/sessions/{sessionId}/tools（[tool-categories]）。
 *
 * <p>{@code category} 取值只能是 {@code CU} / {@code KB} / {@code BR} / {@code SYS}
 * （校验见 {@code SessionToolsController.setCategoryEnabled}，非法值 → 400）。
 * {@code enabled=false} = 该分类下全部工具禁用（后端跳过核心编排工具
 * Agent/TaskStop/SendMessage）；{@code true} = 该分类全部启用。
 */
public record SessionToolCategoryToggleRequest(
    /** 分类（CU / KB / BR / SYS） */
    String category,
    /** true = 全部启用；false = 全部禁用（跳过核心编排工具） */
    boolean enabled
) {}
