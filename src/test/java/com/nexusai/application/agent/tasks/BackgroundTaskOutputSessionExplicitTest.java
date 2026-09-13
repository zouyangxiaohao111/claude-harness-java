package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [批 3b-D7 · 用户裁定「显式传参」] 后台任务的输出根必须归属<b>发起方显式传入的会话</b>，
 * 且真实执行线程 {@code bg-task-worker} 上 {@code RequestContext.sessionId() == null}。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：旧实现 {@code BackgroundTaskRunner.resolveSessionId()}
 * 读 MDC（→ sysprop → {@code "unknown"}）。本类的调用点全在派生线程（tool-exec 池 / 本类的
 * {@code bg-task-worker} / 轮询定时器）——CC {@code diskOutput.ts:50-55} 用的是
 * {@code STATE.sessionId}（进程单例，:425-427 state.ts），Java 多会话<b>没有对应物</b>，
 * 只能由发起方（AI 工具调用手上就有 {@code ToolUseContext.sessionId()}）显式透传。
 *
 * <p><b>鉴别力来源</b>：调用线程上故意写「别的会话」的残留 MDC（第三态），且让该会话持有自己的
 * L2 cwd —— 旧实现（读 MDC）会把输出写进<b>残留会话</b>的目录；修复后只认显式入参。
 * 断言落点 = 真实文件（{@code bg-task-worker} 真跑 bash 写出），不是纯路径字符串比较。
 */
@DisplayName("批 3b-D7 · 后台任务输出根 = 显式会话（真实 bg-task-worker 线程 + 残留 MDC 反向对照）")
class BackgroundTaskOutputSessionExplicitTest {

    private static final String EXPLICIT = "sess-d7-explicit";
    private static final String STALE = "sess-d7-stale-third-state";

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final RecordingNotificationQueue queue = new RecordingNotificationQueue();
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, framework);

    @AfterEach
    void tearDown() {
        runner.shutdown();
        RequestContext.clear();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    /** 记录 enqueue 发生所在线程（真实 bg-task-worker）与其上 ambient MDC（不改语义，纯观察）。 */
    static class RecordingNotificationQueue extends NotificationQueue {
        final AtomicReference<String> enqueueThread = new AtomicReference<>();
        final AtomicReference<String> enqueueThreadMdc = new AtomicReference<>("<未入队>");
        final AtomicReference<String> itemSessionId = new AtomicReference<>("<未入队>");

        @Override
        public synchronized void enqueuePendingNotification(QueueItem item) {
            enqueueThread.set(Thread.currentThread().getName());
            enqueueThreadMdc.set(RequestContext.sessionId());
            itemSessionId.set(item.sessionId());
            super.enqueuePendingNotification(item);
        }
    }

    @Test
    @DisplayName("① spawn 的输出落显式会话目录（不是残留 MDC 会话目录），且 bg-task-worker 上 MDC 为 null")
    void spawnedTask_outputBelongsToExplicitSession(@TempDir Path tmp) throws Exception {
        // 残留会话持有自己的 L2 cwd —— 旧实现（读 MDC）会把它当作输出根的会话
        Path staleCwd = Files.createDirectories(tmp.resolve("stale-session-cwd"));
        SessionCwdHolder.set(STALE, staleCwd.toString());
        // 必须真实存在：LocalBashTaskRunner 现用显式会话的 cwd 作进程工作目录（不存在 → 进程起不来）
        SessionCwdHolder.set(EXPLICIT, Files.createDirectories(tmp.resolve("explicit-session-cwd")).toString());

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        String outputFile = BackgroundTaskRunner.taskOutputPath(EXPLICIT, taskId);
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "d7 显式会话", "tool-d7", System.currentTimeMillis(), null, null,
            outputFile, 0L, false);

        // 调用线程（工具线程等价物）写残留 MDC（第三态：别的会话）
        RequestContext.set(STALE, "msg-stale-d7");
        try {
            runner.spawn(task, "echo d7-explicit-session", EXPLICIT);
        } finally {
            RequestContext.clear();
        }

        // 等待真实 bg-task-worker 跑完（终态 + 通知已入队）
        awaitTerminal(taskId);
        awaitEnqueued();

        assertThat(sessionDirOf(outputFile))
            .as("输出根必须落在**显式传入**的会话目录下（旧实现读 MDC 会落 stale 会话）")
            .isEqualTo(EXPLICIT);
        assertThat(outputFile).doesNotContain(STALE);
        assertThat(Path.of(outputFile)).as("真实 bg-task-worker 已写出该文件").exists();
        assertThat(Files.readString(Path.of(outputFile))).contains("d7-explicit-session");

        // 该后台执行线程上 ambient 路线取不到会话（证明输出根不来自 ThreadLocal）
        assertThat(queue.enqueueThread.get())
            .as("完成通知必须在真实后台执行线程 bg-task-worker 上入队（证明上面是派生线程的真值）")
            .isNotNull()
            .contains("bg-task-worker");
        assertThat(queue.enqueueThreadMdc.get())
            .as("bg-task-worker 上 RequestContext.sessionId() 必须为 null —— 输出根不来自 ambient 路线")
            .isNull();
        assertThat(queue.itemSessionId.get())
            .as("通知携带的会话 = 显式传入的会话（非残留 MDC 会话）")
            .isEqualTo(EXPLICIT);
    }

    @Test
    @DisplayName("② registerAsyncAgent/registerAgentForeground：outputFile 落显式会话目录（子代理侧同源）")
    void agentTasks_outputBelongsToExplicitSession(@TempDir Path tmp) throws Exception {
        SessionCwdHolder.set(STALE, Files.createDirectories(tmp.resolve("stale2")).toString());
        SessionCwdHolder.set(EXPLICIT, Files.createDirectories(tmp.resolve("explicit2")).toString());
        RequestContext.set(STALE, "msg-stale-d7");
        try {
            UUID agentId = UUID.randomUUID();
            BackgroundTask async = runner.registerAsyncAgent(
                agentId, "d7 异步", "prompt", "general-purpose", null, EXPLICIT);
            BackgroundTask fg = runner.registerAgentForeground(
                UUID.randomUUID(), "d7 前台", "prompt", "general-purpose", EXPLICIT);

            assertThat(sessionDirOf(async.outputFile()))
                .as("async agent 输出根 = 显式会话（旧实现读 MDC → stale 会话目录）")
                .isEqualTo(EXPLICIT);
            assertThat(sessionDirOf(fg.outputFile())).isEqualTo(EXPLICIT);
            assertThat(async.outputFile()).doesNotContain(STALE);
            assertThat(fg.outputFile()).doesNotContain(STALE);
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * [批 3b-D7 · 签名收紧] `spawn` 的 createSessionId 现为**必填**：null/空白 ⇒ fail-loud。
     *
     * <p>WHY：用户裁定「不传递不能有守卫」—— 生产调用方（BashTool:2705 / PowerShellTool:1028）
     * 恒从 {@code ctx.sessionId()} 取值，签名不得留「可能没有」；且缺值若被静默接受，空白串会被写进
     * {@code task.sessionId()}（通知/输出根归属静默错）比报错更难查。旧实现（三源兜底）下本用例必红：
     * null 会被 MDC/sysprop/"unknown" 悄悄补上而不抛。
     */
    @Test
    @DisplayName("③ spawn/registerAsyncAgent 缺会话 → fail-loud（签名收紧后不再有「可空回落」守卫）")
    void spawnAndRegister_missingSession_failsLoud() {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "d7 缺会话", "tool-d7-null", System.currentTimeMillis(), null, null,
            BackgroundTaskRunner.taskOutputPath(EXPLICIT, taskId), 0L, false);

        assertThatThrownBy(() -> runner.spawn(task, "echo never", null))
            .as("spawn(null) → 缺值 fail-loud（旧实现回落 MDC/sysprop/unknown）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("createSessionId");
        assertThatThrownBy(() -> runner.spawn(task, "echo never", "   "))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> runner.registerAsyncAgent(
                UUID.randomUUID(), "缺会话", "prompt", "general-purpose", null, null))
            .as("registerAsyncAgent(null) → 输出根缺值 fail-loud")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.registerAgentForeground(
                UUID.randomUUID(), "缺会话", "prompt", "general-purpose", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.registerWorkflowTask("w-null-3b", "spec", "spec", null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 输出文件路径的 per-session 层（{...}/{sessionId}/tasks/{taskId}.output → sessionId）。 */
    private static String sessionDirOf(String outputFile) {
        Path tasksDir = Path.of(outputFile).getParent();
        return tasksDir.getParent().getFileName().toString();
    }

    private void awaitTerminal(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            BackgroundTask t = runner.listAllTasks().stream()
                .filter(x -> x.id().equals(taskId)).findFirst().orElse(null);
            if (t != null && t.status() != BackgroundTaskStatus.RUNNING) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("后台任务未在 30s 内到达终态（taskId=" + taskId + "）");
    }

    private void awaitEnqueued() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (queue.enqueueThread.get() != null) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("后台任务完成通知未入队（queue.enqueueThread 仍 null）");
    }
}
