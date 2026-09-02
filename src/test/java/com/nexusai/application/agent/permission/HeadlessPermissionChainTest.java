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
 * [Session WF3-02 A4] headless 权限决策链测试 · 对齐 CC permissions.ts:932-951
 * {@code shouldAvoidPermissionPrompts → runPermissionRequestHooksForHeadlessAgent →
 * auto-deny asyncAgent}.
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: A4 补全 headless（后台 async agent）的权限拒绝链。
 * 无 A4 时 headless ask 会落入 interactive 弹窗（无 UI 的 async agent 弹不出 → 卡死/错误放行），
 * CC 的语义是：先给 PermissionRequest hooks 决策机会，hook 无决策才 auto-deny
 * {@code {type:'asyncAgent', reason:'Permission prompts are not available in this context'}}。
 * 每个测试钉死一个不变量：hook allow 放行 / hook deny 阻断 / 无 hook 决策 auto-deny /
 * 非 headless 仍走弹窗。
 *
 * <p>CC 真源（permissions.ts:400-471 + 932-951 + messages.ts:234-235）:
 * <pre>
 *   if (appState.toolPermissionContext.shouldAvoidPermissionPrompts) {
 *     const hookDecision = await runPermissionRequestHooksForHeadlessAgent(...)
 *     if (hookDecision) return hookDecision
 *     return { behavior:'deny', decisionReason:{type:'asyncAgent', reason:'Permission prompts
 *       are not available in this context'}, message: AUTO_REJECT_MESSAGE(tool.name) }
 *   }
 * </pre>
 *
 * @see ToolPermissionGate
 * @since Session WF3-02
 */
@DisplayName("[WF3-02 A4] headless 权限决策链（shouldAvoidPermissionPrompts → hooks → asyncAgent auto-deny）")
class HeadlessPermissionChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final String SESSION_ID = "00000000-0000-0000-0000-0000000000b4";

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
        final AtomicInteger checkCalls = new AtomicInteger();
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                       ToolUseContext ctx, ToolPermissionContext permCtx) {
            checkCalls.incrementAndGet();
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

    /** permCtx · shouldAvoidPermissionPrompts 由调用方决定（第 9 位字段）。 */
    private static ToolPermissionContext newPermCtx(boolean shouldAvoidPermissionPrompts) {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), shouldAvoidPermissionPrompts, false, null);
    }

    private static ToolUseContext newCtx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock newCall(String id) {
        return new ToolUseBlock(id, "Bash", JSON.createObjectNode());
    }

    /** 主构造器（12 参）· 注入 hookRegistry，applier/persister 置 null（A4 apply/persist 跳过）。 */
    private static ToolPermissionGate newGate(StubPipeline pipeline, RecordingPrompter prompter,
                                              HookRegistry hookRegistry) {
        PermissionDecisionLogger logger = new PermissionDecisionLogger(null);
        return new ToolPermissionGate(
            pipeline, prompter, null, null, null,        // autoModeGate/denialTracker/bubble
            null, null, null, logger,                    // coordinator/swarm/interactive/logger
            hookRegistry, null, null);                   // hookRegistry/applier/persister
    }

    // ─────────────────────── 1. 无 hook 决策 → auto-deny asyncAgent ───────────────────────

    @Test
    @DisplayName("headless + 无 PermissionRequest hook 决策 → DENY + AsyncAgent 归因")
    void headless_noHookDecision_autoDenyAsyncAgent() {
        // WHY: CC permissions.ts:944-951 — 无 hook 决策时 auto-deny {type:'asyncAgent'}，
        // message = AUTO_REJECT_MESSAGE(tool.name)。若 Java 仍走弹窗 → headless agent 卡死。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        // hook 执行但无 permissionRequestResult（proceed → prr=null）→ auto-deny
        Mockito.when(hookRegistry.executeEvent(Mockito.any(HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed());

        ToolPermissionGate gate = newGate(pipeline, prompter, hookRegistry);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_a4_1"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("auto-deny 归因必须是 AsyncAgent（CC permissions.ts:947-950）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason())
                    .isInstanceOf(PermissionDecisionReason.AsyncAgent.class));
        assertThat(result.result())
            .as("message 对齐 CC AUTO_REJECT_MESSAGE 全文（messages.ts:234-235）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.message())
                    .contains("Permission to use Bash has been denied.")
                    .contains("IMPORTANT: You *may* attempt to accomplish this action")
                    .contains("Let the user decide how to proceed."));
        assertThat(prompter.calls.get())
            .as("headless auto-deny → 绝不弹窗")
            .isZero();
    }

    @Test
    @DisplayName("headless + hookRegistry 未注入 → 直接 auto-deny（null 兼容 fail-closed）")
    void headless_nullHookRegistry_autoDeny() {
        // WHY: gate 新注入 3 bean 须 null 兼容 —— hookRegistry null → 直接 auto-deny，
        // 不 NPE（CC hook 失败 fall through 同款，permissions.ts:469-476）。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();

        ToolPermissionGate gate = newGate(pipeline, prompter, null);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_a4_2"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.AsyncAgent.class));
        assertThat(prompter.calls.get()).isZero();
    }

    // ─────────────────────── 2. hook allow → ALLOW ───────────────────────

    @Test
    @DisplayName("headless + PermissionRequest hook allow → ALLOW（hook 决策优先）")
    void headless_hookAllow_allows() {
        // WHY: CC permissions.ts:425-445 — hook allow 优先于 auto-deny，finalInput =
        // decision.updatedInput ?? input。hook allow 不应被 auto-deny 吞掉。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        Mockito.when(hookRegistry.executeEvent(Mockito.any(HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Allow(null, null)));

        ToolPermissionGate gate = newGate(pipeline, prompter, hookRegistry);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_a4_3"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(result.result())
            .as("hook allow 归因必须是 Hook(PermissionRequest)（CC permissions.ts:439-442）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow ->
                assertThat(allow.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Hook.class, h ->
                        assertThat(h.hookName()).isEqualTo("PermissionRequest")));
        assertThat(prompter.calls.get())
            .as("hook allow → 不弹窗")
            .isZero();
    }

    // ─────────────────────── 3. hook deny → DENY（Hook 归因） ───────────────────────

    @Test
    @DisplayName("headless + PermissionRequest hook deny → DENY + Hook 归因（hook message 透传）")
    void headless_hookDeny_deniesWithHookReason() {
        // WHY: CC permissions.ts:452-462 — hook deny message 进 deny message（fail-closed），
        // decisionReason {type:'hook', hookName:'PermissionRequest', reason:message}。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        Mockito.when(hookRegistry.executeEvent(Mockito.any(HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Deny("hook says no", null)));

        ToolPermissionGate gate = newGate(pipeline, prompter, hookRegistry);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_a4_4"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("hook deny message 必须透传（CC permissions.ts:458 message || 'Permission denied by hook'）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny -> {
                assertThat(deny.message()).contains("hook says no");
                assertThat(deny.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Hook.class, h ->
                        assertThat(h.hookName()).isEqualTo("PermissionRequest"));
            });
        assertThat(prompter.calls.get()).isZero();
    }

    // ─────────────────────── 4. 非 headless → 走 prompter ───────────────────────

    @Test
    @DisplayName("非 headless（shouldAvoidPermissionPrompts=false）→ 走 interactive 弹窗，不触发 A4")
    void nonHeadless_goesToPrompter_notA4() {
        // WHY: A4 仅当 shouldAvoidPermissionPrompts=true 才触发；非 headless 的 ask 仍走
        // interactive 弹窗（CC :932 条件为 false 时不进入 A4 块）。hook 也不应被调用。
        StubPipeline pipeline = new StubPipeline();
        RecordingPrompter prompter = new RecordingPrompter();
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, hookRegistry);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_a4_5"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get())
            .as("非 headless → interactive 弹窗恰好 1 次")
            .isEqualTo(1);
        Mockito.verify(hookRegistry, Mockito.never())
            .executeEvent(Mockito.any(HookEvent.class));
    }
}
