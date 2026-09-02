package com.nexusai.application.agent.worktree;

import com.nexusai.application.agent.skill.NexusaiPaths;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * s18 Worktree 路径与 slug 校验工具 — 对齐 CC worktree.ts:48 (VALID_WORKTREE_SLUG_SEGMENT),
 * worktree.ts:66-87 (validateWorktreeSlug), worktree.ts:204-206 (worktreesDir),
 * worktree.ts:217-223 (flattenSlug + worktreeBranchName).
 *
 * <p>L1 行为对齐:
 * <ul>
 *   <li>slug 总长 ≤ 64（CC MAX_WORKTREE_SLUG_LENGTH=64，worktree.ts:49）</li>
 *   <li>每段路径必须匹配 {@code [a-zA-Z0-9._-]+}（空段因 regex + 而失败，拒绝首尾 '/'）</li>
 *   <li>拒绝 {@code .} / {@code ..} 段 (路径穿越防护)</li>
 *   <li>扁平化: {@code user/feature} → {@code worktree-user+feature} (避免 git D/F conflict)</li>
 *   <li>worktrees 根目录: {@code <gitRoot>/.nexusai/worktrees/}（决策 D7 变更：nexusai 自有根，不迁就 claude）</li>
 * </ul>
 */
public final class WorktreePaths {

    /** CC worktree.ts:48 - 每段合法字符 regex */
    public static final String VALID_WORKTREE_SLUG_SEGMENT = "[a-zA-Z0-9._-]+";

    /** CC worktree.ts:49 - slug 总长上限（非单段/段数上限） */
    public static final int MAX_WORKTREE_SLUG_LENGTH = 64;

    private static final Pattern SEGMENT_PATTERN = Pattern.compile(VALID_WORKTREE_SLUG_SEGMENT);

    private WorktreePaths() {
        // utility class
    }

    /**
     * 校验 worktree slug — 对齐 CC worktree.ts:66-87 validateWorktreeSlug.
     *
     * <p>规则（逐字对齐 CC）：<b>总长 ≤ 64</b>（CC worktree.ts:67-71），
     * 拒绝 {@code .} / {@code ..} 段（:76-80），每段必须匹配
     * {@code [a-zA-Z0-9._-]+}（:81-85，空段因 + 而失败 → 隐含拒绝首尾 '/'）。
     * Java 显式拒绝首尾 '/'（Java split 丢弃尾部空串，需显式补闸）。
     *
     * @param slug 例如 "my-feature" 或 "user/auth-refactor"
     * @throws IllegalArgumentException slug 不合法
     */
    public static void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("worktree slug must not be blank");
        }
        if (slug.length() > MAX_WORKTREE_SLUG_LENGTH) {
            throw new IllegalArgumentException("Invalid worktree name: must be "
                    + MAX_WORKTREE_SLUG_LENGTH + " characters or fewer (got " + slug.length() + ")");
        }
        if (slug.startsWith("/") || slug.endsWith("/")) {
            throw new IllegalArgumentException("worktree slug must not start or end with '/': " + slug);
        }
        String[] segments = slug.split("/");
        for (String seg : segments) {
            if (seg.isEmpty()) {
                throw new IllegalArgumentException("worktree slug contains empty segment: " + slug);
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("worktree slug segment must not be '.' or '..': " + seg);
            }
            if (!SEGMENT_PATTERN.matcher(seg).matches()) {
                throw new IllegalArgumentException("worktree slug segment has invalid characters: " + seg
                        + " (allowed: [a-zA-Z0-9._-])");
            }
        }
    }

    /**
     * 计算 worktree 目录路径 — 对齐 CC worktree.ts:204-206 worktreesDir.
     *
     * <p>决策 D7 变更（2026-08-30，用户拍板）：nexusai 复刻版自有根，worktree 存放目录
     * {@code <gitRoot>/.nexusai/worktrees/}（原 {@code .claude/worktrees/}）。worktree 是 git
     * 级结构（跟随项目仓库），与用户级 {@code ~/.nexusai}（NexusaiPaths）不同——本目录在
     * <b>项目内</b>（{@code gitRoot} 下），故用固定 {@code .nexusai} 子目录而非用户级根。
     *
     * @param gitRoot git 仓库根目录
     * @return {@code <gitRoot>/.nexusai/worktrees/}
     */
    public static Path worktreesDir(Path gitRoot) {
        if (gitRoot == null) {
            throw new IllegalArgumentException("gitRoot must not be null");
        }
        return gitRoot.resolve(NexusaiPaths.getProjectDirName()).resolve("worktrees");
    }

    /**
     * 计算具体 worktree 路径 — {@code <gitRoot>/.nexusai/worktrees/<slug>}.
     */
    public static Path worktreePathFor(Path gitRoot, String slug) {
        validateSlug(slug);
        return worktreesDir(gitRoot).resolve(slug);
    }

    /**
     * 计算分支名 — 对齐 CC worktree.ts:217-223 flattenSlug + worktreeBranchName.
     *
     * <p>{@code user/feature} → {@code worktree-user+feature} (避免 git D/F conflict).
     */
    public static String worktreeBranchName(String slug) {
        validateSlug(slug);
        return "worktree-" + flattenSlug(slug);
    }

    /**
     * 路径分隔符 '/' 替换为 '+' — 对齐 CC worktree.ts:217-219.
     */
    public static String flattenSlug(String slug) {
        validateSlug(slug);
        return slug.replace("/", "+");
    }
}