package com.nexusai.model.memory.dto;

/**
 * 记忆条目摘要（列表用 · <b>不含正文</b>）。
 *
 * <p>WHY 不返回 content：本机 492 条 × 平均 6.5KB ≈ 3.2MB，一次拉全量会把列表接口拖慢；
 * 正文走 {@code GET /memory/entries/{file}} 单条读取。
 *
 * @param type        frontmatter {@code type:} 原值（user/feedback/project/reference），
 *                    缺失/非法 → {@code null}（对应 {@code MemoryType.fromString} 的降级语义）
 * @param name        frontmatter {@code name:}（列表标题）；缺失回落文件名
 * @param description frontmatter {@code description:}（一行摘要）；缺失 → {@code null}
 * @param filename    相对记忆目录的文件名（单层 .md）
 * @param mtimeMs     文件修改时间（epoch millis）
 * @param sizeBytes   文件字节数；读不到 → {@code -1}
 */
public record MemoryEntrySummaryDto(
    String type,
    String name,
    String description,
    String filename,
    long mtimeMs,
    long sizeBytes
) {
}
