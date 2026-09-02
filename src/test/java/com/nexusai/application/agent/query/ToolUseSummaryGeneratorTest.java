package com.nexusai.application.agent.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Method;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [H7-arch Phase 5 P4 C7] ToolUseSummaryGenerator CC 契约测试。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>gate=on + 工具轮 → 下轮顶部 summary 注入</b> — CC query.ts:1469-1481 工具批后
 *       fire-and-forget 生产，query.ts:1055-1060 下轮顶部 await + yield。Java 端必须把摘要
 *       attachment 注入 transcript。</li>
 *   <li><b>gate=off → 不调</b> — CC {@code config.gates.emitToolUseSummaries} 关闭时
 *       {@code generateToolUseSummary} 不触发，零 Haiku 调用开销。</li>
 *   <li><b>子 agent（agentId != null）不生产</b> — CC {@code !toolUseContext.agentId} 注释明确
 *       "subagents don't surface in mobile UI — skip the Haiku call"。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert loop 内 gate 检查 / 生产调用 / 下轮消费 → 测试 3 fail；
 * revert generator 空工具短路 → 测试 2 fail。
 */
class ToolUseSummaryGeneratorTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";
    private static final ObjectMapper JSON = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Haiku 组件行为
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("组件: provider.chatWithOptions 返回摘要 → attachment(type=tool_use_summary, 含 precedingToolUseIds)")
    void haikuGenerator_producesAttachment() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [W9-01] 5 参 chatWithOptions 取代旧 4 参 chat（options 承载 CC queryHaiku options）
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
            .thenReturn("Used Bash 3 times");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        // [RV14B-WIRE-04] 2 参 getProvider(config, providerType)（原 getProvider(any(), any()) 1 参恒 mock 已移除）
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolve(anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        HaikuToolUseSummaryGenerator gen = new HaikuToolUseSummaryGenerator(factory, resolver);
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        List<ToolUseBlock> tools = List.of(new ToolUseBlock("toolu_1", "Bash", JSON.createObjectNode()));

        AttachmentMessageDto result = gen.generateToolUseSummaryAsync(
            state, tools, List.of(), "check files", true).join();

        assertThat(result)
            .as("Haiku 成功 → 必须返回 tool_use_summary attachment")
            .isNotNull();
        assertThat(result.type()).isEqualTo("tool_use_summary");
        assertThat(result.content()).isEqualTo("Used Bash 3 times");
        assertThat(result.precedingToolUseIds())
            .as("precedingToolUseIds 必须含 tool_use_id（CC query.ts:1437 toolUseIds = toolUseBlocks.map(b=>b.id)）")
            .containsExactly("toolu_1");
    }

    @Test
    @DisplayName("组件: chatWithOptions 承载 CC prompt(10行+5示例) + queryHaiku options")
    void haikuGenerator_carriesCCPromptAndOptions() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
            .thenReturn("Fixed NPE in UserService");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolve(anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        HaikuToolUseSummaryGenerator gen = new HaikuToolUseSummaryGenerator(factory, resolver);
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        List<ToolUseBlock> tools = List.of(new ToolUseBlock("toolu_9", "Bash", JSON.createObjectNode()));

        gen.generateToolUseSummaryAsync(state, tools, List.of(), "debug the crash", true).join();

        ArgumentCaptor<String> sysPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LlmProvider.ChatRequestOptions> optionsCaptor =
            ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(any(), anyString(), sysPromptCaptor.capture(), anyString(), optionsCaptor.capture());

        // [W9-01] CC toolUseSummaryGenerator.ts:15-24 prompt 原文（git-commit-subject 10 行 + 5 示例）
        assertThat(sysPromptCaptor.getValue())
            .as("system prompt 必须含 CC 原文 git-commit-subject 规则 + 5 示例")
            .contains("think git-commit-subject, not sentence")
            .contains("Keep the verb in past tense and the most distinctive noun")
            .contains("Searched in auth/")
            .contains("Fixed NPE in UserService")
            .contains("Created signup endpoint")
            .contains("Read config.json")
            .contains("Ran failing tests");

        // [W9-01] CC toolUseSummaryGenerator.ts:73-80 queryHaiku options 六项
        LlmProvider.ChatRequestOptions opts = optionsCaptor.getValue();
        assertThat(opts.querySource())
            .as("querySource 必须 = 'tool_use_summary_generation'（CC :74）")
            .isEqualTo("tool_use_summary_generation");
        assertThat(opts.enablePromptCaching()).isEqualTo(true);
        assertThat(opts.agents()).isEmpty();
        assertThat(opts.hasAppendSystemPrompt()).isEqualTo(false);
        assertThat(opts.mcpTools()).isEmpty();
        assertThat(opts.isNonInteractiveSession()).isEqualTo(true);
        assertThat(opts.thinkingConfig()).isNotNull();
        assertThat(opts.thinkingConfig().type()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("组件: 空工具 → completedFuture(null)（CC toolUseSummaryGenerator.ts:53-55）")
    void haikuGenerator_emptyTools_returnsNull() {
        HaikuToolUseSummaryGenerator gen = new HaikuToolUseSummaryGenerator(null);
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);

        AttachmentMessageDto result = gen.generateToolUseSummaryAsync(
            state, List.of(), List.of(), null, false).join();

        assertThat(result)
            .as("tools.length==0 → 必须返回 null（CC generateToolUseSummary 早返）")
            .isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. loop wiring: gate=on 工具轮 → 下轮注入
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("loop: gate=on + 工具轮 → 下轮顶部 summary attachment 注入（CC query.ts:1055-1060/1469-1481）")
    void gateOn_toolTurn_injectsSummary() {
        AttachmentMessageDto summaryAttachment = new AttachmentMessageDto(
            null, "attachment", "tool_use_summary", "tools did X", null, null, null);
        ToolUseSummaryGenerator generator = Mockito.mock(ToolUseSummaryGenerator.class);
        when(generator.generateToolUseSummaryAsync(any(), anyList(), anyList(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(summaryAttachment));

        AgentState state = runLoopWithToolTurn(true, generator);

        assertThat(state.attachments().stream().anyMatch(a -> "tool_use_summary".equals(a.type())))
            .as("gate=on + 工具轮 → 下轮顶部必须注入 tool_use_summary attachment（CC query.ts:1055-1060）")
            .isTrue();
    }

    @Test
    @DisplayName("loop: gate=off → generateToolUseSummaryAsync 不调（零 Haiku 开销）")
    void gateOff_generatorNotCalled() {
        ToolUseSummaryGenerator generator = Mockito.mock(ToolUseSummaryGenerator.class);
        AgentState state = runLoopWithToolTurn(false, generator);

        verify(generator, never()).generateToolUseSummaryAsync(any(), anyList(), anyList(), any(), anyBoolean());
        assertThat(state.attachments().stream().anyMatch(a -> "tool_use_summary".equals(a.type())))
            .as("gate=off → 不得注入 tool_use_summary attachment")
            .isFalse();
    }

    @Test
    @DisplayName("loop wiring: 生产调用点 gated on emitToolUseSummaries + agentId==null + 组件空值保护")
    void loopWiringCallsites() throws Exception {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("loop 生产点必须检查 gates().emitToolUseSummaries()（CC query.ts:1412 config.gates.emitToolUseSummaries）")
            .contains("emitToolUseSummaries()");
        assertThat(source)
            .as("loop 生产点必须检查 state.agentId() == null（CC !toolUseContext.agentId）")
            .contains("state.agentId() == null");
        assertThat(source)
            .as("loop 生产点必须检查 toolUseSummaryGenerator() != null（空值保护）")
            .contains("ctx.toolUseSummaryGenerator() != null");
        assertThat(source)
            .as("loop 生产点必须调用 generateToolUseSummaryAsync（CC query.ts:1469）")
            .contains("generateToolUseSummaryAsync(");
        assertThat(source)
            .as("loop 消费点必须 await pendingToolUseSummary（CC query.ts:1056 const summary = await pendingToolUseSummary）")
            .contains("pendingToolUseSummary.get(2, java.util.concurrent.TimeUnit.SECONDS)");
        int produceIdx = source.indexOf("generateToolUseSummaryAsync(");
        int consumeIdx = source.indexOf("pendingToolUseSummary.get(2, java.util.concurrent.TimeUnit.SECONDS)");
        assertThat(produceIdx).isGreaterThan(0);
        assertThat(consumeIdx).isGreaterThan(0);
        assertThat(consumeIdx)
            .as("消费点必须出现在生产点之前（上轮生产 → 下轮顶部消费）")
            .isLessThan(produceIdx);
    }

    @Test
    @DisplayName("SDK 出站: tool_use_summary snake_case wire（CC coreSchemas.ts:1769-1778）")
    void sdkOutbound_serializesSnakeCaseWire() throws Exception {
        // [W9-01 OPD-TS-29] 出站契约 = CC SDKToolUseSummaryMessageSchema:
        //   {type:'tool_use_summary', summary, preceding_tool_use_ids, uuid, session_id}
        // WHY（规则 9）: 若 Java 把 precedingToolUseIds 序列化成 camelCase（precedingToolUseIds），
        //   或丢 uuid/session_id，SDK 侧 zod 校验将拒收——本条测试钉住 snake_case wire。
        SimpMessagingTemplate wsTemplate = Mockito.mock(SimpMessagingTemplate.class);
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        AttachmentMessageDto summary = summaryAttachment("att-2", "Fixed NPE",
            List.of("toolu_1", "toolu_2"));

        // [W9-01] 32 参 compat ctor 注入 wsTemplate（record 组件 final，测试不可反射改写）
        AgentLoopContext ctx = new AgentLoopContext(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, wsTemplate, "/topic/tasks",
            null, null, FeatureFlags.ALL_DISABLED, null, null, null, null,
            null, null, null, null, null, null, null, null);

        Method outboundMethod = LlmAgentLoop.class.getDeclaredMethod(
            "emitToolUseSummarySdkMessage", AgentLoopContext.class, AgentState.class, AttachmentMessageDto.class);
        outboundMethod.setAccessible(true);
        outboundMethod.invoke(null, ctx, state, summary);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(wsTemplate).convertAndSend(Mockito.anyString(), payloadCaptor.capture());
        com.fasterxml.jackson.databind.node.ObjectNode node =
            (com.fasterxml.jackson.databind.node.ObjectNode) payloadCaptor.getValue();
        assertThat(node.get("type").asText()).isEqualTo("tool_use_summary");
        assertThat(node.get("summary").asText()).isEqualTo("Fixed NPE");
        assertThat(node.get("preceding_tool_use_ids"))
            .as("wire 必须 snake_case preceding_tool_use_ids（coreSchemas.ts:1773）")
            .isNotNull();
        assertThat(node.get("preceding_tool_use_ids").size()).isEqualTo(2);
        assertThat(node.get("uuid").asText()).isEqualTo("att-2");
        assertThat(node.get("session_id").asText()).isEqualTo(sessionId.toString());
    }

    @Test
    @DisplayName("SDK 出站: wsTemplate=null → 静默跳过（对齐仅 streaming 消费 SDK 消息）")
    void sdkOutbound_noWsTemplate_skips() throws Exception {
        SimpMessagingTemplate wsTemplate = Mockito.mock(SimpMessagingTemplate.class);
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        AttachmentMessageDto summary = summaryAttachment("att-3", "Searched in auth/", List.of());

        // [IMP2-06] 显式转型消除重载歧义（新增 7 参 (FeatureFlags, EventBridge) vs 既有
        // (ToolExecutionBeans, HookRegistry)；null,null 锁定既有 7 参）。
        AgentLoopContext ctx = com.nexusai.application.agent.TestContexts.agentLoopContext(
            null, null, null, null, null,
            (com.nexusai.application.agent.loop.AgentLoopContext.ToolExecutionBeans) null,
            (com.nexusai.application.agent.permission.hook.HookRegistry) null);
        // ctx.wsTemplate() = null（TestContexts 重载默认）→ 出站方法必须静默 return，不抛异常

        Method outboundMethod = LlmAgentLoop.class.getDeclaredMethod(
            "emitToolUseSummarySdkMessage", AgentLoopContext.class, AgentState.class, AttachmentMessageDto.class);
        outboundMethod.setAccessible(true);
        outboundMethod.invoke(null, ctx, state, summary);

        Mockito.verify(wsTemplate, Mockito.never()).convertAndSend(Mockito.anyString(), Mockito.any(Object.class));
    }

    /** [W9-01] 构造 type='tool_use_summary' attachment（对齐 HaikuToolUseSummaryGenerator 30 参 canonical）。 */
    private static AttachmentMessageDto summaryAttachment(String id, String content,
                                                          List<String> precedingToolUseIds) {
        return new AttachmentMessageDto(
            id, "attachment", "tool_use_summary", content, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, 0, false, null, null, null, false, false, null,
            null, null, null, precedingToolUseIds);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 驱动 loop 跑一轮工具轮 + 一轮纯文本轮。
     *
     * <p>provider mock 第 1 次调返回 tool_calls assistant msg（触发工具轮 + C7 生产），
     * 第 2 次调返回纯文本（loop 正常退出）。handleToolCallsTurn 用 mock 返回 "continue"，
     * 不真正执行工具。
     */
    private static AgentState runLoopWithToolTurn(boolean gateOn, ToolUseSummaryGenerator generator) {
        // [H7-arch Phase 5-2 P3-⑤] 重方法已 static 化（真实 handleToolCallsTurn + 真实 executor，
        // 由 per-turn TUC availableTools 的 dummy "Bash" 驱动；Bash 返回固定 success result → "continue"）：
        //   getModelForCall → deps.resolveModel() null → 回落 recoveryState=params.modelName()="test-model"；
        //   computeBudgetFromGates → qc 非 ant + 无 TokenBudgetBeans → FALLBACK=200_000；
        //   estimateMessagesTokens → 无 TokenEstimator → chars/4（小消息 ≪ blockingLimit，不误触发）。

        // 两次 stream：1) tool_calls 2) 纯文本
        // 注意: loop 调 12 参 stream（含 onStreamingFallback），必须 stub 12 参——
        // Mockito mock 不执行 default 方法体，stub 11 参不会被 12 参委托到。
        AtomicInteger callCount = new AtomicInteger();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：onChunk@9/onMsg@10/onErr@15/onComplete@16
        Mockito.doAnswer(inv -> {
            int call = callCount.getAndIncrement();
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (call == 0) {
                onChunk.accept("Let me check");
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("Let me check", "tool_calls",
                    List.of(new ToolUseBlock("toolu_c7_1", "Bash", input))));
            } else {
                onChunk.accept("Final answer");
                onMsg.accept(new AssistantMessage("Final answer", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "check files", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        QueryConfig qc = QueryConfig.buildQueryConfig(
            "s", () -> true, () -> gateOn, () -> false, () -> true);

        AgentLoopContext ctx = com.nexusai.application.agent.TestContexts.agentLoopContext(
            null, factory, qc, generator, null);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());
        return state;
    }
}
