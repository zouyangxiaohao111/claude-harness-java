package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.bash.ShellResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 流式输出接线 · 对齐 CC shellCommand.background(taskId) 文件模式
 * （LocalShellTask.tsx:220 + ShellCommand.ts:349-366 + TaskOutput.ts:24-27）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>R5 运行期文件增长</b>：TaskOutputTool 轮询（TaskOutput.ts:81-164 #tick）与
 *       StallWatchdog 增长检测（Files.size）都依赖 outputFile 在进程<b>运行中</b>持续增长。
 *       旧实现 stdout/stderr 只读入内存 StringBuilder、进程退出后才一次性写盘（TRUNCATE）→
 *       运行期轮询读空 + 产出型长命令 stall 检测失真。修复后每行实时 append 落盘。</li>
 *   <li><b>StallWatchdog 增长重置</b>：产出型长命令运行期文件持续增长 → offset 增长检测重置
 *       计时（CC LocalShellTask.tsx:58-60）→ 不误报 stall；文件冻结 + prompt 尾部才 stall。</li>
 * </ul>
 */
@DisplayName("[R5] 流式输出接线（运行期 outputFile 增长 → TaskOutputTool/StallWatchdog 实时可见）")
class LocalBashTaskRunnerStreamingTest {

    @TempDir
    Path tempDir;

    /** 是否有可用 bash/zsh（Windows 走 Git Bash）· ShellResolver 找不到 → false（跳过）。
     *  旧实现硬编码 /bin/sh（Windows JVM 无法解析）→ Windows 恒跳过；改后 ShellResolver 探测，
     *  Windows 有 Git Bash 即可真跑（对齐 CC Windows 必须 Git Bash）。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 输出文件是否已包含指定内容（IOException 视为 false，供轮询 lambda 使用） */
    private static boolean fileContains(Path out, String needle) {
        try {
            return Files.exists(out) && Files.readString(out).contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    /** 简单轮询等待（mvn 测试类路径无 awaitility 依赖，手写兜底） */
    private static void awaitUntil(BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. 落盘原语：appendOutputLine 逐行 APPEND（非 TRUNCATE）+ stderr 前缀
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("appendOutputLine 逐行增量写盘（不覆盖前行），stderr 带 [stderr] 前缀")
    void appendOutputLine_writesPerLineIncrementally_withStderrPrefix() throws Exception {
        // WHY: R5 流式路径的落盘原语 = 每行 APPEND（非 TRUNCATE）。若实现误用 TRUNCATE_EXISTING，
        //   后行覆盖前行 → 运行期轮询只能读到末行 → 流式失效。stderr 前缀对齐 CC 管道模式
        //   DiskTaskOutput.append（TaskOutput.ts:183 `[stderr] ${data}`）。
        Path out = tempDir.resolve("append.out");
        LocalBashTaskRunner runner = new LocalBashTaskRunner();

        runner.appendOutputLine(out.toString(), "first", false);
        runner.appendOutputLine(out.toString(), "second", false);
        runner.appendOutputLine(out.toString(), "boom", true);

        assertThat(Files.readString(out))
            .as("每行增量 APPEND + stderr 前缀")
            .isEqualTo("first\nsecond\n[stderr] boom\n");
    }

    // ════════════════════════════════════════════════════════════════
    // 2. 流式接线：execute() 运行期（进程未退出）outputFile 即增长
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("execute() 运行期 outputFile 已含产出行（进程未退出）—— R5 核心证明")
    void execute_streamsToFileWhileProcessRuns() throws Exception {
        // WHY（R5 核心）：TaskOutputTool 轮询 / StallWatchdog 增长检测依赖运行期文件增长。
        //   旧实现进程退出后才一次性写盘 → 本测试在进程未退出时读文件，只能读到空 → RED；
        //   修复后 echo 行立即落盘 → GREEN。进程 sleep 30 保证运行期窗口足够轮询。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        Path out = tempDir.resolve("stream.out");
        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        Thread t = new Thread(() -> {
            try {
                runner.execute("echo hello-stream; sleep 30", out.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "stream-test");
        t.setDaemon(true);
        t.start();
        try {
            awaitUntil(() -> fileContains(out, "hello-stream"),
                "运行期 outputFile 增长");
            assertThat(runner.isProcessAlive())
                .as("读到产出行时进程仍在运行（证明是流式落盘，非进程退出后一次性写盘）")
                .isTrue();
        } finally {
            runner.killProcess();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 全链路：spawn → TaskOutputTool 读路径（getOutput）运行期读到流式内容
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("spawn 后 TaskOutputTool 读路径（getOutput 非阻塞）在 RUNNING 期读到产出内容")
    void spawn_streamsOutput_visibleToTaskOutputToolWhileRunning() throws Exception {
        // WHY: R5 的最终消费方是 TaskOutputTool（TaskOutputTool.java:181 委托
        //   BackgroundTaskRunner.getOutput → readTaskOutput → Files.readString）。
        //   旧实现运行期文件不存在 → getOutput 返回占位提示"Output file not yet available"→ 轮询读空。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, service, sdk);

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        String command = "echo hello-poll; sleep 30";
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            command, "tu-" + taskId, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false, null, false);
        runner.spawn(task, command, null);
        try {
            awaitUntil(() -> {
                BackgroundTaskRunner.TaskOutput out = runner.getOutput(taskId, false, 0);
                return out.found() && out.content() != null && out.content().contains("hello-poll");
            }, "TaskOutputTool 轮询读到运行期流式输出");

            BackgroundTask current = runner.getTask(taskId).orElseThrow();
            assertThat(current.status())
                .as("读到产出内容时任务仍 RUNNING（证明运行期流式，非终态一次性写盘）")
                .isEqualTo(BackgroundTaskStatus.RUNNING);
        } finally {
            runner.cancel(taskId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 4. StallWatchdog 回归：文件增长 → 重置计时 → 产出型命令不误报
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("StallWatchdog：outputFile 持续增长 → offset 重置计时 → 不误报 stall")
    void producingOutput_resetsGrowthTimer_noStall() throws Exception {
        // WHY（R5 stall 侧）：产出型长命令运行期文件持续增长 → StallWatchdog.check 检测
        //   currentOffset > lastOffset（CC LocalShellTask.tsx:58-60）→ 重置 lastGrowthTime。
        //   旧实现（进程退出前不写文件）文件不增长 → 45s 阈值后误判 prompt-stall（R5 失真）。
        //   本测试用 800ms 短阈值 + 60ms 间隔持续 append，总时长 > 阈值仍不应 stall。
        Path out = tempDir.resolve("stall-growth.out");
        Files.writeString(out, "init\n");
        AtomicBoolean stalled = new AtomicBoolean(false);
        StallWatchdog watchdog = new StallWatchdog(
            "t-growth", out.toString(), 800, 100, () -> stalled.set(true));
        watchdog.start();
        try {
            for (int i = 0; i < 15; i++) {
                Files.writeString(out, "line " + i + "\n", StandardOpenOption.APPEND);
                Thread.sleep(60);
            }
            // 距最后一次 append 后 sleep 400ms（< 800ms 阈值）——期间文件无增长但仍在阈值内
            Thread.sleep(400);
            assertThat(watchdog.isStalled())
                .as("产出型输出持续增长 → 增长检测重置计时 → 不误报 stall")
                .isFalse();
            assertThat(stalled.get()).isFalse();
        } finally {
            watchdog.stop();
        }
    }

    @Test
    @DisplayName("StallWatchdog 对照：文件冻结 + prompt 尾部 → 触发 stall（确认检测仍生效）")
    void frozenOutput_withPromptTail_triggersStall() throws Exception {
        // WHY: 对照测试 —— 证明 StallWatchdog 检测本身仍生效（R5 不破坏 stall 能力）。
        //   文件冻结 + 尾部 "ready to proceed?"（命中 P_CONTINUE_PROCEED）+ 超过阈值 → stalled。
        //   这是"增长重置"测试的阴性对照：不增长 + prompt 尾部才触发。
        Path out = tempDir.resolve("stall-frozen.out");
        Files.writeString(out, "ready to proceed?\n");
        AtomicBoolean stalled = new AtomicBoolean(false);
        StallWatchdog watchdog = new StallWatchdog(
            "t-frozen", out.toString(), 500, 100, () -> stalled.set(true));
        watchdog.start();
        try {
            awaitUntil(() -> watchdog.isStalled() || stalled.get(), "stall 触发");
            assertThat(watchdog.isStalled()).as("文件冻结 + prompt 尾部 → 触发 stall").isTrue();
        } finally {
            watchdog.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 5. T1: 5GB size-watchdog（对齐 CC ShellCommand.ts:239-261）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("size-watchdog 判定：文件超阈值 → true；不存在/≤阈值 → false（不误杀）")
    void isOutputExceeded_decidesThreshold() throws Exception {
        // WHY（规则九 · 判定方法独立可测）: watchdog 的核心判定 = Files.size(outputFile) > maxBytes。
        //   文件不存在（首次写入前 ENOENT）/ IO 异常 → false（跳过 tick，不误杀进程，对齐 CC
        //   ShellCommand.ts:255-257 catch）。超限 → true（触发 SIGKILL）。判定独立于 5s 轮询间隔，
        //   单测直调无需等真实 watchdog tick。
        Path out = tempDir.resolve("decide.out");
        assertThat(LocalBashTaskRunner.isOutputExceeded(out, 1024))
            .as("文件不存在（ENOENT）→ 不误杀").isFalse();
        Files.writeString(out, "0123456789");
        assertThat(LocalBashTaskRunner.isOutputExceeded(out, 1024))
            .as("文件大小 ≤ 阈值 → 不触发").isFalse();
        Files.writeString(out, "x".repeat(4096), StandardOpenOption.APPEND);
        assertThat(LocalBashTaskRunner.isOutputExceeded(out, 1024))
            .as("文件大小 > 阈值 → 触发杀进程判定").isTrue();
    }

    @Test
    @DisplayName("size-watchdog：输出超限进程被杀（exitCode=137）+ stderr 前缀 kill 消息（对齐 CC prependStderr）")
    void execute_killsProcessWhenOutputExceeds_watchdogMessageInStderr() throws Exception {
        // WHY（规则九 · T1 核心）: background 长任务 stuck append loop 可把磁盘打满（CC 768GB incident,
        //   ShellCommand.ts:357 注释）。size-watchdog 每 5s stat 输出文件，超阈值且进程存活 → SIGKILL。
        //   注入小阈值（1KB）合成超限场景（真实 5GB 不跑）: 进程写 64KB 到输出文件后 sleep 保持存活
        //   → watchdog 触发 → execute 返回 exitCode=137（128+SIGKILL）+ stderr 前缀 kill 消息
        //   （对齐 CC #handleExit prependStderr, ShellCommand.ts:318-322）。RED: 超限不杀 / 消息不前缀
        //   → 变红（磁盘打满防护失效，通知侧 sizeWatchdogKillNote 也无从识别）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        Path out = tempDir.resolve("watchdog-kill.out");
        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        AtomicReference<LocalBashTaskRunner.BashResult> captured = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                captured.set(runner.execute(
                    "yes '0123456789abcdefghijklmnopqrstuvwxyz' | head -c 65536; sleep 30",
                    out.toString(), 1024));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "watchdog-test");
        t.setDaemon(true);
        t.start();
        try {
            awaitUntil(() -> captured.get() != null, "size-watchdog 杀进程后 execute 返回");
            LocalBashTaskRunner.BashResult r = captured.get();
            assertThat(r.exitCode())
                .as("watchdog 杀进程 → exitCode=137（128+SIGKILL）")
                .isEqualTo(LocalBashTaskRunner.KILLED_FOR_SIZE_EXIT_CODE);
            assertThat(r.stderr())
                .as("stderr 前缀 size-watchdog kill 消息（对齐 CC prependStderr）")
                .startsWith(LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE);
            assertThat(runner.isProcessAlive())
                .as("被 watchdog 杀的进程必须已终止")
                .isFalse();
        } finally {
            runner.killProcess();
            t.interrupt();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 6. G2-1b: 后台任务接 Shell 快照（source 用户环境 / 无快照回退 -l）
    // ════════════════════════════════════════════════════════════════

    /**
     * 快照注入测试 runner · 覆盖 {@link LocalBashTaskRunner#resolveBackgroundSnapshot()} 返回
     * 指定快照路径（或 null 模拟快照缺失），不污染 {@code ShellExecutor} 全局快照缓存。
     */
    static final class SnapshotInjectRunner extends LocalBashTaskRunner {
        private final Path snapshotPath;
        SnapshotInjectRunner(Path snapshotPath) { this.snapshotPath = snapshotPath; }
        @Override
        Path resolveBackgroundSnapshot() { return snapshotPath; }
    }

    @Test
    @DisplayName("后台任务 source 快照 → 命令可见快照注入的用户环境（导出变量 + PATH 前缀）")
    void execute_backgroundTask_sourcesSnapshot_carriesUserEnv() throws Exception {
        // WHY（对齐 CC 后台语义 · 规则九）: 后台任务必须像前台一样获得用户交互 shell 环境
        //   （函数/别名/PATH）。旧实现走两参 bash（无 -l、无快照 source）→ 后台命令缺用户
        //   PATH/别名（如用户 PATH 里自装的 CLI）→ 与 CC 漂移。本测试手工构造快照导出变量 +
        //   PATH 前缀，证明 source 前缀生效（bashProvider.ts:161-167）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        String marker = "bg-env-" + System.nanoTime();
        Path snap = tempDir.resolve("snapshot-test.sh");
        Files.writeString(snap,
            "export NEXUSAI_BG_TEST_MARKER=" + marker + "\n"
                + "export PATH=\"/tmp/nexusai-bg-path:$PATH\"\n");

        LocalBashTaskRunner runner = new SnapshotInjectRunner(snap);
        LocalBashTaskRunner.BashResult r = runner.execute(
            "printf '%s|%s' \"$NEXUSAI_BG_TEST_MARKER\" \"$PATH\"", null);

        assertThat(r.exitCode()).as("source 快照后命令正常退出").isZero();
        assertThat(r.stdout()).as("快照导出的变量对后台命令可见").contains(marker);
        assertThat(r.stdout()).as("快照导出的 PATH 前缀对后台命令可见").contains("/tmp/nexusai-bg-path");
    }

    @Test
    @DisplayName("后台任务无快照 → 回退 -l login shell 仍正常执行（不阻塞、不破坏）")
    void execute_noSnapshot_fallsBackToLoginShell_runsNormally() throws Exception {
        // WHY（优雅回退 · 规则九）: 快照生成失败/超时/被清理时必须回退 -l login shell
        //   （bashProvider.ts:93-103 + getSpawnArgs :200-206），后台启动不被阻塞，命令仍正常执行。
        //   RED: 回退路径抛异常或命令不执行 → 快照失效即后台任务全挂。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        LocalBashTaskRunner runner = new SnapshotInjectRunner(null); // 模拟快照缺失
        LocalBashTaskRunner.BashResult r = runner.execute("echo fallback-ok", null);

        assertThat(r.exitCode()).as("无快照回退 -l 后命令正常退出").isZero();
        assertThat(r.stdout()).as("命令输出正常（回退不破坏既有行为）").contains("fallback-ok");
    }
}
