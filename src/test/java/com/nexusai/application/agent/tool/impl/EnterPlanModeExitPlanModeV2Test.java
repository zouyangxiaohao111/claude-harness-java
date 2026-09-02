package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 · EnterPlanMode 静态 flag → permission mode 状态机 + ExitPlanMode V2 语义.
 *
 * <p>WHY 本测试（CLAUDE.md 规则九 · 验证意图而非行为）:
 * <ul>
 *   <li>EnterPlanMode 写 {@code appState.toolPermissionContext.mode=PLAN} + prePlanMode —— 证明 plan
 *       状态随会话 permission context 流转（消除静态 sessionPlanMode map 的多会话并发隐患）</li>
 *   <li>ExitPlanMode validateInput errorCode 1 / checkPermissions Ask / prePlanMode 恢复 —— 证明
 *       "退出 plan 必须经用户确认 + 恢复进入前 mode" 的 CC V2 语义</li>
 *   <li>Approved Plan 全文回显 —— 证明模型在 tool_result 拿到完整计划（CC
 *       ExitPlanModeV2Tool.ts:481-489 extractApprovedPlan 依赖该回显）</li>
 * </ul>
 */
class EnterPlanModeExitPlanModeV2Test {

    private final EnterPlanModeTool enterPlanModeTool = new EnterPlanModeTool();
    private final ExitPlanModeTool exitPlanModeTool = new ExitPlanModeTool();

    // ═══════════════════ 工具方法 ═══════════════════

    /** 构造带可变 appState 的 ToolUseContext；getAppState 返回会话 map 引用，setAppState 应用 updater。 */
    private static ToolUseContext ctxWithAppState(ToolPermissionContext tpc, String agentType) {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        if (tpc != null) {
            appState.put("toolPermissionContext", tpc);
        }
        Function<Map<String, Object>, Map<String, Object>> get = ignored -> appState;
        Consumer<Function<Map<String, Object>, Map<String, Object>>> set = updater -> {
            Map<String, Object> next = updater.apply(appState);
            appState.clear();
            appState.putAll(next);
        };
        ToolUseContext base = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            tpc, PermissionMode.DEFAULT, Map.of(), false, "", Path.of("."),
            null, null, null, get, set, m -> {}, s -> {});
        if (agentType == null) {
            return base;
        }
        return base.with(new ToolUseContext.SubagentContextOverrides(
            null, agentType, null, null, null, null, null, null, null,
            null, null, null, null, null));
    }

    /** mode=PLAN + prePlanMode 的 permission context。 */
    private static ToolPermissionContext planCtx(PermissionMode prePlanMode) {
        return new ToolPermissionContext(PermissionMode.PLAN, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, prePlanMode);
    }

    private static ToolUseBlock call(ObjectNode input) {
        return new ToolUseBlock("id-" + UUID.randomUUID(), "EnterPlanMode", input);
    }

    private static ToolUseBlock exitCall(ObjectNode input) {
        return new ToolUseBlock("id-" + UUID.randomUUID(), "ExitPlanMode", input);
    }

    private static ToolPermissionContext appStateTpc(ToolUseContext ctx) {
        Object v = ctx.getAppState().apply(null).get("toolPermissionContext");
        return (ToolPermissionContext) v;
    }

    // ═══════════════════ EnterPlanMode ═══════════════════

    @Test
    @DisplayName("EnterPlanMode 写 appState.toolPermissionContext.mode=PLAN + prePlanMode=当前 mode")
    void enterPlanModeWritesModePlanAndPrePlanMode() {
        ToolUseContext ctx = ctxWithAppState(ToolPermissionContext.strict(PermissionMode.DEFAULT), null);

        AgentToolResult<?> r = enterPlanModeTool.execute(call(JsonNodeFactory.instance.objectNode()), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("进入 plan 应成功").isFalse();
        ToolPermissionContext updated = appStateTpc(ctx);
        assertThat(updated.mode()).isEqualTo(PermissionMode.PLAN);
        assertThat(updated.prePlanMode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("EnterPlanMode 已 PLAN 时 no-op（prePlanMode 保留，不进死循环）")
    void enterPlanModeAlreadyPlanIsNoop() {
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.ACCEPT_EDITS), null);

        AgentToolResult<?> r = enterPlanModeTool.execute(call(JsonNodeFactory.instance.objectNode()), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        ToolPermissionContext updated = appStateTpc(ctx);
        assertThat(updated.mode()).isEqualTo(PermissionMode.PLAN);
        assertThat(updated.prePlanMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("EnterPlanMode 子 agent 上下文拒绝（agentType 非空）")
    void enterPlanModeRejectsAgentContext() {
        ToolUseContext sub = ctxWithAppState(ToolPermissionContext.strict(PermissionMode.DEFAULT), "explore");

        AgentToolResult<?> r = enterPlanModeTool.execute(call(JsonNodeFactory.instance.objectNode()), sub);

        // [IMP-F 2026-08-22] 修正：isToolErrorData 只识别登记前缀（Error:/No-op: 等），
        // "EnterPlanMode tool cannot be used in agent contexts" 不命中 → 该断言恒 false（旧缺陷）。
        // 拒绝语义由下方 contains 断言锁定（CC EnterPlanModeTool.ts:79 'cannot be used in agent contexts'）。
        assertThat((String) ((ToolResult<?>) r).data())
            .as("子 agent 上下文必须拒绝（CC EnterPlanModeTool.ts:78-80）")
            .contains("EnterPlanMode tool cannot be used in agent contexts");
    }

    @Test
    @DisplayName("EnterPlanMode inputSchema 无 goal 参数 + 只读/并发/延迟/阈值 override")
    void enterPlanModeSchemaAndOverrides() {
        JsonNode schema = enterPlanModeTool.inputSchema();

        assertThat(schema.path("properties").has("goal"))
            .as("CC EnterPlanModeTool.ts:21-25 z.strictObject({}) 无 goal")
            .isFalse();
        assertThat(enterPlanModeTool.isReadOnly(schema)).isTrue();
        assertThat(enterPlanModeTool.isConcurrencySafe(schema)).isTrue();
        assertThat(enterPlanModeTool.shouldDefer(schema)).isTrue();
        assertThat(enterPlanModeTool.maxResultSizeChars()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("EnterPlanMode mapToToolResultBlockParam 6 步只读探索指令（CC :108-118）")
    void enterPlanModeToolResultBlockHasSixStepInstructions() {
        ToolUseContext ctx = ctxWithAppState(ToolPermissionContext.strict(PermissionMode.DEFAULT), null);
        AgentToolResult<?> r = enterPlanModeTool.execute(call(JsonNodeFactory.instance.objectNode()), ctx);

        ToolResultBlockParam block = enterPlanModeTool.mapToToolResultBlockParam(r, "enter-plan", false);

        assertThat(block.type()).isEqualTo("tool_result");
        String content = (String) block.content();
        assertThat(content).contains("In plan mode, you should:");
        assertThat(content).contains("1. Thoroughly explore the codebase to understand existing patterns");
        assertThat(content).contains("6. When ready, use ExitPlanMode to present your plan for approval");
        assertThat(content).contains("DO NOT write or edit any files yet");
    }

    // ═══════════════════ ExitPlanMode ═══════════════════

    @Test
    @DisplayName("ExitPlanMode validateInput 非 plan mode → errorCode 1 + CC 文案")
    void exitPlanModeValidateInputRejectsOutsidePlan() {
        ToolUseContext ctx = ctxWithAppState(ToolPermissionContext.strict(PermissionMode.DEFAULT), null);

        var vr = exitPlanModeTool.validateInput(JsonNodeFactory.instance.objectNode(), ctx);

        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("1");
        assertThat(vr.message()).contains("You are not in plan mode");
    }

    @Test
    @DisplayName("ExitPlanMode validateInput plan mode 通过")
    void exitPlanModeValidateInputPassesInPlan() {
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);

        var vr = exitPlanModeTool.validateInput(JsonNodeFactory.instance.objectNode(), ctx);

        assertThat(vr.ok()).isTrue();
    }

    @Test
    @DisplayName("ExitPlanMode checkPermissions 返回 Ask('Exit plan mode?') + requiresUserInteraction=true")
    void exitPlanModeCheckPermissionsAsks() {
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);

        PermissionResult pr = exitPlanModeTool.checkPermissions(JsonNodeFactory.instance.objectNode(), ctx);

        assertThat(pr).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) pr).message()).isEqualTo("Exit plan mode?");
        assertThat(exitPlanModeTool.requiresUserInteraction()).isTrue();
    }

    @Test
    @DisplayName("ExitPlanMode 恢复 prePlanMode + 清 prePlanMode + V2 输出 + Approved Plan 回显")
    void exitPlanModeRestoresModeAndReturnsV2Output() {
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.ACCEPT_EDITS), null);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("plan", "Step 1: do X\nStep 2: do Y");

        AgentToolResult<?> r = exitPlanModeTool.execute(exitCall(input), ctx);
        ToolResult<?> tr = (ToolResult<?>) r;

        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        ToolPermissionContext updated = appStateTpc(ctx);
        assertThat(updated.mode()).as("应恢复进入 plan 前的 mode").isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(updated.prePlanMode()).as("prePlanMode 应被清空").isNull();

        Map<String, Object> so = ToolResult.presentationMeta(tr);
        assertThat(so.get("plan")).isEqualTo("Step 1: do X\nStep 2: do Y");
        assertThat(so.get("isAgent")).isEqualTo(false);
        // [WF6] filePath 不再恒 null：接线 PlanProvider.getPlanFilePath(agentId) 返回磁盘真实路径
        // （CC ExitPlanModeV2Tool.ts:246 filePath = getPlanFilePath(context.agentId)），主会话 → {sessionId}.md
        assertThat(so.get("filePath")).as("应返回磁盘真实 plan 文件路径（主会话 {sessionId}.md）")
            .isInstanceOf(String.class);
        assertThat((String) so.get("filePath")).endsWith(".md");
        assertThat(so.get("planWasEdited")).isEqualTo(true);

        ToolResultBlockParam block = exitPlanModeTool.mapToToolResultBlockParam(tr, "exit-plan", false);
        String content = (String) block.content();
        // CC ExitPlanModeV2Tool.ts:477-479 planWasEdited=true → label 'Approved Plan (edited by user)'
        assertThat(content).contains("## Approved Plan (edited by user):");
        assertThat(content).contains("Step 1: do X");
        assertThat(content).contains("Step 2: do Y");
    }

    @Test
    @DisplayName("ExitPlanMode 空 plan → 简短确认（无 Approved Plan 回显）")
    void exitPlanModeEmptyPlanShortConfirmation() {
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);

        AgentToolResult<?> r = exitPlanModeTool.execute(exitCall(JsonNodeFactory.instance.objectNode()), ctx);
        ToolResult<?> tr = (ToolResult<?>) r;

        Map<String, Object> so = ToolResult.presentationMeta(tr);
        assertThat(so.get("plan")).isNull();
        assertThat(so.containsKey("planWasEdited")).as("无 input.plan → 不应标记 edited").isFalse();
        assertThat(so.containsKey("hasTaskTool")).as("无 Agent 工具 → 不应输出 hasTaskTool").isFalse();

        ToolResultBlockParam block = exitPlanModeTool.mapToToolResultBlockParam(tr, "exit-plan", false);
        assertThat((String) block.content())
            .isEqualTo("User has approved exiting plan mode. You can now proceed.");
    }

    // ═══════════════════ [G22④ / OPD-PW-06] allowedPrompts 形状校验 ═══════════════════

    @Test
    @DisplayName("ExitPlanMode allowedPrompts 合法形状 → validateInput 通过")
    void exitPlanModeAllowsValidAllowedPrompts() {
        // WHY: CC allowedPromptSchema（ExitPlanModeV2Tool.ts:64-73）z.object({tool: enum['Bash'],
        //   prompt: string})——合法项必须通过，否则正常 plan 授权请求被误拒。
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ArrayNode ap = input.putArray("allowedPrompts");
        ObjectNode item = ap.addObject();
        item.put("tool", "Bash");
        item.put("prompt", "run tests");

        Tool.ValidationResult vr = exitPlanModeTool.validateInput(input, ctx);
        assertThat(vr.ok()).as("合法 allowedPrompts 必须通过 validateInput").isTrue();
    }

    @Test
    @DisplayName("ExitPlanMode allowedPrompts 非法项（tool 非 Bash）→ validateInput 拒绝")
    void exitPlanModeRejectsInvalidAllowedPromptTool() {
        // WHY: [G22④] 旧实现非法 allowedPrompts 静默忽略（△ B2），权限链可能误放行非 Bash 工具
        //   授权——必须前置拒绝（对齐 CC zod enum 拦截）。
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ArrayNode ap = input.putArray("allowedPrompts");
        ObjectNode item = ap.addObject();
        item.put("tool", "BashTool");
        item.put("prompt", "run tests");

        Tool.ValidationResult vr = exitPlanModeTool.validateInput(input, ctx);
        assertThat(vr.ok()).as("tool 非 'Bash' 必须拒绝").isFalse();
        assertThat(vr.message()).contains(".tool must be 'Bash'");
    }

    @Test
    @DisplayName("ExitPlanMode allowedPrompts 缺 prompt → validateInput 拒绝")
    void exitPlanModeRejectsAllowedPromptMissingPrompt() {
        // WHY: prompt 是允许项的语义描述（CC :68-71），缺失则授权无意义——必须拒绝。
        ToolUseContext ctx = ctxWithAppState(planCtx(PermissionMode.DEFAULT), null);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ArrayNode ap = input.putArray("allowedPrompts");
        ap.addObject().put("tool", "Bash");

        Tool.ValidationResult vr = exitPlanModeTool.validateInput(input, ctx);
        assertThat(vr.ok()).as("缺 prompt 必须拒绝").isFalse();
        assertThat(vr.message()).contains(".prompt must be a non-empty string");
    }

}
