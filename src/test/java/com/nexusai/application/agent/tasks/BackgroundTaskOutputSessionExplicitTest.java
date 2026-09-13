package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.SessionCwdHolder;
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
 * [批 3b-D7 · 用户裁定「显式传参」] 后台任务的输出根必须归属<b>发起方显式传入的会话</b>。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：旧实现 {@code BackgroundTaskRunner.resolveSessionId()}
 * 读「当前线程的 ambient 会话槽」（→ sysprop → {@code "unknown"}）。本类的调用点全在派生线程
 * （tool-exec 池 / 本类的 {@code bg-task-worker} / 轮询定时器）——CC {@code diskOutput.ts:50-55}
 * 用的是 {@code STATE.sessionId}（进程单例，:425-427 state.ts），Java 多会话<b>没有对应物</b>，
 * 只能由发起方（AI 工具调用手上就有 {@code ToolUseContext.sessionId()}）显式透传。
 * [批 3c] 该 ambient 槽已整类删除。
 *
 * <p><b>鉴别力来源</b>：让另一个会话（{@code STALE}）持有自己的 L2 cwd —— 任何按 ambient 会话解析
 * 的实现都会把输出写进<b>那个会话</b>的目录；修复后只认显式入参。
 * 断言落点 = 真实文件（{@code bg-task-worker} 真跑 bash 写出），不是纯路径字符串比较。
 */
@DisplayName("批 3b-D7 · 后台任务输出根 = 显式会话（真实 bg-task-worker 线程）")
class BackgroundTaskOutputSessionExplicitTest {

    private static final String EXPLICIT = "sess-d7-explicit";
    private static final String STALE = "sess-d7-stale-third-state";

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final RecordingNotificationQueue queue = new RecordingNotificationQueue();
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, framework);

    @AfterEach
    void tearDown() {
        runner.shutdown();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    /** 记录 enqueue 发生所在线程（真实 bg-task-worker）与其携带的会话（不改语义，纯观察）。 */
    static class RecordingNotificationQueue extends NotificationQueue {
        final AtomicReference<String> enqueueThread = new AtomicReference<>();
        final AtomicReference<String> itemSessionId = new AtomicReference<>("<未入队>");

        @Override
        public synchronized void enqueuePendingNotification(QueueItem item) {
            enqueueThread.set(Thread.currentThread().getName());
            itemSessionId.set(item.sessionId());
            super.enqueuePendingNotification(item);
        }
    }

    @Test
    @DisplayName("① spawn 的输出落显式会话目录（不是另一会话目录）")
    void spawnedTask_outputBelongsToExplicitSession(@TempDir Path tmp) throws Exception {
        // 另一会话持有自己的 L2 cwd —— 任何按 ambient 会话解析的实现会把它当作输出根的会话
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

        // [批 3c] 语义消失：原此处先在调用线程写「别的会话」的裸 MDC 值当反向对照（第三态）。
        //   该 ambient 会话槽已整类删除 ⇒ 装置删除；反向鉴别力由上面的「另一会话持有自己的 L2 cwd」
        //   诱饵承担，断言全部原样保留。
        runner.spawn(task, "echo d7-explicit-session", EXPLICIT);

        // 等待真实 bg-task-worker 跑完（终态 + 通知已入队）
        awaitTerminal(taskId);
        awaitEnqueued();

        assertThat(sessionDirOf(outputFile))
            .as("输出根必须落在**显式传入**的会话目录下（按 ambient 会话解析会落 stale 会话）")
            .isEqualTo(EXPLICIT);
        assertThat(outputFile).doesNotContain(STALE);
        assertThat(Path.of(outputFile)).as("真实 bg-task-worker 已写出该文件").exists();
        assertThat(Files.readString(Path.of(outputFile))).contains("d7-explicit-session");

        assertThat(queue.enqueueThread.get())
            .as("完成通知必须在真实后台执行线程 bg-task-worker 上入队（证明上面是派生线程的真值）")
            .isNotNull()
            .contains("bg-task-worker");
        assertThat(queue.itemSessionId.get())
            .as("通知携带的会话 = 显式传入的会话（非任何 ambient 会话）")
            .isEqualTo(EXPLICIT);
    }

    @Test
    @DisplayName("② registerAsyncAgent/registerAgentForeground：outputFile 落显式会话目录（子代理侧同源）")
    void agentTasks_outputBelongsToExplicitSession(@TempDir Path tmp) throws Exception {
        SessionCwdHolder.set(STALE, Files.createDirectories(tmp.resolve("stale2")).toString());
        SessionCwdHolder.set(EXPLICIT, Files.createDirectories(tmp.resolve("explicit2")).toString());
        // [批 3c] 语义消失：原此处写「别的会话」的裸 MDC 当反向对照 —— 该槽已整类删除，装置删除。
        UUID agentId = UUID.randomUUID();
        BackgroundTask async = runner.registerAsyncAgent(
            agentId, "d7 异步", "prompt", "general-purpose", null, EXPLICIT);
        BackgroundTask fg = runner.registerAgentForeground(
            UUID.randomUUID(), "d7 前台", "prompt", "general-purpose", EXPLICIT);

        assertThat(sessionDirOf(async.outputFile()))
            .as("async agent 输出根 = 显式会话（按 ambient 会话解析会落 stale 会话目录）")
            .isEqualTo(EXPLICIT);
        assertThat(sessionDirOf(fg.outputFile())).isEqualTo(EXPLICIT);
        assertThat(async.outputFile()).doesNotContain(STALE);
        assertThat(fg.outputFile()).doesNotContain(STALE);
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
