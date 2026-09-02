package com.nexusai.application.agent.workflow.worktree;

import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.WorkflowAbortedError;
import com.nexusai.application.agent.workflow.agent.AgentAdapterContext;
import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * workflow agent 隔离 worktree + abort 桥 · 对齐 CC {@code workflow/backends/claudeCodeBackend.ts}。
 *
 * <p><b>职责（两段）</b>：
 * <ol>
 *   <li><b>worktree 隔离</b> — CC {@code claudeCodeBackend.ts:219-234}（创建）+ {@code :169-200}
 *       （cleanupWorkflowWorktree）+ {@code utils/worktree.ts:1144-1173 hasWorktreeChanges}：
 *       <ul>
 *         <li>{@link #makeWorkflowWorktreeSlug} 生成 {@code wf_&lt;sha256&gt;} slug（CC :159-162），
 *             匹配 {@code cleanupStaleAgentWorktrees} 清理正则
 *             {@code ^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$}（worktree.ts:1032）</li>
 *         <li>{@link #createIsolation} fail-closed：{@code isolation:'worktree'} 建树失败时
 *             <b>不静默回落共享 cwd</b>（否则并发写互踩），返回 {@link IsolationFailed}
 *             → 调用方经 {@link #toWorktreeFailed} 转 {@code dead('worktree-failed')}（CC :227-233）</li>
 *         <li>{@link #cleanupWorkflowWorktree} hasWorktreeChanges 收尾（CC :169-200）：无变更
 *             自动删；有变更 / 探测失败（fail-closed）保留并记日志</li>
 *       </ul></li>
 *   <li><b>abort 桥</b> — CC {@code claudeCodeBackend.ts:242-255}（ctx.signal → agentAbort +
 *       registerAgentAbort）+ {@code :329-353}（catch AbortError → {@link WorkflowAbortedError} +
 *       finally unregister/cleanup）：
 *       <ul>
 *         <li>{@link AbortBridge#create} 建子 AbortController，父 {@code ctx.signal()} 取消时级联
 *             abort（Java {@link AbortController#onCancel} 等价 addEventListener），并注入
 *             {@code ctx.registerAgentAbort(agentId, agentAbort)} 供
 *             {@code service.kill(runId, agentId)} 精确 abort 单个 agent（CC 注释 :244-245 根因：
 *             不桥接则 workflow 被杀时 runAgent 无感知，'x' 失效）</li>
 *         <li>{@link #rethrowIfAborted} 判定 abort（{@code agentAbort.signal.aborted} 或
 *             {@code error.name === 'AbortError'}，CC :333-334）→ 抛 {@link WorkflowAbortedError}
 *             （P0 已有），否则 hooks.agent 会把它当普通失败吞成 dead</li>
 *         <li>{@link AbortBridge#close} finally 幂等清理：unregisterAgentAbort +
 *             removeOnCancel（CC :343-347）</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>用法（未来 ClaudeCodeBackendAdapter.run 骨架）</b>：
 * <pre>
 * IsolationResult iso = manager.createIsolation(params, ctx.runId(), coreAgentId, gitRoot);
 * if (iso instanceof IsolationFailed failed) return manager.toWorktreeFailed(failed);
 * try (AbortBridge bridge = AbortBridge.create(ctx)) {
 *     ... // runInCwd(worktreePath, () -> runAgent(...)); override.abortController = bridge.agentAbort()
 * } catch (Exception e) {
 *     rethrowIfAborted(bridge, e);               // abort → 抛 WorkflowAbortedError
 *     return new AgentRunResultDead(RUNAGENT_THREW, e.getMessage());
 * } finally {
 *     if (iso instanceof Isolated iso2) cleanupWorkflowWorktree(iso2.info(), agentType);
 * }
 * </pre>
 *
 * <p><b>Java 取舍</b>：hook-based worktree（CC worktree.ts:912-919 hasWorktreeCreateHook）教学版
 * 未接线 → {@code hookBased} 恒 false（保留字段对齐 CC 契约，未来 hook 接入时 cleanup 自动跳过
 * 变更探测）。headCommit 基线捕获（git rev-parse HEAD）对齐 CC {@code headCommit: baseSha}
 * （worktree.ts:371），供 hasWorktreeChanges 的 {@code rev-list --count &lt;base&gt;..HEAD} 判定。
 */
@Component
public final class AgentWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(AgentWorktreeManager.class);

    /** isolation 唯一取值 · CC original: {@code isolation?: 'worktree'} (types.ts:19)。 */
    public static final String ISOLATION_WORKTREE = "worktree";

    private final WorktreeService worktreeService;

    /**
     * Spring 注入构造。
     *
     * @param worktreeService 现有 Worktree 服务（创建/清理 git worktree · 对齐 CC createAgentWorktree）
     */
    @Autowired
    public AgentWorktreeManager(WorktreeService worktreeService) {
        this.worktreeService = worktreeService;
    }

    /** 非 Spring 便利构造（单测 / 工具场景）：无事件日志的 WorktreeService。 */
    public AgentWorktreeManager() {
        this(new WorktreeService());
    }

    // ════════════════════════════════════════════════════════════════════
    // worktree 隔离结果（fail-closed 三态）· 对齐 CC claudeCodeBackend.ts:220-234
    // ════════════════════════════════════════════════════════════════════

    /**
     * worktree 隔离创建结果三态 · CC original: claudeCodeBackend.ts:220-234 内联逻辑
     * （worktreeInfo = createAgentWorktree(...) | null | catch 后 return dead）。
     *
     * <p>三态：{@code NoIsolation}（未要求隔离）/ {@code Isolated}（创建成功，携带
     * {@link WorktreeInfo}）/ {@code IsolationFailed}（创建失败，携带 detail，fail-closed）。
     */
    public sealed interface IsolationResult
            permits IsolationResult.NoIsolation, IsolationResult.Isolated, IsolationResult.IsolationFailed {

        /** 未要求 worktree 隔离（isolation != 'worktree'）→ 共享 cwd 直接运行。 */
        record NoIsolation() implements IsolationResult {
        }

        /** 隔离 worktree 创建成功 · CC original: {@code worktreeInfo} (claudeCodeBackend.ts:151/223)。 */
        record Isolated(WorktreeInfo info) implements IsolationResult {
        }

        /**
         * 建树失败（fail-closed）· CC original: claudeCodeBackend.ts:227-233 catch 分支
         * （{@code detail = e.message}，返回 {@code {kind:'dead', reason:'worktree-failed', detail}}）。
         */
        record IsolationFailed(String detail) implements IsolationResult {
        }
    }

    /**
     * 隔离 worktree 信息 · CC original: {@code WorkflowWorktreeInfo} =
     * {@code Awaited<ReturnType<typeof createAgentWorktree>>} (claudeCodeBackend.ts:151)。
     *
     * @param worktreePath  worktree 目录 · CC original: worktreePath (worktree.ts:902-952)
     * @param worktreeBranch worktree 分支名 · CC original: worktreeBranch
     * @param headCommit   创建/恢复时捕获的基线 HEAD SHA · CC original: headCommit
     *                      （worktree.ts:371 baseSha / :252 existingHead）
     * @param gitRoot      主仓 git 根（canonical）· CC original: gitRoot
     * @param hookBased    是否 hook-based（Java 恒 false，对齐 CC 契约字段）
     */
    public record WorktreeInfo(
            Path worktreePath,
            String worktreeBranch,
            String headCommit,
            Path gitRoot,
            boolean hookBased) {
    }

    // ════════════════════════════════════════════════════════════════════
    // wf_<sha256> slug（对齐 CC makeWorkflowWorktreeSlug claudeCodeBackend.ts:159-162）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 生成 workflow agent worktree slug · 对齐 CC {@code makeWorkflowWorktreeSlug}
     * (claudeCodeBackend.ts:159-162)。
     *
     * <p><b>WHY sha256</b>（CC 注释 :155-158）：taskId 是 {@code 'w'+base36}（非 UUID），不能直接放进
     * cleanupStaleAgentWorktrees 清理正则 {@code ^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$}
     * （worktree.ts:1032）；sha256({@code runId:agentId}) 是确定性映射，且 agentId 保证同 runId 下
     * 多 agent 的 slug 唯一（无共享计数器、无线程安全问题）。
     *
     * @param runId     当前 workflow runId · CC original: {@code ctx.runId}
     * @param coreAgentId 核心层 subagent 追踪 id（字符串，非引擎层数字序号）· CC original: coreAgentId
     * @return {@code wf_<8hex>-<3hex>-<decimal>}
     */
    public static String makeWorkflowWorktreeSlug(String runId, String coreAgentId) {
        String h = sha256Hex(runId + ":" + coreAgentId);
        int num = Integer.parseInt(h.substring(11, 17), 16) % 100_000;
        return "wf_" + h.substring(0, 8) + "-" + h.substring(8, 11) + "-" + num;
    }

    // ════════════════════════════════════════════════════════════════════
    // worktree 创建（fail-closed）· 对齐 CC claudeCodeBackend.ts:220-234
    // ════════════════════════════════════════════════════════════════════

    /**
     * 按 isolation 创建隔离 worktree · 对齐 CC claudeCodeBackend.ts:220-234。
     *
     * <ul>
     *   <li>{@code isolation != 'worktree'} → {@code NoIsolation}（共享 cwd）</li>
     *   <li>创建成功 → {@code Isolated(WorktreeInfo)}（slug = {@link #makeWorkflowWorktreeSlug}，
     *       headCommit = worktree HEAD 基线）</li>
     *   <li>创建抛异常 → {@code IsolationFailed(detail)}（<b>fail-closed</b>：不静默回落共享 cwd，
     *       否则并发 agent 写互踩，CC :227-228 注释）</li>
     * </ul>
     *
     * @param params      agent() 入参（isolation 字段）· CC original: params
     * @param runId       workflow runId · CC original: ctx.runId
     * @param coreAgentId 核心层 subagent id · CC original: coreAgentId
     * @param gitRoot     主仓 git 根（canonical，调用方解析）· CC original: findCanonicalGitRoot(getCwd())
     * @return 三态结果（NoIsolation / Isolated / IsolationFailed）
     */
    public IsolationResult createIsolation(AgentRunParams params, String runId, String coreAgentId, Path gitRoot) {
        if (params == null || !ISOLATION_WORKTREE.equals(params.isolation())) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentWorktreeManager] createIsolation: isolation={} → 无隔离（共享 cwd）runId={}",
                        params == null ? null : params.isolation(), runId);
            }
            return new IsolationResult.NoIsolation();
        }
        String slug = makeWorkflowWorktreeSlug(runId, coreAgentId);
        try {
            WorktreeCreateResult result = worktreeService.createAgentWorktree(gitRoot, slug);
            String headCommit = readHeadCommit(result.worktreePath());
            WorktreeInfo info = new WorktreeInfo(
                    result.worktreePath(), result.worktreeBranch(), headCommit, result.gitRoot(), false);
            log.info("[AgentWorktreeManager] worktree 隔离创建完成 runId={} agentId={} slug={} path={} "
                    + "branch={} headCommit={}（CC claudeCodeBackend.ts:223-233）",
                    runId, coreAgentId, slug, result.worktreePath(), result.worktreeBranch(), headCommit);
            return new IsolationResult.Isolated(info);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warn("[AgentWorktreeManager] worktree 创建失败（fail-closed → dead(worktree-failed)）"
                    + "runId={} agentId={} slug={} detail={}（CC claudeCodeBackend.ts:227-233）",
                    runId, coreAgentId, slug, detail);
            return new IsolationResult.IsolationFailed(detail);
        }
    }

    /**
     * fail-closed 结果转 {@code dead('worktree-failed')} · 对齐 CC claudeCodeBackend.ts:232
     * {@code return { kind:'dead', reason:'worktree-failed', detail }}。
     *
     * @param failed createIsolation 返回的失败态
     * @return dead(DeadReason.WORKTREE_FAILED, detail)
     */
    public static AgentRunResultDead toWorktreeFailed(IsolationResult.IsolationFailed failed) {
        String detail = failed != null ? failed.detail() : null;
        if (log.isDebugEnabled()) {
            log.debug("[AgentWorktreeManager] fail-closed: 返回 dead(worktree-failed) detail={}", detail);
        }
        return new AgentRunResultDead(AgentRunResult.DeadReason.WORKTREE_FAILED, detail);
    }

    // ════════════════════════════════════════════════════════════════════
    // worktree 收尾（hasWorktreeChanges fail-closed）· 对齐 CC claudeCodeBackend.ts:169-200
    // ════════════════════════════════════════════════════════════════════

    /**
     * agent 结束后清理 worktree · 对齐 CC {@code cleanupWorkflowWorktree} (claudeCodeBackend.ts:169-200)。
     *
     * <p>hookBased 或 headCommit 缺失 → 保留（无法探测 VCS 变更，CC :173）；否则
     * {@link #hasWorktreeChanges}（fail-closed）探测：无变更 → 自动删除（worktree + branch）；
     * 有变更 / 探测失败 → 保留并日志路径（CC :196-199，v1 用日志而非扩展 AgentRunResult，
     * 避免动 journal 序列化）。
     *
     * @param info      本次运行的 worktree 信息（null → no-op）
     * @param agentType agent 类型（日志归因）· CC original: agentDef.agentType
     */
    public static void cleanupWorkflowWorktree(WorktreeInfo info, String agentType) {
        if (info == null) {
            return;
        }
        if (info.hookBased() || info.headCommit() == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentWorktreeManager] cleanup: hookBased={} headCommit={} → 保留 worktree（无法探测变更，CC :173）",
                        info.hookBased(), info.headCommit());
            }
            return;
        }
        boolean changed = true;
        try {
            changed = hasWorktreeChanges(info.worktreePath(), info.headCommit());
        } catch (Exception e) {
            log.warn("[AgentWorktreeManager] workflow worktree 变更探测失败（{}）→ fail-closed 保留: {}（CC :178-181）",
                    agentType, e.getMessage());
            changed = true;
        }
        if (!changed) {
            try {
                removeAgentWorktree(info);
            } catch (Exception e) {
                log.warn("[AgentWorktreeManager] workflow worktree 删除失败（{}）: {}（CC :190-193）",
                        agentType, e.getMessage());
            }
        } else {
            log.info("[AgentWorktreeManager] workflow worktree 保留（有变更, {}）: {}（CC :196-199）",
                    agentType, info.worktreePath());
        }
    }

    /**
     * 探测 worktree 是否有变更 · 对齐 CC {@code hasWorktreeChanges} (worktree.ts:1144-1173)。
     *
     * <p><b>fail-closed</b>（CC 注释 :1141-1142「callers use this to decide whether to remove,
     * so fail-closed」）：{@code git status --porcelain} 非空、{@code git status} 失败、
     * {@code git rev-list --count &lt;base&gt;..HEAD} 失败/输出非整数、headCommit 缺失，
     * 任一 → {@code true}（保留，不冒险删）。
     *
     * @param worktreePath worktree 目录
     * @param headCommit  创建时基线 SHA · CC original: headCommit
     * @return true = 有变更或无法确认（fail-closed 保留）
     */
    public static boolean hasWorktreeChanges(Path worktreePath, String headCommit) {
        if (worktreePath == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentWorktreeManager] hasWorktreeChanges: worktreePath=null → true（fail-closed）");
            }
            return true;
        }
        GitCommandRunner.Result status = GitCommandRunner.run(worktreePath, "status", "--porcelain");
        if (!status.isSuccess()) {
            log.warn("[AgentWorktreeManager] hasWorktreeChanges: git status 失败 exit={} cwd={} → true（fail-closed）",
                    status.exitCode(), worktreePath);
            return true;
        }
        if (status.stdout() != null && !status.stdout().trim().isEmpty()) {
            return true;
        }
        if (headCommit == null || headCommit.isBlank()) {
            log.warn("[AgentWorktreeManager] hasWorktreeChanges: headCommit 缺失 → true（fail-closed）");
            return true;
        }
        GitCommandRunner.Result revList = GitCommandRunner.run(
                worktreePath, "rev-list", "--count", headCommit + "..HEAD");
        if (!revList.isSuccess()) {
            log.warn("[AgentWorktreeManager] hasWorktreeChanges: git rev-list 失败 exit={} → true（fail-closed）",
                    revList.exitCode());
            return true;
        }
        try {
            return Integer.parseInt(revList.stdout().trim()) > 0;
        } catch (NumberFormatException e) {
            log.warn("[AgentWorktreeManager] hasWorktreeChanges: rev-list 输出非整数 '{}' → true（fail-closed）",
                    revList.stdout().trim());
            return true;
        }
    }

    /**
     * 删除 agent worktree + 临时分支 · 对齐 CC {@code removeAgentWorktree}
     * (worktree.ts:961-1020，git 分支，hook 分支 Java 未接线)。
     *
     * <p>在 gitRoot 下执行（worktree 目录即将删除），{@code git worktree remove --force} 成功后
     * {@code git branch -D} 清理临时分支（分支删除失败仅 warn，不阻断）。
     *
     * @param info worktree 信息（含 path/branch/gitRoot）
     */
    private static void removeAgentWorktree(WorktreeInfo info) {
        GitCommandRunner.Result r = GitCommandRunner.run(
                info.gitRoot(), "worktree", "remove", "--force", info.worktreePath().toString());
        if (!r.isSuccess()) {
            log.warn("[AgentWorktreeManager] removeAgentWorktree: git worktree remove 失败 exit={} stderr={} → 保留（不删）",
                    r.exitCode(), r.stderr());
            return;
        }
        if (info.worktreeBranch() != null && !info.worktreeBranch().isBlank()) {
            GitCommandRunner.Result br = GitCommandRunner.run(
                    info.gitRoot(), "branch", "-D", info.worktreeBranch());
            if (!br.isSuccess()) {
                log.warn("[AgentWorktreeManager] removeAgentWorktree: git branch -D 失败 exit={}（worktree 已删，分支保留）",
                        br.exitCode());
            }
        }
        log.info("[AgentWorktreeManager] removeAgentWorktree: 已删除 worktree path={} branch={}",
                info.worktreePath(), info.worktreeBranch());
    }

    /**
     * 读取目录 HEAD commit SHA · 对齐 CC {@code readWorktreeHeadSha}（rev-parse HEAD，worktree.ts:247）。
     * 失败/空输出 → null（cleanup 侧 fail-closed 保留）。
     *
     * @param cwd 目录（worktree 路径）
     * @return HEAD SHA 或 null
     */
    private static String readHeadCommit(Path cwd) {
        if (cwd == null) {
            return null;
        }
        GitCommandRunner.Result r = GitCommandRunner.run(cwd, "rev-parse", "HEAD");
        if (!r.isSuccess() || !r.hasStdout()) {
            log.warn("[AgentWorktreeManager] readHeadCommit: rev-parse HEAD 失败 cwd={} exit={} "
                    + "→ headCommit=null（cleanup 保留 worktree，fail-closed）", cwd, r.exitCode());
            return null;
        }
        return r.stdout().trim();
    }

    // ════════════════════════════════════════════════════════════════════
    // abort 桥 · 对齐 CC claudeCodeBackend.ts:242-255（建立）+ :329-353（判定/清理）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 建立 abort 桥 · 对齐 CC claudeCodeBackend.ts:246-255。
     *
     * <p>否则 workflow 被杀时 runAgent 无感知（'x' 失效根因，CC 注释 :242-243）：abort 信号
     * 到不了内部 fetch，agent 跑完才结束。单 agent kill 走
     * {@code service.kill(runId, agentId)} → {@code taskRegistrar.killAgent} →
     * {@code agentAbortControllers.get(agentId).abort()}；同一 agentAbort 同时接管两条路径。
     *
     * @param ctx adapter 运行上下文（signal/runId/agentId/registerAgentAbort/unregisterAgentAbort）
     * @return AbortBridge（AutoCloseable，finally 中 close）
     */
    public static AbortBridge createAbortBridge(AgentAdapterContext ctx) {
        AbortController agentAbort = new AbortController();
        Consumer<AbortController> onParentAbort = parent -> agentAbort.abort();
        if (ctx.signal().isCancelled()) {
            agentAbort.abort();
        } else {
            ctx.signal().onCancel(onParentAbort);
        }
        if (ctx.registerAgentAbort() != null) {
            ctx.registerAgentAbort().accept(ctx.agentId(), agentAbort);
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentWorktreeManager] abort 桥建立：ctx.signal → agentAbort runId={} agentId={} parentAborted={}（CC :246-255）",
                    ctx.runId(), ctx.agentId(), ctx.signal().isCancelled());
        }
        return new AbortBridge(agentAbort, onParentAbort, ctx);
    }

    /**
     * abort 判定命中 → 抛 {@link WorkflowAbortedError} · 对齐 CC claudeCodeBackend.ts:333-335。
     *
     * <p>必须 rethrow（而非吞成 dead）：否则 hooks.agent 把 abort 当普通失败吞进 dead，
     * workflow 不知道被 kill 了（'x' 另一面：信号到了但结果伪装成正常完成，CC 注释 :331-332）。
     *
     * @param bridge abort 桥（null → no-op，无 abort 可判）
     * @param e      runAgent 抛出的异常
     * @throws WorkflowAbortedError abort 命中时
     */
    public static void rethrowIfAborted(AbortBridge bridge, Throwable e) {
        if (bridge != null && bridge.isAbortCause(e)) {
            throw bridge.toWorkflowAborted();
        }
    }

    /**
     * abort 桥 · 持有 agent 级 AbortController + 父 signal 级联 + 登记/注销。
     *
     * <p><b>close 幂等</b>（CC :343-347 注释）：unregisterAgentAbort / removeEventListener
     * 重复调用安全（{@code CopyOnWriteArrayList.remove} + Map.remove 幂等）。
     */
    public static final class AbortBridge implements AutoCloseable {

        private final AbortController agentAbort;
        private final Consumer<AbortController> onParentAbort;
        private final AgentAdapterContext ctx;

        private AbortBridge(AbortController agentAbort, Consumer<AbortController> onParentAbort,
                            AgentAdapterContext ctx) {
            this.agentAbort = agentAbort;
            this.onParentAbort = onParentAbort;
            this.ctx = ctx;
        }

        /**
         * agent 级 AbortController · CC original: {@code agentAbort} (claudeCodeBackend.ts:246)。
         * 传给 runAgent 的 override.abortController，作为 kill 桥 + 内部 fetch 取消信号。
         */
        public AbortController agentAbort() {
            return agentAbort;
        }

        /**
         * agentAbort 是否已取消 · CC original: {@code agentAbort.signal.aborted} (:333)。
         */
        public boolean isAborted() {
            return agentAbort.isCancelled();
        }

        /**
         * abort 判定 · 对齐 CC :333-334 {@code agentAbort.signal.aborted ||
         * (e as Error)?.name === 'AbortError'}。
         *
         * <p>Java 等价：桥自身已取消，或异常是 {@link AbortException}（对齐 CC AbortError，
         * errors.ts:12-17）/ {@link CancellationException}（CompletableFuture 取消）。
         *
         * @param e runAgent 抛出的异常（可为 null → 仅查桥状态）
         */
        public boolean isAbortCause(Throwable e) {
            if (isAborted()) {
                return true;
            }
            return e != null && (e instanceof AbortException || e instanceof CancellationException);
        }

        /**
         * 生成透传异常 · CC original: {@code throw new WorkflowAbortedError()} (:334)。
         * 复用 P0 {@link WorkflowAbortedError}（引擎识别为 {@code killed} 终态）。
         */
        public WorkflowAbortedError toWorkflowAborted() {
            return new WorkflowAbortedError();
        }

        /**
         * finally 清理（幂等）· 对齐 CC :343-347 unregisterAgentAbort + removeEventListener。
         */
        @Override
        public void close() {
            if (ctx.unregisterAgentAbort() != null) {
                ctx.unregisterAgentAbort().accept(ctx.agentId());
            }
            ctx.signal().removeOnCancel(onParentAbort);
            if (log.isDebugEnabled()) {
                log.debug("[AgentWorktreeManager] abort 桥关闭（unregister + removeOnCancel）runId={} agentId={}（CC :343-347）",
                        ctx.runId(), ctx.agentId());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    /** SHA-256 hex · CC original: {@code createHash('sha256').digest('hex')} (claudeCodeBackend.ts:160)。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 恒含 SHA-256；不可达
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
