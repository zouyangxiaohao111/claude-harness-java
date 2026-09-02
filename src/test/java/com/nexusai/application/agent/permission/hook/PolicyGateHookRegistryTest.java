package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-01] 策略门控链测试 · disableAllHooks / allowManagedHooksOnly 全链生效
 * （对齐 CC hooksConfigSnapshot.ts:18-88 五分支 + hooks.ts:1978-1980/3022-3027 入口短路
 * + hooks.ts:1534-1540 managedOnly session 跳过）。
 *
 * <p>WHY (D1 / OD-07 / OD-10): 旧实现 HooksSettings 无参构造 supplier=key->null 生产恒 false、
 * executeEvent 入口无短路、快照 allowManagedHooksOnly 分支恒空 Map、session 分链照跑、
 * programmatic 链绕过政策 —— 企业策略在生产不可观察。本测试锁定修正后的五条不变量：
 * INV-1（短路先于匹配/执行）、INV-2（managedOnly 时 settings 保留/session 丢弃）、
 * INV-11（programmatic 不豁免政策）。
 *
 * <p>不依赖 Spring 容器：手动构造 HooksSettings / HooksConfigSnapshot / HookMatcherEngine。
 */
@DisplayName("[IMPL-01] 策略门控链（disableAllHooks / allowManagedHooksOnly 全链生效）")
class PolicyGateHookRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 构造 helper ─────────────────────────────────────────────────────────

    /** 组装 registry：settings → snapshot → engine → registry（含 policy 门控接线）. */
    private HookRegistry newRegistry(HooksSettings settings) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        return registry;
    }

    /** policy 键值 supplier：仅返回给定的键值对，其余 null. */
    private static HooksSettings policySettings(Map<String, Object> policy) {
        return new HooksSettings(key -> policy.get(key));
    }

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    // ── 1. INV-1: executeEvent 入口 disableAllHooks 短路 ─────────────────────

    @Test
    @DisplayName("1. disableAllHooks=true → executeEvent 早返，注册 GenericHook 不执行")
    void disableAllHooks_executeEvent_shortCircuits() {
        // WHY: 企业策略禁用全部 hook 时，programmatic GenericHook（如内置 Stop 消费端）也
        //       不得执行 —— CC executeHooks 入口短路 (hooks.ts:1978-1980)，短路先于任何执行。
        HooksSettings settings = policySettings(Map.of("disableAllHooks", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("policy-gate-test",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.stop("不该执行");
            },
            HookEventType.STOP);

        GenericHook.HookResult result = registry.executeEvent(HookEvent.stop("s1", null, false, null));

        assertThat(ran).as("disableAllHooks=true 时 GenericHook 不得执行").isFalse();
        assertThat(result.preventContinuation()).as("短路结果必须无干预").isFalse();
    }

    @Test
    @DisplayName("2. disableAllHooks=true → executeEventAll 早返空列表")
    void disableAllHooks_executeEventAll_returnsEmpty() {
        // WHY: executeEventAll 是 executeHooksOutsideREPL 等价入口（CC :3022-3027 同样短路返回 []），
        //       只堵 executeEvent 会留下第二条执行通道。
        HooksSettings settings = policySettings(Map.of("disableAllHooks", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.register("policy-gate-all-test",
            event -> {
                ran.set(true);
                return GenericHook.HookResult.proceed();
            },
            HookEventType.TASK_COMPLETED);

        List<GenericHook.HookResult> results =
            registry.executeEventAll(HookEvent.taskCompleted("t1", "s", "sid", null));

        assertThat(results).isEmpty();
        assertThat(ran).as("disableAllHooks=true 时 executeEventAll 不得执行任何 hook").isFalse();
    }

    // ── 2. INV-11: programmatic 链不豁免政策闸门（OD-07）──────────────────

    @Test
    @DisplayName("3. disableAllHooks=true → programmatic PreToolUse hook 不执行（OD-07 非豁免）")
    void disableAllHooks_programmaticPreToolUse_skipped() {
        // WHY (OD-07 ADJUDICATED): CC 的 programmatic/SDK hook 与配置 hook 同走 executeHooks
        //       入口短路 —— programmatic 不豁免。旧实现 executePreToolUse 完全绕过政策。
        HooksSettings settings = policySettings(Map.of("disableAllHooks", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.registerPreToolUse("policy-gate-ptu", (toolName, input, ctx) -> {
            ran.set(true);
            return AggregatedHookResult.proceed();
        });

        AggregatedHookResult result = registry.executePreToolUse("Bash", null, null);

        assertThat(ran).as("disableAllHooks=true 时 PreToolUse hook 不得执行").isFalse();
        assertThat(result.permissionBehavior()).isNull();
        assertThat(result.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("4. disableAllHooks=true → programmatic PostToolUse / PostToolUseFailure hook 不执行")
    void disableAllHooks_programmaticPostToolUse_skipped() {
        // WHY: CC 的 PostToolUse 链同样经 executeHooks 短路（toolHooks.ts:68-88 经 executeHooks），
        //       政策禁用时 post 链也必须零执行。
        HooksSettings settings = policySettings(Map.of("disableAllHooks", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.registerPostToolUse("policy-gate-post", (toolName, input, result, ctx, active) -> {
            ran.set(true);
            return GenericHook.HookResult.proceed();
        });

        GenericHook.HookResult post = registry.executePostToolUse("Bash", null, null, null);
        GenericHook.HookResult failure =
            registry.executePostToolUseFailure("Bash", null, null, null, false, false);

        assertThat(ran).as("disableAllHooks=true 时 PostToolUse hook 不得执行").isFalse();
        assertThat(post.preventContinuation()).isFalse();
        assertThat(failure.preventContinuation()).isFalse();
    }

    // ── 3. INV-2: managedOnly 时 session 分链跳过（方向修正：原 Java 方向相反）────

    @Test
    @DisplayName("5. allowManagedHooksOnly=true → session function hook 跳过（方向修正）")
    void allowManagedHooksOnly_sessionFunctionHook_skipped() {
        // WHY (OD-10): 旧实现方向相反 —— 快照杀 settings、session 分链照跑。CC 语义
        //       (hooks.ts:1534-1540)：managedOnly 时 session hook 全部跳过；settings 配置
        //       hook 经快照保留（policy hooks）。
        HooksSettings settings = policySettings(Map.of("allowManagedHooksOnly", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean sessionRan = new AtomicBoolean(false);
        registry.addFunctionHook("s1", HookEventType.PRE_TOOL_USE, "Bash",
            (messages, signal) -> {
                sessionRan.set(true);
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            },
            "测试拦截", null, "policy-gate-fn");

        registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertThat(sessionRan).as("allowManagedHooksOnly=true 时 session hook 必须跳过").isFalse();
    }

    @Test
    @DisplayName("6. allowManagedHooksOnly=true → session command hook 跳过（executor 不应被调用）")
    void allowManagedHooksOnly_sessionCommandHook_skipped() {
        // WHY: command 类 session hook 与 function 类同属 session 分链，managedOnly 时同样跳过；
        //       本用例验证 executeSessionHooks 门控在分派 executor 之前生效（executor 恒 null 也不炸）。
        HooksSettings settings = policySettings(Map.of("allowManagedHooksOnly", Boolean.TRUE));
        HookRegistry registry = newRegistry(settings);

        registry.addSessionHook("s1", HookEventType.PRE_TOOL_USE, "Bash",
            commandHook("echo session"), null, null);

        GenericHook.HookResult result = registry.executeEvent(HookEvent.toolPre("Bash", null, "s1", null));

        assertThat(result.preventContinuation()).isFalse();
        assertThat(result.blockingError()).isNull();
    }

    // ── 4. 快照 5 分支完整建模（EV-CFG-016：allowManagedHooksOnly 返回 policy hooks 非空）──

    @Test
    @DisplayName("7. allowManagedHooksOnly=true → 快照返回 policy hooks（非空），user hooks 被排除")
    void allowManagedHooksOnly_snapshot_returnsPolicyHooks() throws Exception {
        // WHY (EV-CFG-016): 旧实现该分支恒空 Map —— 企业配置的 managed hook 永不执行。
        //       CC hooksConfigSnapshot.ts:27-29 返回 policySettings.hooks（非空）。
        JsonNode policyHooks = mapper.readTree("""
            {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "echo policy-hook"}]}]}
            """);
        HooksSettings settings = policySettings(Map.of(
            "allowManagedHooksOnly", Boolean.TRUE,
            "hooks", policyHooks));
        // user settings 也配了同名 hook —— managedOnly 时必须被排除（方向：settings 非 managed 丢弃）
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                commandHook("echo user-hook"), "Bash", HookSource.USER_SETTINGS, null)
        ));

        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();

        List<HookMatcher> matchers = snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).as("allowManagedHooksOnly 分支必须返回 policy hooks（非空）").isNotEmpty();
        assertThat(matchers.get(0).matcher()).isEqualTo("Bash");
        assertThat(matchers.get(0).hooks())
            .extracting(h -> ((CommandHook) h).command())
            .containsExactly("echo policy-hook");
    }

    @Test
    @DisplayName("8. 无 policy → 快照返回合并 user hooks（分支 5，回归）")
    void noPolicy_snapshot_returnsMergedUserHooks() {
        // WHY: 无企业管控时走 CC 分支 5（mergedSettings.hooks），既有的 user settings 加载链
        //       不能被 5 分支建模破坏。
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                commandHook("echo user-hook"), "Bash", HookSource.USER_SETTINGS, null)
        ));

        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();

        List<HookMatcher> matchers = snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).isNotEmpty();
        assertThat(matchers.get(0).hooks())
            .extracting(h -> ((CommandHook) h).command())
            .containsExactly("echo user-hook");
    }

    @Test
    @DisplayName("9. merged disableAllHooks=true（policy 未禁）→ 快照返回 policy hooks（分支 4）")
    void mergedDisableAllHooks_snapshot_returnsPolicyHooks() throws Exception {
        // WHY: CC hooksConfigSnapshot.ts:47-49 —— 非 managed 想禁全部管不了 managed，
        //       快照仍返回 policy hooks；同时 shouldAllowManagedHooksOnly 为 true（hooks.ts:69-74）。
        // [EX_G_DisableAllHooks R1] 注入点由 setConfigStorage(FileConfigStorage) 改为
        // setDisableAllHooksMerged(boolean)（原单文件通道已删除，生产经
        // MultiSourceHooksConfigLoader 全源合并注入；端到端链路见 MultiSourceDisableAllHooksTest）。
        JsonNode policyHooks = mapper.readTree("""
            {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "echo policy-hook"}]}]}
            """);
        HooksSettings settings = policySettings(Map.of("hooks", policyHooks));
        settings.setDisableAllHooksMerged(true);

        assertThat(settings.shouldAllowManagedHooksOnly())
            .as("merged disableAllHooks && policy 未禁 → managed-only 语义").isTrue();

        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();

        List<HookMatcher> matchers = snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).as("分支 4 必须返回 policy hooks").isNotEmpty();
    }

    // ── 5. D1-4: supplier 生产注入（非恒 false）+ D1-5 三闸门语义 ─────────────

    @Test
    @DisplayName("10. ManagedPolicySettingsSupplier 从 policy 文件读 disableAllHooks/allowManagedHooksOnly")
    void managedPolicySettingsSupplier_readsPolicyFile(@TempDir Path tmp) throws Exception {
        // WHY (EV-CFG-019): 旧无参构造 supplier=key->null 生产恒 false —— 企业策略不可观察。
        //       生产注入真实 policy 文件 supplier 后，shouldDisableAll/shouldAllowManagedHooksOnly
        //       必须非恒 false。
        Path policyFile = tmp.resolve("managed-settings.json");
        Files.writeString(policyFile, """
            {
              "disableAllHooks": true,
              "allowManagedHooksOnly": true,
              "hooks": {
                "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "echo policy-hook"}]}]
              }
            }
            """);

        ManagedPolicySettingsSupplier supplier = new ManagedPolicySettingsSupplier(new ObjectMapper(), policyFile.toString());

        assertThat(supplier.get("disableAllHooks")).isEqualTo(Boolean.TRUE);
        assertThat(supplier.get("allowManagedHooksOnly")).isEqualTo(Boolean.TRUE);
        assertThat(supplier.get("hooks")).isNotNull();

        HooksSettings settings = new HooksSettings(key -> null);
        settings.setManagedPolicySettingsSupplier(supplier);

        assertThat(settings.shouldDisableAll()).as("生产 supplier 注入后 must 非恒 false").isTrue();
        assertThat(settings.shouldAllowManagedHooksOnly()).isTrue();
    }

    @Test
    @DisplayName("11. 无 policy 文件 / 路径为空 → supplier 恒 null，门控不误伤（回归）")
    void managedPolicySettingsSupplier_noPolicyFile_returnsNull(@TempDir Path tmp) throws Exception {
        // WHY: 无企业管控（nexusai.policy.path 为空/文件不存在）是最常见场景，
        //       supplier 必须返回 null 保持"无 policy"语义，不得抛异常。
        ManagedPolicySettingsSupplier empty = new ManagedPolicySettingsSupplier(new ObjectMapper(), "");
        assertThat(empty.get("disableAllHooks")).isNull();

        ManagedPolicySettingsSupplier missing = new ManagedPolicySettingsSupplier(
            new ObjectMapper(), tmp.resolve("not-exists.json").toString());
        assertThat(missing.get("disableAllHooks")).isNull();
        assertThat(missing.all()).isEmpty();
    }

    // ── 6. 反例：无政策时 hook 正常执行（防过度门控）────────────────────────

    @Test
    @DisplayName("12. 无 policy → programmatic PreToolUse hook 正常执行（回归）")
    void noPolicy_programmaticPreToolUse_runs() {
        // WHY: 门控只应在政策触发时生效；无 policy（默认）时 hook 链必须原样执行。
        HooksSettings settings = new HooksSettings(key -> null);
        HookRegistry registry = newRegistry(settings);

        AtomicBoolean ran = new AtomicBoolean(false);
        registry.registerPreToolUse("policy-gate-ptu-ok", (toolName, input, ctx) -> {
            ran.set(true);
            return AggregatedHookResult.proceed();
        });

        registry.executePreToolUse("Bash", null, null);

        assertThat(ran).as("无 policy 时 PreToolUse hook 必须正常执行").isTrue();
    }

    // ── 7. env hook 链 (CwdChanged/FileChanged) 同走短路 ────────────────────

    @Test
    @DisplayName("13. disableAllHooks=true → CwdChanged env hook 短路，watchPaths 为空")
    void disableAllHooks_envHookShortCircuits() {
        // WHY: CC executeCwdChangedHooks → executeEnvHooks → executeHooksOutsideREPL
        //      (hooks.ts:4249)，入口短路 :3022-3027 先于 getMatchingHooks —— env hook 不豁免
        //      disableAllHooks，且闸门在<b>执行时点</b>读最新 policy（快照可能滞后）。
        //      旧实现 executeEnvHookCollectingWatchPaths 绕过政策闸门。
        //      RED 判别：无闸门时快照（捕获时无 policy）仍有 hook → 进程被启动 → lastSpec 非空。
        Map<String, Object> policy = new java.util.HashMap<>();
        HooksSettings settings = new HooksSettings(policy::get);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.CWD_CHANGED,
                commandHook("echo should-not-run"), ".envrc", HookSource.USER_SETTINGS, null)
        ));
        // 快照在 policy 生效前捕获（含 user hook）→ 执行时点仅靠快照空判定无法拦截
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(new HookMatcherEngine(snapshot, new PermissionRuleValueParser()));
        assertThat(snapshot.getHooksConfigFromSnapshot().get(HookEventType.CWD_CHANGED))
            .as("前置：快照须含 CWD_CHANGED hook（滞后快照场景）").isNotEmpty();

        // policy 在快照捕获后生效 —— 闸门必须在执行时点短路
        policy.put("disableAllHooks", Boolean.TRUE);

        CommandHookExecutorTest.FakeHookProcess proc = CommandHookExecutorTest.FakeHookProcess.normal(
            "{\"hookSpecificOutput\": {\"hookEventName\": \"CwdChanged\","
                + " \"watchPaths\": [\"/tmp/watch1\"]}}",
            "", 0);
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(proc);
        CommandHookExecutor executor = new CommandHookExecutor(launcher,
            k -> null, p -> true, () -> "C:/project", id -> "C:/plugins/" + id);
        registry.setCommandHookExecutor(executor);

        List<String> watchPaths =
            registry.executeCwdChangedHooksCollectingWatchPaths("/old/cwd", "/new/cwd", "s1").watchPaths();

        assertThat(watchPaths).as("disableAllHooks=true 时 env hook 必须短路，watchPaths 为空").isEmpty();
        assertThat(launcher.lastSpec)
            .as("disableAllHooks=true 时进程启动器不得被调用（短路先于任何执行）").isNull();
    }

    @Test
    @DisplayName("14. 无 policy → CwdChanged env hook 正常执行并产出 watchPaths（正控）")
    void noPolicy_envHook_executesAndCollectsWatchPaths() {
        // WHY: 正控 —— 证明 13 的断言有效：同一配置无 policy 时 hook 执行并产出 watchPaths，
        //       disableAllHooks 短路断言是"执行被跳过"而非"hook 本就无输出"。
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.CWD_CHANGED,
                commandHook("echo watch"), ".envrc", HookSource.USER_SETTINGS, null)
        ));
        HookRegistry registry = newRegistry(settings);

        CommandHookExecutorTest.FakeHookProcess proc = CommandHookExecutorTest.FakeHookProcess.normal(
            "{\"hookSpecificOutput\": {\"hookEventName\": \"CwdChanged\","
                + " \"watchPaths\": [\"/tmp/watch1\"]}}",
            "", 0);
        CommandHookExecutor executor = new CommandHookExecutor(
            new CommandHookExecutorTest.FakeLauncher(proc),
            k -> null, p -> true, () -> "C:/project", id -> "C:/plugins/" + id);
        registry.setCommandHookExecutor(executor);

        List<String> watchPaths =
            registry.executeCwdChangedHooksCollectingWatchPaths("/old/cwd", "/new/cwd", "s1").watchPaths();

        assertThat(watchPaths).as("无 policy 时 env hook 必须执行并收集 watchPaths")
            .containsExactly("/tmp/watch1");
    }
}
