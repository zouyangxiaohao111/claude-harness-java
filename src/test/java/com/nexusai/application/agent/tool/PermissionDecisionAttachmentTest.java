package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.CoordinatorPermissionHandler;
import com.nexusai.application.agent.permission.PermissionDecisionLogger;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PreToolUseHook;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 CCJ-T6-18] PermissionRequest hook 决策 → hook_permission_decision
 * attachment (CC toolExecution.ts:979-993).
 *
 * <p>验证单元:
 * <ol>
 *   <li>executor 注入点: PreToolUse hook 返回带 Hook("PermissionRequest") reason 的
 *       Allow 决策 → agentState 出现 hook_permission_decision (content=allow)</li>
 *   <li>coordinator 路径: PermissionDecision.decisionReason (Hook("PermissionRequest"))
 *       经 gate 透传 → DecisionResult.result() 携带 Hook reason (不再丢失归因)</li>
 * </ol>
 */
@DisplayName("[IMP-HOOKS-S6 CCJ-T6-18] hook_permission_decision 附件两路")
class PermissionDecisionAttachmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────────────────────── 1. executor 注入点 ───────────────────────────

    @Test
    @DisplayName("PermissionRequest hook allow 决策 → hook_permission_decision attachment (allow)")
    void permissionRequestAllowDecision_attachmentInjected() {
        Tool stub = new Tool() {
            @Override public String name() { return "permreq_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        // hook 决策 reason = Hook("PermissionRequest") — 对齐 interactive racer 产物
        PreToolUseHook prHook = (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null, null, null,
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Hook("PermissionRequest", null, "allow"),
                "toolu_pr_1", false, null, List.of()),
            null, null, null, null, null, null, null, null, null);
        hooks.registerPreToolUse("permreq", prHook);

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_pr_1", "permreq_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(exec.getResultErrorFlags().get("toolu_pr_1"))
            .as("PermissionRequest allow 路径 error flag 必须为 false（IMP-C2 后 isError 由执行器推导）")
            .isFalse();
        List<AttachmentMessageDto> decisionAtts = state.attachments().stream()
            .filter(a -> "hook_permission_decision".equals(a.type()))
            .toList();
        assertThat(decisionAtts)
            .as("PermissionRequest allow 决策 → 恰 1 个 hook_permission_decision attachment")
            .hasSize(1);
        assertThat(decisionAtts.get(0).content())
            .as("决策值经 content 承载 (allow/deny)")
            .isEqualTo("allow");
        assertThat(decisionAtts.get(0).hookEvent()).isEqualTo("PermissionRequest");
        assertThat(decisionAtts.get(0).toolUseID()).isEqualTo("toolu_pr_1");
    }

    @Test
    @DisplayName("非 PermissionRequest hook 决策 → 无 hook_permission_decision attachment")
    void nonPermissionRequestDecision_noAttachment() {
        Tool stub = new Tool() {
            @Override public String name() { return "plain_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse("plain", (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null, null, null,
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("plain allow"),
                null, false, null, List.of()),
            null, null, null, null, null, null, null, null, null));

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_plain_1", "plain_stub", JSON.createObjectNode()));
        exec.getRemainingResults();

        assertThat(state.attachments().stream()
            .filter(a -> "hook_permission_decision".equals(a.type())))
            .as("非 PermissionRequest reason 不得产 hook_permission_decision")
            .isEmpty();
    }

    // ─────────────────────────── 2. coordinator 路径归因透传 ───────────────────────────

    @Test
    @DisplayName("coordinator hook 决策经 gate 透传 Hook('PermissionRequest') reason")
    void coordinatorDecision_reasonCarriedThroughGate() {
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, true, null); // awaitAutomatedChecksBeforeDialog=true
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);

        Tool stub = new Tool() {
            @Override public String name() { return "coord_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        // hooksRunner 返回带 Hook("PermissionRequest") reason 的决策 (runPermissionRequestHooks 产物)
        CoordinatorPermissionHandler coordinator = new CoordinatorPermissionHandler(
            () -> false,
            params -> new CoordinatorPermissionHandler.PermissionDecision(
                "allow", "Permission request hook approved",
                CoordinatorPermissionHandler.Source.HOOK,
                new PermissionDecisionReason.Hook("PermissionRequest", null, "allow")),
            (check, input, toolUseId) -> null,
            ex -> {});
        PermissionPipeline pipeline = new PermissionPipeline() {
            @Override
            public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                          ToolUseContext cctx, ToolPermissionContext pctx) {
                return new PermissionResult.Ask("ask", new PermissionDecisionReason.Other("test"),
                    List.of(), null, null, null, false, null, List.of());
            }
        };
        PermissionPrompter prompter = (tool, input, reason, cctx, requestId) ->
            new PermissionResult.Allow(input, new PermissionDecisionReason.Other("user"),
                requestId, false, null, List.of());
        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            coordinator, null, null, new PermissionDecisionLogger(new Telemetry()));

        ToolPermissionGate.DecisionResult decision = gate.check(
            stub, new ToolUseBlock("toolu_coord_1", "coord_stub", JSON.createObjectNode()),
            JSON.createObjectNode(), ctx, permCtx, null);

        assertThat(decision.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(decision.result())
            .as("coordinator hook allow 决策必须携带 PermissionResult (reason 不丢失)")
            .isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) decision.result();
        assertThat(allow.reason())
            .as("gate 透传 Hook('PermissionRequest') reason 至 executor 注入点 (CCJ-T6-18)")
            .isInstanceOf(PermissionDecisionReason.Hook.class);
        assertThat(((PermissionDecisionReason.Hook) allow.reason()).hookName())
            .isEqualTo("PermissionRequest");
    }

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }
}
