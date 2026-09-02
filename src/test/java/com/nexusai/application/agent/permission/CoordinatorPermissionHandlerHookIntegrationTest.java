package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Session S07] Coordinator hooksRunner 生产化集成测试 · 对齐 CC coordinatorHandler.ts:26-62
 * + PermissionContext.ts:216-263 ({@code ctx.runHooks}).
 *
 * <p><b>WHY (X1 / H9-GAP-1)</b>: 旧生产构造器 {@code HooksRunner} 恒返回 null —
 * coordinator 分支 (awaitAutomatedChecksBeforeDialog=true, coordinator worker / BUBBLE fork)
 * 的 PermissionRequest hook 决策永不生效 (hooks first, fast, local 语义失效).
 * 本测试用<b>真实 {@link HookRegistry}</b> + programmatic hook (非 mock executeEvent,
 * 非注入式 runner 桩 — 对抗"测试用桩不覆盖生产恒 null 路径"限制, 探查报告 §12 E3 限制),
 * 验证生产构造器接线后 hook allow/deny 决策被采纳、事件载荷完整、fall-through 语义保持.
 *
 * <p>验收标准映射: #1 (hooksRunner 实际执行 pre-tool-use hooks 并聚合 permissionRequestResult)
 * + #4 (hook deny 为 blocking, fail-closed).
 */
class CoordinatorPermissionHandlerHookIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 生产构造器 (O18 2 参: feature/hookRegistry; BashClassifier 已删, classifier 步恒 null) · 仅接 hookRegistry. */
    private static CoordinatorPermissionHandler productionHandler(HookRegistry registry) {
        return new CoordinatorPermissionHandler(null, registry);
    }

    /** 完整 9 参 Params (hook 执行上下文, S07). */
    private static CoordinatorPermissionHandler.Params params() {
        return new CoordinatorPermissionHandler.Params(
            null,
            Map.of(),
            List.of(new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS)),
            "default",
            "Bash",
            JSON.createObjectNode().put("command", "git status"),
            "call_1",
            "sess-1",
            "agent-1");
    }

    @Test
    @DisplayName("S07-1 hooksRunner 生产化: hook allow → handle 返回 allow (source=HOOK) + 事件载荷完整")
    void hookAllow_adoptedByCoordinator() {
        // WHY: CC coordinatorHandler.ts:33-38 runHooks 非空 → 直接 return hookResult (hooks first).
        //      若 hooksRunner 仍恒 null (H9-GAP-1), hook allow 决策永不采纳, coordinator 分支失效.
        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("perm-allow", event -> {
            captured.set(event);
            return GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Allow(
                    Map.of("command", "git status --short"), List.of()));
        }, HookEventType.PERMISSION_REQUEST);

        CoordinatorPermissionHandler handler = productionHandler(registry);
        var decision = handler.handle(params());

        assertThat(decision)
            .as("hook allow → handle 必须返回决策 (不再恒 Optional.empty)")
            .isPresent();
        assertThat(decision.get().decision()).isEqualTo("allow");
        assertThat(decision.get().source())
            .as("hook 决策 source 归因必须为 HOOK (granted_by_permission_hook)")
            .isEqualTo(CoordinatorPermissionHandler.Source.HOOK);
        // 事件载荷完整 (CC executePermissionRequestHooks hooks.ts:4157-4192 hook_input)
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().type()).isEqualTo(HookEventType.PERMISSION_REQUEST);
        assertThat(captured.get().toolName()).isEqualTo("Bash");
        assertThat(captured.get().toolUseId()).isEqualTo("call_1");
        assertThat(captured.get().permissionMode()).isEqualTo("default");
        assertThat(captured.get().permissionSuggestions())
            .as("permission_suggestions 必须透传到 hook (CC coreSchemas.ts:431)")
            .hasSize(1);
    }

    @Test
    @DisplayName("S07-2 hook deny → handle 返回 deny + hook message (fail-closed, 验收 #4)")
    void hookDeny_blocksToolExecution() {
        // WHY: CC PermissionContext.ts:240-258 deny → buildDeny(message || 'Permission denied by hook')
        //      — hook 拒绝必须阻断 (gate 收到 deny 决策 → DecisionResult.DENY, 工具不执行).
        HookRegistry registry = new HookRegistry();
        registry.register("perm-deny", event ->
            GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Deny("policy blocks", false)),
            HookEventType.PERMISSION_REQUEST);

        CoordinatorPermissionHandler handler = productionHandler(registry);
        var decision = handler.handle(params());

        assertThat(decision).isPresent();
        assertThat(decision.get().decision()).isEqualTo("deny");
        assertThat(decision.get().reason()).as("hook deny message 必须透传").isEqualTo("policy blocks");
        assertThat(decision.get().source()).isEqualTo(CoordinatorPermissionHandler.Source.HOOK);
    }

    @Test
    @DisplayName("S07-3 hook 无决策 → handle 返回 empty (fall through 到 classifier/dialog)")
    void hookNoDecision_fallsThrough() {
        // WHY: CC runHooks 无 permissionRequestResult → null → coordinator 继续 classifier /
        //      fall through 到交互 dialog (coordinatorHandler.ts:59-61).
        HookRegistry registry = new HookRegistry();
        registry.register("perm-silent", event -> GenericHook.HookResult.proceed(),
            HookEventType.PERMISSION_REQUEST);

        CoordinatorPermissionHandler handler = productionHandler(registry);
        assertThat(handler.handle(params()))
            .as("hook 未表态 → Optional.empty (fall through)")
            .isEmpty();
    }

    @Test
    @DisplayName("S07-4 hookRegistry 未接线 → fall through (不假实现, 与 H9 前行为一致)")
    void noHookRegistry_fallsThrough() {
        CoordinatorPermissionHandler handler = productionHandler(null);
        assertThat(handler.handle(params())).isEmpty();
    }

    @Test
    @DisplayName("S07-5 [WF3-X6] hook allow 携带 updatedInput + updatedPermissions → 决策透传 (规则变更不再静默丢弃)")
    void hookAllowWithUpdatedPayload_carriesIntoDecision() {
        // WHY: CC PermissionContext.ts:233-239 handleHookAllow — finalInput = updatedInput ?? input,
        //      + persistPermissions(updatedPermissions) (PermissionContext.ts:324-325).
        //      交叉核验 X-WF7-06 不变量 A: coordinator 路径此前只 log hasUpdatedInput,
        //      gate 用原 input 放行, updatedPermissions 静默丢弃 — "hook 批准的规则变更不生效".
        HookRegistry registry = new HookRegistry();
        registry.register("perm-allow-upd", event ->
            GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Allow(
                    Map.of("command", "git status --short"),
                    List.of(Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session")))),
            HookEventType.PERMISSION_REQUEST);

        CoordinatorPermissionHandler handler = productionHandler(registry);
        var decision = handler.handle(params());

        assertThat(decision).isPresent();
        assertThat(decision.get().decision()).isEqualTo("allow");
        assertThat(decision.get().updatedInput())
            .as("hook allow 的 updatedInput 必须透传给 gate (改写后输入执行)")
            .isEqualTo(Map.of("command", "git status --short"));
        assertThat(decision.get().updatedPermissions())
            .as("hook allow 的 updatedPermissions 必须解析为 PermissionUpdate 透传给 gate (apply+persist)")
            .hasSize(1);
        assertThat(decision.get().interrupt()).isFalse();
    }

    @Test
    @DisplayName("S07-6 [WF3-X6] hook deny interrupt=true → 决策携带 interrupt (gate 将 abort 会话)")
    void hookDenyInterrupt_carriesInterruptFlag() {
        // WHY: CC PermissionContext.ts:245-250 — deny && interrupt → abortController.abort()
        //      会话级中断. 交叉核验 X-WF7-06 不变量 B: coordinator 路径此前不检查
        //      deny.interrupt (Javadoc concern S07 显式登记) — "拒绝并中断"降级为"仅拒绝本次".
        HookRegistry registry = new HookRegistry();
        registry.register("perm-deny-interrupt", event ->
            GenericHook.HookResult.proceed()
                .withPermissionRequestResult(new PermissionRequestResult.Deny("blocked", true)),
            HookEventType.PERMISSION_REQUEST);

        CoordinatorPermissionHandler handler = productionHandler(registry);
        var decision = handler.handle(params());

        assertThat(decision).isPresent();
        assertThat(decision.get().decision()).isEqualTo("deny");
        assertThat(decision.get().reason()).isEqualTo("blocked");
        assertThat(decision.get().interrupt())
            .as("deny.interrupt=true 必须透传, gate 才能 abort 会话")
            .isTrue();
    }

    @Test
    @DisplayName("OPD-WF7-02-01 coordinator classifier runner: Bash + feature 开 + 投机命中 → allow(source=CLASSIFIER) + matchedRule 登记")
    void classifierHit_returnsClassifierAllow() {
        // WHY: CC PermissionContext.ts:174-215 tryClassifier（awaitClassifierAutoApproval + matchedRule）—
        //      feature('BASH_CLASSIFIER') 开时 coordinator 路径 classifier 应 auto-approve（coordinatorHandler.ts:41-46）。
        //      旧生产 runner 恒 null stub（O18 删启发式）→ 激活 BASH_CLASSIFIER 即漂移（R2）。
        Environment env = mock(Environment.class);
        when(env.getProperty("nexusai.feature.bash-classifier")).thenReturn("true");
        BashClassifierFeature feature = new BashClassifierFeature(env);
        CoordinatorPermissionHandler handler = new CoordinatorPermissionHandler(feature, null);

        SpeculativeClassifier.seedSpeculativeClassifierCheckForTest("ls -la",
            new SpeculativeClassifier.SpeculativeClassifierResult(
                true, "allowed rule", "high", "Allowed by prompt rule: \"allowed rule\""));
        CoordinatorPermissionHandler.Params p = new CoordinatorPermissionHandler.Params(
            new CoordinatorPermissionHandler.PendingClassifierCheck("Bash", "ls -la"),
            Map.of(), List.of(), "default", "Bash",
            JSON.createObjectNode().put("command", "ls -la"),
            "call_c1", "sess-1", "agent-1");

        var decision = handler.handle(p);
        assertThat(decision)
            .as("classifier 命中 → handle 必须返回决策（不再恒 Optional.empty）")
            .isPresent();
        assertThat(decision.get().decision()).isEqualTo("allow");
        assertThat(decision.get().source())
            .as("classifier 决策 source 必须为 CLASSIFIER（gate 上报 granted_by_classifier）")
            .isEqualTo(CoordinatorPermissionHandler.Source.CLASSIFIER);
        assertThat(decision.get().decisionReason())
            .as("decisionReason 必须携带 bash_allow classifier 归因（CC buildAllow decisionReason）")
            .isInstanceOf(PermissionDecisionReason.Classifier.class);
        // CC PermissionContext.ts:191-201 TRANSCRIPT_CLASSIFIER → matchedRule → setClassifierApproval
        assertThat(ClassifierApprovals.getClassifierApproval("call_c1", null))
            .as("matchedRule 必须登记到 ClassifierApprovals（CC setClassifierApproval）")
            .isEqualTo("allowed rule");
    }

    @Test
    @DisplayName("OPD-WF7-02-01 coordinator classifier runner: feature 关 → handle 跳过 classifier（fall through）")
    void classifierFeatureOff_fallsThrough() {
        // WHY: coordinatorHandler.ts:41-43 feature('BASH_CLASSIFIER') 门 → tryClassifier 不执行
        //      （CC 双端关闭下等效）；runner 结构完整但门控关闭即 fall through 交互弹窗。
        CoordinatorPermissionHandler handler = new CoordinatorPermissionHandler(null, null);
        CoordinatorPermissionHandler.Params p = new CoordinatorPermissionHandler.Params(
            new CoordinatorPermissionHandler.PendingClassifierCheck("Bash", "ls -la"),
            Map.of(), List.of(), "default", "Bash",
            JSON.createObjectNode().put("command", "ls -la"),
            "call_c2", "sess-1", "agent-1");
        assertThat(handler.handle(p)).isEmpty();
    }

    @Test
    @DisplayName("DEC-WF7-02-04 EV-086: SPECULATIVE_WAIT_TIMEOUT_MS 已移除（awaitClassifierAutoApproval 无超时, 对齐 CC abort signal）")
    void speculativeWaitTimeoutRemoved() {
        // RED→GREEN：删除前 CoordinatorPermissionHandler 声明 SPECULATIVE_WAIT_TIMEOUT_MS=2s
        // 静态字段 → getDeclaredField 不抛 NoSuchFieldException → 断言失败（RED）。
        // 删除后字段不存在 → 抛 NoSuchFieldException → 通过（GREEN）。
        // WHY（规则九 · 验证意图）：CC awaitClassifierAutoApproval（bashPermissions.ts:1555-1587）
        // 无超时——取消由 abort signal 驱动（gate 消费路径 isAborted 统一处理），2s 有界等待为
        // Java 独有（EV-WF7-HP-086），用户 2026-08-18 拍板移除对齐 CC。
        assertThatThrownBy(() -> CoordinatorPermissionHandler.class
                .getDeclaredField("SPECULATIVE_WAIT_TIMEOUT_MS"))
            .as("SPECULATIVE_WAIT_TIMEOUT_MS 必须已移除（DEC-WF7-02-04 EV-086：CC 无超时, 改 abort signal）")
            .isInstanceOf(NoSuchFieldException.class);
    }
}
