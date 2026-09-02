package com.nexusai.model.project.dto;

import jakarta.validation.constraints.NotBlank;

/** PUT /api/v1/sessions/{sessionId}/project 请求 · 把项目绑定到会话 */
public record ProjectBindRequest(
    @NotBlank String projectId
) {}
