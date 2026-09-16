package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.MicroCompactResult;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [G1] 子代理自动压缩「接线」端到端证据 · 走**生产子代理所走的那条重载**。
 *
 * <h2>WHY 需要本测试（既有 {@link SubagentAutoCompactGateCcTest} 证明不了接线）</h2>
 * <p>既有 3 个 {@code ..._autoCompacts} 用例调的是 <b>4 参 {@code AutoCompactor} 重载</b>
 * （{@code queryLoop(params, state, uuids, AutoCompactor)}，测试直接把压缩器当形参注入）；
 * 而生产子代理（{@code SubagentExecutor:4676}）走的是 <b>4 参 {@code boolean} 重载</b>
 * （{@code queryLoop(params, state, uuids, skillListingResume)}）—— 该重载中间链一律把
 * {@code autoCompactor}/{@code microCompactor} 硬传 {@code null}。两条重载不同 ⇒ 既有用例
 * 是**假绿**：它证明「压缩器被注入时能压缩」，不证明「子代理路径能拿到压缩器」。
 *
 * <h2>本测试怎么证明</h2>
 * <p>压缩器**只**放进 {@code AgentLoopContext} 的 {@code autoCompactor()}/{@code microCompactor()}
 * 分量（走 38 参 canonical 构造器，等价 {@code AgentLoopContextFactory} 的装配形态），
 * 调用方形参恒 {@code null} —— 若 {@code LlmAgentLoop.queryLoop} 的「形参优先，为空则翻包」
 * 派生行不存在或失效，压缩器就丢，下列断言必红（见 §反向实验）。
 */
@DisplayName("[G1] 子代理自动压缩接线：压缩器只经 AgentLoopContext 工具包下发（生产 4 参 boolean 重载）")
class SubagentAutoCompactWiringG1Test {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
    }

    // ───────────────────── 主证据：auto 腿 ─────────────────────

    @Test
    @DisplayName("子代理（4 参 boolean 重载 · 形参 null）超阈 → ctx.autoCompactor() 翻包生效 ⇒ 真压缩")
    void subagentOverLimit_autoCompacts_viaCtxWiringOnly() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();

        // ⛔ 关键：压缩器只进 ctx 分量；queryLoop 的形参（含 autoCompactor）恒 null。
        AgentLoopContext ctx = ctxWithCompactors(factory, null, autoCompactor());
        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);

        LoopResult result = LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state),
            state, new ArrayList<>(), /*skillListingResume=*/false);   // ← 生产子代理所走的 4 参 boolean 重载

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.rawMessages())
            .as("[G1] 子代理超阈必须真的发生自动压缩（压缩器经 ctx 工具包下发 ⇒ 形参 null 也能拿到）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("反向对照：同一重载 + ctx.autoCompactor()==null（等价接线前）⇒ 不压缩")
    void subagentOverLimit_noCompact_whenCtxCarriesNoCompactor() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();

        AgentLoopContext ctx = ctxWithCompactors(factory, null, null);   // ctx 分量也是 null = G1 接线前形态
        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);

        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state),
            state, new ArrayList<>(), /*skillListingResume=*/false);

        assertThat(state.rawMessages())
            .as("无任何压缩器来源 ⇒ 不压缩（负对照：证明上面的 compact_boundary 不是别处产生的）")
            .noneMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    // ───────────────────── micro 腿 ─────────────────────

    @Test
    @DisplayName("micro 腿：ctx.microCompactor() 被 loop 真实调用（同一派生行的另一半）")
    void microCompactor_legIsTakenFromCtx() {
        AgentState state = subagentState();
        appendLargeMessages(state, 3);
        LlmProviderFactory factory = completingProviderFactory();

        MicroCompactor micro = Mockito.mock(MicroCompactor.class);
        // 默认 no-op 语义：返回同一列表引用（loop 侧 `mc.messages() != beforeMicro` 判假 → 不动投影）。
        when(micro.microcompactMessages(anyList(), any(), any(), any()))
            .thenAnswer(inv -> new MicroCompactResult(inv.getArgument(0), null));

        AgentLoopContext ctx = ctxWithCompactors(factory, micro, null);
        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);

        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state),
            state, new ArrayList<>(), /*skillListingResume=*/false);

        verify(micro).microcompactMessages(anyList(), any(), any(), any());
    }

    // ───────────── 工厂 → ctx 那一环的守护（独立于上面 3 个用例） ─────────────

    /**
     * ⭐ 本用例是「工厂 → ctx」这一环的<b>唯一</b>守护。
     *
     * <p><b>WHY 必须单独守护（本仓铁律：seam 层有守护 ≠ 接线被守护）</b>：上面 3 个用例用
     * {@link #ctxWithCompactors} <b>手工</b>造 38 参 canonical ctx，压缩器直接塞进 ctx ⇒
     * <b>整条链路绕过了 AgentLoopContextFactory</b>。故把工厂构造点
     * {@code microCompactor, autoCompactor)} 改回 {@code null, null)} 时它们<b>仍全绿</b>
     * （已实测：Tests run: 3, Failures: 0）—— 即「工厂是否真的把压缩器装进 ctx」零守护，
     * 漏装时生产子代理静默退回 blocking 预检硬退 bug（请求发不出去）。
     *
     * <p>本用例<b>经工厂</b>装配（{@code shared(...)} 是最轻的公开入口：全仓唯一无会话形参、
     * 不触 DB / 不触 env —— 传非空 projectRoot 即短路 {@code freshSession} 的兜底分支），
     * 只注入本批新增的两个压缩器字段，<b>不 stub 任何无关 mock</b>（守护不得变脆）。
     *
     * @see AgentLoopContextFactory#setAutoCompactor(AutoCompactor)
     */
    @Test
    @DisplayName("[G1 工厂守护] AgentLoopContextFactory 装配出的 ctx 必须携带 autoCompactor / microCompactor")
    void factoryWiresCompactorsIntoContext() {
        AutoCompactor auto = autoCompactor();
        MicroCompactor micro = Mockito.mock(MicroCompactor.class);

        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setAutoCompactor(auto);
        contextFactory.setMicroCompactor(micro);

        AgentLoopContext ctx = contextFactory.shared(System.getProperty("java.io.tmpdir"));

        assertThat(ctx.autoCompactor())
            .as("工厂必须把 autoCompactor 装进 ctx（漏装 ⇒ 生产子代理退回 blocking 预检硬退 bug，请求发不出去）")
            .isSameAs(auto);
        assertThat(ctx.microCompactor())
            .as("工厂必须把 microCompactor 装进 ctx（漏装 ⇒ 子代理 micro 压缩腿失效）")
            .isSameAs(micro);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static final String SUMMARY_MARK = "<summary>";

    /**
     * 38 参 canonical {@code AgentLoopContext}（等价 {@code AgentLoopContextFactory} 装配形态的
     * 最小版）：位置 11 = llmProviderFactory、20 = FeatureFlags、37 = microCompactor、38 = autoCompactor，
     * 其余恒 null。⛔ 分量顺序必须与 record 一致（micro 在前、auto 在后）。
     */
    private static AgentLoopContext ctxWithCompactors(LlmProviderFactory factory,
                                                     MicroCompactor microCompactor,
                                                     AutoCompactor autoCompactor) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null,           // 1-9
            null, factory, null, null, null, null, null, null, null, null,  // 10-19
            FeatureFlags.ALL_DISABLED,                                      // 20 featureFlags
            null, null, null, null, null,                                   // 21-25
            null, null, null, null, null,                                   // 26-30
            null, null, null, null, null, null,                             // 31-36
            microCompactor,                                                 // 37 microCompactor（G1）
            autoCompactor);                                                 // 38 autoCompactor（G1）
    }

    /** 子代理状态：agentId ≠ sessionId（生产子代理语义）。 */
    private static AgentState subagentState() {
        String session = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        return new AgentState("sys", session, UUID.randomUUID());
    }

    /** 超阈 autoCompactor：tokenCounter 恒 200_000；摘要走桩回调（不触 LLM）。 */
    private static AutoCompactor autoCompactor() {
        return new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult(SUMMARY_MARK + "compact</summary>", null));
    }

    private static void appendLargeMessages(AgentState state, int count) {
        for (int i = 0; i < count; i++) {
            state.appendMessage(singleMessage("u" + i, "hi"));
        }
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, QuerySource source, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            source, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** provider 正常完成（onChunk/onMsg/onComplete）· 无 blocking 拦截时 loop 快速收尾。 */
    private static LlmProviderFactory completingProviderFactory() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain text reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
