package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [is_meta 接线 2026-09-12] teammate 收尾段 hook 回注（TaskCompleted / TeammateIdle 的
 * {@code blockingError} → user 消息）必须带 {@code isMeta=true}。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC 真源
 * {@code query/stopHooks.ts:386-389}（TaskCompleted）与 {@code :428-431}（TeammateIdle）：
 * <pre>
 *   const userMessage = createUserMessage({
 *     content: getTaskCompletedHookMessage(result.blockingError),
 *     isMeta: true,          // :388
 *   })
 * </pre>
 * Java 旧实现两处走 3 参 {@code toMessage(Role.user, msg, null)} → {@code isMeta} 取兼容构造器默认
 * <b>false</b> → 该行被当成「真实用户消息」，两条读侧口径<b>同时</b>出错（都是用户可见的后果）：
 * <ol>
 *   <li>{@link AgentState#lastUserMessageId()}（{@code :425-433} 显式跳过 {@code isMeta=true} 的 user）
 *       会把这个 hook 反馈消息选为「最后 user」→ 事件/落库归属指向它的<b>随机 UUID</b>；</li>
 *   <li>{@code countNonMetaMessages}（轨迹条数徽标）把它计入真实消息 → 条数<b>虚高</b>。</li>
 * </ol>
 * 模型面不受影响（isMeta 绝不参与模型请求裁剪），与同批 P2-14 的两处 Stop hook 回注同款。
 *
 * <p><b>RED 条件</b>：把任一回注点改回 3 参 {@code toMessage(Role.user, msg, null)}
 * → 断言的 {@code isMeta()} 为 false、且 {@code lastUserMessageId()} 被劫持为注入消息的随机 UUID → 红。
 *
 * <p>端到端触发（非直接调私有方法）：teammate 上下文 + 真实 {@code queryLoop} 跑到收尾段
 * （对齐 {@code LlmAgentLoopStopHookFailureCatchTest} 的 teammate 段到达手法）。
 */
@DisplayName("[is_meta] teammate 收尾段 hook 回注带 isMeta=true（CC stopHooks.ts:388 / :430）")
class LlmAgentLoopTeammateHookIsMetaTest {

    private static final String TEAMMATE_NAME = "alice";
    private static final String REAL_USER_ID = "m-real-user";
    private static final String BLOCK_TEXT = "hook 反馈：请修复后继续";

    /**
     * 最近一次 runTeammateTurnEnd 的 state（供跨读侧断言读取 AgentState 归属）。
     * <p>⚠️ 两个回注点<b>不同时</b>触发（每次 run 只武装被测的那一个）—— 否则其中一个的 isMeta
     * 写错会同时劫持另一个测试的 {@code lastUserMessageId} 断言，两测试互相污染、失去「哪个回注点
     * 写错」的分辨力（变异验证据此判定）。
     */
    private AgentState LAST_RUN_STATE;

    @AfterEach
    void clearTeammateContext() {
        Teammate.clearDynamicTeamContext();
    }

    @Test
    @DisplayName("TaskCompleted blockingError → 回注 user 消息 isMeta=true，且不劫持 lastUserMessageId")
    void taskCompletedBlockingError_injectsIsMetaUserMessage() {
        List<ChatMessageDto> injected = runTeammateTurnEnd(HookEventType.TASK_COMPLETED);
        assertInjectedIsMetaAndDoesNotHijack(injected);
    }

    @Test
    @DisplayName("TeammateIdle blockingError → 回注 user 消息 isMeta=true，且不劫持 lastUserMessageId")
    void teammateIdleBlockingError_injectsIsMetaUserMessage() {
        List<ChatMessageDto> injected = runTeammateTurnEnd(HookEventType.TEAMMATE_IDLE);
        assertInjectedIsMetaAndDoesNotHijack(injected);
    }

    // ────────────────────────────────────────────────────────────────────
    // 断言（两个回注点共用 —— 语义与 WHY 完全相同，仅 CC 行号不同）
    // ────────────────────────────────────────────────────────────────────

    private void assertInjectedIsMetaAndDoesNotHijack(List<ChatMessageDto> injected) {
        assertThat(injected)
            .as("hook blockingError 必须回注进对话历史（CC 逐 result 全部 yield + push）")
            .isNotEmpty();
        assertThat(injected).allSatisfy(m -> assertThat(m.isMeta())
            .as("回注内容必须 isMeta=true —— 否则（①）被 AgentState.lastUserMessageId() 选中导致"
                + "事件/落库归属指向该消息的随机 UUID；（②）计入 countNonMetaMessages 使轨迹条数虚高"
                + "（CC stopHooks.ts:388/:430 'isMeta: true'）")
            .isTrue());
        assertThat(injected).allSatisfy(m -> assertThat(m.role()).isEqualTo(Role.user));

        assertThat(LAST_RUN_STATE.lastUserMessageId())
            .as("跨读侧断言：hook 回注不得劫持归属 —— 最后 user 必须仍是真实用户消息")
            .isEqualTo(REAL_USER_ID);
        assertThat(LAST_RUN_STATE.lastUserMessageId())
            .as("归属不得指向任何回注消息的 uuid")
            .isNotIn(injected.stream().map(ChatMessageDto::id).toList());
    }

    // ────────────────────────────────────────────────────────────────────
    // 端到端 harness
    // ────────────────────────────────────────────────────────────────────

    /**
     * 跑一轮 teammate 收尾段，只让<b>指定那一个</b> hook 回注一次 blockingError。
     *
     * <p>只武装被测回注点（另一侧恒返回空 List）是刻意的：两侧同时回注时，任一侧 isMeta 写错都会
     * 把 {@code lastUserMessageId} 劫持到自己的 uuid，令两个测试同时变红 —— 那样变异验证就无法回答
     * 「是哪一处写错了」，两个测试互相污染。consumed flag 保证重入后不再触发 → loop 正常收敛
     * （而不是跑到重入安全阀）。
     *
     * @param firedHook 本次要触发的回注点（TASK_COMPLETED / TEAMMATE_IDLE）
     * @return 注入 state.rawMessages() 的、content 含 {@link #BLOCK_TEXT} 的 user 消息
     */
    private List<ChatMessageDto> runTeammateTurnEnd(HookEventType firedHook) {
        boolean taskCompletedArmed = firedHook == HookEventType.TASK_COMPLETED;
        AtomicBoolean served = new AtomicBoolean(false);
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
            "a1", TEAMMATE_NAME, "team1", "blue", false, "p1"));

        // ── provider：单文本帧 1000 tokens → 触发 budget stop，loop 收敛到 stop hooks ──
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

        // ── hookRegistry：Stop 收集 proceed（放行到 teammate 段）；仅被测回注点回一次 blockingError ──
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any()))
            .thenReturn(new HookRegistry.StopHookCollectResult(
                List.of(), 0, List.of(), List.of(), false, null, false));
        // 2 参重载 = TaskCompleted 回注点（LlmAgentLoop:8339 executeEventAll(taskCompletedEvent, tuc)）
        when(hookRegistry.executeEventAll(any(HookEvent.class), any(ToolUseContext.class)))
            .thenAnswer(inv -> {
                HookEvent event = inv.getArgument(0);
                if (taskCompletedArmed && event.type() == HookEventType.TASK_COMPLETED
                        && served.compareAndSet(false, true)) {
                    return List.of(GenericHook.HookResult.stop("blocked", BLOCK_TEXT));
                }
                return List.of();
            });
        // 1 参重载 = TeammateIdle 回注点（LlmAgentLoop:8373 executeEventAll(teammateIdleEvent)）
        when(hookRegistry.executeEventAll(any(HookEvent.class)))
            .thenAnswer(inv -> {
                HookEvent event = inv.getArgument(0);
                if (!taskCompletedArmed && event.type() == HookEventType.TEAMMATE_IDLE
                        && served.compareAndSet(false, true)) {
                    return List.of(GenericHook.HookResult.stop("blocked", BLOCK_TEXT));
                }
                return List.of();
            });

        // ── sessionState.taskService：1 条 in_progress 且 owner == teammate 名（TaskCompleted 段门控）──
        AgentLoopContext.LoopSessionState sessionState = new AgentLoopContext.LoopSessionState();
        TaskService taskService = Mockito.mock(TaskService.class);
        when(taskService.listTasks(anyString())).thenReturn(List.of(
            new Task("t-1", "subject", "desc", null, TEAMMATE_NAME,
                Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of())));
        sessionState.setTaskService(taskService);

        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            REAL_USER_ID, null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());
        state.setTurnTokenBudget(1000);
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        // 32 参 compat ctor：位置 31 = sessionState（25..32 依次为 toolUseSummaryGenerator /
        //   toolExecutionBeans / tokenBudgetBeans / eventBridge / permissionContextBuilder /
        //   promptSuggestion / sessionState / claudemdEngine）
        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, sessionState, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        assertThatCode(() -> LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), null,
                tucWithNotification(null).withAvailableTools(List.of(
                    TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>()))
            .as("teammate 收尾段 hook 回注链路不应抛穿 queryLoop")
            .doesNotThrowAnyException();

        LAST_RUN_STATE = state;
        return state.rawMessages().stream()
            .filter(m -> Role.user.equals(m.role()) && m.content() != null && m.content().contains(BLOCK_TEXT))
            .toList();
    }

    /** 32 参构造器构建 TUC（of() 工厂不含 UI 回调）。 */
    private static ToolUseContext tucWithNotification(Consumer<Notification> addNotification) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            addNotification, null, null, null, null, null, null, null, null, null);
    }
}
