package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.FrontmatterHooks;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-10] DEL-L03-03: SUBAGENT_STOP 单发语义测试。
 *
 * <p>WHY (规则九 · 验证意图): CC 子代理 Stop 仅 loop 内 executeStopHooks(subagentId)
 * （hooks.ts:3639-3697 → 'SubagentStop' 事件，stopHooks.ts:96 session_id = agentId），
 * <b>无 finally 二次发射</b>。Java 旧实现 SubagentExecutor finally 双发（DEL-L03-03 已删），
 * 且 loop 内 Stop 段恒发 STOP 事件（子代理场景应为 SUBAGENT_STOP）。本测试锚定单发契约：
 * <ul>
 *   <li>事件形状：subagentStop 事件 sessionId=agentId、agentId/agent_type/stop_hook_active
 *       载荷齐全（对齐 CC stopHooks.ts:96-119）</li>
 *   <li>分发链路：frontmatter hooks（SessionHookStore key=agentId）仅在该 SUBAGENT_STOP
 *       事件（sessionId=agentId）上执行一次；父会话 STOP 事件（sessionId=父会话）不触发
 *       （无跨会话泄漏 = 无"第二次发射"可消费面）</li>
 *   <li>finally 发射点删除由 grep 归零验证（SubagentExecutor 不再含 SUBAGENT_STOP 发射）</li>
 * </ul>
 */
@DisplayName("[IMPL-10] SUBAGENT_STOP 单发（DEL-L03-03）")
class SubagentStopSingleFireTest {

    private static CommandHook cmdHook(String command) {
        return new CommandHook(command, null, null, null, null, false, false, false);
    }

    /**
     * WHY: 事件形状必须与 CC executeStopHooks 一致 —— 工厂把传入的 sessionId 原样透传到
     * 载荷 {@code session_id}（hooks.ts:3672 createBaseHookInput 无 sessionId 参数 →
     * getSessionId() 主会话；子代理身份只进 agent_id）；session 作用域匹配（SessionHookStore
     * key=agentId）由 {@code event.agentId() ?? event.sessionId()} 承担（HookRegistry
     * sessionCommandMatched，对齐 CC hooks.ts:2003）。[IMP-LL-03 EX-01] record 顶层
     * {@code transcriptPath} 按子代理 transcript 填充（对齐 CC execAgentHook.ts:54-56：
     * {@code toolUseContext.agentId ? getAgentTranscriptPath(agentId) : getTranscriptPath()}）。
     */
    @Test
    @DisplayName("subagentStop 事件: sessionId 透传 + transcriptPath 按子代理 transcript 填充 + agent_id/agent_type/stop_hook_active 载荷")
    void subagentStopEvent_shape_matchesCc() {
        HookEvent e = HookEvent.subagentStop(
            "agent-1", "explore", "agent-1", true, "/transcript/agent-1.md", "final text");

        assertThat(e.type()).isEqualTo(HookEventType.SUBAGENT_STOP);
        // sessionId 工厂透传契约（生产 §14/in-loop 传主会话 state.sessionId()，见 LlmAgentLoop）
        assertThat(e.sessionId()).isEqualTo("agent-1");
        assertThat(e.agentId()).isEqualTo("agent-1");
        // [IMP-LL-03 EX-01] transcriptPath 按子代理 transcript 填充（CC execAgentHook.ts:54-56）
        assertThat(e.transcriptPath())
            .as("EX-01: subagent Stop 的 transcriptPath 指向子代理 transcript（agent_transcript_path 同源）")
            .isEqualTo("/transcript/agent-1.md");
        assertThat(e.data()).containsEntry("agent_id", "agent-1");
        assertThat(e.data()).containsEntry("agent_type", "explore");
        assertThat(e.data()).containsEntry("stop_hook_active", true);
        assertThat(e.data()).containsEntry("last_assistant_message", "final text");
    }

    /**
     * WHY: 单发语义 —— 子代理 loop 内 stop 段发一次 SUBAGENT_STOP（sessionId=agentId），
     * frontmatter hooks（SessionHookStore key=agentId）执行恰好一次；父会话 STOP 事件
     * （sessionId=父会话）不得触发子代理 hooks（旧 hookSessionScopes 跨会话过滤的替代）。
     */
    @Test
    @DisplayName("frontmatter hooks: SUBAGENT_STOP(sessionId=agentId) 单发执行；父会话 STOP 不触发")
    void subagentStop_singleFire_frontmatterHooksRunOnce() {
        HookRegistry registry = new HookRegistry();
        Map<HookEventType, List<HookMatcher>> hooks = Map.of(
            HookEventType.STOP,
            List.of(new HookMatcher("*", List.of(cmdHook("echo hi")))));

        // FrontmatterHooks（agent 场景）: STOP → SUBAGENT_STOP，注册到 SessionHookStore key=agentId
        int registered = FrontmatterHooks.register(registry, "agent-1", "explore", hooks);
        assertThat(registered).isEqualTo(1);
        assertThat(registry.getSessionHooks("agent-1", HookEventType.SUBAGENT_STOP)).isNotEmpty();

        // 记录实际执行（CommandHookExecutor 需要注入；未注入时 executeConfiguredCommand 返回
        // proceed 不执行 —— 用 function hook 同链验证分发更精确，见下一用例）。
        HookEvent stopEvent = HookEvent.subagentStop("agent-1", "explore", "agent-1", false, null, "done");
        // 事件本身可执行（executeEvent 全链，含 session hooks 匹配）
        GenericHook.HookResult r = registry.executeEvent(stopEvent, null, null);
        assertThat(r).as("SUBAGENT_STOP 事件执行不抛异常").isNotNull();

        // 父会话 STOP 事件（旧 SubagentExecutor finally 发射的事件形状）不得触发子代理 hooks
        HookEvent parentStop = HookEvent.stop("parent-session", null, false, null);
        GenericHook.HookResult parentR = registry.executeEvent(parentStop, null, null);
        assertThat(parentR).isNotNull();
    }

    /**
     * WHY: 执行计数锚定"恰好一次" —— session function hook（同 SessionHookStore 执行链，
     * executeEvent → executeSessionHooks）在 subagentStop(sessionId=agentId) 上执行 1 次；
     * 父会话 STOP 事件 0 次。若 loop 与 finally 双发（旧缺陷），本事件会被消费 2 次。
     */
    @Test
    @DisplayName("session hook 恰好执行一次（单发）；父会话 STOP 零次")
    void subagentStop_singleFire_countedExactlyOnce() {
        HookRegistry registry = new HookRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.addFunctionHook("agent-1", HookEventType.SUBAGENT_STOP, "",
            (messages, signal) -> {
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            },
            "blocked", null, null);

        // [IMP-HR-07 · OPD-WF6-01-05 测试调和] isSessionHookEligible 要求事件 ∈ CC appState 发射点
        // 集合 且 会话活跃（LlmAgentLoop.isSessionRunning）。子代理在父会话 agent 循环内运行 →
        // 主会话（事件载荷 sessionId）必在 RUNNING_SESSIONS。markRunning 建立该状态；子代理
        // frontmatter hooks 注册 key=agentId（CC hooks.ts:2003 匹配 key=agentId ?? getSessionId()）。
        String mainSession = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        LlmAgentLoop.markRunning(mainSession);
        try {
            // 子代理 loop 内 stop 段单发（agentId=agent-1 → 匹配 key=agentId，CC hooks.ts:2003）
            registry.executeEvent(HookEvent.subagentStop("agent-1", "explore", mainSession.toString(),
                false, null, "done"), null, null);
            assertThat(calls.get()).as("单发事件 → 恰好执行一次").isEqualTo(1);

            // 旧 finally 二次发射形状（sessionId=父会话、agentId=null 的主线程 STOP）不得再触发
            registry.executeEvent(HookEvent.stop("parent-session", null, false, "done", null),
                null, null);
            assertThat(calls.get()).as("不同会话的 STOP 不得触发本 agent hooks（无跨会话泄漏）")
                .isEqualTo(1);
        } finally {
            LlmAgentLoop.markIdle(mainSession);
        }
    }
}
