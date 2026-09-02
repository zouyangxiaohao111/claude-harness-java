package com.nexusai.application.agent.tool;

/**
 * git diff 结构化输出 · 对齐 CC {@code gitDiffSchema}
 * （Open-ClaudeCode/src/tools/FileEditTool/types.ts:46-60）。
 *
 * @param filename   文件路径（相对 git 根，'/' 分隔 · CC gitDiffSchema filename）
 * @param status     'modified' | 'added'（CC gitDiffSchema status）
 * @param additions  新增行数（CC gitDiffSchema additions）
 * @param deletions  删除行数（CC gitDiffSchema deletions）
 * @param changes    变更总行数 = additions + deletions（CC gitDiffSchema changes）
 * @param patch      统一 diff 的 hunk 内容（从 @@ 开始 · CC gitDiffSchema patch）
 * @param repository GitHub owner/repo，无则为 null（CC gitDiffSchema repository）
 */
public record ToolUseDiff(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        String repository) {
}
