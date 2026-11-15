package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorTaskRunner 测试 · 对齐 CC Monitor 的「每行 stdout 是事件」语义。
 *
 * <p>用真实子进程（echo / sleep）验证：输出落盘、退出终态、超时、手动停止。
 */
@DisplayName("[monitor-cc] MonitorTaskRunner")
class MonitorTaskRunnerTest {

    private BackgroundTaskRunner backgroundTaskRunner;
    private TaskFrameworkService taskFrameworkService;
    private NotificationQueue notificationQueue;
    private MonitorTaskRunner runner;

    @BeforeEach
    void setUp() {
        backgroundTaskRunner = mock(BackgroundTaskRunner.class);
        taskFrameworkService = mock(TaskFrameworkService.class);
        notificationQueue = mock(NotificationQueue.class);
        when(backgroundTaskRunner.getTask(any())).thenReturn(java.util.Optional.empty());
        runner = new MonitorTaskRunner(backgroundTaskRunner, taskFrameworkService, notificationQueue);
    }

    @AfterEach
    void tearDown() {
        // 确保测试不留下运行中的子进程
        for (String id : List.of("b-test")) {
            try {
                runner.stop(id, "stopped");
            } catch (Exception ignored) {
            }
        }
    }

    private static String awaitFileContains(Path file, String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains(needle)) {
                    return text;
                }
            }
            Thread.sleep(50);
        }
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static Path outputPath(String file) {
        return Path.of(file);
    }

    /** 测试环境 shell = Git bash（ShellResolver 实测），sleep 可用且足够长。 */
    private static String longSleepCommand() {
        return "sleep 5";
    }

    @Test
    @DisplayName("注册的任务 type=MONITOR（任务面板显示「监控」而非「命令」）")
    void registersMonitorTaskType() throws Exception {
        ArgumentCaptor<BackgroundTask> cap = ArgumentCaptor.forClass(BackgroundTask.class);

        String taskId = runner.startCommand("echo x", "类型测试", 0, "c5", null, "sess-mon");

        org.mockito.Mockito.verify(backgroundTaskRunner)
            .registerExternalTask(cap.capture(), any());
        assertThat(cap.getValue().type()).isEqualTo(TaskType.MONITOR);
        assertThat(cap.getValue().id()).isEqualTo(taskId);
        runner.stop(taskId, "stopped");
    }

    @Test
    @DisplayName("command：每行 stdout 落进 outputFile（alpha / beta）")
    void command_streamsEachLineToOutputFile() throws Exception {
        String taskId = runner.startCommand("echo alpha; echo beta", "看两行", 0, "c1", null, "sess-mon");
        Path out = outputPath(BackgroundTaskRunner.taskOutputPath("sess-mon", taskId));

        String text = awaitFileContains(out, "beta", 30_000);

        assertThat(text).contains("alpha").contains("beta");
    }

    @Test
    @DisplayName("command 退出 ⇒ 输出写盘（stream ended 语义由 stop 用例覆盖）")
    void command_exitWritesOutput() throws Exception {
        String taskId = runner.startCommand("echo done", "退出测试", 0, "c2", null, "sess-mon");
        Path out = outputPath(BackgroundTaskRunner.taskOutputPath("sess-mon", taskId));

        String text = awaitFileContains(out, "done", 30_000);

        assertThat(text).contains("done");
    }

    @Test
    @DisplayName("timeout 语义 ⇒ stop(\"timed out\") 发终态通知")
    void timeout_notifiesTimedOut() throws Exception {
        String taskId = runner.startCommand(longSleepCommand(), "超时测试", 0, "c3", null, "sess-mon");
        Thread.sleep(200);
        runner.stop(taskId, "timed out");

        ArgumentCaptor<NotificationQueue.QueueItem> cap =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        org.mockito.Mockito.verify(notificationQueue,
            org.mockito.Mockito.timeout(30_000).atLeastOnce()).enqueuePendingNotification(cap.capture());

        assertThat(cap.getAllValues()).anyMatch(i -> i.value().contains("timed out"));
    }

    @Test
    @DisplayName("stop() ⇒ 停止并通知 stopped")
    void stop_notifiesStopped() throws Exception {
        String taskId = runner.startCommand(longSleepCommand(), "停止测试", 0, "c4", null, "sess-mon");
        Thread.sleep(300);
        runner.stop(taskId, "stopped");

        ArgumentCaptor<NotificationQueue.QueueItem> cap =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        org.mockito.Mockito.verify(notificationQueue,
            org.mockito.Mockito.timeout(30_000).atLeastOnce()).enqueuePendingNotification(cap.capture());

        assertThat(cap.getAllValues()).anyMatch(i -> i.value().contains("stopped"));
    }

    @Test
    @DisplayName("ws：非 ws/wss 协议 ⇒ 拒绝")
    void ws_rejectsNonWsScheme() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                runner.startWebSocket("https://example.com", List.of(), "d", 0, "c", null, "sess-mon"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ws://");
    }

    @Test
    @DisplayName("ws：host 不在 allowed_domains ⇒ 拒绝")
    void ws_rejectsDomainNotAllowed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                runner.startWebSocket("wss://evil.com/feed", List.of("example.com"), "d", 0, "c", null, "sess-mon"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowed_domains");
    }

    @Test
    @DisplayName("ws：非法 URL ⇒ 拒绝")
    void ws_rejectsInvalidUrl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                runner.startWebSocket("::not a url::", List.of(), "d", 0, "c", null, "sess-mon"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("domainAllowed：精确与后缀匹配")
    void domainAllowed_matchesExactAndSuffix() {
        assertThat(MonitorTaskRunner.domainAllowed("example.com", List.of("example.com"))).isTrue();
        assertThat(MonitorTaskRunner.domainAllowed("api.example.com", List.of("example.com"))).isTrue();
        assertThat(MonitorTaskRunner.domainAllowed("evilexample.com", List.of("example.com"))).isFalse();
        assertThat(MonitorTaskRunner.domainAllowed("example.com", List.of("other.com"))).isFalse();
    }
}
