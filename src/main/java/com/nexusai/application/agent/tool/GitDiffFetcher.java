package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.plugin.GitProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单文件 git diff 获取器 · 对齐 CC {@code fetchSingleFileGitDiff}
 * （Open-ClaudeCode/src/utils/gitDiff.ts:405-441）。
 *
 * <p>门控默认关（对齐 CC FileEditTool.ts:544-554 / FileWriteTool.ts:344-353）：
 * 仅当 env {@code CLAUDE_CODE_REMOTE} truthy 且 feature
 * {@code tengu_quartz_lantern}（Java 端以 system property 承载）truthy 才触发。
 * truthy 语义对齐 CC isEnvTruthy（envUtils.ts:32-37）：'1'/'true'/'yes'/'on'（忽略大小写/首尾空白）。
 *
 * <p>流程（对齐 CC gitDiff.ts:405-441）：
 * <ol>
 *   <li>findGitRoot 向上找 .git（目录或文件，兼容 worktree/submodule）；找不到返回 null</li>
 *   <li>ls-files --error-unmatch 判断是否已跟踪；已跟踪 → diff merge-base(HEAD, base) 或 HEAD</li>
 *   <li>未跟踪 → 生成 synthetic diff（全部行算新增）</li>
 * </ol>
 *
 * <p>测试注入：{@link #setExecutor} 用桩覆盖 git 执行器（对齐 DynamicSkillsManager.setGitExec 模式），
 * 单测强制开 gate 覆盖 tracked/untracked 两分支。
 */
public final class GitDiffFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitDiffFetcher.class);

    /** truthy 值集合 · CC isEnvTruthy（envUtils.ts:36）。 */
    private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "on");

    /** 测试注入的 git 执行器（null = 用 GitProcessRunner 默认）。 */
    private static volatile GitProcessRunner.Executor executorOverride;

    private GitDiffFetcher() {
    }

    /** 测试桩注入（与 DynamicSkillsManager.setGitExec 同模式）。 */
    public static void setExecutor(GitProcessRunner.Executor executor) {
        executorOverride = executor;
        if (log.isDebugEnabled()) {
            log.debug("GitDiffFetcher: 注入 git 执行器桩 executor={}", executor != null);
        }
    }

    public static void clearExecutor() {
        executorOverride = null;
    }

    /** gate 是否开启：env CLAUDE_CODE_REMOTE truthy && property tengu_quartz_lantern truthy（默认关）。 */
    public static boolean isEnabled() {
        String remote = System.getenv("CLAUDE_CODE_REMOTE");
        if (remote == null) {
            // 测试/容器注入通道：system property 兜底（生产仅 env，不受影响）
            remote = System.getProperty("CLAUDE_CODE_REMOTE", "false");
        }
        String lantern = System.getProperty("tengu.quartz.lantern", "false");
        boolean enabled = isTruthy(remote) && isTruthy(lantern);
        if (enabled && log.isDebugEnabled()) {
            log.debug("GitDiffFetcher: gitDiff 门控开启（CLAUDE_CODE_REMOTE truthy && tengu_quartz_lantern truthy）");
        }
        return enabled;
    }

    /**
     * 获取单文件 diff；gate 关闭或非 git 仓库/命令失败返回 null。
     *
     * @param absoluteFilePath 目标文件绝对路径
     * @return ToolUseDiff（对齐 gitDiffSchema）；不可得返回 null
     */
    public static ToolUseDiff fetch(Path absoluteFilePath) {
        long start = System.nanoTime();
        // 1. findGitRoot（CC git.ts findGitRoot：从文件父目录向上找 .git，dir 或 file）
        Path gitRoot = findGitRoot(absoluteFilePath.toAbsolutePath().normalize().getParent());
        if (gitRoot == null) {
            if (log.isDebugEnabled()) {
                log.debug("GitDiffFetcher: 非 git 仓库，跳过 gitDiff path={}", absoluteFilePath);
            }
            return null;
        }
        // gitPath 相对 git 根，'/' 分隔（CC gitDiff.ts:409-410）
        String gitPath = gitRoot.relativize(absoluteFilePath.toAbsolutePath().normalize())
                .toString().replace('\\', '/');

        // 2. 是否已跟踪（CC gitDiff.ts:413-419 ls-files --error-unmatch）
        GitProcessRunner runner = runner();
        GitProcessRunner.Result lsResult = runNoLocks(runner, List.of("ls-files", "--error-unmatch", gitPath), gitRoot);
        ToolUseDiff diff;
        if (lsResult.ok()) {
            // 已跟踪 → diff <diffRef> -- path
            String diffRef = getDiffRef(runner, gitRoot);
            GitProcessRunner.Result diffResult = runNoLocks(
                    runner, List.of("diff", diffRef, "--", gitPath), gitRoot);
            if (!diffResult.ok() || diffResult.stdout() == null || diffResult.stdout().isEmpty()) {
                return null;
            }
            diff = parseRawDiffToToolUseDiff(gitPath, diffResult.stdout(), "modified");
        } else {
            // 未跟踪 → synthetic diff（CC gitDiff.ts:437-440 + generateSyntheticDiff :479-520）
            diff = generateSyntheticDiff(gitPath, absoluteFilePath);
            if (diff == null) {
                return null;
            }
        }
        long costMs = (System.nanoTime() - start) / 1_000_000L;
        if (log.isDebugEnabled()) {
            log.debug("GitDiffFetcher: 生成 gitDiff 完成 path={} status={} additions={} deletions={} 耗时={}ms",
                gitPath, diff.status(), diff.additions(), diff.deletions(), costMs);
        }
        return diff;
    }

    // ── 内部 ──

    private static GitProcessRunner runner() {
        if (executorOverride != null) {
            return new GitProcessRunner(executorOverride, "git");
        }
        return new GitProcessRunner();
    }

    /**
     * 执行带 --no-optional-locks 的 git 命令（CC 各调用统一前缀）· [G17②] 3s 超时对齐 CC
     * {@code SINGLE_FILE_DIFF_TIMEOUT_MS = 3000}（gitDiff.ts:384，fetchSingleFileGitDiff
     * 全部 execFileNoThrowWithCwd 均传此超时；旧实现走 GitProcessRunner 默认 120s）。
     */
    private static GitProcessRunner.Result runNoLocks(GitProcessRunner runner, List<String> args, Path cwd) {
        List<String> fullArgs = new ArrayList<>(args.size() + 1);
        fullArgs.add("--no-optional-locks");
        fullArgs.addAll(args);
        return runner.run(fullArgs, cwd.toString(), GitProcessRunner.gitNoPromptEnv(),
            SINGLE_FILE_DIFF_TIMEOUT_MS);
    }

    /** findGitRoot：从 start 向上找含 .git（目录或文件）的目录；找不到返回 null。 */
    static Path findGitRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 选择 diff 基准 ref（CC gitDiff.ts:486-495 getDiffRef）：
     * 优先 CLAUDE_CODE_BASE_REF env；否则 merge-base HEAD 默认分支；失败回退 HEAD。
     */
    private static String getDiffRef(GitProcessRunner runner, Path gitRoot) {
        String baseBranch = System.getenv("CLAUDE_CODE_BASE_REF");
        if (baseBranch == null || baseBranch.isEmpty()) {
            GitProcessRunner.Result branchResult =
                    runNoLocks(runner, List.of("symbolic-ref", "--short", "refs/remotes/origin/HEAD"), gitRoot);
            baseBranch = branchResult.ok() ? branchResult.stdout().trim() : null;
            if (baseBranch == null || baseBranch.isEmpty()) {
                GitProcessRunner.Result configResult =
                        runNoLocks(runner, List.of("config", "--get", "init.defaultBranch"), gitRoot);
                baseBranch = configResult.ok() ? configResult.stdout().trim() : null;
            }
            if (baseBranch == null || baseBranch.isEmpty()) {
                baseBranch = "main";
            }
        }
        GitProcessRunner.Result mergeBase =
                runNoLocks(runner, List.of("merge-base", "HEAD", baseBranch), gitRoot);
        if (mergeBase.ok() && mergeBase.stdout() != null && !mergeBase.stdout().trim().isEmpty()) {
            return mergeBase.stdout().trim();
        }
        return "HEAD";
    }

    /**
     * 解析统一 diff 为结构化形状（CC gitDiff.ts:446-472 parseRawDiffToToolUseDiff）：
     * 只取 @@ 开始的 hunk 段作为 patch，统计 + / − 行数（排除 +++ / ---）。
     */
    static ToolUseDiff parseRawDiffToToolUseDiff(String filename, String rawDiff, String status) {
        List<String> patchLines = new ArrayList<>();
        boolean inHunks = false;
        int additions = 0;
        int deletions = 0;
        String[] lines = rawDiff.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("@@")) {
                inHunks = true;
            }
            if (inHunks) {
                patchLines.add(line);
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
            }
        }
        return new ToolUseDiff(filename, status, additions, deletions,
                additions + deletions, String.join("\n", patchLines), null);
    }

    /**
     * 未跟踪文件的 synthetic diff（CC gitDiff.ts:479-520 generateSyntheticDiff）：
     * {@code @@ -0,0 +1,N @@} + 每行前加 '+'；超大小限制（MAX_DIFF_SIZE_BYTES）返回 null。
     */
    static ToolUseDiff generateSyntheticDiff(String gitPath, Path absoluteFilePath) {
        try {
            long sizeBytes = Files.size(absoluteFilePath);
            if (sizeBytes > MAX_DIFF_SIZE_BYTES) {
                if (log.isDebugEnabled()) {
                    log.debug("GitDiffFetcher: 文件超 synthetic diff 大小限制 size={} max={}",
                        sizeBytes, MAX_DIFF_SIZE_BYTES);
                }
                return null;
            }
            String content = Files.readString(absoluteFilePath);
            String[] split = content.split("\n", -1);
            List<String> lines = new ArrayList<>(split.length);
            for (String s : split) {
                lines.add(s);
            }
            // 去掉尾随空行（文件以 \n 结尾）
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            int lineCount = lines.size();
            StringBuilder sb = new StringBuilder("@@ -0,0 +1,").append(lineCount).append(" @@");
            for (String line : lines) {
                sb.append('\n').append('+').append(line);
            }
            return new ToolUseDiff(gitPath, "added", lineCount, 0, lineCount, sb.toString(), null);
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("GitDiffFetcher: 读取未跟踪文件失败，跳过 synthetic diff path={} cause={}",
                    absoluteFilePath, e.toString());
            }
            return null;
        }
    }

    /** synthetic diff 大小上限 · [G17②] 对齐 CC MAX_DIFF_SIZE_BYTES = 1_000_000（1MB，gitDiff.ts:37）；
     *  旧实现 5MB 偏离 CC（CC 对 >1MB 未跟踪文件跳过 synthetic diff 返回 null）。 */
    private static final long MAX_DIFF_SIZE_BYTES = 1_000_000L;

    /** 单文件 diff git 命令超时 · 对齐 CC SINGLE_FILE_DIFF_TIMEOUT_MS = 3000（gitDiff.ts:384）。 */
    private static final long SINGLE_FILE_DIFF_TIMEOUT_MS = 3000L;

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        return TRUTHY.contains(value.toLowerCase(Locale.ROOT).trim());
    }
}
