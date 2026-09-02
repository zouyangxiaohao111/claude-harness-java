package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [G 复刻任务] YoloClassifier telemetry 4 项 (P1-6 + P2-8 + P2-9 + P2-2)
 * · 对齐 CC {@code Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:810-940}
 * + {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:416-422}.
 *
 * <p><b>WHY (意图验证, 不只验证行为):</b>
 * G 是 telemetry 字段携带 — 验证 {@link YoloClassifierResult} 真实承担 CC 真源对象字段
 * (durationMs/promptLengths/thinking/stage1MsgId/stage2MsgId), 不仅是 5 个空字段.
 *
 * <p>[S06 重构] 契约变更（OPD-WF6-01/⊕-01/⊕-02/⊕-03）：fake LLM 返回 CC 2-stage XML
 * （{@code <block>yes/no</block>}），stage2 触发 = stage1 block（非 confidence&gt;0.8）；
 * 结果断言布尔 {@code shouldBlock}。thinking 经 parseXmlThinking（⊕-06 仅 XML）。
 *
 * <p><b>测试清单:</b>
 * <ol>
 *   <li>{@link #yoloResult_carriesDurationMs} — G P1-6 durationMs 字段携带真实 LLM 时长</li>
 *   <li>{@link #yoloResult_carriesPromptLengths} — G P2-8 PromptLengths 子 record + 字段</li>
 *   <li>{@link #promptLengths_rejectsNegative} — PromptLengths 不变量保护</li>
 *   <li>{@link #yoloResult_carriesThinking} — G P2-9 Stage 2 thinking 字段</li>
 *   <li>{@link #yoloResult_carriesStage1MsgId} — G P2-2 stage1MsgId 字段</li>
 *   <li>{@link #yoloResult_carriesStage2MsgId} — G P2-2 stage2MsgId 字段</li>
 *   <li>{@link #classifySync_carriesStageMsgIdsAndThinking} — 集成: mock LlmProvider
 *       走真实 classifyActionCore, stage1 block → stage2, msgId + thinking 从 chatWithRaw 提取
 *       并保留到最终结果（M3.2 chatWithRaw 已落地）</li>
 *   <li>{@link #classifySync_promptLengthsBuckets} — 集成: 三段分桶对齐 CC
 *       yoloClassifier.ts:1038-1051</li>
 *   <li>{@link #streamingExecutor_emitsCancelledOnAbortedEntry} — 集成: 真实 abort 入口
 *       触发 tengu_tool_use_cancelled 双发含 CC toolExecution.ts:419 isMcp 字段</li>
 * </ol>
 *
 * @see YoloClassifierResult
 * @see PromptLengths
 */
class YoloClassifierTelemetryTest {

    // ============== G P1-6 durationMs ==============

    @Test
    @DisplayName("G P1-6: YoloClassifierResult 携带 durationMs (CC yoloClassifier.ts:810,850,888)")
    void yoloResult_carriesDurationMs() {
        YoloClassifierResult r = result(false, "allow reason", "claude-sonnet-4-5",
            1234L, new PromptLengths(500L, 1500L, 200L), null,
            "msg_stage1_aaa", "msg_stage2_bbb", null, null);
        assertThat(r.durationMs())
            .as("CC yoloClassifier.ts:810 stage1DurationMs 必须被携带")
            .isEqualTo(1234L);
    }

    // ============== G P2-8 PromptLengths ==============

    @Test
    @DisplayName("G P2-8: YoloClassifierResult 携带 PromptLengths (CC yoloClassifier.ts:719-723,1062-1065)")
    void yoloResult_carriesPromptLengths() {
        PromptLengths lengths = new PromptLengths(1024L, 4096L, 256L);
        YoloClassifierResult r = result(true, "deny reason", "claude-thinking",
            2500L, lengths, "deep thinking content", null, null, null, null);
        assertThat(r.promptLengths())
            .as("CC yoloClassifier.ts:719 promptLengths 子对象必须被携带")
            .isNotNull();
        assertThat(r.promptLengths().systemPromptLength())
            .as("CC yoloClassifier.ts:1063 systemPrompt: systemPrompt.length")
            .isEqualTo(1024L);
        assertThat(r.promptLengths().toolCallsLength())
            .as("CC yoloClassifier.ts:1064 toolCalls: toolCallsLength")
            .isEqualTo(4096L);
    }

    @Test
    @DisplayName("G P2-8: PromptLengths 紧凑构造器拒绝负数")
    void promptLengths_rejectsNegative() {
        assertThatThrownBy(() -> new PromptLengths(-1L, 100L, 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("systemPromptLength is negative");
        assertThatThrownBy(() -> new PromptLengths(100L, -1L, 100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toolCallsLength is negative");
    }

    // ============== G P2-9 thinking ==============

    @Test
    @DisplayName("G P2-9: YoloClassifierResult 携带 thinking (CC yoloClassifier.ts:924)")
    void yoloResult_carriesThinking() {
        YoloClassifierResult r = result(true, "ask reason", "claude-thinking",
            3000L, new PromptLengths(2048L, 8192L, 512L),
            "Chain-of-thought: I need to consider security implications...",
            "msg_s1_ccc", null, null, null);
        assertThat(r.thinking())
            .as("CC yoloClassifier.ts:924 thinking: parseXmlThinking(stage2Text) ?? undefined")
            .isNotNull()
            .startsWith("Chain-of-thought");
    }

    // ============== G P2-2 stage1MsgId ==============

    @Test
    @DisplayName("G P2-2: YoloClassifierResult 携带 stage1MsgId (CC yoloClassifier.ts:821,838,856,911)")
    void yoloResult_carriesStage1MsgId() {
        YoloClassifierResult r = result(true, "low conf ask", "claude-sonnet-4-5",
            4000L, new PromptLengths(1500L, 3000L, 800L), null,
            "msg_s1_xyz", "msg_s2_xyz2", null, null);
        assertThat(r.stage1MsgId())
            .as("CC yoloClassifier.ts:821 stage1MsgId = stage1Raw.id")
            .isEqualTo("msg_s1_xyz");
    }

    // ============== G P2-2 stage2MsgId ==============

    @Test
    @DisplayName("G P2-2: YoloClassifierResult 携带 stage2MsgId (CC yoloClassifier.ts:915,939)")
    void yoloResult_carriesStage2MsgId() {
        YoloClassifierResult r = result(false, "thinking allow", "claude-thinking",
            5000L, new PromptLengths(2048L, 6000L, 1024L), "thinking",
            "msg_s1_pqr", "msg_s2_pqr2", null, null);
        assertThat(r.stage2MsgId())
            .as("CC yoloClassifier.ts:915 stage2MsgId = stage2Raw.id")
            .isEqualTo("msg_s2_pqr2");
    }

    // ============== G P2-2/P2-9 集成: classifySync 真实路径携带 msgId + thinking ==============

    @Test
    @DisplayName("G P2-2/P2-9 集成: classifyActionCore 2-stage XML 真实路径携带 stage1/stage2 msgId + thinking"
        + " (CC yoloClassifier.ts:799,885,924)")
    void classifySync_carriesStageMsgIdsAndThinking() throws Exception {
        // stage1 <block>yes</block> → 触发 stage2；stage2 带 <thinking> + <block>no</block>
        // [S06] stage 区分经 userMessage 后缀（XML_S1_SUFFIX / XML_S2_SUFFIX），非 modelName（单模型 ⊕-08）
        LlmProvider fakeProvider = new FakeLlmProvider() {
            @Override
            public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
                if (userMessage.contains("Err on the side of blocking")) {
                    // stage 1 (fast): block → 触发 stage 2
                    return new LlmRawResponse(
                        "<block>yes</block>",
                        "msg_stage1_real", null, "req_stage1");
                }
                // stage 2 (thinking): no + thinking（parseXmlThinking 仅 XML，⊕-06）
                return new LlmRawResponse(
                    "<thinking>thinking from stage2 raw</thinking><block>no</block><reason>blocked after thinking</reason>",
                    "msg_stage2_real", null, "req_stage2");
            }
        };
        YoloClassifierImpl classifier = newClassifier(fakeProvider);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.stage1MsgId())
            .as("stage1MsgId = stage1Raw.id (CC yoloClassifier.ts:799/821/911/935)")
            .isEqualTo("msg_stage1_real");
        assertThat(result.stage2MsgId())
            .as("stage2MsgId = stage2Raw.id (CC yoloClassifier.ts:885/915/939)")
            .isEqualTo("msg_stage2_real");
        assertThat(result.thinking())
            .as("thinking 仅 stage 2 成功路径携带 (CC yoloClassifier.ts:924 parseXmlThinking)")
            .isEqualTo("thinking from stage2 raw");
        assertThat(result.shouldBlock())
            .as("stage2 block=no → shouldBlock=false（CC 布尔契约）")
            .isFalse();
        assertThat(result.stage())
            .as("stage2 决策 → stage=2（CC 'thinking'）")
            .isEqualTo(2);
    }

    // ============== G P2-8 集成: classifySync 按 CC 1038-1051 分桶 promptLengths ==============

    @Test
    @DisplayName("G P2-8 集成: classifySync promptLengths 三段按 CC 角色分桶"
        + " (systemPrompt/actionCompact+assistant/user, yoloClassifier.ts:1038-1051)")
    void classifySync_promptLengthsBuckets() throws Exception {
        ChatMessageDto userMsg = new ChatMessageDto(
            "u1", "s1", Role.user, "user", "user prompt content", null,
            null, null, null, null, null, null, null, null, null, null, null);
        ChatMessageDto asstMsg = new ChatMessageDto(
            "a1", "s1", Role.assistant, "assistant", "assistant tool call content", null,
            null, null, null, null, null, null, null, null, null, null, null);
        // [S12 R2] assistant 文本（无 toolCalls）必须被转录排除 —— 防注入（CC :341-357）
        List<ChatMessageDto> transcript = List.of(userMsg, asstMsg);

        // stage1 <block>no</block> → 单 stage 放行（fast allow）
        LlmProvider fakeProvider = new FakeLlmProvider() {
            @Override
            public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
                return new LlmRawResponse(
                    "<block>no</block>",
                    "msg_stage1_hi", null, "req_stage1");
            }
        };
        YoloClassifierImpl classifier = newClassifier(fakeProvider);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, transcript, null)
            .get(10, TimeUnit.SECONDS);

        // 期望值按与实现相同的构建路径复算 (避免字符串字面量漂移)
        // [S12 R2] assistant 纯文本消息（无 toolCalls）被转录排除 → 不进 toolCalls 桶
        YoloPromptBuilder promptBuilder = new YoloPromptBuilder();
        long expectedSystem = promptBuilder.buildYoloSystemPrompt().length();
        // [OPD-24 G1] actionCompact 走 Bash stub 投影（CC BashTool.tsx:442-444 command）→ "Bash ls -la\n"
        Map<String, Tool> lookup = new java.util.HashMap<>();
        for (Tool t : bashProjectionRegistry().all()) {
            lookup.put(t.name(), t);
        }
        long expectedToolCalls = promptBuilder.toCompactBlock("Bash", input, lookup).length();
        long expectedUser = promptBuilder.toCompact(userMsg, Map.of()).content().length();

        assertThat(result.promptLengths())
            .as("classifySync 必须携带 promptLengths 子对象 (CC yoloClassifier.ts:1062-1066)")
            .isNotNull();
        assertThat(result.promptLengths().systemPromptLength())
            .as("systemPrompt 桶 = systemPrompt.length (CC :1063)")
            .isEqualTo(expectedSystem);
        assertThat(result.promptLengths().toolCallsLength())
            .as("toolCalls 桶 = actionCompact.length + Σ(assistant) (CC :1038-1051)")
            .isEqualTo(expectedToolCalls);
        assertThat(result.promptLengths().userPromptsLength())
            .as("userPrompts 桶 = Σ(user role) (CC :1046-1048)")
            .isEqualTo(expectedUser);
    }

    /**
     * 便捷构造 CC 契约 result · 简化 19 参 record 的测试构造。
     */
    private static YoloClassifierResult result(
            boolean shouldBlock, String reason, String model, long durationMs,
            PromptLengths promptLengths, String thinking,
            String stage1MsgId, String stage2MsgId, String stage1RequestId, String stage2RequestId) {
        int stage = stage2MsgId != null ? YoloClassifierResult.STAGE_THINKING : YoloClassifierResult.STAGE_FAST;
        return new YoloClassifierResult(
            thinking, shouldBlock, reason, false, false, model, null, durationMs, promptLengths,
            null, stage, null, null, stage1RequestId, stage1MsgId, null, null, stage2RequestId, stage2MsgId);
    }

    /**
     * 构造真实 YoloClassifierImpl (真实 promptBuilder/tokenEstimator, mock providerFactory).
     * 单模型（⊕-08）显式传入 "claude-fast"，fake provider 经 userMessage 后缀区分 stage。
     *
     * <p>[RV14B-WIRE-01] 注入 stub ModelConfigResolver（按 modelId 返回真实 config）+ 2 参
     * getProvider(config, providerType) stub。
     */
    private YoloClassifierImpl newClassifier(LlmProvider fakeProvider) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.nullable(String.class))).thenReturn(fakeProvider);
        YoloClassifierImpl classifier = new YoloClassifierImpl(
            new YoloPromptBuilder(),
            new YoloTokenEstimator(),
            factory,
            "claude-fast",
            30,
            resolver,
            true /* two-stage XML：本测试 fake LLM 返回 CC 2-stage XML <block> 协议（OPD-WF6-01 重构） */
        );
        // [OPD-24 G1] 注入带 toAutoClassifierInput override 的 Bash stub —— 否则 default ''
        //   使 action 投影为空 → 短路 ALLOW 不进 LLM（本测试验证真实 LLM 路径必须投影非空）。
        classifier.setToolRegistry(bashProjectionRegistry());
        return classifier;
    }

    /**
     * [IMP-6 OPD-WF6-01-RV] 构造 1-stage 分类器（twoStageClassifier=false）· 与
     * {@link #newClassifier} 同构，仅 two-stage 开关为 false（对齐 CC 默认 1-stage）。
     */
    private YoloClassifierImpl newClassifierOneStage(LlmProvider fakeProvider) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.nullable(String.class))).thenReturn(fakeProvider);
        YoloClassifierImpl classifier = new YoloClassifierImpl(
            new YoloPromptBuilder(),
            new YoloTokenEstimator(),
            factory,
            "claude-fast",
            30,
            resolver,
            false
        );
        classifier.setToolRegistry(bashProjectionRegistry());
        return classifier;
    }

    /**
     * 注册 Bash stub（override toAutoClassifierInput → command，对齐 CC BashTool.tsx:442-444）。
     * [OPD-24 G1] classify() 走 toolRegistry.all() 建投影 lookup，未注入 registry 则查不到工具 → ''。
     */
    private ToolRegistry bashProjectionRegistry() {
        ToolRegistry registry = new ToolRegistry();
        JsonNode schema = new ObjectMapper().createObjectNode();
        registry.register(new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "Tool: Bash"; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public String toAutoClassifierInput(JsonNode input) {
                // CC BashTool.tsx:442-444 toAutoClassifierInput → input.command
                return input != null && input.get("command") != null
                    ? input.get("command").asText() : "";
            }
        });
        return registry;
    }

    // ============== IMP-6 OPD-WF6-01-RV: 1-stage classify_result 工具协议 ==============

    @Test
    @DisplayName("IMP-6 OPD-WF6-01-RV: twoStageClassifier=false → 1-stage classify_result 工具协议"
        + "（chatWithOptionsMessage + tools + tool_choice，CC yoloClassifier.ts:1131-1305）")
    void classifyOneStage_usesClassifyResultToolProtocol() throws Exception {
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider fake = new FakeLlmProvider() {
            @Override
            public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                           String systemPrompt, String userMessage,
                                                           LlmProvider.ChatRequestOptions options) {
                capturedOptions.set(options);
                JsonNode input = new ObjectMapper().createObjectNode()
                    .put("thinking", "allowed because read-only")
                    .put("shouldBlock", false)
                    .put("reason", "read-only ls");
                // AgentUsage 7 参便捷构造：inputTokens/outputTokens/cacheCreationInputTokens/cacheReadInputTokens/...
                return new AssistantMessage("", "tool_calls",
                    List.of(new ToolUseBlock("call_1", YoloPromptBuilder.CLASSIFY_RESULT_TOOL_NAME, input)),
                    "", null, new AgentUsage(100L, 50L, 7L, 9L, null, null, null), null);
            }
        };
        YoloClassifierImpl classifier = newClassifierOneStage(fake);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(capturedOptions.get()).as("1-stage 必须走 chatWithOptionsMessage 工具协议").isNotNull();
        assertThat(capturedOptions.get().toolChoice()).as("CC :1152-1155 tool_choice 必须强制 classify_result").isNotNull();
        assertThat(capturedOptions.get().toolChoice().type()).isEqualTo("tool");
        assertThat(capturedOptions.get().toolChoice().name()).isEqualTo("classify_result");
        assertThat(capturedOptions.get().tools()).as("CC :1151 tools=[YOLO_CLASSIFIER_TOOL_SCHEMA]").hasSize(1);
        assertThat(result.shouldBlock()).as("1-stage 解析 shouldBlock=false → 放行").isFalse();
        assertThat(result.reason()).as("1-stage reason 取自 tool_use input（CC :1242）").isEqualTo("read-only ls");
        assertThat(result.usage()).as("1-stage usage 必须填充（CC :1168-1173）").isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
        assertThat(result.usage().cacheReadInputTokens()).isEqualTo(9);
        assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("IMP-6 OPD-WF6-01-RV: 1-stage 无 classify_result tool_use 块 → block for safety（CC :1198-1212）")
    void classifyOneStage_noToolUseBlock_blocksForSafety() throws Exception {
        LlmProvider fake = new FakeLlmProvider() {
            @Override
            public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                           String systemPrompt, String userMessage,
                                                           LlmProvider.ChatRequestOptions options) {
                return new AssistantMessage("I decline to use tools", "stop",
                    List.of(), "", null, new AgentUsage(1L, 1L, 0L, 0L, null, null, null), null);
            }
        };
        YoloClassifierImpl classifier = newClassifierOneStage(fake);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).as("无 tool_use 块 → fail-closed block").isTrue();
        assertThat(result.reason())
            .as("CC :1206 reason 对齐").isEqualTo("Classifier returned no tool use block - blocking for safety");
    }

    @Test
    @DisplayName("IMP-6 OPD-WF6-01-RV: 1-stage classify_result schema 非法（shouldBlock 非布尔）→ block for safety（CC :1220-1236）")
    void classifyOneStage_invalidSchema_blocksForSafety() throws Exception {
        LlmProvider fake = new FakeLlmProvider() {
            @Override
            public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                           String systemPrompt, String userMessage,
                                                           LlmProvider.ChatRequestOptions options) {
                JsonNode input = new ObjectMapper().createObjectNode()
                    .put("thinking", "reasoning")
                    .put("shouldBlock", "yes")  // 字符串而非布尔 → zod 解析失败
                    .put("reason", "blocked");
                return new AssistantMessage("", "tool_calls",
                    List.of(new ToolUseBlock("call_1", YoloPromptBuilder.CLASSIFY_RESULT_TOOL_NAME, input)),
                    "", null, new AgentUsage(1L, 1L, 0L, 0L, null, null, null), null);
            }
        };
        YoloClassifierImpl classifier = newClassifierOneStage(fake);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).as("schema 非法 → fail-closed block").isTrue();
        assertThat(result.reason())
            .as("CC :1229 reason 对齐").isEqualTo("Invalid classifier response - blocking for safety");
    }

    // ============== IMP-6 OPD-WF6-02-RV: usage extractUsage/combineUsage 填充 ==============

    @Test
    @DisplayName("IMP-6 OPD-WF6-02-RV: 2-stage usage 填充（extractUsage stage1/stage2 + combineUsage 汇总，CC :797/:883/:889-891）")
    void classifyTwoStage_carriesUsage() throws Exception {
        LlmProvider fake = new FakeLlmProvider() {
            @Override
            public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
                if (userMessage.contains("Err on the side of blocking")) {
                    // stage1 <block>yes</block> + usage (100, 20, 5, 3)
                    return new LlmRawResponse("<block>yes</block>", "msg_s1_usage", "th1", "req_s1_usage",
                        new LlmProvider.LlmUsage(100, 20, 5, 3));
                }
                // stage2 <block>no</block> + usage (50, 10, 2, 1)
                return new LlmRawResponse("<block>no</block><reason>usage ok</reason>", "msg_s2_usage", "th2", "req_s2_usage",
                    new LlmProvider.LlmUsage(50, 10, 2, 1));
            }
        };
        YoloClassifierImpl classifier = newClassifier(fake);
        JsonNode input = new ObjectMapper().createObjectNode().put("command", "ls -la");

        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).isFalse();
        assertThat(result.usage()).as("usage = combineUsage(stage1Usage, stage2Usage)（CC :889-891）").isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(150);
        assertThat(result.usage().outputTokens()).isEqualTo(30);
        assertThat(result.usage().cacheReadInputTokens()).isEqualTo(7);
        assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(4);
        assertThat(result.stage1Usage()).as("stage1Usage = extractUsage(stage1Raw)（CC :797）").isNotNull();
        assertThat(result.stage1Usage().inputTokens()).isEqualTo(100);
        assertThat(result.stage2Usage()).as("stage2Usage = extractUsage(stage2Raw)（CC :883）").isNotNull();
        assertThat(result.stage2Usage().inputTokens()).isEqualTo(50);
    }

    // ============== IMP-6 OPD-WF6-03-RV: 用户 auto-mode 规则注入 ==============

    @Test
    @DisplayName("IMP-6 OPD-WF6-03-RV: buildYoloSystemPrompt 注入 settings.autoMode 三段（allow/soft_deny/environment，CC yoloClassifier.ts:494-539）")
    void buildYoloSystemPrompt_injectsAutoModeRules() throws Exception {
        YoloPromptBuilder builder = new YoloPromptBuilder();
        Path settingsPath = Files.createTempFile("settings-imp6", ".json");
        try {
            Files.writeString(settingsPath, "{\"autoMode\":{\"allow\":[\"git status\",\"git diff\"],"
                + "\"soft_deny\":[\"rm -rf\"],\"environment\":[\"project uses node 20\"]}}");
            builder.setUserSettingsPath(settingsPath);
            String prompt = builder.buildYoloSystemPrompt();

            assertThat(prompt).as("allow 规则注入（CC :517-519/528-531）").contains("- git status").contains("- git diff");
            assertThat(prompt).as("soft_deny 规则注入（CC :520-522/532-535）").contains("- rm -rf");
            assertThat(prompt).as("environment 规则注入（CC :523-525/536-539）").contains("- project uses node 20");
            assertThat(prompt).as("用户规则 REPLACE 模板默认值（CC :530 userAllow ?? defaults）")
                .doesNotContain("- Running read-only shell commands");
        } finally {
            Files.deleteIfExists(settingsPath);
        }
    }

    @Test
    @DisplayName("IMP-6 OPD-WF6-03-RV: 无用户规则 → 保留模板默认值（CC :530/:534/:538 userX ?? defaults）")
    void buildYoloSystemPrompt_keepsTemplateDefaultsWithoutRules() throws Exception {
        YoloPromptBuilder builder = new YoloPromptBuilder();
        Path settingsPath = Files.createTempFile("settings-imp6-default", ".json");
        try {
            Files.writeString(settingsPath, "{}");
            builder.setUserSettingsPath(settingsPath);
            String prompt = builder.buildYoloSystemPrompt();

            assertThat(prompt).as("模板默认 allow 规则保留").contains("- Running read-only shell commands");
            assertThat(prompt).as("模板默认 deny 规则保留").contains("- Recursive force deletion of directories or files");
        } finally {
            Files.deleteIfExists(settingsPath);
        }
    }

    // ============== G step 5d: StreamingToolExecutor abort 真实路径 OTel 埋点 ==============

    @Test
    @DisplayName("G step 5d: 真实 abort 入口(已 abort 的 AbortController)触发 tengu_tool_use_cancelled"
        + " + 含 CC toolExecution.ts:419 isMcp 字段 (对齐 CC)")
    void streamingExecutor_emitsCancelledOnAbortedEntry() throws Exception {
        // 1) 构造 Spy Telemetry(捕获 recordEvent + logOTelEvent 调用)
        SpyTelemetry spy = new SpyTelemetry();

        // 2) 构造 StreamingToolExecutor: 注册 stub 工具(走真实 executeAsync 而非 unknown-tool 短路)
        //    + ctx 携带已 abort 的 AbortController(reason=permission_denied)
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool("Bash", false));
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-cancel-telemetry");
            t.setDaemon(true);
            return t;
        });
        AbortController abortController = new AbortController();
        abortController.abort("permission_denied");
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(),
            null, PermissionMode.DEFAULT
        );
        StreamingToolExecutor exec = new StreamingToolExecutor(
            registry,
            executor,
            ctx,
            null,  // Consumer<AgentToolResult>
            null,  // ToolPermissionGate
            null   // HookRegistry
        );
        exec.setTelemetry(spy);

        // 3) 真实 abort 路径: add(call) → executeAsync 入口 getAbortReason 短路
        //    (reason=permission_denied → user_interrupted, StreamingToolExecutor.java:2745-2746)
        try {
            JsonNode input = new ObjectMapper().createObjectNode();
            exec.add(new ToolUseBlock("call_cancel_test_001", "Bash", input));
            List<ToolResult> results = exec.getRemainingResults();
            assertThat(results)
                .as("abort 短路必须产出合成错误结果")
                .hasSize(1);
            assertThat(exec.getResultErrorFlags().get("call_cancel_test_001"))
                .as("abort 短路合成错误结果必须标记 error（IMP-C2 后 isError 由执行器推导）")
                .isTrue();
        } finally {
            executor.shutdown();
        }

        // 4) 断言: tengu_tool_use_cancelled 被 recordEvent + logOTelEvent 双发
        assertThat(spy.recordedEvents)
            .as("abort 入口必须触发 1P recordEvent (CC toolExecution.ts:416)")
            .containsKey("tengu_tool_use_cancelled");
        Map<String, Object> cancelledAttrs = spy.recordedEvents.get("tengu_tool_use_cancelled");
        assertThat(cancelledAttrs)
            .as("CC toolExecution.ts:416-422 3 核心字段必须注入")
            .containsKeys("toolName", "toolUseID", "isMcp");
        assertThat(cancelledAttrs.get("toolName"))
            .isEqualTo("Bash");
        assertThat(cancelledAttrs.get("toolUseID"))
            .isEqualTo("call_cancel_test_001");
        assertThat(cancelledAttrs.get("isMcp"))
            .as("CC toolExecution.ts:419 isMcp: tool.isMcp ?? false 必对齐")
            .isEqualTo(false);

        // OTel 部分独立验证
        assertThat(spy.otelEvents)
            .as("abort 入口必须双发 OTel (Java 扩展通道)")
            .containsKey("tengu_tool_use_cancelled");
        assertThat(spy.otelEvents.get("tengu_tool_use_cancelled"))
            .containsKeys("toolName", "toolUseID", "isMcp");
    }

    /**
     * 最小 fake LlmProvider: chat/stream 抛错, 仅 chatWithRaw 可用
     * (YoloClassifier 唯一调用通道, M3.2 对齐 CC sideQuery).
     */
    private abstract static class FakeLlmProvider implements LlmProvider {
        @Override public String type() { return "fake"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            throw new UnsupportedOperationException("YoloClassifier 路径必须走 chatWithRaw (M3.2)");
        }
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride,
                                     com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onToken, Consumer<AssistantMessage> onMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk, Runnable onStreamingFallback,
                                     com.nexusai.application.agent.tool.AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onDone) {
            throw new UnsupportedOperationException("YoloClassifier 路径不使用 stream");
        }
    }

    /**
     * 最小 stub 工具: 使 add() 走真实 executeAsync 路径 (registry.get 命中),
     * abort 短路时工具不会被执行, execute 永不调用.
     */
    private Tool stubTool(String name, boolean mcp) {
        JsonNode schema = new ObjectMapper().createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public boolean isMcp() { return mcp; }
        };
    }

    /**
     * Spy Telemetry · 双发 recordEvent + logOTelEvent 写入内存 Map 验证调用.
     */
    static class SpyTelemetry extends Telemetry {
        final Map<String, Map<String, Object>> recordedEvents = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> otelEvents = new ConcurrentHashMap<>();

        @Override
        public void recordEvent(String eventName, Map<String, Object> metadata) {
            recordedEvents.put(eventName, new java.util.HashMap<>(metadata));
        }

        @Override
        public void logOTelEvent(String eventName, Map<String, ?> metadata) {
            otelEvents.put(eventName, new java.util.HashMap<>(metadata));
        }

        @Override
        public void logOTelEvent(String eventName) {
            otelEvents.put(eventName, Map.of());
        }
    }
}
