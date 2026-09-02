package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [REV-FIX-4 WF-3 缝隙2] 生产接线测试 · createSpringBean 必须把 hookRegistry 透传 gate，
 * headless 场景 PermissionRequest hook 链才完整（对齐 CC permissions.ts:932-951）。
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: WF-3 复验发现生产 fallback（AgentLoopContext
 * .buildStreamingExecutor → createSpringBean 7 参重载）第 10 参 hookRegistry 硬编码 null → gate
 * 的 headless 决策链 {@code runHeadlessPermissionRequestHooks} 首行 {@code hookRegistry == null}
 * 直接返回 null → 恒 auto-deny。fail-closed 语义虽已生效，但 hook allow 永远无法放行 —— 链不完整。
 * CC 真源 permissions.ts:932-951 语义：headless（shouldAvoidPermissionPrompts）恒先跑
 * {@code runPermissionRequestHooksForHeadlessAgent}（hooks 真实链，permissions.ts:409
 * {@code executePermissionRequestHooks}），hook allow/deny 优先采纳，无 hook 决策才 auto-deny
 * asyncAgent。本测试经<b>生产工厂</b>（createSpringBean）钉死接线不变量：一旦回退为 null 死路径
 * 测试必红。
 *
 * <pre>CC 真源（permissions.ts:932-951 + 400-471）:
 *   if (appState.toolPermissionContext.shouldAvoidPermissionPrompts) {
 *     const hookDecision = await runPermissionRequestHooksForHeadlessAgent(...)
 *     if (hookDecision) return hookDecision
 *     return { behavior:'deny', decisionReason:{type:'asyncAgent', reason:'Permission prompts
 *       are not available in this context'}, message: AUTO_REJECT_MESSAGE(tool.name) }
 *   }</pre>
 *
 * @see ToolPermissionGate#createSpringBean
 * @see ToolPermissionGate#runHeadlessPermissionRequestHooks
 * @since REV-FIX-4
 */
@DisplayName("[REV-FIX-4] createSpringBean 生产接线 · headless hookRegistry 透传（CC permissions.ts:932-951）")
class HeadlessHookRegistryProductionWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
    private static final String SESSION_ID = "00000000-0000-0000-0000-0000000000e4";

    // ─────────────────────────────── 测试桩 ───────────────────────────────

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static final class StubPipeline extends PermissionPipeline {
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                       ToolUseContext ctx, ToolPermissionContext permCtx) {
            return new PermissionResult.Ask(
                "stub ask", new PermissionDecisionReason.Other("test"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    private static final class RecordingPrompter implements PermissionPrompter {
        final AtomicInteger calls = new AtomicInteger();
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input, PermissionDecisionReason reason,
                                        ToolUseContext ctx, String requestId) {
            calls.incrementAndGet();
            return new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), requestId, false, null, List.of());
        }
    }

    // ─────────────────────────────── 构造辅助 ───────────────────────────────

    /** permCtx · shouldAvoidPermissionPrompts = 第 9 位字段（CC shouldAvoidPermissionPrompts）. */
    private static ToolPermissionContext newHeadlessPermCtx() {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), true, false, null);
    }

    private static ToolUseContext newCtx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock newCall(String id) {
        return new ToolUseBlock(id, "Bash", JSON.createObjectNode());
    }

    /**
     * 生产工厂路径：8 参 createSpringBean（AgentLoopContext.buildStreamingExecutor fallback
     * 同款签名）。其余 handler/feature 传 null（fallback 场景仅 pipeline/prompter 必填）。
     */
    private static ToolPermissionGate gateViaProductionFactory(
            StubPipeline pipeline, RecordingPrompter prompter, HookRegistry hookRegistry) {
        return ToolPermissionGate.createSpringBean(
            pipeline, prompter, null,             // pipeline / prompter / telemetry
            null, null, null,                     // coordinator / swarm / interactive
            null,                                 // bashClassifierFeature（恒禁用竞速）
            hookRegistry);                        // REV-FIX-4：真实 hookRegistry 透传
    }

    /** 生产工厂 7 参路径（修复前 fallback 唯一入口）· hookRegistry 恒 null（委托 8 参 null）. */
    private static ToolPermissionGate gateViaLegacySevenParamFactory(
            StubPipeline pipeline, RecordingPrompter prompter) {
        return ToolPermissionGate.createSpringBean(
            pipeline, prompter, null,
            null, null, null,
            null);
    }

    // ─────────────────── 1. 生产工厂 8 参 → hookRegistry 接线 → hook allow 放行 ───────────────────

    @Test
    @DisplayName("生产工厂(8参, hookRegistry注入) + headless + hook allow → ALLOW（修复前 7 参→auto-deny）")
    void productionFactory_wiresHookRegistry_headlessHookAllow_allows() {
        // WHY: WF-3 缝隙2 —— 生产 fallback 若走 7 参（hookRegistry=null），headless hook 链不接线，
        // hook allow 永远无法放行（恒 auto-deny）。CC permissions.ts:932-951 headless 恒先跑 hook 链，
        // hook allow 优先采纳（permissions.ts:425-445）。生产工厂必须把 hookRegistry 透传 gate。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        Mockito.when(hookRegistry.executeEvent(Mockito.any(HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Allow(null, null)));

        ToolPermissionGate gate = gateViaProductionFactory(pipeline, prompter, hookRegistry);
        ToolPermissionContext permCtx = newHeadlessPermCtx();
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_f4_1"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision())
            .as("headless hook allow 必须放行 ALLOW（8 参生产工厂已接线 hookRegistry）")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(result.result())
            .as("hook allow 归因必须是 Hook(PermissionRequest)（CC permissions.ts:439-442）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow ->
                assertThat(allow.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Hook.class, h ->
                        assertThat(h.hookName()).isEqualTo("PermissionRequest")));
        assertThat(prompter.calls.get())
            .as("hook allow → 不弹窗（headless 无 UI）")
            .isZero();
        Mockito.verify(hookRegistry, Mockito.times(1))
            .executeEvent(Mockito.any(HookEvent.class));
    }

    // ─────────────────── 2. 生产工厂 7 参（修复前）→ hookRegistry null → auto-deny ───────────────────

    @Test
    @DisplayName("生产工厂(7参, 修复前唯一入口) + headless + hook allow → DENY+AsyncAgent（死路径钉死）")
    void legacySevenParamFactory_headlessHookAllow_autoDeny() {
        // WHY: 钉死修复前行为 —— 7 参重载 hookRegistry 恒 null → headless hook 链不接线 →
        // hook allow 被吞 → auto-deny asyncAgent。本测试确保 7 参向后兼容（委托 8 参 null）不漂移；
        // 若 7 参意外接线上 hookRegistry 或 8 参 null 语义被破坏，测试必须红。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();

        ToolPermissionGate gate = gateViaLegacySevenParamFactory(pipeline, prompter);
        ToolPermissionContext permCtx = newHeadlessPermCtx();
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_f4_2"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision())
            .as("7 参路径 hookRegistry=null → headless 恒 auto-deny（fail-closed，修复前行为）")
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("auto-deny 归因必须是 AsyncAgent（CC permissions.ts:947-950）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.AsyncAgent.class));
        assertThat(prompter.calls.get()).isZero();
    }

    // ─────────────────── 3. 生产工厂 8 参 hookRegistry=null → fail-closed 回归 ───────────────────

    @Test
    @DisplayName("生产工厂(8参, hookRegistry=null) + headless → DENY+AsyncAgent（fail-closed 回归）")
    void productionFactory_nullHookRegistry_headlessAutoDeny() {
        // WHY: hookRegistry 未注入（如独立单测 / runner 未接线）时 headless 仍须 fail-closed
        // auto-deny，不 NPE（CC hook 失败 fall through 同款，permissions.ts:469-476）。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();

        ToolPermissionGate gate = gateViaProductionFactory(pipeline, prompter, null);
        ToolPermissionContext permCtx = newHeadlessPermCtx();
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_f4_3"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("hookRegistry=null → asyncAgent auto-deny（fail-closed 语义不回归）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.AsyncAgent.class));
        assertThat(prompter.calls.get()).isZero();
    }
}
