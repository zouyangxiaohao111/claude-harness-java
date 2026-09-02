package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.provider.dto.ModelDto;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H13] ExecAgentHook 语义补齐测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/hooks/execAgentHook.ts (340 行)。
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图, 非仅行为): H13 补齐 7 项语义——
 * structured_output attachment 检测（替换 tool_call 反向扫）、schema 校验失败跳过继续、
 * HookResult.message 补 attachment、tengu_agent_stop_hook_* analytics、agentName、
 * hookAgentId 'hook-agent-${UUID}'、StructuredOutputEnforcementHook 5s 重入超时。
 * 每条断言体现 CC 真源行为 WHY, 而非仅"结果对不对"。
 *
 * <p>RED 证明: {@link #execAgentHook_detectsStructuredOutputAttachment_notToolCallReverseScan}
 * 在旧实现（tool_call 反向扫）下失败——agent 最后一次 StructuredOutput 调用 schema 非法时,
 * 旧实现取到非法 arguments 判 non_blocking_error（hook 验证条件永远失败）; 新实现（attachment
 * 检测）跳过非法结果取到早前合法 attachment → success。
 */
@DisplayName("[H13] ExecAgentHook 语义补齐对齐 CC execAgentHook.ts")
class ExecAgentHookSemanticsTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String SO = ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME;
    private static final String HOOK_NAME = "test-agent-hook";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");

    // ════════════════════════════════════════════════════════════════════════
    // 测试夹具
    // ════════════════════════════════════════════════════════════════════════

    /** 合法 StructuredOutput tool_call · input={ok, reason?} · 对齐 CC hookResponseSchema. */
    private ToolUseBlock structuredCall(boolean ok, String reason) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("ok", ok);
        if (reason != null) input.put("reason", reason);
        return new ToolUseBlock("toolu-struct-" + System.nanoTime(), SO, input);
    }

    /** schema 非法的 StructuredOutput tool_call · 缺 ok 字段（CC hookResponseSchema required:['ok']）.
     *  SyntheticOutputTool 校验失败 → 工具返回 error, 不产生 structured_output attachment。 */
    private ToolUseBlock invalidStructuredCall() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("foo", "bar");
        return new ToolUseBlock("toolu-struct-invalid-" + System.nanoTime(), SO, input);
    }

    /** 非 structured tool_call（如 Read）· 触发多轮回填. */
    private ToolUseBlock readCall() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "transcript.txt");
        return new ToolUseBlock("toolu-read-" + System.nanoTime(), "Read", input);
    }

    /** 纯文本 stop 响应 · 让 queryLoop needsFollowUp=false 退出循环. */
    private static AssistantMessage stopText(String text) {
        return new AssistantMessage(text, "stop", List.of());
    }

    /** 按脚本返回 AssistantMessage 的 provider · 超出脚本长度时重复最后一条. */
    static class ScriptableProvider implements LlmProvider {
        final List<AssistantMessage> responses;
        final AtomicInteger callCount = new AtomicInteger(0);
        // [IMP-HOOKS-S2] 捕获供 CCJ-EXEC-16 / CCJ-EXEC-08 断言（null = 未捕获）
        final AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();
        final AtomicReference<ArrayNode> capturedTools = new AtomicReference<>();
        final AtomicReference<String> capturedThinkingType = new AtomicReference<>();

        ScriptableProvider(List<AssistantMessage> responses) { this.responses = responses; }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<com.nexusai.model.session.dto.ChatMessageDto> history, ArrayNode tools,
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
            capturedSystemPrompt.set(systemPromptBlocks == null ? null
                : systemPromptBlocks.stream()
                    .map(com.nexusai.application.agent.prompt.SystemPromptBlock::text)
                    .collect(java.util.stream.Collectors.joining("\n\n")));
            capturedTools.set(tools);
            int idx = callCount.getAndIncrement();
            AssistantMessage am = responses.get(Math.min(idx, responses.size() - 1));
            onAssistantMessage.accept(am);
            onComplete.run();
        }

        /**
         * [CCJ-EXEC-08] 捕获 thinkingConfig（hook agent 请求经 19-arg blocks 重载下发）。
         * 随后委托既有 blocks 重载（默认实现忽略 thinkingConfig，行为与无捕获版本一致）。
         */
        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<com.nexusai.model.session.dto.ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride, com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           LlmProvider.ChatRequestOptions.ThinkingConfig thinkingConfig,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            capturedThinkingType.set(thinkingConfig != null ? thinkingConfig.type() : null);
            stream(config, modelName, systemPromptBlocks, history, tools,
                maxOutputTokensOverride, taskBudget, effortValue, querySource,
                onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
                onStreamingFallback, abortController, onError, onComplete);
        }
    }

    /** 抛异常 provider（non_blocking_error 测试）· 对齐 CC :316-338 外层 catch. */
    private static LlmProvider explodingProvider() {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }
            @Override
            public void stream(ProviderConfig config, String modelName,
                               List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                               List<com.nexusai.model.session.dto.ChatMessageDto> history, ArrayNode tools,
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

    private ExecAgentHook hookWith(LlmProvider provider) {
        return hookWith(provider, null, null);
    }


    /** 捕获 recordEvent 事件名 + 属性 · 用于验证 tengu_agent_stop_hook_* analytics 载荷. */
    static class CapturingTelemetry extends Telemetry {
        final List<String> eventNames = new ArrayList<>();
        final Map<String, Map<String, Object>> eventAttrs = new LinkedHashMap<>();

        @Override public void recordEvent(String name, Map<String, Object> attributes) {
            eventNames.add(name);
            eventAttrs.put(name, attributes);
            super.recordEvent(name, attributes);
        }
    }

    /** 捕获 register(name,...) 的注册名 · 用于验证 enforcement hook 名含 hook-agent- 前缀. */
    static class CapturingRegistry extends HookRegistry {
        final List<String> registeredNames = new ArrayList<>();

        @Override public synchronized void register(String name, GenericHook hook, HookEventType... events) {
            registeredNames.add(name);
            super.register(name, hook, events);
        }
    }

    private ExecAgentHook hookWith(LlmProvider provider, HookRegistry registry, Telemetry telemetry) {
        // [MAINCHAIN-01] LlmAgentLoop 主链现调 2 参 getProvider(config, providerType)，须覆写 2 参版本
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return provider; }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        return new ExecAgentHook(objectMapper, contextFactory, new ToolRegistry(), registry,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, telemetry, null, null);
    }

    private HookResult exec(ExecAgentHook h, String prompt, String jsonInput, String agentName) {
        return exec(h, prompt, jsonInput, agentName, null);
    }

    /** 带父 permission context 的 exec（H13-GAP-1 v3 dontAsk + Read(transcriptPath) 测试用）. */
    private HookResult exec(ExecAgentHook h, String prompt, String jsonInput, String agentName,
                            com.nexusai.application.agent.permission.ToolPermissionContext parentPermCtx) {
        AgentHook hook = new AgentHook(prompt, null, null, null, null, null);
        return h.exec(hook, HOOK_NAME, hookEvent, jsonInput, null, null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), agentName,
            parentPermCtx);
    }

    // ════════════════════════════════════════════════════════════════════════
    // structured_output attachment 检测（替换 tool_call 反向扫）· CC :212-226
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("attachment 检测: 非法 StructuredOutput 在最后 -> 跳过非法取早前合法 attachment -> success（CC :212-226）")
    void execAgentHook_detectsStructuredOutputAttachment_notToolCallReverseScan() {
        // WHY (规则九): CC 通过 message.attachment.type==='structured_output' 检测, 不是反向扫 tool_call。
        // agent 先合法调用 StructuredOutput({ok:true} → attachment)，再调一次 schema 非法 ({foo:bar} → 工具
        // error, 不产生 attachment)。旧 Java 反向扫 tool_call 会取到最后一次非法的 arguments 判
        // non_blocking_error（hook 验证条件永远判失败）；attachment 检测跳过非法结果, 取到早前合法
        // attachment → success。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),  // 合法 attachment
            new AssistantMessage("", "tool_calls", List.of(invalidStructuredCall())),      // schema 非法, 无 attachment
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p, null, new CapturingTelemetry());

        HookResult r = exec(h, "verify", "{}", null);

        // 新实现: 取到 {ok:true} attachment → success（非法结果被跳过）
        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
    }

    @Test
    @DisplayName("schema 校验失败 -> 跳过继续 loop（不是直接 non_blocking_error）· CC :216 if(parsed.success) 分支")
    void execAgentHook_schemaInvalidStructuredOutput_skipsAndContinuesLoop() {
        // WHY: CC hookResponseSchema().safeParse 失败时 if(parsed.success) 不成立 → 不记录结果, 继续 loop。
        // 非法 StructuredOutput 之后 agent 重新合法调用 → success。若把 schema 失败直接判
        // non_blocking_error, 一次 LLM 幻觉调用就杀死整个 hook（错误路径）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(invalidStructuredCall())),      // 非法 → 跳过
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),   // 合法 → 接受
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p, null, new CapturingTelemetry());

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // HookResult.message 补 attachment · CC :296-302 (hook_success) / :328-336 (hook_non_blocking_error)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ok:true -> success + message=hook_success attachment（非 null）· CC :293-303")
    void execAgentHook_success_returnsHookSuccessAttachment() {
        // WHY: CC success 返回 message: createAttachmentMessage({type:'hook_success', hookName, toolUseID, hookEvent, content:''})。
        // 旧 Java HookResult.message 恒 null → 前端/审计拿不到 hook 结果 attachment（探查 §C 2.3-2）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p, null, new CapturingTelemetry());

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) r.message()).type()).isEqualTo("hook_success");
        assertThat(((AttachmentMessageDto) r.message()).hookName()).isEqualTo(HOOK_NAME);
    }

    // ════════════════════════════════════════════════════════════════════════
    // blockingError 文本 · CC :278-282 reason undefined → "undefined"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ok:false 且 reason 缺省 -> blockingError 'Agent hook condition was not met: undefined'（CC 模板字面量）")
    void execAgentHook_blockingReasonNull_usesUndefined() {
        // WHY: CC `${structuredOutputResult.reason}` 模板字面量在 reason=undefined 时拼接为 "undefined"。
        // 旧 Java 用 "Agent hook condition was not met" 兜底 → 拼出
        // "Agent hook condition was not met: Agent hook condition was not met"（探查 §C 2.3-9 文本错位）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(false, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p, null, new CapturingTelemetry());

        HookResult r = exec(h, "verify $ARGUMENTS", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError())
            .isEqualTo("Agent hook condition was not met: undefined");
        // CC :280 blockingError.command = hook.prompt
        assertThat(r.blockingError().command()).isEqualTo("verify $ARGUMENTS");
        // [CCJ-EXEC-14] agent blocking 字段面逐字对齐 CC：无 preventContinuation、无 stopReason
        assertThat(r.preventContinuation()).isFalse();
        assertThat(r.stopReason()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // analytics 事件 · CC :242-247 max_turns / :257-263 error type1 / :319-324 error type2 / :287-292 success
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("50 turn 熔断 -> cancelled + tengu_agent_stop_hook_max_turns analytics（CC :238-252）")
    void execAgentHook_maxTurns_emitsMaxTurnsAnalytics() {
        // WHY: CC turnCount>=50 → hitMaxTurns=true → abort()+break → logEvent('tengu_agent_stop_hook_max_turns')。
        // Java loop 以 maxTurns=50 在 turn 顶退出（exitReason=MAX_TURNS）, resolveOutcome 补发同名 analytics。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("checking", "tool_calls", List.of(readCall()))
        ));
        CapturingTelemetry telemetry = new CapturingTelemetry();
        // registry=null: 不注册 enforcement hook, 避免 maxTurns 后 STOP 重入放大轮数（本测试只验 analytics）
        ExecAgentHook h = hookWith(p, null, telemetry);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.CANCELLED);
        assertThat(telemetry.eventNames).contains("tengu_agent_stop_hook_max_turns");
    }

    @Test
    @DisplayName("执行错误 -> non_blocking_error + tengu_agent_stop_hook_error(errorType=2) analytics（CC :316-338）")
    void execAgentHook_error_emitsErrorType2Analytics() {
        // WHY: CC 外层 catch error → logEvent('tengu_agent_stop_hook_error', {errorType: 2, agentName})。
        // Java loop provider 报错设 exitReason=STREAM_ERROR, resolveOutcome isErrorExit → non_blocking_error + type2。
        CapturingTelemetry telemetry = new CapturingTelemetry();
        ExecAgentHook h = hookWith(explodingProvider(), null, telemetry);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(telemetry.eventNames).contains("tengu_agent_stop_hook_error");
        assertThat(telemetry.eventAttrs.get("tengu_agent_stop_hook_error"))
            .containsEntry("errorType", 2);
    }

    @Test
    @DisplayName("non_blocking_error attachment 携带 stderr/stdout/exitCode=1（CC :328-336）")
    void execAgentHook_error_attachmentCarriesStderrStdoutExitCode() {
        // WHY (对抗核验 H13-GAP): CC :333-335 createAttachmentMessage({type:'hook_non_blocking_error',
        //   stderr:'Error executing agent hook: ...', stdout:'', exitCode:1}) 三字段齐全。
        // 旧 Java hookNonBlockingError 仅 content=stderr, stdout/exitCode 丢弃 —— 审计拿不到完整错误载荷。
        CapturingTelemetry telemetry = new CapturingTelemetry();
        ExecAgentHook h = hookWith(explodingProvider(), null, telemetry);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) r.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        // stderr 含 CC 前缀（:333 "Error executing agent hook: "）
        assertThat(att.stderr()).contains("Error executing agent hook: ");
        // stdout = ''（CC :334）
        assertThat(att.stdout()).isEqualTo("");
        // exitCode = 1（CC :335）
        assertThat(att.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("agentName 写入 analytics（CC :246/261/291/322 透传 agent_type）")
    void execAgentHook_agentName_writtenToAnalytics() {
        // WHY: CC logEvent metadata 含 agentName（来自 hookInput.agent_type），用于追踪 hook 归属 agent。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        CapturingTelemetry telemetry = new CapturingTelemetry();
        ExecAgentHook h = hookWith(p, null, telemetry);

        exec(h, "verify", "{}", "subagent-1");

        assertThat(telemetry.eventNames).contains("tengu_agent_stop_hook_success");
        assertThat(telemetry.eventAttrs.get("tengu_agent_stop_hook_success"))
            .containsEntry("agentName", "subagent-1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [?-EX-06] 真实 provider 解析 · 参照 HookRegistry.resolvePromptProvider 模式
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("?-EX-06: ProviderService 提供 enabled+model 匹配 provider → QueryParams.config 非 null/usable（真实 provider 通道）")
    void agentHook_providerResolution_feedsUsableConfigToLoop() {
        // WHY (?-EX-06): 生产上下文无 ProviderConfig bean → providerConfig 注入恒 null →
        //   QueryParams.forLoop(config=null) → loop 走 MockLlmProvider → agent hook 恒
        //   non_blocking_error（09 §8.2 登记）。新通道：ProviderService 按 modelName 解析
        //   enabled + model 匹配 provider → ProviderConfig(baseUrl, 解密 apiKey) → loop 真实可用。
        ModelDto model = new ModelDto("m1", DEFAULT_FAST_MODEL, null, null, null, null,
            null, null, null, null, true, null,
            null, null, null, null, null, null, null, null);
        ProviderDto provider = new ProviderDto("p1", "provider-1", ProviderType.openai_compatible,
            "http://llm.local", null, Map.of(), true, List.of(model), null, null);
        ProviderService ps = new ProviderService() {
            @Override public List<ProviderDto> listAll() { return List.of(provider); }
            @Override public String getDecryptedApiKey(String id) { return "sk-real"; }
        };
        AtomicReference<ProviderConfig> configSeen = new AtomicReference<>();
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                configSeen.set(config);
                return p;
            }
            @Override public LlmProvider getProvider(ProviderConfig config) {
                configSeen.set(config);
                return p;
            }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        ExecAgentHook h = new ExecAgentHook(objectMapper, contextFactory, new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, new CapturingTelemetry(), ps, factory);

        HookResult r = exec(h, "verify", "{}", null);

        // hook 正常执行（真实 provider 下 StructuredOutput → success）
        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        // loop 收到的 config = 解析出的真实配置（baseUrl + 解密 apiKey）
        ProviderConfig seen = configSeen.get();
        assertThat(seen).isNotNull();
        assertThat(seen.isUsable()).isTrue();
        assertThat(seen.baseUrl()).isEqualTo("http://llm.local");
        assertThat(seen.apiKey()).isEqualTo("sk-real");
    }

    @Test
    @DisplayName("?-EX-06: provider 解析不到 → 回落注入兜底（config 不可用, 不构造假可用）")
    void agentHook_providerResolution_noMatch_keepsFallback() {
        // WHY (?-EX-06): 解析不到（无 enabled+model 匹配）→ warn + 维持修复前行为
        //   （注入兜底 ProviderConfig.empty() → loop 走 mock 路径），不构造假可用的
        //   ProviderConfig —— hook 仍执行（不静默跳过）。
        ProviderService ps = new ProviderService() {
            @Override public List<ProviderDto> listAll() { return List.of(); }
            @Override public String getDecryptedApiKey(String id) { return "sk-real"; }
        };
        AtomicReference<ProviderConfig> configSeen = new AtomicReference<>();
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                configSeen.set(config);
                return p;
            }
            @Override public LlmProvider getProvider(ProviderConfig config) {
                configSeen.set(config);
                return p;
            }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        ExecAgentHook h = new ExecAgentHook(objectMapper, contextFactory, new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, new CapturingTelemetry(), ps, factory);

        exec(h, "verify", "{}", null);

        // 兜底 = 注入的 ProviderConfig.empty()（不可用 → loop mock 路径，不假可用）
        ProviderConfig seen = configSeen.get();
        assertThat(seen).isNotNull();
        assertThat(seen.isUsable()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [EX-C R10] model 回落 · CC execAgentHook.ts:118 hook.model ?? getSmallFastModel()
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R10: hook.model 与 defaultFastModel 均空 → 回落 getSmallFastModel env 链（无空串进 QueryParams）")
    void execAgentHook_blankModel_fallsBackToSmallFastModel() {
        // WHY (completion R10): CC :118 hook.model ?? getSmallFastModel() —— fast model 兜底是 env 链
        //   （ANTHROPIC_SMALL_FAST_MODEL || ANTHROPIC_DEFAULT_HAIKU_MODEL || claude-haiku-4-5-20251001，
        //   model.ts:36-38）。旧 Java resolveModel 在 nexusai.hook.fastModel 未配置（空串）时产出空串
        //   进 QueryParams（无守卫）。[R10] 双空 → 回落 SkillImprovementHook.getSmallFastModel()，
        //   断言 loop 实际收到非空模型名。
        AtomicReference<String> capturedModel = new AtomicReference<>();
        LlmProvider recording = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }
            @Override
            public void stream(ProviderConfig config, String modelName,
                               List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                               List<com.nexusai.model.session.dto.ChatMessageDto> history, ArrayNode tools,
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
                capturedModel.set(modelName);
                onAssistantMessage.accept(new AssistantMessage("", "tool_calls",
                    List.of(structuredCall(true, null))));
                onComplete.run();
            }
        };
        LlmProviderFactory factory = new LlmProviderFactory() {
            // [MAINCHAIN-01] 主链现调 2 参 getProvider(config, providerType)，须覆写 2 参版本
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return recording; }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        // defaultFastModel = 空串（nexusai.hook.fastModel 未配置, 同 Spring 缺省）
        ExecAgentHook h = new ExecAgentHook(objectMapper, contextFactory, new ToolRegistry(), null,
            ProviderConfig.empty(), "", new CapturingTelemetry(), null, null);

        HookResult r = exec(h, "verify", "{}", null);

        // hook 正常执行（结构化输出 → success）
        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        // 模型名非空且等于 getSmallFastModel env 链解析值（与 SkillImprovementHook 同实现）
        assertThat(capturedModel.get()).as("hook.model/defaultFastModel 均空 → 不得产出空串进 QueryParams")
            .isNotBlank();
        assertThat(capturedModel.get()).isEqualTo(SkillImprovementHook.getSmallFastModel());
    }

    // ════════════════════════════════════════════════════════════════════════
    // hookAgentId 语义 · CC :122 asAgentId('hook-agent-${randomUUID()}')
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hookAgentId = 'hook-agent-${UUID}' 前缀（非裸 UUID）· enforcement hook 注册名含前缀")
    void execAgentHook_usesHookAgentIdPrefix_notBareUuid() {
        // WHY: CC :122 为 hook agent 建独立命名空间 asAgentId('hook-agent-${UUID}')，避免与父 agent 冲突。
        // 旧 Java exec() 直接用 UUID.randomUUID()（generateHookAgentId 死方法）。本测试经 CapturingRegistry
        // 观察 enforcement hook 注册名, 断言含 'hook-agent-' 前缀 → 证明 exec() 走了 generateHookAgentId()。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        CapturingRegistry registry = new CapturingRegistry();
        ExecAgentHook h = hookWith(p, registry, new CapturingTelemetry());

        exec(h, "verify", "{}", null);

        assertThat(registry.registeredNames)
            .anyMatch(name -> name.contains("hook-agent-"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // StructuredOutputEnforcementHook 5s 重入超时 · CC hookHelpers.ts:81 {timeout: 5000}
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // [对抗核验 H13-GAP-1 v3] dontAsk + Read(transcriptPath) session rule · CC execAgentHook.ts:141-153
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("父 permCtx 继承: 父 alwaysAllowRules 保留 + 追加 SESSION Read(/transcriptPath) + mode=DONT_ASK")
    void buildHookPermissionContext_inheritsParentRulesAndAddsRead() {
        // WHY (J.md H13-GAP-1): CC getAppState() override = {...父规则集, mode:'dontAsk',
        //   session: [...父session规则, `Read(/${transcriptPath})`]}。旧 Java 空规则集 + 无父继承,
        //   DONT_ASK 会拒绝 hook 全部工具（Bash/Grep/Read 均需 ask）→ hook 无法验证条件。
        // 父 context: USER_SETTINGS allow Bash + SESSION allow Grep
        Map<com.nexusai.application.agent.permission.PermissionRuleSource,
            Set<com.nexusai.application.agent.permission.PermissionRule>> parentAllow = new java.util.HashMap<>();
        parentAllow.put(com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS,
            Set.of(new com.nexusai.application.agent.permission.PermissionRule(
                com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
                com.nexusai.application.agent.permission.PermissionRuleValue.wholeTool("Bash"))));
        parentAllow.put(com.nexusai.application.agent.permission.PermissionRuleSource.SESSION,
            Set.of(new com.nexusai.application.agent.permission.PermissionRule(
                com.nexusai.application.agent.permission.PermissionRuleSource.SESSION,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
                com.nexusai.application.agent.permission.PermissionRuleValue.wholeTool("Grep"))));
        com.nexusai.application.agent.permission.ToolPermissionContext parent =
            com.nexusai.application.agent.permission.ToolPermissionContext.of(
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
                parentAllow, Map.of(), Map.of(), Map.of());

        var hookPermCtx = ExecAgentHook.buildHookPermissionContext(parent, "sessions/sess-1/transcript.jsonl");

        // mode=DONT_ASK（驱动 dontAsk→deny，对齐 CC permissions.ts:503-517）
        assertThat(hookPermCtx.mode()).isEqualTo(com.nexusai.application.agent.permission.PermissionMode.DONT_ASK);
        // 父 USER_SETTINGS Bash 规则继承
        assertThat(hookPermCtx.alwaysAllowRules().get(
            com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS))
            .extracting(r -> r.ruleValue().toolName()).contains("Bash");
        // SESSION 桶 = 父 Grep + 新 Read(/transcriptPath)（CC session: [...existing, `Read(/path)`]）
        var sessionRules = hookPermCtx.alwaysAllowRules().get(
            com.nexusai.application.agent.permission.PermissionRuleSource.SESSION);
        assertThat(sessionRules).extracting(r -> r.ruleValue().toolName()).contains("Grep", "Read");
        assertThat(sessionRules).anyMatch(r -> "Read".equals(r.ruleValue().toolName())
            && "/sessions/sess-1/transcript.jsonl".equals(r.ruleValue().ruleContent()));
    }

    @Test
    @DisplayName("父 permCtx 为 null -> 返回 null（旧行为, 不触发 DONT_ASK 拒绝全部工具）")
    void buildHookPermissionContext_nullParent_returnsNull() {
        // WHY: 父上下文缺失时不能降级为 DONT_ASK+空规则（会拒绝 hook 全部工具, 破坏验证能力）。
        //       保持旧行为（null → hook TUC 不设置专属 permCtx）。
        assertThat(ExecAgentHook.buildHookPermissionContext(null, "transcript.jsonl")).isNull();
    }

    @Test
    @DisplayName("transcriptPath 空 -> 不追加 Read 规则（父规则仍继承）")
    void buildHookPermissionContext_blankTranscript_skipsReadRule() {
        // WHY: CC 只在 transcriptPath 存在时追加 Read 规则；transcriptPath 缺失时不应注入无效 Read。
        var parent = com.nexusai.application.agent.permission.ToolPermissionContext.strict(
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
        var hookPermCtx = ExecAgentHook.buildHookPermissionContext(parent, null);

        assertThat(hookPermCtx).isNotNull();
        assertThat(hookPermCtx.mode()).isEqualTo(com.nexusai.application.agent.permission.PermissionMode.DONT_ASK);
        assertThat(hookPermCtx.alwaysAllowRules().get(
            com.nexusai.application.agent.permission.PermissionRuleSource.SESSION)).isNullOrEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [对抗核验 H13-GAP-5 v3] STOP 事件 data 注入 agent_type · CC coreSchemas.ts:393
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("STOP 事件 agent_type 工厂: data 携带 agent_type（agent hook agentName 生产载荷）")
    void stopEvent_factory_withAgentType_carriesData() {
        // WHY (对抗核验 H13-GAP-5): LlmAgentLoop 构造 STOP 事件 data 不填 agent_type →
        //   HookRegistry.hookAgentNameFrom 读到 null → STOP 触发 agent hook 的 agentName analytics 无载荷。
        //   新 5 参工厂由 loop 注入 ToolUseContext.agentType()。
        HookEvent e = HookEvent.stop("sess-1", "agent-1", false, "last msg", "explore");
        assertThat(e.data()).containsEntry("agent_type", "explore");
        assertThat(e.data()).containsEntry("stop_hook_active", false);
    }

    @Test
    @DisplayName("STOP 事件 agent_type 空 -> data 不含 agent_type（对齐 CC agent_type 缺失语义）")
    void stopEvent_factory_blankAgentType_omitsData() {
        // WHY: 主循环 ToolUseContext.agentType() 为 null（主 agent 无类型）→ 不应写入空串污染 data。
        HookEvent e = HookEvent.stop("sess-1", "agent-1", true, null, null);
        assertThat(e.data()).doesNotContainKey("agent_type");
    }

    @Test
    @DisplayName("enforcement 5s 内: 未调 StructuredOutput -> 仍 blocking 重入（CC hookHelpers.ts:70-83）")
    void enforcementHook_within5s_stillEnforces() {
        // WHY: CC registerStructuredOutputEnforcement 注册 Stop 函数 hook, callback 返回 false → blocking 重入
        // loop（注入 "You MUST call StructuredOutput"）。5s 内未满足条件必须继续强制。
        UUID hookAgentId = UUID.randomUUID();
        com.nexusai.application.agent.AgentState state =
            new com.nexusai.application.agent.AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), hookAgentId);
        state.appendMessage(assistantWithToolCall("toolu-read", "Read"));  // 未调 StructuredOutput
        HookEvent event = HookEvent.stop("sess", hookAgentId.toString(), false, null);

        GenericHook.HookResult r = new StructuredOutputEnforcementHook(hookAgentId, state).onEvent(event);

        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError())
            .contains(StructuredOutputEnforcementHook.ENFORCEMENT_PROMPT);
    }

    @Test
    @DisplayName("enforcement 无阻断次数上限: 持续强制直到成功/外层兜底（CC hookHelpers.ts:70-83 无 attempt cap）")
    void enforcementHook_noAttemptCap_keepsEnforcing() {
        // WHY (SURPLUS-1): CC registerStructuredOutputEnforcement（hookHelpers.ts:70-83）无阻断次数上限，
        //   仅 {timeout:5000} 单次 callback 超时；callback 返回 false 必须持续注入强制提示重入。
        //   原 Java 独有 MAX_BLOCKING_ATTEMPTS=5 兜底已删除（DEL-PROBE 裁决: CC 无此能力）。loop 重入
        //   有界性由外层 ExecAgentHook.MAX_AGENT_TURNS=50 计数器 + 60s 整体超时承担（execAgentHook.ts:197-207），
        //   enforcement hook 自身不得早退放行 —— 超过旧上限后仍须强制，否则未成功就放行 = 对齐偏移。
        UUID hookAgentId = UUID.randomUUID();
        com.nexusai.application.agent.AgentState state =
            new com.nexusai.application.agent.AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), hookAgentId);
        state.appendMessage(assistantWithToolCall("toolu-read", "Read"));  // 仍未调 StructuredOutput
        HookEvent event = HookEvent.stop("sess", hookAgentId.toString(), false, null);

        StructuredOutputEnforcementHook hook = new StructuredOutputEnforcementHook(hookAgentId, state);
        for (int i = 1; i <= 10; i++) {  // 远超旧上限 5: 第 6..10 次仍必须强制重入
            GenericHook.HookResult r = hook.onEvent(event);
            assertThat(r.blockingError())
                .as("第 %d 次阻断（超过旧上限 5）仍必须强制重入（无次数上限）", i)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("CALL_TIMEOUT_MS 常量 = 5000（对齐 CC hookHelpers.ts:81 {timeout:5000}）")
    void enforcementHook_callTimeoutConstant_matchesCc() {
        assertThat(StructuredOutputEnforcementHook.CALL_TIMEOUT_MS).isEqualTo(5000L);
    }


    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S2] CCJ-EXEC-16 TAIL/工具 prompt / E8 监听器清理 / CCJ-EXEC-08 thinkingConfig
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[CCJ-EXEC-16] systemPrompt 无强制调用句（指令载体迁移至工具 prompt）")
    void execAgentHook_systemPrompt_noForcedCallSentence() {
        // WHY: CC execAgentHook.ts:107-116 systemPrompt 无 'You MUST call ... exactly once' 句；
        //   强制指令在 createStructuredOutputTool.prompt()（hookHelpers.ts:60-62）。
        //   旧 Java SYSTEM_PROMPT_TAIL 尾部附加句（ExecAgentHook.java:156-157）已删除。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(p.capturedSystemPrompt.get())
            .as("CCJ-EXEC-16: systemPrompt 不得含旧 TAIL 强制调用句")
            .doesNotContain("You MUST call the StructuredOutput tool exactly once")
            .contains("Use the available tools to inspect the codebase");
    }

    @Test
    @DisplayName("[CCJ-EXEC-16] StructuredOutput 工具 description 携带 CC hookHelpers.ts:60-62 prompt 文本")
    void execAgentHook_toolDescription_carriesCcPromptText() {
        // WHY: ToolRegistry.toOpenAiToolsArray 序列化 description = prompt() ?? description()
        //   （api.ts:171 同语义）；hook 专用 SyntheticOutputTool.prompt() = hookHelpers.ts:60-62
        //   逐字文本 —— LLM 在工具 schema 中看到强制调用指令（CC 载体位置）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        exec(h, "verify", "{}", null);

        assertThat(p.capturedTools.get()).isNotNull();
        String structuredDescription = null;
        for (com.fasterxml.jackson.databind.JsonNode tool : p.capturedTools.get()) {
            String name = tool.path("function").path("name").asText("");
            if (SO.equals(name)) {
                structuredDescription = tool.path("function").path("description").asText(null);
            }
        }
        assertThat(structuredDescription)
            .as("hook StructuredOutput 工具 description = CC hookHelpers.ts:60-62 逐字文本")
            .contains("Use this tool to return your verification result.")
            .contains("You MUST call this tool exactly once at the end of your response.");
    }

    @Test
    @DisplayName("[E8/CCJ-EXEC-06] exec 结束后父 abort 上不残留监听器（CC removeEventListener 两路径）")
    void execAgentHook_parentAbortListenerRemoved() {
        com.nexusai.application.agent.tool.AbortController parent =
            new com.nexusai.application.agent.tool.AbortController();
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        AgentHook hook = new AgentHook("verify", null, null, null, null, null);
        h.exec(hook, HOOK_NAME, hookEvent, "{}", null, parent, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);

        assertThat(parent.listenerCount())
            .as("CC execAgentHook.ts:229-230/:305-306 removeEventListener 必须移除父监听器（防累积）")
            .isZero();
    }

    @Test
    @DisplayName("[CCJ-EXEC-08] thinkingConfig=disabled 到达 provider（hook agent 请求显式关闭思考）")
    void execAgentHook_thinkingConfigDisabled_reachesProvider() {
        // WHY: CC execAgentHook.ts:134 thinkingConfig:{type:'disabled'} 注入 agentToolUseContext.options
        //   → query.ts:662 options.thinkingConfig → provider 请求。旧 Java loop 无该机制（H13-GAP-2）。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true, null))),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(p.capturedThinkingType.get())
            .as("hook agent 请求必须携带 thinkingConfig disabled（CC execAgentHook.ts:134）")
            .isEqualTo("disabled");
    }

    @Test
    @DisplayName("[CCJ-EXEC-12] structured_output reason:null → schema 校验失败跳过 → cancelled（CC :216 if(parsed.success)）")
    void execAgentHook_reasonNull_structuredOutputSkipped() {
        // WHY: hookHelpers.ts:16-24 zod z.string().optional() 拒绝 null → safeParse 失败 →
        //   continue loop（CC :216-226）→ 无合法 structured output → cancelled（CC :254-267）。
        //   旧 Java isNull() 放行 → 按 ok 分流（reason:null + ok:true → success），语义偏移。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCallWithNullReason())),
            stopText("done")
        ));
        ExecAgentHook h = hookWith(p);

        HookResult r = exec(h, "verify", "{}", null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.CANCELLED);
    }

    /** structured_output 携带 reason:null（zod optional 拒绝 null · CCJ-EXEC-12）. */
    private ToolUseBlock structuredCallWithNullReason() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("ok", true);
        input.putNull("reason");
        return new ToolUseBlock("toolu-struct-nullreason-" + System.nanoTime(), SO, input);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具 helper
    // ════════════════════════════════════════════════════════════════════════

    /** assistant message 含一个 tool_call（对齐 CC assistant.message.content tool_use block）. */
    private static com.nexusai.model.session.dto.ChatMessageDto assistantWithToolCall(String toolUseId, String toolName) {
        com.nexusai.model.session.dto.ToolCallDto tc =
            new com.nexusai.model.session.dto.ToolCallDto(toolUseId, toolName, "{}", null, null);
        return new com.nexusai.model.session.dto.ChatMessageDto(
            UUID.randomUUID().toString(), null, com.nexusai.model.session.dto.Role.assistant, null,
            "", null, List.of(tc), null, null, null, null, java.time.OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
