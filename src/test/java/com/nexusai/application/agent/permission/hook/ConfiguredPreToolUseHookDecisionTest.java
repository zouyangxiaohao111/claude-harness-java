package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-03] 配置 hook 决策链测试 · fireGenericEvent 返回值接线进工具决策链
 * （对齐 CC toolHooks.ts:481-563 单链）+ OD-06（continue:false 仅 deny 阻断，
 * 对齐 toolExecution.ts:1025-1027）+ X5（tool_use_id 非 null）。
 *
 * <p>WHY（D6 / EV-016 / EV-005 / EV-009）: 旧实现 {@code fireGenericEvent} 双总线桥接
 * 把 settings.json 配置 hook 的结果丢弃（fire-and-forget），配置 PreToolUse hook 的
 * deny/allow/updatedInput/stopReason/additionalContext 全部不生效；PreToolUse
 * continue:false 无条件阻断工具（偏离 CC 仅 deny 路径阻断）；{@code toolPre} 4 参重载
 * 传 {@code tool_use_id=null}。本测试锁定修正后的五条不变量：
 * INV-7（deny/allow/updatedInput/stopReason/additionalContext 单链生效）、
 * INV-8（deny 阻断 / allow 照跑）、X5（tool_use_id 非 null）。
 *
 * <p>不依赖 Spring 容器：手动构造 HooksSettings / HooksConfigSnapshot /
 * HookMatcherEngine + StubCommandExecutor（无真实进程）。
 */
@DisplayName("[IMPL-03] 配置 hook 决策链（deny/allow/updatedInput/stopReason/additionalContext 生效 + OD-06 + X5）")
class ConfiguredPreToolUseHookDecisionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Stub 执行器: 捕获 jsonInput + 返回预设 CommandHookResult ─────────────

    /** 覆写 execute 的 stub：不启动真实进程，按预设 stdout/status 返回。 */
    static class StubCommandExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        final AtomicReference<HookEvent> capturedEvent = new AtomicReference<>();
        private final Function<String, CommandHookExecutor.CommandHookResult> responder;

        StubCommandExecutor(Function<String, CommandHookExecutor.CommandHookResult> responder) {
            this.responder = responder;
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort) {
            capturedJsonInput.set(jsonInput);
            capturedEvent.set(hookEvent);
            return responder.apply(jsonInput);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort,
                                                             long defaultTimeoutMs, String hookCwd) {
            // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private static CommandHookExecutor.CommandHookResult exit0Json(String stdout) {
        return new CommandHookExecutor.CommandHookResult(stdout, "", stdout, 0, false, false);
    }

    // ── 构造 helper ─────────────────────────────────────────────────────────

    /** settings 配 1 条 PreToolUse:Bash command hook → registry（含 stub executor）. */
    private HookRegistry registryWithConfiguredHook(StubCommandExecutor stub) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(stub);
        return registry;
    }

    private ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT
        );
    }

    private JsonNode input() {
        return JSON.createObjectNode().put("k", "v");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. D6-1 / INV-7: 配置 hook deny → AHR.permissionBehavior=Deny（消息=deny 文案）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. 配置 PreToolUse hook deny → executePreToolUse 聚合出 Deny + deny 文案（CC toolHooks.ts:541-553）")
    void configuredHook_denyReachesAggregatedResult() {
        // WHY: 旧实现 fireGenericEvent 丢弃返回值 → 配置 hook 的 deny 决策在工具链不可见。
        //   CC runPreToolUseHooks (toolHooks.ts:541-553) deny → hookPermissionResult deny
        //   （消息 = hookPermissionDecisionReason || 缺省文案）。
        StubCommandExecutor stub = new StubCommandExecutor(
            j -> exit0Json("{\"decision\":\"block\",\"reason\":\"denied by config policy\"}"));
        HookRegistry registry = registryWithConfiguredHook(stub);

        AggregatedHookResult outcome = registry.executePreToolUse(
            "Bash", input(), ctx(), "tu-1");

        assertThat(outcome.permissionBehavior())
            .as("配置 hook decision:block → Deny 决策必须到达聚合结果 (INV-7)")
            .isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) outcome.permissionBehavior();
        assertThat(deny.message())
            .as("deny 消息 = hookPermissionDecisionReason (CC deny 文案)")
            .contains("denied by config policy");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. D6-1 / INV-8: deny → 工具被阻断（E2E executor 层）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2. 配置 PreToolUse deny → 工具不执行 + ToolResult error 含 deny 文案")
    void configuredHook_denyBlocksTool() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor(
            j -> exit0Json("{\"decision\":\"block\",\"reason\":\"denied by config policy\"}"));
        HookRegistry hooks = registryWithConfiguredHook(stub);

        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "should not run");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolUseContext ctx = ctx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());

        exec.add(new ToolUseBlock("toolu_deny_1", "Bash", input()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(toolExecuted.get())
            .as("配置 hook deny 必须阻断 tool.execute (INV-8)")
            .isFalse();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_deny_1"))
            .as("配置 hook deny 结果必须标记 error（IMP-C2 后 isError 由执行器推导）")
            .isTrue();
        assertThat(((String) results.get(0).data()))
            .as("deny 消息为 deny 文案 (验收标准 1)")
            .contains("denied by config policy");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. D6-1 / INV-8: allow → 工具照跑
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3. 配置 PreToolUse allow → 工具照跑（CC toolHooks.ts:520-528）")
    void configuredHook_allowRunsTool() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor(
            j -> exit0Json("{\"decision\":\"approve\"}"));
        HookRegistry hooks = registryWithConfiguredHook(stub);

        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "ran");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx(), null, null, hooks);
        exec.setTelemetry(new Telemetry());

        exec.add(new ToolUseBlock("toolu_allow_1", "Bash", input()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(toolExecuted.get())
            .as("配置 hook allow → 工具照跑 (INV-8)")
            .isTrue();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_allow_1"))
            .as("配置 hook allow 路径 error flag 必须为 false")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. D6-1 / INV-7: allow + updatedInput → 工具输入整体替换
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4. 配置 PreToolUse allow + updatedInput → 工具收到替换后输入（CC 全替换语义）")
    void configuredHook_allowWithUpdatedInputReplaces() throws Exception {
        // WHY: CC hookSpecificOutput.PreToolUse.updatedInput (hooks.ts:618-620) →
        //   toolHooks.ts:524-525 hookPermissionResult.updatedInput →
        //   resolveHookPermissionDecision input=updatedInput（全替换, toolExecution.ts:1130-1132）。
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\","
                + "\"updatedInput\":{\"k2\":\"v2\"}}}"));
        HookRegistry hooks = registryWithConfiguredHook(stub);

        AtomicReference<JsonNode> receivedInput = new AtomicReference<>();
        Tool tool = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                receivedInput.set(call.input());
                return ToolResult.success(call.id(), "ran");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx(), null, null, hooks);
        exec.setTelemetry(new Telemetry());

        // LLM 原输入 {"k":"v"} — hook 替换为 {"k2":"v2"}（整体替换，原字段消失）
        exec.add(new ToolUseBlock("toolu_upd_1", "Bash", input()));
        exec.getRemainingResults();

        assertThat(receivedInput.get()).isNotNull();
        assertThat(receivedInput.get().has("k")).as("CC 全替换语义: 原字段消失").isFalse();
        assertThat(receivedInput.get().path("k2").asText()).isEqualTo("v2");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. OD-06 / INV-8 反例: continue:false + allow → 工具照跑 + hook_stopped_continuation
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. continue:false + allow → 工具照跑 + 注入 hook_stopped_continuation（OD-06 反例）")
    void configuredHook_continueFalseAllow_runsTool() throws Exception {
        // WHY (OD-06 ADJUDICATED): CC toolExecution.ts:1025-1027 的 shouldPreventContinuation
        //   只在 permissionDecision.behavior !== 'allow'（deny 路径）补错误文案；allow 路径
        //   工具照跑，成功后仅注入 hook_stopped_continuation attachment
        //   (toolExecution.ts:1571-1582)。旧 Java 无条件阻断（StreamingToolExecutor:1323-1329）。
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"continue\":false,\"stopReason\":\"review paused\","
                + "\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}"));
        HookRegistry hooks = registryWithConfiguredHook(stub);

        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "ran");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        ToolUseContext ctx = ctx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_cf_1", "Bash", input()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(toolExecuted.get())
            .as("continue:false + allow → 工具照跑（OD-06 反例, 旧实现无条件阻断）")
            .isTrue();
        assertThat(exec.getResultErrorFlags().get("toolu_cf_1"))
            .as("allow 路径不产生 error result")
            .isFalse();
        boolean stopped = state.attachments().stream()
            .anyMatch(a -> "hook_stopped_continuation".equals(a.type()));
        assertThat(stopped)
            .as("工具成功后注入 hook_stopped_continuation attachment (CC toolExecution.ts:1571-1582)")
            .isTrue();
        boolean hasStopReason = state.attachments().stream()
            .filter(a -> "hook_stopped_continuation".equals(a.type()))
            .anyMatch(a -> a.content() != null && a.content().contains("review paused"));
        assertThat(hasStopReason)
            .as("stopReason 注入 attachment 消息链 (验收标准 3)")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. OD-06: continue:false + deny → 阻断（保持）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("6. continue:false + deny → 工具阻断（deny 路径保持）")
    void configuredHook_continueFalseDeny_blocks() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"continue\":false,\"stopReason\":\"nope\","
                + "\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
                + "\"permissionDecisionReason\":\"blocked by review\"}}"));
        HookRegistry hooks = registryWithConfiguredHook(stub);

        AtomicReference<Boolean> toolExecuted = new AtomicReference<>(false);
        Tool tool = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
                toolExecuted.set(true);
                return ToolResult.success(call.id(), "should not run");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx(), null, null, hooks);
        exec.setTelemetry(new Telemetry());

        exec.add(new ToolUseBlock("toolu_cfd_1", "Bash", input()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(toolExecuted.get())
            .as("continue:false + deny → 阻断（deny 路径保持）")
            .isFalse();
        assertThat(exec.getResultErrorFlags().get("toolu_cfd_1"))
            .as("配置 hook deny 结果必须标记 error")
            .isTrue();
        assertThat(((String) results.get(0).data()))
            .contains("blocked by review");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. D6-1 / INV-7: additionalContext + stopReason 流入 AHR
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("7. 配置 hook additionalContext + stopReason 流入聚合结果（注入 assistant 消息链）")
    void configuredHook_additionalContextAndStopReasonFlowThrough() {
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"continue\":false,\"stopReason\":\"sr-1\","
                + "\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"additionalContext\":\"ctx-1\"}}"));
        HookRegistry registry = registryWithConfiguredHook(stub);

        AggregatedHookResult outcome = registry.executePreToolUse(
            "Bash", input(), ctx(), "tu-1");

        assertThat(outcome.preventContinuation())
            .as("continue:false → preventContinuation=true 透传（阻断语义由 deny 路径承载）")
            .isTrue();
        assertThat(outcome.stopReason()).isEqualTo("sr-1");
        assertThat(outcome.additionalContexts())
            .as("additionalContext 流入 AHR.additionalContexts (CC toolHooks.ts:566-578)")
            .containsExactly("ctx-1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 8. X5: 配置 hook 收到的 payload tool_use_id 非 null
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("8. 配置 hook 收到的 jsonInput 含 tool_use_id（X5, CC coreSchemas.ts:417 必传）")
    void configuredHook_receivesNonNullToolUseId() {
        // WHY (X5): 旧实现 HookEvent.toolPre 4 参重载传 tool_use_id=null → buildJsonInput
        //   丢弃 → 配置 hook 收不到工具调用 ID。CC PreToolUseHookInputSchema
        //   (coreSchemas.ts:417) tool_use_id 必传。
        StubCommandExecutor stub = new StubCommandExecutor(
            j -> exit0Json("{}"));
        HookRegistry registry = registryWithConfiguredHook(stub);

        registry.executePreToolUse("Bash", input(), ctx(), "tu-1");

        String jsonInput = stub.capturedJsonInput.get();
        assertThat(jsonInput)
            .as("jsonInput 必须携带 tool_use_id")
            .contains("\"tool_use_id\"", "\"tu-1\"");
        HookEvent event = stub.capturedEvent.get();
        assertThat(event.toolUseId())
            .as("HookEvent.toolUseId 非 null (X5)")
            .isEqualTo("tu-1");
    }
}
