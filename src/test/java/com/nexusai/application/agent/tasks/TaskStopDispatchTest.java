package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskStop 按 type 分发 · 对齐 CC stopTask.ts:38-100（stopTask → getTaskByType → taskImpl.kill）
 * 与 killShellTasks.ts:16-46（killTask：杀子进程 + KILLED + notified=true）+ LocalAgentTask.tsx:281-303
 * （killAsyncAgent：only-if-running）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>R1 孤儿进程</b>：running bash 任务若被 TaskStop 路由到 killAsyncAgent（仅标 KILLED +
 *       agent 格式通知，不碰子进程），则 bash 子进程成为孤儿继续跑。bash 必须走 cancel()（killProcess
 *       杀子进程 + shell 格式通知 "Background command ..."）；agent 通知前缀是 "Agent ..." —— 通知
 *       格式是两条路径的可观测区分。</li>
 *   <li><b>返回 task_type 用实际类型</b>：旧实现硬编码 "bash"（TaskStopTool.java:167），不反映
 *       local_agent 等实际类型（CC TaskStopTool.ts:126 task_type: result.taskType）。</li>
 *   <li><b>LOCAL_AGENT 回归</b>：TaskStop 路径的 killAsyncAgent 必须仍生效——两条 killAsyncAgent
 *       调用链（AsyncAgentFinalizer.finalizeKilled + TaskStopTool）行为不变（OPD-TS-23）。</li>
 * </ul>
 */
@DisplayName("[OPD-TS-23] TaskStop 按 type 分发（bash→cancel 杀子进程 / local_agent→killAsyncAgent）")
class TaskStopDispatchTest {

    @TempDir
    Path tempDir;

    /** 测试上下文：runner + 可 drain 的 NotificationQueue（验证通知格式区分 bash/agent 路径） */
    private record Ctx(BackgroundTaskRunner runner, NotificationQueue queue) {}

    private Ctx newCtx() {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        return new Ctx(new BackgroundTaskRunner(nq, service, sdk), nq);
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

    @Test
    @DisplayName("running bash 任务 stopTask 走 cancel（shell 格式通知 + KILLED + task_type=local_bash）")
    void runningBashTask_stopTask_dispatchesToCancelShellFormat() throws Exception {
        // WHY: 旧实现 TaskStopTool 无条件先 killAsyncAgent（TaskStopTool.java:158）→ bash 任务被标
        //   KILLED 但子进程未杀（R1 孤儿进程）+ agent 格式通知误报。修复后 bash 走 cancel() →
        //   killProcess 杀子进程 + shell 格式通知（"Background command ..." 前缀，CC LocalShellTask.tsx:154）。
        Ctx ctx = newCtx();
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        String command = "sleep 30";
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            command, "tu-" + taskId, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false, null, false);
        ctx.runner.spawn(task, command, null);

        // 平台容错重试：Windows JVM 无法解析 /bin/sh（LocalBashTaskRunner 硬编码 /bin/sh）时任务
        //   自失败 → NOT_RUNNING → assume skip；Linux（真实部署环境）sleep 30 保证 RUNNING → 全断言。
        BackgroundTaskRunner.StopTaskResult result = null;
        for (int i = 0; i < 40; i++) {
            result = ctx.runner.stopTask(taskId);
            if (result.ok()) {
                break;
            }
            if (result.errorCode() == BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING) {
                Assumptions.assumeTrue(false,
                    "本机 JVM 无法解析 /bin/sh（Windows 平台限制），跳过 bash 分发断言（Linux 上执行）");
            }
            Thread.sleep(50);
        }
        assertThat(result.ok()).as("bash 任务应可停止").isTrue();
        assertThat(result.taskType()).as("bash → task_type=local_bash（CC TaskStopTool.ts:126，非硬编码）")
            .isEqualTo("local_bash");
        assertThat(result.command()).as("command 承载 bash command").isEqualTo(command);

        // KILLED + notified=true（CC killShellTasks.ts:37-38 抑制后续通知）
        BackgroundTask after = ctx.runner.getTask(taskId).orElseThrow();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(after.notified()).as("kill 后抑制后续通知（CC stopTask.ts:82 notified=true）").isTrue();

        // 通知必须是 shell 格式（"Background command" 前缀 + 无 <result> 段）→ 证明走 cancel/markKilled
        //   （若误走 killAsyncAgent 会是 agent 格式 "Agent ..." —— R1 前科路径）。
        String xml = ctx.queue.dequeueAll().stream()
            .map(NotificationQueue.QueueItem::value)
            .collect(Collectors.joining("\n"));
        assertThat(xml).as("bash 通知应为 shell 格式（killProcess 路径）").contains("Background command");
        assertThat(xml).as("bash 通知不应为 agent 格式（非 killAsyncAgent 路径）").doesNotContain("Agent \"");
    }

    @Test
    @DisplayName("R1 最终证明：bash 子进程被杀（非 Windows，Linux 部署环境）")
    void runningBashTask_stopTask_killsSubprocess() throws Exception {
        // WHY: R1 的最终形态 = bash 子进程不成为孤儿。本测试依赖 bash `$$` 即真实进程 PID 语义：
        //   Linux 上 `$$`=真实 PID、`exec sleep` 同 PID；MSYS/Git Bash 的 `$$` 是 MSYS 模拟 PID
        //   （非 Windows 进程 PID），ProcessHandle.of 无法匹配 → 进程 kill 断言在 Windows 无意义。
        //   故仅 Linux 验证（旧实现 Windows 因 /bin/sh 不可解析也跳过，原因不同但同为跳过）。
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
            "MSYS $$ 非 Windows 真实 PID，进程 kill 验证需 Linux 真实 PID 语义");
        Ctx ctx = newCtx();
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        Path pidFile = tempDir.resolve(taskId + ".pid");
        // echo $$ 写 sh PID → exec sleep 保持同 PID → currentProcess 即该 PID
        String command = "echo $$ > " + pidFile + "; exec sleep 30";
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            command, "tu-" + taskId, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false, null, false);
        ctx.runner.spawn(task, command, null);

        awaitUntil(() -> Files.exists(pidFile), "bash 子进程 PID 文件写出");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            .as("前置：子进程应处于 running 状态").isTrue();

        BackgroundTaskRunner.StopTaskResult result = ctx.runner.stopTask(taskId);
        assertThat(result.ok()).isTrue();

        // 子进程已被 killProcess 杀死 —— R1 孤儿进程修复的最终证明
        awaitUntil(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "bash 子进程终止");
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            .as("bash 子进程必须被杀，不能成为孤儿").isFalse();
    }

    @Test
    @DisplayName("running local_agent 任务 stopTask 走 killAsyncAgent + 幂等（回归）")
    void runningLocalAgentTask_stopTask_goesThroughKillAsyncAgent() {
        // WHY: LOCAL_AGENT 必须仍走 killAsyncAgent（only-if-running 原子守卫），不能误入 cancel。
        //   回归 OPD-TS-23：TaskStop 路径 killAsyncAgent 行为不变。
        Ctx ctx = newCtx();
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", "general-purpose", null, null);

        BackgroundTaskRunner.StopTaskResult result = ctx.runner.stopTask(taskId);

        assertThat(result.ok()).isTrue();
        assertThat(result.taskType()).as("agent 类型字符串 local_agent").isEqualTo("local_agent");
        assertThat(result.command()).as("command 承载 agent description").isEqualTo("调研任务");

        BackgroundTask after = ctx.runner.getTask(taskId).orElseThrow();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.KILLED);

        // 幂等：二次 stopTask 短路（only-if-running 守卫 → not_running）
        BackgroundTaskRunner.StopTaskResult second = ctx.runner.stopTask(taskId);
        assertThat(second.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
    }

    @Test
    @DisplayName("running dream 任务 stopTask → DreamTaskRegistry.kill（getTaskByType('dream') → DreamTask.kill · OPD-TP-09）")
    void runningDreamTask_stopTask_dispatchesToDreamKill() {
        // WHY: dream 任务注册在 DreamTaskRegistry + TaskFrameworkService store（不经 spawn），
        //   stopTask 必须经注册表分发到 DreamTask.kill（abort fork + priorMtime 回退锁）；否则
        //   不可终止 → RK-9 悬空 fork 无法回收。task_type=dream / command='dreaming'（CC :97
        //   非 shell 用 description）。
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        DreamTaskRegistry dream = new DreamTaskRegistry(service);
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, service, sdk);
        runner.setDreamTaskRegistry(dream);
        AbortController abort = new AbortController();
        String taskId = dream.registerDreamTask(2, 999L, abort);

        BackgroundTaskRunner.StopTaskResult result = runner.stopTask(taskId);

        assertThat(result.ok()).isTrue();
        assertThat(result.taskType()).as("dream → task_type=dream（CC TaskType 枚举小写值）").isEqualTo("dream");
        assertThat(result.command()).as("非 shell 任务 command 承载 description（CC stopTask.ts:97）").isEqualTo("dreaming");
        // DreamTask.kill 语义：abort + killed + notified=true（DreamTask.ts:136-156）
        DreamTaskState after = dream.getDreamTask(taskId).orElseThrow();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(after.notified()).isTrue();
        assertThat(abort.isCancelled()).as("kill 必须 abort fork（DreamTask.ts:140）").isTrue();

        // 幂等：二次 stopTask 短路（status=killed 非 running → not_running）
        BackgroundTaskRunner.StopTaskResult second = runner.stopTask(taskId);
        assertThat(second.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
    }

    @Test
    @DisplayName("不存在任务 → NOT_FOUND（CC StopTaskError not_found :46-48）")
    void stopTask_notFound_returnsNotFound() {
        Ctx ctx = newCtx();
        BackgroundTaskRunner.StopTaskResult result = ctx.runner.stopTask("no-such-task");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("非 running 任务 → NOT_RUNNING（CC StopTaskError not_running :50-55）")
    void stopTask_notRunning_returnsNotRunning() {
        Ctx ctx = newCtx();
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        ctx.runner.registerAsyncAgent(agentId, "调研任务", "prompt", "general-purpose", null, null);
        ctx.runner.completeAsyncAgent(taskId, AsyncAgentResult.success("结论", 1, 10L, "agent-x"));

        BackgroundTaskRunner.StopTaskResult result = ctx.runner.stopTask(taskId);
        assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
    }

    /** 可控 RemoteSessionsApi 桩 —— 断言 archive 被 kill 触发。 */
    static class StubRemoteApi implements com.nexusai.application.agent.remote.RemoteSessionsApi {
        boolean archiveCalled;

        @Override
        public com.nexusai.application.agent.remote.RemoteSessionsApi.SessionResource fetchSession(String sessionId) {
            return new com.nexusai.application.agent.remote.RemoteSessionsApi.SessionResource(sessionId, "running", Map.of());
        }

        @Override
        public com.nexusai.application.agent.remote.RemoteSessionsApi.PollResult pollEvents(String sessionId, String afterId) {
            return com.nexusai.application.agent.remote.RemoteSessionsApi.PollResult.eventsOnly(List.of(), null);
        }

        @Override
        public void archiveSession(String sessionId) {
            archiveCalled = true;
        }

        @Override
        public boolean sendEventToRemoteSession(String sessionId, Object content, String uuid) {
            return true;
        }

        @Override
        public boolean updateSessionTitle(String sessionId, String title) {
            return true;
        }
    }

    /** 构建 runner + 独立 SdkEventQueue + RemoteAgentTaskService 的测试上下文。 */
    private record RemoteCtx(BackgroundTaskRunner runner, NotificationQueue queue,
                             SdkEventQueue sdk, TaskFrameworkService framework,
                             com.nexusai.application.agent.remote.RemoteAgentTaskService remoteService,
                             StubRemoteApi api,
                             java.util.concurrent.ScheduledExecutorService scheduler) {
        void close() {
            scheduler.shutdownNow();
        }
    }

    private RemoteCtx newRemoteCtx() {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, service, sdk);
        StubRemoteApi api = new StubRemoteApi();
        java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "test-remote-poll");
                t.setDaemon(true);
                return t;
            });
        com.nexusai.application.agent.remote.RemoteAgentTaskService svc =
            new com.nexusai.application.agent.remote.RemoteAgentTaskService(
                service, nq, sdk, api, () -> tempDir, () -> tempDir.resolve("out"), scheduler, 50L);
        runner.setRemoteAgentTaskService(svc);
        return new RemoteCtx(runner, nq, sdk, service, svc, api, scheduler);
    }

    @Test
    @DisplayName("remote_agent 任务 stopTask 走 RemoteAgentTask.kill（归档 + SDK stopped + KILLED）")
    void remoteAgentTask_stopTask_dispatchesToRemoteAgentKill() throws Exception {
        // WHY: M-9 —— CC getTaskByType('remote_agent') → RemoteAgentTask.kill（RemoteAgentTask.tsx:811-847）。
        //   remote 任务不经 BackgroundTaskRunner.spawn（注册在 RemoteAgentTaskService.remoteTasks +
        //   framework store）→ 本地 tasks 查不到，必须回退 RemoteAgentTaskService 分发；误走本地
        //   not_found 会留下 running 远程会话持续消耗云资源（archive 永远不触发）。
        RemoteCtx ctx = newRemoteCtx();
        try {
            com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisterOptions opts =
                new com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisterOptions(
                    com.nexusai.application.agent.remote.RemoteTaskType.REMOTE_AGENT,
                    "sess-1", "部署", "claude -p 'deploy'", "tu-r1", null, null, null, null, null);
            com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisteredRemoteTask reg =
                ctx.remoteService().registerRemoteAgentTask(opts);

            BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(reg.taskId());

            assertThat(result.ok()).isTrue();
            assertThat(result.taskType()).as("remote_agent → task_type=remote_agent（CC task.type）")
                .isEqualTo("remote_agent");
            // 经 RemoteAgentTask.kill：KILLED + notified + SDK stopped + archive（:811-847）
            assertThat(ctx.remoteService().findTask(reg.taskId()).status()).isEqualTo(BackgroundTaskStatus.KILLED);
            assertThat(ctx.api().archiveCalled).as("kill 必须触发 archiveRemoteSession（释放云资源，:840-842）").isTrue();
            List<SdkEventQueue.DrainedSdkEvent> drained = ctx.sdk().drainSdkEvents(null);
            boolean stopped = drained.stream().anyMatch(d ->
                d.event() instanceof SdkEventQueue.TaskNotificationEvent e && "stopped".equals(e.status()));
            assertThat(stopped).as("kill 必须发 task_notification stopped SDK 事件（:835-838）").isTrue();
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("remote_agent 任务非 running → NOT_RUNNING（M-9 分发状态守卫）")
    void remoteAgentTask_stopTask_notRunning_returnsNotRunning() {
        RemoteCtx ctx = newRemoteCtx();
        try {
            com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisterOptions opts =
                new com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisterOptions(
                    com.nexusai.application.agent.remote.RemoteTaskType.REMOTE_AGENT,
                    "sess-1", "部署", "claude -p 'deploy'", "tu-r1", null, null, null, null, null);
            com.nexusai.application.agent.remote.RemoteAgentTaskService.RegisteredRemoteTask reg =
                ctx.remoteService().registerRemoteAgentTask(opts);
            ctx.remoteService().kill(reg.taskId()); // 先杀掉 → 非 running

            BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(reg.taskId());
            assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
        } finally {
            ctx.close();
        }
    }

    @Test
    @DisplayName("本地 tasks 主分支未处理的类型 → UNSUPPORTED_TYPE（CC StopTaskError unsupported_type :57-63，不误杀）")
    void stopTask_unsupportedType_returnsUnsupportedType() throws Exception {
        // WHY: stopTask 主分支（:1149-1200）仅分发 LOCAL_BASH/LOCAL_AGENT/DREAM/REMOTE_AGENT/
        //   MONITOR_MCP/LOCAL_WORKFLOW；IN_PROCESS_TEAMMATE 正常走 registry 回退
        //   （stopInProcessTeammateTask :1126-1134），若异常出现在本地 tasks 且 RUNNING → 主分支
        //   无此 else-if → else 显式 UNSUPPORTED_TYPE（对齐 CC getTaskByType undefined →
        //   unsupported_type）。旧断言用 LOCAL_WORKFLOW 已过时（W-4b 已加 killWorkflowTask 分支，
        //   该类型已支持 → 走 killWorkflowTask 标 KILLED，非 unsupported）。
        Ctx ctx = newCtx();
        String taskId = TaskIdGenerator.generate(TaskType.IN_PROCESS_TEAMMATE);
        String command = "sleep 30";
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            command, null, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false, null, false);
        ctx.runner.spawn(task, command, null); // spawn 不校验 type，作为 RUNNING 的非 bash/agent 任务

        BackgroundTaskRunner.StopTaskResult result = ctx.runner.stopTask(taskId);
        assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.UNSUPPORTED_TYPE);
        // 未支持的 task 不应被误标 KILLED（不误杀）
        assertThat(ctx.runner.getTask(taskId).orElseThrow().status()).isEqualTo(BackgroundTaskStatus.RUNNING);

        ctx.runner.cancel(taskId); // 清理
    }
}
