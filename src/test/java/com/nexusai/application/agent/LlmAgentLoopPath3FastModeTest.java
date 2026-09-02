package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.recovery.FastModeRuntimeState;
import com.nexusai.application.agent.recovery.TransientErrorHandler;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * LlmAgentLoop Path 3 fast-mode fallback 接线测试 · 对齐 CC withRetry.ts:267-314。
 *
 * <p><b>WHY (意图验证, 规则九)</b>: Path 3 的 fast-mode fallback 分支决定 429/529 在 fast mode
 * 下的处置——长 retry-after → cooldown（切标准速度）、overage header → 永久禁用、400
 * 'Fast mode is not enabled' → 永久禁用。任一分支未接线，fast-mode 会话在 429/529 下会
 * 反复快速重试（缓存打满）或永远卡在 fast mode。
 *
 * <p><b>RED teeth</b>: 若 Path 3 未读 fast mode gate / 未触发 cooldown / 未处理 overage /
 * 未处理 isFastModeNotEnabledError → 本测试断言 FastModeRuntimeState 状态必须 fail。
 *
 * <p><b>F3 恒关（2026-08-22）</b>: 用户拍板 fast mode 恒关（非 Anthropic 无 fast-mode 服务端）。
 * 本测试用自建 QueryConfig（fastModeEnabled=() -> true）使 Path 3 fast-mode 分支可进入，
 * 但 {@code FastModeRuntimeState.isFastModeEnabled()} 恒 false → triggerFastModeCooldown 的
 * CC:218-220 守卫恒拦截（429 长 retry-after 不再触发 cooldown，走标准速度）；overage / 400
 * 拒绝的 org 级禁用（handleFastModeOverageRejection / handleFastModeRejectedByAPI）不经
 * isFastModeEnabled 守卫，仍按 CC 语义直接生效。
 *
 * <p>构造注意：TestContexts.agentLoopContext 的 transientErrorHandler 为 null（Path 3 入口门
 * {@code ctx.transientErrorHandler() != null} 跳过），本测试直接构造 AgentLoopContext 注入
 * 真实 {@link TransientErrorHandler}。
 */
class LlmAgentLoopPath3FastModeTest {

    @BeforeEach
    void setUp() {
        FastModeRuntimeState.reset();
        FastModeRuntimeState.ENV_READER = name -> null; // fast mode 全局启用（NEXUSAI_DISABLE_FAST_MODE 未设）
    }

    @AfterEach
    void tearDown() {
        FastModeRuntimeState.reset();
        FastModeRuntimeState.ENV_READER = System::getenv;
        LlmAgentLoop.setRetryKeepAliveListener(null);
    }

    private AgentLoopContext ctxWithTransientHandler(LlmProviderFactory factory) {
        QueryConfig qc = QueryConfig.buildQueryConfig(
            "s", () -> true, () -> false, () -> false, () -> true);
        return new AgentLoopContext(
            Mockito.mock(ToolRegistry.class), null, null, null, null, null, null, null, null,
            qc, factory, new TransientErrorHandler(), null, null, null, null, null, null, null,
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);  // 33 modelConfigResolver · 34 sdkEventQueue · 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    private void runLoop(LlmProviderFactory factory) {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        AgentLoopContext ctx = ctxWithTransientHandler(factory);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());
    }

    /** provider：首次 onErr 抛异常，后续成功（2nd+ 调用收尾） */
    private LlmProviderFactory providerFailsOnceThenSucceeds(LlmApiException firstError) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        AtomicInteger call = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (call.getAndIncrement() == 0) {
                onErr.accept(firstError);
            } else {
                onChunk.accept("ok");
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
                onComplete.run();
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** provider：第 1 次抛 first，第 2 次抛 second，第 3+ 次成功收尾 */
    private LlmProviderFactory providerFailsTwiceThenSucceeds(
            LlmApiException first, LlmApiException second) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        AtomicInteger call = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            int n = call.getAndIncrement();
            if (n == 0) {
                onErr.accept(first);
            } else if (n == 1) {
                onErr.accept(second);
            } else {
                onChunk.accept("ok");
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
                onComplete.run();
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    @Test
    @DisplayName("F3 恒关：fast mode 恒关 → 429 长 retry-after 不触发 cooldown（trigger 守卫恒拦截）")
    void longRetryAfterDoesNotTriggerCooldownWhenFastModeAlwaysOff() {
        LlmProviderFactory factory = providerFailsOnceThenSucceeds(
            new LlmApiException(429, Map.of("retry-after", List.of("600")), "rate limited"));
        runLoop(factory);
        // F3 恒关（用户拍板：非 Anthropic 无 fast-mode 服务端）：FastModeRuntimeState.isFastModeEnabled()
        //   恒 false → triggerFastModeCooldown 的 CC:218-220 守卫恒拦截 → 429 长 retry-after 不置 cooldown，
        //   全程走标准速度（原「cooldown 触发」断言已随恒关失效，意图反转）。
        assertThat(FastModeRuntimeState.isFastModeCooldown())
            .as("F3 恒关下 429 长 retry-after 不应触发 fast-mode cooldown")
            .isFalse();
    }

    @Test
    @DisplayName("fast mode 激活 + overage header → 永久禁用 fast mode（CC withRetry.ts:275-282）")
    void overageRejectionDisablesFastMode() {
        LlmProviderFactory factory = providerFailsOnceThenSucceeds(
            new LlmApiException(429,
                Map.of("anthropic-ratelimit-unified-overage-disabled-reason", List.of("org_level_disabled")),
                "rate limited"));
        runLoop(factory);
        assertThat(FastModeRuntimeState.isOrgDisabled())
            .as("overage 拒绝应永久禁用 fast mode")
            .isTrue();
    }

    @Test
    @DisplayName("fast mode 激活 + 400 'Fast mode is not enabled' → 永久禁用 fast mode（CC withRetry.ts:310-314）")
    void apiRejectedFastModeDisablesFastMode() {
        LlmProviderFactory factory = providerFailsOnceThenSucceeds(
            new LlmApiException(400, Map.of(), "Fast mode is not enabled for your organization"));
        runLoop(factory);
        assertThat(FastModeRuntimeState.isOrgDisabled())
            .as("isFastModeNotEnabledError 应触发 handleFastModeRejectedByAPI 永久禁用")
            .isTrue();
    }

    @Test
    @DisplayName("out-of-credits overage → 本 episode 临时禁用 fast mode，后续 429/529 走标准退避不触发 cooldown（V-PF-3 · CC withRetry.ts:280 retryContext.fastMode=false 无条件）")
    void outOfCreditsOverageSwitchesToStandardSpeed() {
        // WHY (规则九)：CC withRetry.ts:280 在 overage 拒绝后无条件 retryContext.fastMode=false
        //   （含 out-of-credits），切标准速度。Java 旧实现仅非 out-of-credits 设 orgDisabled，
        //   out-of-credits 保持 fast mode 激活 → 下一 429 仍进 fast-mode 分支触发 cooldown
        //   （缓存打满）。本测试验证 out-of-credits 拒绝后 fast-mode 分支被 episode 局部禁用跳过。
        LlmProviderFactory factory = providerFailsTwiceThenSucceeds(
            new LlmApiException(429,
                Map.of("anthropic-ratelimit-unified-overage-disabled-reason", List.of("out_of_credits")),
                "rate limited"),
            // 第二次调用：429 无 retry-after header。若 fast mode 仍激活 → 走 fast-mode 分支
            //   触发 cooldown（CC:291-304）；V-PF-3 修复后 episode 已临时禁用 → 走标准退避
            //   （backoff，~1s），不触发 cooldown。
            new LlmApiException(429, Map.of(), "rate limited"));
        runLoop(factory);
        // out-of-credits 不永久禁用 org（CC fastMode.ts:286-288 isOutOfCreditsReason）
        assertThat(FastModeRuntimeState.isOrgDisabled())
            .as("out-of-credits overage 不应永久禁用 fast mode（org_level 级）")
            .isFalse();
        // 第二次 429 若走 fast-mode 分支会触发 cooldown；修复后 episode 已禁用 → 未触发
        assertThat(FastModeRuntimeState.isFastModeCooldown())
            .as("out-of-credits overage 后本 episode fast mode 已临时禁用，后续 429 走标准退避不触发 cooldown")
            .isFalse();
    }
}
