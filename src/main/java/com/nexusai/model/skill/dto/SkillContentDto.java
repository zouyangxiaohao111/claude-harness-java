package com.nexusai.model.skill.dto;

import java.util.List;

/**
 * 本机已安装技能正文 · {@code GET /api/v1/skills/{name}/content}。
 *
 * @param name        技能名（目录名）
 * @param displayName frontmatter name（可空）
 * @param description 描述
 * @param version     版本
 * @param content     SKILL.md 正文
 * @param contentPath SKILL.md 路径（只读展示）
 * @param baseDir     技能根目录
 * @param whenToUse   使用时机（frontmatter）
 * @param allowedTools 允许工具（frontmatter）
 */
public record SkillContentDto(
        String name,
        String displayName,
        String description,
        String version,
        String content,
        String contentPath,
        String baseDir,
        String whenToUse,
        List<String> allowedTools
) {
}
