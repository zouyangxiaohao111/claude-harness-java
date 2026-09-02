package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.hook.CommandHookExecutor.CommandHookResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [EX-HOOK R5 + EX_E §8.3] command hook 父 abort 全链路：
 * <ol>
 *   <li>调用点层（EX-HOOK R5）· 对齐 CC executeHooks 入口 {@code if (signal?.aborted) return}
 *       （hooks.ts:2015-2017）：父 per-turn TUC 已取消 → 跳过执行（不 spawn 子进程）。</li>
 *   <li>执行器层（EX_E）· 对齐 CC execCommandHook 的 abortSignal（hooks.ts:755）+ wrapSpawn
 *       addEventListener('abort')（ShellCommand.ts:264-265）：进程运行期间父 abort →
 *       destroyForcibly + aborted=true + 'Hook cancelled'（hooks.ts:1257, :1300-1307）。</li>
 * </ol>
 *
 * <p>WHY (规则九 · 验证意图): 父 per-turn TUC 已取消时，command hook 不应再 spawn
 * 子进程（用户中止意图不可吞）；已运行中的 hook 进程必须被终止，不能继续占用资源。
 *
 * <p>不依赖 Spring 容器：调用点层手动构造 HooksSettings / HooksConfigSnapshot /
 * HookMatcherEngine + StubCommandExecutor（无真实进程）；执行器层注入 fake
 * ProcessLauncher + BlockingHookProcess（无真实进程）。
 */
@DisplayName("[EX-HOOK R5 + EX_E §8.3] command hook 父 abort 透传（入口早返 + 进程内 kill）")
class CommandHookAbortPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 覆写 execute 的 stub：计数调用，不启动真实进程。 */
    static class CountingCommandExecutor extends CommandHookExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput, String pluginRoot, String pluginId, String skillRoot, Integer hookIndex, boolean forceSyncExecution, AbortController parentAbort) {
            calls.incrementAndGet();
            return new CommandHookResult("{\"decision\":\"approve\"}", "", "", 0, false, false);
        }

            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                             String pluginRoot, String pluginId, String skillRoot,
                                             Integer hookIndex, boolean forceSyncExecution,
                                             AbortController parentAbort, long defaultTimeoutMs, String hookCwd) {
                // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
                return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                    hookIndex, forceSyncExecution, parentAbort);
            }
    }

    /** [IMP-A2-1 · MG-5] 记录收到 parentAbort 的 stub · 验证批级 abort 透传为执行器 parentAbort. */
    static class CapturingParentAbortExecutor extends CountingCommandExecutor {
        final AtomicReference<AbortController> capturedParentAbort = new AtomicReference<>();

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                         String pluginRoot, String pluginId, String skillRoot,
                                         Integer hookIndex, boolean forceSyncExecution,
                                         AbortController parentAbort) {
            capturedParentAbort.set(parentAbort);
            return super.execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    /** settings 配 1 条 PreToolUse:Bash command hook → registry（含 stub executor）. */
    private HookRegistry registryWithConfiguredHook(CountingCommandExecutor stub) {
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

    private static ToolUseContext parentTucWith(AbortController abort) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 主线程惯例: agentId == sessionId（与 R7 判定无关，此处仅作父上下文载体）
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", abort, List.of(), null, PermissionMode.DEFAULT);
    }

    /**
     * WHY: CC executeHooks 入口 {@code if (signal?.aborted) return}（hooks.ts:2015-2017）—
     * 父 abort 已取消 → 不执行 hook（不 spawn 子进程）。parentTuc 经 executeEvent 3 参
     * 重载透传（LlmAgentLoop Stop 段同链路）。
     */
    @Test
    @DisplayName("父 abort 已取消 → command hook 不执行（executor.execute 0 次）")
    void commandHook_parentAbortCancelled_skipsExecution() {
        CountingCommandExecutor stub = new CountingCommandExecutor();
        HookRegistry registry = registryWithConfiguredHook(stub);
        AbortController cancelled = new AbortController();
        cancelled.abort();
        ToolUseContext parentTuc = parentTucWith(cancelled);

        registry.executeEvent(
            HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", null, "tu-1"),
            null, parentTuc);

        assertThat(stub.calls.get())
            .as("父 abort 已取消 → command hook 跳过执行（CC executeHooks 入口 signal.aborted 早返 hooks.ts:2015-2017）")
            .isZero();
    }

    /**
     * WHY: 反向 — 父 abort 未取消（NOOP）→ 正常执行（保证早返检查不误杀正常路径）。
     */
    @Test
    @DisplayName("父 abort 未取消 → command hook 正常执行（1 次）")
    void commandHook_parentAbortNotCancelled_executes() {
        CountingCommandExecutor stub = new CountingCommandExecutor();
        HookRegistry registry = registryWithConfiguredHook(stub);
        ToolUseContext parentTuc = parentTucWith(AbortController.NOOP);

        registry.executeEvent(
            HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", null, "tu-1"),
            null, parentTuc);

        assertThat(stub.calls.get())
            .as("父 abort 未取消 → command hook 正常执行")
            .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // EX_E §8.3 · 执行器侧进程内 abort kill（对齐 CC wrapSpawn abort → treeKill SIGKILL）
    // ════════════════════════════════════════════════════════════════════════

    /** 测试用 command hook · timeout=5s 作安全网（abort 未达时 5s 内走超时路径失败而非挂 10min）. */
    private static final CommandHook TEST_HOOK =
        new CommandHook("echo hello", null, null, 5, null, null, null, null);

    private static final HookEvent TEST_EVENT =
        HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", null, "tu-1");

    /**
     * 阻塞型 fake 进程 · waitFor 循环直到 destroyForcibly（等价真实子进程被 SIGKILL 后退出）。
     * 首次进入 waitFor 时 countDown 信号量，测试借此确定"进程已运行"再触发 abort（确定性，非 sleep 竞态）。
     */
    static class BlockingHookProcess implements CommandHookExecutor.HookProcess {
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        private final CountDownLatch waitForEntered;

        BlockingHookProcess(CountDownLatch waitForEntered) {
            this.waitForEntered = waitForEntered;
        }

        @Override
        public OutputStream stdin() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitForEntered.countDown();
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!destroyed.get() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return destroyed.get();
                }
            }
            return destroyed.get();
        }

        @Override
        public void destroyForcibly() {
            destroyed.set(true);
        }

        @Override
        public int exitValue() {
            return 1;
        }
    }

    /** 立即完成型 fake 进程 · exit 0 + 固定 stdout，验证无 abort 时正常结果路径不变. */
    static class CompletingHookProcess implements CommandHookExecutor.HookProcess {
        private final String stdoutContent;

        CompletingHookProcess(String stdoutContent) {
            this.stdoutContent = stdoutContent;
        }

        @Override
        public OutputStream stdin() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(stdoutContent.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void destroyForcibly() {
            // no-op · 正常完成路径不应被终止
        }

        @Override
        public int exitValue() {
            return 0;
        }
    }

    /** 构造注入 fake launcher 的 executor（其余依赖走默认实现）· 返回 (executor, 已启动进程). */
    private static CommandHookExecutor executorWith(CommandHookExecutor.ProcessLauncher launcher) {
        return new CommandHookExecutor(launcher, null, null, null, null);
    }

    /**
     * WHY: CC execCommandHook 把 abortSignal 传给 wrapSpawn，运行期间 abort →
     * {@code #abortHandler} → {@code kill()} → {@code treeKill(pid, SIGKILL)}
     * （ShellCommand.ts:186-193, :345-347），结果 {@code aborted: signal.aborted}
     * （hooks.ts:1257）。父 abort 时子进程必须被终止（不能继续占用资源），且结果标记
     * aborted + 'Hook cancelled'（hooks.ts:1300-1307 ABORT_ERR 文案）。
     */
    @Test
    @DisplayName("执行器：运行中父 abort → 子进程被终止 + aborted='Hook cancelled' 结果")
    void commandHook_executor_parentAbortDuringRun_killsChildAndReturnsAborted() throws Exception {
        AbortController abort = new AbortController();
        CountDownLatch waitForEntered = new CountDownLatch(1);
        BlockingHookProcess proc = new BlockingHookProcess(waitForEntered);
        CommandHookExecutor executor = executorWith(spec -> proc);

        AtomicReference<CommandHookResult> result = new AtomicReference<>();
        Thread runner = new Thread(() -> result.set(executor.execute(
            TEST_HOOK, TEST_EVENT, "abort-during-run", "{}",
            null, null, null, null, false, abort)),
            "abort-during-run");
        runner.start();

        // 等 fake 进程真正进入 waitFor（即 hook 已 spawn 运行）再触发 abort
        assertThat(waitForEntered.await(5, TimeUnit.SECONDS))
            .as("子进程应已进入 waitFor（hook 运行中）")
            .isTrue();
        abort.abort("user_cancelled");
        runner.join(5000);

        assertThat(runner.isAlive())
            .as("execute 应在父 abort 后返回（waitFor 因子进程被终止而结束）")
            .isFalse();
        assertThat(proc.destroyed.get())
            .as("父 abort → 子进程被 destroyForcibly 终止（对齐 CC treeKill SIGKILL）")
            .isTrue();
        CommandHookResult r = result.get();
        assertThat(r.aborted())
            .as("结果标记 aborted=true（CC hooks.ts:1257 aborted: signal.aborted）")
            .isTrue();
        assertThat(r.stderr())
            .as("结果标记 'Hook cancelled'（CC ABORT_ERR hooks.ts:1300-1307）")
            .isEqualTo("Hook cancelled");
        assertThat(r.status()).isEqualTo(1);
    }

    /**
     * WHY: 边界 — 父 abort 已取消后才调 execute。CC createCombinedAbortSignal 对已取消
     * signal 立即 abort 合并信号（combinedAbortSignal.ts:23-26），wrapSpawn 在已取消信号上
     * addEventListener 立即触发 → spawn 后立刻 kill。Java onCancel 对已取消 controller
     * 同步立即触发（AbortController.java onCancel 已取消 → 立即 accept）→ 同样 kill 路径。
     */
    @Test
    @DisplayName("执行器：父 abort 已取消（调用前）→ 立即终止 + aborted 结果")
    void commandHook_executor_parentAlreadyAborted_returnsAborted() {
        AbortController abort = new AbortController();
        abort.abort("user_cancelled");
        BlockingHookProcess proc = new BlockingHookProcess(new CountDownLatch(1));
        CommandHookExecutor executor = executorWith(spec -> proc);

        CommandHookResult r = executor.execute(
            TEST_HOOK, TEST_EVENT, "already-aborted", "{}",
            null, null, null, null, false, abort);

        assertThat(proc.destroyed.get())
            .as("已取消的父 abort → onCancel 立即触发 → 子进程被终止")
            .isTrue();
        assertThat(r.aborted()).isTrue();
        assertThat(r.stderr()).isEqualTo("Hook cancelled");
    }

    /**
     * WHY: 反向 — parentAbort 为 null / NOOP → 不监听，结果路径与旧 10 参签名完全一致
     * （正常完成：exit 0 + stdout 原样返回，aborted=false）。保证 abort 接线不破坏既有行为。
     */
    @Test
    @DisplayName("执行器：parentAbort=null/NOOP → 正常执行（aborted=false，结果原样）")
    void commandHook_executor_nullAbort_preservesExistingBehavior() {
        CompletingHookProcess proc = new CompletingHookProcess("hello from hook\n");
        CommandHookExecutor executor = executorWith(spec -> proc);

        CommandHookResult rNull = executor.execute(
            TEST_HOOK, TEST_EVENT, "null-abort", "{}",
            null, null, null, null, false, null);
        CommandHookResult rNoop = executor.execute(
            TEST_HOOK, TEST_EVENT, "noop-abort", "{}",
            null, null, null, null, false, AbortController.NOOP);

        for (CommandHookResult r : List.of(rNull, rNoop)) {
            assertThat(r.aborted())
                .as("无父 abort → 结果 aborted=false（既有行为不变）")
                .isFalse();
            assertThat(r.status())
                .as("无父 abort → exit 0 原样返回")
                .isZero();
            assertThat(r.stdout())
                .as("无父 abort → stdout 原样返回")
                .isEqualTo("hello from hook\n");
            assertThat(r.backgrounded()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-A2-1 · MG-5] 批级 abort 透传（compact/SessionEnd 路径 · 对齐 CC compact.ts:418/:728
    //   context.abortController.signal → execCommandHook abortSignal hooks.ts:2453）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 验证意图): A2-1 缺口是"批级 abort 仅入口早退，运行中子进程不被 SIGKILL"。
     * CC 把 compact 上下文 signal 透传给 execCommandHook（hooks.ts:2453 → wrapSpawn
     * addEventListener('abort') → treeKill SIGKILL，ShellCommand.ts:264-265）——批级 abort
     * 必须到达执行器 parentAbort，运行中 hook 子进程才能在批中止时被终止（不能只入口早返）。
     * 断言 stub 收到的 parentAbort 就是传入的 batchAbort。
     */
    @Test
    @DisplayName("executeEventAll(事件, batchAbort)：批级 abort 透传为 CommandHookExecutor.parentAbort")
    void executeEventAll_batchAbort_passedAsParentAbort() {
        CapturingParentAbortExecutor stub = new CapturingParentAbortExecutor();
        HookRegistry registry = registryWithConfiguredHook(stub);
        AbortController batchAbort = new AbortController();

        registry.executeEventAll(
            HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", null, "tu-1"),
            batchAbort);

        assertThat(stub.calls.get())
            .as("批级 abort 未取消 → command hook 正常执行")
            .isEqualTo(1);
        assertThat(stub.capturedParentAbort.get())
            .as("批级 abort 透传为执行器 parentAbort（对齐 CC execCommandHook abortSignal → wrapSpawn 杀子进程）")
            .isSameAs(batchAbort);
    }

    /**
     * WHY: 反向 — 批级 abort 已取消 → 入口早退整批跳过（对齐 CC executeHooksOutsideREPL
     * {@code if (signal?.aborted) return []} hooks.ts:3051-3053），不 spawn 子进程。保证
     * 批级 abort 透传不破坏既有入口早返语义。
     */
    @Test
    @DisplayName("executeEventAll(事件, 已取消 batchAbort)：入口早退，command hook 不执行")
    void executeEventAll_cancelledBatchAbort_skipsExecution() {
        CapturingParentAbortExecutor stub = new CapturingParentAbortExecutor();
        HookRegistry registry = registryWithConfiguredHook(stub);
        AbortController cancelled = new AbortController();
        cancelled.abort();

        registry.executeEventAll(
            HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-1", null, "tu-1"),
            cancelled);

        assertThat(stub.calls.get())
            .as("批级 abort 已取消 → command hook 跳过执行（对齐 CC signal?.aborted → return [] hooks.ts:3051-3053）")
            .isZero();
    }
}
