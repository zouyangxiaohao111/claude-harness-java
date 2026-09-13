package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactProgressState;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * [批 1b · 2026-09-13] auto-compact 可被中断（与手动 /compact 对齐）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图，非 WHAT）</b>：{@link CompactProgressState} 有两条
 * abort 通道，auto 压缩此前<b>两条全空</b>：
 * <ol>
 *   <li><b>线程级（摘要断流源）</b>：{@code StreamCompactSummary} 的 abort supplier 是
 *       {@code () -> CompactProgressState.currentAbort()}（ToolRegistrationConfig:1107，ThreadLocal）。
 *       auto 路径从未 {@code registerAbort} → supplier 恒取 null → 回落 {@code AbortController.NOOP}
 *       → 摘要流永不被打断（用户按 Esc 时压缩照跑到底）。</li>
 *   <li><b>会话级（跨线程前端 Esc 桥）</b>：前端停止键/Esc → {@code ChatService.cancelSession}
 *       → {@code CompactProgressState.abortForSession(sessionId)}（ConcurrentHashMap）。
 *       auto 路径从未 {@code registerSessionAbort} → 无此会话 → 返回 false → 前端「停止」
 *       对自动压缩完全无效。</li>
 * </ol>
 *
 * <p><b>为什么必须「经真实线程」断言（本类的存在理由）</b>：本仓已有 4 例「测试线程设置后同线程断言」
 * 的零覆盖力夹具掩盖生产缺陷。会话级通道的全部价值在于<b>跨线程</b>可达 —— 同线程断言即使
 * 生产代码漏注册也会因为「自己注册自己读」而恒绿。故本类：压缩跑在独立线程（{@code auto-compact-loop}，
 * 与生产会话线程同构），断言在<b>测试线程</b>发出（= 前端 Esc 所在的 HTTP 线程）。
 *
 * <p><b>RED tooth（删掉生产代码哪一行 → 本类哪条断言红）</b>：
 * <ul>
 *   <li>删 {@code LlmAgentLoop} auto 压缩块的 {@code registerSessionAbort(...)} →
 *       {@link #autoCompactInFlight_abortForSessionFromAnotherThread_isTrue_andCancelsSummarizerAbortSource}
 *       的「跨线程 abortForSession 必须 true」断言红（false）；</li>
 *   <li>删 {@code registerAbort(...)} → 同条测试的
 *       {@code abortReadBySummarizer}（摘要侧读到 null）与 {@code summarizerSawCancelled}（未取消）断言红；</li>
 *   <li>删 finally 的 {@code clearAbort} → {@link #autoCompactFinished_releasesBothAbortChannels}
 *       的 {@code currentAbort()} 非 null 断言红；删 {@code removeSessionAbort} → 同条
 *       {@code abortForSession} 断言红（该用例的控制器<b>未取消</b>，故能区分「已移除」与
 *       「已取消」—— 第一版把清理断言挂在已取消的控制器上，删掉 finally 仍全绿 = 假守卫，已改）。</li>
 *   <li>去掉 NOOP 守卫（无条件注册 TUC 的 abortController）→
 *       {@link #autoCompact_withNoopAbortController_doesNotFakeAbortedSignal} 红。</li>
 * </ul>
 *
 * <p><b>CC 真源（claude-code-best）</b>：CC 侧压缩与主查询<b>共用同一控制器</b> ——
 * {@code compactConversation} 全链读 {@code context.abortController.signal}
 * （{@code services/compact/compact.ts:442} pre-hooks / {@code :757} post-hooks /
 * {@code :1237} fork 子查询 / {@code :1347} 摘要流式请求），且自动压缩把同一 toolUseContext
 * 透传进去（{@code services/compact/autoCompact.ts:342-344}）→ 用户 Esc 中断的是同一个控制器，
 * 由 {@code commands/compact/compact.ts:135} 翻译为 {@code 'Compaction canceled.'}。
 * 本仓 auto 路径的等价物 = {@code params.toolUseContext().abortController()}
 * （= runAbortController，亦即 {@code CompactConversation.buildAutoContext:603} 写进 ccCtx 的同一实例）。
 */
@DisplayName("[批1b] auto-compact 可中断：registerAbort + registerSessionAbort（跨线程 Esc 真线程可达）")
class LlmAgentLoopAutoCompactAbortTest {

    /** 测试 1 会话（独立 key，避免与其它用例的静态槽位串台）。 */
    private static final String SESSION = "sess-autocompact-abort-1";
    /** 测试 2 会话（NOOP 守卫用例独立 key）。 */
    private static final String SESSION_NOOP = "sess-autocompact-abort-2";
    /** 测试 3 会话（成对清理用例独立 key）。 */
    private static final String SESSION_FINISHED = "sess-autocompact-abort-3";

    /** 压缩调用模型（喂 ccCtx 的本 turn 有效模型）。 */
    private static final String COMPACT_MODEL = "compact-model-a";

    /** 等待窗口：压缩回调进入 / 循环线程收尾（真线程 + 超时，绝不无限等）。 */
    private static final long AWAIT_SECONDS = 20;

    // ════════════════════════════════════════════════════════════════════
    // 1. 真线程：auto 压缩在飞 → 另一线程 abortForSession 必须 true
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("auto 压缩在飞：另一线程 abortForSession(sessionId) = true，且取消的正是摘要侧读到的控制器")
    void autoCompactInFlight_abortForSessionFromAnotherThread_isTrue_andCancelsSummarizerAbortSource()
            throws Exception {
        AgentState state = new AgentState("sys", SESSION, null);
        state.replaceMessages(List.of(
            message("m1", Role.user, "question-1"),
            message("m2", Role.user, "question-2")));

        // 本 run 的 AbortController（生产 = LlmAgentLoop.run 构造的 runAbortController，
        // 经 buildBaseToolUseContext 进 base TUC）。测试不自己注册任何东西 —— 注册必须由生产代码完成。
        AbortController runAbort = new AbortController();
        CountDownLatch insideSummarize = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AbortController> abortReadBySummarizer = new AtomicReference<>();
        AtomicBoolean summarizerSawCancelled = new AtomicBoolean(false);

        AutoCompactor autoCompactor = blockingAutoCompactor(
            insideSummarize, release, abortReadBySummarizer, summarizerSawCancelled);
        AgentLoopContext ctx = agentLoopContext(plainReplyProvider());
        QueryParams p = params(state, ctx, runAbort);

        Thread loopThread = new Thread(() -> LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>(), autoCompactor), "auto-compact-loop");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            assertThat(insideSummarize.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("auto 压缩必须真实进入摘要回调（tokenCounter=2,000,000 恒越阈值）——"
                    + "否则本测试无从观察在飞窗口（fail loud，而非静默跳过）")
                .isTrue();

            // ⭐ 跨线程断言：本线程 ≠ 压缩线程（等价前端 Esc 所在 HTTP 线程 →
            //   ChatService.cancelSession → abortForSession）。修复前：sessionAborts 无此会话 → false。
            assertThat(CompactProgressState.abortForSession(SESSION))
                .as("auto 压缩在飞时，会话级 abort 通道必须可达（修复前恒 false → 前端 Esc/停止"
                    + "对自动压缩完全无效）")
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
        // ⛔ 清理（clearAbort / removeSessionAbort）不在此断言：本测试里 runAbort 已被上面那次
        //   abortForSession 置为取消 → 即使 finally 完全缺失，abortForSession 也会因 isCancelled()
        //   返回 false 而「看起来通过」（第一版断言正是如此：删掉 finally 仍然全绿 —— 假守卫）。
        //   清理由 {@link #autoCompactFinished_releasesBothAbortChannels()} 用<b>未取消</b>的控制器
        //   在同一线程上真正钉住。
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. NOOP 守卫：不得出现「宣称可 abort 却什么也没 abort」的假信号
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TUC 无 run 控制器（NOOP）→ 不注册会话级槽位：abortForSession 必须 false（拒绝假「已打断」）")
    void autoCompact_withNoopAbortController_doesNotFakeAbortedSignal() throws Exception {
        AgentState state = new AgentState("sys", SESSION_NOOP, null);
        state.replaceMessages(List.of(
            message("n1", Role.user, "question-1"),
            message("n2", Role.user, "question-2")));

        CountDownLatch insideSummarize = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AutoCompactor autoCompactor = blockingAutoCompactor(insideSummarize, release,
            new AtomicReference<>(), new AtomicBoolean(false));
        AgentLoopContext ctx = agentLoopContext(plainReplyProvider());
        // NOOP = buildBaseToolUseContext 在 runAbortController==null 时的回落值（永不取消）。
        QueryParams p = params(state, ctx, AbortController.NOOP);

        Thread loopThread = new Thread(() -> LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>(), autoCompactor), "auto-compact-loop-noop");
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
    void autoCompactFinished_releasesBothAbortChannels() {
        AgentState state = new AgentState("sys", SESSION_FINISHED, null);
        state.replaceMessages(List.of(
            message("f1", Role.user, "question-1"),
            message("f2", Role.user, "question-2")));

        AbortController runAbort = new AbortController();
        AtomicBoolean summarizeRan = new AtomicBoolean(false);
        AutoCompactor autoCompactor = new AutoCompactor(
            msgs -> 2_000_000,
            (prompt, messages) -> {
                summarizeRan.set(true);
                return new CompactConversation.SummaryResult(
                    "<summary>ok</summary>", compactionUsage());
            });

        AgentLoopContext ctx = agentLoopContext(plainReplyProvider());
        QueryParams p = params(state, ctx, runAbort);
        // 同步在本测试线程驱动 —— 这样压缩结束后可直接观察压缩线程的 ThreadLocal
        // （异步线程一结束 ThreadLocal 随线程消亡，clearAbort 就再也测不到）。
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(p.deps().context(), p, state),
            state, new ArrayList<>(), autoCompactor);

        assertThat(summarizeRan.get())
            .as("前置：auto 压缩必须真的跑过（否则下面两条断言对「没注册过」也成立 = 空断言）")
            .isTrue();
        assertThat(CompactProgressState.currentAbort())
            .as("finally 的 clearAbort 必须把当前压缩 abort 源出栈：残留会让后续 StreamCompactSummary"
                + " 读到<b>上一次</b>压缩的控制器（跨 turn 串台）")
            .isNull();
        assertThat(CompactProgressState.abortForSession(SESSION_FINISHED))
            .as("finally 的 removeSessionAbort 必须移除会话槽位：runAbort 此处<b>未被取消</b>，"
                + "残留 → 本断言返回 true（前端之后按 Esc 会去 abort 一个已经结束的压缩，"
                + "在 auto 路径上更会连带 abort 整个 run 控制器）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 在摘要回调内<b>阻塞</b>的 AutoCompactor —— 制造「压缩在飞」窗口，供另一线程断言。
     *
     * <p>回调全程跑在压缩线程（生产会话线程）上：{@code abortRead} 读的是生产 supplier 的
     * <b>同一表达式</b> {@code CompactProgressState.currentAbort()}（ThreadLocal），
     * 故它不是「测试自己设置自己读」，而是对生产注册动作的观察。
     */
    private static AutoCompactor blockingAutoCompactor(
            CountDownLatch inside, CountDownLatch release,
            AtomicReference<AbortController> abortRead, AtomicBoolean sawCancelled) {
        return new AutoCompactor(
            // 恒越阈值（对任意窗口解析结果都触发；同 CompactSessionCostWiringTest 口径）
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
    }

    /** 压缩调用 usage（四元组非零 → 成功分支的成本结转路径同样被驱动）。 */
    private static CompactConversation.TokenUsage compactionUsage() {
        return new CompactConversation.TokenUsage(1_000, 200, 3_000, 400);
    }

    private static QueryParams params(AgentState state, AgentLoopContext ctx,
                                      AbortController abortController) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            // 会话 id 与 state.sessionId() 同源（生产 buildBaseToolUseContext 即取 state.sessionId()）
            ToolUseContext.of(UUID.randomUUID(), state.sessionId(), PermissionMode.DEFAULT,
                List.of(), "", abortController),
            QuerySource.USER, COMPACT_MODEL, null,
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

    /** 36 组件 ctx（第 36 位 = modelCostCalculator，压缩成功分支的成本结转依赖它）。 */
    private static AgentLoopContext agentLoopContext(LlmProviderFactory factory) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null,   // 1-10
            factory, null, null, null, null, null, null, null, null,      // 11-19
            FeatureFlags.ALL_DISABLED,                                    // 20 featureFlags
            null, null, null, null, null,                                 // 21-25
            null, null, null, null, null,                                 // 26-30
            null, null, null, null, null,                                 // 31-35
            costCalculator());                                            // 36 modelCostCalculator
    }

    /** 计价器桩：窗口/上限值同 CompactSessionCostWiringTest（保证阈值体系与既有用例同解）。 */
    private static ModelCostCalculator costCalculator() {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(false);
        when(calc.contextWindowFor(anyString())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString())).thenReturn(8_192L);
        when(calc.calculateCostYuan(anyString(), any(AgentUsage.class), anyBoolean())).thenReturn(0.0);
        return calc;
    }

    private static LlmProviderFactory plainReplyProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
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
