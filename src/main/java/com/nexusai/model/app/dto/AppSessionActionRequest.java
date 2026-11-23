package com.nexusai.model.app.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/apps/{key}/start|stop} 请求体：目标会话（应用启动/停止是<b>会话级</b>）。
 *
 * @param sessionId 会话 id（必填；启动会写入该会话 enabled_apps）
 */
public record AppSessionActionRequest(@NotBlank String sessionId) {}
