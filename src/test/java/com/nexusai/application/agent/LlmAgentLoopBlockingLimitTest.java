package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [H7-arch Phase 5 P4 C1] blocking-limit 预检测试 · 对齐 CC query.ts:615-648。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>超窗 → provider.stream 不被调 + exitReason=BLOCKING_LIMIT</b> — CC 在 callModel 前
 *       {@code calculateTokenWarningState(tokenCountWithEstimation(messagesForQuery) - snipTokensFreed,
 *       mainLoopModel)}，{@code isAtBlockingLimit} 时 yield PROMPT_TOO_LONG + return
 *       {@code {reason:'blocking_limit'}}。Java 端必须跳过 LLM 调用，避免超窗请求 413。</li>
 *   <li><b>skip 条件</b> — 本 turn 刚压缩过（justCompacted）/ compact 源 / session_memory 源 /
 *       RC 或 CC 接管时跳过预检。测试覆盖"未压缩 + USER 源 → 预检生效"。</li>
 * </ol>
 *
 * <p><b>[H7-arch Phase 5-2 P3 测试同步]</b> 18 个轻方法 static 化后 Mockito 无法 stub runtime 的
 * {@code computeBudgetFromGates / estimateMessagesTokens}。改为经 {@link TestContexts#tokenBudgetBeans}
 * 注入 TokenBudgetBeans mock（TokenEstimator 固定 tokenUsage、ModelMapper/ProviderMapper 固定
 * contextWindow）驱动 static 逻辑返回精确预算数值（意图不变：估算超窗 → 拦截）。
 *
 * <p><b>RED teeth</b>: revert LlmAgentLoop 中 blocking-limit 预检（删除估计/比较或删除 break）→
 * provider.stream 会被调用、exitReason 不再是 BLOCKING_LIMIT，本测试必须 fail。
 */
class LlmAgentLoopBlockingLimitTest {

    /** 组装 messagesForLlm（含 memory/todo/task 注入）后 token 估算超窗 → 必须拦截。 */
    private static final int MOCK_TOKEN_USAGE = 100_000;
    /** contextWindow 50000 → blockingLimit = 50000 - 3000 = 47000 &lt; 100000 → 触发。 */
    private static final int MOCK_CONTEXT_WINDOW = 50_000;

    @Test
    @DisplayName("token 估算超窗 → provider.stream 不被调 + exitReason=BLOCKING_LIMIT（CC query.ts:637-647）")
    void tokenUsageOverWindow_blocksProviderCall() {
        // ── 1. P3-⑤ 后重方法已 static 化（本测试不触发 executor 构建，无需 mock）──

        // ── 2. mock provider：若 blocking-limit 未拦截，stream 会被调 → 用 verify(never) 守住 ──
        // 给 12 参 stream 一个"正常完成"的 stub：万一 blocking-limit 回归，loop 能快速完成
        // （而非挂 300s stream timeout），断言立刻暴露 exitReason != BLOCKING_LIMIT。
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("should not happen under blocking-limit");
            onMsg.accept(new AssistantMessage("should not happen under blocking-limit", "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        whenFactoryGetProvider(factory, provider);

        // ── 3. 构造最小 AgentLoopContext（未压缩 / 无 feature / 无 tool / 无 hook）──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null,
            TestContexts.tokenBudgetBeans(MOCK_CONTEXT_WINDOW, MOCK_TOKEN_USAGE));

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        // ── 4. 驱动 loop（[H7-arch Phase 5-2 B1] 收敛签名：queryLoop(loop.QueryParams, state, uuids)）──
        LoopResult result = LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 5. 断言 ──
        assertThat(state.exitReason())
            .as("token 估算超窗必须 exitReason=BLOCKING_LIMIT（CC query.ts:648 return {reason:'blocking_limit'}）")
            .isEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        // provider.stream 必须 0 次调用（loop 已调用 provider.type()，故不能用 verifyNoInteractions）
        verify(provider, never()).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(state.messages().get(state.messages().size() - 1).content())
            .as("blocking-limit 必须 append PROMPT_TOO_LONG assistant 错误消息（CC createAssistantAPIErrorMessage）")
            .contains("too long");
    }

    @Test
    @DisplayName("源为 COMPACT → 跳过预检，provider 正常调用（CC: forked agent 需运行降 token）")
    void compactSource_skipsBlockingCheck() {
        // 与上例同配置但 querySource=COMPACT：skip 条件命中 → 不拦截
        // provider 需要真实完成（onComplete）否则 loop 走 stream timeout
        // [IMP-SP-08] ModelCaller 切 blocks 重载：loop 恒经 splitSysPromptPrefix → blocks 发送，
        //   必须 stub LlmProvider 17-arg blocks 重载（Mockito mock 不执行 default 方法体，
        //   stub 13-arg String 重载不会被 17-arg blocks 委托到）。
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
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        whenFactoryGetProvider(factory, provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        // COMPACT 源跳过预检，但注入相同预算数值保证"若 skip 回归 → 拦截"可被本测试捕获。
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null,
            TestContexts.tokenBudgetBeans(MOCK_CONTEXT_WINDOW, MOCK_TOKEN_USAGE));

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.COMPACT, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        assertThat(state.exitReason())
            .as("COMPACT 源跳过预检 → 走正常 LLM 调用（exitReason 应为 NORMAL 而非 BLOCKING_LIMIT）")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        verify(provider).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static void whenFactoryGetProvider(LlmProviderFactory factory, LlmProvider provider) {
        org.mockito.Mockito.when(factory.getProvider(any(), any())).thenReturn(provider);
    }
}
