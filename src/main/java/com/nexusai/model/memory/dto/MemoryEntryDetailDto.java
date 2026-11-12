package com.nexusai.model.memory.dto;

/**
 * 记忆条目详情（单条正文 · 点开某条时才读）。
 *
 * @param type        frontmatter {@code type:} 原值；缺失 → {@code null}
 * @param filename    相对记忆目录的文件名（单层 .md）
 * @param content     文件正文（UTF-8 全文，含 frontmatter）
 * @param mtimeMs     文件修改时间（epoch millis）
 * @param sizeBytes   文件字节数
 */
public record MemoryEntryDetailDto(
    String type,
    String filename,
    String content,
    long mtimeMs,
    long sizeBytes
) {
}
