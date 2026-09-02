package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.recovery.FastModeRuntimeState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
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
 * [DEC-RV-02 · FIX-16] per-call fallbackModel 接线测试 · 对齐 CC withRetry.ts:130/337 + main.tsx:2020。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: CC 的降级模型由<b>调用方按调用传入</b>
 * （{@code --fallback-model} → {@code userSpecifiedFallbackModel} → {@code options.fallbackModel}，
 * withRetry.ts:337 {@code if (options.fallbackModel)} 优先于任何全局默认）；Java 端 per-call
 * 候选来源 = HTTP 请求体 {@code SendMessageRequest.fallbackModel} → {@link RunRequest} 工厂
 * → {@link QueryParams#fallbackModel()} → {@link TransientErrorHandler#handle(... fallbackModel)}
 * （fallbackModelParam 非空优先，env {@code FALLBACK_MODEL_ID} 仅兜底，决策 10）。
 *
 * <p>本测试锁定<b>按调用传入优先于 env 兜底</b>：构造携带 fallbackModel="per-call-A" 的
 * {@link RunRequest}（经 web 层工厂重载），env 兜底设 "env-B"，连续 529 触发降级后断言
 * 实际使用的降级模型是 "per-call-A" 而非 "env-B"。
 *
 * <p><b>RED teeth</b>: ① 删 web 层工厂 fallbackModel 透传（session/user 重载传 null）
 * → fallback 模型变 "env-B"，断言必须红；② TransientErrorHandler.tryFallbackModel 改回
 * 直读 env（不读 fallbackModelParam）→ "per-call-A" 断言必须红。
 */
class LlmAgentLoopModelFallbackPerCallTest {

    private static final String OPUS_ELIGIBLE = "claude-opus-4-20250514"; // 非自定义 Opus，529 资格闸放行
    private static final String PER_CALL_A = "per-call-A";
    private static final String ENV_B = "env-B";

    @AfterEach
    void restoreEnv() {
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER =
            () -> com.nexusai.application.agent.api.ApiErrors.FALLBACK_MODEL_ID;
        FastModeRuntimeState.reset();
        FastModeRuntimeState.ENV_READER = System::getenv;
        LlmAgentLoop.setRetryKeepAliveListener(null);
    }

    // ── 契约级：web 层工厂重载携带 fallbackModel ──

    @Test
    @DisplayName("session 工厂 9-arg 重载携带 fallbackModel → RunRequest.fallbackModel 非 null")
    void sessionOverload_carriesFallbackModel() {
        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RunRequest req = RunRequest.session("p", sid, null,
            ProviderConfig.empty(), "test-model", null, null, PER_CALL_A, null);
        assertThat(req.fallbackModel())
            .as("web 层 session 工厂必须透传 fallbackModel（DEC-RV-02 接线：SendMessageRequest → RunRequest）")
            .isEqualTo(PER_CALL_A);
    }

    @Test
    @DisplayName("user 工厂 7-arg 重载携带 fallbackModel → RunRequest.fallbackModel 非 null")
    void userOverload_carriesFallbackModel() {
        RunRequest req = RunRequest.user("p", ProviderConfig.empty(), "test-model", null, null, PER_CALL_A, null);
        assertThat(req.fallbackModel())
            .as("web 层 user 工厂必须透传 fallbackModel（VerifyChatController 入口）")
            .isEqualTo(PER_CALL_A);
    }

    // ── 循环级：per-call fallbackModel 优先于 env 兜底 ──

    @Test
    @DisplayName("连续 529 触发降级 → 实际用 per-call fallbackModel=A（非 env-B）")
    void perCallFallbackWinsOverEnv() {
        // env 兜底 = env-B；per-call = per-call-A → 断言降级用 A
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> ENV_B;
        // 禁用 fast mode（NEXUSAI_DISABLE_FAST_MODE truthy）：fast-mode 分支会把 529 当
        // 短重试拦截（withRetry.ts:284-289），不走连续 529 → fallback 计数路径。禁用后
        // 529 走标准 TransientErrorHandler 降级路径（对齐 CC 非 fast-mode 主流程）。
        FastModeRuntimeState.ENV_READER = name -> "1"; // NEXUSAI_DISABLE_FAST_MODE truthy → fast mode 关闭
        FastModeRuntimeState.reset();

        List<String> calledModels = new ArrayList<>();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        AtomicInteger call = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            int c = call.getAndIncrement();
            if (c < 3) {
                // 前 3 次连续 529 → TransientErrorHandler 达阈值抛 FallbackTriggeredError
                onErr.accept(new LlmApiException(529,
                    Map.of("retry-after", List.of("0")), "overloaded"));
            } else {
                // 第 4 次（降级模型 per-call-A）成功
                onChunk.accept("ok");
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
                onComplete.run();
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class), null, null, null, null, null,
            null, null, null,
            // fastModeEnabled=false：fast-mode 分支会把 529 当短重试拦截（withRetry.ts:284-289），
            // 不走连续 529 → fallback 计数路径。禁用后 529 走标准 TransientErrorHandler 降级路径
            // （对齐 CC 非 fast-mode 主流程，fast mode 语义由 Path3FastModeTest 单独覆盖）。
            com.nexusai.application.agent.query.QueryConfig.buildQueryConfig(
                "s", () -> true, () -> false, () -> false, () -> false),
            factory, new TransientErrorHandler(), null, null, null,
            null, null, null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null,
            null, null, null, null, null, null, null);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        // web 层工厂（session 重载）→ RunRequest.fallbackModel=per-call-A → QueryParams → loop
        RunRequest req = RunRequest.session("question", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
            ProviderConfig.empty(), OPUS_ELIGIBLE, null, null, PER_CALL_A, null);
        QueryParams queryParams = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, OPUS_ELIGIBLE, null, null,
            req.fallbackModel(), // DEC-RV-02 接线点：RunRequest.fallbackModel → QueryParams.fallbackModel
            null, null, deps, ProviderConfig.empty());

        com.nexusai.application.agent.LlmAgentLoop.queryLoop(queryParams, state, new ArrayList<>());

        // 断言：4 次调用 —— 前 3 次原模型，第 4 次（降级重试）必须用 per-call-A，绝不用 env-B
        assertThat(calledModels)
            .as("连续 529 触发降级后第 4 次调用必须用 per-call fallbackModel（CC withRetry.ts:337 options.fallbackModel 优先 env）")
            .hasSize(4)
            .endsWith(PER_CALL_A);
        assertThat(calledModels)
            .as("per-call fallbackModel 存在时 env 兜底绝不生效（DEC-RV-02 · 决策 10 env 仅默认值）")
            .doesNotContain(ENV_B);
    }
}
