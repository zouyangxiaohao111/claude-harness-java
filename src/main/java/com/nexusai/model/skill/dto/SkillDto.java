package com.nexusai.model.skill.dto;

/**
 * 响应：Skill 完整信息
 * - content：来自 skill.json 的正文（默认空字符串）
 * - tags：来自 skill.json 的标签列表
 * - config：来自 DB（任意 JSON 形状）
 */
public record SkillDto(
    String id,
    String name,
    String description,
    boolean enabled,
    boolean builtin,
    String content,         // skill.json.content
    java.util.List<String> tags,   // skill.json.tags
    Object config           // DB config (任意 JSON)
) {}
