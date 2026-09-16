package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [G4] `rcOwnsBlocking` 守护 · <b>子代理超阈不得发生 {@code BLOCKING_LIMIT} 硬退</b>。
 *
 * <h2>WHY 需要本类（G1 之后仍无人守护的那一环）</h2>
 * <p>G1 让压缩器经 {@code AgentLoopContext} 送达子代理后，`rcOwnsBlocking` 表达式里的
 * {@code autoCompactor != null} 在子代理路径**恒真** ⇒ 与 CC {@code query.ts:633} 的全局
 * {@code isAutoCompactEnabled()} 行为等价 ⇒ 子代理超阈时**不再**被 blocking 预检硬退。
 * 但 G1 的 {@link SubagentAutoCompactWiringG1Test} 只断言「真的发生了压缩」（`compact_boundary`），
 * <b>没有任何测试锁定「不发生硬退」</b>（grep 实测：该类无 `BLOCKING_LIMIT` / `exitReason` /
 * `PROMPT_TOO_LONG` 断言）⇒ 若有人把 G1 的派生行改掉、或把工厂实参改回 {@code null}，
 * 子代理会**静默退回**今天这个 bug：在 {@code callModel} 之前被合成
 * `PROMPT_TOO_LONG` + `BLOCKING_LIMIT` 硬退，**请求根本发不出去** ⇒ 拿不到真实 413 ⇒
 * reactive 应急恢复链永远进不去。
 *
 * <h2>夹具（沿用本仓既有「热夹具」惯例 · {@code E1aForkShieldGateTest} 同款）</h2>
 * <p>{@code TestContexts.tokenBudgetBeans(50000, 100000)} ⇒ blockingLimit = 50000 − 3000 = 47000
 * &lt; tokenUsage 100000 ⇒ **未被豁免的来源必被拦截**。本类第 3 个用例是**夹具活性自检**
 * （与 E1a 的「主线程对照」同目的）：同一热夹具下**不装压缩器** ⇒ 必须被拦截 ——
 * 否则前两条断言的「没硬退」会是**假绿**（夹具根本不热）。
 *
 * <h2>⛔ 本类不重复 G1 的断言</h2>
 * <p>「真的发生压缩（compact_boundary 出现）」由 {@link SubagentAutoCompactWiringG1Test} 承担；
 * 本类只补**新维度**：{@code exitReason != BLOCKING_LIMIT} + 无合成的 `PROMPT_TOO_LONG` 消息。
 */
@DisplayName("[G4] 子代理超阈不硬退：rcOwnsBlocking 恒真（等价 CC 全局判据）")
class SubagentBlockingLimitShieldG4Test {

    /** 组装后 token 估算超窗 → 未豁免来源必被 blocking-limit 拦截（同 E1aForkShieldGateTest）。 */
    private static final int MOCK_TOKEN_USAGE = 100_000;
    /** contextWindow 50000 → blockingLimit = 47000 &lt; 100000 → 触发。 */
    private static final int MOCK_CONTEXT_WINDOW = 50_000;

    private static final String SUMMARY_MARK = "<summary>";

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
    }

    // ════════════════ 1. 新维度：装了压缩器 ⇒ 子代理超阈不硬退 ════════════════

    @Test
    @DisplayName("[G4] 子代理来源超窗 + 压缩器已接线 ⇒ 不发生 BLOCKING_LIMIT 硬退（无合成 PROMPT_TOO_LONG）")
    void subagentOverWindow_noBlockingLimitHardExit_whenCompactorsWired() {
        Drive d = driveSubagent(ctxWithCompactors(autoCompactor(), enabledReactiveCompactor()));

        assertThat(d.exitReason)
            .as("⛔ 子代理被 blocking-limit 硬退 = G1 之前的 bug 复现（请求发不出去、拿不到真实 413）"
                + "：rcOwnsBlocking 必须因 autoCompactor != null 恒真而豁免预检")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(d.hasSynthesizedPromptTooLong)
            .as("⛔ 不得出现合成的 PROMPT_TOO_LONG assistant 错误消息（LlmAgentLoop 预检 break 的产物）")
            .isFalse();
    }

    // ════════════════ 2. 隔离 rcOwnsBlocking 这一条腿（auto 不触发时仍豁免）════════════════

    @Test
    @DisplayName("[G4] 仅 rcOwnsBlocking 生效（autocompact 本轮未触发）⇒ 仍不硬退 —— 把该腿与 justCompacted 区分开")
    @SuppressWarnings("unchecked")
    void subagentOverWindow_rcOwnsBlockingAloneShields_whenAutoCompactDoesNotFire() {
        // 真实场景：autocompact 已启用但本轮**没有**触发（上一轮压缩失败/熔断、或门控暂不满足）
        //   ⇒ justCompacted=false ⇒ 若没有 rcOwnsBlocking，子代理会在本轮被直接硬退。
        AutoCompactor enabledButNotFiring = mock(AutoCompactor.class);
        when(enabledButNotFiring.isAutoCompactEnabled()).thenReturn(true);
        when(enabledButNotFiring.shouldAutoCompact(anyList(), anyString(), anyString(), anyInt()))
            .thenReturn(false);
        // 兜底桩：即便 loop 仍调用 autoCompactIfNeeded，也返回「未压缩」而非 null（防 NPE 掩盖真实断言）
        when(enabledButNotFiring.autoCompactIfNeeded(anyList(), anyInt(), any(), any(), any()))
            .thenAnswer(inv -> new AutoCompactor.AutoCompactResult(
                false, (List<ChatMessageDto>) inv.getArgument(0), null, 0, null, null));

        Drive d = driveSubagent(ctxWithCompactors(enabledButNotFiring, enabledReactiveCompactor()));

        assertThat(d.exitReason)
            .as("⛔ 本条只靠 rcOwnsBlocking 豁免（autocompact 未触发 ⇒ justCompacted 救不了）"
                + "：把它与 justCompacted 区分开，否则用例 1 可能因 justCompacted 而假绿")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(d.hasSynthesizedPromptTooLong).isFalse();
    }

    // ════════════════ 3. 夹具活性自检（防假绿）════════════════

    @Test
    @DisplayName("[G4 自检] 同一热夹具下不装压缩器 ⇒ 必须被拦截（证明夹具确实是「热」的，否则前两条假绿）")
    void control_sameHotFixture_withoutCompactors_stillTripsBlockingLimit() {
        Drive d = driveSubagent(ctxWithoutCompactors());

        assertThat(d.exitReason)
            .as("热夹具自检：同预算下无压缩器豁免 ⇒ 必须 BLOCKING_LIMIT（否则前两条断言的「没硬退」毫无鉴别力）")
            .isEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(d.hasSynthesizedPromptTooLong)
            .as("被拦截时必须伴随合成的 PROMPT_TOO_LONG 消息（本类用该产物做观测面）")
            .isTrue();
    }

    // ════════════════════════════════ fixtures ════════════════════════════════

    private record Drive(AgentState.ExitReason exitReason, boolean hasSynthesizedPromptTooLong) {}

    /** 走**生产子代理所走的** 4 参 boolean 重载（形参 autoCompactor 恒 null ⇒ 只能靠 ctx 分量翻包）。 */
    private static Drive driveSubagent(AgentLoopContext ctx) {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, UUID.randomUUID());   // 子代理：agentId ≠ sessionId
        state.appendMessage(userMessage("m1", "q"));

        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state),
            state, new ArrayList<>(), /*skillListingResume=*/false);            // ← 生产子代理重载

        boolean synthesized = state.rawMessages().stream().anyMatch(m ->
            Role.assistant.equals(m.role())
                && com.nexusai.application.agent.api.ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE.equals(m.content()));
        return new Drive(state.exitReason(), synthesized);
    }

    /** 38 参 canonical ctx · 位置 11=providerFactory、20=flags、21=reactive、27=tokenBudget、37/38=压缩器。 */
    private static AgentLoopContext ctxWithCompactors(AutoCompactor auto, ReactiveCompactor reactive) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null,                    // 1-9
            null, completingProviderFactory(), null, null, null, null, null, null, null, null,  // 10-19
            FeatureFlags.ALL_DISABLED,                                               // 20
            reactive, null, null, null, null,                                        // 21-25
            null, TestContexts.tokenBudgetBeans(MOCK_CONTEXT_WINDOW, MOCK_TOKEN_USAGE), null, null, null,  // 26-30
            null, null, null, null, null, null,                                      // 31-36
            null,                                                                    // 37 microCompactor
            auto);                                                                   // 38 autoCompactor
    }

    /** 对照夹具：同热预算但**无**压缩器（compat 32 参 ⇒ auto/reactive 皆 null ⇒ rcOwnsBlocking=false）。 */
    private static AgentLoopContext ctxWithoutCompactors() {
        return TestContexts.agentLoopContext(
            null, completingProviderFactory(), null, null,
            TestContexts.tokenBudgetBeans(MOCK_CONTEXT_WINDOW, MOCK_TOKEN_USAGE),
            FeatureFlags.ALL_DISABLED, null);
    }

    /** 启用态的 ReactiveCompactor（rcOwnsBlocking 的另一半：非空 + enabled）。 */
    private static ReactiveCompactor enabledReactiveCompactor() {
        ReactiveCompactor rc = mock(ReactiveCompactor.class);
        when(rc.isReactiveCompactEnabled()).thenReturn(true);
        return rc;
    }

    /** 超阈 autoCompactor：tokenCounter 恒 200_000 ⇒ shouldAutoCompact=true（与 G1 同款）。 */
    private static AutoCompactor autoCompactor() {
        return new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult(SUMMARY_MARK + "compact</summary>", null));
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, QuerySource source, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return false; }
        };
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            source, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 纯文本 stop provider（单轮收尾）· 位置常量按重载 arity 分派（同 E1aForkShieldGateTest 的教训）。 */
    @SuppressWarnings("unchecked")
    private static LlmProviderFactory completingProviderFactory() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        org.mockito.stubbing.Answer<Object> answer = inv -> {
            Object[] args = inv.getArguments();
            int chunkIdx = args.length == 20 ? 10 : 9;
            int msgIdx = args.length == 20 ? 11 : 10;
            int doneIdx = args.length == 20 ? 17 : 16;
            java.util.function.Consumer<String> onChunk = (java.util.function.Consumer<String>) args[chunkIdx];
            java.util.function.Consumer<AssistantMessage> onMsg =
                (java.util.function.Consumer<AssistantMessage>) args[msgIdx];
            Runnable onComplete = (Runnable) args[doneIdx];
            onChunk.accept("plain text reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of(), "", null, 5L));
            }
            onComplete.run();
            return null;
        };
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
