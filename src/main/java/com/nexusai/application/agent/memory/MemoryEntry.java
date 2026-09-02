package com.nexusai.application.agent.memory;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 单条记忆记录 · 对齐 CC memoryScan.ts:13-19 MemoryHeader（filename/filePath/mtimeMs/description/type）
 *
 * @param type        4 类记忆之一（user/feedback/project/reference）；可为 {@code null}（对应 CC
 *                    undefined，memoryTypes.ts:28-31 parseMemoryType 未知/缺失 → undefined）
 * @param description 一句话描述（CC memoryScan.ts:60 {@code frontmatter.description || null}；
 *                    manifest 行格式 memoryScan.ts:84-94）
 * @param filename    相对记忆目录的文件名（CC memoryScan.ts:57 filename=relativePath；递归目录时
 *                    形如 {@code sub/deep/c.md}，非 basename）
 * @param filePath    记忆文件完整路径
 * @param mtime       文件修改时间（CC mtimeMs）
 */
public record MemoryEntry(
    MemoryType type,
    String description,
    String filename,
    Path filePath,
    Instant mtime
) {
}
