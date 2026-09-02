package com.nexusai.application.agent.workflow.worktree;

import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.WorkflowAbortedError;
import com.nexusai.application.agent.workflow.agent.AgentAdapterContext;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager.AbortBridge;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager.IsolationResult;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager.WorktreeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentWorktreeManager 单元测试 · 对齐 CC {@code claudeCodeBackend.ts:159-162}（slug）+
 * {@code :219-234}（fail-closed）+ {@code :242-255 / :329-353}（abort 桥）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>slug 形如清理正则</b> — CC cleanupStaleAgentWorktrees 只认
 *       {@code ^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$}（worktree.ts:1032）。若 slug 变形（如直接放
 *       taskId / UUID），30 天清理会漏扫泄漏 worktree → 磁盘无限膨胀。断言：确定性、同 runId
 *       多 agent 唯一、匹配正则。</li>
 *   <li><b>fail-closed → dead(worktree-failed)</b> — 建树失败必须降级 dead 而非静默回落共享 cwd
 *       （CC :227-228：否则并发 agent 写互踩）。断言：gitRoot 不存在（建树必然抛）→ IsolationFailed
 *       → toWorktreeFailed 产出 WORKTREE_FAILED。</li>
 *   <li><b>abort 桥级联</b> — workflow 被杀时 ctx.signal 必须级联 abort 到 agentAbort
 *       （CC :242-243 根因：不桥接 runAgent 无感知，'x' 失效）。断言：父 signal.abort →
 *       bridge.isAborted()=true；父已取消 → 建桥即 abort。</li>
 *   <li><b>abort 透传 WorkflowAbortedError</b> — catch 内 abort 必须 rethrow（CC :331-335：
 *       否则 hooks.agent 吞成 dead，workflow 不知道被杀）。断言：rethrowIfAborted 抛
 *       WorkflowAbortedError；非 abort 异常不抛。</li>
 *   <li><b>清理幂等</b> — finally unregister + removeOnCancel（CC :343-347）。断言：close 后
 *       register/unregister 成对、父 signal listener 移除（listenerCount 归零）。</li>
 * </ol>
 */
class AgentWorktreeManagerTest {

    private static final Pattern WF_SLUG =
            Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$");

    private final AgentWorktreeManager manager = new AgentWorktreeManager();

    // ── slug 生成（CC claudeCodeBackend.ts:159-162）─────────────────────────

    @Test
    @DisplayName("slug 形如 wf_<8hex>-<3hex>-<decimal> 且确定性、同 runId 多 agent 唯一")
    void slugMatchesCleanupRegex() {
        String a = AgentWorktreeManager.makeWorkflowWorktreeSlug("run-1", "agent-a");
        String b = AgentWorktreeManager.makeWorkflowWorktreeSlug("run-1", "agent-a");
        String c = AgentWorktreeManager.makeWorkflowWorktreeSlug("run-1", "agent-b");

        assertThat(WF_SLUG.matcher(a).matches())
                .as("slug 必须命中 cleanupStaleAgentWorktrees 清理正则 ^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$")
                .isTrue();
        assertThat(a).isEqualTo(b).as("确定性：同 (runId, agentId) 必须产出同 slug");
        assertThat(a).isNotEqualTo(c).as("唯一性：同 runId 不同 agentId 必须产出不同 slug");
    }

    // ── createIsolation fail-closed（CC claudeCodeBackend.ts:219-234）────────

    @Test
    @DisplayName("isolation=worktree 但 gitRoot 不存在 → IsolationFailed → dead(worktree-failed)")
    void createIsolationFailClosed() {
        AgentRunParams params = new AgentRunParams(
                "do x", null, null, null, null, "worktree", null, null, null);

        IsolationResult result = manager.createIsolation(
                params, "run-1", "agent-a", Path.of("Z:/nonexistent-repo"));

        assertThat(result).isInstanceOf(IsolationResult.IsolationFailed.class);
        IsolationResult.IsolationFailed failed = (IsolationResult.IsolationFailed) result;
        assertThat(failed.detail()).isNotBlank();

        AgentRunResultDead dead = AgentWorktreeManager.toWorktreeFailed(failed);
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.WORKTREE_FAILED);
        assertThat(dead.detail()).isEqualTo(failed.detail());
    }

    @Test
    @DisplayName("isolation 未要求或非 worktree → NoIsolation（共享 cwd）")
    void createIsolationNoIsolation() {
        assertThat(manager.createIsolation(null, "run-1", "agent-a", null))
                .isInstanceOf(IsolationResult.NoIsolation.class);

        AgentRunParams shared = new AgentRunParams(
                "do x", null, null, null, null, null, null, null, null);
        assertThat(manager.createIsolation(shared, "run-1", "agent-a", null))
                .isInstanceOf(IsolationResult.NoIsolation.class);
    }

    // ── hasWorktreeChanges fail-closed（CC worktree.ts:1144-1173）───────────

    @Test
    @DisplayName("hasWorktreeChanges 对 null 路径 / 缺失基线 → true（fail-closed 保留）")
    void hasWorktreeChangesFailClosed() {
        assertThat(AgentWorktreeManager.hasWorktreeChanges(null, "abc123"))
                .as("worktreePath=null → 无法探测 → true（保留）")
                .isTrue();
        assertThat(AgentWorktreeManager.hasWorktreeChanges(Path.of("."), null))
                .as("headCommit=null → 无法 rev-list 基线 → true（保留）")
                .isTrue();
    }

    // ── abort 桥（CC claudeCodeBackend.ts:242-255 / :329-353）────────────────

    @Test
    @DisplayName("父 ctx.signal.abort → agentAbort 级联取消")
    void abortBridgePropagatesParentSignal() {
        AbortController parent = new AbortController();
        AbortBridge bridge = AgentWorktreeManager.createAbortBridge(ctx(parent));

        assertThat(bridge.isAborted()).isFalse();
        parent.abort();
        assertThat(bridge.isAborted()).isTrue().as("workflow 被杀 → agentAbort 必须级联 abort");
        bridge.close();
    }

    @Test
    @DisplayName("父已取消 → 建桥即 abort（CC :248-250 提前 abort 分支）")
    void abortBridgeParentAlreadyAborted() {
        AbortController parent = new AbortController();
        parent.abort();

        AbortBridge bridge = AgentWorktreeManager.createAbortBridge(ctx(parent));

        assertThat(bridge.isAborted()).isTrue();
        bridge.close();
    }

    @Test
    @DisplayName("rethrowIfAborted：abort 命中 → 抛 WorkflowAbortedError；非 abort 不抛")
    void rethrowIfAborted() {
        AbortController parent = new AbortController();
        AbortBridge bridge = AgentWorktreeManager.createAbortBridge(ctx(parent));

        // 未 abort + 普通异常 → 不抛（runAgent 错误降级 dead 正常路径）
        AgentWorktreeManager.rethrowIfAborted(bridge, new RuntimeException("boom"));
        assertThat(bridge.isAbortCause(new RuntimeException("boom"))).isFalse();
        assertThat(bridge.isAbortCause(new AbortException("cancel"))).isTrue();

        parent.abort();
        assertThatThrownBy(() -> AgentWorktreeManager.rethrowIfAborted(bridge, new RuntimeException("boom")))
                .isInstanceOf(WorkflowAbortedError.class)
                .as("abort 命中必须 rethrow WorkflowAbortedError，否则被吞成 dead");
        bridge.close();
    }

    @Test
    @DisplayName("close 幂等：unregister 成对 + 父 signal listener 移除")
    void abortBridgeCloseIsIdempotent() {
        AbortController parent = new AbortController();
        List<Integer> registered = new ArrayList<>();
        List<Integer> unregistered = new ArrayList<>();
        AgentAdapterContext c = ctx(parent, registered, unregistered);

        AbortBridge bridge = AgentWorktreeManager.createAbortBridge(c);
        assertThat(registered).containsExactly(7);
        assertThat(parent.listenerCount()).isEqualTo(1).as("建桥后父 signal 挂 1 个级联 listener");

        bridge.close();
        assertThat(unregistered).containsExactly(7);
        assertThat(parent.listenerCount()).isZero().as("close 后父 signal listener 必须移除");

        bridge.close();
        // 幂等性契约（CC :343-347 注释）：在 registrar 的 Map.remove / removeOnCancel 的 COW remove
        // 层幂等（重复 remove no-op），而非桥自身抑制回调——真实 InMemoryTaskRegistrar.unregisterAgentAbort
        // 是 Map.remove。断言：二次 close 不抛 + listener 不重复泄漏。
        assertThat(parent.listenerCount()).isZero().as("二次 close 后 listener 仍为 0（COW remove no-op）");
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private static AgentAdapterContext ctx(AbortController parent) {
        return ctx(parent, null, null);
    }

    private static AgentAdapterContext ctx(AbortController parent,
                                           List<Integer> registered,
                                           List<Integer> unregistered) {
        return new AgentAdapterContext(
                null,
                parent,
                "run-1",
                7,
                null,
                registered != null
                        ? (agentId, ac) -> registered.add(agentId)
                        : (agentId, ac) -> {
                        },
                unregistered != null
                        ? unregistered::add
                        : agentId -> {
                        });
    }
}
