package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H7-arch Phase 3] ExecAgentHook 接入 queryLoop 集成测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/hooks/execAgentHook.ts (340 行).
 *
 * <p>WHY: CC agent hook 复用主 query()（CC :167），Java Phase 3 改调 queryLoop。
 * 本测试走真实 queryLoop 路径（[H7-arch Phase 5-2 P3-③] AgentLoopContextFactory.shared() + base TUC，
 * 替代 fresh carrier + ScriptableProvider），验证 9 条意图：
 * 多轮 / 50 熔断 / 强制 structured output / success / blocking /
 * cancelled(timeout) / non_blocking_error / unique agentId / 默认 model.
 *
 * <p>测试夹具：匿名 LlmProviderFactory 返回 ScriptableProvider（按脚本返回 AssistantMessage）；
 * AgentLoopContextFactory 注入该 factory（hook agent loop 经 ctx.llmProviderFactory 调 provider.stream）；
 * ExecAgentHook.exec 内部构造 base TUC（availableTools=effectiveTools + isNonInteractiveSession=true）。
 *
 * <p><b>测试意图 vs 旧 stream 版差异</b>（CLAUDE.md 规则 9 · 验证 WHY）：
 * <ul>
 *   <li>多轮 callCount：queryLoop 在 StructuredOutput 后需多一轮 stop 响应才退出循环
 *       （CC 靠 attachment 检测立即 abort，Java loop 靠 needsFollowUp=false 退出）</li>
 *   <li>timeout：Java loop 的 stream await 不响应 state.cancel 中断，超时是"软"的
 *       （当前 turn 完成后 break），故不断言严格 elapsed < timeout</li>
 *   <li>non_blocking_error：Java loop 在 provider 报错时设 exitReason=STREAM_ERROR 返回
 *       （不抛异常），ExecAgentHook 按 exitReason 映射 non_blocking_error（对齐 CC :316-338）</li>
 * </ul>
 */
@DisplayName("[H7-arch Phase 3] ExecAgentHook 接入 queryLoop 对齐 CC execAgentHook.ts")
class R33H7_ExecAgentHookTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String SO = "StructuredOutput";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");

    // ════════════════════════════════════════════════════════════════════════
    // mock 工具
    // ════════════════════════════════════════════════════════════════════════

    /** StructuredOutput tool_call, input={ok, reason?} · 对齐 CC hookResponseSchema. */
    private ToolUseBlock structuredCall(boolean ok, String reason) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("ok", ok);
        if (reason != null) input.put("reason", reason);
        return new ToolUseBlock("toolu-struct-" + System.nanoTime(), SO, input);
    }

    /** 非 structured tool_call (如 Read)，触发多轮回填 · 对齐 CC agent hook 子循环调工具场景. */
    private ToolUseBlock readCall() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "transcript.txt");
        return new ToolUseBlock("toolu-read-" + System.nanoTime(), "Read", input);
    }

    /** 纯文本 stop 响应（无 tool_call）· 让 queryLoop needsFollowUp=false 退出循环. */
    private static AssistantMessage stopText(String text) {
        return new AssistantMessage(text, "stop", List.of());
    }

    /**
     * 按脚本返回 AssistantMessage 的 provider · 超出脚本长度时重复最后一条。
     * 捕获 tools/systemPrompt/model 供断言。
     */
    static class ScriptableProvider implements LlmProvider {
        final List<AssistantMessage> responses;
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicReference<ArrayNode> capturedTools = new AtomicReference<>();
        final AtomicReference<String> capturedModel = new AtomicReference<>();
        final AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();
        final long delayMs;

        ScriptableProvider(List<AssistantMessage> responses) {
            this(responses, 0L);
        }

        ScriptableProvider(List<AssistantMessage> responses, long delayMs) {
            this.responses = responses;
            this.delayMs = delayMs;
        }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride,
                           com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
            int idx = callCount.getAndIncrement();
            capturedModel.set(modelName);
            // [⊕C-1] String systemPrompt 兼容契约已删除：捕获 blocks join 文本（断言 SO 文案可达性保持）
            capturedSystemPrompt.set(systemPromptBlocks == null ? null : systemPromptBlocks.stream()
                .map(com.nexusai.application.agent.prompt.SystemPromptBlock::text)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n\n")));
            capturedTools.set(tools);
            AssistantMessage am = responses.get(Math.min(idx, responses.size() - 1));
            onAssistantMessage.accept(am);
            onComplete.run();
        }
    }

    /** 抛异常 provider (non_blocking_error 测试) · 对齐 CC :316-338 外层 catch. */
    private static LlmProvider explodingProvider() {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }
            @Override
            public void stream(ProviderConfig config, String modelName,
                               List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                               List<ChatMessageDto> history, ArrayNode tools,
                               Integer maxOutputTokensOverride,
                               com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                               String effortValue, String querySource,
                               java.util.function.Consumer<String> onChunk,
                               java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                               java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                               java.util.function.Consumer<String> onReasoningChunk,
                               Runnable onStreamingFallback,
                               com.nexusai.application.agent.tool.AbortController abortController,
                               java.util.function.Consumer<Throwable> onError,
                               Runnable onComplete) {
                onError.accept(new RuntimeException("provider exploded"));
            }
        };
    }

    /**
     * 构造 AgentLoopContextFactory · 注入 scriptableProvider 的 LlmProviderFactory（hook agent
     * loop 经 factory.shared() ctx 读 llmProviderFactory 调 provider.stream）。P3-③ 后无 carrier。
     */
    private static AgentLoopContextFactory newFactory(LlmProviderFactory factory) {
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        return contextFactory;
    }

    /**
     * 构造 ExecAgentHook · [H7-arch Phase 5-2 P3-③] contextFactory（替代 fresh carrier）。
     * parentToolRegistry 空（hook agent 仅 SyntheticOutputTool，由 buildEffectiveRegistry 注入）。
     */
    private ExecAgentHook hookWith(ScriptableProvider provider) {
        // [MAINCHAIN-01] LlmAgentLoop 主链现调 2 参 getProvider(config, providerType)，须覆写 2 参版本
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return provider; }
        };
        // [H13] 构造签名新增 telemetry 参数（此处 null = 不发射 analytics 事件）
        // [EX-C ?-EX-06] 构造签名新增 providerService/llmProviderFactory（此处 null = 未接线 → 注入兜底）
        return new ExecAgentHook(objectMapper, newFactory(factory), new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
    }

    /** Exploding factory (non_blocking_error 测试). */
    private ExecAgentHook hookWithExploding(LlmProvider exploding) {
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return exploding; }
        };
        return new ExecAgentHook(objectMapper, newFactory(factory), new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
    }

    private static String HOOK_NAME() { return "test-agent-hook"; }

    private HookResult exec(ExecAgentHook h, String prompt, Integer timeout, String model, String jsonInput) {
        AgentHook hook = new AgentHook(prompt, null, timeout, model, null, null);
        return h.exec(hook, HOOK_NAME(), hookEvent, jsonInput, null, null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 9 测试
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多轮 LLM 子循环：前 N 轮非 structured tool_call -> 回填继续 -> structured ok=true -> success (CC :167-227)")
    void execAgentHook_runsMultiTurnLlmSubloop() {
        // WHY 多轮: CC query() 让 agent 多次调工具验证条件后才调 SyntheticOutputTool.
        // 前 2 轮 Read tool_call (effectiveRegistry 无 Read -> error result 回填继续)，第 3 轮 structured ok=true，
        // 第 4 轮 stop 文本退出循环。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("let me check", "tool_calls", List.of(readCall())),
            new AssistantMessage("checking more", "tool_calls", List.of(readCall())),
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify $ARGUMENTS", null, null, "{\"tool\":\"bash\"}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        // 验证多轮: stream 被调 >= 3 次 (2 次 Read 回填 + 1 次 structured + 1 次 stop 退出)
        assertThat(p.callCount.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("50 轮熔断：始终非 structured tool_call -> turnCount>=50 -> cancelled (CC :119,201-208)")
    void execAgentHook_abortsAt50Turns() {
        // WHY 熔断: CC MAX_AGENT_TURNS=50 防止 agent hook 无限循环. 始终 Read tool_call -> 50 轮后 cancelled.
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("checking", "tool_calls", List.of(readCall()))
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify $ARGUMENTS", null, null, "{}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.CANCELLED);
        // 验证熔断: stream 恰好调 50 次 (MAX_AGENT_TURNS)，不是无限
        assertThat(p.callCount.get()).isEqualTo(50);
    }

    @Test
    @DisplayName("强制 structured JSON 输出：tools 含 StructuredOutput + systemPrompt 含强制文案 (CC :89,107-116)")
    void execAgentHook_forcesStructuredJsonOutput() {
        // WHY 强制: CC createStructuredOutputTool() 注入 SyntheticOutputTool + systemPrompt 强制 LLM 调用.
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        exec(h, "verify $ARGUMENTS", null, null, "{}");

        // tools ArrayNode 含 StructuredOutput 工具定义（skipSpecialToolsFilter 让其暴露）
        ArrayNode tools = p.capturedTools.get();
        assertThat(tools).isNotNull();
        assertThat(tools.toString()).contains(SO);
        // systemPrompt 含强制文案 (对齐 CC :113 "return your result using the StructuredOutput tool")
        assertThat(p.capturedSystemPrompt.get()).contains(SO);
    }

    @Test
    @DisplayName("structured {ok:true} -> outcome=success (CC :293-303)")
    void execAgentHook_outcomeSuccessOnOk() {
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify $ARGUMENTS", null, null, "{}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("structured {ok:false,reason} -> outcome=blocking + blockingError (CC :271-283；[CCJ-EXEC-14] 无 preventContinuation/stopReason)")
    void execAgentHook_outcomeBlockingOnDeny() {
        // WHY: CC execAgentHook.ts:271-283 blocking 返回仅 {hook, outcome, blockingError}
        //   （无 preventContinuation、无 stopReason 键）。旧 Java 多带
        //   preventContinuation=true + stopReason=reason（CCJ-EXEC-14）——分发层会误触发
        //   "禁止继续" 路径（stopHooks.ts:269-293），而 CC 仅凭 outcome=blocking 的
        //   blockingError 通道重入（stopHooks.ts:330-331）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(false, "tests not run"))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify $ARGUMENTS", null, null, "{}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        // [CCJ-EXEC-14] agent blocking 字段面逐字对齐 CC：preventContinuation=false、stopReason=null
        assertThat(r.preventContinuation()).isFalse();
        assertThat(r.stopReason()).isNull();
        assertThat(r.blockingError()).isNotNull();
        // CC :279 blockingError.command = hook.prompt
        assertThat(r.blockingError().command()).isEqualTo("verify $ARGUMENTS");
    }

    @Test
    @DisplayName("timeout/abort -> outcome=cancelled (CC :308-313)")
    void execAgentHook_outcomeCancelledOnAbort() {
        // WHY timeout: CC createCombinedAbortSignal 合并父 signal + timeout，超时 -> cancelled.
        // Java loop stream await 不响应 state.cancel 中断 -> 软超时（当前 turn 完成后 break），仍 cancelled。
        // provider 每轮延迟 300ms，hook timeout=1s -> 1s 后 state.cancel，当前 turn 完成后 cancelled。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("checking", "tool_calls", List.of(readCall()))
        ), 300L);
        ExecAgentHook h = hookWith(p);

        long start = System.currentTimeMillis();
        HookResult r = exec(h, "verify $ARGUMENTS", 1, null, "{}");  // timeout=1s
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.outcome()).isEqualTo(HookOutcome.CANCELLED);
        // 软超时：elapsed 取决于 stream 完成时间（约 N*300ms 直到 1s timeout 触发后当前 turn 完成）
        assertThat(elapsed).isLessThan(3000L);
    }

    @Test
    @DisplayName("provider 报错 -> outcome=non_blocking_error (CC :316-338)")
    void execAgentHook_outcomeNonBlockingErrorOnException() {
        // WHY: CC 外层 catch error -> non_blocking_error。Java loop 设 exitReason=STREAM_ERROR 返回，
        // ExecAgentHook 按 isErrorExit(STREAM_ERROR) 映射 non_blocking_error。
        ExecAgentHook h = hookWithExploding(explodingProvider());

        HookResult r = exec(h, "verify $ARGUMENTS", null, null, "{}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
    }

    @Test
    @DisplayName("unique agentId 命名空间：hook-agent-${UUID} 格式 + 每次唯一 (CC :122)")
    void execAgentHook_usesUniqueAgentIdNamespace() {
        String id1 = ExecAgentHook.generateHookAgentId();
        String id2 = ExecAgentHook.generateHookAgentId();

        assertThat(id1).startsWith("hook-agent-");
        assertThat(id1).isNotEqualTo(id2);
        // UUID 部分长度 36 (8-4-4-4-12)
        String uuid1 = id1.substring("hook-agent-".length());
        assertThat(uuid1).hasSize(36);
    }

    @Test
    @DisplayName("默认 model 路由：hook.model=null -> getSmallFastModel; 设值 -> 用设值 (CC :118)")
    void execAgentHook_defaultModelGetSmallFast() {
        // WHY: CC hook.model ?? getSmallFastModel() - hook 未指定 model 时走默认 fast model.
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);
        exec(h, "verify", null, null, "{}");
        assertThat(p.capturedModel.get()).isEqualTo(DEFAULT_FAST_MODEL);

        // hook.model 设值 -> 用设值
        ScriptableProvider p2 = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h2 = hookWith(p2);
        exec(h2, "verify", null, "custom-model-x", "{}");
        assertThat(p2.capturedModel.get()).isEqualTo("custom-model-x");
    }
}
