package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactProgressState;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [批 1b 追加 · 2026-09-13] reactive 应急压缩可被中断（与 auto / 手动 /compact 同源同判据）。
 *
 * <p><b>同构性核实（用户裁定「reactive 也是自动触发的压缩」→ 按 R7 同源同判据必须一起补）</b>：
 * <ol>
 *   <li><b>同一控制器来源</b>：reactive 块用 {@code params.toolUseContext()} 建 ccCtx
 *       （LlmAgentLoop reactive 块 {@code CompactConversation.buildAutoContext(params.toolUseContext(), …)}）
 *       —— 与 auto 块<b>同一表达式</b>。</li>
 *   <li><b>同一摘要 abort 源</b>：{@code reactiveCcCtx.setSummaryProducer(ctx.reactiveCompactor().summaryProducer())}
 *       → {@code ReactiveCompactor.summaryProducer()} 委托 {@code compactCallback.summarize}
 *       （ReactiveCompactor:255-262）→ 生产装配 = {@code streamCompactSummary} bean
 *       （ToolRegistrationConfig:2178 {@code new ReactiveCompactor(tokenCounter, streamCompactSummary)}）
 *       → 该 bean 的 abort supplier 即 {@code () -> CompactProgressState.currentAbort()}
 *       （ToolRegistrationConfig:1107，ThreadLocal）。⇒ {@code registerAbort} 可达摘要流。</li>
 *   <li><b>同一线程</b>：reactive 块是 {@code loop()} 直落代码（无 lambda / 无 executor，
 *       以 {@code continue}/{@code break} 回到 do-while），与 auto 块同在主循环线程
 *       ⇒ ThreadLocal 注册/读取同线程命中。</li>
 *   <li><b>同一「自动触发」属性</b>：reactive 由 PTL(413)/media 错误恢复触发（非用户敲命令）。</li>
 * </ol>
 * ⇒ 同构成立，补同款（不做任何 reactive 特有分支）。
 *
 * <p><b>RED tooth（删掉生产代码哪一行 → 本类哪条断言红）</b>：见
 * {@link LlmAgentLoopAutoCompactAbortTest} 同款四条；本类逐条对应 reactive 块。
 * <p><b>⛔ 已避开的假守卫</b>：清理断言<b>不</b>挂在「已被 abort 的控制器」上（那样删掉 finally
 * 仍全绿）—— 见 {@link #reactiveCompactFinished_releasesBothAbortChannels()} 用<b>未取消</b>
 * 的控制器 + 同线程观察 ThreadLocal。
 */
@DisplayName("[批1b 追加] reactive 应急压缩可中断：registerAbort + registerSessionAbort（真线程跨线程可达）")
class LlmAgentLoopReactiveCompactAbortTest {

    /** 测试 1 会话（独立 key，避免与其它用例的静态槽位串台）。 */
    private static final String SESSION = "sess-reactive-compact-abort-1";
    /** 测试 2 会话（NOOP 守卫用例独立 key）。 */
    private static final String SESSION_NOOP = "sess-reactive-compact-abort-2";
    /** 测试 3 会话（成对清理用例独立 key）。 */
    private static final String SESSION_FINISHED = "sess-reactive-compact-abort-3";

    /** 压缩调用模型（喂 ccCtx 的本 turn 有效模型）。 */
    private static final String COMPACT_MODEL = "compact-model-a";

    /** 等待窗口：压缩回调进入 / 循环线程收尾（真线程 + 超时，绝不无限等）。 */
    private static final long AWAIT_SECONDS = 20;

    // ════════════════════════════════════════════════════════════════════
    // 1. 真线程：reactive 压缩在飞 → 另一线程 abortForSession 必须 true
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reactive 压缩在飞：另一线程 abortForSession(sessionId) = true，且取消的正是摘要侧读到的控制器")
    void reactiveCompactInFlight_abortForSessionFromAnotherThread_isTrue_andCancelsSummarizerAbortSource()
            throws Exception {
        AgentState state = new AgentState("sys", SESSION, null);
        preCompactMessages().forEach(state::appendMessage);

        // 生产 = LlmAgentLoop.run 构造的 runAbortController（经 buildBaseToolUseContext 进 base TUC）。
        // 测试不自己注册任何东西 —— 注册必须由生产代码完成。
        AbortController runAbort = new AbortController();
        CountDownLatch insideSummarize = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AbortController> abortReadBySummarizer = new AtomicReference<>();
        AtomicBoolean summarizerSawCancelled = new AtomicBoolean(false);

        ReactiveCompactor rc = blockingReactiveCompactor(
            insideSummarize, release, abortReadBySummarizer, summarizerSawCancelled);
        AgentLoopContext ctx = agentLoopContext(ptlOnceThenStopProvider(), rc);
        QueryParams p = params(state, ctx, runAbort);

        Thread loopThread = new Thread(() -> LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>()), "reactive-compact-loop");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            assertThat(insideSummarize.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("reactive 应急压缩必须真实进入摘要回调（首次调用 413 PTL → reactiveGate 开）——"
                    + "否则本测试无从观察在飞窗口（fail loud，而非静默跳过）")
                .isTrue();

            // ⭐ 跨线程断言：本线程 ≠ 压缩线程（等价前端 Esc 所在 HTTP 线程 →
            //   ChatService.cancelSession → abortForSession）。
            assertThat(CompactProgressState.abortForSession(SESSION))
                .as("reactive 压缩在飞时，会话级 abort 通道必须可达（补丁前恒 false → 前端 Esc/停止"
                    + "对应急压缩完全无效）")
                .isTrue();
        } finally {
            release.countDown();
            loopThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }

        assertThat(loopThread.isAlive()).as("压缩放行后循环线程必须结束（不挂起）").isFalse();
        assertThat(abortReadBySummarizer.get())
            .as("摘要侧 abort 源（生产 supplier () -> CompactProgressState.currentAbort() 的同一 ThreadLocal）"
                + "必须 = TUC 上的 run 级控制器（CC context.abortController 同源，不新建第二套）")
            .isSameAs(runAbort);
        assertThat(summarizerSawCancelled.get())
            .as("跨线程 abort 必须落到摘要真正消费的那个控制器（isCancelled=true → provider 硬断流 →"
                + " 压缩中止，CC 'Compaction canceled.'）")
            .isTrue();
        // ⛔ 清理不在此断言：runAbort 已被上面那次 abortForSession 置为取消 → 删掉 finally 也会
        //   「看起来通过」（假守卫，auto 侧第一版踩过）。清理由测试 3 用未取消的控制器真正钉住。
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. NOOP 守卫：不得出现「宣称可 abort 却什么也没 abort」的假信号
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TUC 无 run 控制器（NOOP）→ 不注册会话级槽位：abortForSession 必须 false（拒绝假「已打断」）")
    void reactiveCompact_withNoopAbortController_doesNotFakeAbortedSignal() throws Exception {
        AgentState state = new AgentState("sys", SESSION_NOOP, null);
        preCompactMessages().forEach(state::appendMessage);

        CountDownLatch insideSummarize = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReactiveCompactor rc = blockingReactiveCompactor(insideSummarize, release,
            new AtomicReference<>(), new AtomicBoolean(false));
        AgentLoopContext ctx = agentLoopContext(ptlOnceThenStopProvider(), rc);
        // NOOP = buildBaseToolUseContext 在 runAbortController==null 时的回落值（永不取消）。
        QueryParams p = params(state, ctx, AbortController.NOOP);

        Thread loopThread = new Thread(() -> LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>()), "reactive-compact-loop-noop");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            assertThat(insideSummarize.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("NOOP 场景下压缩同样在飞（守卫只影响 abort 通道，不影响压缩本身）")
                .isTrue();
            assertThat(CompactProgressState.abortForSession(SESSION_NOOP))
                .as("NOOP 永不取消：注册它会让 abortForSession 恒返回 true 却什么也没打断"
                    + "（假「已打断」信号 → 前端以为停住了）→ 生产代码显式跳过注册，本处必须 false")
                .isFalse();
        } finally {
            release.countDown();
            loopThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
        assertThat(loopThread.isAlive()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 成对清理：压缩结束后两条 abort 通道都必须释放
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("压缩结束 → clearAbort（ThreadLocal 出栈）+ removeSessionAbort（会话槽位移除）成对释放")
    void reactiveCompactFinished_releasesBothAbortChannels() {
        AgentState state = new AgentState("sys", SESSION_FINISHED, null);
        preCompactMessages().forEach(state::appendMessage);
        int original = state.rawMessages().size();

        AbortController runAbort = new AbortController();
        AtomicBoolean summarizeRan = new AtomicBoolean(false);
        ReactiveCompactor rc = new ReactiveCompactor(
            msgs -> 2_000_000,
            (prompt, msgs) -> {
                summarizeRan.set(true);
                return new CompactConversation.SummaryResult(
                    "<summary>ok</summary>", compactionUsage());
            });
        rc.setEnabled(true);

        AgentLoopContext ctx = agentLoopContext(ptlOnceThenStopProvider(), rc);
        QueryParams p = params(state, ctx, runAbort);
        // 同步在本测试线程驱动 —— 这样压缩结束后可直接观察压缩线程的 ThreadLocal
        // （异步线程一结束 ThreadLocal 随线程消亡，clearAbort 就再也测不到）。
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>());

        assertThat(summarizeRan.get())
            .as("前置：reactive 压缩必须真的跑过（否则下面两条断言对「没注册过」也成立 = 空断言）")
            .isTrue();
        assertThat(state.rawMessages().size())
            .as("前置：应急压缩真实生效（消息被压缩）")
            .isLessThan(original);
        assertThat(CompactProgressState.currentAbort())
            .as("finally 的 clearAbort 必须把当前压缩 abort 源出栈：残留会让后续 StreamCompactSummary"
                + " 读到<b>上一次</b>压缩的控制器（跨 turn 串台）")
            .isNull();
        assertThat(CompactProgressState.abortForSession(SESSION_FINISHED))
            .as("finally 的 removeSessionAbort 必须移除会话槽位：runAbort 此处<b>未被取消</b>，"
                + "残留 → 本断言返回 true（前端之后按 Esc 会去 abort 一个已经结束的压缩，"
                + "在自动压缩路径上更会连带 abort 整个 run 控制器）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 在摘要回调内<b>阻塞</b>的 ReactiveCompactor —— 制造「压缩在飞」窗口，供另一线程断言。
     *
     * <p>回调跑在压缩线程（生产会话线程）上：{@code abortRead} 读的是生产 supplier 的
     * <b>同一表达式</b> {@code CompactProgressState.currentAbort()}（ThreadLocal），
     * 故不是「测试自己设置自己读」，而是对生产注册动作的观察。
     */
    private static ReactiveCompactor blockingReactiveCompactor(
            CountDownLatch inside, CountDownLatch release,
            AtomicReference<AbortController> abortRead, AtomicBoolean sawCancelled) {
        ReactiveCompactor rc = new ReactiveCompactor(
            msgs -> 2_000_000,
            (prompt, messages) -> {
                abortRead.set(CompactProgressState.currentAbort());
                inside.countDown();
                release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                AbortController ac = CompactProgressState.currentAbort();
                sawCancelled.set(ac != null && ac.isCancelled());
                return new CompactConversation.SummaryResult(
                    "<summary>ok</summary>", compactionUsage());
            });
        rc.setEnabled(true);
        return rc;
    }

    /** 压缩调用 usage（四元组非零 → 成功分支的成本结转路径同样被驱动）。 */
    private static CompactConversation.TokenUsage compactionUsage() {
        return new CompactConversation.TokenUsage(1_000, 200, 3_000, 400);
    }

    private static QueryParams params(AgentState state, AgentLoopContext ctx,
                                      AbortController abortController) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), state.sessionId(), PermissionMode.DEFAULT,
                List.of(), "", abortController),
            QuerySource.USER, COMPACT_MODEL, 8,
            null, null, null, null,
            deps(ctx), ProviderConfig.empty());
    }

    /** deps.resolveModel() 非 null → loop 用它作本 turn 有效模型（喂 ccCtx 的压缩模型）。 */
    private static LoopDeps deps(AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return COMPACT_MODEL; }
        };
    }

    /** 36 组件 ctx（第 20 = REACTIVE_FLAGS 开、第 21 = reactiveCompactor、第 36 = 计价器）。 */
    private static AgentLoopContext agentLoopContext(LlmProviderFactory factory, ReactiveCompactor rc) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null,   // 1-10
            factory, null, null, null, null, null, null, null, null,      // 11-19
            rc != null ? REACTIVE_FLAGS : FeatureFlags.ALL_DISABLED,      // 20 featureFlags
            rc, null, null, null, null,                                   // 21-25
            null, null, null, null, null,                                 // 26-30
            null, null, null, null, null,                                 // 31-35
            costCalculator());                                            // 36 modelCostCalculator
    }

    /** REACTIVE_COMPACT 开（同 CompactSessionCostWiringTest 口径）。 */
    private static final FeatureFlags REACTIVE_FLAGS = new FeatureFlags(
        true, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false);

    /** 计价器桩：窗口/上限值同既有用例（保证阈值体系同解）。 */
    private static ModelCostCalculator costCalculator() {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(false);
        when(calc.contextWindowFor(anyString())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString())).thenReturn(8_192L);
        when(calc.calculateCostYuan(anyString(), any(AgentUsage.class), anyBoolean())).thenReturn(0.0);
        return calc;
    }

    /** 首次 PTL(413) → reactive compact → 重试返回 stop 纯文本（同 CompactSessionCostWiringTest）。 */
    private static LlmProviderFactory ptlOnceThenStopProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onErr.accept(new LlmApiException(
                    413, Collections.emptyMap(),
                    "prompt is too long: 137500 tokens > 135000 maximum"));
            } else {
                Consumer<String> onChunk = inv.getArgument(9);
                Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                onChunk.accept("recovered reply");
                if (onMsg != null) {
                    onMsg.accept(new AssistantMessage("recovered reply", "stop", List.of()));
                }
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** 60 条 user + 末位 assistant 带 usage（触发 PTL 的会话形状，同既有用例）。 */
    private static List<ChatMessageDto> preCompactMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(message("m" + i, Role.user, "content " + i));
        }
        msgs.add(new ChatMessageDto(
            "last-a", null, Role.assistant, "assistant", "final response", null, List.of(),
            FinishReason.stop, 30_000, 5_000,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null));
        return msgs;
    }

    private static ChatMessageDto message(String id, Role role, String content) {
        return new ChatMessageDto(
            id, null, role, role.name(), content, null, List.of(),
            FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }
}
