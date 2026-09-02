package com.nexusai.model.session.dto;

import com.nexusai.model.provider.dto.ModelTag;

/** POST /api/v1/sessions 请求 · 至少需要 title 或 modelName 之一 */
public record SessionCreateRequest(
    String title,
    ModelTag model,                 // 缺省后端默认 DS
    String modelName,               // 可选 · 仅显式传入时落库 sessions.model_name（会话 override）；缺省 null，读时运行时解析
    String mainProjectId,
    Boolean bareMode                // 可选 · 会话级 bare（精简）模式开关（V33 列 bare_mode）；null = 不设置（回落 env/默认 false）
) {}
