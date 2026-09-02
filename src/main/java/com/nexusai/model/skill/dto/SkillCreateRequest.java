package com.nexusai.model.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /api/v1/skills 请求 · PATCH 也复用此结构（全字段可选）
 * - name / description / content：会同时写入 skill.json 和 DB
 * - config：仅写入 DB（任意 JSON 形状，存为 TEXT）
 * - tags：仅写入 skill.json（DB 不存）
 */
public record SkillCreateRequest(
    @NotBlank @Size(max = 64) String name,
    String description,
    String content,                                   // 写入 skill.json.content
    List<String> tags,                                // 写入 skill.json.tags
    Boolean enabled,                                  // 缺省 = true
    Object config                                     // 任意 JSON 形状
) {}
