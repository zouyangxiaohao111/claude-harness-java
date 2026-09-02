package com.nexusai.model.session.dto;

/**
 * PATCH /api/v1/sessions/{sessionId}/tools/{toolName} 请求 · 会话级工具禁用/恢复（待前端对接 §29）。
 *
 * <p>{@code enabled=false} = 禁用该工具（从 LLM schema 剔除）；{@code enabled=true} = 恢复。
 */
public record SessionToolToggleRequest(
    /** false=禁用该工具；true=恢复该工具 */
    boolean enabled
) {}
