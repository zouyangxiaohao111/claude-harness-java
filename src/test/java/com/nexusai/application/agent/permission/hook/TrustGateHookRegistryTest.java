package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.mcp.HeadersHelper;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-04] workspace trust 门控测试 · 三入口（executeEvent / executePreToolUse /
 * executeSessionHooks）接入 trust 门控（对齐 CC shouldSkipHookDueToTrust hooks.ts:286-296）
 * + executeEventAll / env hook 链（CC executeHooksOutsideREPL :3031 同门控）。
 *
 * <p>WHY (D9 / OD-13 / EV-L01-030): CC 自述历史漏洞（SessionEnd 在拒绝 trust 后执行，
 * hooks.ts:280-283）防 RCE 的安全门 —— 交互模式全部 hook 要求 workspace trust；
 * Java hook 执行入口此前无等价门控（EV-CCE-034: Java 全库 shouldSkipHookDueToTrust
 * 0 命中）。本测试锁定修正后的不变量 INV-9（交互模式全部 hook 要求 trust；
 * 非交互路径不跳过）。
 *
 * <p>CC 真源行为（hooks.ts:286-296，不信注释看行为）:
 * <pre>
 *   isInteractive = !getIsNonInteractiveSession()   // 非交互 (SDK/-p) → trust 隐式
 *   if (!isInteractive) return false
 *   return !checkHasTrustDialogAccepted()           // 交互模式: 未接受 trust → 跳过
 * </pre>
 *
 * <p>不依赖 Spring 容器：手动构造 HookRegistry + {@code setTrustGateSuppliers} 注入
 * supplier（mirror {@link HeadersHelper} 注入式 BooleanSupplier 契约）。
 */
@DisplayName("[IMPL-04] workspace trust 门控（三入口 + executeEventAll + env 链，对齐 CC shouldSkipHookDueToTrust）")
class TrustGateHookRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 固定会话 UUID · WHY（IMP-HR-07 测试调和）: isSessionHookEligible 要求事件 ∈ CC appState
     *  发射点集合 且 会话活跃（LlmAgentLoop.isSessionRunning）。旧非 UUID "s1" parse 失败 →
     *  门控 false → session hook 永不执行，trust 门控无法被真实测到。本类 session-hook 用例
     *  用本 UUID + markRunning 建立活跃会话（对齐 HookRegistryTest 6a/6b/6c 同款 seam）。 */
    private static final String SESSION_UUID = "00000000-0000-0000-0000-0000000000c1";

    // ── 构造 helper ─────────────────────────────────────────────────────────

    /** 组装 registry：trust 拒绝（交互模式 + 未接受 trust）. */
    private HookRegistry registryTrustRejected() {
        HookRegistry registry = new HookRegistry();
        registry.setTrustGateSuppliers(() -> false, () -> false);
        return registry;
    }

    /** 组装 registry：trust 接受. */
    private HookRegistry registryTrustAccepted() {
        HookRegistry registry = new HookRegistry();
        registry.setTrustGateSuppliers(() -> false, () -> true);
        return registry;
    }

    /** 组装 registry：非交互会话（无 trust 概念）→ 不跳过. */
    private HookRegistry registryNonInteractive() {
        HookRegistry registry = new HookRegistry();
        registry.setTrustGateSuppliers(() -> true, () -> false);
        return registry;
    }

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. executeEvent 入口：trust 拒绝 → 跳过（零副作用）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. trust 拒绝（交互+未接受）→ executeEvent 跳过注册 GenericHook（零副作用）")
    void trustRejected_executeEvent_skipsHooks() {
        // WHY (D9 / INV-9): CC executeHooks 入口 trust 门控 (hooks.ts:1994) —— 拒绝 trust 后
        //   SessionEnd 等 hook 不得执行（历史漏洞防 RCE）。旧实现无门控，hook 照跑。
        HookRegistry registry = registryTrustRejected();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("trust-gate-ev",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.stop("不该执行");
            },
            HookEventType.STOP);

        GenericHook.HookResult result = registry.executeEvent(HookEvent.stop("s1", null, false, null));

        assertThat(ran).as("trust 拒绝时 executeEvent 不得执行任何 hook").isFalse();
        assertThat(result.preventContinuation()).as("跳过结果必须无干预 (proceed)").isFalse();
    }

    @Test
    @DisplayName("2. trust 接受 → executeEvent 正常执行（回归）")
    void trustAccepted_executeEvent_runsHooks() {
        // WHY: 门控只应在 trust 拒绝时生效；接受后 hook 链必须原样执行（验收 4 回归）。
        HookRegistry registry = registryTrustAccepted();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("trust-gate-ev-ok",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.proceed();
            },
            HookEventType.STOP);

        registry.executeEvent(HookEvent.stop("s1", null, false, null));

        assertThat(ran).as("trust 接受时 executeEvent 必须正常执行").isTrue();
    }

    @Test
    @DisplayName("3. 非交互会话（无 trust 概念）→ executeEvent 不跳过（对齐 CC :289-291）")
    void nonInteractive_executeEvent_neverSkips() {
        // WHY (验收 5 / CC hooks.ts:288-291): 非交互模式 (SDK/-p) trust 隐式 ——
        //   isInteractive=false → shouldSkipHookDueToTrust 恒 false，即使 trust 未接受。
        HookRegistry registry = registryNonInteractive();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("trust-gate-ev-ni",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.proceed();
            },
            HookEventType.STOP);

        registry.executeEvent(HookEvent.stop("s1", null, false, null));

        assertThat(ran).as("非交互会话 trust 隐式，hook 必须执行").isTrue();
    }

    @Test
    @DisplayName("4. 未接线 supplier → 旧行为不跳过（手动构造/测试回归面）")
    void unwiredSuppliers_executeEvent_runsHooks() {
        // WHY: 与策略快照 null 语义一致（IMPL-01 模式）—— 未注入 supplier 的构造场景
        //   （大量手动 new HookRegistry 的既有测试）不得被 trust 门控破坏。
        HookRegistry registry = new HookRegistry();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("trust-gate-ev-unwired",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.proceed();
            },
            HookEventType.STOP);

        registry.executeEvent(HookEvent.stop("s1", null, false, null));

        assertThat(ran).as("未接线 supplier → 旧行为，hook 必须执行").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. executePreToolUse 入口：trust 拒绝 → 工具不被 hook 阻断/干预
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. trust 拒绝 → executePreToolUse 跳过 programmatic PreToolUse hook")
    void trustRejected_executePreToolUse_skipsProgrammatic() {
        // WHY (验收 2 / INV-9): CC executePreToolHooks = yield* executeHooks（入口门控
        //   hooks.ts:1994）—— trust 拒绝时工具调用不得被任何 hook 阻断/改写。
        HookRegistry registry = registryTrustRejected();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.registerPreToolUse("trust-gate-ptu", (toolName, input, ctx) -> {
            ran.set(true);
            return AggregatedHookResult.proceed();
        });

        AggregatedHookResult outcome = registry.executePreToolUse("Bash", JSON.createObjectNode(), null);

        assertThat(ran).as("trust 拒绝时 programmatic PreToolUse hook 不得执行").isFalse();
        assertThat(outcome.permissionBehavior()).as("跳过结果必须无干预 (proceed)").isNull();
    }

    @Test
    @DisplayName("6. trust 接受 → executePreToolUse 正常执行（回归）")
    void trustAccepted_executePreToolUse_runsHooks() {
        HookRegistry registry = registryTrustAccepted();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.registerPreToolUse("trust-gate-ptu-ok", (toolName, input, ctx) -> {
            ran.set(true);
            return AggregatedHookResult.proceed();
        });

        registry.executePreToolUse("Bash", JSON.createObjectNode(), null);

        assertThat(ran).as("trust 接受时 programmatic PreToolUse hook 必须执行").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. executeSessionHooks：trust 拒绝 → session hook 跳过（经 executeEvent 观察）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("7. trust 拒绝 → session function hook 不执行（executeSessionHooks 门控）")
    void trustRejected_sessionFunctionHook_skipped() {
        // WHY (验收 3 / INV-9): session 作用域 hook 同属 CC getMatchingHooks 合并链
        //   （hooksSettings.ts:146-158）→ 同走 executeHooks 入口门控。executeSessionHooks
        //   是 private（仅 executeEvent 调用），经 executeEvent 观察零副作用。
        HookRegistry registry = registryTrustRejected();

        AtomicBoolean ran = new AtomicBoolean(false);
        // matcher 用 "*": STOP 事件 matchQuery=null → 全部 matcher 通过 (HookMatcherEngine
        //   :668-671); matcher=null 会触发 SessionHookStore.getSessionHookCallback NPE
        //   (既有缺陷, 非本任务范围).
        // [IMP-HR-07 测试调和] 会话运行中（markRunning）但 trust 拒绝 → session hook 仍必须跳过，
        //   否则本用例会因门控 false 而"假通过"（非 trust 门控被真实测试）。
        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.STOP, "*",
            (messages, signal) -> {
                ran.set(true);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            },
            null, null, null);

        LlmAgentLoop.markRunning(SESSION_UUID);
        try {
            GenericHook.HookResult result = registry.executeEvent(
                HookEvent.stop(SESSION_UUID.toString(), null, false, null));

            assertThat(ran).as("trust 拒绝时 session hook 不得执行").isFalse();
            assertThat(result.preventContinuation()).as("跳过结果必须无干预 (proceed)").isFalse();
        } finally {
            LlmAgentLoop.markIdle(SESSION_UUID);
        }
    }

    @Test
    @DisplayName("8. trust 接受 → session function hook 正常执行（回归）")
    void trustAccepted_sessionFunctionHook_runs() {
        HookRegistry registry = registryTrustAccepted();

        AtomicBoolean ran = new AtomicBoolean(false);
        // [IMP-HR-07 测试调和] 会话运行中（markRunning）+ trust 接受 → session hook 必须执行。
        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.STOP, "*",
            (messages, signal) -> {
                ran.set(true);
                return CompletableFuture.completedFuture(Boolean.TRUE);
            },
            null, null, null);

        LlmAgentLoop.markRunning(SESSION_UUID);
        try {
            registry.executeEvent(HookEvent.stop(SESSION_UUID.toString(), null, false, null));

            assertThat(ran).as("trust 接受时 session hook 必须执行").isTrue();
        } finally {
            LlmAgentLoop.markIdle(SESSION_UUID);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. executeEventAll（CC executeHooks 全结果消费者等价入口）：同门控
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("9. trust 拒绝 → executeEventAll 早返空列表")
    void trustRejected_executeEventAll_returnsEmpty() {
        // WHY: 只堵 executeEvent 会留下第二条执行通道（IMPL-01 同款理由）；
        //   CC executeHooks :1994 门控覆盖全部消费者（TaskCreate/TaskCompleted 等）。
        HookRegistry registry = registryTrustRejected();

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("trust-gate-all",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.proceed();
            },
            HookEventType.TASK_COMPLETED);

        List<GenericHook.HookResult> results =
            registry.executeEventAll(HookEvent.taskCompleted("t1", "s", "sid", null));

        assertThat(results).as("trust 拒绝时 executeEventAll 必须返回空列表").isEmpty();
        assertThat(ran).as("trust 拒绝时 executeEventAll 不得执行任何 hook").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. env hook 链（CC executeEnvHooks → executeHooksOutsideREPL :3031 同门控）
    // ════════════════════════════════════════════════════════════════════════

    /** settings 配 1 条 CwdChanged command hook → registry（含真实 executor + fake launcher）. */
    private HookRegistry registryWithEnvHook(CommandHookExecutorTest.FakeLauncher launcher) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.CWD_CHANGED,
                commandHook("echo trust-env"), ".envrc", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(new HookMatcherEngine(snapshot, new PermissionRuleValueParser()));
        CommandHookExecutor executor = new CommandHookExecutor(launcher,
            k -> null, p -> true, () -> "C:/project", id -> "C:/plugins/" + id);
        registry.setCommandHookExecutor(executor);
        return registry;
    }

    private static CommandHookExecutorTest.FakeHookProcess watchProc() {
        return CommandHookExecutorTest.FakeHookProcess.normal(
            "{\"hookSpecificOutput\": {\"hookEventName\": \"CwdChanged\","
                + " \"watchPaths\": [\"/tmp/watch1\"]}}",
            "", 0);
    }

    @Test
    @DisplayName("10. trust 拒绝 → CwdChanged env hook 短路（进程不启动，watchPaths 为空）")
    void trustRejected_envHook_shortCircuits() {
        // WHY (验收 5 边界 / CC hooks.ts:4249→3031): executeEnvHooks 走
        //   executeHooksOutsideREPL —— 交互模式下 env hook 同样要求 trust。
        CommandHookExecutorTest.FakeHookProcess proc = watchProc();
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(proc);
        HookRegistry registry = registryWithEnvHook(launcher);
        registry.setTrustGateSuppliers(() -> false, () -> false);

        List<String> watchPaths =
            registry.executeCwdChangedHooksCollectingWatchPaths("/old/cwd", "/new/cwd", "s1").watchPaths();

        assertThat(watchPaths).as("trust 拒绝时 env hook 必须短路，watchPaths 为空").isEmpty();
        assertThat(launcher.lastSpec)
            .as("trust 拒绝时进程启动器不得被调用（短路先于任何执行）").isNull();
    }

    @Test
    @DisplayName("11. trust 接受 → CwdChanged env hook 正常执行并产出 watchPaths（正控）")
    void trustAccepted_envHook_executesAndCollectsWatchPaths() {
        // WHY: 正控 —— 证明 10 的断言有效：同一配置 trust 接受时 hook 执行并产出
        //       watchPaths，短路断言是"执行被跳过"而非"hook 本就无输出"。
        CommandHookExecutorTest.FakeHookProcess proc = watchProc();
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(proc);
        HookRegistry registry = registryWithEnvHook(launcher);
        registry.setTrustGateSuppliers(() -> false, () -> true);

        List<String> watchPaths =
            registry.executeCwdChangedHooksCollectingWatchPaths("/old/cwd", "/new/cwd", "s1").watchPaths();

        assertThat(watchPaths).as("trust 接受时 env hook 必须执行并收集 watchPaths")
            .containsExactly("/tmp/watch1");
    }
}
