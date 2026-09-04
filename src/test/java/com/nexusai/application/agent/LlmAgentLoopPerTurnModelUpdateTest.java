package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [RES-L1] 每轮 setCurrentModel 对齐 CC options.mainLoopModel 每轮读最新值。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * CC query.ts:572 每次进入 query() 从 {@code toolUseContext.options.mainLoopModel} 读最新模型值，
 * 而非固定缓存 spawn 时初始值。Java 必须每轮 turn 在 LLM 调用前将解析后的 effectiveModel
 * 写回 {@code state.setCurrentModel()}，使 resume 时读到当前会话模型而非仅 spawn 初始模型。
 *
 * <p><b>RED teeth</b>: 去掉 loop 内 {@code state.setCurrentModel(effectiveModel)} →
 * state.currentModel() 保持 doRun() 初始值 → 本测试 fail。
 */
class LlmAgentLoopPerTurnModelUpdateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("[RES-L1] queryLoop 每轮解析 effectiveModel 后写入 state.currentModel()（CC query.ts:572 等价）")
    void queryLoop_updatesCurrentModelEachTurn() {
        // ── 1. provider：单轮成功返回 stop ──
        List<String> calledModels = new ArrayList<>();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("answer");
            onMsg.accept(new AssistantMessage("answer", "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. state：初始 setCurrentModel("spawn-model")，模拟 doRun() 入口写入 ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.setCurrentModel("spawn-model");
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        // ── 3. deps：resolveModel() 返回不同模型（模拟 ConfigTool SET / skill override 等）──
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "resolved-model"; }
        };

        // ── 4. 执行 queryLoop ──
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "spawn-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 5. 断言 ──
        assertThat(calledModels)
            .as("provider 必须被调用且使用 resolved-model（deps.resolveModel() 返回的值）")
            .containsExactly("resolved-model");

        // WHY (RES-L1): state.currentModel() 必须反映最近一次 effectiveModel，
        // 而非 spawn 初始值。resume 时 ResumeService 读取本字段获取当前会话模型。
        assertThat(state.currentModel())
            .as("state.currentModel() 必须在每轮 LLM 调用前更新为 effectiveModel（CC query.ts:572 等价）"
                + " · 不能保持 doRun() 初始值 'spawn-model'")
            .isEqualTo("resolved-model");
    }

    @Test
    @DisplayName("[RES-L1] 多轮时 state.currentModel() 跟踪最新 effectiveModel（模拟中途模型切换）")
    void queryLoop_currentModelTracksLatestResolvedModel() {
        // ── provider：2 轮调用，第 1 轮返回 tool_use（触发第 2 轮），第 2 轮 stop ──
        List<String> calledModels = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // 第 1 轮：返回 tool_use，触发工具执行后进入第 2 轮
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash",
                        JSON.createObjectNode().put("command", "ls")))));
            } else {
                // 第 2 轮：stop
                onChunk.accept("done");
                onMsg.accept(new AssistantMessage("done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── state：初始 spawn-model ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.setCurrentModel("spawn-model");
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        // ── deps：resolveModel() 返回 "round-model"（两轮都是同一个新模型）──
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "round-model"; }
        };

        // ── 执行 queryLoop ──
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "spawn-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 断言 ──
        assertThat(calledModels)
            .as("provider 两轮都必须使用 resolved-model")
            .containsExactly("round-model", "round-model");

        // WHY (RES-L1): 多轮后 state.currentModel() 必须跟踪最近一次 effectiveModel。
        // 这是 resume 时能读到正确模型的保障（CC query.ts:572 每轮重新读取）。
        assertThat(state.currentModel())
            .as("多轮后 state.currentModel() 必须等于最近一次 effectiveModel，非 spawn 初始值")
            .isEqualTo("round-model");
    }

    /**
     * [pdf-vision-align 对抗核验 #1] 子代理新 AgentState（currentModel()=null）首 turn 预设。
     *
     * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：per-turn TUC（loop 内
     * {@code AgentLoopContext.toolExecContext}）在 effectiveModel 解析（同迭代稍后）<b>之前</b>构建，
     * TUC.effectiveModelName 取自 {@code state.currentModel()} 上一轮写值。主循环经 doRun 入口预设
     * （:2234），但子代理/独立 loop 直接调 queryLoop（SubagentExecutor:4231），全新 AgentState
     * currentModel()=null → 首 turn 工具 ctx.effectiveModelName()=null → vision 子代理首 turn
     * Read pdf 被 {@code PdfSupport.isPDFSupported} 3 参（null model → 保守 false）误判文本模型。
     * queryLoop 入口 null-guard 预设 params.modelName() 修复之；本测试用「未 preset 的 fresh state +
     * 捕获 ctx 的工具」验证首 turn 工具看到非 null 模型名。
     *
     * <p><b>RED tooth</b>: 删 queryLoop 入口预设 → 首 turn 工具 ctx.effectiveModelName()=null → fail。
     */
    @Test
    @DisplayName("[对抗核验 #1] 首 turn currentModel 初始 null（子代理新 AgentState）→ queryLoop 入口预设 → 首 turn 工具 ctx.effectiveModelName 非 null")
    void queryLoop_firstTurn_presetsCurrentModelForFreshSubagentState() {
        // ── 1. provider：turn 1 返回 Bash tool_use（触发工具执行），turn 2 stop ──
        List<String> calledModels = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            String model = inv.getArgument(1);
            calledModels.add(model);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // turn 1：返回 Bash tool_use → executor 真实执行工具 → 工具捕获首 turn ctx
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash",
                        JSON.createObjectNode().put("command", "ls")))));
            } else {
                onChunk.accept("done");
                onMsg.accept(new AssistantMessage("done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. 子代理新 AgentState：不 setCurrentModel（模拟 SubagentExecutor:3892 全新 AgentState，currentModel()=null）──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        // ── 3. 捕获首 turn 工具 ctx.effectiveModelName ──
        java.util.List<String> capturedModels = java.util.Collections.synchronizedList(new ArrayList<>());
        com.nexusai.application.agent.tool.Tool capturingTool = new com.nexusai.application.agent.tool.Tool() {
            final com.fasterxml.jackson.databind.ObjectMapper j =
                new com.fasterxml.jackson.databind.ObjectMapper();
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "capture"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return j.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call,
                    com.nexusai.application.agent.tool.ToolUseContext ctx) {
                capturedModels.add(ctx != null ? ctx.effectiveModelName() : "ctx-null");
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return false; }
            // resolveModel() 默认 null → resolveTurnEffectiveModel 回落 recoveryState=params.modelName()
        };

        // ── 4. 执行 queryLoop（modelName=vision-model，模拟 vision 子代理；state 未 preset currentModel）──
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(capturingTool)),
                QuerySource.USER, "vision-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 5. 断言 ──
        assertThat(capturedModels)
            .as("turn 1 的 Bash tool_use 必须真实执行（首 turn 工具被调用）")
            .isNotEmpty();
        assertThat(capturedModels.get(0))
            .as("修复 #1：子代理新 AgentState（currentModel 初始 null）首 turn 工具 ctx.effectiveModelName "
                + "必须非 null（= queryLoop 入口预设的 params.modelName()）——否则 vision 子代理首 turn "
                + "Read pdf 被 isPDFSupported 3 参（null → 保守 false）误判文本模型")
            .isEqualTo("vision-model");
    }
}
