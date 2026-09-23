package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [T1 · A-4 伴改] 「继承来的会话键」在子代理收尾时<b>不得被清</b>。
 *
 * <p><b>WHY 本类必须存在（规则九 · 测试验证意图）</b>：Step 21.2b 有两条清理 ——
 * {@code clearSessionHooks(sessionId)} 与 {@code clearSessionHooks(agentId)}，其中「按 sessionId 清」
 * 是 <b>Java 独有</b>（CC 上游 {@code Open-ClaudeCode/src/tools/AgentTool/runAgent.ts:821} 只清 agentId；
 * CCB 只在 SessionEnd 清会话桶 {@code src/utils/hooks.ts:4298}）。而本仓子代理的 sessionId 是
 * <b>从父 TUC 继承来的</b>（{@code createSubagentContext.java:233-234}）——普通 Agent-tool 子代理继承
 * 主会话、T1 之后 teammate 继承 Leader 会话。⇒ 按它清 = 清 <b>Leader 自己</b>用 skill 注册的
 * frontmatter / 运行时 hooks（{@code RegisterSkillHooks.java:110} 经 SkillToolImpl 以 sessionId 注册）。
 *
 * <p>本任务之所以必须处理它：teammate 由「每会话一次」变为「<b>每轮一次</b>」
 * （{@code AutonomousAgentLoop.runOneTurn} 每轮调 {@code executeStreaming}）—— 不收窄就等于
 * 每个 teammate 每轮把 Leader 的 hooks 清空一次（用户表现：Leader 的 skill hooks 失效）。
 *
 * <p>断言标的 = {@link SubagentExecutor#sessionHookKeyToClear} 的返回键 + 真实
 * {@link HookRegistry} 桶的存活/清空（行为验证，不是字段自称）。
 */
@DisplayName("T1 · A-4：继承来的会话键不得被清（Leader 的 session hooks 必须存活）")
class SubagentExecutorInheritedSessionHookKeyTest {

    private static final String LEADER_SESSION = "sess-leader-a4";

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    private static SubagentExecutor executorWith(HookRegistry hookRegistry) {
        // 其余依赖传 null：本类只验证收尾清理 seam 的判据与效果（对齐
        // SubagentExecutorSessionHookCleanupTest 的直构约定）。
        return new SubagentExecutor(null, hookRegistry, null, null, null, "model", "system-prompt");
    }

    /**
     * WHY：teammate/子代理的 sessionId 继承自 Leader ⇒ 收尾时若照旧清 sessionId，清掉的是
     * Leader 自己注册的 hooks（本批之前该键是 no-session 幻影桶，无害；本批之后 = 真实 Leader 桶）。
     */
    @Test
    @DisplayName("继承来的会话键 ⇒ 收尾不清理，Leader 桶里的 hooks 必须存活")
    void cleanup_skipsInheritedSessionKey_keepsLeaderHooks() {
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.addSessionHook(LEADER_SESSION, HookEventType.PRE_TOOL_USE, "Bash",
            commandHook("echo leader"), null, null);
        ToolUseContext leaderParentTuc =
            ToolUseContext.of(UUID.randomUUID(), LEADER_SESSION, PermissionMode.DEFAULT);

        String key = SubagentExecutor.sessionHookKeyToClear(LEADER_SESSION, leaderParentTuc);
        assertThat(key).as("继承来的会话键 ⇒ 返回 null（不清理）").isNull();

        // Step 21.2b 等价执行路径
        executorWith(hookRegistry).cleanupSessionHooks(key, UUID.randomUUID());

        assertThat(hookRegistry.getSessionHooks(LEADER_SESSION, HookEventType.PRE_TOOL_USE))
            .as("⭐ Leader 的会话桶必须存活（改前：teammate 每轮收尾都会把它清空）")
            .containsKey(HookEventType.PRE_TOOL_USE);
    }

    /**
     * WHY：证明上一条的收窄<b>不是空断言</b>（「本来就没清」）—— 用未收窄的键直接清，桶确实被清空。
     * 若 {@code clearSessionHooks(sessionId)} 本身失效（例如 hookRegistry 未接线），本用例红。
     */
    @Test
    @DisplayName("承重性对照：未经收窄的同一会话键确实会清空 Leader 桶")
    void cleanup_directSessionKey_wouldClearLeaderHooks() {
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.addSessionHook(LEADER_SESSION, HookEventType.PRE_TOOL_USE, "Bash",
            commandHook("echo leader"), null, null);

        executorWith(hookRegistry).cleanupSessionHooks(LEADER_SESSION, UUID.randomUUID());

        assertThat(hookRegistry.getSessionHooks(LEADER_SESSION, HookEventType.PRE_TOOL_USE))
            .as("未收窄时确实会清空 ⇒ sessionHookKeyToClear 的判据是承重的（不是恒真恒假）")
            .doesNotContainKey(HookEventType.PRE_TOOL_USE);
    }

    /**
     * WHY：收窄只能作用于「继承来的」键 —— standalone 降级路径（无父 TUC、sessionId = no-session 哨兵）
     * 与「子代理自有独立会话」都必须保持原清理语义，否则会从「清错桶」翻成「该清的没清」（泄漏）。
     */
    @Test
    @DisplayName("对照：standalone 哨兵键 / 自有会话键仍按原语义清理")
    void cleanup_stillClearsNonInheritedSessionKeys() {
        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.addSessionHook(SessionKeys.NO_SESSION, HookEventType.PRE_TOOL_USE, "Bash",
            commandHook("echo nosession"), null, null);
        SubagentExecutor executor = executorWith(hookRegistry);

        String noSessionKey = SubagentExecutor.sessionHookKeyToClear(SessionKeys.NO_SESSION, null);
        assertThat(noSessionKey).as("无父 TUC ⇒ 不是继承来的键，照原语义清理").isEqualTo(SessionKeys.NO_SESSION);
        executor.cleanupSessionHooks(noSessionKey, UUID.randomUUID());
        assertThat(hookRegistry.getSessionHooks(SessionKeys.NO_SESSION, HookEventType.PRE_TOOL_USE))
            .as("standalone 哨兵桶仍被清理（⛔ 哨兵未被删除，其降级路径行为不变）")
            .doesNotContainKey(HookEventType.PRE_TOOL_USE);

        ToolUseContext otherParent =
            ToolUseContext.of(UUID.randomUUID(), "sess-leader-other", PermissionMode.DEFAULT);
        assertThat(SubagentExecutor.sessionHookKeyToClear("sess-child-own", otherParent))
            .as("与父会话键不同 ⇒ 是本 agent 自有的桶，照清")
            .isEqualTo("sess-child-own");
        assertThat(SubagentExecutor.sessionHookKeyToClear(null, null))
            .as("无会话键 ⇒ 不清理（既有 null no-op 语义）")
            .isNull();
    }
}
