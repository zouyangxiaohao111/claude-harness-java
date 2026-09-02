package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * remote_agent 状态机定向测试 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:386-847。
 *
 * <p><b>WHY（意图验证，规则九）</b>：远程任务生命周期 = 注册(r 前缀+sidecar+poll) →
 * 恢复(--resume 判活) → 轮询(archived/result/stableIdle 判完成) → kill(归档+SDK 收尾)。
 * 每个测试锁死一个 CC 行号行为，防止"状态机写跑偏但测试仍绿"的假实现。
 */
@DisplayName("[W6-02] RemoteAgentTaskService 状态机（register/restore/poll/kill，对齐 CC RemoteAgentTask.tsx）")
class RemoteAgentTaskServiceTest {

    @TempDir
    Path tempDir;

    private ScheduledExecutorService scheduler;
    private TaskFrameworkService framework;
    private NotificationQueue notifications;
    private SdkEventQueue sdkEvents;
    private StubSessionsApi api;
    private RemoteAgentTaskService service;

    /** 可控 RemoteSessionsApi 桩 — 队列消费 poll 响应，最后一个无限重复。 */
    static class StubSessionsApi implements RemoteSessionsApi {
        SessionResource fetchResult = new SessionResource("sess-1", "running", Map.of());
        RuntimeException fetchError;
        final List<PollResult> pollQueue = new ArrayList<>();
        PollResult defaultPoll = PollResult.eventsOnly(List.of(), null);
        boolean archiveCalled;
        String archivedSession;
        int pollCalls;

        @Override
        public SessionResource fetchSession(String sessionId) {
            if (fetchError != null) {
                throw fetchError;
            }
            return fetchResult;
        }

        @Override
        public PollResult pollEvents(String sessionId, String afterId) {
            pollCalls++;
            if (!pollQueue.isEmpty()) {
                return pollQueue.remove(0);
            }
            return defaultPoll;
        }

        @Override
        public void archiveSession(String sessionId) {
            archiveCalled = true;
            archivedSession = sessionId;
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

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-poll");
            t.setDaemon(true);
            return t;
        });
        sdkEvents = new SdkEventQueue();
        framework = new TaskFrameworkService(sdkEvents);
        notifications = new NotificationQueue();
        api = new StubSessionsApi();
        service = new RemoteAgentTaskService(framework, notifications, sdkEvents, api,
            () -> tempDir, () -> tempDir.resolve("output"),
            scheduler, 15L); // 15ms 加速轮询
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // ── helpers ──

    private RemoteAgentTaskService.RegisterOptions options(String sessionId, String title) {
        // 第 10 参 creatingSessionId = null（无本地会话，回落全局）—— cron-notify 前语义不变。
        return new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.REMOTE_AGENT,
            sessionId, title, "claude -p 'do it'", "tool-1", null, null, null, null, null);
    }

    private static void await(long timeoutMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private List<NotificationQueue.QueueItem> drainTaskNotifications() {
        return notifications.dequeueAllMatching(i ->
            NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode()));
    }

    /** assistant 事件（SDK 格式，CC :570-571） */
    private static Map<String, Object> assistantEvent(String text) {
        return Map.of("type", "assistant",
            "message", Map.of("content", List.of(Map.of("type", "text", "text", text))));
    }

    /** result 事件（CC :610） */
    private static Map<String, Object> resultEvent(String subtype) {
        return Map.of("type", "result", "subtype", subtype);
    }

    private SdkEventQueue.TaskNotificationEvent firstTerminated() {
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEvents.drainSdkEvents(null);
        for (SdkEventQueue.DrainedSdkEvent d : drained) {
            if (d.event() instanceof SdkEventQueue.TaskNotificationEvent e) {
                return e;
            }
        }
        return null;
    }

    // ── 注册 ──

    // ── 批次Y Q3：输出根收敛唯一根 + 红线豁免（输出 temp，sidecar 留项目目录） ──

    @Test
    @DisplayName("批次Y: 输出落 taskOutputDirSupplier(temp 唯一根)，sidecar 落 sessionDirSupplier(项目目录) —— 两根解耦（红线豁免）")
    void register_outputInTempRoot_sidecarInProjectDir_decoupled() throws Exception {
        // WHY（批次Y Q3 意图，规则九）：CC RemoteAgentTask.tsx 输出走统一 diskOutput（temp，
        //   绝不在项目目录）；项目目录只有元数据 sidecar remote-agents/*.meta.json
        //   （sessionStorage.ts:320-328）。Java 旧设计把输出文件写进项目工作目录树
        //   {projectRoot}/{sessionId}/tasks = CC 无对应的 Java 自创偏离；本测试锁死红线豁免后的
        //   拆分：taskOutputDirSupplier（temp）与 sessionDirSupplier（项目目录）是<b>两个独立根</b>，
        //   输出不再经 currentSessionProjectRoot（projectRoot 回落链本身不动，只拆输出落点耦合）。
        //   若回归把输出写回项目目录（两 supplier 合并），本测试立即变红。
        // 本测试注入：sessionDir=tempDir（项目目录），taskOutputDir=tempDir.resolve("output")（temp 根）
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        try {
            // 输出文件 = taskOutputDirSupplier + <taskId>.output（init 建空文件）
            Path outputPath = tempDir.resolve("output").resolve(reg.taskId() + ".output");
            assertThat(outputPath).as("输出文件必须落 taskOutputDirSupplier（temp 根）").exists();
            // 输出文件不在项目目录根（sessionDir=tempDir）下 —— 两根解耦，输出不再写项目目录
            assertThat(tempDir.resolve(reg.taskId() + ".output"))
                .as("项目目录（sessionDir）下不得有输出文件（输出根与 sidecar 根解耦）").doesNotExist();
            assertThat(tempDir.resolve("tasks").resolve(reg.taskId() + ".output"))
                .as("旧项目目录 tasks 根下不得有输出文件").doesNotExist();
            // sidecar 留项目目录（sessionDirSupplier → remote-agents）
            assertThat(RemoteAgentMetadataStore.getRemoteAgentMetadataPath(tempDir, reg.taskId()))
                .as("sidecar 留项目目录（对齐 CC sessionStorage.ts:320-328）").exists();
        } finally {
            reg.cleanup().run();
        }
    }

    @Test
    @DisplayName("批次Y: framework store 承载的 outputFile 指向新唯一根（读方/TaskOutputTool 读存储字段同源）")
    void register_storeOutputFilePointsToUnifiedRoot() throws Exception {
        // WHY（批次Y Q3 意图）：读方（TaskOutputTool → BackgroundTaskRunner.getOutput →
        //   resolveOutputTask → task.outputFile()）读<b>存储字段</b>，不重算路径 —— stamp 时用
        //   同源函数则读方自动跟随。断言 framework store 中 base.outputFile 指向 taskOutputDirSupplier
        //   唯一根（{tmpdir}/nexusai-sessions/{sessionId}/tasks/{taskId}.output 由注入 supplier 决定），
        //   而非旧项目目录根。回归到项目目录根即变红。
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        try {
            var stored = framework.getTask(reg.taskId()).orElseThrow();
            assertThat(stored.outputFile())
                .as("store 承载 outputFile = taskOutputDirSupplier 唯一根")
                .isEqualTo(tempDir.resolve("output").resolve(reg.taskId() + ".output").toString());
            // 旧项目目录根形态（{sessionDir}/{sessionId}/tasks 或直接 {sessionDir}/tasks）不得再产出
            assertThat(stored.outputFile()).doesNotContain(tempDir.resolve("tasks").toString());
            assertThat(stored.outputFile()).startsWith(tempDir.resolve("output").toString());
        } finally {
            reg.cleanup().run();
        }
    }

    @Test
    @DisplayName("批次Y: 完成通知 <output-file> 引用新唯一根（读方透传新路径，不硬编码旧项目目录根）")
    void pollTerminalNotification_outputFileReferencesUnifiedRoot() throws Exception {
        // WHY（批次Y Q3 意图）：完成通知 XML 的 <output-file> 是模型经 TaskOutput 读输出的指示。
        //   CC RemoteAgentTask.tsx:171 getTaskOutputPath(taskId) 拼 <output-file>（temp 唯一根）。
        //   Java enqueueRemoteNotification 用 taskOutputDirSupplier.resolve(taskId+".output")（:676）
        //   拼 <output-file> —— 断言引用新唯一根，且不引用项目目录根（sessionDir=tempDir 注入）。
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        // 下一 tick 返回 archived → completed + 通知
        api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e1", "archived", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        String xml = items.get(0).value();
        Path expectedOutput = tempDir.resolve("output").resolve(reg.taskId() + ".output");
        assertThat(xml).as("完成通知 <output-file> 引用新唯一根").contains("<output-file>" + expectedOutput + "</output-file>");
        assertThat(xml).as("完成通知不得引用项目目录根").doesNotContain("<output-file>" + tempDir.resolve(reg.taskId() + ".output") + "</output-file>");
        await(1000, () -> RemoteAgentMetadataStore.read(tempDir, reg.taskId()) == null);
    }

    /**
     * WHY（cron-notify · 规则九）：后台任务通知应带<b>本地创建会话</b> sessionId → drain 3a 注入
     * 创建会话回合（用户拍板「该会话构建的后台任务要能通知到对应会话循环」）。registerRemoteAgentTask
     * 的 {@code RegisterOptions.sessionId} 是 <b>CCR remote 会话</b>（API 轮询用），<b>不是</b>本地
     * 创建会话 —— 两者 canonicalUuid 永不相等，拿 remote id 冒充创建会话会让 drainForQuery 永不命中
     * 创建会话回合、空闲时 CronIdleExecutor 以 remote id 派生 UUID 起 phantom 会话 loop。本测试锁死：
     * 通知携带的是独立捕获的本地创建会话（creatingSessionId），且该会话的 turn 能捞到通知（路由验证）。
     */
    @Test
    @DisplayName("cron-notify: remote 任务完成通知带本地创建会话（非 remote CCR sessionId）+ 路由到创建会话回合")
    void terminalNotification_carriesCreatingSession() throws Exception {
        String remoteCcrSession = "cse-rem-remote1";
        String localCreateSession = "sess-rem-local1";
        // 第 10 参 creatingSessionId = 本地创建会话（生产 caller 从 ctx.sessionId() 透传）；
        // RegisterOptions.sessionId 仍为 remote CCR（API 轮询用）。
        RemoteAgentTaskService.RegisterOptions opts =
            new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.REMOTE_AGENT,
                remoteCcrSession, "部署", "claude -p 'do it'", "tool-1", null, null, null, null, localCreateSession);
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(opts);
        // 下一 tick 返回 archived → completed + 通知（对齐 pollTerminalNotification 路径）
        api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e1", "archived", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        // 路由验证：本地创建会话的 turn（drainForQuery）必须捞到完成通知（3a canonicalUuid 匹配）
        List<NotificationQueue.QueueItem> drained =
            notifications.drainForQuery(true, null, localCreateSession);
        NotificationQueue.QueueItem item = drained.stream()
            .filter(i -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode()))
            .findFirst().orElse(null);
        assertThat(item).as("本地创建会话 turn 必须捞到 remote 完成通知（drain 3a 路由）").isNotNull();
        // 通知携带的是<b>本地创建会话</b>（不是 remote CCR sessionId）
        assertThat(com.nexusai.common.SessionKeys.canonicalUuid(item.sessionId()))
            .as("remote 完成通知必须携带本地创建会话 creatingSessionId（而非 remote CCR sessionId）")
            .isEqualTo(com.nexusai.common.SessionKeys.canonicalUuid(localCreateSession));
        assertThat(item.sessionId())
            .as("通知 sessionId 不得是 remote CCR sessionId（语义错位回归）")
            .isNotEqualTo(remoteCcrSession);
    }

    @Test
    @DisplayName("register: r 前缀 taskId + registerTask + sidecar 写入 + poll 启动（CC :415-465）")
    void registerGeneratesTaskIdRegistersPersistsStartsPolling() throws Exception {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        try {
            // r 前缀（Task.ts:79-87 remote_agent:'r'）
            assertThat(reg.taskId()).startsWith("r");
            assertThat(reg.taskId()).hasSize(9);
            assertThat(reg.sessionId()).isEqualTo("sess-1");
            // registerTask 入 framework store，status=running（:425/:437）
            assertThat(framework.getTask(reg.taskId())).isPresent();
            assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
            // sidecar 写入（:442-454）
            assertThat(RemoteAgentMetadataStore.getRemoteAgentMetadataPath(tempDir, reg.taskId())).exists();
            // poll 已启动（:460）— stub 被调用
            await(1000, () -> api.pollCalls >= 1);
            assertThat(api.pollCalls).isGreaterThanOrEqualTo(1);
        } finally {
            reg.cleanup().run();
        }
    }

    // ── 恢复 ──

    private void writeSidecar(String taskId, String sessionId, String remoteTaskType) {
        RemoteAgentMetadata meta = new RemoteAgentMetadata(taskId, remoteTaskType, sessionId,
            "部署", "claude -p 'x'", 5000L, "tool-1", null, null, null, null, null);
        RemoteAgentMetadataStore.write(tempDir, meta);
    }

    @Test
    @DisplayName("restore: fetchSession 404 → 删 sidecar（CC :498-500）")
    void restoreDropsSidecarOn404() {
        writeSidecar("rdead0001", "sess-404", "remote-agent");
        api.fetchError = new RemoteSessionsApi.SessionNotFoundException("sess-404");

        service.restoreRemoteAgentTasks();

        assertThat(RemoteAgentMetadataStore.read(tempDir, "rdead0001")).isNull();
        assertThat(framework.getTask("rdead0001")).isEmpty();
    }

    @Test
    @DisplayName("restore: 401 可恢复 → 保留 sidecar 跳过（CC :501-503）")
    void restoreKeepsSidecarOnRecoverableError() {
        writeSidecar("rkeep0001", "sess-401", "remote-agent");
        api.fetchError = new RemoteSessionsApi.SessionExpiredException();

        service.restoreRemoteAgentTasks();

        // 401 是登录可恢复错误 — 远程会话仍在运行，不删 sidecar 不复活任务
        assertThat(RemoteAgentMetadataStore.read(tempDir, "rkeep0001")).isNotNull();
        assertThat(framework.getTask("rkeep0001")).isEmpty();
    }

    @Test
    @DisplayName("restore: archived → 删 sidecar 不复活（CC :506-509）")
    void restoreDropsArchivedSession() {
        writeSidecar("rarch0001", "sess-arch", "remote-agent");
        api.fetchResult = new RemoteSessionsApi.SessionResource("sess-arch", "archived", Map.of());

        service.restoreRemoteAgentTasks();

        assertThat(RemoteAgentMetadataStore.read(tempDir, "rarch0001")).isNull();
        assertThat(framework.getTask("rarch0001")).isEmpty();
    }

    @Test
    @DisplayName("restore: running → 重建 state + 恢复轮询（CC :511-530）")
    void restoreRebuildsRunningTask() throws Exception {
        writeSidecar("rrun00001", "sess-live", "remote-agent");
        api.fetchResult = new RemoteSessionsApi.SessionResource("sess-live", "running", Map.of());
        // 保留默认 poll（空增量 + 不终止）→ 轮询持续
        api.defaultPoll = RemoteSessionsApi.PollResult.eventsOnly(List.of(), null);

        service.restoreRemoteAgentTasks();

        var task = framework.getTask("rrun00001");
        assertThat(task).isPresent();
        // 重建 startTime = spawnedAt（:524）
        assertThat(task.get().startTime()).isEqualTo(5000L);
        assertThat(task.get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        // sidecar 保留
        assertThat(RemoteAgentMetadataStore.read(tempDir, "rrun00001")).isNotNull();
        // 恢复轮询已启动
        await(1000, () -> api.pollCalls >= 1);
        assertThat(api.pollCalls).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("restore: 脏 remoteTaskType 回退 remote-agent（CC :514）")
    void restoreFallsBackRemoteTypeOnDirtyValue() {
        writeSidecar("rdirt0001", "sess-dirt", "bogus-type");
        api.fetchResult = new RemoteSessionsApi.SessionResource("sess-dirt", "running", Map.of());
        api.defaultPoll = RemoteSessionsApi.PollResult.eventsOnly(List.of(), null);

        service.restoreRemoteAgentTasks();

        assertThat(framework.getTask("rdirt0001")).isPresent();
    }

    // ── 轮询 ──

    @Test
    @DisplayName("poll: sessionStatus=archived → completed + 通知 + evict + 删 sidecar（CC :579-589）")
    void pollArchivedCompletesAndNotifies() throws Exception {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        // 下一 tick 返回 archived
        api.pollQueue.add(RemoteSessionsApi.PollResult.eventsOnly(List.of(), "e1"));
        api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e2", "archived", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        // 通知入队 + 文案对齐 CC :177 Remote task "..." completed successfully
        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).value()).contains("Remote task \"部署\" completed successfully");
        assertThat(items.get(0).value()).contains("<task-type>remote_agent</task-type>");
        // sidecar 删除（:587）— delete 为 poll 线程最后一步，await 避免时序竞争
        await(1000, () -> RemoteAgentMetadataStore.read(tempDir, reg.taskId()) == null);
        // 轮询停止
        int calls = api.pollCalls;
        await(300, () -> api.pollCalls == calls);
    }

    @Test
    @DisplayName("poll: result(subtype 非 success) → failed + 通知（CC :687/:724）")
    void pollResultFailureMarksFailed() throws Exception {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(resultEvent("error")), "e1", "idle", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.FAILED;
        });
        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).value()).contains("<status>failed</status>");
        await(1000, () -> RemoteAgentMetadataStore.read(tempDir, reg.taskId()) == null);
    }

    @Test
    @DisplayName("poll: result(success) → completed（CC :687/:724）")
    void pollResultSuccessCompletes() throws Exception {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(resultEvent("success")), "e1", "idle", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        assertThat(framework.getTask(reg.taskId()).get().endTime()).isNotNull();
    }

    @Test
    @DisplayName("poll: remote-review 任务 stableIdle 5 连 idle + assistant 输出（无 SessionStart hook）→ completed（CC :661-666/:685-687）")
    void pollStableIdleCompletesForReviewTask() throws Exception {
        // WHY: CC :685 sessionDone = isRemoteReview && (cachedReviewContent != null ||
        //      !hasSessionStartHook && stableIdle && hasAssistantEvents)。stableIdle 判完成只对
        //      isRemoteReview 任务生效 —— review 无 tag 时由 extractReviewFromLog 全量扫描兜底
        //      （CC :736），故纯 assistant 文本 + 5 连 idle → completed 且通知注入 review 文本。
        //      原 FIND-1 测试用非 review 任务断言 COMPLETED 是假绿（await 静默超时 + 文件断言
        //      与状态无关）；此正向用例锁死 review 稳定完成路径。
        var opts = new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.REMOTE_AGENT,
            "sess-1", "代码审查", "claude -p 'review'", "tool-1", Boolean.TRUE, null, null, null, null);
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(opts);
        // 先来一条 assistant 输出（hasAssistantEvents=true，无 SessionStart hook），然后连续 idle
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(assistantEvent("working...")), "e1", "idle", null));
        for (int i = 0; i < 6; i++) {
            api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e" + (2 + i), "idle", null));
        }

        await(5000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        var t = framework.getTask(reg.taskId()).get();
        assertThat(t.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(t.endTime()).isNotNull();
        // review 完成通知注入 review 文本（extractReviewFromLog 全量兜底 = assistant 文本，CC :736-742）
        await(1000, () -> notifications.peek(i ->
            NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode())).isPresent());
        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        String xml = items.get(0).value();
        assertThat(xml).contains("<summary>Remote review completed</summary>");
        assertThat(xml).contains("working...");
        await(1000, () -> RemoteAgentMetadataStore.read(tempDir, reg.taskId()) == null);
    }

    @Test
    @DisplayName("poll: 非 review 任务 stableIdle 不完成（CC :685 isRemoteReview 门控 · 负向）")
    void pollStableIdleKeepsNonReviewTaskRunning() throws Exception {
        // WHY: 同一组输入（assistant 输出 + 6 连 idle）作用于非 review 任务 —— sessionDone
        //      被 isRemoteReview 门控置 false，任务保持 RUNNING。若实现误删 isRemoteReview 门控
        //      （或误用 stableIdle 判完成），本测试立即变红 —— 直接守护 CC :685 语义。
        //      同时确认 delta 落盘独立于状态机（输出文件仍写入 "working..."）。
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(assistantEvent("working...")), "e1", "idle", null));
        for (int i = 0; i < 6; i++) {
            api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e" + (2 + i), "idle", null));
        }
        // 等全部 idle 增量消费完（1 assistant + 6 idle = 7 次 poll）
        await(5000, () -> api.pollCalls >= 7);
        // 状态仍 RUNNING —— stableIdle 不完成非 review 任务
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        // 输出仍落盘（appendTaskOutput, CC :576 —— 与状态机完成判定无关）
        Path out = tempDir.resolve("output").resolve(reg.taskId() + ".output");
        assertThat(out).exists();
        String content = new String(java.nio.file.Files.readAllBytes(out));
        assertThat(content).contains("working...");
        // 继续轮询仍不完成（无 result/archived 事件）
        int calls = api.pollCalls;
        await(500, () -> api.pollCalls > calls);
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        // 无完成通知（markTaskNotified 未被触发）
        assertThat(drainTaskNotifications()).isEmpty();
    }

    @Test
    @DisplayName("poll: isLongRunning 跳过 result 判完成，继续轮询（CC :610）")
    void pollSkipsResultForLongRunning() throws Exception {
        var opts = new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.REMOTE_AGENT,
            "sess-1", "监控", "claude -p 'watch'", "tool-1", null, null, Boolean.TRUE, null, null);
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(opts);
        // 返回 result(success) — isLongRunning 应跳过，不完成
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(resultEvent("success")), "e1", "idle", null));

        await(500, () -> api.pollCalls >= 2); // 至少又 poll 了一轮
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
    }

    @Test
    @DisplayName("poll: completionChecker 非 null 结果 → completed + 通知文本=checker 返回（CC :590-604）")
    void pollCompletionCheckerCompletesWithCheckerText() throws Exception {
        // WHY: completionCheckers 注册表（:78）按 remoteTaskType 分发 — autofix-pr 这类
        //      "外部状态驱动完成"的任务（如 PR 已合并）依赖每 poll tick 调用 checker，
        //      checker 返回非 null 即完成且通知文本=返回串（:600-604）。不接通则
        //      autofix-pr 永不完成。
        // 用独立类型避免污染其它测试（COMPLETION_CHECKERS 为静态注册表）
        RemoteAgentTaskService.registerCompletionChecker(RemoteTaskType.AUTOFIX_PR, meta ->
            Boolean.TRUE.equals(meta.get("merged")) ? "PR 已合并" : null);
        try {
            var opts = new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.AUTOFIX_PR,
                "sess-1", "PR 任务", "claude -p 'fix'", "tool-1", null, null, null,
                new java.util.HashMap<>(Map.of("owner", "acme", "repo", "repo1", "prNumber", 7)), null);
            RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(opts);
            // tick1: merged=false → null 继续轮询；tick2: merged=true → 完成
            api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e1", "idle", null));
            api.pollQueue.add(new RemoteSessionsApi.PollResult(List.of(), "e2", "idle", null));
            // 在第二次 poll 前翻转 metadata 的 merged 标志
            await(1000, () -> {
                var state = service.findTask(reg.taskId());
                if (state == null) return false;
                state.remoteTaskMetadata().put("merged", true);
                return true;
            });

            await(3000, () -> {
                var t = framework.getTask(reg.taskId());
                return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
            });
            // 通知文本 = checker 返回值（CC :602 enqueueRemoteNotification(taskId, completionResult,...)）
            List<NotificationQueue.QueueItem> items = drainTaskNotifications();
            assertThat(items).isNotEmpty();
            assertThat(items.get(0).value()).contains("PR 已合并");
        } finally {
            // 清理注册表避免污染其它测试（CC :84 set 覆盖语义）
            RemoteAgentTaskService.registerCompletionChecker(RemoteTaskType.AUTOFIX_PR, meta -> null);
        }
    }

    // ── kill ──

    @Test
    @DisplayName("kill: only-if-running → KILLED + SDK stopped + archive + 删 sidecar（CC :811-847）")
    void killArchivesAndEmitsStopped() throws Exception {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        await(300, () -> api.pollCalls >= 1);

        boolean killed = service.kill(reg.taskId());

        assertThat(killed).isTrue();
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(framework.getTask(reg.taskId()).get().notified()).isTrue();
        assertThat(framework.getTask(reg.taskId()).get().endTime()).isNotNull();
        // archive fire-and-forget（:840-842）
        assertThat(api.archiveCalled).isTrue();
        assertThat(api.archivedSession).isEqualTo("sess-1");
        // SDK task_notification stopped（:835-838）
        SdkEventQueue.TaskNotificationEvent evt = firstTerminated();
        assertThat(evt).isNotNull();
        assertThat(evt.status()).isEqualTo("stopped");
        // sidecar 删除（:845）
        assertThat(RemoteAgentMetadataStore.read(tempDir, reg.taskId())).isNull();
    }

    @Test
    @DisplayName("kill: 非 running → false，无副作用（CC :817-819）")
    void killIdempotentOnNonRunning() {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        service.kill(reg.taskId());

        boolean again = service.kill(reg.taskId());
        assertThat(again).isFalse();
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.KILLED);
    }

    @Test
    @DisplayName("race 守卫: poll in-flight 时 kill → 终态不被覆盖 + 不双发通知（CC :693-720）")
    void raceGuardKillDuringPoll() throws Exception {
        // 让 poll 第一次返回一条 assistant 增量（触发 logGrew 状态更新），随后返回 result(success)
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(assistantEvent("hi")), "e1", "idle", null));
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        // 等第一次 poll 消费增量
        await(300, () -> api.pollQueue.isEmpty());
        // 立即 kill — 后续 poll 返回 result(success) 不应把 KILLED 覆盖成 COMPLETED
        service.kill(reg.taskId());
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(resultEvent("success")), "e9", "idle", null));

        await(500, () -> api.pollCalls >= 3);
        assertThat(framework.getTask(reg.taskId()).get().status()).isEqualTo(BackgroundTaskStatus.KILLED);
        // 仅 kill 发一条 SDK stopped；poll 不得再补 completed 通知
        SdkEventQueue.TaskNotificationEvent evt = firstTerminated();
        assertThat(evt).isNotNull();
        assertThat(evt.status()).isEqualTo("stopped");
    }

    // ── M-10 通知三变体 ──

    @Test
    @DisplayName("remote-review 完成：注入 review 文本通知，不引用 output-file（CC :738 enqueueRemoteReviewNotification）")
    void pollRemoteReviewCompleted_injectsReviewTextNotification() throws Exception {
        // WHY: bughunter 路径 review 由 hook_progress stdout 的 <remote-review> tag 触发完成。
        //   CC :738 走 enqueueRemoteReviewNotification —— 直接注入 findings 文本（非 output-file
        //   间接），summary 固定 "Remote review completed"。若误走 enqueueRemoteNotification 则
        //   会带 <output-file> 引用 + "Remote task ..." 文案 —— 两条路径可观测区分。
        var opts = new RemoteAgentTaskService.RegisterOptions(RemoteTaskType.REMOTE_AGENT,
            "sess-1", "代码审查", "claude -p 'hunt'", "tool-1", Boolean.TRUE, null, null, null, null);
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(opts);
        // bughunter：SessionStart hook 的 echo 落 hook_progress；tag 出现在运行末尾
        api.pollQueue.add(new RemoteSessionsApi.PollResult(
            List.of(Map.of("type", "system", "subtype", "hook_progress", "hook_event", "SessionStart",
                "stdout", "<remote-review>{\"finding\":\"1 个 bug\"}</remote-review>")),
            "e1", "idle", null));

        await(3000, () -> {
            var t = framework.getTask(reg.taskId());
            return t.isPresent() && t.get().status() == BackgroundTaskStatus.COMPLETED;
        });
        // 状态先落、通知后入队（同 tick 末尾）—— await 通知非空避免时序竞争（peek 非破坏）
        await(1000, () -> notifications.peek(i ->
            NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode())).isPresent());
        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        String xml = items.get(0).value();
        // extractTag 返回 tag 内部内容（CC :261 tagged.trim() 同语义，不带 <remote-review> 标签）
        assertThat(xml).as("review 完成通知应注入 findings 文本（extractTag 内容）").contains("{\"finding\":\"1 个 bug\"}");
        assertThat(xml).as("review 完成通知 summary 固定").contains("<summary>Remote review completed</summary>");
        assertThat(xml).as("review 完成通知不应引用 output-file（CC 明确无文件间接）").doesNotContain("<output-file>");
        assertThat(xml).as("review 完成通知不应带 'Remote task' 文案（非 enqueueRemoteNotification）").doesNotContain("Remote task \"");
        await(1000, () -> RemoteAgentMetadataStore.read(tempDir, reg.taskId()) == null);
    }

    @Test
    @DisplayName("ultraplan 失败通知：Ultraplan failed 文案 + session URL（CC :225-239）")
    void ultraplanFailureNotification_hasSessionUrl() {
        // WHY: ultraplan 失败告知模型"无 plan 产出 + 访问 session URL + 本地 plan mode 重试"，
        //   不指示读 output-file（JSONL dump 对 plan 提取无用）。URL = claude.ai/code/{sessionId}。
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "规划"));
        service.enqueueUltraplanFailureNotification(reg.taskId(), "sess-1", "exit code 2");

        List<NotificationQueue.QueueItem> items = drainTaskNotifications();
        assertThat(items).isNotEmpty();
        String xml = items.get(0).value();
        assertThat(xml).contains("<summary>Ultraplan failed: exit code 2</summary>");
        assertThat(xml).contains("did not produce a plan (exit code 2)");
        assertThat(xml).contains("https://claude.ai/code/sess-1");
        assertThat(xml).contains("retry locally with plan mode");
        assertThat(xml).doesNotContain("<output-file>");
    }

    @Test
    @DisplayName("findTask：running/终态可见性（M-9 stopTask 分发查询）")
    void findTaskExposesStateForStopDispatch() {
        RemoteAgentTaskService.RegisteredRemoteTask reg = service.registerRemoteAgentTask(options("sess-1", "部署"));
        assertThat(service.findTask(reg.taskId())).isNotNull();
        assertThat(service.findTask(reg.taskId()).status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        service.kill(reg.taskId());
        assertThat(service.findTask(reg.taskId()).status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(service.findTask("no-such")).isNull();
    }
}
