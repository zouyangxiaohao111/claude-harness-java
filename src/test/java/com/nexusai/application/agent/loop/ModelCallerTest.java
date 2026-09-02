package com.nexusai.application.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * [H7-arch Phase 5-2 P3-④] LoopDeps.callModel 封装测试 · 对齐 CC {@code deps.callModel}
 * (deps.ts:21-31, queryModelWithStreaming 封装)。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>loop 的 LLM 调用必须经 deps.callModel（而非直调 provider.stream）</b> — 这是 P3-④ 的
 *       核心：loop 不再直接持有 provider / 直调 stream，统一收敛到 deps 窄 IO。若回归为直调，
 *       本测试因 provider mock 抛异常而 fail。</li>
 *   <li><b>ModelCaller 必须逐字段透传 request → provider.stream</b> — 任何字段错位（尤其
 *       onStreamingFallback 回调位置）都会改变 streaming / fallback / 错误分类行为。</li>
 *   <li><b>匿名 deps（测试）仅实现 context() 即获得真实 LLM 调用能力</b> — 接口默认实现委托
 *       ModelCaller，这是现有 StreamingFallbackTombstoneTest / ModelFallbackTest 可无 override
 *       运行的前置契约。</li>
 * </ol>
 */
class ModelCallerTest {

    @Test
    @DisplayName("ModelCaller.call 逐字段透传 request → provider.stream（含 onStreamingFallback）")
    void modelCaller_delegatesAllFieldsToProviderStream() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        List<Object> captured = new ArrayList<>();
        // [⊕C-1] blocks 唯一发送契约：17 参 blocks stream（config/modelName/blocks/querySource/
        // messages/tools + override/taskBudget/effort + onChunk/onAssistantMessage/onToolCallComplete/
        // onReasoningChunk/onStreamingFallback/abortController/onError/onComplete）。
        Mockito.doAnswer(inv -> {
            for (int i = 0; i < 17; i++) {
                captured.add(inv.getArgument(i));
            }
            return null;
        }).when(provider).stream(any(), anyString(), nullable(List.class), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        ProviderConfig config = new ProviderConfig("http://base", "k");
        ArrayNode tools = new ObjectMapper().createArrayNode();
        List<ChatMessageDto> messages = List.of();
        Consumer<String> onChunk = c -> {};
        Consumer<AssistantMessage> onMsg = m -> {};
        Consumer<ToolUseBlock> onTool = t -> {};
        Consumer<String> onReasoning = r -> {};
        Runnable onFallback = () -> {};
        Consumer<Throwable> onError = e -> {};
        Runnable onComplete = () -> {};

        ModelRequest request = new ModelRequest(config, "m1", null, null, messages, tools,
            null, null, null, null,
            onChunk, onMsg, onTool, onReasoning, onFallback, onError, onComplete, null);

        ModelResponse resp = ModelCaller.call(ctx, request);

        assertThat(resp).isEqualTo(ModelResponse.SUBMITTED);
        assertThat(captured).hasSize(17);
        assertThat(captured.get(0)).isSameAs(config);
        assertThat(captured.get(1)).isEqualTo("m1");
        assertThat(captured.get(2)).as("blocks null/空 = 无 system 字段（⊕C-1 后无 String 兼容路径）").isNull();
        assertThat(captured.get(3)).isSameAs(messages);
        assertThat(captured.get(4)).isSameAs(tools);
        assertThat(captured.get(5)).isNull();
        assertThat(captured.get(6)).isNull();
        assertThat(captured.get(7)).isNull();
        assertThat(captured.get(8)).isNull();
        assertThat(captured.get(9)).isSameAs(onChunk);
        assertThat(captured.get(10)).isSameAs(onMsg);
        assertThat(captured.get(11)).isSameAs(onTool);
        assertThat(captured.get(12)).isSameAs(onReasoning);
        assertThat(captured.get(13)).isSameAs(onFallback);
        assertThat(captured.get(14)).isNull();
        assertThat(captured.get(15)).isSameAs(onError);
        assertThat(captured.get(16)).isSameAs(onComplete);
    }

    @Test
    @DisplayName("[IMP-16 REWORK] ModelCaller 把 taskBudget 透传 provider.stream（blocks 17-arg stream，验收 #1）")
    void modelCaller_passesTaskBudgetToProviderStream() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        com.nexusai.infra.llm.TaskBudgetParam[] captured = { null };
        // [⊕C-1] blocks 唯一发送契约 17 参：config/modelName/blocks/messages/tools/override/
        // taskBudget/effort/querySource + onChunk/onMsg/onTool/onReasoning/onFallback/abort/onError/onComplete
        // → taskBudget 在 arg(6)
        Mockito.doAnswer(inv -> {
            captured[0] = inv.getArgument(6);
            return null;
        }).when(provider).stream(any(), anyString(), nullable(List.class), anyList(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        ProviderConfig config = new ProviderConfig("http://base", "k");
        ModelRequest request = new ModelRequest(config, "m1", null, null, List.of(), null,
            null, new com.nexusai.infra.llm.TaskBudgetParam(200_000, 165_000), null, null,
            c -> {}, m -> {}, t -> {}, r -> {}, () -> {}, e -> {}, () -> {}, null);

        ModelResponse resp = ModelCaller.call(ctx, request);

        assertThat(resp).isEqualTo(ModelResponse.SUBMITTED);
        assertThat(captured[0]).as("taskBudget 必须到达 provider.stream").isNotNull();
        assertThat(captured[0].total()).isEqualTo(200_000);
        assertThat(captured[0].remaining()).isEqualTo(165_000);
    }

    @Test
    @DisplayName("LoopDeps 默认 callModel 经 context() 的 factory → provider.stream（匿名 deps 无需 override）")
    void loopDepsDefault_callModel_routesThroughContextFactory() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        boolean[] streamCalled = {false};
        Mockito.doAnswer(inv -> {
            streamCalled[0] = true;
            return null;
        }).when(provider).stream(any(), anyString(), nullable(List.class), anyList(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
        };
        ModelRequest request = new ModelRequest(ProviderConfig.empty(), "m1", null, null, List.of(),
            null, null, null, null, null,
            c -> {}, m -> {}, t -> {}, r -> {}, () -> {}, e -> {}, () -> {}, null);

        deps.callModel(request);

        assertThat(streamCalled[0])
            .as("匿名 deps 仅实现 context() 即获得真实 LLM 调用（默认 callModel → ModelCaller.call）")
            .isTrue();
        Mockito.verify(factory).getProvider(any(), any());
    }

    @Test
    @DisplayName("loop 的 LLM 调用经 deps.callModel（非直调 provider.stream）")
    void loop_routesThroughDepsCallModel_notDirectProvider() {
        // provider mock：stream 直接调用会抛异常 —— 若 loop 直调 provider 则必走错误路径
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doThrow(new IllegalStateException("must not call provider.stream directly"))
            .when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        // 无工具 → buildStreamingExecutor 返回 null，纯文本路径
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        boolean[] callModelInvoked = {false};
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public ModelResponse callModel(ModelRequest request) {
                callModelInvoked[0] = true;
                // 模拟 provider 响应：累积文本 + 完整 assistant message + 正常完成
                request.onChunk().accept("done");
                request.onAssistantMessage().accept(new AssistantMessage("done", "stop", List.of()));
                request.onComplete().run();
                return ModelResponse.SUBMITTED;
            }
        };

        LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(callModelInvoked[0])
            .as("loop 的 LLM 调用必须经 deps.callModel（而非直调 provider.stream）")
            .isTrue();
        assertThat(state.exitReason())
            .as("经 deps.callModel 提交 + onComplete 触发 → 正常结束（非 STREAM_TIMEOUT / 错误路径）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
        assertThat(state.lastError()).isNull();
    }

    @Test
    @DisplayName("[FIX-STRIP-PREFIX] ModelCaller 把全名 providerName/modelName 剥为裸名再传 provider.stream")
    void modelCaller_stripsProviderPrefixBeforeStream() {
        // 前端传全名 "deepseek/deepseek-v4-flash"（settings 存全名，ModelPickerModal
        // fullName=providerName/modelName）。若不剥 → OpenAiSdkProvider.buildRequestParams
        // .model("deepseek/deepseek-v4-flash") → DeepSeek API 400（"supported model names are
        // deepseek-v4-flash..."）。ModelCaller 必须用 resolver 剥出的裸名发给 SDK。
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        String[] sdkModel = {null};
        Mockito.doAnswer(inv -> {
            sdkModel[0] = inv.getArgument(1);
            return null;
        }).when(provider).stream(any(), anyString(), nullable(List.class), anyList(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // resolver 把全名解析为 DB 裸名
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveSdkModelName("deepseek/deepseek-v4-flash"))
            .thenReturn("deepseek-v4-flash");

        AgentLoopContext ctx = Mockito.mock(AgentLoopContext.class);
        when(ctx.llmProviderFactory()).thenReturn(factory);
        when(ctx.modelConfigResolver()).thenReturn(resolver);

        ModelRequest request = new ModelRequest(new ProviderConfig("http://deepseek", "k"),
            "deepseek/deepseek-v4-flash", null, null, List.of(), null,
            null, null, null, null,
            c -> {}, m -> {}, t -> {}, r -> {}, () -> {}, e -> {}, () -> {}, null);

        ModelCaller.call(ctx, request);

        assertThat(sdkModel[0])
            .as("SDK model 参数必须是裸名（全名会导致 API 400）")
            .isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("[FIX-STRIP-PREFIX] resolver 未注入 / 未命中 → ModelCaller 回落 request.modelName() 原样透传")
    void modelCaller_fallsBackToRequestModelNameWhenResolverMisses() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        String[] sdkModel = {null};
        Mockito.doAnswer(inv -> {
            sdkModel[0] = inv.getArgument(1);
            return null;
        }).when(provider).stream(any(), anyString(), nullable(List.class), anyList(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // 未注入 resolver（测试/非 Spring 场景）→ ctx.modelConfigResolver() 返回 null → 原样透传
        AgentLoopContext ctx = Mockito.mock(AgentLoopContext.class);
        when(ctx.llmProviderFactory()).thenReturn(factory);
        when(ctx.modelConfigResolver()).thenReturn(null);

        ModelRequest request = new ModelRequest(new ProviderConfig("http://base", "k"),
            "deepseek/deepseek-v4-flash", null, null, List.of(), null,
            null, null, null, null,
            c -> {}, m -> {}, t -> {}, r -> {}, () -> {}, e -> {}, () -> {}, null);

        ModelCaller.call(ctx, request);

        assertThat(sdkModel[0])
            .as("resolver null → 回落原始全名（CC 未知名直接传 API，失败即失败）")
            .isEqualTo("deepseek/deepseek-v4-flash");
    }

    @Test
    @DisplayName("[IMP-SP-08] blocks 非 null/空 → ModelCaller 走 17-arg blocks 重载（splitSysPromptPrefix 产物直达 provider）")
    void modelCaller_blocksDispatch_usesBlocksOverload() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // blocks 重载 = LlmProvider 17-arg default（system 为 List<SystemPromptBlock>）：
        //   config/modelName/blocks/messages/tools/maxOutputTokens/taskBudget/effortValue/querySource
        //   + onChunk/onAssistantMessage/onToolCallComplete/onReasoningChunk/onStreamingFallback/
        //     abortController/onError/onComplete
        List<Object> captured = new ArrayList<>();
        Mockito.doAnswer(inv -> {
            for (int i = 0; i < 17; i++) {
                captured.add(inv.getArgument(i));
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        ProviderConfig config = new ProviderConfig("http://base", "k");
        List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks = List.of(
            new com.nexusai.application.agent.prompt.SystemPromptBlock("static-prefix",
                com.nexusai.application.agent.prompt.CacheScope.GLOBAL),
            new com.nexusai.application.agent.prompt.SystemPromptBlock("dynamic-tail",
                com.nexusai.application.agent.prompt.CacheScope.NULL));
        ModelRequest request = new ModelRequest(config, "m1", blocks, "repl_main_thread",
            List.of(), null, null, null, null, null,
            c -> {}, m -> {}, t -> {}, r -> {}, () -> {}, e -> {}, () -> {}, null);
        ModelResponse resp = ModelCaller.call(ctx, request);

        assertThat(resp).isEqualTo(ModelResponse.SUBMITTED);
        assertThat(captured).hasSize(17);
        assertThat(captured.get(2)).isSameAs(blocks);
        assertThat(captured.get(8)).as("querySource 透传（CC options.querySource）").isEqualTo("repl_main_thread");
        // [⊕C-1] blocks 为唯一发送契约（String systemPrompt 字段已删除）：blocks 优先断言保留
    }
}
