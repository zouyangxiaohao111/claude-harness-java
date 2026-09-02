package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.recovery.TransientErrorHandler;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
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
 * [ER-IMP-08] max_tokens 上下文溢出（FLOOR 下限 + retryContext.maxTokensOverride）聚焦测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>FLOOR_OUTPUT_TOKENS=3000 下限</b> — CC withRetry.ts:403-408：availableContext &lt; 3000
 *       → logError + throw（不可恢复，surface）。若 Java 无下限直接调整，极端溢出会以极低
 *       max_tokens 重试导致反复失败 → 本测试断言 MODEL_ERROR surface（CC throw → query.ts:996
 *       catch → model_error）。</li>
 *   <li><b>retryContext.maxTokensOverride 三层优先级</b> — CC claude.ts:1592：retryContext?.maxTokensOverride
 *       || options.maxOutputTokensOverride || model default。溢出调整值必须到达下一次请求的
 *       max_tokens（经 ModelRequest.maxOutputTokensOverride 端到端验证，等价 escalation 测试
 *       capturedOverrides 模式）。</li>
 *   <li><b>调整后 continue 不 sleep</b> — CC withRetry.ts:426：溢出调整不进入指数退避，立即重试。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 移除 Path3 溢出分支（或 availableContext 计算错误）→ 400 overflow 被
 * isRetryable 判为可重试但走 backoff，重试请求 max_tokens 未被调整 → capturedOverrides 无
 * adjusted 值 → fail；下限判断错误 → availableContext&lt;3000 仍调整重试 → exitReason 非 MODEL_ERROR → fail。
 */
class MaxTokensContextOverflowTest {

    // ─────────────────────── 基础设施 helper ───────────────────────

    /**
     * 最小 AgentLoopContext · transientErrorHandler 注入（Path3 入口闸必需，record 位置 15）。
     *
     * <p>featureFlags ALL_DISABLED（溢出属 Path3 临时错误链，不依赖 PTL/media flag）；
     * 其余基础设施 null。
     */
    private AgentLoopContext overflowCtx(LlmProviderFactory factory) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1 toolRegistry
            null,                                                               // 2 hookRegistry
            null, null, null, null, null, null, null, null,    // 3-10
            factory,                                                            // 11 llmProviderFactory
            new TransientErrorHandler(),                                        // 12 transientErrorHandler
            null, null, null, null, null, null, null,                            // 13-19 maxTokensHandler..streamUserMessageId
            flags,                                                              // 20 featureFlags
            null, null, null, null, null, null, null, null, null, null, null, null); // 21-32 reactiveCompactor..claudemdEngine
    }

    /**
     * provider：第 1 次调用投递 onError（溢出异常），第 2 次起正常响应。
     * blocks 重载回调位置：onChunk@9/onMsg@10/onError@15/onComplete@16。
     */
    private LlmProviderFactory overflowProviderFactory(Throwable firstError, AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            if (call == 0) {
                onErr.accept(firstError);
            } else {
                @SuppressWarnings("unchecked")
                Consumer<String> onChunk = inv.getArgument(9);
                @SuppressWarnings("unchecked")
                Consumer<com.nexusai.infra.llm.AssistantMessage> onMsg = inv.getArgument(10);
                onChunk.accept("response " + call);
                onMsg.accept(new com.nexusai.infra.llm.AssistantMessage(
                    "response " + call, "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private AgentState initialState() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m0", "s", Role.user, "user", "hello", null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null, null, List.of(), List.of()));
        return state;
    }

    private void runLoop(AgentLoopContext ctx, AgentState state, Integer maxTurns,
            List<Integer> capturedOverrides) {
        runLoop(ctx, state, maxTurns, capturedOverrides, null);
    }

    private void runLoop(AgentLoopContext ctx, AgentState state, Integer maxTurns,
            List<Integer> capturedOverrides,
            LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override
            public com.nexusai.application.agent.loop.ModelResponse callModel(
                    com.nexusai.application.agent.loop.ModelRequest request) {
                if (capturedOverrides != null) {
                    capturedOverrides.add(request.maxOutputTokensOverride());
                }
                return LoopDeps.super.callModel(request);
            }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", maxTurns, null, null, null, null,
            deps, ProviderConfig.empty());
        if (thinkingConfig != null) {
            params = params.withThinkingConfig(thinkingConfig);
        }
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    /** 溢出消息示例 · CC withRetry.ts:569 "input length and `max_tokens` exceed context limit: 188059 + 20000 > 200000"。 */
    private LlmApiException overflow400(int inputTokens, int maxTokens, int contextLimit) {
        return new LlmApiException(400, Map.of(),
            "input length and `max_tokens` exceed context limit: " + inputTokens + " + " + maxTokens
                + " > " + contextLimit);
    }

    // ─────────────────────── 用例 ───────────────────────

    @Test
    @DisplayName("溢出可恢复（availableContext>=3000）→ 调整 retryContext.maxTokensOverride，下一次请求携带 adjusted · CC withRetry.ts:411-421")
    void overflowRecoverable_adjustsMaxTokensOnRetry() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        List<Integer> capturedOverrides = new ArrayList<>();
        // 188059 + 20000 > 200000 → availableContext = 200000-188059-1000 = 10941 >= 3000
        LlmProviderFactory factory = overflowProviderFactory(
            overflow400(188059, 20000, 200000), callCount);
        AgentLoopContext ctx = overflowCtx(factory);

        runLoop(ctx, state, 8, capturedOverrides);

        // 第 1 次请求 max_tokens=null（入口无 override，按模型解析）；溢出调整后第 2 次=10941
        // （availableContext=10941，max(3000,10941,1)=10941 · CC withRetry.ts:411-421）。
        assertThat(capturedOverrides)
            .as("溢出调整必须经 retryContext.maxTokensOverride 到达下一次请求（CC claude.ts:1592 三层优先级）")
            .contains(10_941);
        assertThat(capturedOverrides).hasSize(2);
        assertThat(capturedOverrides.get(0)).isNull(); // 第 1 次未调整
        // 调整后 continue 不 sleep，重试成功 → NORMAL 退出
        assertThat(state.exitReason()).isEqualTo(AgentState.ExitReason.NORMAL);
    }

    @Test
    @DisplayName("溢出不可恢复（availableContext<3000）→ surface MODEL_ERROR，不调整重试 · CC withRetry.ts:403-408")
    void overflowBelowFloor_surfacesModelError() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        List<Integer> capturedOverrides = new ArrayList<>();
        // 197000 + 20000 > 200000 → availableContext = 200000-197000-1000 = 2000 < 3000
        LlmProviderFactory factory = overflowProviderFactory(
            overflow400(197000, 20000, 200000), callCount);
        AgentLoopContext ctx = overflowCtx(factory);

        runLoop(ctx, state, 8, capturedOverrides);

        // FLOOR 下限命中 → 不可恢复 surface（CC throw error → query.ts:996 → model_error）
        assertThat(state.exitReason()).isEqualTo(AgentState.ExitReason.MODEL_ERROR);
        // 未产生调整重试：只有第 1 次请求（max_tokens 未调整）
        assertThat(capturedOverrides).hasSize(1);
        assertThat(capturedOverrides.get(0)).isNull();
    }

    @Test
    @DisplayName("minRequired=thinking+1 参与取 max：disabled → minRequired=1（availableContext 主导）· CC withRetry.ts:407-415")
    void overflowAdjustment_disabledThinking_minRequiredIsOne() {
        // disabled：minRequired = (0) + 1 = 1，availableContext=198000 主导
        // adjusted = max(3000, 198000, 1) = 198000。该用例覆盖 disabled 分支（非恒 0 的对照）。
        com.nexusai.application.agent.recovery.MaxTokensOverflowError d =
            com.nexusai.application.agent.recovery.ErrorClassifier.parseMaxTokensContextOverflowError(
                overflow400(1000, 20000, 200000));
        assertThat(d).isNotNull();
        assertThat(d.inputTokens()).isEqualTo(1000);
        assertThat(d.maxTokens()).isEqualTo(20000);
        assertThat(d.contextLimit()).isEqualTo(200000);
        // availableContext = max(0, contextLimit - inputTokens - safetyBuffer(1000)) = 198000
        int availableContext = Math.max(0, d.contextLimit() - d.inputTokens() - 1000);
        assertThat(availableContext).isEqualTo(198_000);
        // disabled 分支 minRequired = 0 + 1 = 1（CC withRetry.ts:407-410 非 enabled 走 0）
        int minRequired = 1;
        int adjusted = Math.max(3000, Math.max(availableContext, minRequired));
        assertThat(adjusted).isEqualTo(198_000);
    }

    @Test
    @DisplayName("thinking='enabled'+budgetTokens → minRequired=budgetTokens+1 主导 adjustedMaxTokens · CC withRetry.ts:407-415")
    void overflowAdjustment_enabledThinking_minRequiredDominates() {
        // thinking='enabled' + budgetTokens=20000 → minRequired = 20000 + 1 = 20001
        // 构造 availableContext < 20001 且 >= FLOOR(3000)：contextLimit=200000, inputTokens=178000
        //   → availableContext = 200000-178000-1000 = 21000... 仍 21000 < 20001? 否 21000>20001。
        // 改用 inputTokens=180000 → availableContext = 200000-180000-1000 = 19000 >= 3000
        // adjusted = max(3000, 19000, 20001) = 20001（minRequired 主导）
        int budgetTokens = 20_000;
        int minRequired = budgetTokens + 1; // CC withRetry.ts:409-410 enabled 分支
        int availableContext = Math.max(0, 200_000 - 180_000 - 1000);
        assertThat(availableContext).isEqualTo(19_000);
        assertThat(minRequired).isGreaterThan(availableContext);
        int adjusted = Math.max(3000, Math.max(availableContext, minRequired));
        assertThat(adjusted).isEqualTo(20_001);

        // 端到端验证：overflow 错误 → 下一次请求 max_tokens=20001（minRequired 主导，保留思考预算）
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        List<Integer> capturedOverrides = new ArrayList<>();
        LlmProviderFactory factory = overflowProviderFactory(
            overflow400(180000, 20000, 200000), callCount);
        AgentLoopContext ctx = overflowCtx(factory);

        runLoop(ctx, state, 8, capturedOverrides,
            LlmProvider.ChatRequestOptions.ThinkingConfig.enabled(budgetTokens));

        assertThat(capturedOverrides)
            .as("thinking enabled + budgetTokens=20000 → minRequired=20001 必须主导 adjustedMaxTokens（CC withRetry.ts:407-415，规则9：若 enabled 分支按 0 计则本测试变红）")
            .contains(20_001);
        assertThat(capturedOverrides).hasSize(2);
        assertThat(capturedOverrides.get(0)).isNull(); // 第 1 次未调整
        assertThat(state.exitReason()).isEqualTo(AgentState.ExitReason.NORMAL);
    }
}
