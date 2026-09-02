package com.nexusai.model.session.dto;

import com.nexusai.model.provider.dto.ModelTag;

/** PATCH /api/v1/sessions/{id} 请求 · 全字段可选 */
public record SessionUpdateRequest(
    String title,
    ModelTag model,
    String modelName,
    String mainProjectId,
    Boolean bareMode,               // 可选 · 会话级 bare（精简）模式开关（V33 列 bare_mode）；null = 不改动
    String permissionMode,          // 可选 · 会话级权限模式覆盖（V44 列 permission_mode）；null = 不改动
    String mainThreadAgent          // 可选 · 会话指定主线程 agent（V58 列 main_thread_agent）；null = 不改动
) {}
