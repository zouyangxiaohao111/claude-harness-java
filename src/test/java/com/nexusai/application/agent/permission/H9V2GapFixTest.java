package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H9 v2 对抗核验缺口] 修复验证 · H9-GAP-2 + 未登记缺口 (awaitAutomatedChecksBeforeDialog 生产来源).
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>:
 * <ul>
 *   <li><b>H9-GAP-2</b>: {@link ToolPermissionGate} 是 {@code @Component} 但无默认/可注入构造器,
 *       生产实际走 createSpringBean 静态工厂 fallback — 注解与实例化路径不一致. 修复加
 *       {@code @Autowired} 构造器 + {@link PermissionDecisionLogger} 变 {@code @Component}.
 *       本测试钉死: gate 有 @Autowired 构造器 (Spring 可实例化), decisionLogger 是 @Component
 *       (gate bean 能拿到真实 telemetry).</li>
 *   <li><b>未登记缺口</b>: 生产所有 ToolPermissionContext 构造点 awaitAutomatedChecksBeforeDialog
 *       恒 false → coordinator 分支仅测试可达. CC runAgent.ts:457-464 对 bubble 异步 agent 置
 *       true. 修复加 {@code buildPermissionContext(state, await, mode, shouldAvoid, base)} 5 参 overload +
 *       {@link AgentLoopContext#toolExecContext} 按 BUBBLE 派生. 本测试钉死两个生产来源.</li>
 * </ul>
 *
 * @since H9 v2 缺口修复
 */
class H9V2GapFixTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String SESSION_ID = "00000000-0000-0000-0000-0000000000a2";

    // ─────────────────────── Fix C: awaitAutomatedChecksBeforeDialog 生产来源 ───────────────────────

    @Test
    @DisplayName("buildPermissionContext 5 参(state, await, null, false, base) → awaitAutomatedChecksBeforeDialog 透传 (未登记缺口)")
    void permissionContextBuilder_overloadSetsAwaitAutomatedChecksFlag() {
        // WHY (H9-v2 未登记缺口): 生产所有构造点恒传 false → coordinator 分支 (gate L488)
        // 仅测试可达. 5 参重载给生产一个置 true 的来源 (CC runAgent.ts:457-464 配置驱动).
        PermissionContextBuilder builder = new PermissionContextBuilder();
        AgentState state = new AgentState("sys", SESSION_ID, AGENT_ID);

        assertThat(builder.buildPermissionContext(state, false, null, false, true).awaitAutomatedChecksBeforeDialog())
            .as("默认路径保持 false (主线程/同步子 agent)")
            .isFalse();
        assertThat(builder.buildPermissionContext(state, true, null, false, true).awaitAutomatedChecksBeforeDialog())
            .as("显式 true → coordinator 分支生产可达")
            .isTrue();
    }

    @Test
    @DisplayName("toolExecContext: BUBBLE mode → per-turn permCtx.awaitAutomatedChecksBeforeDialog=true + permissionMode=BUBBLE")
    void toolExecContext_bubbleModeDerivesAwaitAutomatedChecks() {
        // WHY (H9-v2 未登记缺口 + H9 v3 Gap①): CC runAgent.ts:461-464 对 bubble 异步 agent
        // (能弹窗的后台 agent) 置 awaitAutomatedChecksBeforeDialog=true; Java 生产来源 =
        // TUC.permissionMode()==BUBBLE. 若 wiring 丢失 → coordinator 分支恒不可达, 必须红.
        // [H9 v3 Gap①] 额外断言 perTurnTuc.permissionMode()==BUBBLE — 依赖 buildPermissionContext
        //   3 参 mode 透传; 若 mode 被覆盖回 DEFAULT, gate 的 bubble 分支 (ToolPermissionGate L680)
        //   仍不可达 (仅 awaitAutomatedChecks=true 只够 coordinator 分支).
        AgentLoopContext ctx = ctxWithPermissionContextBuilder(new PermissionContextBuilder());
        ToolUseContext baseBubble = ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.BUBBLE);

        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, baseBubble, new AgentState("sys", SESSION_ID, AGENT_ID), Map.of());

        assertThat(perTurn.permissionContext())
            .as("toolExecContext 必须重建 per-turn permCtx (permissionContextBuilder 注入时)")
            .isNotNull();
        assertThat(perTurn.permissionContext().awaitAutomatedChecksBeforeDialog())
            .as("BUBBLE mode 子 agent → coordinator 自动化检查分支生产可达 (CC runAgent.ts:461)")
            .isTrue();
        assertThat(perTurn.permissionMode())
            .as("[H9 v3 Gap①] BUBBLE mode 必须透传到 per-turn TUC (gate bubble 分支 L680 生产可达)")
            .isEqualTo(PermissionMode.BUBBLE);
    }

    @Test
    @DisplayName("toolExecContext: 非 BUBBLE mode → awaitAutomatedChecksBeforeDialog 保持 false")
    void toolExecContext_nonBubbleKeepsFalse() {
        // WHY (H9-v2 未登记缺口): 主线程 / 同步子 agent 不应等待自动化检查 — CC runAgent.ts
        // 默认 isAsync=false → flag false. BUBBLE-only 派生, 非 BUBBLE 不得误置 true.
        AgentLoopContext ctx = ctxWithPermissionContextBuilder(new PermissionContextBuilder());
        ToolUseContext baseDefault = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT);

        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, baseDefault, new AgentState("sys", SESSION_ID, AGENT_ID), Map.of());

        assertThat(perTurn.permissionContext()).isNotNull();
        assertThat(perTurn.permissionContext().awaitAutomatedChecksBeforeDialog())
            .as("非 BUBBLE → 不等待自动化检查 (CC 默认)")
            .isFalse();
    }

    // ─────────────────────── Fix B: gate @Autowired 构造器 + decisionLogger @Component ───────────────────────

    @Test
    @DisplayName("ToolPermissionGate 有 @Autowired 构造器 (Spring 可直接实例化, H9-GAP-2)")
    void toolPermissionGate_hasAutowiredConstructor() throws Exception {
        // WHY (H9-GAP-2): gate 是 @Component 但无默认/可注入构造器 → Spring 无法直接实例化,
        // 生产走 createSpringBean 静态工厂 fallback (注解与实际实例化路径不一致). 修复加
        // @Autowired 构造器 → Spring 字段注入 (AgentLoopContextFactory / LlmAgentLoop) 生效.
        java.lang.reflect.Constructor<?> autowiredCtor = null;
        for (java.lang.reflect.Constructor<?> c : ToolPermissionGate.class.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                autowiredCtor = c;
                break;
            }
        }
        assertThat(autowiredCtor)
            .as("ToolPermissionGate 必须有一个 @Autowired 构造器 (Spring 实例化入口)")
            .isNotNull();
        assertThat(autowiredCtor.getParameterCount())
            .as("@Autowired 构造器必须接收 pipeline + prompter (最小必填依赖)")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("PermissionDecisionLogger 是 @Component (gate bean 能注入真实 telemetry, H9-GAP-2)")
    void permissionDecisionLogger_isSpringComponent() {
        // WHY (H9-GAP-2): gate @Autowired 构造器的 decisionLogger 参数若是 null → 默认实例
        // 无 telemetry, 生产遥测丢失 (与 createSpringBean 3 参传 telemetry 的行为不一致).
        // 让 PermissionDecisionLogger 变 @Component → gate bean 拿到真实 telemetry.
        assertThat(PermissionDecisionLogger.class.isAnnotationPresent(
            org.springframework.stereotype.Component.class))
            .as("PermissionDecisionLogger 必须是 @Component (可被 gate 注入)")
            .isTrue();
    }

    @Test
    @DisplayName("gate @Autowired 构造器注入真实 decisionLogger → 遥测事件经注入 logger 发出")
    void gate_autowiredConstructorWiresDecisionLogger() {
        // WHY (H9-GAP-2): 行为验证 — 经 @Autowired 构造器手工组装 (等价 Spring 注入) 的 gate,
        // decisionLogger 必须是被注入的实例 (带 telemetry), 而不是默认无 telemetry 实例.
        // 用 RecordingTelemetry 验证 coordinator hook-allow 真的发 granted_by_permission_hook.
        RecordingTelemetry telemetry = new RecordingTelemetry();
        PermissionDecisionLogger logger = new PermissionDecisionLogger(telemetry);
        // Ask 结果 → 走 Ask 分发链 → awaitAutomatedChecks=true → coordinator hook-allow
        StubPipeline pipeline = new StubPipeline(new PermissionResult.Ask(
            "stub ask", new PermissionDecisionReason.Other("test"), List.of(),
            null, null, null, false, null, List.of()));
        PermissionPrompter prompter = (tool, input, reason, ctx, requestId) ->
            new PermissionResult.Allow(input,
                new PermissionDecisionReason.Other("should not be asked"), requestId, false, null, List.of());

        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            allowCoordinator(), null, new InteractiveHandler(prompter), logger, null, null, null);

        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, true, null);
        ToolUseContext ctx = ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);

        ToolPermissionGate.DecisionResult result = gate.check(
            new StubTool(), new ToolUseBlock("call_g1", "Read", JSON.createObjectNode()),
            JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(telemetry.events)
            .as("coordinator hook-allow 必须经注入 logger 发 granted_by_permission_hook (H9-GAP-2 遥测保留)")
            .contains("tengu_tool_use_granted_by_permission_hook");
    }

    // ─────────────────────── 辅助 ───────────────────────

    /** 最小 AgentLoopContext · 注入真实 PermissionContextBuilder (TestContexts 默认传 null). */
    private static AgentLoopContext ctxWithPermissionContextBuilder(PermissionContextBuilder builder) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            builder, null, null, null);
    }

    private static CoordinatorPermissionHandler allowCoordinator() {
        return new CoordinatorPermissionHandler(
            () -> false,
            params -> new CoordinatorPermissionHandler.PermissionDecision("allow", "hook approved"),
            (check, input, toolUseId) -> null,
            ex -> {});
    }

    /** 固定返回 Ask 的 pipeline 桩 (触发 coordinator 分发链). */
    private static final class StubPipeline extends PermissionPipeline {
        final PermissionResult result;
        StubPipeline(PermissionResult result) { this.result = result; }
        @Override
        public PermissionResult check(com.nexusai.application.agent.tool.Tool tool,
                                      com.nexusai.application.agent.tool.ToolUseBlock call,
                                      JsonNode input, ToolUseContext ctx, ToolPermissionContext permCtx) {
            return result;
        }
    }

    /** 记录事件名的 telemetry 桩. */
    private static final class RecordingTelemetry extends com.nexusai.application.agent.telemetry.Telemetry {
        final java.util.List<String> events = new java.util.ArrayList<>();
        RecordingTelemetry() {
            super();
        }
        @Override
        public void recordEvent(String name, Map<String, Object> attributes) {
            events.add(name);
        }
    }

    /** 最简 Tool 桩 (仅 name/description). */
    private static final class StubTool implements com.nexusai.application.agent.tool.Tool {
        @Override public String name() { return "Read"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult execute(
                com.nexusai.application.agent.tool.ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "stub-result");
        }
    }
}
