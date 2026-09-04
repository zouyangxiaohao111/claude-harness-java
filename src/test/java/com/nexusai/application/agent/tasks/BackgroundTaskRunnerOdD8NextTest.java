package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.bash.ShellResolver;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [OD-D8] 普通 bash 终态通知 priority NEXT（开态统一）· 对齐 CC LocalShellTask.tsx:166-171
 * {@code priority: feature('MONITOR_TOOL') ? 'next' : 'later'}（本快照构建包宏折叠 false，但用户拍板开态
 * —— Java 产品 Monitor 已 NEXT，统一普通 bash 完成/失败/killed 均 NEXT）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: OD-D8 行为为何重要 —— bash 后台任务完成通知原 priority null →
 * enqueuePendingNotification 默认 LATER → 忙碌回合只能在 turn 结束 idle 兜底被消费；改 NEXT 后
 * 完成通知在<b>工具边界</b>（drainForQuery next 阈值）mid-turn 注入（对齐 OD-D3 isMeta 形态），
 * 模型能在当前回合内看到后台命令结果，不被延迟到回合末。若实现漏改某终态路径（完成/失败/killed），
 * 该路径通知回落 LATER，回合中工具边界不可见 → 本测试 RED 保护。
 *
 * <p><b>范围红线</b>: spawnStub（非 LOCAL_BASH 占位）与 async agent 通知保持默认 LATER（不在本次范围，
 * plan §3.3/§四）；monitor/stall 通知已 NEXT（保留不动）。本类只锁定真正 bash 三终态。
 */
@DisplayName("[OD-D8] BackgroundTaskRunner 普通 bash 终态通知 priority=NEXT")
class BackgroundTaskRunnerOdD8NextTest {

    @TempDir
    Path tempDir;

    private final TaskFrameworkService framework = new TaskFrameworkService(null);

    @AfterEach
    void tearDown() {
        // 与既有 runner 测试同款清理：MDC + sysprop 防跨测试线程泄漏
        com.nexusai.common.RequestContext.clear();
        System.clearProperty("nexusai.sessionId");
        com.nexusai.application.agent.agent.SessionCwdHolder.reset();
    }

    /** 是否有可用 bash/zsh（Windows 走 Git Bash）· ShellResolver 找不到 → 跳过（对齐 R5 测试）。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 简单轮询等待队列收到任意 task-notification（对齐 R5 awaitUntil 模式）。 */
    private static void awaitQueueNotEmpty(NotificationQueue queue, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (queue.peek(q -> true).isPresent()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc + "（队列始终为空）");
    }

    private static void awaitUntil(BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
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

    private BackgroundTaskRunner newRunner(NotificationQueue nq) {
        return new BackgroundTaskRunner(nq, framework, null);
    }

    private BackgroundTask newBashTask(String command) {
        String taskId = "od8-" + UUID.randomUUID();
        return new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            command, "tu-" + taskId, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false);
    }

    @Test
    @DisplayName("bash 完成通知 priority=NEXT（非 LATER）—— busy 回合工具边界可 mid-turn 注入")
    void bashCompletedNotification_isNextPriority() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);
        String command = "echo od-d8-complete; exit 0";

        runner.spawn(newBashTask(command), command, null);
        awaitQueueNotEmpty(nq, "bash 完成通知入队");

        QueueItem item = nq.peek(q -> true).orElseThrow();
        assertThat(item.mode()).as("完成通知 mode=task-notification").isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
        assertThat(item.priority())
            .as("bash 完成通知必须 NEXT（OD-D8 开态，CC LocalShellTask.tsx:166-171）；LATER 只能回合末 idle 兜底")
            .isEqualTo(Priority.NEXT);
    }

    @Test
    @DisplayName("bash 失败通知 priority=NEXT")
    void bashFailedNotification_isNextPriority() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);
        String command = "echo od-d8-fail; exit 3";

        runner.spawn(newBashTask(command), command, null);
        awaitQueueNotEmpty(nq, "bash 失败通知入队");

        QueueItem item = nq.peek(q -> true).orElseThrow();
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
        assertThat(item.priority())
            .as("bash 失败通知必须 NEXT（CC LocalShellTask.tsx:166-171 completed/failed/killed 同 priority）")
            .isEqualTo(Priority.NEXT);
    }

    @Test
    @DisplayName("bash killed 通知 priority=NEXT（markKilled 路径）")
    void bashKilledNotification_isNextPriority() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);
        String command = "sleep 30";
        BackgroundTask task = newBashTask(command);
        String taskId = task.id();

        runner.spawn(task, command, null);
        // 等任务进入 RUNNING（进程存活）再 cancel → markKilled 路径
        awaitUntil(() -> {
            java.util.Optional<BackgroundTask> t = runner.getTask(taskId);
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.RUNNING;
        }, "任务 RUNNING");
        runner.cancel(taskId);
        awaitQueueNotEmpty(nq, "bash killed 通知入队");

        QueueItem item = nq.peek(q -> true).orElseThrow();
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
        assertThat(item.priority())
            .as("bash killed 通知必须 NEXT（markKilled 是 cancel/killShellTasksForAgent 复用路径）")
            .isEqualTo(Priority.NEXT);
    }
}
