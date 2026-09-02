package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H9] ToolPermissionGate Ask 分发链测试 · 对齐 CC
 * {@code useCanUseTool.tsx:93-169} (ask 分支) + {@code PermissionContext.ts:148-173}
 * (resolveIfAborted / cancelAndAbort / REJECT_MESSAGE 模板) +
 * {@code permissionLogging.ts:181-235} (logPermissionDecision 遥测).
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: H9 把 Coordinator / SwarmWorker / Interactive
 * 三 handler 接进 {@link ToolPermissionGate#check} 的 Ask 分发链。每个测试钉死 CC 分发
 * 顺序的一个不变量 —— 顺序错了 (如 swarm 在 coordinator 之前、或 interactive 抢在
 * classifier 竞速之前) 测试必须红，否则"已对齐 CC"就是假的。
 *
 * <p>CC 真源顺序 (useCanUseTool.tsx:93-169):
 * <ol>
 *   <li>awaitAutomatedChecksBeforeDialog → coordinator handler (runHooks → tryClassifier)</li>
 *   <li>resolveIfAborted 预检</li>
 *   <li>swarm worker handler (返回非空即采用)</li>
 *   <li>bash classifier 投机竞速 (非 awaitAutomatedChecksBeforeDialog)</li>
 *   <li>interactive handler (queue + 弹窗, P3 前保留同步阻塞)</li>
 * </ol>
 *
 * <p>所有决策出口都要走 {@code logPermissionDecision} 遥测 (tengu_* 事件 + waitMs +
 * code-edit counter + toolDecisions 时间戳归因)。
 *
 * @see ToolPermissionGate
 * @since Session H9
 */
class CanUseToolDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    // ─────────────────────────────── 测试桩 ───────────────────────────────

    /**
     * 测试桩 Tool · 仅承载 name()/description()（对齐 PermissionBubbleServiceCallerTest 模式）。
     */
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

    /**
     * 可配置返回结果的 PermissionPipeline 桩 · 记录 check 调用次数.
     *
     * <p>WHY 计数: forceDecision 测试断言"跳过规则检查"—— pipeline.check 必须 0 次调用.
     */
    private static final class StubPipeline extends PermissionPipeline {
        PermissionResult result;
        final AtomicInteger checkCalls = new AtomicInteger();
        StubPipeline(PermissionResult result) { this.result = result; }
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                       ToolUseContext ctx, ToolPermissionContext permCtx) {
            checkCalls.incrementAndGet();
            return result;
        }
    }

    /**
     * 记录调用次数的 PermissionPrompter 桩 · 返回可配置结果.
     *
     * <p>WHY 计数: 默认分支必须"同步阻塞弹窗"—— coordinator/swarm/classifier 分支命中时
     * prompter 必须 0 次调用 (分发正确), 只有 interactive 分支才调用.
     */
    private static final class RecordingPrompter implements PermissionPrompter {
        PermissionResult response;
        final AtomicInteger calls = new AtomicInteger();
        RecordingPrompter(PermissionResult response) { this.response = response; }
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input, PermissionDecisionReason reason,
                                        ToolUseContext ctx, String requestId) {
            calls.incrementAndGet();
            return response;
        }
    }

    private static final class RecordingNotification {
        final List<Notification> notifications = new ArrayList<>();
    }

    // ─────────────────────────────── 构造辅助 ───────────────────────────────

    /** permCtx · awaitAutomatedChecksBeforeDialog 由调用方决定 (对齐 CC Tool.ts:133). */
    private static ToolPermissionContext newPermCtx(boolean awaitAutomatedChecksBeforeDialog) {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, awaitAutomatedChecksBeforeDialog, null);
    }

    /** ctx · 可注入自定义 AbortController (abort 预检测试需要). */
    private static ToolUseContext newCtx(ToolPermissionContext permCtx, AbortController abortController) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    /** ctx · 46 参 canonical 构造器 + 可记录的 addNotification (auto-mode deny 通知断言). */
    private static ToolUseContext newCtxWithNotification(ToolPermissionContext permCtx,
                                                          RecordingNotification recorder) {
        return new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", java.nio.file.Paths.get("."), set -> java.util.Set.of(), Map.of(),
            ev -> {}, s -> s, upd -> {}, m -> {}, st -> {},
            recorder.notifications::add, v -> {}, v -> {}, v -> {}, v -> {},
            v -> {}, v -> {}, v -> {}, v -> {}, v -> {},
            false, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
            null, false, false, Map.of(), Map.of(), Map.of(),
            null, null, null, null);
    }

    private static ToolUseBlock newCall(String id, String toolName) {
        return new ToolUseBlock(id, toolName, JSON.createObjectNode());
    }

    private static PermissionResult askResult(PermissionResult.PendingClassifierCheck pendingClassifierCheck) {
        return new PermissionResult.Ask(
            "stub ask", new PermissionDecisionReason.Other("test"), List.of(),
            null, null, null, false, pendingClassifierCheck, List.of());
    }

    /** 无分类器检查的 Ask（boolean false → null 结构体, H14 升级）. */
    private static PermissionResult askResultNoCheck() {
        return askResult(null);
    }

    /** 默认 gate · 三 handler 全部注入 (coordinator/swarm 可单独覆写). */
    private static ToolPermissionGate newGate(StubPipeline pipeline, RecordingPrompter prompter,
                                              CoordinatorPermissionHandler coordinator,
                                              SwarmWorkerPermissionHandler swarm,
                                              Telemetry telemetry) {
        PermissionDecisionLogger logger = new PermissionDecisionLogger(telemetry);
        InteractiveHandler interactive = new InteractiveHandler(prompter);
        return new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            coordinator, swarm, interactive, logger, null, null, null);
    }

    /** 默认 gate + BASH_CLASSIFIER 特性启用 · 投机竞速分支可达 (CC feature('BASH_CLASSIFIER')=true). */
    private static ToolPermissionGate newGateWithBashClassifier(StubPipeline pipeline, RecordingPrompter prompter) {
        BashClassifierFeature bashClassifierFeature = Mockito.mock(BashClassifierFeature.class);
        Mockito.when(bashClassifierFeature.isEnabled()).thenReturn(true);
        return new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            null, null, new InteractiveHandler(prompter), new PermissionDecisionLogger(null),
            null, null, null, bashClassifierFeature);
    }

    private static CoordinatorPermissionHandler allowCoordinator() {
        return new CoordinatorPermissionHandler(
            () -> false,
            params -> new CoordinatorPermissionHandler.PermissionDecision("allow", "hook approved"),
            (check, input, toolUseId) -> null,
            ex -> {});
    }

    private static CoordinatorPermissionHandler denyCoordinator() {
        return new CoordinatorPermissionHandler(
            () -> false,
            params -> new CoordinatorPermissionHandler.PermissionDecision("deny", "coordinator says no"),
            (check, input, toolUseId) -> null,
            ex -> {});
    }

    /**
     * classifier 来源的 coordinator 桩 · HooksRunner 恒 null (hook 未表态),
     * ClassifierRunner 返回 classifier 决策 (CC tryClassifier source = classifier).
     */
    private static CoordinatorPermissionHandler classifierCoordinator() {
        return new CoordinatorPermissionHandler(
            () -> true,
            params -> null,
            (check, input, toolUseId) -> new CoordinatorPermissionHandler.PermissionDecision(
                "allow", "classifier approved", CoordinatorPermissionHandler.Source.CLASSIFIER),
            ex -> {});
    }

    /** 立即完成 leader allow 的 swarm handler 桩. */
    private static SwarmWorkerPermissionHandler allowSwarm() {
        return new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> false,
            null,
            request -> {},          // mailboxSender (无实际发送)
            (requestId, toolUseId, onAllow, onReject) ->
                onAllow.accept(new SwarmPermissionPoller.AllowResult(Map.of(), List.of())),   // 注册时立即模拟 leader allow 响应
            pending -> {},
            () -> {});
    }

    /** [OPD-WF7-02-02] 立即完成 leader allow + 携带 permissionUpdates 的 swarm handler 桩. */
    private static SwarmWorkerPermissionHandler allowSwarmWithUpdates() {
        return new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> false,
            null,
            request -> {},
            (requestId, toolUseId, onAllow, onReject) ->
                onAllow.accept(new SwarmPermissionPoller.AllowResult(Map.of("command", "ls -la"),
                    List.of(new PermissionUpdate.SetMode(
                        PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS)))),
            pending -> {},
            () -> {});
    }

    /**
     * [perm-timeout] 挂起的 swarm handler 桩 · CallbackRegistrar 仅保存 onAllow/onReject 回调
     * 不调用 → handle() 返回的 future 永不完成（等待挂起）。
     *
     * <p>latch 在 registrar.register 时放行，确定性等 check 进入 swarm 无限等待
     * （对齐 CC swarmWorkerHandler.ts:67-147 —— worker 无限等待 leader 决策，无 30s 超时）。
     *
     * @param registered latch · register 回调被调时 countDown（测试等 gate 已进入等待）
     */
    private static SwarmWorkerPermissionHandler pendingSwarm(CountDownLatch registered) {
        return new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> false,
            null,
            request -> {},          // mailboxSender (无实际发送)
            (requestId, toolUseId, onAllow, onReject) -> {
                registered.countDown();  // 注册完成 → 放行主线程
                // 不调用 onAllow/onReject → decisionFuture 永不完成（挂起等待）
            },
            pending -> {},
            () -> {});
    }

    // ─────────────────────── 1. coordinator 分支 (CC :95-109) ───────────────────────

    @Test
    @DisplayName("awaitAutomatedChecksBeforeDialog=true → coordinator 分支采用 hook allow, prompter 不弹窗")
    void awaitAutomatedChecksBeforeDialog_routesToCoordinatorAndAdoptsAllow() {
        // WHY: CC 真源 :95-109 — coordinator worker 的自动化检查 (hooks) 顺序 await,
        // 命中即 resolve, 不再弹窗给用户. 若 Java 端仍走 prompter → 分发链未接线, 必须红.
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("should not be asked"), "c", false, null, List.of()));
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, allowCoordinator(), null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_1", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get())
            .as("coordinator 分支命中 → interactive 弹窗必须 0 次 (CC :105-108 resolve 即返回)")
            .isZero();
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_granted_by_permission_hook"), Mockito.anyMap());
    }

    @Test
    @DisplayName("coordinator 分支 hook deny → 直接阻断, 不弹窗")
    void awaitAutomatedChecksBeforeDialog_coordinatorDenyBlocks() {
        // WHY: CC :240-259 runHooks deny → buildDeny + resolve — 自动化检查有权拒绝,
        // 不应再问用户 (否则 hook deny 形同虚设).
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(null);

        ToolPermissionGate gate = newGate(pipeline, prompter, denyCoordinator(), null, null);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_2", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("coordinator deny 消息必须透传为 deny message")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.message()).contains("coordinator says no"));
        assertThat(prompter.calls.get()).isZero();
    }

    @Test
    @DisplayName("coordinator classifier-allow → granted_by_classifier 遥测 (H9-GAP-1 source 归因)")
    void coordinatorBranch_classifierAllowEmitsClassifierTelemetry() {
        // WHY (H9-GAP-1): CC 的 tryClassifier 决策 source 是 {type:'classifier'}
        // (PermissionContext.ts:204) → granted_by_classifier (permissionLogging.ts:121-129);
        // hook 决策 source 是 {type:'hook'} → granted_by_permission_hook (:140-145)。Java gate
        // 必须按来源细分遥测, 若一律按 hook 上报 → classifier 命中污染 hook 漏斗, 必须红.
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(null);
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, classifierCoordinator(), null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(true);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_2b", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get()).isZero();
        // classifier 来源 → granted_by_classifier, 绝不发 granted_by_permission_hook
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_granted_by_classifier"), Mockito.anyMap());
        Mockito.verify(telemetry, Mockito.never()).recordEvent(
            Mockito.eq("tengu_tool_use_granted_by_permission_hook"), Mockito.anyMap());
    }

    @Test
    @DisplayName("awaitAutomatedChecksBeforeDialog=false → coordinator 分支不触发")
    void awaitAutomatedChecksBeforeDialog_falseSkipsCoordinator() {
        // WHY: CC :95 分支条件 — 仅 awaitAutomatedChecksBeforeDialog=true 才顺序 await
        // 自动化检查; false 时 hooks/classifier 由 interactive 分支异步竞速 (P3), 不能提前阻塞.
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), "c", false, null, List.of()));
        CoordinatorPermissionHandler spy = new CoordinatorPermissionHandler(
            () -> false,
            params -> {
                throw new AssertionError("awaitAutomatedChecksBeforeDialog=false 时 coordinator 必须不被调用 (CC :95)");
            },
            (check, input, toolUseId) -> null,
            ex -> {});

        ToolPermissionGate gate = newGate(pipeline, prompter, spy, null, null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_3", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get())
            .as("默认分支 → interactive 弹窗恰好 1 次 (同步阻塞)")
            .isEqualTo(1);
    }

    // ─────────────────────── 2. swarm worker 分支 (CC :113-125) ───────────────────────

    @Test
    @DisplayName("swarm worker 条件满足 → swarm 分支采用 leader allow, 不弹窗")
    void swarmWorkerCondition_swarmBranchAdoptsLeaderAllow() {
        // WHY: CC :113-125 — swarm worker 的权限请求转发给 leader, leader allow 即放行;
        // 若 Java 端未接 swarm 分支而直接弹窗 → 与 CC 行为不符, 必须红.
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("should not be asked"), "c", false, null, List.of()));

        ToolPermissionGate gate = newGate(pipeline, prompter, null, allowSwarm(), null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_4", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get())
            .as("swarm leader allow → interactive 弹窗必须 0 次 (CC :122-125)")
            .isZero();
    }

    @Test
    @DisplayName("OPD-WF7-02-02 swarm allow 携带 permissionUpdates → gate apply+persist（对齐 CC handleUserAllow persistPermissions）")
    void swarmAllowWithUpdates_applyAndPersist() {
        // WHY: CC handleUserAllow（PermissionContext.ts:291-318）经 persistPermissions 持久化 leader
        //      "Always allow" 规则；旧 Java swarm allow 分支 permissionUpdates 被丢弃（D2，严重度高）——
        //      record 携带但 gate 不消费。本测试断言 gate 在 swarm allow 时调用 applier.applyAll +
        //      persister.persistAll（修复 D2）。
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("should not be asked"), "c", false, null, List.of()));
        PermissionUpdateApplier applier = Mockito.mock(PermissionUpdateApplier.class);
        PermissionUpdatePersister persister = Mockito.mock(PermissionUpdatePersister.class);
        ToolPermissionContext permCtx = newPermCtx(false);
        Mockito.when(applier.applyAll(Mockito.anyList(), Mockito.any()))
            .thenReturn(permCtx);

        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            null, allowSwarmWithUpdates(), new InteractiveHandler(prompter),
            new PermissionDecisionLogger(null), null, applier, persister, null);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_4b", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        Mockito.verify(applier).applyAll(Mockito.anyList(), Mockito.any());
        Mockito.verify(persister).persistAll(Mockito.anyList());
    }

    @Test
    @DisplayName("[perm-timeout] swarm 无限等待被 abort 解除 → 返回取消决策（不再 30s 超时落入交互）")
    void swarmWait_abort_releasesToCancelDecision() throws Exception {
        // WHY (规则九): CC swarmWorkerHandler.ts:137-146 — worker 无限等待 leader 决策，
        //   靠 abort listener 解除等待 → cancelAndAbort（DENY + reason user_abort）。若 Java 仍走
        //   30s 有界等待超时/落入 interactive 弹窗，则与 CC 行为不符（消除 30s 的拍板），必须红。
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(null);
        CountDownLatch registered = new CountDownLatch(1);
        AbortController abort = new AbortController();   // 真实非 NOOP abort（可触发 listener）
        AtomicReference<ToolPermissionGate.DecisionResult> captured = new AtomicReference<>();

        ToolPermissionGate gate = newGate(pipeline, prompter, null, pendingSwarm(registered), null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, abort);

        Thread t = new Thread(() -> {
            ToolPermissionGate.DecisionResult r =
                gate.check(new StubTool("Read"), newCall("call_abort", "Read"),
                    JSON.createObjectNode(), ctx, permCtx);
            captured.set(r);
        });
        t.start();
        assertThat(registered.await(5, TimeUnit.SECONDS))
            .as("swarm handler 回调注册完成 → gate 已进入无限等待")
            .isTrue();
        abort.abort("permission_cancelled");
        t.join(5_000);
        assertThat(t.isAlive())
            .as("abort 必须解除 swarm 无限等待（CC swarmWorkerHandler.ts:137-146）")
            .isFalse();

        ToolPermissionGate.DecisionResult result = captured.get();
        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .as("abort 解除 → cancelAndAbort 决策（DENY + reason user_abort）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                        other -> assertThat(other.reason()).isEqualTo("user_abort")));
        assertThat(prompter.calls.get())
            .as("abort 解除等待 → 取消决策，不落入 interactive 弹窗")
            .isZero();
    }

    // ─────────────────────── 3. 默认 interactive 分支 (CC :160-167) ───────────────────────

    @Test
    @DisplayName("默认分支 → interactive 弹窗 (queue+同步阻塞), 用户 allow → ALLOW")
    void defaultBranch_interactivePromptAndSyncBlock() {
        // WHY: 无 coordinator / 无 swarm / 无 classifier 时, CC :160-167 落到 interactive
        // handler — queue push + 弹窗 + 用户响应. Java P3 前保留同步阻塞语义 (task H9 决策点 1).
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), "c", false, null, List.of()));
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        long before = System.currentTimeMillis();
        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_5", "Read"), JSON.createObjectNode(), ctx, permCtx);
        long after = System.currentTimeMillis();

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(prompter.calls.get()).isEqualTo(1);
        assertThat(after - before)
            .as("同步阻塞语义: prompter 返回后 check 立即返回")
            .isLessThan(5_000L);
        // CC permissionLogging.ts:135-137 user temporary → granted_in_prompt_temporary
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_granted_in_prompt_temporary"), Mockito.anyMap());
    }

    @Test
    @DisplayName("默认分支用户 deny → DENY + rejected_in_prompt 遥测")
    void defaultBranch_userDenyMapsToReject() {
        // WHY: 用户拒绝 → CC interactiveHandler.ts:183-203 onReject → logDecision(reject,
        // user_reject) + cancelAndAbort — Java 同步等价: DENY + REJECT_MESSAGE 模板消息.
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Deny("User denied via WebSocket",
                new PermissionDecisionReason.Other("user_denied"), "c"));
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_6", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_rejected_in_prompt"), Mockito.anyMap());
    }

    // ─────────────────────── 4. forceDecision 注入 (useCanUseTool.tsx:37) ───────────────────────

    @Test
    @DisplayName("forceDecision=Allow → 直接 ALLOW, 跳过 10 层管线")
    void forceDecision_allowSkipsPipeline() {
        // WHY: CC :37 forceDecision !== undefined → Promise.resolve(forceDecision),
        // 不再跑 hasPermissionsToUseTool. Java gate.check 6 参 (H8) 必须保持此短路语义.
        JsonNode updated = JSON.createObjectNode().put("k", "v");
        StubPipeline pipeline = new StubPipeline(null);
        RecordingPrompter prompter = new RecordingPrompter(null);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result = gate.check(
            new StubTool("Read"), newCall("call_7", "Read"), JSON.createObjectNode(), ctx, permCtx,
            new PermissionResult.Allow(updated, new PermissionDecisionReason.Other("hook ask allowed"),
                "call_7", false, null, List.of()));

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(pipeline.checkCalls.get())
            .as("forceDecision 非 null → pipeline.check 必须 0 次 (CC :37)")
            .isZero();
    }

    @Test
    @DisplayName("forceDecision=Ask → 跳过管线但仍走 ask 分发链")
    void forceDecision_askFlowsThroughDispatchChain() {
        // WHY: hook ask 场景 — forceDecision 是 hook 的 ask 消息, 仍要弹窗 (H8 语义),
        // 但不能再跑 10 层管线 (否则规则消息覆盖 hook 消息).
        StubPipeline pipeline = new StubPipeline(null);
        RecordingPrompter prompter = new RecordingPrompter(
            new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), "c", false, null, List.of()));

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result = gate.check(
            new StubTool("Read"), newCall("call_8", "Read"), JSON.createObjectNode(), ctx, permCtx,
            new PermissionResult.Ask("hook asks", new PermissionDecisionReason.Hook("PermissionRequest", null, "ask"),
                List.of(), null, null, null, false,null, List.of()));

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(pipeline.checkCalls.get()).isZero();
        assertThat(prompter.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("forceDecision=Deny → 直接 DENY, 跳过管线")
    void forceDecision_denyBlocksDirectly() {
        StubPipeline pipeline = new StubPipeline(null);
        RecordingPrompter prompter = new RecordingPrompter(null);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result = gate.check(
            new StubTool("Read"), newCall("call_9", "Read"), JSON.createObjectNode(), ctx, permCtx,
            new PermissionResult.Deny("hook denies", new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                "call_9"));

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(pipeline.checkCalls.get()).isZero();
    }

    // ─────────────────────── 5. allow / deny 分支语义 (CC :38-91) ───────────────────────

    @Test
    @DisplayName("allow 分支 → buildAllow 保留 updatedInput/decisionReason + granted_in_config 遥测")
    void allowBranch_buildAllowKeepsUpdatedInputAndDecisionReason() {
        // WHY: CC :50-53 resolve(ctx.buildAllow(result.updatedInput ?? input, {decisionReason}))
        // — updatedInput (hook 改过的输入) 必须透传到执行层, 丢了等于 hook 白改.
        JsonNode updated = JSON.createObjectNode().put("command", "git status");
        PermissionDecisionReason reason = new PermissionDecisionReason.Rule(
            new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Read")));
        StubPipeline pipeline = new StubPipeline(
            new PermissionResult.Allow(updated, reason, "call_10", false, null, List.of()));
        RecordingPrompter prompter = new RecordingPrompter(null);
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_10", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(result.result())
            .as("ALLOW 必须携带原 Allow 结果 (updatedInput + decisionReason 透传)")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow -> {
                assertThat(allow.updatedInput()).isSameAs(updated);
                assertThat(allow.reason()).isSameAs(reason);
            });
        assertThat(result.decisionInfo())
            .as("logPermissionDecision 必须返回 {source, decision, timestamp} 归因 (permissionLogging.ts:220-228)")
            .isNotNull()
            .satisfies(info -> {
                assertThat(info.source()).isEqualTo("config");
                assertThat(info.decision()).isEqualTo("accept");
                assertThat(info.timestamp()).isPositive();
            });
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_granted_in_config"), Mockito.anyMap());
    }

    @Test
    @DisplayName("投机竞速命中 → buildAllow 带 updatedInput + bash_allow classifier reason")
    void speculativeRaceHit_buildAllowWithBashAllowReason() {
        // WHY: CC useCanUseTool.tsx:149-159 — 投机分类器命中时 resolve(ctx.buildAllow(
        //   result.updatedInput ?? input, {decisionReason: classifier bash_allow})),
        //   updatedInput (hook 改过的输入) + bash_allow decisionReason 必须透传到执行层,
        //   否则与 CC buildAllow 契约错位 (F3b: 旧实现 return ALLOW-null 丢弃两者).
        JsonNode updated = JSON.createObjectNode().put("command", "git status --short");
        PermissionResult.Ask ask = new PermissionResult.Ask(
            "stub ask", new PermissionDecisionReason.Other("test"), List.of(),
            null, updated, null, false,
            new PermissionResult.PendingClassifierCheck("git status", "", List.of()),
            List.of());
        StubPipeline pipeline = new StubPipeline(ask);
        RecordingPrompter prompter = new RecordingPrompter(null);
        SpeculativeClassifier.seedSpeculativeClassifierCheckForTest(
            "git status",
            new SpeculativeClassifier.SpeculativeClassifierResult(true, "my prompt rule", "high", "allowed"));

        ToolPermissionGate gate = newGateWithBashClassifier(pipeline, prompter);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtx(permCtx, AbortController.NOOP);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_spec", "Bash"),
                JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(result.result())
            .as("投机命中必须返回带 updatedInput + bash_allow decisionReason 的 Allow (CC buildAllow)")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow -> {
                assertThat(allow.updatedInput()).isSameAs(updated);
                assertThat(allow.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Classifier.class, classifier -> {
                        assertThat(classifier.classifier())
                            .as("bash_allow 无 auto-mode 语义 (CC types/permissions.ts:303-307 分类器变体)")
                            .isEqualTo("bash_allow");
                        assertThat(classifier.reason()).isEqualTo("Allowed by prompt rule: \"my prompt rule\"");
                    });
            });
        assertThat(prompter.calls.get())
            .as("投机竞速命中 → interactive 弹窗必须 0 次 (CC :149 resolve 即返回)")
            .isZero();
    }

    @Test
    @DisplayName("deny + auto-mode classifier → recordAutoModeDenial + notification")
    void denyAutoModeClassifier_recordsDenialAndNotifies() {
        // WHY: CC :77-89 — auto-mode 分类器拒绝要进 /permissions 面板 (RecentDenialsTab)
        // + 即时通知用户; 只返回 DENY 不记录 → 用户无法复盘为什么被自动拒.
        StubPipeline pipeline = new StubPipeline(new PermissionResult.Deny(
            "denied by auto mode", new PermissionDecisionReason.Classifier("auto-mode", "risky command"),
            "call_11"));
        RecordingPrompter prompter = new RecordingPrompter(null);
        RecordingNotification notifications = new RecordingNotification();

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, null);
        ToolPermissionContext permCtx = newPermCtx(false);
        ToolUseContext ctx = newCtxWithNotification(permCtx, notifications);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Bash"), newCall("call_11", "Bash"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(AutoModeDenials.getAutoModeDenials())
            .as("auto-mode 拒绝必须记录到 denials 面板 (CC autoModeDenials.ts)")
            .isNotEmpty();
        assertThat(AutoModeDenials.getAutoModeDenials().get(0).toolName()).isEqualTo("Bash");
        // GC-04 (OPD-WF7-GC-04): SDK 面 tool_use_id/tool_input 必须记录 —
        //   CC QueryEngine.ts:260-267 permission_denials.push({tool_name, tool_use_id, tool_input}),
        //   前端据此展示"哪个工具调用被拒、输入是什么". 缺字段则前端无法复盘.
        assertThat(AutoModeDenials.getAutoModeDenials().get(0).toolUseId())
            .as("GC-04: SDK 面 tool_use_id 必须记录 (coreSchemas.ts:1401, 来源 ToolUseBlock.id)")
            .isEqualTo("call_11");
        assertThat(AutoModeDenials.getAutoModeDenials().get(0).toolInput())
            .as("GC-04: SDK 面 tool_input 必须记录 (coreSchemas.ts:1402, 来源 ToolUseBlock.input)")
            .isNotNull();
        assertThat(notifications.notifications)
            .as("auto-mode 拒绝必须推通知 (CC :84-88 addNotification)")
            .anySatisfy(n -> assertThat(n.title()).contains("denied by auto mode"));
    }

    // ─────────────────────── 6. resolveIfAborted 预检 (CC :34, PermissionContext.ts:148-153) ───────────────────────

    @Test
    @DisplayName("Ask 入口 signal aborted → 立即取消, 不弹窗")
    void askEntry_resolveIfAborted_cancelsImmediately() {
        // WHY: PermissionContext.ts:148-153 — 信号已中止时 resolveIfAborted 立即
        // logCancelled + cancelAndAbort; 此时再弹窗等 30s 是浪费 + 错误 (用户已中止).
        StubPipeline pipeline = new StubPipeline(askResultNoCheck());
        RecordingPrompter prompter = new RecordingPrompter(null);
        Telemetry telemetry = Mockito.mock(Telemetry.class);

        ToolPermissionGate gate = newGate(pipeline, prompter, null, null, telemetry);
        ToolPermissionContext permCtx = newPermCtx(false);
        AbortController aborted = new AbortController();
        aborted.abort("user_interrupt");
        ToolUseContext ctx = newCtx(permCtx, aborted);

        ToolPermissionGate.DecisionResult result =
            gate.check(new StubTool("Read"), newCall("call_12", "Read"), JSON.createObjectNode(), ctx, permCtx);

        assertThat(result.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertThat(result.result())
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.message())
                    .as("取消消息必须对齐 CC REJECT_MESSAGE 模板 (PermissionContext.ts:162-165)")
                    .contains("The user doesn't want to proceed with this tool use."));
        assertThat(prompter.calls.get()).isZero();
        Mockito.verify(telemetry).recordEvent(
            Mockito.eq("tengu_tool_use_cancelled"), Mockito.anyMap());
    }
}
