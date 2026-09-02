package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.bash.ShellResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * G1-2 前台 bash 任务就地转后台 · 对齐 CC LocalShellTask.tsx:259-287 registerForeground /
 * :420-474 backgroundExistingForegroundTask / :491-514 unregisterForeground。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：CC auto-background 定时器触发时，已 registerForeground 的任务
 * 就地转后台——<b>不重新 spawn / 不重新 registerTask</b>（防重复 task_started SDK 事件 + cleanup
 * 回调泄漏，:414-418 注释）。Java 旧实现无 registerForeground 路径 → BashTool 超时只能重新 spawn
 * 或丢进程。本测试锁定：
 * <ul>
 *   <li>registerForeground 登记前台任务（isBackgrounded=false），store 无重复（registerTask 仅一次）</li>
 *   <li>backgroundExistingForegroundTask 翻转 isBackgrounded=true，<b>不重复</b>向 store 注册
 *       （taskId 唯一）</li>
 *   <li>cancel 复用同一 runner（runnerMap）杀子进程，任务标 KILLED</li>
 *   <li>unregisterForeground 仅注销前台任务，已后台化任务不注销</li>
 * </ul>
 */
@DisplayName("[G1-2] 前台 bash 任务 registerForeground / backgroundExistingForegroundTask / unregisterForeground")
class BackgroundTaskRunnerForegroundBashTest {

    @TempDir
    Path tempDir;

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), framework);

    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

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

    @Test
    @DisplayName("registerForeground → backgroundExistingForegroundTask：翻转 isBackgrounded、store 无重复 taskId、cancel 复用同一 runner")
    void backgroundExistingForegroundTask_flipsInPlace_noDuplicateStore_cancelReusesRunner() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        LocalBashTaskRunner bashRunner = new LocalBashTaskRunner();
        // 前台命令在独立线程运行（BashTool G5 侧等价），runner 复用同一实例
        Thread t = new Thread(() -> {
            try {
                bashRunner.execute("sleep 999", null);
            } catch (Exception ignored) {
                // 预期被 cancel 树杀
            }
        }, "fg-bash");
        t.setDaemon(true);
        t.start();
        awaitUntil(bashRunner::isProcessAlive, "前台 bash 进程启动");

        String outputFile = tempDir.resolve(taskId + ".out").toString();
        BackgroundTask fg = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "sleep 999", "tu-" + taskId, System.currentTimeMillis(), null, null,
            outputFile, 0L, false, null, false);
        runner.registerForeground(fg, bashRunner);

        // 登记后：前台 + store 唯一（registerTask 仅一次 → taskId 无重复）
        assertThat(runner.getTask(taskId).orElseThrow().isBackgrounded()).isFalse();
        assertThat(framework.listAll().stream().filter(x -> x.id().equals(taskId)).count())
            .as("registerForeground 仅 registerTask 一次 → store 无重复 taskId")
            .isEqualTo(1L);

        // 就地转后台：不重新 spawn/registerTask（store taskId 仍唯一），isBackgrounded 翻转
        assertThat(runner.backgroundExistingForegroundTask(taskId)).isTrue();
        assertThat(framework.getTask(taskId).orElseThrow().isBackgrounded())
            .as("backgroundExistingForegroundTask 翻转 isBackgrounded=true（对齐 CC :421-441）")
            .isTrue();
        assertThat(framework.listAll().stream().filter(x -> x.id().equals(taskId)).count())
            .as("就地转后台不重复 registerTask（防重复 task_started）")
            .isEqualTo(1L);

        // cancel 复用同一 runner（runnerMap）杀子进程 + 标 KILLED
        assertThat(runner.cancel(taskId)).isTrue();
        awaitUntil(() -> !bashRunner.isProcessAlive(), "cancel 复用 runner 杀子进程");
        assertThat(runner.getTask(taskId).orElseThrow().status())
            .as("cancel 后任务标 KILLED")
            .isEqualTo(BackgroundTaskStatus.KILLED);
        t.interrupt();
    }

    @Test
    @DisplayName("backgroundExistingForegroundTask 幂等/守卫：非前台、无进程、未登记 → false")
    void backgroundExistingForegroundTask_guards() {
        // 未登记 → false
        assertThat(runner.backgroundExistingForegroundTask("no-such-task")).isFalse();

        // 已后台化任务（直接构造 isBackgrounded=true 登记）→ false（对齐 CC shellCommand.background 幂等）
        String bgId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask bg = new BackgroundTask(
            bgId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "cmd", "tu", System.currentTimeMillis(), null, null,
            tempDir.resolve(bgId + ".out").toString(), 0L, false, null, true);
        runner.registerForeground(bg, new LocalBashTaskRunner());
        assertThat(runner.backgroundExistingForegroundTask(bgId)).isFalse();

        // 前台但无进程（isProcessAlive=false → 对齐 CC :421 status==='running' 守卫）→ false
        String noProcId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask noProc = new BackgroundTask(
            noProcId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "cmd", "tu", System.currentTimeMillis(), null, null,
            tempDir.resolve(noProcId + ".out").toString(), 0L, false, null, false);
        runner.registerForeground(noProc, new LocalBashTaskRunner());
        assertThat(runner.backgroundExistingForegroundTask(noProcId)).isFalse();
    }

    @Test
    @DisplayName("unregisterForeground 仅注销前台任务；已后台化任务不注销")
    void unregisterForeground_onlyRemovesForeground() {
        // 前台任务（isBackgrounded=false）
        String fgId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask fg = new BackgroundTask(
            fgId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "cmd", "tu", System.currentTimeMillis(), null, null,
            tempDir.resolve(fgId + ".out").toString(), 0L, false, null, false);
        runner.registerForeground(fg, new LocalBashTaskRunner());

        // 已后台化任务（isBackgrounded=true）
        String bgId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask bg = new BackgroundTask(
            bgId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "cmd", "tu", System.currentTimeMillis(), null, null,
            tempDir.resolve(bgId + ".out").toString(), 0L, false, null, true);
        runner.registerForeground(bg, new LocalBashTaskRunner());

        assertThat(runner.unregisterForeground(fgId)).isTrue();
        assertThat(runner.unregisterForeground(bgId)).isFalse(); // 已后台化不注销
        assertThat(framework.getTask(fgId)).isEmpty();
        assertThat(framework.getTask(bgId)).isPresent();
    }
}
