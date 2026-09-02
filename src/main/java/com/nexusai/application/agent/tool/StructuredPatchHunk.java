package com.nexusai.application.agent.tool;

import java.util.List;

/**
 * 结构化 diff hunk · 对齐 CC {@code hunkSchema}
 * （Open-ClaudeCode/src/tools/FileEditTool/types.ts:36-44）。
 *
 * @param oldStart 旧文件起始行号（1-based；纯插入时指向插入锚点行，CC hunkSchema oldStart）
 * @param oldLines 旧文件受影响行数（CC hunkSchema oldLines）
 * @param newStart 新文件起始行号（CC hunkSchema newStart）
 * @param newLines 新文件受影响行数（CC hunkSchema newLines）
 * @param lines    hunk 内容行，前缀 ' '（上下文）/'+'（新增）/'−'（删除）（CC hunkSchema lines[]）
 */
public record StructuredPatchHunk(
        int oldStart,
        int oldLines,
        int newStart,
        int newLines,
        List<String> lines) {
}
