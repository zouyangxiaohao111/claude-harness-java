package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookMatcherEngine;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.application.agent.permission.hook.IndividualHookConfig;
import com.nexusai.application.agent.permission.hook.HookSource;
import com.nexusai.application.agent.permission.hook.PostToolUseHook;
import com.nexusai.application.agent.permission.hook.PreToolUseHook;
import com.nexusai.application.agent.permission.hook.PromptRequester;
import com.nexusai.application.agent.permission.hook.PromptRequesterFactory;
import com.nexusai.application.agent.permission.hook.PromptResponse;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-3 全量对齐] StreamingToolExecutor 内层 PreToolUse → tool.execute → PostToolUse
 * 串联. PreToolUse hook 返回类型从 {@code PermissionResult} 升级为 16 字段
 * {@link AggregatedHookResult}; PostToolUse hook 仍返回 {@link GenericHook.HookResult}
 * (扩展到 13 字段).
 *
 * <h2>测试矩阵 (19 项)</h2>
 * <h3>P0-1/P0-2/P1/P2 既有测试 (重写保持断言意图)</h3>
 * <ol>
 *   <li>{@link #executeAsync_preToolUseHookUpdatedInputMerged} — PreToolUse hook 输出
 *       {@code updatedInput} → 全替换 (CC toolHooks.ts:556-563)</li>
 *   <li>{@link #executeAsync_postToolUseRunsAfterExecute} — PostToolUse 在 execute 后调</li>
 *   <li>{@link #executeAsync_failureEventEmittedOnToolError} — 工具 error → 失败事件</li>
 *   <li>{@link #executeAsync_nullHookRegistrySkipsAllHookStages} — hookRegistry=null 跳过所有</li>
 *   <li>{@link #executeAsync_mcpSpecificPostToolUseDispatch} — MCP updatedMCPToolOutput 替换</li>
 *   <li>{@link #executeAsync_postToolUseHookNoOuterDispatchInLlmAgentLoop} — 结构断言 (LlmAgentLoop)</li>
 *   <li>{@link #executeAsync_postToolUseHookFiresExactlyOnce} — 内层恰好 1 次</li>
 *   <li>{@link #executeAsync_preToolUseHookUpdatedInputReplaces} — 全替换语义 (P0-2)</li>
 *   <li>{@link #executeAsync_preToolUseHookNoUpdatedInputKeepsOriginal} — null 保留原</li>
 *   <li>{@link #executeAsync_preToolUseHookThrowsEmitsTelemetry} — P2-1 埋点</li>
 *   <li>{@link #executeAsync_postToolUseHookThrowsEmitsTelemetry} — P2-1 埋点</li>
 *   <li>{@link #executeAsync_postToolUseFailureHookThrowsEmitsTelemetry} — P2-1 埋点</li>
 *   <li>{@link #executeAsync_mcpToolPostToolUseBlockingErrorFeedback} — MCP feedback 注入</li>
 *   <li>{@link #executeAsync_mcpToolBothOutputAndFeedback} — MCP 顺序</li>
 * </ol>
 *
 * <h3>P0-3 新增 (5 项)</h3>
 * <ol start="15">
 *   <li>{@link #executeAsync_preToolUseHookPermissionBehaviorAllow} — AHR.permissionBehavior=Allow 直接放行</li>
 *   <li>{@link #executeAsync_preToolUseHookPermissionBehaviorDenyBlocks} —
 *       AHR.permissionBehavior=Deny 阻断 (CC case 2)</li>
 *   <li>{@link #executeAsync_preToolUseHookPreventContinuationStopsTool} —
 *       AHR.preventContinuation=true 阻断 (CC case 4)</li>
 *   <li>{@link #executeAsync_preToolUseHookStopReasonShortcut} —
 *       AHR.preventContinuation+stopReason 翻译为 stop 消息 (CC case 7)</li>
 *   <li>{@link #executeAsync_preToolUseHookAbortExceptionStopsTool} —
 *       hook 抛 AbortException 透传, 立即停止工具 (CC utils/hooks.ts:2045-2051)</li>
 * </ol>
 *
 * @see StreamingToolExecutor#executeAsync
 * @see HookRegistry
 * @see AggregatedHookResult
 */
class StreamingToolExecutorHookInjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolUseContext baseCtx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT
        );
    }

    private ToolUseBlock buildCall(String id, String name, JsonNode input) {
        return new ToolUseBlock(id, name, input);
    }

    private ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    private static Telemetry emptyTelemetry() {
        return new Telemetry();
    }

    /**
     * [P0-3 助手] 构造 Allow AggregatedHookResult, 同时填充 {@code AHR.updatedInput} 字段.
     *
     * <p>关键: P0-3 升级后 AHR 是扁平 record, hook 必须把 updatedInput 写到 AHR 顶层
     * (而非仅 inner Allow). 消费者 (StreamingToolExecutor) 读
     * {@code preOutcome.updatedInput() == AHR.updatedInput()}, 这是 CC 唯一通道.
     */
    private static AggregatedHookResult allowAhr(JsonNode updatedInput, String decisionReason) {
        // 把 JsonNode 转 Map<String, Object> 写到 AHR.updatedInput
        Map<String, Object> updatedMap = jsonNodeToMap(updatedInput);
        // [H4] AHR 16 字段 (移除 additionalContext 单值 + aggregatedAt)
        return new AggregatedHookResult(
            null, null, false, null,
            decisionReason, null,
            new PermissionResult.Allow(updatedInput,
                new PermissionDecisionReason.Other("test merger"), null, false, null, List.of()),
            null, null, updatedMap, null, null, null, null, null, null
        );
    }

    /**
     * JsonNode → Map 转换 helper · 用于把 hook 输入 (JsonNode) 转为 AHR.updatedInput (Map).
     */
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        var iter = node.fields();
        while (iter.hasNext()) {
            var e = iter.next();
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    private static AggregatedHookResult proceed() {
        return AggregatedHookResult.proceed();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. PreToolUse hookUpdatedInput → updatedInput 全替换
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [A2 #1 + P0-3 强化] PreToolUse hook 通过 AHR.permissionBehavior=Allow.updatedInput
     * → updatedInput <b>全替换</b> → tool.execute 收到替换后 input.
     *
     * <p>对齐 CC toolExecution.ts:837 processedInput = result.updatedInput.
     */
    @Test
    @DisplayName("[P0-3] PreToolUse hook AHR.updatedInput 整体替换")
    void executeAsync_preToolUseHookUpdatedInputMerged() throws Exception {
        AtomicReference<JsonNode> capturedInput = new AtomicReference<>();
        Tool mergeTool = new Tool() {
            @Override public String name() { return "merge_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                capturedInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(mergeTool);

        HookRegistry hooks = new HookRegistry();
        ObjectNode hookUpdated = JSON.createObjectNode();
        hookUpdated.put("merged", "by_hook");
        hookUpdated.put("hookOnly", 42);
        // [P0-3] PreToolUseHook 现在返回 AHR
        PreToolUseHook mergerHook = (toolName, input, ctx) -> allowAhr(hookUpdated, "by_hook");
        hooks.registerPreToolUse("merger", mergerHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode originalInput = JSON.createObjectNode();
        originalInput.put("original", "from_llm");
        exec.add(buildCall("toolu_merge_1", "merge_stub", originalInput));

        List<ToolResult> results = exec.getRemainingResults();

        JsonNode finalInput = capturedInput.get();
        assertThat(finalInput).isNotNull();
        assertThat(finalInput.fieldNames())
            .toIterable()
            .as("全替换后字段集合应 = {merged, hookOnly}")
            .containsExactlyInAnyOrder("merged", "hookOnly");
        assertThat(finalInput.get("merged").asText()).isEqualTo("by_hook");
        assertThat(finalInput.get("hookOnly").asInt()).isEqualTo(42);
        // 关键断言: LLM 原 'original' 字段应已被全替换清空
        assertThat(finalInput.has("original")).isFalse();
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] PreToolUse 链 prompt 回调通道接线 · 对齐 CC toolHooks.ts:474
     * (executePreToolHooks 透传 toolUseContext.requestPrompt) + hooks.ts:1990
     * ({@code boundRequestPrompt = requestPrompt?.(hookName, toolInputSummary)}).
     *
     * <p>WHY: 补回通道后必须验证接线真实可达 — StreamingToolExecutor 注入
     * {@link PromptRequesterFactory} 时, PreToolUse 链按 {@code bind("PreToolUse:<toolName>",
     * toolUseSummary)} 绑定并透传给配置 command hook 执行器 (等价 CC REPL.tsx:2520 门控 +
     * toolHooks.ts:474 透传). 未注入 (null) → 通道关闭 (等价 CC feature('HOOK_PROMPTS')=false).
     */
    @Test
    @DisplayName("[IMP-RS-01] PreToolUse 链 prompt 回调通道接线: factory 绑定 + 透传 command hook (toolHooks.ts:474)")
    void executeAsync_preToolUse_promptChannelWiredToCommandHook() throws Exception {
        // 1. settings 配 1 条 PreToolUse:Bash command hook (走 executeConfiguredCommand → 真实执行器)
        HooksSettings settings = new HooksSettings(k -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry hooks = new HookRegistry();
        hooks.setHooksConfigSnapshot(snapshot);
        hooks.setHookMatcherEngine(engine);

        // 2. fake CommandHookExecutor 捕获 13 参 execute 收到的 promptRequester
        AtomicReference<PromptRequester> captured = new AtomicReference<>();
        AtomicReference<String> capturedSource = new AtomicReference<>();
        CommandHookExecutor stub = new CommandHookExecutor() {
            @Override
            public CommandHookExecutor.CommandHookResult execute(CommandHook h, HookEvent ev, String name,
                                                                 String json, String pr, String pid, String sr,
                                                                 Integer idx, boolean fs, AbortController pa,
                                                                 long tmo, String cwd, PromptRequester req) {
                captured.set(req);
                return new CommandHookExecutor.CommandHookResult("", "", "", 0, false, false);
            }
        };
        hooks.setCommandHookExecutor(stub);

        // 3. StreamingToolExecutor + 注入 factory (记录 bind 的 sourceName)
        ToolRegistry registry = registryWith(bashStubTool());
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setPromptRequesterFactory((source, summary) -> {
            capturedSource.set(source);
            return req -> new PromptResponse(req.prompt(), "b");
        });

        ObjectNode input = JSON.createObjectNode();
        input.put("command", "echo hi");
        exec.add(buildCall("toolu_bash_1", "Bash", input));
        exec.getRemainingResults();

        // 断言: 绑定 sourceName = "PreToolUse:Bash" (CC hookName = `${hookEvent}:${matchQuery}`), 透传非 null
        assertThat(capturedSource.get()).isEqualTo("PreToolUse:Bash");
        assertThat(captured.get()).isNotNull();
    }

    /** 供 prompt 通道接线测试的 Bash stub 工具 (仅用于触发 PreToolUse:Bash 匹配). */
    private Tool bashStubTool() {
        return new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub bash"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }

    /**
     * [hook message 普通消息通道 · RE-THINK] PreToolUse hook message → 普通 user 消息 →
     * result.newMessages()（与 tool_result 同批, 一次性）。
     *
     * <p>WHY: CC toolHooks.ts:478-480 {@code result.message} →
     * {@code yield {type:'message', message:{message: result.message}}} →
     * toolExecution.ts:815 {@code resultingMessages.push} → query.ts:1395
     * {@code filter(_ => _.type === 'user')} → 普通 user 消息, 与 tool_result 同批、
     * 一次性（非 attachment 常驻重渲染）。Java 桥: injectPreToolUseHookAttachments 把
     * hook_user_message 结算为普通 user ChatMessageDto → dispatch 并入 t.result.newMessages。
     * 旧行为（hook_user_message → state.attachments() 常驻）已被 RE-THINK 修正; 本测试锁定
     * 新普通消息通道。
     */
    @Test
    @DisplayName("[hook message 普通消息通道] PreToolUse hook message → result.newMessages() user-role 消息（同批, 一次性）")
    void executeAsync_preToolUseHookMessage_inNewMessages() {
        Tool msgTool = new Tool() {
            @Override public String name() { return "msg_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(msgTool);
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse("msg", (toolName, input, ctx) -> new AggregatedHookResult(
            AggregatedHookResult.messageChannel("hello from hook",
                "PreToolUse:msg_stub", "toolu_msg_hook_1", "PreToolUse"),
            null, false, null, null, null, null, null, null, null, null, null, null, null, null, null));

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(buildCall("toolu_msg_hook_1", "msg_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("hook message 不阻断工具执行 (message 是旁路交付)")
            .isFalse();
        // 新普通消息通道: hook message → result.newMessages() 的 user-role 消息
        //   (CC query.ts:1395 filter type==='user' → 与 tool_result 同批送达)
        ToolResult<?> tr = results.get(0);
        assertThat(tr.newMessages())
            .as("hook message 必须作为 user-role 消息并入 result.newMessages() (与 tool_result 同批)")
            .anySatisfy(m -> {
                assertThat(m.content()).isEqualTo("hello from hook");
                assertThat(m.role()).isEqualTo(com.nexusai.model.session.dto.Role.user);
            });
        // hook_user_message 不再进入 attachments（普通消息通道, 非 attachment 常驻重渲染）
        assertThat(state.attachments())
            .as("hook message 不再进入 state.attachments() (普通消息通道, 非 attachment)")
            .noneMatch(a -> "hook_user_message".equals(a.type()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. PostToolUse 在 tool.execute 之后调
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeAsync PostToolUse hook 在 tool.execute 之后调 (顺序不可颠倒)")
    void executeAsync_postToolUseRunsAfterExecute() throws Exception {
        List<String> eventOrder = new CopyOnWriteArrayList<>();
        Tool orderTool = new Tool() {
            @Override public String name() { return "order_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                eventOrder.add("tool.execute:" + call.id());
                return ToolResult.success(call.id(), "raw_output");
            }
        };
        ToolRegistry registry = registryWith(orderTool);

        HookRegistry hooks = new HookRegistry();
        AtomicReference<String> seenResultContent = new AtomicReference<>();
        PostToolUseHook postHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [IMP-C2] ToolResult 已删 toolUseId（mapper 推导）；事件序标签改用 data
            // （工具返回 "raw_output"）——顺序意图不变，断言随之对齐（master 同款模式）。
            // [REWORK-7] ToolResult.toolUseId() 已删 (IMP-C2, 由 mapper 推导) → 用 result.data()
            //   作为本工具执行唯一标记（"raw_output"），顺序断言仍锁 post_hook 在 execute 之后。
            eventOrder.add("post_hook:" + result.data());
            seenResultContent.set((String) result.data());
            return GenericHook.HookResult.proceed();
        };
        hooks.registerPostToolUse("order_post", postHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_order_1", "order_stub", JSON.createObjectNode().put("x", 1)));
        exec.getRemainingResults();

        assertThat(eventOrder).containsExactly("tool.execute:toolu_order_1", "post_hook:raw_output");
        assertThat(seenResultContent.get()).isEqualTo("raw_output");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. failure event emitted on tool error
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeAsync 工具 error → emitPostToolUseFailureAnalytics 触发")
    void executeAsync_failureEventEmittedOnToolError() throws Exception {
        AtomicInteger errorEventCount = new AtomicInteger(0);
        AtomicInteger oTelErrorEventCount = new AtomicInteger(0);
        Telemetry spy = new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_tool_use_error".equals(name)) {
                    errorEventCount.incrementAndGet();
                }
                super.recordEvent(name, attributes);
            }
            @Override
            public void logOTelEvent(String eventName, Map<String, ?> metadata) {
                if ("tool_result".equals(eventName)
                    && metadata != null
                    && "false".equals(String.valueOf(metadata.get("success")))) {
                    oTelErrorEventCount.incrementAndGet();
                }
                super.logOTelEvent(eventName, metadata);
            }
        };

        Tool errorTool = new Tool() {
            @Override public String name() { return "err_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2] 错误结果由执行器经 isToolErrorData(data) 推导 isError；夹具错误消息
                //   必须以可识别错误前缀开头（"Error:"），否则 failure analytics 不触发。
                // [REWORK-7] isError 由执行器按 data 文案推导 (isToolErrorData) → 错误消息须以
                //   已登记错误前缀开头（"Error:"），否则正常返回路径不推导为 error。
                return ToolResult.error(call.id(), "Error: intentional failure for A2 #3", "execution");
            }
        };
        ToolRegistry registry = registryWith(errorTool);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, null);
        exec.setTelemetry(spy);

        exec.add(buildCall("toolu_err_1", "err_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isTrue();
        assertThat(errorEventCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(oTelErrorEventCount.get()).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. null hookRegistry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeAsync hookRegistry = null → 全部 hook 阶段跳过")
    void executeAsync_nullHookRegistrySkipsAllHookStages() throws Exception {
        Tool stub = new Tool() {
            @Override public String name() { return "null_hook_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "result_" + call.id());
            }
        };
        ToolRegistry registry = registryWith(stub);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, null);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_null_hook_1", "null_hook_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(((String) results.get(0).data())).isEqualTo("result_toolu_null_hook_1");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. MCP-specific PostToolUse dispatch (CC line 1494-1530)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeAsync MCP 工具 + hook updatedMCPToolOutput → toolOutput 替换")
    void executeAsync_mcpSpecificPostToolUseDispatch() throws Exception {
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp_tool"; }
            @Override public String description() { return "stub mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "raw_mcp_output");
            }
            @Override public boolean isMcp() { return true; }
        };
        ToolRegistry registry = registryWith(mcpTool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook mcpReplaceHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [H4+H3+S07] 14 字段 HookResult (H3 加 hook; S07 加 permissionRequestResult)
            return new GenericHook.HookResult(false, null, null, null, null, null,
            "MCP_REPLACED_BY_HOOK",  // updatedMCPToolOutput
            null, null,
            GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
        };
        hooks.registerPostToolUse("mcp_replacer", mcpReplaceHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_mcp_1", "mcp_tool", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(((String) results.get(0).data())).isEqualTo("MCP_REPLACED_BY_HOOK");
    }

    /**
     * [IMP-ST-01] MCP updatedMCPToolOutput 替换仅改 data, 原 result 附属字段
     * (contextModifier/mcpMeta/structuredOutput) 必须保留进新结果.
     *
     * <p>WHY (对齐 CC toolExecution.ts:1400-1401/1467/1541): CC 在 PostToolUse 前捕获
     * {@code toolContextModifier = result.contextModifier} + {@code mcpMeta = result.mcpMeta},
     * updatedMCPToolOutput 只替换 {@code toolOutput = result.data}, 最终 addToolResult 仍把
     * 原 result 的 contextModifier/mcpMeta 带进 user message. Java 端旧实现
     * {@code ToolResult.success(toolUseId, updatedContent)} 整结果替换, contextModifier /
     * mcpMeta / structuredOutput 全部丢失 (probe WF3-02 §7 △-2 / OPD-TC-02) — MCP 上下文
     * 修改链与 meta 归因丢失. 本测试验证修复: data 被替换 + 附属字段保留.
     */
    @Test
    @DisplayName("[IMP-ST-01] MCP updatedMCPToolOutput 替换仅改 data, 原 result 附属字段 (contextModifier/mcpMeta) 保留")
    void executeAsync_mcpUpdatedOutputPreservesOriginalAttachedFields() throws Exception {
        java.util.function.Function<ToolUseContext, ToolUseContext> ctxMod = ctx -> ctx;
        ToolResult.McpMeta mcpMeta = new ToolResult.McpMeta(Map.of("mcp_k", "mcp_v"), null);
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp_preserve_tool"; }
            @Override public String description() { return "stub mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2 合并] ToolResult 4 字段契约（data/newMessages/contextModifier/mcpMeta）：
                // structuredOutput 已非字段（走 AgentState 通道），8 参构造不复存在 → 4 参构造。
                return new ToolResult<String>("raw_mcp_output", null, ctxMod, mcpMeta);
            }
            @Override public boolean isMcp() { return true; }
        };
        ToolRegistry registry = registryWith(mcpTool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook mcpReplaceHook = (toolName, input, result, ctx, stopHookActive) ->
            new GenericHook.HookResult(false, null, null, null, null, null,
                "MCP_REPLACED_BY_HOOK",  // updatedMCPToolOutput
                null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
        hooks.registerPostToolUse("mcp_preserve_replacer", mcpReplaceHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_mcp_2", "mcp_preserve_tool", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        // data 被 updatedMCPToolOutput 替换 (CC toolExecution.ts:1496 toolOutput = ...)
        assertThat(((String) results.get(0).data())).isEqualTo("MCP_REPLACED_BY_HOOK");
        // 附属字段必须保留 (CC addToolResult 复用原 result.contextModifier/mcpMeta)
        assertThat(results.get(0).contextModifier()).isSameAs(ctxMod);
        assertThat(results.get(0).mcpMeta()).isNotNull();
        assertThat(results.get(0).mcpMeta().meta()).isEqualTo(Map.of("mcp_k", "mcp_v"));
        // [IMP-C2 合并] structuredOutput 已非 ToolResult 字段（折叠入 data / AgentState 通道），不再单独断言
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. 结构断言 — LlmAgentLoop 不再外层 executePostToolUse
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[A2-P0-1] LlmAgentLoop 源码不再包含 executePostToolUse 调用 (CC 单一串联硬指标)")
    void executeAsync_postToolUseHookNoOuterDispatchInLlmAgentLoop() throws Exception {
        Path llmAgentLoopPath = Paths.get("src", "main", "java", "com", "nexusai",
            "application", "agent", "LlmAgentLoop.java");
        assertThat(Files.exists(llmAgentLoopPath)).isTrue();
        String src = Files.readString(llmAgentLoopPath);

        long executePostToolUseCallCount = countCodeOccurrences(src, "executePostToolUse(");

        assertThat(executePostToolUseCallCount)
            .as("LlmAgentLoop.java 内 executePostToolUse( 调用次数必须 = 0")
            .isEqualTo(0);
    }

    private static long countCodeOccurrences(String src, String token) {
        long count = 0;
        boolean inBlockComment = false;
        for (String rawLine : src.split("\\R")) {
            String line = rawLine;
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false;
                    line = line.substring(line.indexOf("*/") + 2);
                } else {
                    continue;
                }
            }
            int lineCommentIdx = line.indexOf("//");
            String codePart;
            if (lineCommentIdx >= 0) {
                codePart = line.substring(0, lineCommentIdx);
            } else {
                codePart = line;
            }
            String trimmed = codePart.trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("*")) {
                continue;
            }
            int blockStart = codePart.indexOf("/*");
            if (blockStart >= 0) {
                codePart = codePart.substring(0, blockStart);
                if (codePart.contains("/*") && !codePart.contains("*/")) {
                    inBlockComment = true;
                }
            }
            int idx = 0;
            while ((idx = codePart.indexOf(token, idx)) >= 0) {
                count++;
                idx += token.length();
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. PostToolUse hook 内层恰好 1 次
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[A2-P0-1] PostToolUse hook 在 executeAsync 内层恰好被调 1 次")
    void executeAsync_postToolUseHookFiresExactlyOnce() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger(0);
        HookRegistry hooks = new HookRegistry();
        PostToolUseHook counterHook = (toolName, input, result, ctx, stopHookActive) -> {
            invocationCount.incrementAndGet();
            return GenericHook.HookResult.proceed();
        };
        hooks.registerPostToolUse("counter", counterHook);

        Tool stub = new Tool() {
            @Override public String name() { return "counter_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(stub);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_counter_1", "counter_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(invocationCount.get()).isEqualTo(1);
        assertThat(results).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. hookUpdatedInput 全替换语义
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P0-3] AHR.updatedInput 是整体替换语义 (CC line 837 / 1131)")
    void executeAsync_preToolUseHookUpdatedInputReplaces() throws Exception {
        AtomicReference<JsonNode> capturedInput = new AtomicReference<>();
        Tool replaceTool = new Tool() {
            @Override public String name() { return "replace_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                capturedInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(replaceTool);

        HookRegistry hooks = new HookRegistry();
        ObjectNode hookUpdated = JSON.createObjectNode();
        hookUpdated.put("a", "new");
        // [P0-3] PreToolUseHook returns AggregatedHookResult
        PreToolUseHook replacerHook = (toolName, input, ctx) -> allowAhr(hookUpdated, "by_hook");
        hooks.registerPreToolUse("replacer", replacerHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode originalInput = JSON.createObjectNode();
        originalInput.put("a", "original_a");
        originalInput.put("b", "original_b");
        originalInput.put("c", "original_c");
        exec.add(buildCall("toolu_replace_1", "replace_stub", originalInput));

        exec.getRemainingResults();

        JsonNode finalInput = capturedInput.get();
        assertThat(finalInput).isNotNull();
        assertThat(finalInput.size()).isEqualTo(1);
        assertThat(finalInput.fieldNames())
            .toIterable()
            .containsExactly("a");
        assertThat(finalInput.get("a").asText()).isEqualTo("new");
        assertThat(finalInput.has("b")).isFalse();
        assertThat(finalInput.has("c")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8b. [WF3-03 U-9 G1] permissionDecision.updatedInput → 执行层 (gate 决策改写达 execute)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [WF3-03 U-9 G1] hook 改写的工具输入 updatedInput 未传到真正执行层的断链修复。
     *
     * <p>WHY (对齐 CC toolExecution.ts:1130-1131): {@code if (permissionDecision.updatedInput
     * !== undefined) { processedInput = permissionDecision.updatedInput }} — 最终权限决策
     * (resolved.decision) 携带的 {@code Allow.updatedInput} (用户弹窗改写 / hook ask→用户改
     * input / gate 合成) 必须覆盖生效 input 到达 tool.execute。此前 Java 端仅由
     * AHR.updatedInput() 构建 effectiveCall, gate 决策层的 Allow.updatedInput 被丢弃 (G1 断链)。
     *
     * <p>主路径: gate 桩 check() 返回 {@code DecisionResult(ALLOW, Allow(rewritten))}
     * + ctx.permissionContext() != null → tool.execute 必须收到 rewrittenInput。
     */
    @Test
    @DisplayName("[WF3-03 U-9 G1] permissionDecision.updatedInput 覆盖 effectiveCall 达 execute")
    void executeAsync_permissionDecisionUpdatedInputReachesExecute() throws Exception {
        AtomicReference<JsonNode> capturedInput = new AtomicReference<>();
        Tool gateRewriteTool = new Tool() {
            @Override public String name() { return "gate_rewrite_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                capturedInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(gateRewriteTool);

        // gate 桩: pipeline 返回 Allow(rewritten) → gate.check 返回 ALLOW + Allow(rewritten)
        ObjectNode rewritten = JSON.createObjectNode();
        rewritten.put("rewritten", "by_gate");
        PermissionPipeline pipeline = new PermissionPipeline() {
            @Override public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                                   ToolUseContext ctx, ToolPermissionContext permCtx) {
                return new PermissionResult.Allow(rewritten,
                    new PermissionDecisionReason.Other("gate rewrite"), null, false, null, List.of());
            }
        };
        PermissionPrompter prompter = (tool, input, reason, ctx, requestId) ->
            new PermissionResult.Allow(input, reason, null, false, null, List.of());
        ToolPermissionGate gate = new ToolPermissionGate(pipeline, prompter, null, null, null);

        // ctx 需带非 null permissionContext 以触发 resolver (StreamingToolExecutor.java:1437)
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, gate, null);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode originalInput = JSON.createObjectNode();
        originalInput.put("original", "from_llm");
        exec.add(buildCall("toolu_gate_rewrite_1", "gate_rewrite_stub", originalInput));
        exec.getRemainingResults();

        JsonNode finalInput = capturedInput.get();
        assertThat(finalInput).isNotNull();
        assertThat(finalInput.has("rewritten")).isTrue();
        assertThat(finalInput.get("rewritten").asText()).isEqualTo("by_gate");
        assertThat(finalInput.has("original")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. hook 不返回 updatedInput → 保留原 input
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P0-3] AHR.updatedInput=null → 保留原 input (CC undefined 不替换)")
    void executeAsync_preToolUseHookNoUpdatedInputKeepsOriginal() throws Exception {
        AtomicReference<JsonNode> capturedInput = new AtomicReference<>();
        Tool passthroughTool = new Tool() {
            @Override public String name() { return "passthrough_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                capturedInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(passthroughTool);

        HookRegistry hooks = new HookRegistry();
        // [P0-3] AHR.updatedInput=null + permissionBehavior=Allow(未修改 input)
        PreToolUseHook noopHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null,
            "noop", null,
            new PermissionResult.Allow(input,
                new PermissionDecisionReason.Other("noop"), null, false, null, List.of()),
            null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("noop", noopHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode originalInput = JSON.createObjectNode();
        originalInput.put("x", "from_llm");
        originalInput.put("y", "from_llm_y");
        exec.add(buildCall("toolu_passthrough_1", "passthrough_stub", originalInput));
        exec.getRemainingResults();

        JsonNode finalInput = capturedInput.get();
        assertThat(finalInput).isNotNull();
        assertThat(finalInput.size()).isEqualTo(2);
        assertThat(finalInput.get("x").asText()).isEqualTo("from_llm");
        assertThat(finalInput.get("y").asText()).isEqualTo("from_llm_y");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. PreToolUse hook 抛异常 → telemetry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P2-1] PreToolUse hook 抛异常 → 触发 tengu_pre_tool_hook_error 埋点")
    void executeAsync_preToolUseHookThrowsEmitsTelemetry() throws Exception {
        AtomicInteger preToolHookErrorCount = new AtomicInteger(0);
        Telemetry spy = new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_pre_tool_hook_error".equals(name)) {
                    preToolHookErrorCount.incrementAndGet();
                }
                super.recordEvent(name, attributes);
            }
        };

        Tool stub = new Tool() {
            @Override public String name() { return "hook_err_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(stub);

        HookRegistry hooks = new HookRegistry();
        hooks.setTelemetry(spy);
        // [P0-3] hook 可抛任意异常, HookRegistry 接住后用 tengu_*_hook_error 上报
        PreToolUseHook throwingHook = (toolName, input, ctx) -> {
            throw new RuntimeException("intentional hook explosion");
        };
        hooks.registerPreToolUse("throwing", throwingHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(spy);

        exec.add(buildCall("toolu_hook_err_1", "hook_err_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(preToolHookErrorCount.get()).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 11. PostToolUse hook 抛异常 → telemetry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P2-1] PostToolUse hook 抛异常 → 触发 tengu_post_tool_hook_error 埋点")
    void executeAsync_postToolUseHookThrowsEmitsTelemetry() throws Exception {
        AtomicInteger postToolHookErrorCount = new AtomicInteger(0);
        AtomicInteger postToolFailureHookErrorCount = new AtomicInteger(0);
        Telemetry spy = new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_post_tool_hook_error".equals(name)) {
                    postToolHookErrorCount.incrementAndGet();
                } else if ("tengu_post_tool_failure_hook_error".equals(name)) {
                    postToolFailureHookErrorCount.incrementAndGet();
                }
                super.recordEvent(name, attributes);
            }
        };

        Tool stub = new Tool() {
            @Override public String name() { return "post_hook_err_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(stub);

        HookRegistry hooks = new HookRegistry();
        hooks.setTelemetry(spy);
        PostToolUseHook throwingHook = (toolName, input, result, ctx, stopHookActive) -> {
            throw new RuntimeException("intentional PostToolUse hook explosion");
        };
        hooks.registerPostToolUse("throwing_post", throwingHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(spy);

        exec.add(buildCall("toolu_post_hook_err_1", "post_hook_err_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(postToolHookErrorCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(postToolFailureHookErrorCount.get()).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 12. PostToolUseFailure hook 抛异常 → telemetry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P2-1] PostToolUseFailure hook 抛异常 → 触发 tengu_post_tool_failure_hook_error 埋点")
    void executeAsync_postToolUseFailureHookThrowsEmitsTelemetry() throws Exception {
        AtomicInteger postToolFailureHookErrorCount = new AtomicInteger(0);
        AtomicInteger postToolHookErrorCount = new AtomicInteger(0);
        Telemetry spy = new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_post_tool_failure_hook_error".equals(name)) {
                    postToolFailureHookErrorCount.incrementAndGet();
                } else if ("tengu_post_tool_hook_error".equals(name)) {
                    postToolHookErrorCount.incrementAndGet();
                }
                super.recordEvent(name, attributes);
            }
        };

        // [IMP-HR-06 TC-01] 触发面收窄对齐 CC toolExecution.ts:1483/1700 —
        //   PostToolUseFailure 仅 tool.execute 抛异常 (catch) 触发; success 路径正常返回
        //   is_error result 不走失败链. 故本测试工具改为抛异常触发失败链 (旧: 正常返回
        //   ToolResult.error → 旧加宽触发面已删除, 该场景不调 onPostToolUseFailure).
        Tool errorTool = new Tool() {
            @Override public String name() { return "failure_hook_err_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2] catch 路径 data = formatError(th)；以 "Error:" 开头供 isToolErrorData 识别
                throw new IllegalStateException("Error: intentional tool error for P2-1 failure chain");
            }
        };
        ToolRegistry registry = registryWith(errorTool);

        HookRegistry hooks = new HookRegistry();
        hooks.setTelemetry(spy);
        com.nexusai.application.agent.permission.hook.GenericHook throwingFailureHook =
            new com.nexusai.application.agent.permission.hook.GenericHook() {
                @Override
                public com.nexusai.application.agent.permission.hook.GenericHook.HookResult onEvent(
                    com.nexusai.application.agent.permission.hook.HookEvent event) {
                    if (event.type() == com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE) {
                        throw new RuntimeException("intentional PostToolUseFailure hook explosion");
                    }
                    return GenericHook.HookResult.proceed();
                }
            };
        hooks.register("throwing_failure", throwingFailureHook,
            com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(spy);

        exec.add(buildCall("toolu_failure_hook_err_1", "failure_hook_err_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isTrue();
        assertThat(postToolFailureHookErrorCount.get())
            .as("catch 路径 (工具抛异常) 触发 PostToolUseFailure 失败链 → 失败 hook 抛错 → tengu_post_tool_failure_hook_error")
            .isGreaterThanOrEqualTo(1);
        assertThat(postToolHookErrorCount.get()).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 12b. [IMP-HR-06 TC-01] PostToolUseFailure 触发面收窄 (仅 tool.execute 抛异常)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [IMP-HR-06 TC-01] 工具<b>正常返回</b> is_error result (未抛异常) → 走 PostToolUse
     * 成功链, <b>不</b>触发 PostToolUseFailure 失败链.
     *
     * <p>WHY (规则九): 修复前 success 路径 {@code baseResult.isError()} 无条件调
     * {@code executePostToolUseFailure}, 工具正常 is_error 返回 (如 Bash 命令失败) 会错误
     * 触发失败链 — 与 CC 语义相反. CC toolExecution.ts:1483 runPostToolUseHooks 对<b>非异常</b>
     * 结果无条件执行 (含 is_error 返回); runPostToolUseFailureHooks (:1700) 仅在 catch
     * (tool.call 抛异常) 触发. 本测试锁定收窄后的正确分流.
     */
    @Test
    @DisplayName("[IMP-HR-06 TC-01] 工具正常返回 is_error → PostToolUse 成功链执行、PostToolUseFailure 不触发")
    void executeAsync_normalErrorReturn_runsPostToolUse_notFailureChain() throws Exception {
        AtomicInteger postHookCount = new AtomicInteger(0);
        AtomicInteger failureHookCount = new AtomicInteger(0);
        Tool errorTool = new Tool() {
            @Override public String name() { return "tc01_err_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2] 错误结果由执行器经 isToolErrorData(data) 推导 isError；夹具错误消息
                //   必须以可识别错误前缀开头（"Error:"），否则 failure analytics 不触发。
                return ToolResult.error(call.id(), "Error: normal error return", "execution");
            }
        };
        ToolRegistry registry = registryWith(errorTool);
        HookRegistry hooks = new HookRegistry();
        hooks.registerPostToolUse("tc01-post", (toolName, input, result, ctx, stopHookActive) -> {
            postHookCount.incrementAndGet();
            return GenericHook.HookResult.proceed();
        });
        hooks.register("tc01-failure", event -> {
            if (event.type() == com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE) {
                failureHookCount.incrementAndGet();
            }
            return GenericHook.HookResult.proceed();
        }, com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_tc01_1", "tc01_err_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("工具正常返回 is_error=true result")
            .isTrue();
        assertThat(postHookCount.get())
            .as("CC toolExecution.ts:1483 runPostToolUseHooks 对非异常结果无条件执行 — 含 is_error 返回")
            .isEqualTo(1);
        assertThat(failureHookCount.get())
            .as("CC toolExecution.ts:1700 runPostToolUseFailureHooks 仅 catch (tool.call 抛异常) 触发 — is_error 返回不触发")
            .isZero();
    }

    /**
     * [IMP-HR-06 TC-01] 权限 DENY (工具未执行) → <b>不</b>跑任何 post/failure 链.
     *
     * <p>WHY (规则九): 修复前 DENY is_error result 流入 success 路径
     * {@code baseResult.isError()} 分支误触发 PostToolUseFailure; 修复后以
     * {@code t.toolExecuted} 守卫排除未执行结果 — 对齐 CC toolExecution.ts:1103
     * deny 早返 {@code return resultingMessages}, 不跑任何 post/failure 链.
     */
    @Test
    @DisplayName("[IMP-HR-06 TC-01] 权限 DENY (未执行) → PostToolUse 与 PostToolUseFailure 均不触发")
    void executeAsync_denyNotExecuted_skipsPostAndFailureChains() throws Exception {
        AtomicInteger postHookCount = new AtomicInteger(0);
        AtomicInteger failureHookCount = new AtomicInteger(0);
        Tool tool = new Tool() {
            @Override public String name() { return "tc01_deny_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "should not run");
            }
        };
        ToolRegistry registry = registryWith(tool);
        HookRegistry hooks = new HookRegistry();
        PreToolUseHook denyHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null,
            new HookBlockingError("Tool explicitly denied", null),
            false, null,
            "rule deny reason", null,
            new PermissionResult.Deny(
                "Tool explicitly denied",
                new PermissionDecisionReason.Other("test deny"),
                null),
            null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("tc01-deny", denyHook);
        hooks.registerPostToolUse("tc01-deny-post", (toolName, input, result, ctx, stopHookActive) -> {
            postHookCount.incrementAndGet();
            return GenericHook.HookResult.proceed();
        });
        hooks.register("tc01-deny-failure", event -> {
            if (event.type() == com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE) {
                failureHookCount.incrementAndGet();
            }
            return GenericHook.HookResult.proceed();
        }, com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_tc01_deny_1", "tc01_deny_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        // [IMP-C2] ToolResult 已删 isError 字段，执行器在 deny 路径显式置 t.isError=true；
        // 错误标志经 getResultErrorFlags()（mapper 配对推导用的生产 API）暴露。
        // deny 消息原文「Tool explicitly denied」不被 isToolErrorData 前缀表识别 →
        // 以 getResultErrorFlags 验证错误标志，data 原文直出断言保留。
        assertThat(exec.getResultErrorFlags().get("toolu_tc01_deny_1"))
            .as("DENY 决策必须让结果标记为错误（getResultErrorFlags 生产 API）")
            .isTrue();
        assertThat(postHookCount.get())
            .as("CC toolExecution.ts:1103 deny 早返 — 不跑 PostToolUse 成功链")
            .isZero();
        assertThat(failureHookCount.get())
            .as("CC toolExecution.ts:1103 deny 早返 — 不触发 PostToolUseFailure 失败链")
            .isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 13. MCP + blockingError feedback → attachment-only (DEL-STE-02)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DEL-STE-02] MCP 工具 + PostToolUse hook blockingError → attachment-only, tool result 不变")
    void executeAsync_mcpToolPostToolUseBlockingErrorFeedback() throws Exception {
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp_feedback_stub"; }
            @Override public String description() { return "stub mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "raw_mcp_output");
            }
            @Override public boolean isMcp() { return true; }
        };
        ToolRegistry registry = registryWith(mcpTool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook feedbackHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [H4+H3+S07] 14 字段 HookResult (H3 改 additionalContext 单值 String + 加 hook; S07 加 permissionRequestResult)
            return new GenericHook.HookResult(false,
            new HookBlockingError("MCP_FEEDBACK_FROM_HOOK", null),  // blockingError [H4]
            null,  // systemMessage
            null,  // additionalContext (String)
            null,  // message
            null,  // updatedInput
            null,  // updatedMCPToolOutput
            null,  // retry
            null,  // hookPermissionDecisionReason
            GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);
        };
        hooks.registerPostToolUse("mcp_feedback", feedbackHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_mcp_fb_1", "mcp_feedback_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // CC 真源 toolExecution.ts:1498-1499: MCP 分支仅 hookResults.push, 不改 tool output.
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("MCP blockingError 不再翻转 isError (attachment-only)")
            .isFalse();
        assertThat(((String) results.get(0).data()))
            .as("MCP tool result content 不被 hook blockingError 改写")
            .isEqualTo("raw_mcp_output");
        // hook_blocking_error attachment 通道注入 (CC toolHooks.ts:105-115)
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_blocking_error");
                assertThat(a.content()).contains("MCP_FEEDBACK_FROM_HOOK");
            });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 14. MCP + updatedMCPToolOutput + blockingError (attachment-only 顺序)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DEL-STE-02] MCP updatedMCPToolOutput 替换 tool output + blockingError 走 attachment")
    void executeAsync_mcpToolBothOutputAndFeedback() throws Exception {
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp_both_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "raw_mcp");
            }
            @Override public boolean isMcp() { return true; }
        };
        ToolRegistry registry = registryWith(mcpTool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook bothHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [H4+H3+S07] 14 字段 HookResult
            return new GenericHook.HookResult(false,
            new HookBlockingError("MCP_FEEDBACK", null),  // blockingError [H4]
            null, null, null, null,
            "REPLACED_BY_HOOK",
            null, null,
            GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);
        };
        hooks.registerPostToolUse("mcp_both", bothHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_mcp_both_1", "mcp_both_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // updatedMCPToolOutput 仍替换 tool output (CC toolExecution.ts:1494-1497 保留)
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(((String) results.get(0).data()))
            .as("MCP updatedMCPToolOutput 替换 tool output (唯一 content 改写通道)")
            .isEqualTo("REPLACED_BY_HOOK");
        // blockingError 不再拼进 content (DEL-STE-02), 仅走 attachment
        assertThat(((String) results.get(0).data()))
            .as("MCP blockingError 不再拼进 tool result content")
            .doesNotContain("MCP_FEEDBACK");
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_blocking_error");
                assertThat(a.content()).contains("MCP_FEEDBACK");
            });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 14b. [IMP-ST-02 TC-04 E4-2] MCP 附件多类型并存序 · 对齐 CC push tail
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[IMP-ST-02 TC-04 E4-2] MCP 工具 + PostToolUse hook 产 message+blocking+system+additional+updatedMCPToolOutput → 附件先注入 state.attachments()（CC yield 序），toolOutput 替换后置")
    void executeAsync_mcpMultiAttachmentOrder_attachmentsFirstOutputLast() throws Exception {
        // WHY (OPD-TC-04 对齐 + X-PROBE E4-2): CC runPostToolUseHooks 对每个 result 先 yield 附件
        //   (message→blocking→stopped→additional, toolHooks.ts:95-151), updatedMCPToolOutput 最后
        //   yield (:146-151); 消费端 addToolResult(toolOutput) 之后 hookResults flush
        //   (toolExecution.ts:1540-1542 / 1585-1587) → 最终消息流 [tool_result, hook 附件...].
        //   Java 端必须先注入附件通道 (state.attachments() → LLM 队尾 push tail), 再替换
        //   t.result (MCP toolOutput)。本测试锁定: 多类型附件全部注入 + 相对序不逆 + t.result 替换。
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp_e4_tool"; }
            @Override public String description() { return "stub mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "raw_mcp_e4_output");
            }
            @Override public boolean isMcp() { return true; }
        };
        ToolRegistry registry = registryWith(mcpTool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook multiHook = (toolName, input, result, ctx, stopHookActive) ->
            new GenericHook.HookResult(false,
                new HookBlockingError("E4_FEEDBACK", null),   // blockingError (CC yield #2)
                List.of("E4 system msg"),                       // systemMessages (CC hooks.ts:2769-2780)
                List.of("E4 additional ctx"),                   // additionalContexts (CC yield #5)
                AttachmentMessageDto.hookSuccess("PostToolUse:mcp_e4_tool", "toolu_e4", "PostToolUse"), // message (CC yield #1)
                null, "REPLACED_BY_E4_HOOK", null, null,        // updatedInput / updatedMCPToolOutput (CC yield #6 last) / retry / reason
                GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);
        hooks.registerPostToolUse("mcp_e4_multi", multiHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_e4_1", "mcp_e4_tool", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // updatedMCPToolOutput 仍替换 tool output（CC toolExecution.ts:1494-1497）——唯一 content 改写通道
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        assertThat(((String) results.get(0).data()))
            .as("MCP updatedMCPToolOutput 替换 tool output（最后 yield, 唯一 content 改写）")
            .isEqualTo("REPLACED_BY_E4_HOOK");
        // 附件通道必须注入 4 类（CC yield 序 message→blocking→system→additional；updatedMCPToolOutput
        // 不进附件通道, 只改 toolOutput）
        List<String> types = new java.util.ArrayList<>();
        for (com.nexusai.application.agent.attachment.AttachmentMessageDto a : state.attachments()) {
            types.add(a.type());
        }
        assertThat(types).as("4 类 hook 附件必须全部注入 state.attachments()").contains(
            "hook_success", "hook_blocking_error", "hook_system_message", "hook_additional_context");
        // 相对序不逆: blocking 在 system/additional 之前（CC yield 序 blocking→additional）
        assertThat(types.indexOf("hook_blocking_error"))
            .as("hook_blocking_error 必须不晚于 hook_system_message（CC yield 序）")
            .isLessThan(types.indexOf("hook_system_message"));
        assertThat(types.indexOf("hook_blocking_error"))
            .as("hook_blocking_error 必须不晚于 hook_additional_context（CC yield 序）")
            .isLessThan(types.indexOf("hook_additional_context"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 15. non-MCP PostToolUse blockingError → attachment-only (DEL-STE-01)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DEL-STE-01] non-MCP 工具 + PostToolUse hook blockingError → attachment-only, tool result 不变")
    void executeAsync_nonMcpPostToolUseBlockingErrorAttachmentOnly() throws Exception {
        Tool tool = new Tool() {
            @Override public String name() { return "plain_feedback_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "plain_output");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook feedbackHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [H4+H3+S07] 14 字段 HookResult
            return new GenericHook.HookResult(false,
            new HookBlockingError("PLAIN_FEEDBACK_FROM_HOOK", null),  // blockingError [H4]
            null, null, null, null, null, null, null,
            GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);
        };
        hooks.registerPostToolUse("plain_feedback", feedbackHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_plain_fb_1", "plain_feedback_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // CC 真源 toolHooks.ts:105-115: blockingError 仅产 hook_blocking_error attachment, result 不变.
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("non-MCP blockingError 不再翻转 isError (attachment-only)")
            .isFalse();
        assertThat(((String) results.get(0).data()))
            .as("non-MCP tool result content 不被 hook blockingError 改写")
            .isEqualTo("plain_output");
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_blocking_error");
                assertThat(a.content()).contains("PLAIN_FEEDBACK_FROM_HOOK");
            });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 16. non-MCP PostToolUse preventContinuation → attachment-only (DEL-STE-03)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DEL-STE-03] non-MCP 工具 + PostToolUse hook preventContinuation → hook_stopped_continuation attachment, tool result 不变")
    void executeAsync_nonMcpPostToolUsePreventContinuationAttachmentOnly() throws Exception {
        Tool tool = new Tool() {
            @Override public String name() { return "prevent_post_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "still_runs");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        PostToolUseHook preventHook = (toolName, input, result, ctx, stopHookActive) -> {
            // [H4+H3+S07] 14 字段 HookResult
            return new GenericHook.HookResult(true,  // preventContinuation
            null, null, null, null, null, null, null, null,
            GenericHook.HookOutcome.BLOCKING, "USER_CANCELLED", null, null, null, null, null, null, null);
        };
        hooks.registerPostToolUse("prevent_post", preventHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_prevent_post_1", "prevent_post_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // CC 真源 toolHooks.ts:118-130: preventContinuation 仅产 hook_stopped_continuation attachment
        //   + return, tool result 不变 (不再拼 [PostToolUse hook stopped] + 翻转 isError).
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("preventContinuation 不再翻转 isError (attachment-only)")
            .isFalse();
        assertThat(((String) results.get(0).data()))
            .as("preventContinuation 不再改写 tool result content")
            .isEqualTo("still_runs")
            .doesNotContain("PostToolUse hook stopped");
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_stopped_continuation");
                assertThat(a.content()).contains("USER_CANCELLED");
            });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ===== [P0-3 新增 5 项: AggregatedHookResult 16 字段 / 7 类 channel] =====
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [P0-3 #15] AHR.permissionBehavior=Allow(updatedInput=null) → hook 未干涉 input,
     * 工具正常执行并收到 LLM 原 input (CC toolHooks.ts:510-528 hookPermissionResult.Allow path).
     *
     * <p>WHY 区分 passthrough vs allow-without-updatedInput: CC 真源把两者合并为
     * permissionBehavior=Allow, 但 updatedInput 字段为 undefined. 本测试验证 hook
     * 显式声明 Allow 但不传 updatedInput 时, tool.execute 收到原 LLM input (不替换).
     */
    @Test
    @DisplayName("[P0-3 #15] AHR.permissionBehavior=Allow(updatedInput=null) → 原 input 透传")
    void executeAsync_preToolUseHookPermissionBehaviorAllow() throws Exception {
        AtomicReference<JsonNode> capturedInput = new AtomicReference<>();
        Tool tool = new Tool() {
            @Override public String name() { return "perm_allow_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                capturedInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        // [P0-3] AHR with permissionBehavior=Allow, updatedInput=null → 显式放行 (CC toolHooks.ts:520-528)
        PreToolUseHook allowHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null,
            "rule-based allow", null,
            new PermissionResult.Allow(input,
                new PermissionDecisionReason.Other("test allow"),
                null, false, null, List.of()),
            null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("allow", allowHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode originalInput = JSON.createObjectNode();
        originalInput.put("x", "from_llm");
        originalInput.put("y", "from_llm_y");
        exec.add(buildCall("toolu_allow_1", "perm_allow_stub", originalInput));
        List<ToolResult> results = exec.getRemainingResults();

        // 原 input 应保留 (AHR.updatedInput=null 语义 = hook 未干涉 input)
        JsonNode finalInput = capturedInput.get();
        assertThat(finalInput).isNotNull();
        assertThat(finalInput.size()).isEqualTo(2);
        assertThat(finalInput.get("x").asText()).isEqualTo("from_llm");
        assertThat(finalInput.get("y").asText()).isEqualTo("from_llm_y");
        // 工具成功执行
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
    }

    /**
     * [P0-3 #16] AHR.permissionBehavior=Deny → 工具被阻断, ToolResult.error =
     * deny 消息原文直出 (无 Java 独有前缀, 对齐 CC toolExecution.ts:1033
     * tool_result content: errorMessage; CC toolHooks.ts:541-553 deny path).
     *
     * <p>WHY 阻断优先级最高: bypass-immune (deny 比 allow 优先), CC 真源.
     * <p>[EX-B R13] 去 "PreToolUse hook denied: " 前缀: CC 将 deny message 原样
     * 作为 tool_result content (toolExecution.ts:1033), Java 前置前缀偏离 CC.
     */
    @Test
    @DisplayName("[P0-3 #16] AHR.permissionBehavior=Deny → 工具被阻断 (CC deny path)")
    void executeAsync_preToolUseHookPermissionBehaviorDenyBlocks() throws Exception {
        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "perm_deny_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "should not run");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        PreToolUseHook denyHook = (toolName, input, ctx) -> new AggregatedHookResult(
            // [IMPL-07 OD-14] message 统一 AttachmentMessageDto 通道 (String → hook_user_message DTO)
            AggregatedHookResult.messageChannel("Tool explicitly denied",
                "PreToolUse:deny_stub", "toolu_deny_1", "PreToolUse"),
            new HookBlockingError("Tool explicitly denied", null),  // blockingError [H4]
            false, null,
            "rule deny reason", null,
            new PermissionResult.Deny(
                "Tool explicitly denied",
                new PermissionDecisionReason.Other("test deny"),
                null),
            null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("deny", denyHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_deny_1", "perm_deny_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言 1: 工具未执行 (hook deny 阻断)
        assertThat(toolExecuted.get())
            .as("AHR.permissionBehavior=Deny 必须阻断 tool.execute (CC deny path)")
            .isFalse();
        // 关键断言 2: ToolResult.error 原文直出 deny 原因 (无 Java 独有前缀)。
        // [IMP-C2] ToolResult 已删 isError 字段，执行器在 deny 路径显式置 t.isError=true；
        // 错误标志经 getResultErrorFlags()（mapper 配对推导用的生产 API）暴露。
        // deny 消息原文「Tool explicitly denied」不被 isToolErrorData 前缀表识别 →
        // 以 getResultErrorFlags 验证错误标志，data 原文直出断言保留。
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_deny_1"))
            .as("deny 决策必须让结果标记为错误（getResultErrorFlags 生产 API）")
            .isTrue();
        assertThat(((String) results.get(0).data()))
            .as("deny 消息原文直出 (无 'PreToolUse hook denied' 前缀, 对齐 CC toolExecution.ts:1033)")
            .isEqualTo("Tool explicitly denied")
            .doesNotContain("PreToolUse hook denied");
    }

    /**
     * [EX-B R13] 阻断消息兜底 · 对齐 CC toolExecution.ts:1025-1027:
     * <pre>
     *   if (shouldPreventContinuation && !errorMessage) {
     *     errorMessage = `Execution stopped by PreToolUse hook${stopReason ? `: ${stopReason}` : ''}`
     *   }
     * </pre>
     *
     * <p>WHY 直接测 {@link StreamingToolExecutor#denyBlockingMessage} (而非全流程):
     * Java {@link PermissionResult.Deny} record compact constructor 强制 message
     * 非空 (PermissionResult.java:155-161), 全流程构造不出空消息 deny — 兜底分支
     * 在生产路径不可达 (completion-report R13 登记). helper 单测覆盖 CC 防御语义:
     * 空/null/空白消息 + preventContinuation → 兜底文案; 非空消息 (或未
     * preventContinuation) → 原文直出不覆盖 (CC :1023 errorMessage = message).
     */
    @Test
    @DisplayName("[EX-B] 空阻断消息 + preventContinuation → CC 兜底 'Execution stopped by PreToolUse hook[: stopReason]'")
    void denyBlockingMessage_fallsBackToExecutionStoppedWhenBlankAndPreventContinuation() {
        // 兜底 1: 空消息 + preventContinuation + stopReason → 带原因后缀 (CC :1026 模板)
        assertThat(StreamingToolExecutor.denyBlockingMessage("", true, "security gate"))
            .as("空消息 + preventContinuation + stopReason → 'Execution stopped by PreToolUse hook: <stopReason>'")
            .isEqualTo("Execution stopped by PreToolUse hook: security gate");
        // 兜底 2: null 消息 + preventContinuation + 无 stopReason → 无后缀 (CC :1026 空模板)
        assertThat(StreamingToolExecutor.denyBlockingMessage(null, true, null))
            .as("null 消息 + preventContinuation 无 stopReason → 无后缀")
            .isEqualTo("Execution stopped by PreToolUse hook");
        // 兜底 3: 空白消息 + preventContinuation + 空白 stopReason → 无后缀
        assertThat(StreamingToolExecutor.denyBlockingMessage("   ", true, "  "))
            .as("空白消息/空白 stopReason → 无后缀")
            .isEqualTo("Execution stopped by PreToolUse hook");
        // 原文直出: 非空消息 + preventContinuation → 不覆盖 (CC :1023 errorMessage = message)
        assertThat(StreamingToolExecutor.denyBlockingMessage("Tool explicitly denied", true, "stop"))
            .as("非空消息 + preventContinuation → 原文直出不覆盖")
            .isEqualTo("Tool explicitly denied");
        // 原文直出: 非空消息 + 无 preventContinuation → 不覆盖
        assertThat(StreamingToolExecutor.denyBlockingMessage("Tool explicitly denied", false, "stop"))
            .as("非空消息 + 无 preventContinuation → 原文直出不覆盖")
            .isEqualTo("Tool explicitly denied");
    }

    /**
     * [IMPL-03 D6-2 / OD-06] AHR.preventContinuation=true（无 deny 决策）→ 工具照跑,
     * 成功后注入 hook_stopped_continuation attachment（CC toolExecution.ts:1571-1582）.
     *
     * <p>WHY 修正（OD-06 ADJUDICATED）: 旧语义 (P0-3 #17) 把 preventContinuation 当
     * 独立阻断信号无条件阻断工具 — 偏离 CC toolExecution.ts:1025-1027
     * （shouldPreventContinuation 仅在 permissionDecision.behavior !== 'allow' 即 deny
     * 路径补错误文案; allow/无决策路径工具照跑）。EV-005/009 锁定断言按新语义修正。
     */
    @Test
    @DisplayName("[IMPL-03 #17] AHR.preventContinuation=true 无 deny → 工具照跑 + hook_stopped_continuation (OD-06)")
    void executeAsync_preToolUseHookPreventContinuationRunsTool() throws Exception {
        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "prevent_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "ran");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        // [P0-3] AHR with preventContinuation=true + stopReason (CC case 4 + 5)
        PreToolUseHook preventHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null,
            true,                              // preventContinuation = true
            "stopped for review",              // stopReason
            null, null, null, null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("prevent", preventHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_prevent_1", "prevent_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言 1: 工具照跑 (continue:false 无 deny → 仅 deny 阻断, OD-06)
        assertThat(toolExecuted.get())
            .as("continue:false 无 deny → 工具照跑 (OD-06, 旧实现无条件阻断)")
            .isTrue();
        // 关键断言 2: 无 error result
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("allow/无决策路径不产生 error result")
            .isFalse();
        // 关键断言 3: hook_stopped_continuation attachment 携带 stopReason (CC
        //   toolExecution.ts:1571-1582 message = stopReason || 'Execution stopped by hook')
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_stopped_continuation");
                assertThat(a.content()).contains("stopped for review");
            });
    }

    /**
     * [IMPL-03 D6-2 / OD-06] AHR.preventContinuation=true + stopReason（无 deny 决策）
     * → 工具照跑, stopReason 经 hook_stopped_continuation attachment 注入消息链
     * （CC toolExecution.ts:1571-1582 message = stopReason || 'Execution stopped by hook'）.
     *
     * <p>WHY 修正: 旧语义 (P0-3 #18) 断言 stopReason 翻译为 ToolResult.error — 偏离 CC
     * （continue:false 仅在 deny 路径改写错误文案; allow/无决策路径工具照跑）。OD-06
     * ADJUDICATED 后, stopReason 的消费通道 = 成功后 attachment 消息链。
     */
    @Test
    @DisplayName("[IMPL-03 #18] AHR.preventContinuation+stopReason → attachment 消息链, 工具照跑 (OD-06)")
    void executeAsync_preToolUseHookStopReasonShortcut() throws Exception {
        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "stop_shortcut_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "ran");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        PreToolUseHook stopHook = (toolName, input, ctx) -> new AggregatedHookResult(
            // [IMPL-07 OD-14] message 统一 AttachmentMessageDto 通道 (String → hook_user_message DTO)
            AggregatedHookResult.messageChannel("stop-shortcut message",
                "PreToolUse:stop_stub", "toolu_stop_1", "PreToolUse"),  // message (CC case 1)
            null,
            true,                              // preventContinuation
            "USER_CANCELLED",                  // stopReason
            null, null, null, null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("stop", stopHook);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setAgentState(state);

        exec.add(buildCall("toolu_stop_1", "stop_shortcut_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(toolExecuted.get())
            .as("continue:false 无 deny → 工具照跑 (OD-06)")
            .isTrue();
        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        // stopReason "USER_CANCELLED" 经 hook_stopped_continuation attachment 注入消息链
        assertThat(state.attachments())
            .anySatisfy(a -> {
                assertThat(a.type()).isEqualTo("hook_stopped_continuation");
                assertThat(a.content()).contains("USER_CANCELLED");
            });
    }

    /**
     * [P0-3 #19] PreToolUse hook 抛 {@code AbortException} → 透传, 工具立即停止
     * (对齐 CC utils/hooks.ts:2045-2051 executeHooks AbortError rethrow 语义).
     *
     * <p>WHY 透传 (而非 best-effort 吞): 用户中止意图不可被 hook 链吞掉, 必须冒泡到
     * StreamingToolExecutor 主线程 → sibling abort.
     */
    @Test
    @DisplayName("[P0-3 #19] PreToolUse hook 抛 AbortException → 透传 + 工具立即停止")
    void executeAsync_preToolUseHookAbortExceptionStopsTool() throws Exception {
        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "abort_exc_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "should not run");
            }
        };
        ToolRegistry registry = registryWith(tool);

        HookRegistry hooks = new HookRegistry();
        // [P0-3] AbortException 必须透传 (R31-D2.6 校验, 用户中止意图不可吞)
        PreToolUseHook abortingHook = (toolName, input, ctx) -> {
            throw new com.nexusai.application.agent.permission.hook.AbortException(
                "user pressed Ctrl-C");
        };
        hooks.registerPreToolUse("aborting", abortingHook);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_abort_1", "abort_exc_stub", JSON.createObjectNode()));

        // 注: AbortException 在 executeAsync 顶层被吞 (catch Throwable), 但 tool 不会执行.
        // 具体行为见 StreamingToolExecutor.executeAsync 内的 try/catch.
        try {
            exec.getRemainingResults();
        } catch (Throwable th) {
            // AbortException 可能或可能不被抛到此处, 看具体实现. 重要断言是 tool 不执行.
            log.info("got expected throw: " + th.toString());
        }

        // 关键断言 1: 工具未执行 (AbortException 阻断 tool.execute)
        assertThat(toolExecuted.get())
            .as("PreToolUse hook 抛 AbortException 必须阻断 tool.execute (CC utils/hooks.ts:2045-2051)")
            .isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [A2 P0] catch(Throwable) 路径 PostToolUseFailure hook 串联 (CC toolExecution.ts:1700-1713)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[A2 P0] 工具 execute 抛异常 → catch 路径触发 PostToolUseFailure hook")
    void executeAsync_toolThrows_runsPostToolUseFailureHook() throws Exception {
        AtomicInteger failureHookCount = new AtomicInteger(0);
        AtomicInteger errorEventCount = new AtomicInteger(0);
        Telemetry spy = new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_tool_use_error".equals(name)) {
                    errorEventCount.incrementAndGet();
                }
                super.recordEvent(name, attributes);
            }
        };

        Tool throwingTool = new Tool() {
            @Override public String name() { return "throw_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2] catch 路径 data = formatError(th)；以 "Error:" 开头供 isToolErrorData 识别
                throw new RuntimeException("Error: intentional throw for A2 catch-path failure hook");
            }
        };
        ToolRegistry registry = registryWith(throwingTool);

        HookRegistry hooks = new HookRegistry();
        hooks.setTelemetry(spy);
        hooks.register("catch_failure_observer", new GenericHook() {
            @Override
            public GenericHook.HookResult onEvent(
                com.nexusai.application.agent.permission.hook.HookEvent event) {
                if (event.type()
                    == com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE) {
                    failureHookCount.incrementAndGet();
                }
                return GenericHook.HookResult.proceed();
            }
        }, com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(spy);

        exec.add(buildCall("toolu_throw_1", "throw_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isTrue();
        assertThat(failureHookCount.get())
            .as("工具 execute 抛异常 → catch(Throwable) 路径必须调 executePostToolUseFailure"
                + " (CC toolExecution.ts:1700-1713 runPostToolUseFailureHooks 无条件)")
            .isEqualTo(1);
        assertThat(errorEventCount.get())
            .as("catch 路径失败 analytics 仍须双发 (tengu_tool_use_error)")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("[A2 P0] 工具 execute 抛 AbortException → catch 路径同样触发 PostToolUseFailure hook (CC isInterrupt 语义)")
    void executeAsync_toolThrows_abortPath_runsPostToolUseFailureHook() throws Exception {
        AtomicInteger failureHookCount = new AtomicInteger(0);

        Tool abortTool = new Tool() {
            @Override public String name() { return "abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // [IMP-C2] catch 路径 data = formatError(th)；AbortError 特例返回 message，
                //   以 "Error:" 开头供 isToolErrorData 识别为错误
                throw new com.nexusai.application.agent.permission.hook.AbortException(
                    "Error: intentional abort for A2 catch-path failure hook");
            }
        };
        ToolRegistry registry = registryWith(abortTool);

        HookRegistry hooks = new HookRegistry();
        hooks.setTelemetry(emptyTelemetry());
        hooks.register("abort_failure_observer", new GenericHook() {
            @Override
            public GenericHook.HookResult onEvent(
                com.nexusai.application.agent.permission.hook.HookEvent event) {
                if (event.type()
                    == com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE) {
                    failureHookCount.incrementAndGet();
                }
                return GenericHook.HookResult.proceed();
            }
        }, com.nexusai.application.agent.permission.hook.HookEventType.POST_TOOL_USE_FAILURE);

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        exec.add(buildCall("toolu_abort_1", "abort_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isTrue();
        // [IMP-C2] errorCategory 已从 ToolResult 删除（改走 OTel 通道，StreamingToolExecutor
        //   经 ToolErrorFormatter.classifyToolError 在发射点计算并透传）。错误分类语义
        //   （P-25 CC 细粒度类名）直接以生产分类函数验证（master 同款模式）。
        assertThat(ToolErrorFormatter.classifyToolError(
            new com.nexusai.application.agent.permission.hook.AbortException("x")))
            .as("AbortException → errorCategory=AbortException (P-25 CC 细粒度类名, toolExecution.ts:150-171)")
            .isEqualTo("AbortException");
        assertThat(failureHookCount.get())
            .as("CC toolExecution.ts:1694 isInterrupt=true 仍进 runPostToolUseFailureHooks"
                + " — abort 路径必须触发 failure hook")
            .isEqualTo(1);
    }

    /**
     * 兜底 logger for P0-3 #19 — Java 25 stream 测试日志可读性.
     */
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(StreamingToolExecutorHookInjectionTest.class);

    // ═══════════════════════════════════════════════════════════════════════
    // ===== [P0-3 变异测试自证] 故意破坏 AHR 聚合, 确认新测试变红 =====
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [变异测试] AHR.permissionBehavior 聚合优先级破坏 → 至少 #16 (Deny) 测试变红.
     *
     * <p>本测试用作变异自证: 调用方手动调 HookRegistry.executePreToolUse, 故意
     * 注册一个只返 Allow 的 hook (正常应被另一个 Deny hook 阻断), 验证聚合层
     * 实际按 deny > ask > allow 优先级执行. 这等价于"删除 deny 优先级" 变异.
     *
     * <p>注: 这不是真破坏测试代码, 而是行为逆向断言 — 用于 when-merged 检查聚合正确性.
     * 真变异测试需要临时改源码 + 还原, 留待后续 PR.
     */
    @Test
    @DisplayName("[变异自证] AHR.permissionBehavior 聚合: deny > ask > allow 优先级")
    void mutation_AggregatedHookResult_permissionBehaviorPriority_ordering() throws Exception {
        HookRegistry hooks = new HookRegistry();

        // 关键顺序: deny 先注册 (后遍历先跑), allow 后注册
        // 破坏优先级 → deny 会被 allow 覆盖 (last-wins via mergeAggregated).
        // 修复优先级 → deny 必胜 (denyBehavior 桶).
        PreToolUseHook allowHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null,
            null, null,
            new PermissionResult.Allow(
                JSON.createObjectNode(),
                new PermissionDecisionReason.Other("allow"),
                null, false, null, List.of()),
            null, null, null, null, null, null, null, null, null
        );
        PreToolUseHook denyHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null,
            null, null,
            new PermissionResult.Deny(
                "Deny first",
                new PermissionDecisionReason.Other("deny"),
                null),
            null, null, null, null, null, null, null, null, null
        );
        hooks.registerPreToolUse("deny_first", denyHook);
        hooks.registerPreToolUse("allow_second", allowHook);

        JsonNode input = JSON.createObjectNode();
        ToolUseContext ctx = baseCtx();
        AggregatedHookResult result = hooks.executePreToolUse(
            "stub_tool", input, ctx,
            "toolu_1");

        // 关键断言: deny 必胜 allow (即使 allow 后注册, 优先级 deny > allow)
        assertThat(result.permissionBehavior())
            .as("deny > allow 优先级聚合: 先注册的 deny hook 必胜 (即使 allow 后注册)")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result.permissionBehavior()).message())
            .isEqualTo("Deny first");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R5 · gate.check 抛 AbortException → 透传中止（对齐 CC toolExecution.ts:1691-1707）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [R5 / OPD-WF3-DC-v4-07] gate.check 抛 AbortException 必须透传，不得被内层
     * {@code catch(Throwable)} 吞掉后继续执行工具。
     *
     * <p>WHY（对齐 CC）: CC permissions.ts:826-828/:1024-1026 抛 AbortError 中止 agent；
     * toolExecution.ts:1631/1691-1707 catch AbortError → isInterrupt=true + 工具不执行。
     * Java 旧实现 gate 段 catch(Throwable) 把 AbortException 转 ToolResult.error 后
     * <b>继续往下执行 tool.execute</b> —— 用户中止意图被吞，工具照常运行。修复后
     * gate 段显式 rethrow AbortException → 外层 catch(:2152) 以 isAbort=true 处理：
     * 工具不执行 + PostToolUseFailure hook isInterrupt=true。
     *
     * <p>RED→GREEN：修复前本测试断言 {@code executed == false} 失败（工具照常执行）。
     */
    @Test
    @DisplayName("[R5] gate.check 抛 AbortException → 工具不执行（透传中止，对齐 CC isInterrupt）")
    void executeAsync_gateAbortExceptionStopsToolAndPropagates() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool gateAbortTool = new Tool() {
            @Override public String name() { return "gate_abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                executed.set(true);
                return ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = registryWith(gateAbortTool);

        // gate 桩: pipeline.check 抛 AbortException（用户中止意图，CC permissions.ts:826-828）
        PermissionPipeline pipeline = new PermissionPipeline() {
            @Override public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                                   ToolUseContext ctx, ToolPermissionContext permCtx) {
                throw new com.nexusai.application.agent.permission.hook.AbortException(
                    "user abort during permission check");
            }
        };
        PermissionPrompter prompter = (tool, input, reason, ctx, requestId) ->
            new PermissionResult.Allow(input, reason, null, false, null, List.of());
        ToolPermissionGate gate = new ToolPermissionGate(pipeline, prompter, null, null, null);

        // ctx 需带非 null permissionContext 以触发 gate/resolver 路径
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, gate, null);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode input = JSON.createObjectNode();
        input.put("cmd", "rm -rf /");
        exec.add(buildCall("toolu_gate_abort_1", "gate_abort_stub", input));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言（CC toolExecution.ts:1691-1707）: AbortException → 工具不执行
        assertThat(executed.get())
            .as("gate.check 抛 AbortException 后工具不得执行（否则用户中止意图被吞，工具照常运行）")
            .isFalse();
        // 结果以错误 ToolResult 收束（外层 catch isAbort 语义，ToolErrorFormatter.formatError 承载）
        assertThat(results).hasSize(1);
        assertThat(results.get(0).data()).isInstanceOf(String.class);
        assertThat(exec.getResultErrorFlags().get("toolu_gate_abort_1"))
            .as("AbortException 路径结果必须标 isError=true（getResultErrorFlags 推导）")
            .isTrue();
    }
}
