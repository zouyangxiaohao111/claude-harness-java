package com.nexusai.application.agent.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * s18 Worktree 主服务 — 对齐 CC utils/worktree.ts (createWorktreeForSession:702, removeAgentWorktree:961,
 * keepWorktree:780, validateWorktreeSlug:66, hasWorktreeChanges:1144)。
 *
 * <p>L1 行为:
 * <ul>
 *   <li>{@link #createWorktree}: git worktree add -B &lt;branch&gt; &lt;path&gt; (CC P2-11 用 -B 而非 -b 防止孤立分支冲突)</li>
 *   <li>{@link #removeWorktree}: 先 {@link #countChanges} 拒绝, 否则强制删除 worktree + branch</li>
 *   <li>{@link #keepWorktree}: 仅记录 keep 事件, worktree + branch 保留供 review</li>
 *   <li>{@link #createAgentWorktree}: 给 sub-agent 创建隔离 worktree, 返回 cwdOverride path</li>
 *   <li>{@link #removeAgentWorktree}: sub-agent 完成后清理</li>
 * </ul>
 *
 * <p>L1 语义对齐: 所有 git 子进程失败抛 {@link WorktreeException}; 路径操作不抛 IOException (用 try/catch 包装).
 */
@Service
public class WorktreeService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);

    private final WorktreeEventLog eventLog;

    /**
     * [R5 结果驱动] hook-based worktree 路径登记 (slug → hook stdout 路径) · 对齐 CC
     * worktree.ts:720-733 currentWorktreeSession.{worktreePath, hookBased: true}。
     *
     * <p>语义取舍: CC 是进程级单活动会话 (currentWorktreeSession module 变量)，Java 端
     * 用 slug 键内存登记（last-wins，跨会话同 slug 与 CC 单会话语义等价）；登记在
     * ExitWorktreeTool remove/keep 消费后清除（对齐 CC cleanupWorktree/keepWorktree 清 session）。
     * 不落盘（教学版，CC 持久化到 worktree-config.json）。
     */
    private final Map<String, Path> hookBasedPaths = new ConcurrentHashMap<>();

    /**
     * [gap3-discarded] worktree slug → 创建/恢复时捕获的 HEAD commit SHA · 对齐 CC
     * worktree.ts:762 {@code originalHeadCommit: headCommit}（createWorktreeForSession 把
     * headCommit 存入会话）。
     *
     * <p>Java 端会话级内存登记（同 {@link #hookBasedPaths}，last-wins，不落盘）；countChanges
     * 据此用 {@code git rev-list --count <base>..HEAD} 判定未推送 commit。缺 base → fail-closed
     * unknown → removeWorktree 拒绝（对齐 CC originalHeadCommit undefined → refuse）。
     */
    private final Map<String, String> worktreeHeadCommits = new ConcurrentHashMap<>();

    /** Spring 注入用 (默认 events.jsonl 位置) */
    public WorktreeService() {
        this((WorktreeEventLog) null);
    }

    public WorktreeService(WorktreeEventLog eventLog) {
        this.eventLog = eventLog;
    }

    /** 便捷工厂: 使用默认 events.jsonl 位置 */
    public static WorktreeService withDefaultLog(Path gitRoot) {
        return new WorktreeService(WorktreeEventLog.defaultFor(gitRoot));
    }

    /**
     * 校验 slug + 委派事件日志.
     */
    public void validateSlug(String slug) {
        WorktreePaths.validateSlug(slug);
    }

    /**
     * 创建 worktree — 对齐 CC worktree.ts:702-778 createWorktreeForSession + worktree.ts:235-375 getOrCreateWorktree.
     *
     * <p>流程:
     * <ol>
     *   <li>{@link WorktreePaths#validateSlug} 校验 slug 合法性</li>
     *   <li>检查 worktree 路径是否已存在 → 是则返回 {@link WorktreeCreateResult.Resumed} (fast path, 不 fetch)</li>
     *   <li>不存在 → {@code git worktree add -B &lt;branch&gt; &lt;path&gt;} 新建</li>
     *   <li>失败抛 {@link WorktreeException}</li>
     *   <li>事件日志: event=create / result=ok|fail</li>
     * </ol>
     *
     * @param gitRoot git 仓库根目录
     * @param slug    worktree slug (例如 "auth-refactor")
     * @return Created 或 Resumed
     * @throws WorktreeException git 命令失败
     */
    public WorktreeCreateResult createWorktree(Path gitRoot, String slug) {
        WorktreePaths.validateSlug(slug);
        if (gitRoot == null || !Files.isDirectory(gitRoot)) {
            throw new WorktreeException("gitRoot does not exist or is not a directory: " + gitRoot);
        }
        // [IMPL-10] DEL-CCE-01: WorktreeCreate fire-and-forget 发射已删除 —
        //   CC executeWorktreeCreateHook (hooks.ts:4928-4958) 是工具层结果驱动（stdout=worktreePath，
        //   失败 throw），非服务层通知式发射；结果驱动接线见 09 §2 登记。
        Path worktreePath = WorktreePaths.worktreePathFor(gitRoot, slug);
        String branch = WorktreePaths.worktreeBranchName(slug);

        // fast path: worktree 路径已存在且分支存在 → 视为 Resumed
        if (Files.isDirectory(worktreePath)) {
            log.info("[WorktreeService] resume: worktree={} branch={} already exists", worktreePath, branch);
            // [gap3-discarded] 恢复路径捕获现有 HEAD 作基线（对齐 CC worktree.ts:252 headCommit=existingHead，
            //   可 null = 非 git 目录 / rev-parse 失败，countChanges 侧 fail-closed unknown）。
            //   ConcurrentHashMap 禁止 null value → existingHead 为 null 时不登记，
            //   countChanges 走 missing-key 分支 fail-closed unknown（与注释声明一致）。
            String existingHead = readHeadCommit(worktreePath);
            if (existingHead != null) {
                worktreeHeadCommits.put(slug, existingHead);
            }
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("path", worktreePath.toString());
            extra.put("branch", branch);
            extra.put("result", "resumed");
            logEvent("create", slug, extra);
            return new WorktreeCreateResult.Resumed(worktreePath, branch, gitRoot);
        }

        // 确保 .nexusai/worktrees/ 父目录存在（决策 D7：nexusai 自有根）
        try {
            Files.createDirectories(WorktreePaths.worktreesDir(gitRoot));
        } catch (IOException e) {
            throw new WorktreeException("failed to create worktrees dir: " + e.getMessage(), e);
        }

        // [gap3-discarded] 在 git worktree add 之前捕获 base SHA（对齐 CC worktree.ts:371
        //   headCommit=baseSha），作为后续 rev-list --count <base>..HEAD 的基线；null → fail-loud。
        String baseSha = readHeadCommit(gitRoot);
        if (baseSha == null) {
            throw new WorktreeException("failed to resolve HEAD commit in git root " + gitRoot
                    + " (baseline required for worktree change detection)");
        }
        log.info("[WorktreeService] createWorktree 捕获 baseSha={} slug={}", baseSha, slug);

        // git worktree add -B <branch> <path> (CC P2-11: -B 而非 -b, 强制重置孤立分支)
        GitCommandRunner.Result r = GitCommandRunner.run(gitRoot, "worktree", "add", "-B", branch,
                worktreePath.toString());
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("path", worktreePath.toString());
        extra.put("branch", branch);
        extra.put("exitCode", String.valueOf(r.exitCode()));
        if (!r.isSuccess()) {
            extra.put("stderr", r.stderr());
            extra.put("result", "fail");
            logEvent("create", slug, extra);
            throw new WorktreeException("git worktree add failed (exit=" + r.exitCode()
                    + "): " + r.stderr());
        }
        extra.put("result", "ok");
        logEvent("create", slug, extra);
        log.info("[WorktreeService] created worktree={} branch={}", worktreePath, branch);
        worktreeHeadCommits.put(slug, baseSha);
        return new WorktreeCreateResult.Created(worktreePath, branch, gitRoot);
    }

    /**
     * 删除 worktree — 对齐 CC utils/worktree.ts:961-1020 removeAgentWorktree.
     *
     * <p>流程:
     * <ol>
     *   <li>{@link #countChanges} 检查 worktree 内变更</li>
     *   <li>有变更 + discardChanges=false → 抛 {@link WorktreeException} (拒绝)</li>
     *   <li>否则 {@code git worktree remove --force <path>}</li>
     *   <li>{@code git branch -D <branch>} 删除分支</li>
     *   <li>事件日志: event=remove</li>
     * </ol>
     *
     * @param gitRoot        git 仓库根目录
     * @param slug           worktree slug
     * @param discardChanges true 强制删除 (有变更也删), false 有变更拒绝
     * @throws WorktreeException 有变更拒绝 or git 命令失败
     */
    public void removeWorktree(Path gitRoot, String slug, boolean discardChanges) {
        WorktreePaths.validateSlug(slug);
        Path worktreePath = WorktreePaths.worktreePathFor(gitRoot, slug);
        String branch = WorktreePaths.worktreeBranchName(slug);
        // [IMPL-10] DEL-CCE-01: WorktreeRemove fire-and-forget 发射已删除（同上，
        //   CC executeWorktreeRemoveHook hooks.ts:4967-5003 结果驱动，hookRan 决定是否跳过 git remove）。

        if (!Files.isDirectory(worktreePath)) {
            log.info("[WorktreeService] remove: worktree={} not present, noop", worktreePath);
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("path", worktreePath.toString());
            extra.put("result", "noop");
            logEvent("remove", slug, extra);
            return;
        }

        WorktreeChanges changes = countChanges(gitRoot, slug);
        if (!discardChanges) {
            if (changes.unknown()) {
                // [gap3-discarded] fail-closed：无法确认 worktree 状态（缺基线 / rev-list 失败）→ 拒绝
                //   （对齐 CC ExitWorktreeTool.ts:195-200 errorCode 3）。
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("path", worktreePath.toString());
                extra.put("modifiedFiles", String.valueOf(changes.modifiedFileCount()));
                extra.put("result", "rejected");
                logEvent("remove", slug, extra);
                throw new WorktreeException("could not verify worktree state at " + worktreePath
                        + " (missing baseline commit or git rev-list failed). Refusing to remove "
                        + "without explicit confirmation. Pass discardChanges=true to force remove.");
            }
            if (changes.hasAny()) {
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("path", worktreePath.toString());
                extra.put("modifiedFiles", String.valueOf(changes.modifiedFileCount()));
                extra.put("unpushedCommits", String.valueOf(changes.unpushedCommitCount()));
                extra.put("result", "rejected");
                logEvent("remove", slug, extra);
                throw new WorktreeException("worktree has changes (modifiedFiles=" + changes.modifiedFileCount()
                        + ", unpushedCommits=" + changes.unpushedCommitCount()
                        + "); pass discardChanges=true to force remove");
            }
        }

        // git worktree remove --force <path>
        GitCommandRunner.Result r = GitCommandRunner.run(gitRoot, "worktree", "remove", "--force",
                worktreePath.toString());
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("path", worktreePath.toString());
        extra.put("branch", branch);
        extra.put("discardChanges", String.valueOf(discardChanges));
        if (!r.isSuccess()) {
            extra.put("stderr", r.stderr());
            extra.put("exitCode", String.valueOf(r.exitCode()));
            extra.put("result", "fail");
            logEvent("remove", slug, extra);
            throw new WorktreeException("git worktree remove failed (exit=" + r.exitCode()
                    + "): " + r.stderr());
        }

        // git branch -D <branch> (清理孤立分支)
        GitCommandRunner.Result br = GitCommandRunner.run(gitRoot, "branch", "-D", branch);
        extra.put("branchDeleteExitCode", String.valueOf(br.exitCode()));
        extra.put("result", br.isSuccess() ? "ok" : "partial");
        logEvent("remove", slug, extra);
        log.info("[WorktreeService] removed worktree={} branch={} discardChanges={}",
                worktreePath, branch, discardChanges);
        // [gap3-discarded] 清除基线登记（对齐 CC cleanupWorktree 清 currentWorktreeSession）
        worktreeHeadCommits.remove(slug);
    }

    /**
     * 保留 worktree + branch — 对齐 CC utils/worktree.ts:780-799 keepWorktree.
     *
     * <p>只记录 keep 事件, 不删除 worktree 路径或分支. 用户后续可手动 review + merge.
     */
    public void keepWorktree(Path gitRoot, String slug) {
        WorktreePaths.validateSlug(slug);
        Path worktreePath = WorktreePaths.worktreePathFor(gitRoot, slug);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("path", worktreePath.toString());
        extra.put("result", Files.isDirectory(worktreePath) ? "kept" : "absent");
        logEvent("keep", slug, extra);
        log.info("[WorktreeService] kept worktree={} present={}", worktreePath,
                Files.isDirectory(worktreePath));
        // [gap3-discarded] 清除基线登记（对齐 CC keepWorktree 清 currentWorktreeSession）
        worktreeHeadCommits.remove(slug);
    }

    /**
     * 统计 worktree 内变更 — 对齐 CC ExitWorktreeTool.ts:79-113 countWorktreeChanges.
     *
     * <p>{@code git status --porcelain}（modified file 数，外科手术不动）+ {@code git rev-list
     * --count &lt;base&gt;..HEAD}（未推送 commit 数，CC 用 rev-list --count 而非 @{push}，因
     * @{push} 在 worktree 分支无 upstream 时恒 exit 128 → 静默归 0 → 数据丢失）。
     *
     * <p>fail-closed（对齐 CC :94-98 / :107-109 return null）：缺 base 基线或 rev-list 失败时
     * 返回 {@link WorktreeChanges#unknown}，调用方 removeWorktree 据此拒绝删除。
     *
     * @return 变更计数（modified file 数 + 未推送 commit 数 + unknown 标志）
     */
    public WorktreeChanges countChanges(Path gitRoot, String slug) {
        WorktreePaths.validateSlug(slug);
        return countChangesByPath(WorktreePaths.worktreePathFor(gitRoot, slug), slug);
    }

    /**
     * [G22④ / OPD-PW-11] path 级变更计数重载 · 对齐 CC ExitWorktreeTool.ts:79-113
     * {@code countWorktreeChanges(worktreePath, originalHeadCommit)}——直接对<b>给定 worktree 路径</b>
     * 计数（不再经 {@code gitRoot+slug} 经 {@link WorktreePaths#worktreePathFor} 推导）。
     *
     * <p>WHY：hook-based worktree 的真实路径（hook stdout）≠ {@code worktreePathFor(gitRoot,slug)}
     * 推导路径；旧 {@link #countChanges(Path, String)} 对 hook-based 走到「推导路径不存在 → 返回
     * 0/0 clean」——误判干净（CC 用 session.worktreePath 计数，hook-based 无 originalHeadCommit 基线
     * → fail-closed unknown）。本重载用真实路径 {@code git status}，缺失基线 → unknown（fail-closed）。
     *
     * <p>fail-closed 语义同 slug 版：缺 base 基线（hook-based / 跨会话 / 手工 worktree）→
     * {@link WorktreeChanges#unknown}，调用方 removeWorktree / validateInput 据此拒绝。
     *
     * @param worktreePath 真实 worktree 路径（git 创建 = {@code worktreePathFor} 结果；hook-based =
     *                     hook stdout 路径；CC original: session.worktreePath，ExitWorktreeTool.ts:191）
     * @param slug         worktree slug（用于查 {@code worktreeHeadCommits} 基线；CC original:
     *                     originalHeadCommit 直接传参，Java 以 slug 间接查等价）
     * @return 变更计数（modified file 数 + 未推送 commit 数 + unknown 标志）
     */
    public WorktreeChanges countChangesByPath(Path worktreePath, String slug) {
        if (worktreePath == null || !Files.isDirectory(worktreePath)) {
            return new WorktreeChanges(0, 0);
        }
        // git status --porcelain (count modified/untracked files)
        GitCommandRunner.Result status = GitCommandRunner.run(worktreePath, "status", "--porcelain");
        int modifiedCount = countNonEmptyLines(status);
        // [gap3-discarded] 缺 base 基线 → fail-closed unknown（对齐 CC ExitWorktreeTool.ts:94-98
        //   !originalHeadCommit → return null）。会话级 worktreeHeadCommits 缺 slug（hook-based /
        //   跨会话 / 手工 worktree）时无法证明干净，不得声称 0。
        String base = worktreeHeadCommits.get(slug);
        if (base == null || base.isEmpty()) {
            log.warn("[WorktreeService] countChangesByPath: 缺少 headCommit 基线 slug={} path={} → unknown (fail-closed)",
                slug, worktreePath);
            return WorktreeChanges.unknown(modifiedCount);
        }
        // git rev-list --count <base>..HEAD（对齐 CC ExitWorktreeTool.ts:100-106，输出单行整数）
        GitCommandRunner.Result revList = GitCommandRunner.run(worktreePath, "rev-list", "--count",
                base + "..HEAD");
        if (!revList.isSuccess()) {
            log.warn("[WorktreeService] countChangesByPath: rev-list 失败 slug={} path={} exit={} → unknown (fail-closed)",
                    slug, worktreePath, revList.exitCode());
            return WorktreeChanges.unknown(modifiedCount);
        }
        int unpushedCount = parseCommitCount(revList.stdout());
        if (log.isDebugEnabled()) {
            log.debug("[WorktreeService] countChangesByPath slug={} path={} modifiedFiles={} unpushedCommits={}",
                    slug, worktreePath, modifiedCount, unpushedCount);
        }
        return new WorktreeChanges(modifiedCount, unpushedCount);
    }

    /**
     * 计算 stdout 中的非空行数. CC 默认每行以 {@code \n} 结尾 (或 CRLF).
     * split 行为: 空字符串 "".split("\n") = [""], length=1 (无变化); "a\n".split("\n") = ["a", ""], length=2 (1 行).
     * 修正: trim + count length - 1 if endsWith \n.
     */
    private static int countNonEmptyLines(GitCommandRunner.Result r) {
        if (!r.isSuccess() || !r.hasStdout()) {
            return 0;
        }
        String s = r.stdout();
        if (s.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : s.split("\n")) {
            if (!line.isEmpty() && !line.equals("\r")) {
                count++;
            }
        }
        return count;
    }

    /**
     * [gap3-discarded] 读取指定目录的 HEAD commit SHA — 对齐 CC worktree.ts readWorktreeHeadSha
     * （{@code git rev-parse HEAD}）。失败/空输出返回 null（fail-loud 由调用方决定）。
     */
    private static String readHeadCommit(Path cwd) {
        if (cwd == null) {
            return null;
        }
        GitCommandRunner.Result r = GitCommandRunner.run(cwd, "rev-parse", "HEAD");
        if (!r.isSuccess() || !r.hasStdout()) {
            log.warn("[WorktreeService] readHeadCommit: rev-parse HEAD 失败 cwd={} exit={}", cwd, r.exitCode());
            return null;
        }
        return r.stdout().trim();
    }

    /**
     * [gap3-discarded] 解析 rev-list --count 输出的单行整数 — 对齐 CC ExitWorktreeTool.ts:110
     * {@code parseInt(revList.stdout.trim(), 10) || 0}（trim + parseInt，非按行计数；空/异常返 0）。
     */
    private static int parseCommitCount(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(stdout.trim(), 10);
        } catch (NumberFormatException e) {
            log.warn("[WorktreeService] parseCommitCount: 非整数 rev-list 输出 '{}' → 0", stdout.trim());
            return 0;
        }
    }

    /**
     * 给 sub-agent 创建 worktree — 对齐 CC worktree.ts:902-951 createAgentWorktree.
     *
     * <p>区别于 {@link #createWorktree} 的语义: agent worktree 用 ephemeral pattern
     * (CC worktree.ts:1030 ephemeralWorktreePatterns agent-a{7hex}), 但教学版简化用 slug.
     *
     * @return 实际 worktree 路径, 作为 cwdOverride 传给 sub-agent
     */
    public WorktreeCreateResult createAgentWorktree(Path gitRoot, String agentSlug) {
        WorktreePaths.validateSlug(agentSlug);
        WorktreeCreateResult result = createWorktree(gitRoot, agentSlug);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("path", result.worktreePath().toString());
        extra.put("branch", result.worktreeBranch());
        extra.put("result", result instanceof WorktreeCreateResult.Resumed ? "resumed" : "created");
        logEvent("create_agent", agentSlug, extra);
        return result;
    }

    /**
     * sub-agent 完成后清理 — 对齐 CC worktree.ts:961-1020 removeAgentWorktree.
     *
     * <p>与 {@link #removeWorktree} 类似, 但 discardChanges=true (sub-agent 工作必定有产物, 否则等于空跑).
     */
    public void removeAgentWorktree(Path gitRoot, String agentSlug) {
        WorktreePaths.validateSlug(agentSlug);
        try {
            removeWorktree(gitRoot, agentSlug, true);
        } catch (WorktreeException e) {
            log.warn("[WorktreeService] removeAgentWorktree failed for {}: {}", agentSlug, e.getMessage());
        }
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("result", "ok");
        logEvent("remove_agent", agentSlug, extra);
    }

    /** worktree 变更计数 record */
    public record WorktreeChanges(int modifiedFileCount, int unpushedCommitCount, boolean unknown) {
        /** 2 参便利构造（unknown=false）· 兼容既有调用点（ExitWorktreeToolStructuredOutputTest / SubagentToolForkTest） */
        public WorktreeChanges(int modifiedFileCount, int unpushedCommitCount) {
            this(modifiedFileCount, unpushedCommitCount, false);
        }

        /** [gap3-discarded] fail-closed 工厂：无法确认状态时标记 unknown（对齐 CC countWorktreeChanges return null） */
        public static WorktreeChanges unknown(int modifiedFileCount) {
            return new WorktreeChanges(modifiedFileCount, 0, true);
        }

        public boolean hasAny() {
            return modifiedFileCount > 0 || unpushedCommitCount > 0 || unknown;
        }
    }

    /** Worktree 操作异常 */
    public static class WorktreeException extends RuntimeException {
        public WorktreeException(String message) {
            super(message);
        }
        public WorktreeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void logEvent(String event, String slug, Map<String, String> extra) {
        if (eventLog != null) {
            eventLog.log(event, slug, extra);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[WorktreeService] event={} slug={} extra={}", event, slug, extra);
            }
        }
    }

    // [IMPL-10] DEL-CCE-01: hookRegistry 注入 + WorktreeCreate/WorktreeRemove 通知式发射已删除
    //   （CC 为工具层结果驱动 hooks，见 09 §2 登记）。

    // ════════════════════════════════════════════════════════════════════════
    // [R5 结果驱动] hookBased 路径登记 · 对齐 CC currentWorktreeSession.hookBased
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [R5 结果驱动] 登记 hook-based worktree 路径 · 对齐 CC worktree.ts:720-733
     * （executeWorktreeCreateHook 成功后 currentWorktreeSession =
     * {worktreePath: hookResult.worktreePath, hookBased: true}）。
     *
     * <p>仅 EnterWorktreeTool hook 分支调用；git 分支不登记（hookBased=false，
     * ExitWorktreeTool remove 走 git worktree remove）。
     *
     * @param slug         worktree slug（CC worktreeName）
     * @param worktreePath hook stdout 给出的 worktree 路径（CC hookResult.worktreePath）
     */
    public void registerHookBasedWorktree(String slug, Path worktreePath) {
        if (slug == null || worktreePath == null) {
            return;
        }
        hookBasedPaths.put(slug, worktreePath);
        log.info("[WorktreeService] registerHookBasedWorktree slug={} path={}", slug, worktreePath);
    }

    /**
     * [R5 结果驱动] 查询并消费 hook-based worktree 路径 · 对齐 CC cleanupWorktree
     * （worktree.ts:815-855 读取 hookBased + worktreePath 后清 session）与 keepWorktree
     * （:780-799 清 session 保留 worktree）。
     *
     * <p>消费即移除（单次语义）：ExitWorktreeTool remove 据此决定走 hook 还是 git；
     * keep 调用后丢弃返回值仅作登记清除。
     *
     * @param slug worktree slug
     * @return hook-based worktree 路径；未登记（git 创建）→ null
     */
    public Path consumeHookBasedWorktree(String slug) {
        if (slug == null) {
            return null;
        }
        Path p = hookBasedPaths.remove(slug);
        if (p != null && log.isDebugEnabled()) {
            log.debug("[WorktreeService] consumeHookBasedWorktree slug={} path={}", slug, p);
        }
        return p;
    }
}