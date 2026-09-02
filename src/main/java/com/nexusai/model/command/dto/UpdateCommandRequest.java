package com.nexusai.model.command.dto;

import java.util.List;

/**
 * PATCH /api/command/{id} 更新请求 · 所有字段可选
 *
 * <p>与 {@link CreateCommandRequest} 相同结构，但 name 非必填。
 * 未提供的字段保持原值不变。
 */
public record UpdateCommandRequest(
    String name,
    String description,
    String content,
    List<String> aliases,
    List<String> allowedTools,
    String model,
    String context,
    String agent,
    List<String> paths,
    String version,
    String argumentHint,
    String whenToUse,
    String effort,
    String hooks,
    Boolean userInvocable,
    Boolean disableModelInvocation,
    Boolean enabled
) {}
