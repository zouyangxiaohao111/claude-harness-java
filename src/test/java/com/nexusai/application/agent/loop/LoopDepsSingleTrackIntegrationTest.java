package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.tool.ToolRegistry;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-20] deps 单轨集成测试 · 对齐 CC {@code query/deps.ts:21-31}（callModel/microcompact/
 * autocompact/uuid 4 窄 IO）与 {@code query.ts:181-199}（deps 作为 query 第 2 参）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-20 删除 deps raw-cast 壳（D-09，
 * 旧 `query` 包 4 窄 IO 载体），deps 对齐面统一到 {@link LoopDeps}（主循环 {@code MainLoopDeps}）
 * 单接口。本测试守住三条不变量：
 * <ol>
 *   <li><b>主循环 deps 载体必须实现 LoopDeps 单接口</b> —— 若回归出双轨（raw-cast
 *       Object 桥接）或新 deps 载体不实现 LoopDeps，本测试因类型断言失败 → RED。</li>
 *   <li><b>主循环 LLM 调用必须经 deps.callModel</b> —— 主循环 queryLoop 内 callModel 走
 *       {@code params.deps().callModel(request)}（对齐 CC query.ts:659 {@code deps.callModel}）；
 *       若回归为直调 provider.stream，本测试因 provider mock 抛异常而 fail。</li>
 *   <li><b>主循环 chainId 必须经 deps.uuid()</b> —— 每轮 queryTracking 首轮 chainId 由
 *       {@code params.deps().uuid()} 生成（对齐 CC query.ts:353 {@code chainId: deps.uuid()}）；
 *       若旧双轨用自己的 uuid Supplier，本测试 uuid 计数断言 fail。</li>
 * </ol>
 */
class LoopDepsSingleTrackIntegrationTest {

    /** 单条 user 消息（loop 驱动所需最小输入）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            "m1", null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("主循环 deps 载体 MainLoopDeps 实现 LoopDeps 单接口（无旧 deps 双轨）")
    void mainLoopDeps_implementsLoopDeps_singleTrack() {
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            mock(ToolRegistry.class), mock(LlmProviderFactory.class), null, null, null);

        LlmAgentLoop.MainLoopDeps deps =
            new LlmAgentLoop.MainLoopDeps(ctx, () -> "model-x");

        assertThat(deps)
            .as("主循环 deps 必须实现 LoopDeps 单接口（D-09 删除后唯一 deps 对齐面）")
            .isInstanceOf(LoopDeps.class);
        assertThat(deps.isMainLoop()).as("MainLoopDeps 必须 isMainLoop()=true").isTrue();
        assertThat(deps.context()).as("MainLoopDeps 必须暴露 AgentLoopContext").isSameAs(ctx);
        assertThat(deps.resolveModel())
            .as("MainLoopDeps resolveModel() 必须委托 modelResolver")
            .isEqualTo("model-x");
        assertThat(deps.uuid())
            .as("MainLoopDeps uuid() 默认 UUID 生成（对齐 CC deps.uuid）")
            .isNotBlank();
    }

    @Test
    @DisplayName("Subagent/Hook deps 载体同样实现 LoopDeps 单接口（三路收敛单轨）")
    void subagentAndHookDeps_implementLoopDeps_singleTrack() {
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            mock(ToolRegistry.class), mock(LlmProviderFactory.class), null, null, null);

        LoopDeps subagent = new SubagentLoopDeps(ctx);
        LoopDeps hook = new HookLoopDeps(ctx);

        assertThat(subagent)
            .as("SubagentLoopDeps 必须实现 LoopDeps（CC runAgent 复用 query() 的 deps）")
            .isInstanceOf(LoopDeps.class);
        assertThat(hook)
            .as("HookLoopDeps 必须实现 LoopDeps（CC execAgentHook 复用 query() 的 deps）")
            .isInstanceOf(LoopDeps.class);
        assertThat(subagent.isMainLoop()).as("Subagent 非主循环").isFalse();
        assertThat(hook.isMainLoop()).as("Hook 非主循环").isFalse();
    }

    @Test
    @DisplayName("主循环 queryLoop 经 deps.callModel + deps.uuid()（非直调 provider / 非旧 deps 桥接）")
    void queryLoop_routesThroughLoopDeps_callModelAndUuid() {
        // provider mock：直调 stream 直接抛异常 —— 若 loop 直调 provider 则必走错误路径（RED）
        // [IMP-SP-08] loop 经 deps.callModel 覆盖提供响应（本测试不触发真实 provider.stream），
        //   blocks 重载 stub 仅为 RED 守卫（若回归直调 provider 则抛异常 fail）。
        LlmProvider provider = mock(LlmProvider.class);
        Mockito.doThrow(new IllegalStateException("must not call provider.stream directly"))
            .when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            mock(ToolRegistry.class), factory, null, null, null);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(userMessage("question"));

        // 无工具 → buildStreamingExecutor 返回 null，纯文本路径
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        AtomicInteger callModelInvoked = new AtomicInteger(0);
        AtomicInteger uuidInvoked = new AtomicInteger(0);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public ModelResponse callModel(ModelRequest request) {
                callModelInvoked.incrementAndGet();
                // 模拟 provider 响应：累积文本 + 完整 assistant message + 正常完成
                request.onChunk().accept("done");
                request.onAssistantMessage().accept(new AssistantMessage("done", "stop", List.of()));
                request.onComplete().run();
                return ModelResponse.SUBMITTED;
            }
            @Override public String uuid() {
                uuidInvoked.incrementAndGet();
                return "fixed-chain-" + uuidInvoked.get();
            }
        };

        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(callModelInvoked.get())
            .as("主循环 LLM 调用必须经 deps.callModel（LoopDeps 单轨，非直调 provider.stream）")
            .isGreaterThan(0);
        assertThat(uuidInvoked.get())
            .as("主循环 chainId 必须经 deps.uuid() 生成（对齐 CC query.ts:353 deps.uuid）")
            .isGreaterThan(0);
        assertThat(result.finalState().exitReason())
            .as("经 deps.callModel 提交 + onComplete 触发 → 正常结束（非 STREAM_TIMEOUT / 错误路径）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
        assertThat(state.lastError()).isNull();
    }
}
