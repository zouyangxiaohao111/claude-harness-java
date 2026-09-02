package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF5-03 主会话后台化派生查询（startBackgroundSession + symlink 隔离）· 意图验证。
 *
 * <p><b>WHY（规则九）</b>:
 * <ul>
 *   <li><b>'s' 前缀 + symlink 隔离</b>（OPD-TP-13/17）——CC 用独立 taskId + 按 task 隔离 transcript
 *       （LocalMainSessionTask.ts:75-82 + :107-110），使后台任务输出不写主会话 transcript
 *       （/clear 后损坏防护）；若 Java 侧复用主查询 topic/transcript，后台与前台会串流/互污染。</li>
 *   <li><b>runWithAgentContext 隔离</b>（CC :368-375）——后台查询的 agent 上下文必须是 taskId 绑定的
 *       SubagentContext，否则 skill 作用域归到主线程（agentId=null）。</li>
 *   <li><b>任务级 streamTopic</b>（w5-01 隔离设计 1）——后台派生查询必须走 {@code /topic/tasks/{taskId}/stream}，
 *       不复用 {@code /topic/sessions/{S}/stream}（会话级单 topic），否则与前台同 topic 串流。</li>
 *   <li><b>abort 中断</b>（CC :387-401）——中断后置 notified + emitTaskTerminatedSdk('stopped')，
 *       否则 stopTask/chat:killAgents 路径会挂死（无 bookend）。</li>
 * </ul>
 *
 * <p>RED 证据（实施前）：grep 全仓 {@code setTaskStreamContext} / {@code MainSessionBackgroundService}
 * 零命中（baseline §2.1 main-session 概念全缺）；本测试编译即失败。
 */
@DisplayName("[WF5-03] startBackgroundSession 独立派生 + symlink 隔离")
class MainSessionBackgroundServiceTest {

    @TempDir
    Path tempDir;

    private SdkEventQueue sdkEventQueue;
    private TaskFrameworkService taskFrameworkService;
    private MainSessionBackgroundService service;
    private ObjectProvider<LlmAgentLoop> loopProvider;
    private LlmAgentLoop loop;
    private NotificationQueue notificationQueue;

    @BeforeEach
    void setUp() {
        sdkEventQueue = new SdkEventQueue();
        taskFrameworkService = new TaskFrameworkService(sdkEventQueue);
        notificationQueue = new NotificationQueue();
        service = new MainSessionBackgroundService();
        loopProvider = mock(ObjectProvider.class);
        loop = mock(LlmAgentLoop.class);
        when(loopProvider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(service, "taskFrameworkService", taskFrameworkService);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        ReflectionTestUtils.setField(service, "sdkEventQueue", sdkEventQueue);
        ReflectionTestUtils.setField(service, "notificationQueue", notificationQueue);
        // F-2 返工：startBackgroundSession 经 backgroundExecutor（生产 = chatExecutor）fire-and-forget 派发查询。
        //   同步执行器（Runnable::run）让既有意图测试保持确定性 —— loop.run 在调用线程内同步执行。
        ReflectionTestUtils.setField(service, "backgroundExecutor", (Executor) Runnable::run);
    }

    @Test
    @DisplayName("generateMainSessionId：'s' 前缀 + 9 字符（CC :75-82）")
    void generateMainSessionTaskId_hasPrefixAndLength() {
        // WHY: CC 用 's' 前缀区分 main-session 任务（Task.ts 无 's' 枚举）；若复用 'a' 前缀会被
        //   AgentTool 的 taskId===agentId 语义误判为 subagent 任务。单一事实源 = MainSessionTaskState
        //   （服务端 generateMainSessionTaskId 已收敛删除）。
        String id = MainSessionTaskState.generateMainSessionId();
        assertThat(id).startsWith("s").hasSize(9);
        assertThat(id.substring(1)).matches("[0-9a-z]{8}");
    }

    @Test
    @DisplayName("initTaskOutputAsSymlink：symlink 指向 per-task transcript（失败 fallback 普通文件）")
    void initTaskOutputAsSymlink_pointsToTranscript() throws Exception {
        // WHY: OPD-TP-17 真 symlink——后台输出必须链接到隔离 transcript 而不是普通副本，
        //   否则 /clear 重链 symlink 后旧输出路径失效（diskOutput.ts:427-451 unlink 重试语义）。
        String taskId = MainSessionTaskState.generateMainSessionId();
        Path sessionDir = tempDir;
        Path target = sessionDir.resolve("sess-x").resolve("subagents").resolve("agent-" + taskId + ".jsonl");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{}");

        String output = service.initTaskOutputAsSymlink(taskId, sessionDir, "sess-x", target);

        Path outputPath = Path.of(output);
        assertThat(outputPath).exists();
        if (Files.isSymbolicLink(outputPath)) {
            assertThat(Files.readSymbolicLink(outputPath)).isEqualTo(target);
        }
        // Windows/无权限时 fallback 普通文件：仍可读（CC diskOutput.ts:445-447 initTaskOutput）
        assertThat(Files.isReadable(outputPath)).isTrue();
    }

    @Test
    @DisplayName("initTaskOutputAsSymlink：RuntimeException（无效 target）必须 fallback 普通文件而非抛出（CC diskOutput.ts:445-447 catch-all）")
    void initTaskOutputAsSymlink_runtimeFailure_fallsBackToReadableFile() {
        // WHY: CC diskOutput.ts:445-447 外层裸 catch(error) → initTaskOutput fallback ——
        //   symlink 隔离是尽力而为（OPD-TP-17），输出初始化绝不能使 registerMainSessionTask 失败；
        //   若 Java 端仅 catch IOException|UnsupportedOperationException，无效 target（null → NPE）
        //   会 RuntimeException 冒出 → 注册链路崩溃，偏离 CC catch-all 语义。
        String taskId = MainSessionTaskState.generateMainSessionId();
        Path sessionDir = tempDir;

        String output = service.initTaskOutputAsSymlink(taskId, sessionDir, "sess-x", null);

        // 返回可读空文件（CC initTaskOutput 'wx' O_EXCL 等价：新建，不覆盖已存在）
        assertThat(Path.of(output)).exists().isReadable();
    }

    @Test
    @DisplayName("initTaskOutputAsSymlink：输出路径被占用 → unlink 重试或 fallback，注册不被阻断（CC diskOutput.ts:440-447）")
    void initTaskOutputAsSymlink_occupiedPath_unlinksOrFallsBack() throws Exception {
        // WHY: CC diskOutput.ts:440-442 symlink 抛错 → unlink(outputPath) 重试 —— 输出路径已被
        //   占用（EEXIST）时不得让注册失败；重试再失败 → initTaskOutput fallback（:445-447）。
        //   无论结果（重试成功=重新 symlink / fallback=新空文件），旧内容必须被清除，输出可读。
        String taskId = MainSessionTaskState.generateMainSessionId();
        Path sessionDir = tempDir;
        Path outputDir = sessionDir.resolve("sess-x").resolve("tasks");
        Files.createDirectories(outputDir);
        // 预置普通文件占住输出路径（模拟被占用），并预建真实 transcript 目标
        Path outputPath = outputDir.resolve(taskId + ".output");
        Files.writeString(outputPath, "SENTINEL");
        Path target = sessionDir.resolve("sess-x").resolve("subagents").resolve("agent-" + taskId + ".jsonl");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{}");

        String output = service.initTaskOutputAsSymlink(taskId, sessionDir, "sess-x", target);

        Path out = Path.of(output);
        assertThat(out).exists().isReadable();
        // 旧 SENTINEL 内容必须消失（unlink 重试已清理；O_EXCL 语义下不会被截断残留）
        if (Files.isSymbolicLink(out)) {
            assertThat(Files.readSymbolicLink(out)).isEqualTo(target);
        } else {
            assertThat(Files.readString(out)).isEmpty();
        }
    }

    @Test
    @DisplayName("registerMainSessionTask：'s' 前缀任务入 store + output symlink + task_started SDK")
    void registerMainSessionTask_registersTaskAndEmitsStarted() throws Exception {
        // WHY: 后台任务必须注册进统一 store（TaskFrameworkService）并自动发 task_started SDK
        //   （framework.ts:77-117 registerTask 语义），否则 TaskStop/轮询看不到它。
        String taskId = service.registerMainSessionTask("background query", "sess-x", null);

        assertThat(taskId).startsWith("s").hasSize(9);
        var registered = taskFrameworkService.getTask(taskId);
        assertThat(registered).isPresent();
        assertThat(registered.get().isBackgrounded()).isTrue();
        assertThat(registered.get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        // task_started SDK 入队
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        assertThat(drained).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskStartedEvent se
            && taskId.equals(se.taskId()));
    }

    @Test
    @DisplayName("registerMainSessionTask：MainSessionTaskState 独立载体全字段（OPD-TP-16）+ task_started 带 prompt")
    void registerMainSessionTask_usesMainSessionTaskStateCarrier() {
        // WHY: OPD-TP-16 拍板"独立载体、不污染基础 BackgroundTask"——注册必须产生完整
        //   MainSessionTaskState（agentType/prompt/pendingMessages/retain/diskLoaded），
        //   而非仅投影 BackgroundTask（否则 messages/progress 面板缺失、isMainSessionTask
        //   只能靠 's' 前缀猜）。CC LocalMainSessionTask.ts:128-145 taskState 全字段初始化。
        String taskId = service.registerMainSessionTask("background query", "sess-x", null);

        var carrier = taskFrameworkService.getMainSessionTask(taskId);
        assertThat(carrier).isPresent();
        MainSessionTaskState s = carrier.get();
        assertThat(s.type()).isEqualTo(TaskType.LOCAL_AGENT);           // CC :130 type:'local_agent'
        assertThat(s.status()).isEqualTo(BackgroundTaskStatus.RUNNING); // CC :131 status:'running'
        assertThat(s.agentId()).isEqualTo(taskId);                      // CC :132 agentId=taskId
        assertThat(s.prompt()).isEqualTo("background query");           // CC :133 prompt=description
        assertThat(s.agentType()).isEqualTo(MainSessionTaskState.AGENT_TYPE_MAIN_SESSION); // CC :56
        assertThat(s.isBackgrounded()).isTrue();                        // CC :141 isBackgrounded:true
        assertThat(s.pendingMessages()).isEmpty();                      // CC :142 pendingMessages:[]
        assertThat(s.retain()).isFalse();                               // CC :143 retain:false
        assertThat(s.diskLoaded()).isFalse();                           // CC :144 diskLoaded:false
        assertThat(s.retrieved()).isFalse();                            // CC :136 retrieved:false
        // task_started SDK 带 prompt（CC framework.ts:116 'prompt' in task → task.prompt）
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        assertThat(drained).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskStartedEvent se
            && taskId.equals(se.taskId()) && "background query".equals(se.prompt()));
    }

    @Test
    @DisplayName("startBackgroundSession：runWithAgentContext 隔离 + 任务级 streamTopic + 独立 loop.run")
    void startBackgroundSession_runsLoopWithTaskContextAndTopic() {
        // WHY: CC :368-375 runWithAgentContext(SubagentContext{agentId:taskId, subagentName:'main-session'})
        //   隔离 skill 作用域；:107-110 任务级 topic/transcript 防与前台串流。
        final AgentContext[] seenContext = new AgentContext[1];
        doAnswer(inv -> {
            seenContext[0] = AgentContext.getAgentContext();
            return null;
        }).when(loop).run(any());

        String taskId = service.startBackgroundSession(
            "sess-x", "bg", List.of(Map.of("role", "user", "content", "hi")),
            null, "hi", "mock-fast", ProviderConfig.empty(), null);

        // loop 在 SubagentContext（agentId=taskId, subagentName=main-session）内执行
        assertThat(seenContext[0]).isInstanceOf(AgentContext.SubagentContext.class);
        AgentContext.SubagentContext sc = (AgentContext.SubagentContext) seenContext[0];
        assertThat(sc.agentId()).isEqualTo(taskId);
        assertThat(sc.subagentName()).isEqualTo("main-session");
        assertThat(sc.isBuiltIn()).isTrue();
        // 任务级 streamTopic 注入（w5-01 隔离设计 1：/topic/tasks/{taskId}/stream）
        // [IMP-A · F6] 三参签名：真实 sessionId 透传（后台 loop 解析会话 projectRoot 用）
        verify(loop).setTaskStreamContext(eq(null), eq(taskId), eq("sess-x"));
        verify(loop).run(any());
    }

    @Test
    @DisplayName("startBackgroundSession：立即返回 taskId（fire-and-forget，查询异步派发）")
    void startBackgroundSession_returnsBeforeQueryRuns() {
        // WHY: CC LocalMainSessionTask.ts:375 void runWithAgentContext + :478 return taskId ——
        //   startBackgroundSession 必须在查询开始前返回 taskId（fire-and-forget）。若同步阻塞，
        //   HTTP 线程被整个后台查询占住，前端拿不到 taskId 订阅 /topic/tasks/{taskId}/stream，
        //   且 HTTP 超时会孤儿化查询（WF5 返工 F-2 阻断缺陷）。
        AtomicReference<Runnable> pending = new AtomicReference<>();
        // lazy executor：提交即捕获、不执行 —— 证明方法返回时查询尚未运行
        ReflectionTestUtils.setField(service, "backgroundExecutor",
            (Executor) pending::set);

        String taskId = service.startBackgroundSession(
            "sess-x", "bg", List.of(Map.of("role", "user", "content", "hi")),
            null, "hi", "mock-fast", ProviderConfig.empty(), null);

        // 方法已返回 taskId，查询已派发但未执行（fire-and-forget）
        assertThat(taskId).startsWith("s").hasSize(9);
        assertThat(pending.get()).isNotNull();
        verify(loop, never()).run(any());
    }

    @Test
    @DisplayName("abort 中断：置 notified + emitTaskTerminatedSdk('stopped')（CC :387-401）")
    void startBackgroundSession_abortEmitsStoppedAndNotifies() {
        // WHY: abortSignal.aborted 时 CC 短路 —— 置 notified + emitTaskTerminatedSdk('stopped')
        //   （chat:killAgents 路径已 notified 则不重发；stopTask 路径未 notified 必须发 bookend）。
        when(loop.run(any())).thenReturn(null);

        AtomicBoolean abortFlag = new AtomicBoolean(true);
        String taskId = service.startBackgroundSession(
            "sess-x", "bg", List.of(), null, "hi", "mock-fast",
            ProviderConfig.empty(), abortFlag);

        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        assertThat(drained).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskNotificationEvent se
            && taskId.equals(se.taskId()) && "stopped".equals(se.status()));
        // 任务已标记 notified（evictTerminalTask 三闸之一）
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::notified).isEqualTo(true);
    }

    @Test
    @DisplayName("abort 时已 notified（chat:killAgents 先发）→ 不再重复 emitTaskTerminatedSdk('stopped')（CC :391-399 CAS）")
    void startBackgroundSession_abortAlreadyNotified_skipsDuplicateEmit() {
        // WHY: CC :391-399 原子 check-and-set —— alreadyNotified=true（chat:killAgents 路径已
        //   notified+emitted）时不重发 bookend；只有 stopTask 路径（未 notified）才发。
        //   若 Java 无条件 emit，则已 notified 任务会双发 'stopped'，前端收到重复 bookend。
        when(loop.run(any())).thenReturn(null);

        // 先注册任务并手工置 notified=true（模拟 chat:killAgents 先 emitted + notified）
        String taskId = service.registerMainSessionTask("bg query", "sess-x", null);
        taskFrameworkService.getTask(taskId).ifPresent(t ->
            taskFrameworkService.updateTaskState(taskId, t.withNotified()));

        AtomicBoolean abortFlag = new AtomicBoolean(true);
        service.startBackgroundSession(
            "sess-x", "bg", List.of(), null, "hi", "mock-fast",
            ProviderConfig.empty(), abortFlag);

        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        long stoppedCount = drained.stream()
            .filter(e -> e.event() instanceof SdkEventQueue.TaskNotificationEvent se
                && taskId.equals(se.taskId()) && "stopped".equals(se.status()))
            .count();
        // 已 notified → 不发（或至多一次，绝无双发）。registerMainSessionTask 不发 task_terminated，
        // 故此处应恰为 0。
        assertThat(stoppedCount).isZero();
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::notified).isEqualTo(true);
    }

    // ── WF5-04 · completeMainSessionTask + 完成通知 + SDK bookend（OPD-TP-18/19）──

    @Test
    @DisplayName("completeMainSessionTask 后台化：XML 通知入队（'Background session' 5-6 TAG）+ SDK task_terminated + status=completed")
    void completeMainSessionTask_backgrounded_emitsXmlAndSdk() {
        // WHY: CC LocalMainSessionTask.ts:199-206 —— 后台化任务完成需发 XML 通知（模型消费）+
        //   OPD-TP-18 task_terminated SDK bookend（前端消费）。若漏发，模型看不到"Background session
        //   completed"，前端看不到 task_started 的 bookend 闭合（evict 守卫挂死）。
        String taskId = service.registerMainSessionTask("bg query", "sess-x", null);

        service.completeMainSessionTask(taskId, true);

        // status=completed + endTime 置位（CC :187-192）
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::status).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::endTime).isNotNull();
        // XML 通知入队（mode=task-notification，summary 含 'Background session "Background session" completed'
        //   —— CC :245-248 把字面量 'Background session' 作为 description 传给 enqueueMainSessionNotification）
        List<NotificationQueue.QueueItem> items = notificationQueue.dequeueAll();
        assertThat(items).anyMatch(i -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode())
            && i.value().contains("Background session \"Background session\" completed")
            && i.value().contains("<task-id>") && i.value().contains("<status>completed</status>"));
        // SDK task_terminated bookend（OPD-TP-18）
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        assertThat(drained).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskNotificationEvent se
            && taskId.equals(se.taskId()) && "completed".equals(se.status()));
    }

    @Test
    @DisplayName("completeMainSessionTask 已前台化：仅 SDK task_terminated，无 XML（CC :207-218）")
    void completeMainSessionTask_foregrounded_emitsSdkOnly() {
        // WHY: CC :207-218 —— 前台化任务用户在看，无 XML 通知；但 SDK 仍需 task_started bookend 闭合。
        String taskId = service.registerMainSessionTask("bg query", "sess-x", null);
        // 前台化 = isBackgrounded=false（用户观看中）
        taskFrameworkService.getTask(taskId).ifPresent(t ->
            taskFrameworkService.updateTaskState(taskId, t.withIsBackgrounded(false)));

        service.completeMainSessionTask(taskId, false);

        // 无 XML 通知
        assertThat(notificationQueue.dequeueAll()).isEmpty();
        // SDK task_terminated bookend（failed）
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents("sess-x");
        assertThat(drained).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskNotificationEvent se
            && taskId.equals(se.taskId()) && "failed".equals(se.status()));
        // 已标记 notified（evictTerminalTask 三闸之一，CC :213）
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::notified).isEqualTo(true);
    }

    @Test
    @DisplayName("completeMainSessionTask running 守卫：非 running 任务 no-op（CC :177-179）")
    void completeMainSessionTask_nonRunning_noop() {
        // WHY: CC :177-179 status!=='running' 直接返回 —— 已终态任务再完成会重复通知（双发）。
        String taskId = service.registerMainSessionTask("bg query", "sess-x", null);
        sdkEventQueue.drainSdkEvents("sess-x"); // 排空 registerTask 已发的 task_started
        taskFrameworkService.getTask(taskId).ifPresent(t ->
            taskFrameworkService.updateTaskState(taskId, t.withStatus(BackgroundTaskStatus.FAILED)));

        service.completeMainSessionTask(taskId, true);

        // 状态不被覆盖（保持 FAILED），无新通知
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::status).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(notificationQueue.dequeueAll()).isEmpty();
        assertThat(sdkEventQueue.drainSdkEvents("sess-x")).isEmpty();
    }

    @Test
    @DisplayName("enqueueMainSessionNotification CAS 防重：已通知再入队跳过（CC :231-243）")
    void enqueueMainSessionNotification_casPreventsDuplicate() {
        // WHY: CC :231-243 notified check-and-set 原子防重 —— 重复通知会污染模型上下文。
        String taskId = service.registerMainSessionTask("bg query", "sess-x", null);

        service.enqueueMainSessionNotification(taskId, "Background session", "completed", null);
        service.enqueueMainSessionNotification(taskId, "Background session", "completed", null);

        assertThat(notificationQueue.dequeueAll()).hasSize(1);
        assertThat(taskFrameworkService.getTask(taskId)).get()
            .extracting(BackgroundTask::notified).isEqualTo(true);
    }
}
