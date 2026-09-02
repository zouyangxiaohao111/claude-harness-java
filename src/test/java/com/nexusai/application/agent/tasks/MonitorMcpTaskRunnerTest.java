package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MonitorMcpTaskRunner streaming 语义测试 — 对齐 CC LocalShellTask.tsx kind='monitor'
 * streaming-only（:129-144：脚本退出 = stream ended 非 condition met；:47 startStallWatchdog 对
 * monitor 是 noop）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>streaming 输出增长</b>：kind='monitor' 是 streaming-only（CC :130-131）——监控任务在
 *       <b>运行期</b>持续把观测写入 outputFile（TaskOutputTool 轮询 / 下游消费都依赖运行期文件增长）。
 *       旧实现（82L 同步快照）不写文件、无生命周期 → 流式语义缺失，本测试在 monitor 运行中（未终态）
 *       断言 outputFile 已增长。</li>
 *   <li><b>生命周期转换</b>：monitor 是 streaming-only → 脚本退出 = stream ended（CC :136），非 bash
 *       的 "N background commands completed" condition met 语义（:130-133 显式区分）→ completed 摘要
 *       必须是 {@code Monitor "desc" stream ended}；stop() kill → stopped（:142）；轮询抛错 →
 *       script failed（:139）。</li>
 *   <li><b>kill 分发</b>：stop()（BackgroundTaskRunner.stopTask 对 monitor_mcp 的委托，kill 等价）
 *       → 置 stopped + 中断轮询线程 → 流退出 → killed + {@code "stopped"} 摘要（CC :142）。</li>
 *   <li><b>通知摘要</b>：monitor 终态通知（LocalShellTask.tsx:160-165 5 TAG XML）摘要 = CC monitor
 *       分支（:136/139/142），priority='next'（:166-171 feature('MONITOR_TOOL') → 'next'）。
 *       monitor streaming-only 无 exit code 概念 → 不出现 "(exit N)" 段。</li>
 * </ul>
 */
@DisplayName("[W9-03] MonitorMcpTaskRunner streaming 语义（CC LocalShellTask.tsx kind='monitor'）")
class MonitorMcpTaskRunnerTest {

    @TempDir
    Path tempDir;

    // ════════════════════════════════════════════════════════════════
    // 测试基建：mock McpServerService + 直构 framework/sdk/queue + 字段注入
    // ════════════════════════════════════════════════════════════════

    /** 装配 runner：mock McpServerService + 真实 TaskFrameworkService/SdkEventQueue/NotificationQueue（可断言）。 */
    private static MonitorMcpTaskRunner newRunner(McpServerService mcp, TaskFrameworkService service,
                                                  SdkEventQueue sdk, NotificationQueue nq) {
        MonitorMcpTaskRunner runner = new MonitorMcpTaskRunner();
        ReflectionTestUtils.setField(runner, "mcpServerService", mcp);
        ReflectionTestUtils.setField(runner, "taskFrameworkService", service);
        ReflectionTestUtils.setField(runner, "sdkEventQueue", sdk);
        ReflectionTestUtils.setField(runner, "notificationQueue", nq);
        return runner;
    }

    /** 简单 MCP server fixture（error 状态附 lastError）。 */
    private static McpServerDto server(String id, McpStatus status) {
        // 11 参便捷构造（userFacingName/channelPermissions 走默认；gap31 字段本测试不断言）
        return new McpServerDto(id, "server-" + id, "cmd",
            List.of(), Map.of(), status, status == McpStatus.error ? "boom" : null, true, null, "stdio", null);
    }

    /** 输出文件行数（不存在视为 0，供轮询 lambda 使用）。 */
    private static long fileLineCount(Path out) {
        try {
            return Files.exists(out) ? Files.readAllLines(out).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 简单轮询等待（无 awaitility 依赖，手写兜底，同 LocalBashTaskRunnerStreamingTest）。 */
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
    // 1. streaming 输出增长（运行期 outputFile 持续增长，非终态一次性快照）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registerTask 落 RUNNING + m 前缀 + SDK task_started；monitor 运行期 outputFile 增量增长")
    void monitor_streamsObservations_whileRunning_outputGrows() throws Exception {
        // WHY（streaming-only 核心）：TaskOutputTool 轮询依赖运行期文件增长（CC :130-131
        // "the script exiting means the stream ended" — 流式是唯一输出通道）。旧实现一次性同步
        // 快照不写文件 → 运行期读空。本测试在 monitor 未终态时断言文件已随多轮观测增长。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        AtomicInteger tick = new AtomicInteger();
        when(mcp.listAll()).thenAnswer(inv -> {
            int n = tick.incrementAndGet();
            List<McpServerDto> servers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                servers.add(server("s" + i, McpStatus.running));
            }
            return servers;
        });

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);

        String taskId = runner.registerTask("monitor-growth", "tu-1");
        assertThat(taskId).as("monitor 任务 id 前缀 'm'（对齐 Task.ts:82 monitor_mcp:'m'）").startsWith("m");
        BackgroundTask registered = service.getTask(taskId).orElseThrow();
        assertThat(registered.status()).as("registerTask → store RUNNING").isEqualTo(BackgroundTaskStatus.RUNNING);

        Path out = tempDir.resolve(taskId + ".output");
        Thread t = new Thread(() -> runner.monitor(taskId, "monitor-growth", out.toString()), "monitor-growth");
        t.setDaemon(true);
        t.start();
        try {
            awaitUntil(() -> fileLineCount(out) >= 1 && fileContains(out, "servers=1"),
                "首个观测立即落盘");
            long size1 = Files.size(out);
            awaitUntil(() -> fileLineCount(out) >= 2, "第二次观测追加（文件增长）");
            long size2 = Files.size(out);
            assertThat(size2).as("运行期多轮观测 → outputFile 增量增长（streaming-only）").isGreaterThan(size1);
        } finally {
            runner.stop();
            t.join(3000);
        }
    }

    private static boolean fileContains(Path out, String needle) {
        try {
            return Files.exists(out) && Files.readString(out).contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. kill 分发：stop() → 流退出 killed + "stopped" 摘要 + SDK stopped + 通知 XML
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stop() kill 分发 → killed + 'Monitor \"desc\" stopped' 摘要 + SDK stopped + 通知 XML")
    void stop_requestsKill_streamTerminatesKilled() throws Exception {
        // WHY（CC :142 killed → `Monitor "desc" stopped`）：BackgroundTaskRunner.stopTask 对
        // monitor_mcp 委托本 runner.stop()（kill 等价）→ 置 stopped + 中断轮询线程 → 流退出。
        // 若 kill 分发不置 stopped/不中断，流永不退出 → 任务滞留 RUNNING 无法 evict。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        when(mcp.listAll()).thenReturn(List.of(server("s1", McpStatus.running), server("s2", McpStatus.error)));

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);

        String taskId = runner.registerTask("monitor-kill", "tu-2");
        Path out = tempDir.resolve(taskId + ".output");
        Thread t = new Thread(() -> runner.monitor(taskId, "monitor-kill", out.toString()), "monitor-kill");
        t.setDaemon(true);
        t.start();
        try {
            awaitUntil(() -> fileLineCount(out) >= 1, "监控已启动（首观测落盘）");
            assertThat(runner.isRunning()).as("stop 前 monitor 运行中").isTrue();

            runner.stop();
            awaitUntil(() -> service.getTask(taskId)
                .map(tk -> tk.status() == BackgroundTaskStatus.KILLED).orElse(false), "store 终态 killed");

            BackgroundTask terminal = service.getTask(taskId).orElseThrow();
            assertThat(terminal.status()).isEqualTo(BackgroundTaskStatus.KILLED);
            assertThat(terminal.notified()).as("终态 notified:true（可 evict，防双发）").isTrue();

            // SDK task_notification：status='stopped'（sdkEventQueue.ts:114-134）
            List<SdkEventQueue.DrainedSdkEvent> drained = sdk.drainSdkEvents(null);
            assertThat(drained).anySatisfy(e -> {
                assertThat(e.event()).isInstanceOf(SdkEventQueue.TaskNotificationEvent.class);
                SdkEventQueue.TaskNotificationEvent ev = (SdkEventQueue.TaskNotificationEvent) e.event();
                assertThat(ev.status()).isEqualTo("stopped");
                assertThat(ev.summary()).isEqualTo("Monitor \"monitor-kill\" stopped");
            });

            // 通知 XML（CC :160-165）：status=killed + summary='Monitor "desc" stopped' + priority=next
            // 注意：XML 摘要中的引号经 escapeXml 转义为 &quot;（escapeXml :306-316 双引号→&quot;）
            List<NotificationQueue.QueueItem> items = nq.dequeueAll();
            assertThat(items).anySatisfy(item -> {
                assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
                assertThat(item.priority()).isEqualTo(NotificationQueue.Priority.NEXT);
                assertThat(item.value())
                    .contains("<task-id>" + taskId + "</task-id>")
                    .contains("<status>killed</status>")
                    .contains("<summary>Monitor &quot;monitor-kill&quot; stopped</summary>");
            });
        } finally {
            runner.stop();
            t.join(3000);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 生命周期转换：流自然结束 → completed + "stream ended"（CC :136 非 condition met）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("流自然结束（无 stop 请求）→ completed + 'Monitor \"desc\" stream ended' 摘要")
    void naturalStreamEnd_completes_asStreamEnded() throws Exception {
        // WHY（CC :130-131 + :136）：monitor 是 streaming-only —— 脚本退出意味着 stream ended，
        // 非 bash 的 condition met / "N background commands completed" 折叠。若摘要写成 bash 风格
        // （"completed (exit N)"）即语义漂移。测试以仅置 running=false（无 stop 请求）模拟流自然结束。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        when(mcp.listAll()).thenReturn(List.of(server("s1", McpStatus.running)));

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);

        String taskId = runner.registerTask("monitor-end", "tu-3");
        Path out = tempDir.resolve(taskId + ".output");
        Thread t = new Thread(() -> runner.monitor(taskId, "monitor-end", out.toString()), "monitor-end");
        t.setDaemon(true);
        t.start();
        try {
            awaitUntil(() -> fileLineCount(out) >= 1, "监控已启动（首观测落盘）");
            // 模拟流自然结束：仅置 running=false（无 stop() → stopped=false → completed 分支）
            ReflectionTestUtils.setField(runner, "running", false);
            awaitUntil(() -> service.getTask(taskId)
                .map(tk -> tk.status() == BackgroundTaskStatus.COMPLETED).orElse(false), "store 终态 completed");

            List<NotificationQueue.QueueItem> items = nq.dequeueAll();
            assertThat(items).anySatisfy(item ->
                assertThat(item.value())
                    .contains("<status>completed</status>")
                    .contains("<summary>Monitor &quot;monitor-end&quot; stream ended</summary>"));
        } finally {
            runner.stop();
            t.join(3000);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 4. 生命周期转换：轮询抛错 → failed + "script failed" 摘要 + fail loud 重抛
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("观测抛错 → failed + 'Monitor \"desc\" script failed' 摘要 + 异常重抛（fail loud）")
    void snapshotError_marksFailed_scriptFailedSummary_andRethrows() {
        // WHY（CC :139 + 规则十二 fail loud）：monitor 轮询抛错 → script failed；若静默返回
        // "ok" 掩盖错误即违反显式失败。transitionTerminal 先行（store FAILED + 通知入队）再重抛。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenThrow(new RuntimeException("mcp down"));
        when(mcp.listAll()).thenReturn(List.of());

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);

        String taskId = runner.registerTask("monitor-fail", "tu-4");
        Path out = tempDir.resolve(taskId + ".output");

        assertThatThrownBy(() -> runner.monitor(taskId, "monitor-fail", out.toString()))
            .as("轮询抛错 → 重抛 RuntimeException（fail loud，不静默 ok）")
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("mcp down");

        BackgroundTask terminal = service.getTask(taskId).orElseThrow();
        assertThat(terminal.status()).as("transitionTerminal 先行 → store FAILED").isEqualTo(BackgroundTaskStatus.FAILED);

        List<NotificationQueue.QueueItem> items = nq.dequeueAll();
        assertThat(items).anySatisfy(item ->
            assertThat(item.value())
                .contains("<status>failed</status>")
                .contains("<summary>Monitor &quot;monitor-fail&quot; script failed</summary>"));
    }

    // ════════════════════════════════════════════════════════════════
    // 5. 方案B 五层唯一根收敛：monitor_mcp 输出落 {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks
    //    （旧 flat 独立根 {tmpdir}/nexusai-tasks 已删除，对齐 CC 唯一 diskOutput 机制）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("outputFileFor/registerTask 输出落五层唯一根 claude-{uid}/{sanitizedCwd}/{sessionId}/tasks（旧 flat nexusai-tasks 已删）")
    void outputFile_landsInUnifiedSessionScopedRoot() throws Exception {
        // WHY（方案B 意图，规则九）：CC 唯一 diskOutput 机制 = getTaskOutputDir = join(
        // getProjectTempDir(), getSessionId(), 'tasks')（diskOutput.ts:50-55），getProjectTempDir =
        // join(getClaudeTempDir(), sanitizePath(getOriginalCwd()))（filesystem.ts:376-378）—— 不存在
        // "每种任务类型一个根"。Java monitor_mcp 旧 flat 独立根 {tmpdir}/nexusai-tasks（无 per-session 层）
        // 是 Java 自创偏离、CC 无对应；收敛到五层唯一根后：
        //   (a) 并发会话不 clobber（diskOutput.ts:38-41 注释：sessionId 分层防并发会话污染）；
        //   (b) per-user/per-project 隔离（filesystem.ts:307-315/376-378）；
        //   (c) 与 Bash/PS/LOCAL_AGENT/remote_agent 同一读链（TaskOutputTool 经 BackgroundTaskRunner
        //       resolveOutputTask 读存储字段天然同源）。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        when(mcp.listAll()).thenReturn(List.of(server("s1", McpStatus.running)));

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);

        String sessionId = "sess-monitor-y";
        RequestContext.setSession(sessionId);
        try {
            String taskId = runner.registerTask("monitor-unify", "tu-y");
            String outputFile = runner.outputFileFor(taskId);
            // 五层唯一根：per-user + per-project + per-session（CC diskOutput.ts:50-55 + filesystem.ts:376-378）
            String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
            String sanitizedCwd = AutoMemPaths.sanitizePath(CwdResolution.getOriginalCwdLayer(sessionId));
            Path expected = Paths.get(tmpDir, BackgroundTaskRunner.claudeTempDirName(), sanitizedCwd,
                sessionId, "tasks", taskId + ".output");
            assertThat(outputFile).as("monitor_mcp 输出必须落五层唯一根（per-user + per-project + sessionId 层）")
                .isEqualTo(expected.toString());
            assertThat(Paths.get(outputFile).getParent().toString())
                .as("根目录 = claude-{uid}/{sanitizedCwd}/{sessionId}/tasks（CC getTaskOutputDir 语义）")
                .isEqualTo(Paths.get(tmpDir, BackgroundTaskRunner.claudeTempDirName(), sanitizedCwd,
                    sessionId, "tasks").toString());
            // 旧 flat 独立根不得再产出（CC 无对应偏离已删）
            assertThat(outputFile).doesNotContain("nexusai-tasks");

            // 与 BackgroundTaskRunner 唯一根同源（Bash/PS/LOCAL_AGENT/monitor/remote_agent 全收统一根）
            assertThat(outputFile).as("monitor_mcp 与 taskOutputPath 唯一根同源")
                .isEqualTo(BackgroundTaskRunner.taskOutputPath(taskId));

            // 流式写实际落该唯一根文件（父目录自动创建）—— 独立线程跑 monitor（阻塞轮询），
            // 首观测落盘后 stop() 中断退出（对齐既有 streaming 测试模式）
            Thread t = new Thread(() -> runner.monitor(taskId, "monitor-unify", null), "monitor-unify");
            t.setDaemon(true);
            t.start();
            try {
                awaitUntil(() -> fileLineCount(Paths.get(outputFile)) >= 1,
                    "monitor 输出实际写入唯一根文件（父目录自动创建）");
                assertThat(Paths.get(outputFile).getParent()).exists();
                assertThat(Paths.get(outputFile)).exists();
            } finally {
                runner.stop();
                t.join(3000);
            }
        } finally {
            RequestContext.clear();
        }
    }
}
