package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.RequestContext;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台任务运行器 — 对齐 CC LocalShellTask.tsx:180-249 spawnShellTask
 *
 * <p>管理 daemon 线程池中的后台任务执行。CC 模式:
 * <pre>
 * createTaskStateBase → registerTask → shellCommand.background(taskId)
 * → startStallWatchdog → promise.then(enqueueShellNotification)
 * </pre>
 */
public class BackgroundTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRunner.class);

    /** daemon 线程池 — L3 Java idiom: ExecutorService 替代 CC 自管线程 */
    private final ExecutorService executor;

    private final ConcurrentMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    /** s13-p2: 保存 LocalBashTaskRunner 引用 — 供 cancel() 时 destroyForcibly */
    private final ConcurrentMap<String, LocalBashTaskRunner> runnerMap = new ConcurrentHashMap<>();
    /**
     * G1-2: 前台 → 后台任务的 StallWatchdog 注册表 · 对齐 CC backgroundExistingForegroundTask 的
     * {@code cancelStallWatchdog}（LocalShellTask.tsx:442/:446）。前台任务转后台时启动 watchdog，
     * 完成 watcher 在进程结束后 {@code stop()} 并移除 — 防 leak（CC cleanup）。
     */
    private final ConcurrentMap<String, StallWatchdog> foregroundWatchdogs = new ConcurrentHashMap<>();
    /**
     * OPD-TP-21: 按 taskId 的 worker AbortController 注册表 · 对齐 CC taskState.abortController
     * (LocalAgentTask.tsx:123/486-495 registerAsyncAgent 创建并存 taskState)。
     * killAsyncAgent 经 {@code abort()} 直接中断 worker 查询循环（对齐 CC LocalAgentTask.tsx:288）。
     */
    private final ConcurrentMap<String, AbortController> taskAbortControllers = new ConcurrentHashMap<>();
    private final NotificationQueue notificationQueue;
    private final TaskFrameworkService frameworkService;
    /** SDK 事件队列（可为 null —— 测试直构无 bean 时不发 SDK 事件）· OPD-TS-22 task_notification 通道 */
    private final SdkEventQueue sdkEventQueue;
    /**
     * dream 任务注册表 · OPD-TP-09：dream 任务不经 {@link #spawn}（注册在 TaskFrameworkService
     * store + DreamTaskRegistry store），stopTask 需经此委托 DreamTask.kill（getTaskByType('dream')）。
     * 可为 null（未装配时 dream 任务 stopTask 返回 NOT_FOUND）。
     */
    private volatile DreamTaskRegistry dreamTaskRegistry;
    /**
     * remote_agent 状态机 · stopTask REMOTE_AGENT 分发委托（M-9）。
     * volatile + setter 注入（对齐 W7 DreamTaskRegistry 模式）；可为 null —— 未接线时
     * remote 任务查不到 → 走 NOT_FOUND（fail-closed）。
     */
    private volatile com.nexusai.application.agent.remote.RemoteAgentTaskService remoteAgentTaskService;
    /**
     * monitor_mcp 任务运行器 · OPD-TS-25：monitor 任务不经 {@link #spawn}（registerTask 落
     * TaskFrameworkService 统一 store，MonitorMcpTaskRunner.java:99-102），stopTask 需经此委托
     * MonitorMcpTask.kill（getTaskByType('monitor_mcp') → MonitorMcpTaskRunner.stop() 等价，
     * CC stopTask.ts:57-65 + tasks.ts:37-39）。可为 null（未装配时 monitor 任务 stopTask 返回
     * NOT_FOUND / UNSUPPORTED_TYPE）。
     */
    private volatile MonitorMcpTaskRunner monitorMcpTaskRunner;

    /**
     * in_process_teammate 任务注册表（stopTask IN_PROCESS_TEAMMATE 分发委托）· 对齐 CC
     * stopTask.ts:57-65 {@code getTaskByType('in_process_teammate').kill} →
     * spawnInProcess.ts:227-328 {@code killInProcessTeammate}。TaskConfiguration 装配时
     * {@link #setSpawnInProcess} 注入；可为 null（未装配时 teammate 任务 stopTask 返回 NOT_FOUND）。
     */
    private volatile com.nexusai.application.agent.team.SpawnInProcess spawnInProcess;
    /**
     * STOMP 模板 · [cron-task-inject-align C8 · 决策8] 终态 task_notification 结构化直推：空闲路径 /
     * 无 turn 无 SDK drain（LlmAgentLoop :3885-3893 仅 turn 顶部推），经本字段直接推 /topic/tasks。
     * volatile + setter 注入（仿 dreamTaskRegistry :56-78 模式）；可为 null —— 测试直构无 bean 时
     * {@link #emitTerminatedSdk} null 守卫跳过不阻断。
     */
    private volatile org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate;

    public BackgroundTaskRunner(NotificationQueue notificationQueue,
                                 TaskFrameworkService frameworkService) {
        this(notificationQueue, frameworkService, null);
    }

    public BackgroundTaskRunner(NotificationQueue notificationQueue,
                                 TaskFrameworkService frameworkService,
                                 SdkEventQueue sdkEventQueue) {
        this.notificationQueue = notificationQueue;
        this.frameworkService = frameworkService;
        this.sdkEventQueue = sdkEventQueue;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bg-task-worker");
            t.setDaemon(true); // CC daemon thread
            return t;
        });
    }

    /** 注入 dream 任务注册表（TaskConfiguration 装配 · OPD-TP-09 TaskStop 分发用）。 */
    public void setDreamTaskRegistry(DreamTaskRegistry dreamTaskRegistry) {
        this.dreamTaskRegistry = dreamTaskRegistry;
    }

    /** 注入 remote_agent 状态机（stopTask REMOTE_AGENT 分发委托）· TaskConfiguration 装配时调用。 */
    public void setRemoteAgentTaskService(com.nexusai.application.agent.remote.RemoteAgentTaskService remoteAgentTaskService) {
        this.remoteAgentTaskService = remoteAgentTaskService;
    }

    /** 注入 monitor_mcp 任务运行器（TaskConfiguration 装配 · OPD-TS-25 TaskStop/killMonitorMcpTasksForAgent 分发用）。 */
    public void setMonitorMcpTaskRunner(MonitorMcpTaskRunner monitorMcpTaskRunner) {
        this.monitorMcpTaskRunner = monitorMcpTaskRunner;
    }

    /** 注入 in-process teammate 注册表（TaskConfiguration 装配 · IMP-G3 TaskStop IN_PROCESS_TEAMMATE 分发用）。 */
    public void setSpawnInProcess(com.nexusai.application.agent.team.SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
    }

    /** 注入 STOMP 模板（TaskConfiguration 装配 · C8 结构化 task_notification 直推）。 */
    public void setWsTemplate(org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    /**
     * 提交后台任务 — 对齐 CC spawnShellTask
     *
     * @param task 后台任务 (status=RUNNING)
     * @param bashCommand 要执行的 bash 命令
     * @param createSessionId 创建此后台任务的会话 sessionId（Phase 4 cron-notify：由工具体从
     *                        {@code ToolUseContext.sessionId()} 透传，drain 3a 注入创建会话回合）；
     *                        null = 无会话上下文 → 回落全局（CronIdleExecutor GLOBAL_SESSION_UUID）。
     *                        <b>不得读 MDC</b> —— 工具体在 tool-exec 池线程执行，无 MDC
     *                        （StreamingToolExecutor 注释权威确认），ctx.sessionId() 是唯一可靠源
     *                        （MonitorTool 同模式）。task 自身已带 sessionId 时优先保留（caller 显式）。
     */
    public void spawn(BackgroundTask task, String bashCommand, @Nullable String createSessionId) {
        // Phase 4 (cron-notify): 透传创建会话 sessionId 存在 task 上，供 executor/watchdog 通知
        // 线程入队 {@code QueueItem.sessionId}（那些线程 MDC 已丢）。
        // CC 对齐：LocalShellTask.tsx:105-171 enqueueShellNotification 注入当前会话（CC 单进程
        // ambient）；Java 多会话 → 显式携带，drain 3a 注入创建会话回合。
        BackgroundTask sessionTask = (createSessionId != null && !createSessionId.isBlank() && task.sessionId() == null)
            ? task.withSessionId(createSessionId) : task;
        tasks.put(sessionTask.id(), sessionTask);
        frameworkService.registerTask(sessionTask);

        // s13-p2: 创建 runner 并保存引用 (供 cancel() 时 destroyForcibly)
        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        runnerMap.put(sessionTask.id(), runner);

        // s13 P1-5 修复: CC startStallWatchdog 检测到 prompt → enqueue advisory notification
        // 对齐 CC LocalShellTask.tsx:80-94 (非 kill — kill 是模型决策)
        StallWatchdog watchdog = new StallWatchdog(
            sessionTask.id(), sessionTask.outputFile(),
            StallWatchdog.CC_STALL_THRESHOLD_MS, StallWatchdog.CC_STALL_CHECK_INTERVAL_MS,
            () -> {
                log.warn("BackgroundTaskRunner: stall detected for task {}, enqueuing advisory", sessionTask.id());
                // 读取输出尾部 (CC LocalShellTask.tsx:64-72)
                String tail = StallWatchdog.readTailForTest(sessionTask.outputFile());
                String xml = TaskNotificationBuilder.buildStallNotification(
                    sessionTask.id(), sessionTask.description(), tail);
                notificationQueue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(xml, "task-notification",
                        NotificationQueue.Priority.NEXT, null, null, false, null, false, null,
                        sessionTask.sessionId()));
            });
        watchdog.start();

        Future<?> future = executor.submit(() -> {
            try {
                LocalBashTaskRunner.BashResult result = runner.execute(bashCommand, sessionTask.outputFile());

                // s13-p2: cancel() 可能在 execute() 期间标记 KILLED → 不再覆盖
                BackgroundTask current = tasks.get(sessionTask.id());
                if (current != null && current.status() == BackgroundTaskStatus.KILLED) {
                    if (log.isDebugEnabled()) {
                        log.debug("BackgroundTaskRunner: task {} already KILLED, skip result processing", sessionTask.id());
                    }
                    return;
                }

                BackgroundTask completed = sessionTask
                    .withStatus(result.exitCode() == 0 ? BackgroundTaskStatus.COMPLETED : BackgroundTaskStatus.FAILED)
                    .withEndTime(System.currentTimeMillis())
                    // [IMP-G] G25① CC TaskOutput.exitCode 跟踪（local_bash result.code）
                    .withExitCode(result.exitCode())
                    .withNotified();
                tasks.put(sessionTask.id(), completed);
                frameworkService.updateTaskState(sessionTask.id(), completed);

                // [IMPL-10] DEL-L03-02: TaskCompleted/TeammateIdle hook 发射已删除
                //   （CC stopHooks.ts 为 turn-end 内联，无 background-task 完成触发路径）

                // CC LocalShellTask.tsx:105-171 — enqueueShellNotification
                // T1: size-watchdog 杀进程消息并入 summary（BashResult.stderr 前缀命中 → detail 追加），
                //   对齐 CC prependStderr 的模型可见语义（ShellCommand.ts:318-322）。非 size-kill → null 不改变文本。
                String xml = TaskNotificationBuilder.buildEnqueueShellNotification(completed, result.exitCode(),
                    sizeWatchdogKillNote(result));
                notificationQueue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(xml, "task-notification",
                        null, null, null, false, null, false, null, completed.sessionId()));

                // OPD-TS-22/TP-18: 终态 task_notification SDK 事件（Java 无 print.ts XML→SDK 解析，
                // 必须直接发射供前端消费；XML 通知仍走队列供模型，非双发——CC 双发风险是两条 SDK 路径）
                emitTerminatedSdk(completed);

                log.info("BackgroundTaskRunner: task {} completed, exitCode={}", sessionTask.id(), result.exitCode());
            } catch (Exception e) {
                // CC: cancel() 已标记 KILLED 时, 不再覆盖为 FAILED (防止竞态覆盖)
                BackgroundTask current = tasks.get(sessionTask.id());
                if (current != null && current.status() == BackgroundTaskStatus.KILLED) {
                    if (log.isDebugEnabled()) {
                        log.debug("BackgroundTaskRunner: task {} already KILLED, skip FAILED override", sessionTask.id());
                    }
                    return;
                }
                BackgroundTask failed = sessionTask
                    .withStatus(BackgroundTaskStatus.FAILED)
                    .withEndTime(System.currentTimeMillis())
                    .withNotified();
                tasks.put(sessionTask.id(), failed);
                frameworkService.updateTaskState(sessionTask.id(), failed);

                String xml = TaskNotificationBuilder.buildEnqueueShellNotification(failed, -1);
                notificationQueue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(xml, "task-notification",
                        null, null, null, false, null, false, null, failed.sessionId()));

                emitTerminatedSdk(failed);

                log.error("BackgroundTaskRunner: task {} failed: {}", sessionTask.id(), e.getMessage());
            } finally {
                watchdog.stop(); // s13-p1: CC cleanup
                runnerMap.remove(sessionTask.id()); // s13-p2
                runningTasks.remove(sessionTask.id());
            }
        });

        runningTasks.put(sessionTask.id(), future);
        log.info("BackgroundTaskRunner: task {} spawned, description='{}'", sessionTask.id(), sessionTask.description());
    }

    /**
     * T1: size-watchdog 杀进程的通知补充说明 · BashResult 为 size-kill（exitCode=137 + stderr 前缀命中
     * {@link LocalBashTaskRunner#KILLED_FOR_SIZE_MESSAGE}）→ 返回该消息并入通知 summary（对齐 CC
     * prependStderr 语义的摘要侧等价，ShellCommand.ts:318-322）；非 size-kill → null（不改变既有通知文本）。
     * package-private 供聚焦单测直调（规则九）。
     */
    static String sizeWatchdogKillNote(LocalBashTaskRunner.BashResult result) {
        if (result.exitCode() == LocalBashTaskRunner.KILLED_FOR_SIZE_EXIT_CODE
                && result.stderr() != null
                && result.stderr().startsWith(LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE)) {
            return LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE;
        }
        return null;
    }

    /**
     * Stub 任务 spawn — 对齐 CC 其余 6 种 TaskType (非 LOCAL_BASH)
     *
     * <p>写入 stub 输出文件 → 立即标记 COMPLETED → enqueue 通知
     * <br>s14+ 将替换为各自的实际执行器 (AgentTask / WorkflowTask / etc.)
     */
    public void spawnStub(BackgroundTask task, @Nullable String createSessionId) {
        // Phase 4 (cron-notify): 透传创建会话 sessionId（同 spawn，供通知透传）。
        BackgroundTask sessionTask = (createSessionId != null && !createSessionId.isBlank() && task.sessionId() == null)
            ? task.withSessionId(createSessionId) : task;
        tasks.put(sessionTask.id(), sessionTask);
        frameworkService.registerTask(sessionTask);

        // 写入 stub 输出文件
        try {
            Path outputPath = Path.of(sessionTask.outputFile());
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath,
                "[s13 stub] Task type '" + sessionTask.type().getTypeString()
                + "' not yet fully implemented — will be available in s14+");
        } catch (IOException e) {
            log.warn("BackgroundTaskRunner: failed to write stub output for task {}: {}", sessionTask.id(), e.getMessage());
        }

        BackgroundTask completed = sessionTask
            .withStatus(BackgroundTaskStatus.COMPLETED)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(sessionTask.id(), completed);
        frameworkService.updateTaskState(sessionTask.id(), completed);

        String xml = TaskNotificationBuilder.buildEnqueueShellNotification(completed, 0);
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml, "task-notification",
                null, null, null, false, null, false, null, completed.sessionId()));

        emitTerminatedSdk(completed);

        log.info("BackgroundTaskRunner: stub task {} ({}) completed", sessionTask.id(), sessionTask.type().getTypeString());
    }

    /**
     * 读取任务输出文件内容 — 对齐 CC 读取 outputFile
     *
     * @return 输出文件内容, 若文件不存在则返回提示消息
     */
    /**
     * 输出读取的任务解析 · 本地 {@code tasks} map 优先，未命中回退统一 store
     * （{@link TaskFrameworkService}）。
     *
     * <p><b>WHY（monitor-rework DEC-4）</b>: MonitorMcpTaskRunner.registerTask 把 MONITOR_MCP
     * 任务注册到 TaskFrameworkService.store（framework.ts:77-117 registerTask 语义），不在本 runner
     * 本地 map。CC 的 {@code state.tasks} 是单一异质 map（bash + monitor_mcp + agent 同 map），
     * TaskOutputTool 经 getOutput 天然可读 monitor 任务；Java 拆分两处存储 → getOutput/readTaskOutput
     * 只查本地 map 会让 TaskOutput 对 monitor 任务返回 found=false（DEC-4「模型经 TaskOutput 读
     * outputFile」断裂）。此回退对齐 CC 单一 map 语义。
     *
     * <p><b>公开入口（Bug B）</b>：跨模块按 taskId 查任务的公共方法 ——
     * SubagentController.getTranscript 以前端 taskId 查本 map 取 {@code task.agentId()}
     * （子代理 UUID）→ {@link com.nexusai.application.agent.subagent.AgentContext#unpackAgentId}
     * 还原 a+16hex 找 transcript。registerAsyncAgent / registerAgentForeground 均本地 + store
     * 双写（BackgroundTaskRunner.java:639/704 + frameworkService.registerTask），本方法两处均覆盖。
     *
     * @param taskId 任务 id
     * @return 任务（本地 map 或统一 store）；均无 → null
     */
    public BackgroundTask resolveOutputTask(String taskId) {
        BackgroundTask local = tasks.get(taskId);
        if (local != null) {
            return local;
        }
        if (frameworkService != null) {
            return frameworkService.getTask(taskId).orElse(null);
        }
        return null;
    }

    public String readTaskOutput(String taskId) {
        BackgroundTask task = resolveOutputTask(taskId);
        if (task == null) {
            return "Task not found: " + taskId;
        }
        try {
            Path path = Path.of(task.outputFile());
            if (Files.exists(path)) {
                return Files.readString(path);
            }
            return "Output file not yet available for task: " + taskId;
        } catch (IOException e) {
            log.warn("BackgroundTaskRunner: failed to read output for task {}: {}", taskId, e.getMessage());
            return "Failed to read output for task: " + taskId;
        }
    }

    /**
     * 取消后台任务 — 对齐 CC killShellTasks
     *
     * <p>s13-p2: 先标记 KILLED → 杀子进程 (destroyForcibly + waitFor) → future.cancel
     * <br>优雅降级: waitFor 超时不抛异常, 已退出进程跳过 destroyForcibly
     */
    public boolean cancel(String taskId) {
        Future<?> future = runningTasks.remove(taskId);
        if (future != null) {
            // s13-p2: 先标记 KILLED (防止竞态 — killProcess 后 executor 线程可能立即返回)
            markKilled(taskId);

            // s13-p2: CC killTask → shellCommand.kill() → destroyForcibly + waitFor
            LocalBashTaskRunner runner = runnerMap.remove(taskId);
            if (runner != null) {
                if (runner.isProcessAlive()) {
                    runner.killProcess();
                    if (log.isDebugEnabled()) {
                        log.debug("BackgroundTaskRunner: subprocess killed for task {}", taskId);
                    }
                } else if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner: subprocess already exited for task {}", taskId);
                }
            }

            boolean cancelled = future.cancel(true);
            log.info("BackgroundTaskRunner: task {} cancelled={}", taskId, cancelled);
            return cancelled;
        }
        // 任务不在 runningTasks 中 — 可能已完成或不存在
        // Phase 3: 若 task 在 tasks 中且 RUNNING (async agent 场景), 也尝试 markKilled
        BackgroundTask task = tasks.get(taskId);
        if (task != null && task.status() == BackgroundTaskStatus.RUNNING) {
            markKilled(taskId);
            // G1-2: 前台 bash 任务（registerForeground）不经 executor.submit → 不在 runningTasks，
            //   但仍持有 runner（runnerMap）→ 杀子进程（对齐 CC killTask → shellCommand.kill()，
            //   killShellTasks.ts:16-46）。原实现漏杀 → 前台转后台后 cancel 只标 KILLED 不杀进程，
            //   子进程成孤儿。runner 不在 runnerMap（async agent / 其他类型）→ 跳过。
            LocalBashTaskRunner runner = runnerMap.get(taskId);
            if (runner != null && runner.isProcessAlive()) {
                runner.killProcess();
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner: foreground subprocess killed for task {}", taskId);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner: task {} (async, no Future) marked KILLED", taskId);
            }
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debug("BackgroundTaskRunner: cancel task {} not found in runningTasks", taskId);
        }
        return false;
    }

    /**
     * Phase 3: 标记 task 为 KILLED + 入队通知 (供 cancel/killShellTasksForAgent 复用).
     * 提取自原 cancel 方法以支持 async agent task (无 Future 场景).
     */
    private void markKilled(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) return;
        BackgroundTask killed = task
            .withStatus(BackgroundTaskStatus.KILLED)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(taskId, killed);
        frameworkService.updateTaskState(taskId, killed);

        String xml = TaskNotificationBuilder.buildEnqueueShellNotification(killed, -1);
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml, "task-notification",
                null, null, null, false, null, false, null, killed.sessionId()));

        emitTerminatedSdk(killed);
    }

    /** 获取任务当前状态 */
    public Optional<BackgroundTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 获取所有已注册任务 */
    public List<BackgroundTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 全部类型任务全量（本地 tasks ∪ TaskFrameworkService.store，按 id 去重）。
     *
     * <p>用户拍板「全部类型」（#138 前端异步任务清单）——monitor_mcp / in_process_teammate /
     * dream 任务不经 {@link #spawn}，仅存统一 store（MonitorMcpTaskRunner /
     * InProcessTeammateTaskRegistry / DreamTaskRegistry registerTask → store），本地 map 查不到；
     * REMOTE_AGENT 独立 remoteTasks map 权威（RemoteAgentTaskService.findTask），不入本合并。
     * runner 先 put、framework 后 put：双写类型两处同值，去重后任一皆可（LinkedHashMap 保序）。
     *
     * @return 合并去重后的全部后台任务（本地 + store），供统一清单 / stop-all 消费
     */
    public List<BackgroundTask> listAllTasks() {
        List<BackgroundTask> storeTasks = frameworkService != null ? frameworkService.listAll() : List.of();
        Map<String, BackgroundTask> merged = new LinkedHashMap<>();
        for (BackgroundTask t : tasks.values()) {
            merged.put(t.id(), t);
        }
        for (BackgroundTask t : storeTasks) {
            merged.put(t.id(), t);
        }
        if (log.isDebugEnabled()) {
            log.debug("BackgroundTaskRunner.listAllTasks: 本地 {} + store {} → 合并 {} 个任务（全部类型）",
                tasks.size(), storeTasks.size(), merged.size());
        }
        return new ArrayList<>(merged.values());
    }

    /** 停止后台任务运行器 */
    public void shutdown() {
        executor.shutdownNow();
        log.info("BackgroundTaskRunner: shutdown");
    }

    // ════════════════════════════════════════════════════════════════════
    // Phase 3: owner-scoped 批 kill + async agent 归属
    // 对齐 CC AgentTool.tsx:686-764 + killShellTasks.ts:53-76 +
    //            LocalAgentTask.tsx:197-262
    // ════════════════════════════════════════════════════════════════════

    /**
     * 当前会话 ID · 对齐 CC getSessionId（diskOutput.ts:50-55 getTaskOutputDir 消费）。
     *
     * <p>CC 真源：{@code bootstrap/state.ts:431-433} 进程级 sessionId 恒非 null；Java 无单例
     * 会话，以 {@link RequestContext#sessionId()}（MDC）为主源（agent 循环线程经
     * {@code ChatService.processUserMessage} 入口设定，ChatService.java:154），回退
     * {@code nexusai.sessionId} sysprop（对齐 TaskService.getTaskListId 优先级 5，
     * tasks.ts:209 getSessionId() 回退），再回退 {@code "unknown"}（fail-closed 占位，
     * 测试/无会话上下文直构场景）。
     *
     * @return 非 null 会话 ID 串
     */
    private static String resolveSessionId() {
        String sid = RequestContext.sessionId();
        if (sid != null && !sid.isBlank()) {
            return sid;
        }
        String sysprop = System.getProperty("nexusai.sessionId");
        if (sysprop != null && !sysprop.isBlank()) {
            return sysprop;
        }
        return "unknown";
    }

    /**
     * CC {@code getClaudeTempDirName} 等价（filesystem.ts:307-315）—— task 输出目录的 <b>per-user 层</b>目录名。
     *
     * <p>CC 真源（自验，不信注释）：
     * <pre>
     * getClaudeTempDirName():                                  // filesystem.ts:307-315
     *   if (getPlatform() === 'windows') return 'claude'       // :308-310 Windows tmpdir 已 per-user
     *   const uid = process.getuid?.() ?? 0                    // :313
     *   return `claude-${uid}`                                 // :314 Unix /tmp 共享需 uid 隔离
     * </pre>
     * Java 等价：Windows（{@code os.name} 含 "windows"）→ {@code claude}（{@code java.io.tmpdir}
     * 已是 {@code C:\Users\{user}\AppData\Local\Temp}，天然 per-user）；非 Windows →
     * {@code claude-{user.name}}（{@code System.getProperty("user.name")} 为 CC {@code getuid()}
     * 的 Java 等价——任务铁律："uid 来源：System.getProperty("user.name") 或 cc uid 等价"）。
     *
     * <p>WHY（规则九）：Unix 多用户共享同一 {@code /tmp}，不加 uid 层会造成权限冲突与跨用户串读
     * （filesystem.ts:311-313 注释）；Windows tmpdir 已 per-user 故 CC 不加 uid（:305 注释）。
     *
     * @return per-user 层目录名（Windows {@code claude} / Unix {@code claude-{uid}}）
     */
    static String claudeTempDirName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            return "claude";
        }
        String uid = System.getProperty("user.name", "0");
        return "claude-" + uid;
    }

    /**
     * 任务输出目录 · 对齐 CC getTaskOutputDir（diskOutput.ts:50-55
     * {@code join(getProjectTempDir(), getSessionId(), 'tasks')}）。
     *
     * <p><b>方案B 五层对齐 CC 真源形态</b>（推翻 A-7 简化根 {@code nexusai-sessions}，单轨无兼容层）：
     * <pre>
     * getTaskOutputDir()  = join(getProjectTempDir(), getSessionId(), 'tasks')                 // diskOutput.ts:50-55
     * getProjectTempDir() = join(getClaudeTempDir(), sanitizePath(getOriginalCwd())) + sep     // filesystem.ts:376-378
     * getClaudeTempDir()  = join(tmpdir, getClaudeTempDirName()) + sep                          // filesystem.ts:331-347
     * </pre>
     * Java 五层映射：
     * <pre>
     * {tmpRoot}/claude-{uid}/{sanitizePath(originalCwd)}/{sessionId}/tasks
     *   ①tmp根      ②per-user      ③per-project                ④per-session   ⑤tasks
     * </pre>
     * <ol>
     *   <li><b>① tmpRoot</b> = {@code java.io.tmpdir}（现有）</li>
     *   <li><b>② per-user</b> = {@link #claudeTempDirName()}（CC getClaudeTempDirName
     *       filesystem.ts:307-315：Windows→{@code claude}，Unix→{@code claude-{uid}}）</li>
     *   <li><b>③ per-project</b> = {@link CwdResolution#getOriginalCwdLayer(String)} 的 CC
     *       sanitizePath（sessionStoragePortable.ts:311-319，非字母数字→'-'；filesystem.ts:376
     *       {@code sanitizePath(getOriginalCwd())}）—— 不同项目 originalCwd → 不同输出目录</li>
     *   <li><b>④ per-session</b> = {@link #resolveSessionId()}（同源，防并发会话 clobber）</li>
     *   <li><b>⑤ tasks</b> 子目录 + taskOutputPath 追加 {@code .output} 扩展名（现有）</li>
     * </ol>
     *
     * <p>WHY（规则九）：CC 唯一 diskOutput 机制（C 探查 grep 全仓实证 13 类写方/读方全走
     * getTaskOutputPath，无第二个根）；per-user 层防多用户共享 /tmp 权限冲突（filesystem.ts:311-314），
     * per-project 层按 originalCwd 分项目（filesystem.ts:376-378），per-session 层防并发会话 clobber
     * （diskOutput.ts:38-41）。读方（TaskOutputTool / taskOutputFile 存储字段）存的是完整路径，自动跟随。
     * 旧 A-7 简化根 {@code {tmpdir}/nexusai-sessions} 已删除（无兼容层/双轨）。
     *
     * @return 任务输出目录绝对路径（不含 taskId 文件名）
     */
    public static String taskOutputDir() {
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        String sessionId = resolveSessionId();
        String sanitizedCwd = AutoMemPaths.sanitizePath(CwdResolution.getOriginalCwdLayer(sessionId));
        return Paths.get(tmpDir, claudeTempDirName(), sanitizedCwd, sessionId, "tasks").toString();
    }

    /**
     * 任务输出文件路径 · 对齐 CC getTaskOutputPath（diskOutput.ts:72-74
     * {@code join(getTaskOutputDir(), \`${taskId}.output\`)}）。
     *
     * <p>产出 {@code {tmpRoot}/claude-{uid}/{sanitizePath(originalCwd)}/{sessionId}/tasks/{taskId}.output}
     * （方案B 五层，见 {@link #taskOutputDir()}）。
     *
     * @param taskId 任务 id（local_agent = agentId.toString()，CC 合一）
     * @return 输出文件绝对路径
     */
    public static String taskOutputPath(String taskId) {
        return Paths.get(taskOutputDir(), taskId + ".output").toString();
    }

    /**
     * Phase 3: 注册 async agent task · 对齐 CC registerAsyncAgent (AgentTool.tsx:686-764)
     *
     * <p>CC 语义: taskId === agentId (合一), task.type=LOCAL_AGENT,
     * task.isBackgrounded=true (async spawn 一律后台).
     *
     * <p>这里只做 task 注册 + 入 store, 实际执行交给 caller 提供的 Future
     * (Phase 3.5+ 接入 SubagentExecutor 真实执行流).
     *
     * <p><b>OPD-TP-21</b>: 创建并保存 task-scoped AbortController · 对齐 CC registerAsyncAgent
     * (LocalAgentTask.tsx:486-495):
     * <ul>
     *   <li>{@code parentAbortController} 非 null → {@code parent.createChild()}（CC :488
     *       createChildAbortController — 父 abort 级联到子，如 in-process teammate 场景）</li>
     *   <li>{@code null} → 独立 unlinked controller（CC :489 createAbortController()；async
     *       子 agent 独立运行，CC runAgent.ts:526 isAsync ? new AbortController()）</li>
     * </ul>
     * 保存于 {@link #taskAbortControllers}，kill 时 {@link #killAsyncAgent} 经 {@code abort()}
     * 直接中断 worker（对齐 CC LocalAgentTask.tsx:288 task.abortController?.abort()）。
     *
     * <p><b>A-7 outputFile</b>：对齐 CC 分层格式 {@code taskOutputPath(agentId)}（CC
     * LocalAgentTask.tsx:488 createTaskStateBase → Task.ts:121 outputFile: getTaskOutputPath(id)；
     * 旧平铺 {@code /tmp/agent-{taskId}.out} 已删除）。
     *
     * @param agentId              sub-agent UUID (CC 合一 taskId)
     * @param description          人类可读描述 (CC AgentTask.description)
     * @param prompt               agent prompt
     * @param selectedAgentType    Agent 类型名 (general-purpose, statusline-setup, etc.)
     * @param parentAbortController 父 agent 的 AbortController (可为 null → 独立 unlinked)
     * @param createSessionId      创建此子代理任务的会话 sessionId（Phase 4 cron-notify：由
     *                             SubagentTool 从 {@code mainLoop.getCurrentToolUseContext().sessionId()}
     *                             透传——父循环 TUC 承载用户会话，<b>不可读 MDC</b>，tool-exec 池线程
     *                             无 MDC）；null = 回落全局。
     * @return 已注册的 BackgroundTask
     */
    public BackgroundTask registerAsyncAgent(
            UUID agentId, String description, String prompt,
            String selectedAgentType, @Nullable AbortController parentAbortController,
            @Nullable String createSessionId) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId 不能为 null");
        }
        // CC 语义: taskId === agentId (LocalAgentTask.tsx:197-262)
        String taskId = agentId.toString();
        // 方案B: outputFile 对齐 CC 五层 {tmpRoot}/claude-{uid}/{sanitizePath(originalCwd)}/{sessionId}/tasks/{taskId}.output
        //   （CC LocalAgentTask.tsx:488 createTaskStateBase → Task.ts:121 getTaskOutputPath）
        String outputFile = taskOutputPath(taskId);
        // Phase 4 (cron-notify): 透传创建会话 sessionId（SubagentTool 从父循环 TUC 提取，
        // 与 spawn 同源）——终态通知（transitionToTerminal/enqueueAgentNotification/killAsyncAgent）
        // 在 worker 线程执行，MDC 已丢，须注册时透传存在 task 上。
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            description != null ? description : selectedAgentType,
            null,
            System.currentTimeMillis(),
            null, null,
            outputFile, 0L, false,
            agentId,        // agentId
            true,           // isBackgrounded (async 一律后台)
            createSessionId,// Phase 4: 创建会话 sessionId（通知注入创建会话回合）
            null, null,
            prompt,         // [IMP-G] G25① CC TaskOutput.prompt 跟踪（local_agent spawn prompt）
            null,           // result（G25①）
            null,           // worktreePath（FORK-02，注册时未知，由 SubagentExecutor Step 21.0 登记）
            null            // worktreeBranch（FORK-02）
        );
        tasks.put(taskId, task);
        // OPD-TP-21: task-scoped AbortController（父非 null → child 级联；null → 独立 unlinked）。
        // 对齐 CC LocalAgentTask.tsx:486-489 决策 + CC runAgent.ts:526 async 独立控制器。
        AbortController taskAbort = parentAbortController != null
            ? parentAbortController.createChild()
            : new AbortController();
        taskAbortControllers.put(taskId, taskAbort);
        frameworkService.registerTask(task);
        log.info("BackgroundTaskRunner: async agent registered taskId={} type={} abortController={}",
            taskId, selectedAgentType, taskAbort);
        return task;
    }

    /**
     * [FORK-02] 登记 task 的保留隔离 worktree · 对齐 CC enqueueAgentNotification worktreePath/
     * worktreeBranch（LocalAgentTask.tsx:198-209 + getWorktreeResult AgentTool.tsx:644-685
     * 保留才返回 {@code {worktreePath, worktreeBranch}}）。
     *
     * <p><b>WHY</b>: fork / isolation=worktree 子代理存活（keepWorktree）或 resume 复用时，
     * 父 Agent 必须从终态通知收到产物路径 —— 否则模型拿到「任务完成」却找不到隔离副本改了什么
     * （FORK-02 模型可见高优先）。CC 在 spawn 层以 cleanupWorktreeIfNeeded 闭包捕获；Java 多会话
     * 服务由 {@link SubagentExecutor} Step 21.0 判定保留后登记到 task，终态通知
     * （{@link #killAsyncAgent} / {@link #transitionToTerminal}）读
     * {@code task.worktreePath()/task.worktreeBranch()} 透传 {@code buildEnqueueAgentNotification}
     * → XML 输出 worktree 段。
     *
     * <p>同步更新 {@code frameworkService}（与 registerAsyncAgent/transitionToTerminal 双写一致），
     * task 查询/resume 亦可见。worktreePath 空 → no-op（无隔离 worktree 场景零行为变化）。
     *
     * @param taskId         task id (= agentId.toString())
     * @param worktreePath   保留 worktree 绝对路径（null/空白 → no-op）
     * @param worktreeBranch worktree 分支（可空）
     * @return 更新后的 task（task 不存在 → null）
     */
    public BackgroundTask registerTaskWorktree(String taskId, @Nullable String worktreePath,
                                               @Nullable String worktreeBranch) {
        if (taskId == null || worktreePath == null || worktreePath.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.registerTaskWorktree: task={} worktree 信息为空, 跳过",
                    taskId);
            }
            return null;
        }
        AtomicReference<BackgroundTask> ref = new AtomicReference<>();
        tasks.computeIfPresent(taskId, (k, current) -> {
            BackgroundTask updated = current.withWorktree(worktreePath, worktreeBranch);
            ref.set(updated);
            return updated;
        });
        BackgroundTask updated = ref.get();
        if (updated == null) {
            log.warn("BackgroundTaskRunner.registerTaskWorktree: task {} 不存在, worktree 未登记 "
                + "(worktreePath={})", taskId, worktreePath);
            return null;
        }
        frameworkService.updateTaskState(taskId, updated);
        log.info("BackgroundTaskRunner: task {} 登记保留 worktree={} branch={}",
            taskId, worktreePath, worktreeBranch);
        return updated;
    }

    /**
     * [RF-2 ②] 注册前台子代理任务 · 对齐 CC registerAgentForeground
     * （LocalAgentTask.tsx:526-614）。
     *
     * <p>CC 语义：sync 子代理在 query loop 启动前注册为「前台任务」（{@code isBackgrounded: false}），
     * 以便随时被 backgroundAll 后台化（LocalAgentTask.tsx:565 注释「Not yet backgrounded - running
     * in foreground」）。注册返回 {@code taskId = agentId}（合一），CC AgentTool.tsx:828-843 用其
     * 作为 {@code summaryTaskId = foregroundTaskId} 守卫 sync 摘要门（AgentTool.tsx:852
     * {@code summaryTaskId && sdk}）。对比 {@link #registerAsyncAgent}（isBackgrounded=true 恒后台），
     * 本方法 isBackgrounded=false。
     *
     * <p>Java 端子代理 agentId 在 {@code executeStreaming} Step 5（createSubagentContext）内生成，
     * 故本方法由 executor 内部在 agentId 已知后调用（非 SubagentTool 层），taskId=agentId 合一不变。
     *
     * @param agentId           子代理 UUID（CC 合一 taskId）
     * @param description       任务描述（CC AgentTask.description / prompt）
     * @param prompt            agent prompt（CC LocalAgentTask.tsx:119）
     * @param selectedAgentType Agent 类型名（general-purpose 等）
     * @param createSessionId   创建此子代理任务的会话 sessionId（Phase 4 cron-notify：由
     *                          SubagentExecutor 从 {@code executeStreaming} 的
     *                          {@code agentTuc.sessionId()}（父继承会话）透传；<b>不可读 MDC</b>，
     *                          tool-exec 池线程无 MDC）；null = 回落全局。
     * @return 已注册的前台 BackgroundTask（isBackgrounded=false）
     */
    public BackgroundTask registerAgentForeground(
            UUID agentId, String description, String prompt, String selectedAgentType,
            @Nullable String createSessionId) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId 不能为 null");
        }
        // CC 语义: taskId === agentId（LocalAgentTask.tsx:132/406）
        String taskId = agentId.toString();
        // A-7: outputFile 对齐 CC 分层格式（CC LocalAgentTask.tsx:553 createTaskStateBase
        //   → Task.ts:121 outputFile: getTaskOutputPath(id)）
        String outputFile = taskOutputPath(taskId);
        // Phase 4 (cron-notify): 透传创建会话 sessionId（SubagentExecutor 从 executeStreaming
        // 的 agentTuc.sessionId() 提取）——若随后被 backgroundAgentTask 后台化，完成通知须注入
        // 创建会话回合。
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            description != null ? description : selectedAgentType,
            null,
            System.currentTimeMillis(),
            null, null,
            outputFile, 0L, false,
            agentId,        // agentId
            false,          // isBackgrounded=false（前台，CC LocalAgentTask.tsx:565）
            createSessionId,// Phase 4: 创建会话 sessionId（通知注入创建会话回合）
            null, null,
            prompt,         // [IMP-G] G25① CC TaskOutput.prompt 跟踪（local_agent spawn prompt）
            null,           // result（G25①）
            null,           // worktreePath（FORK-02，注册时未知，由 SubagentExecutor Step 21.0 登记）
            null            // worktreeBranch（FORK-02）
        );
        tasks.put(taskId, task);
        frameworkService.registerTask(task);
        log.info("BackgroundTaskRunner: 前台子代理任务已注册 taskId={} type={} isBackgrounded=false (RF-2 registerAgentForeground)",
            taskId, selectedAgentType);
        return task;
    }

    /**
     * [RF-2 ③] 后台化指定前台子代理任务 · 对齐 CC backgroundAgentTask
     * （LocalAgentTask.tsx:620-652）。
     *
     * <p>CC 语义：{@code isLocalAgentTask(task) && !task.isBackgrounded} 守卫 → 置
     * {@code isBackgrounded: true}（:628-643）。仅前台任务可后台化，幂等（已后台化/不存在 → false）。
     *
     * @param taskId 任务 id（= agentId.toString()）
     * @return true 成功后台化；false 任务不存在 / 非前台 / 已后台化
     */
    public boolean backgroundAgentTask(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null || current.type() != TaskType.LOCAL_AGENT) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.backgroundAgentTask: task {} 不存在或非 local_agent", taskId);
            }
            return false;
        }
        if (current.isBackgrounded()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.backgroundAgentTask: task {} 已后台化, 跳过", taskId);
            }
            return false;
        }
        BackgroundTask backgrounded = current.withIsBackgrounded(true);
        tasks.put(taskId, backgrounded);
        frameworkService.updateTaskState(taskId, backgrounded);
        log.info("BackgroundTaskRunner.backgroundAgentTask: task {} 已后台化 (RF-2 backgroundAgentTask)", taskId);
        return true;
    }

    /**
     * [RF-2 ③] 后台化所有前台子代理任务 · 对齐 CC backgroundAll
     * （LocalShellTask.tsx:390-410）。
     *
     * <p>CC 语义（LocalShellTask.tsx:402-409）：遍历 {@code state.tasks}，对
     * {@code isLocalAgentTask(task) && !task.isBackgrounded} 的前台 agent 任务逐个调
     * {@code backgroundAgentTask}。Java 端仅代理 local_agent 前台任务（bash 前台任务走
     * LocalShellTask.backgroundTask 等价，不属本方法职责，对齐 CC :394-400 与 :402-409 分流）。
     *
     * @return 实际后台化的任务数
     */
    public int backgroundAll() {
        int count = 0;
        List<String> foreground = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            if (task.type() == TaskType.LOCAL_AGENT && !task.isBackgrounded()) {
                foreground.add(task.id());
            }
        }
        for (String taskId : foreground) {
            if (backgroundAgentTask(taskId)) {
                count++;
            }
        }
        log.info("BackgroundTaskRunner.backgroundAll: 后台化 {} 个前台子代理任务 (RF-2)", count);
        return count;
    }

    /**
     * [RF-2 ②] 注销前台子代理任务（未后台化直接完成）· 对齐 CC unregisterAgentForeground
     * （LocalAgentTask.tsx:657-682）。
     *
     * <p>CC 语义：删 {@code backgroundSignalResolvers} 项 → 仅当 {@code isLocalAgentTask(task) &&
     * !task.isBackgrounded}（前台未后台化）才从 {@code state.tasks} 移除（:664-678）。后台化任务
     * 不在此注销（由 async 生命周期 completeAsyncAgent 收尾）。
     *
     * @param taskId 任务 id
     * @return true 实际注销（前台任务）；false 不存在 / 已后台化
     */
    public boolean unregisterAgentForeground(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            return false;
        }
        if (current.isBackgrounded()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.unregisterAgentForeground: task {} 已后台化, 不注销", taskId);
            }
            return false;
        }
        tasks.remove(taskId);
        // CC unregisterAgentForeground 从 state.tasks 裸移除（LocalAgentTask.tsx:664-678）；
        //   Java 双 store（runner.tasks + frameworkService.store）须同步移除，避免 store 滞留 RUNNING。
        frameworkService.removeTask(taskId);
        log.info("BackgroundTaskRunner.unregisterAgentForeground: task {} 前台任务已注销 (RF-2)", taskId);
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // G1-2: 前台 bash 任务就地转后台（对齐 CC LocalShellTask.tsx:259-287 registerForeground /
    //   :420-474 backgroundExistingForegroundTask / :491-514 unregisterForeground）
    // ════════════════════════════════════════════════════════════════════

    /**
     * G1-2: 注册前台 bash 任务 · 对齐 CC registerForeground（LocalShellTask.tsx:259-287）。
     *
     * <p>CC 语义：bash 命令运行到 BackgroundHint 阈值后先经本方法登记为前台任务
     * （{@code isBackgrounded: false}，:281），供后续 auto-background / Ctrl+B
     * {@link #backgroundExistingForegroundTask} 就地转后台。仅登记不 spawn（进程由 BashTool
     * G5 侧线程经 {@code LocalBashTaskRunner.execute()} 运行），task 入本地 {@link #tasks} +
     * {@link TaskFrameworkService} store（对齐 CC registerTask → state.tasks，framework.ts:77-117），
     * runner 引用存 {@link #runnerMap}（cancel 复用同一 runner 杀进程）。
     *
     * @param task   前台 bash 任务（isBackgrounded 必须为 false；调用方构造时置）
     * @param runner 承载该前台命令的 LocalBashTaskRunner
     * @return taskId
     */
    public String registerForeground(BackgroundTask task, LocalBashTaskRunner runner) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        if (runner == null) {
            throw new IllegalArgumentException("runner 不能为 null");
        }
        tasks.put(task.id(), task);
        frameworkService.registerTask(task);
        runnerMap.put(task.id(), runner);
        log.info("BackgroundTaskRunner.registerForeground: 前台 bash 任务已登记 taskId={} (G1-2, LocalShellTask.tsx:259-287)",
            task.id());
        return task.id();
    }

    /**
     * G1-2: 前台任务就地转后台 · 对齐 CC backgroundExistingForegroundTask
     * （LocalShellTask.tsx:420-474）。
     *
     * <p>CC 语义：auto-background 定时器触发时，已 registerForeground 的任务<b>就地转后台</b>——
     * <b>不重新 spawn / 不重新 registerTask</b>（防重复 {@code task_started} SDK 事件 + cleanup
     * 回调泄漏，:414-418 注释），仅 {@code shellCommand.background(taskId)} + 翻转
     * {@code isBackgrounded: true}（:421-441），随后启动 StallWatchdog（:442）+ 挂完成 handler
     * （:445-472，进程结束后推进终态 + 通知 + evict）。
     *
     * <p>Java 等价：guard（local_bash 且前台且进程仍存活，对齐 CC :421 的
     * {@code shellCommand.background} status==='running' 守卫）→ 翻转 isBackgrounded=true
     * （本地 + store 双写）→ 启动 {@link StallWatchdog} → executor 提交完成 watcher
     * （{@link LocalBashTaskRunner#awaitCompletion()} 等待前台进程结束 → 终态 + 通知）。不重复
     * {@link #registerTask}（无 task_started）。
     *
     * @param taskId 前台 bash 任务 id（registerForeground 返回值）
     * @return true 成功转后台；false 任务不存在 / 非 local_bash / 已后台化 / 进程已结束
     */
    public boolean backgroundExistingForegroundTask(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null || !LocalShellTaskGuards.isLocalShellTask(current) || current.isBackgrounded()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.backgroundExistingForegroundTask: task {} 不存在/非前台/已后台化, 跳过",
                    taskId);
            }
            return false;
        }
        LocalBashTaskRunner runner = runnerMap.get(taskId);
        // 对齐 CC :421 shellCommand.background(taskId) —— 进程已结束（status != 'running'）→ 不可后台化
        if (runner == null || !runner.isProcessAlive()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.backgroundExistingForegroundTask: task {} 进程已结束/无 runner, 不可后台化",
                    taskId);
            }
            return false;
        }
        BackgroundTask backgrounded = current.withIsBackgrounded(true);
        tasks.put(taskId, backgrounded);
        frameworkService.updateTaskState(taskId, backgrounded);

        // 启动 StallWatchdog（前台任务此前无 watchdog）· 对齐 CC :442 startStallWatchdog
        StallWatchdog watchdog = newStallWatchdog(backgrounded);
        watchdog.start();
        foregroundWatchdogs.put(taskId, watchdog);

        // 挂完成 handler · 对齐 CC :445-472 shellCommand.result.then —— 等前台进程自然结束
        //   → 推进终态 + 通知 + 停 watchdog。进程在 G5 BashTool 线程运行，此处仅阻塞等待
        //   runner.awaitCompletion()（CountDownLatch，无竞态读 BashResult）。
        executor.submit(() -> completeForegroundBackgroundedTask(taskId, watchdog));

        log.info("BackgroundTaskRunner.backgroundExistingForegroundTask: task {} 已就地转后台 (G1-2, LocalShellTask.tsx:420-474)",
            taskId);
        return true;
    }

    /**
     * G1-2: 注销前台任务（未转后台直接完成）· 对齐 CC unregisterForeground
     * （LocalShellTask.tsx:491-514）。
     *
     * <p>CC 语义：仅当任务仍为前台（{@code isLocalShellTask && !isBackgrounded}）才从
     * {@code state.tasks} 移除（:496-509），并调 cleanup（:513）。已后台化任务不在此注销
     * （由 async 生命周期完成 watcher 收尾）。
     *
     * @param taskId 前台 bash 任务 id
     * @return true 实际注销（前台任务）；false 不存在 / 已后台化 / 非 local_bash
     */
    public boolean unregisterForeground(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null || !LocalShellTaskGuards.isLocalShellTask(current) || current.isBackgrounded()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.unregisterForeground: task {} 不存在/已后台化/非 local_bash, 不注销",
                    taskId);
            }
            return false;
        }
        tasks.remove(taskId);
        // CC unregisterForeground 从 state.tasks 裸移除（LocalShellTask.tsx:496-509）；
        //   Java 双 store 须同步移除，避免 store 滞留 RUNNING。
        frameworkService.removeTask(taskId);
        runnerMap.remove(taskId);
        log.info("BackgroundTaskRunner.unregisterForeground: task {} 前台任务已注销 (G1-2, LocalShellTask.tsx:491-514)",
            taskId);
        return true;
    }

    /**
     * [BUG2-EVENT 同款] 前台 bash 任务完成注销前补发终态事件 · 对齐 spawn 完成 emitTerminatedSdk。
     *
     * <p><b>WHY</b>：前端「子代理运行状况」靠 STOMP 事件流收尾——registerForeground →
     * frameworkService.registerTask 发 task_started（task_type=local_bash）→ 前端 register 身份
     * status='running'（显示"进行中"）；但 unregisterForeground 只 removeTask + removeStore、
     * <b>零终态事件</b> → 前端永远收不到 done/failed/stopped → status 滞留 running，已完成命令
     * 一直显示"进行中"。本方法在注销前把任务推进终态快照 + emitTerminatedSdk（结构化
     * task.notification → 前端 addActivity 收尾移出"进行中"），随后 unregisterForeground 移除
     * store（GET /tasks 不再返回，双通道一致）。
     *
     * <p>调用方（BashTool）在 unregisterForeground 前调用：自然完成 exitCode==0 → COMPLETED 否则
     * FAILED；中断 → KILLED；超时 kill → FAILED。KILLED 守卫由 emitTerminatedSdk 状态映射兜底。
     *
     * @param taskId 前台 bash 任务 id（registerForeground 返回值）
     * @param status 终态（COMPLETED / FAILED / KILLED）
     */
    public void emitForegroundTerminal(String taskId, BackgroundTaskStatus status) {
        if (taskId == null || status == null || !status.isTerminal()) {
            return;
        }
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            return;
        }
        BackgroundTask terminal = current
            .withStatus(status)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(taskId, terminal);
        emitTerminatedSdk(terminal);
        log.info("BackgroundTaskRunner.emitForegroundTerminal: task {} 补发终态事件 status={}（G1-2 前台完成）",
            taskId, status.getStatusString());
    }

    /**
     * 新建已启动的 StallWatchdog · 前台 → 后台任务用（对齐 CC startStallWatchdog，
     * LocalShellTask.tsx:46-104）。stall 时读取输出尾部入队 advisory 通知（对齐 spawn 同款路径）。
     *
     * @param task 后台化后的任务
     * @return 未启动的 StallWatchdog（调用方 start()）
     */
    private StallWatchdog newStallWatchdog(BackgroundTask task) {
        return new StallWatchdog(
            task.id(), task.outputFile(),
            StallWatchdog.CC_STALL_THRESHOLD_MS, StallWatchdog.CC_STALL_CHECK_INTERVAL_MS,
            () -> {
                log.warn("BackgroundTaskRunner: stall detected for backgrounded foreground task {}, enqueuing advisory",
                    task.id());
                String tail = StallWatchdog.readTailForTest(task.outputFile());
                String xml = TaskNotificationBuilder.buildStallNotification(
                    task.id(), task.description(), tail);
                notificationQueue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(xml, "task-notification",
                        NotificationQueue.Priority.NEXT, null, null, false, null, false, null,
                        task.sessionId()));
            });
    }

    /**
     * G1-2: 后台化前台任务的完成 watcher · 对齐 CC backgroundExistingForegroundTask 的
     * {@code shellCommand.result.then}（LocalShellTask.tsx:445-472）。
     *
     * <p>阻塞等待 {@link LocalBashTaskRunner#awaitCompletion()}（前台进程结束）→ 读取
     * {@code getLastResult()} → KILLED 守卫短路（对齐 CC :451 wasKilled 守卫）→ 推进终态
     * （completed/failed + exitCode + endTime + notified）→ enqueue shell 通知 + 终态 SDK →
     * finally 停 StallWatchdog（对齐 CC :446 cancelStallWatchdog）。
     */
    private void completeForegroundBackgroundedTask(String taskId, StallWatchdog watchdog) {
        try {
            LocalBashTaskRunner runner = runnerMap.get(taskId);
            if (runner == null) {
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner.completeForegroundBackgroundedTask: task {} runner 已移除, 跳过",
                        taskId);
                }
                return;
            }
            runner.awaitCompletion();
            LocalBashTaskRunner.BashResult result = runner.getLastResult();

            // 对齐 CC :451-454 —— cancel/kill 已标 KILLED 时不覆盖终态
            BackgroundTask current = tasks.get(taskId);
            if (current == null) {
                return;
            }
            if (current.status() == BackgroundTaskStatus.KILLED) {
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner.completeForegroundBackgroundedTask: task {} 已 KILLED, 跳过结果处理",
                        taskId);
                }
                return;
            }

            int exitCode = result != null ? result.exitCode() : -1;
            BackgroundTask completed = (result != null
                ? current.withStatus(result.exitCode() == 0 ? BackgroundTaskStatus.COMPLETED : BackgroundTaskStatus.FAILED)
                    .withExitCode(result.exitCode())
                : current.withStatus(BackgroundTaskStatus.FAILED))
                .withEndTime(System.currentTimeMillis())
                .withNotified();
            tasks.put(taskId, completed);
            frameworkService.updateTaskState(taskId, completed);

            // 对齐 CC :470 enqueueShellNotification（size-watchdog kill 消息并入 detail）
            String xml = TaskNotificationBuilder.buildEnqueueShellNotification(completed, exitCode,
                result != null ? BackgroundTaskRunner.sizeWatchdogKillNote(result) : null);
            notificationQueue.enqueuePendingNotification(
                new NotificationQueue.QueueItem(xml, "task-notification",
                    null, null, null, false, null, false, null, completed.sessionId()));
            emitTerminatedSdk(completed);

            log.info("BackgroundTaskRunner.completeForegroundBackgroundedTask: task {} 完成 exitCode={} (G1-2)",
                taskId, exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断路径：不覆盖终态（保持现状，进程状态由 cancel 处理）
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.completeForegroundBackgroundedTask: task {} 等待中断", taskId);
            }
        } catch (Exception e) {
            // 对齐 CC result.then 的异常兜底 —— 非 KILLED 时标记 FAILED（防任务滞留 RUNNING）
            BackgroundTask current = tasks.get(taskId);
            if (current != null && current.status() != BackgroundTaskStatus.KILLED) {
                BackgroundTask failed = current
                    .withStatus(BackgroundTaskStatus.FAILED)
                    .withEndTime(System.currentTimeMillis())
                    .withNotified();
                tasks.put(taskId, failed);
                frameworkService.updateTaskState(taskId, failed);
                String xml = TaskNotificationBuilder.buildEnqueueShellNotification(failed, -1);
                notificationQueue.enqueuePendingNotification(
                    new NotificationQueue.QueueItem(xml, "task-notification",
                        null, null, null, false, null, false, null, failed.sessionId()));
                emitTerminatedSdk(failed);
                log.error("BackgroundTaskRunner.completeForegroundBackgroundedTask: task {} 失败: {}",
                    taskId, e.getMessage());
            }
        } finally {
            watchdog.stop(); // 对齐 CC :446 cancelStallWatchdog —— 防 leak
            foregroundWatchdogs.remove(taskId);
        }
    }

    /**
     * OPD-TP-21: 取 task-scoped AbortController · 对齐 CC taskState.abortController
     * (LocalAgentTask.tsx:123)。由 {@link #registerAsyncAgent} 创建并保存，kill 时
     * {@link #killAsyncAgent} 经 {@code abort()} 直接中断 worker。
     *
     * @param taskId task id (= agentId.toString())
     * @return 该任务的 AbortController；非 agent 任务/未注册/已终态清理后为 null
     */
    @Nullable
    public AbortController taskAbortController(String taskId) {
        return taskAbortControllers.get(taskId);
    }

    /**
     * Phase 3: 杀死 async agent task · 原子 update + only-if-running 守卫 · 对齐 CC stopTask.
     *
     * <p>幂等: 重复调用 (running→KILLED 后再次调用) 应返回 false, 不重复 enqueue 通知.
     *
     * <p>[S4-1 残差 ②] 通知承载 killed 部分结果 (CC runAsyncAgentLifecycle :659-667 killed 通知
     * finalMessage=partialResult, extractPartialResult :658) — 委托 2 参重载, result=null
     * (TaskStopTool 裸 kill 无结果上下文).
     *
     * @param taskId task id (= agentId.toString())
     * @return true 实际执行了 kill; false task 不存在 / 已非 running / 已 KILLED
     */
    public boolean killAsyncAgent(String taskId) {
        return killAsyncAgent(taskId, null);
    }

    /**
     * Phase 3: 杀死 async agent task（携带部分结果）· 对齐 CC runAsyncAgentLifecycle AbortError
     * 路径 (agentToolUtils.ts:640-668 killAsyncAgent + extractPartialResult).
     *
     * <p>通知经 {@link TaskNotificationBuilder#buildEnqueueAgentNotification} agent 格式:
     * summary {@code Agent "..." was stopped} + {@code <result>} 段承载 partialResult
     * (CC LocalAgentTask.tsx:249 resultSection; killed 无 usage 段, CC :250 仅 completed).
     *
     * <p><b>OPD-TP-21</b>: 标 KILLED 后调用 task-scoped {@code abortController.abort()} —
     * 对齐 CC LocalAgentTask.tsx:288 {@code task.abortController?.abort()} 直接中断 worker
     * 查询循环（SubagentExecutor.java:2553/2669-2675 onCancel → state.cancel → "aborted" 结果
     * → finalizeKilled 链）。随后清注册表（对齐 CC :295 kill 后 {@code abortController: undefined}）。
     * abort() 幂等（AbortController CAS）——worker 自身 AbortError 路径再调 kill 无副作用。
     *
     * @param taskId task id (= agentId.toString())
     * @param result killed 结果 (summary = 部分结果文本; null = TaskStopTool 裸 kill 路径)
     * @return true 实际执行了 kill; false task 不存在 / 已非 running / 已 KILLED
     */
    public boolean killAsyncAgent(String taskId, AsyncAgentResult result) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killAsyncAgent: task {} 不存在", taskId);
            }
            return false;
        }
        // only-if-running 守卫 (CC stopTask 等价)
        if (current.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killAsyncAgent: task {} status={} 非 running, 跳过",
                    taskId, current.status().getStatusString());
            }
            return false;
        }
        BackgroundTask killed = current
            .withStatus(BackgroundTaskStatus.KILLED)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(taskId, killed);
        frameworkService.updateTaskState(taskId, killed);

        // OPD-TP-21: abort task-scoped controller 直接中断 worker（CC LocalAgentTask.tsx:288）。
        //   先 abort（worker 端 onCancel 同步触发）再清注册表（CC :295 abortController: undefined）。
        AbortController taskAbort = taskAbortControllers.remove(taskId);
        if (taskAbort != null) {
            taskAbort.abort();
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killAsyncAgent: task {} abortController 已 abort", taskId);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killAsyncAgent: task {} 无 abortController 注册表项（可能已清理）", taskId);
            }
        }

        // [S4-1 残差 ②] killed 通知 agent 格式 + <result> 部分结果 (CC agentToolUtils.ts:659-667)
        //   [FORK-02] 透传保留 worktree (SubagentExecutor Step 21.0 登记, CC getWorktreeResult)
        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(
            killed, result, killed.worktreePath(), killed.worktreeBranch());
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml, "task-notification",
                null, null, null, false, null, false, null, killed.sessionId()));

        emitTerminatedSdk(killed);

        log.info("BackgroundTaskRunner: async agent task {} killed (partialLen={})",
            taskId, result != null && result.summary() != null ? result.summary().length() : 0);
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // W-4b: local_workflow 任务生命周期（对齐 CC LocalWorkflowTask.tsx:53-216
    //   registerLocalWorkflowTask / completeWorkflowTask / failWorkflowTask /
    //   killWorkflowTask + LocalWorkflowTask.kill 委托）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 注册 local_workflow 任务 · CC original: {@code registerLocalWorkflowTask}
     * (LocalWorkflowTask.tsx:53-83) + {@code taskRegistrar.register}（ports.ts:98-105）。
     *
     * <p>构造 {@code BackgroundTask(taskId, LOCAL_WORKFLOW, RUNNING, description, ...)}
     * → 入本地 {@link #tasks} map（stopTask 分发用）→ 存 task-scoped AbortController
     * （kill 时 abort 中断引擎 signal，对齐 CC :79 abortController）→
     * {@link TaskFrameworkService#registerTask}（对齐 CC registerTask task 入 state.tasks）。
     *
     * <p>字段对齐（LocalWorkflowTask.tsx:66-80）：
     * <ul>
     *   <li>id = taskId（'w-' 前缀，generateTaskId 生成）</li>
     *   <li>type = LOCAL_WORKFLOW / status = RUNNING / startTime = now</li>
     *   <li>description = CC opts.description（= summary ?? workflowName，ports.ts:99）</li>
     *   <li>toolUseId 透传（CC createTaskStateBase 第三参）</li>
     *   <li>outputFile = {@link #taskOutputPath(taskId)}（CC Task.ts:121 getTaskOutputPath）</li>
     *   <li>isBackgrounded=true（后台任务，非前台）</li>
     * </ul>
     *
     * @param taskId           task id（WorkflowPortsImpl generateTaskId 产出 'w-...'）
     * @param description      任务描述（CC description = summary ?? workflowName）
     * @param workflowName     CC original: workflowName（meta.name，workflow 脚本名）
     * @param abortController  task-scoped AbortController（CC :79，kill 时 abort）
     * @param toolUseId        CC original: toolUseId（可空）
     * @param createSessionId  创建会话 sessionId（cron-notify 透传，可空 → 回落全局）
     * @return 已注册的 BackgroundTask
     */
    public BackgroundTask registerWorkflowTask(
            String taskId, String description, String workflowName,
            @Nullable AbortController abortController, @Nullable String toolUseId,
            @Nullable String createSessionId) {
        // CC Task.ts:121 outputFile: getTaskOutputPath(id)
        String outputFile = taskOutputPath(taskId);
        // [IMP-G] G25① BackgroundTask 新增 exitCode/error/prompt/result 4 字段（TaskOutput 跟踪）——
        //   local_workflow 无进程 exit code / prompt（脚本已入内存），失败原因走 error（CC LocalWorkflowTaskState.error）。
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_WORKFLOW, BackgroundTaskStatus.RUNNING,
            description, toolUseId, System.currentTimeMillis(), null, null,
            outputFile, 0L, false, null, true, createSessionId,
            null, null, null, null, null, null);
        tasks.put(taskId, task);
        if (abortController != null) {
            taskAbortControllers.put(taskId, abortController);
        }
        frameworkService.registerTask(task);
        log.info("BackgroundTaskRunner: local_workflow task 已注册 taskId={} workflowName={} (W-4b registerLocalWorkflowTask)",
            taskId, workflowName);
        return task;
    }

    /** 完成 local_workflow 任务 · CC original: {@code completeWorkflowTask} (LocalWorkflowTask.tsx:85-96)。 */
    public void completeWorkflowTask(String taskId) {
        transitionWorkflowTask(taskId, BackgroundTaskStatus.COMPLETED);
    }

    /** 失败 local_workflow 任务 · CC original: {@code failWorkflowTask} (LocalWorkflowTask.tsx:98-111)。 */
    public void failWorkflowTask(String taskId) {
        transitionWorkflowTask(taskId, BackgroundTaskStatus.FAILED);
    }

    /**
     * complete/fail 共用终态推进 · CC original: {@code completeWorkflowTask} /
     * {@code failWorkflowTask}（LocalWorkflowTask.tsx:89-95/:103-109）。
     *
     * <p>对齐 CC updater：{@code status=completed|failed, endTime=Date.now(), notified=true,
     * abortController: undefined}（:94-95/:107-108，清 taskAbortControllers 注册表）。
     * CC complete/fail <b>不做 only-if-running 守卫</b>（与 killWorkflowTask :122 不同），
     * 但调用方（ports.ts taskRegistrar）先按 runId 查 binding —— kill 后 binding 已删除，
     * 后续 complete/fail 在 WorkflowPortsImpl 层 no-op（对齐 CC ports.ts:147 bindings.delete）。
     *
     * @param taskId         task id
     * @param terminalStatus 终态（COMPLETED / FAILED）
     */
    private void transitionWorkflowTask(String taskId, BackgroundTaskStatus terminalStatus) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.transitionWorkflowTask: task {} 不存在, no-op", taskId);
            }
            return;
        }
        BackgroundTask terminal = current
            .withStatus(terminalStatus)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(taskId, terminal);
        frameworkService.updateTaskState(taskId, terminal);
        // CC LocalWorkflowTask.tsx:95/:108 abortController: undefined → 清注册表
        taskAbortControllers.remove(taskId);
        emitTerminatedSdk(terminal);
        log.info("BackgroundTaskRunner.transitionWorkflowTask: local_workflow task {} 推进到 {} (W-4b)",
            taskId, terminalStatus.getStatusString());
    }

    /**
     * kill local_workflow 任务 · CC original: {@code killWorkflowTask} (LocalWorkflowTask.tsx:117-132)。
     *
     * <p>only-if-running 守卫（:122 {@code if (task.status !== 'running') return task}）：
     * 非 running 不推进、不 abort、不发 SDK。running → abort task-scoped controller
     * （:123 {@code task.abortController?.abort()}，中断引擎 signal）→ status=killed +
     * endTime + notified + abortController undefined（:124-130）→ 终态 SDK 事件。
     *
     * @param taskId task id
     * @return true 实际执行 kill；false 任务不存在 / 已非 running
     */
    public boolean killWorkflowTask(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killWorkflowTask: task {} 不存在", taskId);
            }
            return false;
        }
        if (current.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killWorkflowTask: task {} status={} 非 running, 跳过 (LocalWorkflowTask.tsx:122)",
                    taskId, current.status().getStatusString());
            }
            return false;
        }
        BackgroundTask killed = current
            .withStatus(BackgroundTaskStatus.KILLED)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        tasks.put(taskId, killed);
        frameworkService.updateTaskState(taskId, killed);
        // LocalWorkflowTask.tsx:123 abortController?.abort() —— 中断引擎 signal
        AbortController taskAbort = taskAbortControllers.remove(taskId);
        if (taskAbort != null) {
            taskAbort.abort();
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killWorkflowTask: task {} abortController 已 abort", taskId);
            }
        }
        emitTerminatedSdk(killed);
        log.info("BackgroundTaskRunner.killWorkflowTask: local_workflow task {} killed (W-4b, LocalWorkflowTask.tsx:117-132)",
            taskId);
        return true;
    }

    /**
     * 停止任务错误码 · 对齐 CC StopTaskError code (stopTask.ts:10-18).
     */
    public enum StopTaskErrorCode {
        /** CC 'not_found' — 任务不存在 (stopTask.ts:46-48) */
        NOT_FOUND,
        /** CC 'not_running' — 任务非 running (stopTask.ts:50-55) */
        NOT_RUNNING,
        /** CC 'unsupported_type' — 类型无 kill 实现 (stopTask.ts:57-63) */
        UNSUPPORTED_TYPE
    }

    /**
     * 停止结果 · 对齐 CC StopTaskResult (stopTask.ts:25-29).
     *
     * @param taskId    任务 id
     * @param taskType  实际任务类型串 (CC task.type → TaskType.getTypeString)
     * @param command   bash 承载 command / agent 承载 description (CC stopTask.ts:97)
     * @param errorCode 失败原因; null 表示成功
     */
    public record StopTaskResult(
            String taskId,
            String taskType,
            String command,
            @Nullable StopTaskErrorCode errorCode) {
        public boolean ok() {
            return errorCode == null;
        }
    }

    /**
     * 停止后台任务 · 按 task.type() 分发 · 对齐 CC stopTask.ts:38-100
     * （stopTask → getTaskByType → taskImpl.kill）。
     *
     * <p>分发映射（CC getTaskByType :37-47 + 各 Task.kill）：
     * <ul>
     *   <li>{@code LOCAL_BASH} → {@code cancel()}（CC LocalShellTask.kill → killTask
     *       killShellTasks.ts:16-46：only-if-running + isLocalShellTask 守卫 → shellCommand.kill()
     *       杀子进程 → status=killed + notified=true + endTime）。cancel() 内置 killProcess +
     *       KILLED + withNotified（抑制后续通知）+ emitTerminatedSdk('stopped')（CC stopTask.ts:70-95）。</li>
     *   <li>{@code LOCAL_AGENT} → {@code killAsyncAgent}（CC LocalAgentTask.kill → killAsyncAgent
     *       LocalAgentTask.tsx:273-303：only-if-running 原子守卫 + status=killed）。</li>
     *   <li>{@code DREAM} → {@link DreamTaskRegistry#kill}（CC DreamTask.kill → abort + priorMtime
     *       回滚锁；OPD-TP-09）。dream 任务不经 spawn → 本地 tasks 查不到，经
     *       {@link #stopDreamTask} 回退注册表分发。</li>
     *   <li>其余 type → {@code UNSUPPORTED_TYPE}（CC getTaskByType 返回 undefined → StopTaskError
     *       'unsupported_type'）。Java 仅 LOCAL_BASH/LOCAL_AGENT/DREAM 有真实 kill 实现。</li>
     *   <li>{@code REMOTE_AGENT} → {@link RemoteAgentTaskService#kill}（CC RemoteAgentTask.kill
     *       RemoteAgentTask.tsx:811-847：only-if-running → killed+notified+endTime →
     *       emitTaskTerminatedSdk('stopped') → archiveRemoteSession → evict → 删 sidecar）。remote
     *       任务不经 spawn → 本地 tasks 查不到，经 {@link #stopRemoteAgentTask} 回退注册表分发。</li>
     *   <li>其余 type → {@code UNSUPPORTED_TYPE}（CC getTaskByType 返回 undefined → StopTaskError
     *       'unsupported_type'）。Java 仅 LOCAL_BASH/LOCAL_AGENT/REMOTE_AGENT 有真实 kill 实现。</li>
     * </ul>
     *
     * <p>WHY（R1 孤儿进程）：旧 TaskStopTool 无条件先 killAsyncAgent（TaskStopTool.java:158），
     * bash 任务被标 KILLED 但 killAsyncAgent 不碰子进程（无 runnerMap 操作）→ bash 子进程孤儿。
     * 本方法先查 type 再分发，bash 必然走 cancel() → killProcess 杀子进程。
     *
     * @param taskId 任务 id（bash=TaskIdGenerator 生成；agent=agentId.toString()）
     * @return 停止结果（ok()=true 成功；false 时 errorCode 指示失败原因）
     */
    public StopTaskResult stopTask(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) {
            // dream 任务不经 spawn（注册在 TaskFrameworkService store + DreamTaskRegistry store）→
            // 本地 tasks 查不到，回退 DreamTaskRegistry（OPD-TP-09 getTaskByType('dream') 分发）
            StopTaskResult dreamResult = stopDreamTask(taskId);
            if (dreamResult != null) {
                return dreamResult;
            }
            // remote 任务不经 spawn（注册在 RemoteAgentTaskService.remoteTasks 权威 map + framework
            // store）→ 本地 tasks 查不到，回退 RemoteAgentTaskService（getTaskByType('remote_agent')）
            StopTaskResult remoteResult = stopRemoteAgentTask(taskId);
            if (remoteResult != null) {
                return remoteResult;
            }
            // OPD-TS-25: monitor 任务注册在统一 store（MonitorMcpTaskRunner.registerTask）而非本地
            // tasks 地图 → 回退 store 查 + MonitorMcpTaskRunner.stop() 分发（getTaskByType('monitor_mcp')）
            StopTaskResult monitorResult = stopMonitorMcpTask(taskId);
            if (monitorResult != null) {
                return monitorResult;
            }
            // [IMP-G3] in_process_teammate 任务注册在统一 store（InProcessTeammateTaskRegistry.registerTask
            // → TaskFrameworkService）而非本地 tasks 地图 → 回退 store 查 + registry.kill() 分发
            // （对齐 CC stopTask.ts:57-65 getTaskByType('in_process_teammate').kill →
            // spawnInProcess.ts:227-328 killInProcessTeammate）。TaskStopTool 不再保留独立 registry
            // 分支（CC TaskStopTool.ts 无该概念，纯委托 stopTask）。
            StopTaskResult teammateResult = stopInProcessTeammateTask(taskId);
            if (teammateResult != null) {
                return teammateResult;
            }
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: task {} 不存在 (not_found)", taskId);
            }
            return new StopTaskResult(taskId, null, null, StopTaskErrorCode.NOT_FOUND);
        }
        String taskType = task.type().getTypeString();
        String command = task.description();
        if (task.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: task {} status={} 非 running (not_running)",
                    taskId, task.status().getStatusString());
            }
            return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.NOT_RUNNING);
        }
        if (LocalShellTaskGuards.isLocalShellTask(task)) {
            // CC stopTask.ts:65 LocalShellTask.kill → killTask（killShellTasks.ts:16-46）
            // cancel() 内置：killProcess 杀子进程 + KILLED + notified=true + emitTerminatedSdk('stopped')
            boolean cancelled = cancel(taskId);
            log.info("BackgroundTaskRunner.stopTask: bash task {} cancelled={} (killProcess)", taskId, cancelled);
        } else if (task.type() == TaskType.LOCAL_AGENT) {
            // CC stopTask.ts:65 LocalAgentTask.kill → killAsyncAgent（LocalAgentTask.tsx:281-303）
            boolean killed = killAsyncAgent(taskId, null);
            log.info("BackgroundTaskRunner.stopTask: local_agent task {} killed={} (killAsyncAgent)", taskId, killed);
        } else if (task.type() == TaskType.DREAM) {
            // OPD-TP-09: DreamTask.kill（DreamTask.ts:132-156）—— 防御分支（dream 任务通常不在
            // 本地 tasks，走上方 stopDreamTask 回退；此处兜底 tasks 内含 dream 的情形）
            boolean killed = dreamTaskRegistry != null && dreamTaskRegistry.kill(taskId);
            if (!killed) {
                return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.NOT_RUNNING);
            }
            log.info("BackgroundTaskRunner.stopTask: dream task {} killed (DreamTask.kill)", taskId);
        } else if (task.type() == TaskType.REMOTE_AGENT) {
            // CC getTaskByType('remote_agent') → RemoteAgentTask.kill（RemoteAgentTask.tsx:811-847）
            // 防御分支（remote 任务通常不在本地 tasks，走上方 stopRemoteAgentTask 回退）
            boolean killed = remoteAgentTaskService != null && remoteAgentTaskService.kill(taskId);
            if (!killed) {
                return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.NOT_RUNNING);
            }
            log.info("BackgroundTaskRunner.stopTask: remote_agent task {} killed (RemoteAgentTask.kill)", taskId);
        } else if (task.type() == TaskType.MONITOR_MCP) {
            // OPD-TS-25: MonitorMcpTask.kill 等价（CC stopTask.ts:65 → MonitorMcpTaskRunner.stop()，
            // 流式循环退出 → killed + notified + SDK 'stopped'）。防御分支——monitor 任务通常不在本地
            // tasks 地图（registerTask 落统一 store），走上方 stopMonitorMcpTask 回退。
            if (monitorMcpTaskRunner == null) {
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner.stopTask: monitor task {} runner 未装配 (unsupported_type)",
                        taskId);
                }
                return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.UNSUPPORTED_TYPE);
            }
            monitorMcpTaskRunner.stop();
            log.info("BackgroundTaskRunner.stopTask: monitor_mcp task {} stop requested (MonitorMcpTaskRunner.stop → 流退出 killed + SDK stopped)",
                taskId);
        } else if (task.type() == TaskType.LOCAL_WORKFLOW) {
            // W-4b: getTaskByType('local_workflow') → LocalWorkflowTask.kill → killWorkflowTask
            //（LocalWorkflowTask.tsx:210-216 kill 委托，对齐 CC stopTask.ts:57-63 分发）。
            // local_workflow 任务经 registerWorkflowTask 入本地 tasks map（不经 spawn），
            // 故在上方 NOT_FOUND 回退之前命中此处（与 LOCAL_AGENT 同型分发）。
            boolean killed = killWorkflowTask(taskId);
            log.info("BackgroundTaskRunner.stopTask: local_workflow task {} killed={} (killWorkflowTask → LocalWorkflowTask.kill)",
                taskId, killed);
        } else {
            // CC stopTask.ts:57-63 getTaskByType → undefined → StopTaskError('unsupported_type')
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: task {} type {} unsupported (unsupported_type)",
                    taskId, taskType);
            }
            return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.UNSUPPORTED_TYPE);
        }
        return new StopTaskResult(taskId, taskType, command, null);
    }

    /**
     * dream 任务停止 · 对齐 CC stopTask.ts:38-65（getTaskByType('dream') → DreamTask.kill）。
     *
     * <p>CC stopTask 先查 appState.tasks（Java 双存储 dream 不在本地 tasks）→ status 非 running
     * → not_running；running → taskImpl.kill（DreamTask.kill 内置 only-if-running 守卫）。
     *
     * @param taskId dream 任务 id
     * @return 非 dream 任务 → null（交由正常 not_found 路径）；dream 任务 → 停止结果
     */
    private StopTaskResult stopDreamTask(String taskId) {
        if (dreamTaskRegistry == null) {
            return null;
        }
        DreamTaskState dream = dreamTaskRegistry.getDreamTask(taskId).orElse(null);
        if (dream == null) {
            return null;
        }
        String command = dream.description();
        if (dream.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: dream task {} status={} 非 running (not_running)",
                    taskId, dream.status().getStatusString());
            }
            return new StopTaskResult(taskId, TaskType.DREAM.getTypeString(), command,
                StopTaskErrorCode.NOT_RUNNING);
        }
        boolean killed = dreamTaskRegistry.kill(taskId);
        if (!killed) {
            return new StopTaskResult(taskId, TaskType.DREAM.getTypeString(), command,
                StopTaskErrorCode.NOT_RUNNING);
        }
        log.info("BackgroundTaskRunner.stopTask: dream task {} killed (DreamTask.kill taskId={})", taskId, taskId);
        return new StopTaskResult(taskId, TaskType.DREAM.getTypeString(), command, null);
    }

    /**
     * remote_agent 任务停止 · 对齐 CC stopTask.ts:38-65（getTaskByType('remote_agent') →
     * RemoteAgentTask.kill RemoteAgentTask.tsx:811-847）。
     *
     * <p>CC stopTask 先查 appState.tasks（Java 双存储 remote 不在本地 tasks）→ status 非 running
     * → not_running；running → taskImpl.kill（RemoteAgentTask.kill 内置 only-if-running 守卫：
     * killed+notified+endTime + emitTaskTerminatedSdk('stopped') + archiveRemoteSession +
     * evictTaskOutput + removeRemoteAgentMetadata）。
     *
     * @param taskId remote_agent 任务 id（TaskIdGenerator r 前缀）
     * @return 非 remote_agent 任务 → null（交由正常 not_found 路径）；remote_agent 任务 → 停止结果
     */
    private StopTaskResult stopRemoteAgentTask(String taskId) {
        if (remoteAgentTaskService == null) {
            return null;
        }
        com.nexusai.application.agent.remote.RemoteAgentTaskState remote = remoteAgentTaskService.findTask(taskId);
        if (remote == null) {
            return null;
        }
        String taskType = TaskType.REMOTE_AGENT.getTypeString();
        String command = remote.description();
        if (remote.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: remote task {} status={} 非 running (not_running)",
                    taskId, remote.status().getStatusString());
            }
            return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.NOT_RUNNING);
        }
        boolean killed = remoteAgentTaskService.kill(taskId);
        if (!killed) {
            return new StopTaskResult(taskId, taskType, command, StopTaskErrorCode.NOT_RUNNING);
        }
        log.info("BackgroundTaskRunner.stopTask: remote_agent task {} killed (RemoteAgentTask.kill taskId={})",
            taskId, taskId);
        return new StopTaskResult(taskId, taskType, command, null);
    }

    /**
     * monitor_mcp 任务停止 · 对齐 CC stopTask.ts:38-65（getTaskByType('monitor_mcp') →
     * MonitorMcpTask.kill，tasks.ts:37-39）。
     *
     * <p>monitor 任务不经 {@link #spawn}（registerTask 落统一 store，MonitorMcpTaskRunner.java:99-102）
     * → 本地 tasks 地图查不到，回退统一 store 查 + {@link MonitorMcpTaskRunner#stop()} 分发
     * （mirror {@link #stopDreamTask}）。kill 语义：stop() 置 stopped + 中断轮询线程 → monitor()
     * 流式循环退出按 CC LocalShellTask.tsx:142 流转 killed + notified + SDK 'stopped'
     * （MonitorMcpTaskRunner.transitionTerminal）。
     *
     * @param taskId monitor 任务 id
     * @return 非 monitor_mcp 任务 / runner 未装配 → null（交由正常 not_found / 其他分发路径）；
     *         monitor 任务 → 停止结果
     */
    private StopTaskResult stopMonitorMcpTask(String taskId) {
        if (monitorMcpTaskRunner == null || frameworkService == null) {
            return null;
        }
        BackgroundTask task = frameworkService.getTask(taskId).orElse(null);
        if (task == null || task.type() != TaskType.MONITOR_MCP) {
            return null;
        }
        String command = task.description();
        if (task.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: monitor task {} status={} 非 running (not_running)",
                    taskId, task.status().getStatusString());
            }
            return new StopTaskResult(taskId, TaskType.MONITOR_MCP.getTypeString(), command,
                StopTaskErrorCode.NOT_RUNNING);
        }
        monitorMcpTaskRunner.stop();
        log.info("BackgroundTaskRunner.stopTask: monitor_mcp task {} stop requested (MonitorMcpTaskRunner.stop)",
            taskId);
        return new StopTaskResult(taskId, TaskType.MONITOR_MCP.getTypeString(), command, null);
    }

    /**
     * in_process_teammate 任务停止 · 对齐 CC stopTask.ts:38-65（getTaskByType('in_process_teammate') →
     * InProcessTeammateTask.kill → spawnInProcess.ts:227-328 killInProcessTeammate）。
     *
     * <p>teammate 任务不经 {@link #spawn}（registerTask 落统一 store，InProcessTeammateTaskRegistry.register
     * → TaskFrameworkService）→ 本地 tasks 地图查不到，回退统一 store 查 + registry.kill() 分发
     * （mirror {@link #stopMonitorMcpTask}）。kill 语义：AutonomousAgentLoop.kill() →
     * abort 生命周期控制器 + killed 状态转换 + evict/SDK 链（对齐 killInProcessTeammate）。
     *
     * @param taskId teammate 任务 id
     * @return 非 in_process_teammate 任务 / 注册表未装配 → null（交由正常 not_found / 其他分发路径）；
     *         teammate 任务 → 停止结果
     */
    private StopTaskResult stopInProcessTeammateTask(String taskId) {
        if (spawnInProcess == null || frameworkService == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: teammate 注册表未装配, 跳过 in_process_teammate 分发 task={}",
                    taskId);
            }
            return null;
        }
        BackgroundTask task = frameworkService.getTask(taskId).orElse(null);
        if (task == null || task.type() != TaskType.IN_PROCESS_TEAMMATE) {
            return null;
        }
        String command = task.description();
        if (task.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.stopTask: teammate task {} status={} 非 running (not_running)",
                    taskId, task.status().getStatusString());
            }
            return new StopTaskResult(taskId, TaskType.IN_PROCESS_TEAMMATE.getTypeString(), command,
                StopTaskErrorCode.NOT_RUNNING);
        }
        boolean killed = spawnInProcess.registry().kill(taskId);
        if (!killed) {
            return new StopTaskResult(taskId, TaskType.IN_PROCESS_TEAMMATE.getTypeString(), command,
                StopTaskErrorCode.NOT_RUNNING);
        }
        log.info("BackgroundTaskRunner.stopTask: in_process_teammate task {} killed (killInProcessTeammate taskId={})",
            taskId, taskId);
        return new StopTaskResult(taskId, TaskType.IN_PROCESS_TEAMMATE.getTypeString(), command, null);
    }

    /**
     * Phase 3: owner-scoped 批 kill · 对齐 CC killShellTasksForAgent (killShellTasks.ts:53-76).
     *
     * <p>遍历所有 running task, 仅终止 agentId 匹配 + LOCAL_BASH 类型.
     * agentId=null 的 task (main-thread spawn) 不属于任何 agent, 不应被杀.
     *
     * <p>WHY: 之前 LocalShellTaskKiller.killShellTasksForAgent 无 agentId 守卫 (注释 74-75
     * 行明确), 会"全杀"所有 LOCAL_BASH. Phase 3 通过 BackgroundTask.agentId 字段做
     * owner-scoped 过滤, 对齐 CC fail-closed 语义.
     *
     * @param agentId 拥有待终止 task 的 sub-agent UUID
     * @return 实际终止的 task 数
     */
    public int killShellTasksForAgent(UUID agentId) {
        if (agentId == null) {
            log.debug("BackgroundTaskRunner.killShellTasksForAgent: agentId=null, 跳过");
            return 0;
        }
        int killed = 0;
        List<String> toKill = new ArrayList<>();
        // 第一遍: 收集应杀的 taskId (避免 ConcurrentModification)
        for (BackgroundTask task : tasks.values()) {
            if (task.type() != TaskType.LOCAL_BASH) continue;
            if (task.status() != BackgroundTaskStatus.RUNNING) continue;
            if (task.agentId() == null) continue;
            if (!agentId.equals(task.agentId())) continue;
            toKill.add(task.id());
        }
        // 第二遍: 实际调 cancel (复用 cancel 的 KILLED + notification + destroyForcibly)
        for (String taskId : toKill) {
            if (cancel(taskId)) {
                killed++;
                log.info("BackgroundTaskRunner: orphaned bash task {} killed for agent {}",
                    taskId, agentId);
            }
        }
        log.info("BackgroundTaskRunner.killShellTasksForAgent: agent={} 终止 {} 个 task",
            agentId, killed);
        return killed;
    }

    /**
     * Phase 3: owner-scoped 批 kill monitor 任务 · 对齐 CC runAgent.ts:852-861
     * killMonitorMcpTasksForAgent（feature('MONITOR_TOOL') → MonitorMcpTask module）。
     *
     * <p>遍历统一 store 中所有 running monitor_mcp task, 仅终止 agentId 匹配者（monitor 任务注册于
     * TaskFrameworkService store，MonitorMcpTaskRunner.registerTask；agentId=null 的任务不属于任何
     * agent，不应被杀）。kill 语义：{@link MonitorMcpTaskRunner#stop()} → 流式循环退出 → killed +
     * notified + SDK 'stopped'。
     *
     * @param agentId 拥有待终止 monitor 任务的 sub-agent UUID
     * @return 实际终止的 monitor task 数
     */
    public int killMonitorMcpTasksForAgent(UUID agentId) {
        if (agentId == null || monitorMcpTaskRunner == null || frameworkService == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.killMonitorMcpTasksForAgent: agentId={} 或 runner={} 缺失, 跳过",
                    agentId, monitorMcpTaskRunner != null);
            }
            return 0;
        }
        int killed = 0;
        for (BackgroundTask task : frameworkService.listAll()) {
            if (task.type() != TaskType.MONITOR_MCP) continue;
            if (task.status() != BackgroundTaskStatus.RUNNING) continue;
            if (task.agentId() == null) continue;
            if (!agentId.equals(task.agentId())) continue;
            monitorMcpTaskRunner.stop();
            killed++;
            log.info("BackgroundTaskRunner: monitor task {} stopped for agent {}", task.id(), agentId);
        }
        log.info("BackgroundTaskRunner.killMonitorMcpTasksForAgent: agent={} 终止 {} 个 monitor 任务",
            agentId, killed);
        return killed;
    }

    /**
     * Phase 3: 原子 enqueueAgentNotification · 对齐 CC enqueueShellNotification
     *          (LocalShellTask.tsx:105-171).
     *
     * <p>防 TaskOutput + lifecycle 双路径重复通知: 首次调用返回 true + enqueue + notified=true;
     * 重复调用返回 false (notified 守卫短路), 不重复 enqueue.
     *
     * @param taskId task id
     * @return true 首次调用 (实际 enqueue); false 重复调用 (已 notified) 或 task 不存在
     */
    public boolean enqueueAgentNotification(String taskId) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) {
            log.debug("BackgroundTaskRunner.enqueueAgentNotification: task {} 不存在", taskId);
            return false;
        }
        // 原子 CAS 守卫: notified=true 短路 (CC LocalShellTask.tsx:156-159)
        if (current.notified()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.enqueueAgentNotification: task {} 已 notified, 跳过",
                    taskId);
            }
            return false;
        }
        BackgroundTask notified = current.withNotified();
        tasks.put(taskId, notified);

        String xml = TaskNotificationBuilder.buildEnqueueShellNotification(notified, 0);
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml, "task-notification",
                null, null, null, false, null, false, null, notified.sessionId()));
        if (log.isDebugEnabled()) {
            log.debug("BackgroundTaskRunner.enqueueAgentNotification: task {} notified",
                taskId);
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // CRON-D4: teammate 按 agentId 查找 · 对齐 CC findTeammateTaskByAgentId
    // (InProcessTeammateTask.tsx:92-108)
    // ════════════════════════════════════════════════════════════════════

    /**
     * CRON-D4: 按 teammate agentId 查找 IN_PROCESS_TEAMMATE 任务 ·
     * 对齐 CC findTeammateTaskByAgentId (Open-ClaudeCode/src/tasks/
     * InProcessTeammateTask/InProcessTeammateTask.tsx:92-108).
     *
     * <p>CC 语义（:95-107）：
     * <pre>
     * for (const task of Object.values(tasks)) {
     *   if (isInProcessTeammateTask(task) && task.identity.agentId === agentId) {
     *     if (task.status === 'running') return task   // :98-99 running 优先
     *     if (!fallback) fallback = task                // :102-104 首匹配兜底
     *   }
     * }
     * return fallback                                   // :107 无则 undefined
     * </pre>
     *
     * <p>等价不变量：
     * <ul>
     *   <li>类型过滤：Java {@code type == IN_PROCESS_TEAMMATE} 等价 CC
     *       {@code isInProcessTeammateTask}（TaskType.java:25 已存在）</li>
     *   <li>agentId 匹配：CC {@code task.identity.agentId === agentId}（string 相等）。
     *       Java {@code BackgroundTask.agentId} 为 {@code @Nullable UUID}（BackgroundTask.java:79）
     *       → 用 {@code toString()} 与入参 String 比较（入参为 null 直接返回 null，fail-closed）</li>
     *   <li>running 优先 return；否则首匹配 fallback（<b>可能为 terminal</b>——
     *       terminal 过滤由调用方 onFireTask 等价逻辑承担，CC useScheduledTasks.ts:97
     *       {@code !isTerminalTaskStatus(teammate.status)}）</li>
     *   <li>无匹配返回 null（CC :107 undefined 等价）</li>
     * </ul>
     *
     * <p>WHY（意图）：TestJob fire 按 {@code dto.agentId()} 路由到 teammate 时，
     * 若存在多个同 agentId 的任务（旧 killed 任务未 evict + 新 running 任务并存），
     * running 优先避免把 prompt 注入死任务（CC :96-99 注释 "Prefer running tasks in case
     * old killed tasks still exist"）。
     *
     * @param agentId teammate agentId（ScheduleDto.agentId 透传，String）
     * @return 匹配的 teammate 任务（running 优先，否则首匹配兜底）；无匹配/入参 null → null
     */
    public BackgroundTask findTeammateByAgentId(String agentId) {
        if (agentId == null) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.findTeammateByAgentId: agentId=null, 直接返回 null");
            }
            return null;
        }
        BackgroundTask fallback = null;
        for (BackgroundTask task : tasks.values()) {
            // CC :95 isInProcessTeammateTask(task) && task.identity.agentId === agentId
            if (task.type() != TaskType.IN_PROCESS_TEAMMATE) continue;
            if (task.agentId() == null) continue;
            if (!agentId.equals(task.agentId().toString())) continue;
            // CC :98-99 running 优先
            if (task.status() == BackgroundTaskStatus.RUNNING) {
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner.findTeammateByAgentId: 命中 running teammate "
                            + "task={} agentId={}", task.id(), agentId);
                }
                return task;
            }
            // CC :102-104 首匹配兜底
            if (fallback == null) {
                fallback = task;
            }
        }
        if (fallback != null && log.isDebugEnabled()) {
            log.debug("BackgroundTaskRunner.findTeammateByAgentId: 命中首匹配兜底 task={} agentId={}",
                fallback.id(), agentId);
        }
        return fallback;
    }

    // ════════════════════════════════════════════════════════════════════
    // Phase A 任务 1: async agent 终态 API (completeAsyncAgent / failAsyncAgent)
    // 对齐 CC LocalAgentTask.tsx:197-262 lifecycle → terminal state
    // ════════════════════════════════════════════════════════════════════

    /**
     * Phase A 任务 1: 完成 async agent task · 原子 CAS 推进到 COMPLETED.
     *
     * <p>语义:
     * <ul>
     *   <li>status==RUNNING → 写入 outputFile + 推进 COMPLETED + enqueue 通知 + evict</li>
     *   <li>status!=RUNNING (已被 kill/fail/duplicate complete) → CAS 守卫短路, 返回当前 task, 不重复 enqueue</li>
     *   <li>task 不存在 → 抛 IllegalStateException</li>
     * </ul>
     *
     * <p>WHY CAS: worker 完成回调与 cancel()/killAsyncAgent() 并发时, 简单 get+put 会导致
     * "kill 写 KILLED 后 worker 覆盖 COMPLETED" 的 race. 这里用
     * {@link ConcurrentMap#computeIfPresent(Object, java.util.function.BiFunction)} 保证
     * 读-改-写原子, killAsyncAgent 先写 KILLED 后 worker compute 会读出 KILLED 并短路.
     *
     * @param taskId task id (= agentId.toString())
     * @param result  agent 结果 (summary 写入 outputFile)
     * @return 终态后的 task (CAS 守卫时返回当前未变 task)
     */
    public BackgroundTask completeAsyncAgent(String taskId, AsyncAgentResult result) {
        return transitionToTerminal(taskId, BackgroundTaskStatus.COMPLETED, result);
    }

    /**
     * Phase A 任务 1: 失败 async agent task · 原子 CAS 推进到 FAILED.
     *
     * <p>封装为 {@link AsyncAgentResult#failure(String, String)} 后走同一
     * {@link #transitionToTerminal} 通道. summary 字段写入 outputFile 作为错误描述.
     */
    public BackgroundTask failAsyncAgent(String taskId, String error) {
        return transitionToTerminal(taskId, BackgroundTaskStatus.FAILED,
            AsyncAgentResult.failure(error, taskId));
    }

    /**
     * Phase A 任务 1: 原子 CAS 终态化 · 被 completeAsyncAgent / failAsyncAgent 复用.
     *
     * <p>执行顺序:
     * <ol>
     *   <li>CAS 外: 取 task 快照, 写 summary 到 outputFile (无锁, 失败仅 log.warn)</li>
     *   <li>CAS 内 (computeIfPresent): 仅做 withStatus + withEndTime + withNotified</li>
     *   <li>CAS 外, 仅 CAS 推进成功时: frameworkService.updateTaskState + 通知 + evict</li>
     * </ol>
     *
     * <p>WHY 文件 I/O 移出 CAS lambda: 持锁时间 = 文件 I/O 时间会阻塞同一 bucket 的
     * map 操作, 加剧 killAsyncAgent 与 completeAsyncAgent 的 race. 移出后, 即使 file
     * 写完后 CAS 被 kill 抢先短路, reader 看到 KILLED 时 outputFile 已含 summary
     * (记录"worker 已完成但被 kill 抢先"的事实).
     *
     * <p>CAS 守卫短路时: 跳过全部外发副作用, 仅返回当前 task (避免重复通知).
     */
    private BackgroundTask transitionToTerminal(String taskId,
            BackgroundTaskStatus next, AsyncAgentResult result) {
        // CAS 外: 取 task 快照并先写 outputFile (无锁, 失败仅 log.warn)
        BackgroundTask snapshot = tasks.get(taskId);
        if (snapshot == null) {
            throw new IllegalStateException("task not found: " + taskId);
        }
        if (result != null) {
            appendToOutputFile(snapshot.outputFile(), result);
        }
        // 用 ConcurrentHashMap.computeIfPresent 保证读-改-写原子 (vs get+put race)
        AtomicReference<BackgroundTask> ref = new AtomicReference<>();
        AtomicBoolean advanced = new AtomicBoolean(false);
        tasks.computeIfPresent(taskId, (k, current) -> {
            if (current.status() != BackgroundTaskStatus.RUNNING) {
                // CAS 守卫短路: 已终态, 不推进
                ref.set(current);
                return current;
            }
            long now = System.currentTimeMillis();
            // [IMP-G] G25① CC TaskOutput.result/error 跟踪：result = AsyncAgentResult.summary（clean final
            //   answer 等价物，CC getTaskOutputData local_agent result/remote prompt 源）；FAILED 时
            //   summary 承载错误描述（CC agentTask.error 等价，AsyncAgentResult.failure 包装）。
            BackgroundTask terminal = current
                .withStatus(next)
                .withEndTime(now)
                .withResult(result != null && result.summary() != null && !result.summary().isBlank()
                    ? result.summary() : null)
                .withError(next == BackgroundTaskStatus.FAILED && result != null && result.summary() != null
                    ? result.summary() : null)
                .withNotified();
            ref.set(terminal);
            advanced.set(true);
            return terminal;
        });
        BackgroundTask finalTask = ref.get();
        if (finalTask == null) {
            // computeIfPresent 仅在 key 存在时调用 lambda — key 不存在时 lambda 不被调用
            throw new IllegalStateException("task not found: " + taskId);
        }
        if (!advanced.get()) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.transitionToTerminal: task {} 已非 RUNNING (status={}), CAS 短路",
                    taskId, finalTask.status().getStatusString());
            }
            return finalTask;
        }
        // 推进成功 — 触发外发副作用
        frameworkService.updateTaskState(taskId, finalTask);
        // [S4-1 残差 ①] async agent 终态通知改 agent 格式 (CC enqueueAgentNotification
        //   LocalAgentTask.tsx:246-257): 含 usage{total_tokens,tool_uses,duration_ms} (completed,
        //   agentToolUtils.ts:630-634) + result 段 (finalMessage/partialResult) + failed error 并入
        //   summary. 旧 shell 格式 (buildEnqueueShellNotification) 仅剩 shell/stub 路径使用.
        //   [FORK-02] 透传保留 worktree (SubagentExecutor Step 21.0 登记, CC getWorktreeResult)
        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(
            finalTask, result, finalTask.worktreePath(), finalTask.worktreeBranch());
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml, "task-notification",
                null, null, null, false, null, false, null, finalTask.sessionId()));

        emitTerminatedSdk(finalTask);

        // CC finalizer cleanup: 从 framework store evict 终态 task
        frameworkService.evictTerminalTask(taskId);
        // OPD-TP-21: worker 自然完成/失败 → 清理 task-scoped abortController 注册表
        //   （CC 终态 state 被 evict，controller 引用随之释放；防注册表无限增长）。
        taskAbortControllers.remove(taskId);

        log.info("BackgroundTaskRunner.{}: task {} 推进到 {} (endTime={})",
            next == BackgroundTaskStatus.COMPLETED ? "completeAsyncAgent" : "failAsyncAgent",
            taskId, next.getStatusString(), finalTask.endTime());
        return finalTask;
    }

    /**
     * 追加 result 到 outputFile · async agent worker 完成后写入 final assistant message.
     * 文件不存在时创建; 已存在时 append 保留 worker 累积输出.
     *
     * <p><b>方案B 对齐</b>：outputFile 为 CC 五层
     * {@code {tmpRoot}/claude-{uid}/{sanitizePath(originalCwd)}/{sessionId}/tasks/{taskId}.output}，
     * 父目录（.../{sessionId}/tasks）不再天然存在 —— 写前 {@code createDirectories} 建父目录
     * （对齐 CC ensureOutputDir，diskOutput.ts:65-67 mkdir recursive）。旧平铺 /tmp 路径父目录恒在，
     * 无需此步。
     */
    private void appendToOutputFile(String path, AsyncAgentResult result) {
        if (path == null || path.isBlank() || result.summary() == null || result.summary().isEmpty()) return;
        try {
            Path outputPath = Paths.get(path);
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, result.summary(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.appendToOutputFile: summary 已追加到 outputFile path={}", path);
            }
        } catch (Exception e) {
            log.warn("BackgroundTaskRunner.appendToOutputFile: 写 outputFile 失败 path={} 错误={}",
                path, e.getMessage());
        }
    }

    /**
     * Phase 3: TaskOutput 阻塞查询 · 对齐 CC TaskOutputTool.tsx:118-143.
     *
     * @param taskId    task id
     * @param blocking  true 阻塞直到 terminal 或 timeout; false 立即返回当前状态
     * @param timeoutMs 最大阻塞时间 (ms), blocking=true 时有效
     * @return TaskOutput 包装 (含 status, content, timedOut, found)
     */
    public TaskOutput getOutput(String taskId, boolean blocking, long timeoutMs) {
        BackgroundTask task = resolveOutputTask(taskId);
        if (task == null) {
            // 未找到: 返回 PENDING + found=false
            return new TaskOutput(taskId, null, null, null,
                BackgroundTaskStatus.PENDING, "", true, false,
                null, null, null, null);
        }
        if (!blocking) {
            // 立即返回当前状态
            String content = readTaskOutput(taskId);
            return new TaskOutput(taskId, task.type().getTypeString(), task.description(),
                task.outputFile(), task.status(), content, false, true,
                task.exitCode(), task.error(), task.prompt(), task.result());
        }
        // blocking=true: 轮询直到 terminal 或 timeout
        long deadline = System.currentTimeMillis() + timeoutMs;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            BackgroundTask current = resolveOutputTask(taskId);
            if (current == null) {
                return new TaskOutput(taskId, null, null, null,
                    BackgroundTaskStatus.PENDING, "", true, false,
                    null, null, null, null);
            }
            if (current.status().isTerminal()) {
                content = readTaskOutput(taskId);
                return new TaskOutput(taskId, current.type().getTypeString(), current.description(),
                    current.outputFile(), current.status(), content, false, true,
                    current.exitCode(), current.error(), current.prompt(), current.result());
            }
            content = readTaskOutput(taskId);
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new TaskOutput(taskId, current.type().getTypeString(), current.description(),
                    current.outputFile(), current.status(), content, true, true,
                    current.exitCode(), current.error(), current.prompt(), current.result());
            }
        }
        // timeout
        BackgroundTask finalState = resolveOutputTask(taskId);
        return new TaskOutput(taskId,
            finalState != null ? finalState.type().getTypeString() : null,
            finalState != null ? finalState.description() : null,
            finalState != null ? finalState.outputFile() : null,
            finalState != null ? finalState.status() : BackgroundTaskStatus.PENDING,
            content, true, true,
            finalState != null ? finalState.exitCode() : null,
            finalState != null ? finalState.error() : null,
            finalState != null ? finalState.prompt() : null,
            finalState != null ? finalState.result() : null);
    }

    /**
     * Phase 3: 测试辅助 — 将 task 标记为终态 (无实际子进程) · 仅供测试使用.
     *
     * <p>生产代码应通过 cancel() 或 task 完成自然进入终态.
     */
    public void markTerminalState(String taskId, BackgroundTaskStatus terminalStatus) {
        BackgroundTask current = tasks.get(taskId);
        if (current == null) return;
        BackgroundTask updated = current
            .withStatus(terminalStatus)
            .withEndTime(System.currentTimeMillis());
        tasks.put(taskId, updated);
        // 测试辅助不设 notified → CC 语义：终态 && not-notified 不 evict（保留在 store）
        frameworkService.updateTaskState(taskId, updated);
    }

    /**
     * Phase 3: TaskOutput 阻塞查询结果 · 对齐 CC TaskOutputTool.tsx 输出 schema.
     *
     * @param taskId      task id
     * @param taskType    任务类型串（CC TaskOutput.task_type，如 'local_bash'/'in_process_teammate'；
     *                    found=false 时为 null）
     * @param description 任务描述（CC TaskOutput.description；found=false 时为 null）
     * @param outputFile  任务输出文件绝对路径（CC formatTaskOutput 截断头；found=false 时为 null）
     * @param status      任务状态 (查询时刻)
     * @param content     当前输出内容
     * @param timedOut    true 阻塞查询超时; false 未超时
     * @param found       true task 找到; false 未找到
     * @param exitCode    [IMP-G] G25① CC TaskOutput.exitCode（local_bash result.code；无 → null）
     * @param error       [IMP-G] G25① CC TaskOutput.error（local_agent error；无 → null）
     * @param prompt      [IMP-G] G25① CC TaskOutput.prompt（local_agent/remote prompt；无 → null）
     * @param result      [IMP-G] G25① CC TaskOutput.result（local_agent clean result；无 → null）
     */
    public record TaskOutput(
        String taskId,
        String taskType,
        String description,
        String outputFile,
        BackgroundTaskStatus status,
        String content,
        boolean timedOut,
        boolean found,
        @Nullable Integer exitCode,
        @Nullable String error,
        @Nullable String prompt,
        @Nullable String result
    ) {
        /** 8 参兼容构造器（G25① 前调用方/测试桩）· 4 跟踪字段缺省 null。 */
        public TaskOutput(String taskId, String taskType, String description, String outputFile,
                          BackgroundTaskStatus status, String content, boolean timedOut, boolean found) {
            this(taskId, taskType, description, outputFile, status, content, timedOut, found,
                 null, null, null, null);
        }
    }

    /**
     * 终态 task_notification SDK 事件 — 对齐 CC emitTaskTerminatedSdk（sdkEventQueue.ts:114-134）。
     *
     * <p>CC status 串：'completed' | 'failed' | 'stopped'。summary 用 task.description
     * （对齐 CC stopTask.ts:90 summary: task.description）；outputFile 透传 Java 输出文件路径
     * （CC 直接发射路径缺省 ''，因正常 XML 解析路径会填 output_file；Java 无该解析，透传更有用）。
     * sdkEventQueue 为 null（测试直构）时静默跳过。
     */
    private void emitTerminatedSdk(BackgroundTask task) {
        if (sdkEventQueue == null) {
            return;
        }
        String status = switch (task.status()) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case KILLED -> "stopped";
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("BackgroundTaskRunner.emitTerminatedSdk: task {} status={} 非终态, 跳过",
                        task.id(), task.status().getStatusString());
                }
                yield null;
            }
        };
        if (status == null) {
            return;
        }
        sdkEventQueue.emitTaskTerminatedSdk(task.id(), status,
            new SdkEventQueue.TaskTerminatedOpts(task.toolUseId(), task.description(),
                task.outputFile(), null));
        // [C8 · 决策8] 空闲路径 / 无 turn 无 SDK drain → 单点 STOMP 直推 /topic/tasks（结构化
        //   task_notification，字段对齐既有 SdkEventQueue.TaskNotificationEvent 契约 FR-5；
        //   sessionId 供前端按会话过滤）。wsTemplate null（测试直构）→ 跳过不阻断。
        if (wsTemplate != null) {
            wsTemplate.convertAndSend("/topic/tasks",
                new com.nexusai.eventbus.ws.TaskNotificationEvent(
                    task.sessionId(), task.id(), status, task.description()));
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskRunner.emitTerminatedSdk: task {} 已直推 /topic/tasks task_notification({})",
                    task.id(), status);
            }
        }
    }

    // [IMPL-10] DEL-L03-02: hookRegistry 注入 + TaskCompleted/TeammateIdle 发射已删除
    //   （CC 无 background-task 完成触发路径，stopHooks.ts turn-end 内联）
}
