package com.nexusai.model.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/projects 请求 · 注册一个新项目 */
public record ProjectCreateRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 1024) String path
) {}
