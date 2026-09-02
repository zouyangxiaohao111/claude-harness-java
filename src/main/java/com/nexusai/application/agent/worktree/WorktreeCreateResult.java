package com.nexusai.application.agent.worktree;

import java.nio.file.Path;

/**
 * s18 worktree 创建结果 — sealed interface discriminated union, 对齐 CC WorktreeCreateResult
 * (worktree.ts 中 worktree.ts:235-375 getOrCreateWorktree 返回的 existed 标志).
 *
 * <p>L1 行为: 区分新建 (Created) 和快速恢复 (Resumed) 两种语义, 调用方可据此决定后续动作
 * (新建需做 sparse-checkout / symlink / post-creation setup, 恢复则跳过).
 */
public sealed interface WorktreeCreateResult permits WorktreeCreateResult.Created, WorktreeCreateResult.Resumed {

    /** worktree 在磁盘上的路径 */
    Path worktreePath();

    /** worktree 关联的分支名 */
    String worktreeBranch();

    /** git 仓库根目录 (canonical, 非 worktree 内 .git) */
    Path gitRoot();

    /**
     * 新创建分支 + worktree — 对齐 CC worktree.ts worktree was freshly created.
     * <p>基线 HEAD commit 经 {@code WorktreeService.worktreeHeadCommits} map 流转
     * （gap3-discarded，对齐 CC countWorktreeChanges rev-list base..HEAD），record 不承载。
     */
    record Created(Path worktreePath, String worktreeBranch, Path gitRoot) implements WorktreeCreateResult {}

    /**
     * 已存在的 worktree 被快速恢复 (跳过 fetch + add) — 对齐 CC readWorktreeHeadSha fast path.
     * <p>基线 HEAD commit 同 Created，经 map 流转。
     */
    record Resumed(Path worktreePath, String worktreeBranch, Path gitRoot) implements WorktreeCreateResult {}
}