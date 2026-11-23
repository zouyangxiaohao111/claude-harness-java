package com.nexusai.model.market.dto;

import java.util.Map;

/**
 * 市场技能预览 · {@code GET /api/market/skills/{name}/preview}。
 *
 * @param name        技能名（请求路径）
 * @param version     版本（从清单/参数解析；可空）
 * @param slug        包内 skills/&lt;slug&gt; 目录名
 * @param markdown    SKILL.md 全文
 * @param frontmatter YAML frontmatter（无则空 Map）
 * @param iconDataUrl 包内图标（data:image/...;base64），无则 null
 * @param contentLength 正文字节数
 * @param cached      是否命中缓存
 */
public record MarketSkillPreviewDto(
        String name,
        String version,
        String slug,
        String markdown,
        Map<String, Object> frontmatter,
        String iconDataUrl,
        int contentLength,
        boolean cached
) {
}
