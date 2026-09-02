package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [SH-03 · OPD-WF4-SH-03] Stop hook 链抛异常的用户可见反馈 + teammate 段 catch 兜底聚焦测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code stopHooks.ts:456-472} 用<b>单 try</b>
 * （:175-455，含 teammate 段 :334-453）包住整条 Stop hook 链，catch 路径：① 采集 durationMs +
 * tengu_stop_hook_error 遥测（GAP-1，本期不实施）；② yield
 * {@code createSystemMessage(`Stop hook failed: ${errorMessage(error)}`, 'warning')}（GAP-2，
 * subtype='informational'，<b>模型不可见、用户可见</b>，供用户调试 hook）；③ return
 * {@code {blockingErrors:[], preventContinuation:false}}——<b>优雅续行，异常不抛到 run() 边界</b>。
 *
 * <p>Java 旧实现（本变更前）：in-loop/§14 catch 仅 {@code log.warn}（无用户可见消息，GAP-2 缺失）；
 * <b>teammate 段（TaskCompleted/TeammateIdle hook 执行）无 try</b>，异常上抛至 run() 边界
 * （OPD-WF4-SH-03 ?-3）。本测试验证两点 RED→GREEN：
 * <ul>
 *   <li><b>in-loop Stop hook 抛异常</b> → catch 不吞静默：用户可见 notification（addNotification
 *       id='stop-hook-failed'）发出 + 优雅退出，异常不抛穿 queryLoop；且<b>不 append 到
 *       state.messages()</b>（对齐 CC 模型不可见）——旧实现无此反馈（RED）。</li>
 *   <li><b>teammate 段 hook 抛异常</b>（TeammateIdle executeEventAll throw）→ 新 catch 兜底：
 *       异常不抛到 run() 边界、正常退出（NORMAL）、用户可见 notification 发出——旧实现异常
 *       直接抛穿（RED）。</li>
 * </ul>
 */
class LlmAgentLoopStopHookFailureCatchTest {

    @AfterEach
    void clearTeammateContext() {
        Teammate.clearDynamicTeamContext();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // A. in-loop Stop hook 链抛异常 → 用户可见反馈 + 优雅退出（GAP-2）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("in-loop Stop hook 抛异常 → addNotification(stop-hook-failed) + 优雅退出，不抛穿 queryLoop，且不 append 到 messages（模型不可见）")
    void inLoopStopHookThrows_surfacesUserVisibleFeedbackAndExitsGracefully() {
        AtomicReference<Notification> captured = new AtomicReference<>();
        ToolUseContext baseTuc = tucWithNotification(captured::set);

        // ── provider：单文本帧 1000 tokens → budget=1000 的 90% 阈值（900）触发 stop，loop 收敛 ──
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("first");
            onMsg.accept(new AssistantMessage("first", "stop", List.of(), "", null, 1000L));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── hookRegistry：executeStopHooksCollecting 直接抛异常 → in-loop catch 路径 ──
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any()))
            .thenThrow(new RuntimeException("stop hook boom"));

        // ── state + ctx ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());
        state.setTurnTokenBudget(1000);
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        // ── 执行（旧实现：异常抛穿 queryLoop → 本行抛错 RED）──
        assertThatCode(() -> LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                baseTuc.withAvailableTools(List.of(
                    com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>()))
            .as("[SH-03] in-loop Stop hook 异常不得抛穿 queryLoop（CC catch return {[],false} 优雅续行）")
            .doesNotThrowAnyException();

        // ── 用户可见反馈：addNotification(stop-hook-failed, ERROR) ──
        Notification n = captured.get();
        assertThat(n)
            .as("[SH-03] in-loop Stop hook 异常必须产生用户可见 notification（CC catch yield 'Stop hook failed'）")
            .isNotNull();
        assertThat(n.id()).isEqualTo("stop-hook-failed");
        assertThat(n.level()).isEqualTo(Notification.Level.ERROR);
        assertThat(n.body()).contains("stop hook boom");

        // ── 模型不可见守卫：'Stop hook failed' 不进 state.messages()（CC subtype informational）──
        assertThat(state.messages().stream().map(m -> m.content()))
            .as("[SH-03] 'Stop hook failed' 系统消息不得进 state.messages()（CC subtype='informational' 模型不可见）")
            .noneMatch(c -> c != null && c.contains("Stop hook failed"));

        // ── 优雅退出：budget stop → NORMAL ──
        assertThat(state.exitReason())
            .as("[SH-03] Stop hook 异常吞掉后按正常路径退出（CC return {[],false} 非 preventContinuation）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. teammate 段 hook 抛异常 → catch 兜底，不抛到 run() 边界
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("teammate 段 TeammateIdle hook 抛异常 → catch 兜底，不抛穿 queryLoop，正常退出（NORMAL），用户可见 notification")
    void teammateSectionThrows_isCaughtAtLoopBoundaryAndExitsGracefully() {
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
            "a1", "alice", "team1", "blue", false, "p1"));
        AtomicReference<Notification> captured = new AtomicReference<>();
        ToolUseContext baseTuc = tucWithNotification(captured::set);

        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("first");
            onMsg.accept(new AssistantMessage("first", "stop", List.of(), "", null, 1000L));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── hookRegistry：Stop 收集 proceed；teammateIdle 执行抛异常 → 触发 teammate 段 catch ──
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any()))
            .thenReturn(new HookRegistry.StopHookCollectResult(
                List.of(), 0, List.of(), List.of(), false, null, false));
        when(hookRegistry.executeEventAll(any(HookEvent.class)))
            .thenThrow(new RuntimeException("teammate idle boom"));

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());
        state.setTurnTokenBudget(1000);
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        // ── 执行（旧实现：teammate 段无 try → 异常抛穿 queryLoop RED）──
        assertThatCode(() -> LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                baseTuc.withAvailableTools(List.of(
                    com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>()))
            .as("[SH-03] teammate 段 hook 异常不得抛穿 queryLoop（CC stopHooks.ts:456-472 单 try catch 覆盖 teammate 段）")
            .doesNotThrowAnyException();

        Notification n = captured.get();
        assertThat(n)
            .as("[SH-03] teammate 段 hook 异常必须产生用户可见 notification")
            .isNotNull();
        assertThat(n.id()).isEqualTo("stop-hook-failed");
        assertThat(n.body()).contains("teammate idle boom");

        // ── 优雅退出：异常被吞掉 → NORMAL（非 STOP_HOOK_PREVENTED、非重入）──
        assertThat(state.exitReason())
            .as("[SH-03] teammate 段 hook 异常 → 正常退出（CC catch return {[],false}，不置 preventContinuation、不重入）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // C. notifyStopHookFailed 单元（helper 契约）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("notifyStopHookFailed 触发 addNotification（key=stop-hook-failed, level=ERROR, body 含异常 message）")
    void notifyStopHookFailed_firesAddNotification() {
        AtomicReference<Notification> captured = new AtomicReference<>();
        ToolUseContext tuc = tucWithNotification(captured::set);

        LlmAgentLoop.notifyStopHookFailed(tuc, new RuntimeException("boom detail"));

        Notification n = captured.get();
        assertThat(n).isNotNull();
        assertThat(n.id()).isEqualTo("stop-hook-failed");
        assertThat(n.level()).isEqualTo(Notification.Level.ERROR);
        assertThat(n.title()).contains("Stop hook failed");
        assertThat(n.body()).contains("boom detail");
    }

    @Test
    @DisplayName("notifyStopHookFailed 无 addNotification 回调 → 静默（仅日志，不抛异常）")
    void notifyStopHookFailed_noopConsumer_doesNotThrow() {
        ToolUseContext bareTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), null, AbortController.NOOP);
        assertThatCode(() -> LlmAgentLoop.notifyStopHookFailed(bareTuc, new RuntimeException("x")))
            .as("[SH-03] addNotification 未接线（子代理 ctor 置 null）时必须 no-op 不抛（CC addNotification?.() 可选链）")
            .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** 32 参构造器构建携带真实 addNotification 回调的 TUC（of() 工厂不含 UI 回调）。 */
    private static ToolUseContext tucWithNotification(Consumer<Notification> addNotification) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            addNotification, null, null, null, null, null, null, null, null, null);
    }
}
