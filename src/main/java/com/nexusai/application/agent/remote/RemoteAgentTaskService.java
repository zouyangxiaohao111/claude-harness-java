package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskIdGenerator;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.RequestContext;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * remote_agent 任务状态机 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:386-847。
 *
 * <p>承载 M-2/M-3/M-4/M-8：registerRemoteAgentTask / persist+list+remove
 * RemoteAgentMetadata（sidecar）/ restoreRemoteAgentTasks / startRemoteSessionPolling。
 * 命名对齐 OPD-TP-02（registerRemoteAgentTask）。
 *
 * <p>职责映射（CC 行号已 grep -n 自验）：
 * <ul>
 *   <li>{@link #registerRemoteAgentTask} — :386-466（generateTaskId r 前缀 + initTaskOutput +
 *       createTaskStateBase+running + registerTask + persist sidecar + start polling）</li>
 *   <li>{@link #restoreRemoteAgentTasks} — :477-532（--resume：list sidecar → fetchSession 判活，
 *       404/archived→删 sidecar，running→重建 state+pollStartedAt=now）</li>
 *   <li>{@link #kill} — :811-847（only-if-running→killed+notified+endTime →
 *       emitTaskTerminatedSdk('stopped') → archiveRemoteSession fire-and-forget → evict → 删 sidecar）</li>
 *   <li>polling — :538-799（1s 自调度，lastEventId 增量，archived/result/stableIdle/review timeout/
 *       race 守卫）</li>
 * </ul>
 *
 * <p><b>状态承载</b>: {@code remoteTasks} map 为权威状态（对齐 CC {@code state.tasks[taskId]}），
 * 其中 {@code base}（BackgroundTask）镜像进 {@link TaskFrameworkService} store
 * （SDK task_started / offset / evict 机制复用）。sidecar 目录 = sessionDir/remote-agents
 * （项目目录，对齐 CC sessionStorage.ts:320-328）。
 *
 * <p><b>批次Y Q3 输出根收敛</b>: 输出文件根 = {@code taskOutputDirSupplier} 注入的 temp 唯一根
 * （{@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks}，RemoteTaskConfiguration 已解耦
 * currentSessionProjectRoot，不再写项目目录）—— 对齐 CC RemoteAgentTask.tsx 统一 diskOutput
 * （getTaskOutputPath，输出在 temp；项目目录只有元数据 sidecar）。
 */
public class RemoteAgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentTaskService.class);

    /** CC :540 POLL_INTERVAL_MS = 1000 */
    public static final long POLL_INTERVAL_MS = 1_000L;
    /** CC :541 REMOTE_REVIEW_TIMEOUT_MS = 30 * 60 * 1000 */
    public static final long REMOTE_REVIEW_TIMEOUT_MS = 30L * 60L * 1_000L;
    /** CC :545 STABLE_IDLE_POLLS = 5 */
    public static final int STABLE_IDLE_POLLS = 5;

    /** CC completionCheckers（:78）— 按 RemoteTaskType 键分发的 completion checker 注册表。 */
    private static final Map<RemoteTaskType, RemoteTaskCompletionChecker> COMPLETION_CHECKERS =
        new ConcurrentHashMap<>();

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_READER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 权威任务状态 map（对齐 CC state.tasks[taskId]） */
    private final ConcurrentHashMap<String, RemoteAgentTaskState> remoteTasks = new ConcurrentHashMap<>();
    /** 活动轮询循环 map */
    private final ConcurrentHashMap<String, PollLoop> pollLoops = new ConcurrentHashMap<>();

    private final TaskFrameworkService framework;
    private final NotificationQueue notificationQueue;
    private final SdkEventQueue sdkEventQueue;
    private final RemoteSessionsApi sessionsApi;
    private final Supplier<Path> sessionDirSupplier;
    private final Supplier<Path> taskOutputDirSupplier;
    private final ScheduledExecutorService pollScheduler;
    /** 轮询间隔 · 生产默认 CC :540 POLL_INTERVAL_MS=1000；测试可注入更小值加速 */
    private final long pollIntervalMs;

    public RemoteAgentTaskService(TaskFrameworkService framework,
                                  NotificationQueue notificationQueue,
                                  @Nullable SdkEventQueue sdkEventQueue,
                                  RemoteSessionsApi sessionsApi,
                                  Supplier<Path> sessionDirSupplier,
                                  Supplier<Path> taskOutputDirSupplier,
                                  ScheduledExecutorService pollScheduler) {
        this(framework, notificationQueue, sdkEventQueue, sessionsApi,
            sessionDirSupplier, taskOutputDirSupplier, pollScheduler, POLL_INTERVAL_MS);
    }

    /** 测试构造 — 可注入 pollIntervalMs（默认 {@link #POLL_INTERVAL_MS}）。 */
    public RemoteAgentTaskService(TaskFrameworkService framework,
                                  NotificationQueue notificationQueue,
                                  @Nullable SdkEventQueue sdkEventQueue,
                                  RemoteSessionsApi sessionsApi,
                                  Supplier<Path> sessionDirSupplier,
                                  Supplier<Path> taskOutputDirSupplier,
                                  ScheduledExecutorService pollScheduler,
                                  long pollIntervalMs) {
        this.framework = Objects.requireNonNull(framework);
        this.notificationQueue = Objects.requireNonNull(notificationQueue);
        this.sdkEventQueue = sdkEventQueue;
        this.sessionsApi = Objects.requireNonNull(sessionsApi);
        this.sessionDirSupplier = Objects.requireNonNull(sessionDirSupplier);
        this.taskOutputDirSupplier = Objects.requireNonNull(taskOutputDirSupplier);
        this.pollScheduler = Objects.requireNonNull(pollScheduler);
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : POLL_INTERVAL_MS;
    }

    // ────────────────────────────────────────────────────────────────────
    // 类型与选项
    // ────────────────────────────────────────────────────────────────────

    /** CC RemoteTaskCompletionChecker（:77）— 返回非 null 文本则完成，null 继续轮询。 */
    @FunctionalInterface
    public interface RemoteTaskCompletionChecker {
        @Nullable
        String check(@Nullable Map<String, Object> remoteTaskMetadata);
    }

    /** CC registerRemoteAgentTask options（:386-403）。 */
    public record RegisterOptions(
        RemoteTaskType remoteTaskType,
        String sessionId,
        String title,
        String command,
        @Nullable String toolUseId,
        @Nullable Boolean isRemoteReview,
        @Nullable Boolean isUltraplan,
        @Nullable Boolean isLongRunning,
        @Nullable Map<String, Object> remoteTaskMetadata,
        /**
         * Phase 4 (cron-notify): 本地创建会话 sessionId。与 {@code sessionId}（CCR remote 会话，
         * API 轮询用）不同 —— 本字段是<b>本地</b>发起该远程任务的会话，完成通知注入该本地会话回合。
         * 由生产 caller（AgentTool 远端 / /review 命令）从 {@code ToolUseContext.sessionId()} 显式
         * 透传（tool-exec 池线程无 MDC，不可依赖 {@code RequestContext.sessionId()}）；null 时回退
         * MDC（register 在会话线程路径），仍 null → 回落全局。
         */
        @Nullable String creatingSessionId
    ) {
        public RegisterOptions {
            if (remoteTaskType == null) throw new IllegalArgumentException("remoteTaskType 不能为 null");
            if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为 null");
            if (title == null) title = "";
            if (command == null) command = "";
        }
    }

    /** CC registerRemoteAgentTask 返回值（:399-403）— {taskId, sessionId, cleanup}。 */
    public record RegisteredRemoteTask(String taskId, String sessionId, Runnable cleanup) {
    }

    // ────────────────────────────────────────────────────────────────────
    // 注册 / 恢复 / kill
    // ────────────────────────────────────────────────────────────────────

    /**
     * 注册 remote agent 任务 · 对齐 CC registerRemoteAgentTask（:386-466）。
     * taskId r 前缀（TaskIdGenerator+TaskType.REMOTE_AGENT，对齐 CC :415 generateTaskId('remote_agent')）。
     */
    public RegisteredRemoteTask registerRemoteAgentTask(RegisterOptions options) {
        String taskId = TaskIdGenerator.generate(TaskType.REMOTE_AGENT);
        long now = System.currentTimeMillis();
        Path outputPath = RemoteTaskOutput.outputPath(taskOutputDirSupplier.get(), taskId);
        // CC :420 void initTaskOutput — 注册前建空文件
        RemoteTaskOutput.init(outputPath);

        // Phase 4 (cron-notify): 本地创建会话 = 显式透传（RegisterOptions.creatingSessionId，生产
        //   caller 从 ctx.sessionId() 取）?? MDC 兜底（register 在会话线程路径）?? null（回落全局）。
        //   <b>绝不能把 options.sessionId()（CCR remote 会话）当创建会话</b> —— 二者语义不同，
        //   remote id 的 canonicalUuid 永不等于任何本地会话，drainForQuery 永不命中创建会话回合。
        String creatingSession = options.creatingSessionId() != null && !options.creatingSessionId().isBlank()
            ? options.creatingSessionId() : RequestContext.sessionId();
        BackgroundTask base = new BackgroundTask(taskId, TaskType.REMOTE_AGENT,
            BackgroundTaskStatus.RUNNING,
            options.title() != null ? options.title() : "",
            options.toolUseId(), now, null, null,
            outputPath.toString(), 0L, false)
            .withSessionId(creatingSession);
        RemoteAgentTaskState state = new RemoteAgentTaskState(base,
            options.remoteTaskType(), options.remoteTaskMetadata(),
            options.sessionId(), options.command() != null ? options.command() : "",
            options.title() != null ? options.title() : "",
            List.of(), List.of(),
            options.isLongRunning(), now, options.isRemoteReview(), null,
            options.isUltraplan(), null);

        remoteTasks.put(taskId, state);
        // CC :437 registerTask — 发射 task_started SDK
        framework.registerTask(base);

        // CC :442-454 persistRemoteAgentMetadata（fire-and-forget）— 创建会话随 sidecar 持久化，
        // --resume 恢复后通知仍归创建会话。
        persistRemoteAgentMetadata(new RemoteAgentMetadata(taskId,
            options.remoteTaskType().value(), options.sessionId(),
            options.title() != null ? options.title() : "",
            options.command() != null ? options.command() : "", now,
            options.toolUseId(), options.isLongRunning(), options.isUltraplan(),
            options.isRemoteReview(), options.remoteTaskMetadata(), creatingSession));

        // CC :460 startRemoteSessionPolling
        Runnable stopPolling = startRemoteSessionPolling(taskId);
        log.info("RemoteAgentTaskService.registerRemoteAgentTask: taskId={} remoteSessionId={} creatingSessionId={} type={}",
            taskId, options.sessionId(), creatingSession, options.remoteTaskType().value());
        return new RegisteredRemoteTask(taskId, options.sessionId(), stopPolling);
    }

    /**
     * 恢复 remote agent 任务 · 对齐 CC restoreRemoteAgentTasks（:477-532）。
     * 扫描 sidecar → fetchSession 判活：404→删 sidecar；archived→删；其他→保留跳过；
     * running→重建 state(status=running, startTime=spawnedAt, pollStartedAt=now) + initOutput + polling。
     */
    public void restoreRemoteAgentTasks() {
        try {
            restoreRemoteAgentTasksImpl();
        } catch (Exception e) {
            log.warn("RemoteAgentTaskService.restoreRemoteAgentTasks 失败: {}", e.getMessage());
        }
    }

    private void restoreRemoteAgentTasksImpl() {
        Path sessionDir = sessionDirSupplier.get();
        if (sessionDir == null) {
            return;
        }
        List<RemoteAgentMetadata> persisted = RemoteAgentMetadataStore.list(sessionDir);
        if (persisted.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (RemoteAgentMetadata meta : persisted) {
            // CC :490-505 fetchSession 判活
            String remoteStatus;
            try {
                remoteStatus = sessionsApi.fetchSession(meta.sessionId()).sessionStatus();
            } catch (RemoteSessionsApi.SessionNotFoundException e) {
                log.info("RemoteAgentTaskService.restore: 丢弃 {} (404: {})", meta.taskId(), e.getMessage());
                RemoteAgentMetadataStore.delete(sessionDir, meta.taskId());
                continue;
            } catch (Exception e) {
                log.info("RemoteAgentTaskService.restore: 跳过 {} (可恢复: {})", meta.taskId(), e.getMessage());
                continue;
            }
            if ("archived".equals(remoteStatus)) {
                log.info("RemoteAgentTaskService.restore: 会话已归档，删除 sidecar {}", meta.taskId());
                RemoteAgentMetadataStore.delete(sessionDir, meta.taskId());
                continue;
            }
            // CC :511-527 重建 state
            Path outputPath = RemoteTaskOutput.outputPath(taskOutputDirSupplier.get(), meta.taskId());
            // Phase 4 (cron-notify): 恢复时从 sidecar 读回本地创建会话（meta.creatingSessionId）——
            // --resume 后恢复任务的通知仍注入创建会话回合；旧 sidecar 无该字段 → null（回落全局）。
            BackgroundTask base = new BackgroundTask(meta.taskId(), TaskType.REMOTE_AGENT,
                BackgroundTaskStatus.RUNNING,
                meta.title() != null ? meta.title() : "", meta.toolUseId(),
                meta.spawnedAt(), null, null, outputPath.toString(), 0L, false)
                .withSessionId(meta.creatingSessionId());
            RemoteAgentTaskState state = new RemoteAgentTaskState(base,
                RemoteTaskType.fromValue(meta.remoteTaskType()).orElse(RemoteTaskType.REMOTE_AGENT),
                meta.remoteTaskMetadata(), meta.sessionId(),
                meta.command() != null ? meta.command() : "",
                meta.title() != null ? meta.title() : "",
                List.of(), List.of(),
                meta.isLongRunning(), now, meta.isRemoteReview(), null,
                meta.isUltraplan(), null);
            remoteTasks.put(meta.taskId(), state);
            framework.registerTask(base); // replacement：非新开始，跳过 task_started
            RemoteTaskOutput.init(outputPath);
            startRemoteSessionPolling(meta.taskId());
            log.info("RemoteAgentTaskService.restore: 恢复任务 {} sessionId={}", meta.taskId(), meta.sessionId());
        }
    }

    /**
     * kill remote agent 任务 · 对齐 CC RemoteAgentTask.kill（:811-847）。
     * only-if-running → killed+notified+endTime → emitTaskTerminatedSdk('stopped') →
     * archiveRemoteSession（fire-and-forget）→ evict output → 删 sidecar。
     */
    public boolean kill(String taskId) {
        AtomicBoolean killed = new AtomicBoolean(false);
        remoteTasks.computeIfPresent(taskId, (k, prev) -> {
            if (prev.status() != BackgroundTaskStatus.RUNNING) {
                return prev;
            }
            killed.set(true);
            return prev.withStatus(BackgroundTaskStatus.KILLED).withNotified().withEndTime(System.currentTimeMillis());
        });
        if (!killed.get()) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteAgentTaskService.kill: task {} 不存在或非 running, 跳过", taskId);
            }
            return false;
        }
        RemoteAgentTaskState s = remoteTasks.get(taskId);
        if (s == null) {
            return false;
        }
        framework.updateTaskState(taskId, s.base());
        // CC :835-838 emitTaskTerminatedSdk(taskId,'stopped',{toolUseId, summary: description})
        if (sdkEventQueue != null) {
            sdkEventQueue.emitTaskTerminatedSdk(taskId, "stopped",
                new SdkEventQueue.TaskTerminatedOpts(s.toolUseId(), s.description(), s.base().outputFile(), null));
        }
        // CC :840-842 archiveRemoteSession fire-and-forget
        if (s.sessionId() != null) {
            try {
                sessionsApi.archiveSession(s.sessionId());
            } catch (Exception e) {
                log.warn("RemoteAgentTaskService.kill: 归档 {} 失败: {}", s.sessionId(), e.getMessage());
            }
        }
        stopPolling(taskId);
        RemoteTaskOutput.evict(taskId);
        RemoteAgentMetadataStore.delete(sessionDirSupplier.get(), taskId);
        log.info("RemoteAgentTaskService.kill: task {} killed, 已归档 session {}", taskId, s.sessionId());
        return true;
    }

    /**
     * 按 taskId 查询 remote_agent 任务状态 · 供 stopTask 分发（M-9）判 running/not_running。
     * 对齐 CC stopTask.ts:38-53（appState.tasks[taskId] 查状态）；remote 任务不经
     * BackgroundTaskRunner.spawn，注册在 {@code remoteTasks} 权威 map 中。
     */
    @Nullable
    public RemoteAgentTaskState findTask(String taskId) {
        return remoteTasks.get(taskId);
    }

    /** CC registerCompletionChecker（:84-86）。 */
    public static void registerCompletionChecker(RemoteTaskType remoteTaskType, RemoteTaskCompletionChecker checker) {
        COMPLETION_CHECKERS.put(remoteTaskType, checker);
    }

    // ────────────────────────────────────────────────────────────────────
    // sidecar 持久化（persist / remove）
    // ────────────────────────────────────────────────────────────────────

    /**
     * CC persistRemoteAgentMetadata（:92-98）— fire-and-forget，失败仅日志不阻塞注册。
     */
    private void persistRemoteAgentMetadata(RemoteAgentMetadata meta) {
        RemoteAgentMetadataStore.write(sessionDirSupplier.get(), meta);
    }

    // ────────────────────────────────────────────────────────────────────
    // 轮询（startRemoteSessionPolling）
    // ────────────────────────────────────────────────────────────────────

    /**
     * CC startRemoteSessionPolling（:538-799）— 启动 1s 自调度轮询。
     *
     * @return cleanup（CC :796-798 — isRunning=false）
     */
    public Runnable startRemoteSessionPolling(String taskId) {
        PollLoop loop = pollLoops.computeIfAbsent(taskId, id -> new PollLoop(id));
        if (loop.isStarted()) {
            // 已有活动循环（重复 start），返回空 cleanup 避免停掉现有循环
            return () -> { };
        }
        loop.start();
        log.info("RemoteAgentTaskService.startRemoteSessionPolling: taskId={} 轮询启动 (interval={}ms)",
            taskId, POLL_INTERVAL_MS);
        return loop::stop;
    }

    private void stopPolling(String taskId) {
        PollLoop loop = pollLoops.remove(taskId);
        if (loop != null) {
            loop.stop();
        }
    }

    /**
     * 单任务轮询循环 · 对齐 CC :552-790 poll() 闭包（isRunning + 增量游标 + idle 计数）。
     * CC setTimeout 自调度 → Java ScheduledExecutorService 每 tick 末尾排下一 tick（无重叠）。
     */
    private final class PollLoop {
        private final String taskId;
        /**
         * [IMP-C D2-A/F3] 任务创建/恢复线程捕获的 projectRoot 冻结值（null = 无会话上下文，回落）。
         *
         * <p>WHY: tick 在 pollScheduler 定时器线程执行（{@code pollScheduler.schedule(this::tick, ...)}），
         *   ThreadLocal 不跨线程 —— 不冻结则 tick 内 {@code sessionDirSupplier}/{@code taskOutputDirSupplier}
         *   惰性读取解析到回落值（CLAUDE_PROJECT_DIR env ?? config home）而非任务所属会话目录 P
         *   （T4-D8：remote_agent sidecar 写 config home 而非 P/{sessionId}）。创建任务时冻结
         *   （registerRemoteAgentTask 会话线程）入 task 对象，任务执行时 tick 注入（set + finally
         *   restore，对齐 LlmAgentLoop.run() capture/restore 模式）。
         */
        private final String frozenProjectRoot;
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private final AtomicBoolean started = new AtomicBoolean(false);
        private volatile String lastEventId;
        private final List<Map<String, Object>> accumulatedLog = new java.util.ArrayList<>();
        private volatile String cachedReviewContent;
        private int consecutiveIdlePolls;

        PollLoop(String taskId) {
            this.taskId = taskId;
            // 创建/恢复任务的调用线程（register = 会话线程 / restore = 启动线程）捕获冻结。
            this.frozenProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        }

        boolean isStarted() {
            return started.get();
        }

        void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            isRunning.set(true);
            scheduleNext();
        }

        void stop() {
            isRunning.set(false);
            started.set(false);
        }

        private void scheduleNext() {
            if (!isRunning.get()) {
                return;
            }
            pollScheduler.schedule(this::tick, pollIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /** 单次 poll tick · 对齐 CC :552-790。 */
        void tick() {
            if (!isRunning.get()) {
                return;
            }
            // [IMP-C D2-A/F3] 定时器线程注入任务创建时冻结的会话上下文 —— projectRoot（ThreadLocal）
            // + sessionId（MDC），使 tick 内惰性读取（sessionDirSupplier/taskOutputDirSupplier 等）
            // 解析到任务所属会话目录（对齐 LlmAgentLoop.run() capture/restore 模式；restore 而非
            // remove，防线程池复用串台回归）。null 冻结值（restore 场景）不 set，保持回落语义。
            String prevProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
            try {
                try {
                    RemoteAgentTaskState task = remoteTasks.get(taskId);
                    if (frozenProjectRoot != null && !frozenProjectRoot.isBlank()) {
                        AutoMemPaths.setCurrentProjectRoot(frozenProjectRoot);
                    }
                    // CC :557-563 — 任务被杀/已终态 → 静默 return（session 保留）
                    if (task == null || task.status() != BackgroundTaskStatus.RUNNING) {
                        return;
                    }
                    // MDC sessionId 注入（task 对象创建时冻结；定时器线程无 MDC 上下文）
                    RequestContext.setSession(task.sessionId());
                // CC :564-578 pollRemoteSessionEvents(lastEventId) 增量
                RemoteSessionsApi.PollResult response = sessionsApi.pollEvents(task.sessionId(), lastEventId);
                lastEventId = response.lastEventId();
                boolean logGrew = response.newEvents() != null && !response.newEvents().isEmpty();
                if (logGrew) {
                    accumulatedLog.addAll(response.newEvents());
                    String deltaText = deltaText(response.newEvents(), task.isRemoteReview());
                    if (!deltaText.isEmpty()) {
                        Path output = Path.of(task.base().outputFile());
                        RemoteTaskOutput.append(output, deltaText + "\n");
                    }
                }
                // CC :579-589 archived → completed+notify+evict+删 sidecar
                if ("archived".equals(response.sessionStatus())) {
                    completeTask(taskId, task.title(), "completed", task.toolUseId());
                    return;
                }
                // CC :590-604 completionChecker → 非 null 即完成
                RemoteTaskCompletionChecker checker = COMPLETION_CHECKERS.get(task.remoteTaskType());
                if (checker != null) {
                    String completionResult = checker.check(task.remoteTaskMetadata());
                    if (completionResult != null) {
                        completeTask(taskId, completionResult, "completed", task.toolUseId());
                        return;
                    }
                }
                // CC :610 result 事件（isUltraplan/isLongRunning 跳过）
                boolean ultraOrLong = Boolean.TRUE.equals(task.isUltraplan()) || Boolean.TRUE.equals(task.isLongRunning());
                Map<String, Object> result = ultraOrLong ? null : findLastResult(accumulatedLog);

                // CC :619-621 remote-review 缓存 tag（delta 扫描）
                if (Boolean.TRUE.equals(task.isRemoteReview()) && logGrew && cachedReviewContent == null) {
                    cachedReviewContent = RemoteAgentLogParser.extractReviewTagFromLog(response.newEvents());
                }
                // CC :627-656 心跳 progress 解析（最后一次出现）
                RemoteAgentTaskState.ReviewProgress[] newProgressHolder = {null};
                if (Boolean.TRUE.equals(task.isRemoteReview()) && logGrew) {
                    newProgressHolder[0] = parseReviewProgress(response.newEvents());
                }
                // CC :660-665 stableIdle
                boolean hasAnyOutput = hasAnyOutput(accumulatedLog, task.isRemoteReview());
                if ("idle".equals(response.sessionStatus()) && !logGrew && hasAnyOutput) {
                    consecutiveIdlePolls++;
                } else {
                    consecutiveIdlePolls = 0;
                }
                boolean stableIdle = consecutiveIdlePolls >= STABLE_IDLE_POLLS;
                // CC :681-687 sessionDone / reviewTimedOut / newStatus
                boolean hasSessionStartHook = hasSessionStartHook(accumulatedLog);
                boolean hasAssistantEvents = hasAssistantEvents(accumulatedLog);
                boolean sessionDone = Boolean.TRUE.equals(task.isRemoteReview())
                    && (cachedReviewContent != null || (!hasSessionStartHook && stableIdle && hasAssistantEvents));
                boolean reviewTimedOut = Boolean.TRUE.equals(task.isRemoteReview())
                    && System.currentTimeMillis() - task.pollStartedAt() > REMOTE_REVIEW_TIMEOUT_MS;
                String newStatus;
                if (result != null) {
                    newStatus = "success".equals(String.valueOf(result.get("subtype"))) ? "completed" : "failed";
                } else if (sessionDone || reviewTimedOut) {
                    newStatus = "completed";
                } else {
                    newStatus = accumulatedLog.isEmpty() ? "starting" : "running";
                }
                boolean terminal = result != null || sessionDone || reviewTimedOut;
                long endTime = terminal ? System.currentTimeMillis() : 0L;

                // CC :693-719 race 守卫 updateTaskState（prevTask.status!=='running'→bail）
                boolean[] raceTerminated = {false};
                RemoteAgentTaskState applied = remoteTasks.computeIfPresent(taskId, (k, prev) -> {
                    if (prev.status() != BackgroundTaskStatus.RUNNING) {
                        raceTerminated[0] = true;
                        return prev;
                    }
                    boolean statusUnchanged = "running".equals(newStatus) || "starting".equals(newStatus);
                    if (!logGrew && statusUnchanged) {
                        return prev;
                    }
                    RemoteAgentTaskState updated = prev
                        .withStatus("starting".equals(newStatus)
                            ? BackgroundTaskStatus.RUNNING : parseStatus(newStatus))
                        .withLog(new java.util.ArrayList<>(accumulatedLog));
                    if (logGrew) {
                        updated = updated.withTodoList(RemoteAgentLogParser.extractTodoListFromLog(accumulatedLog));
                    }
                    if (newProgressHolder[0] != null) {
                        updated = updated.withReviewProgress(newProgressHolder[0]);
                    }
                    if (terminal) {
                        updated = updated.withEndTime(endTime);
                    }
                    return updated;
                });
                if (raceTerminated[0]) {
                    return;
                }
                if (applied != null) {
                    framework.updateTaskState(taskId, applied.base());
                }
                // CC :723-758 完成/超时 → 通知 + evict + 删 sidecar
                if (terminal) {
                    String finalStatus = result != null && !"success".equals(String.valueOf(result.get("subtype")))
                        ? "failed" : "completed";
                    if (Boolean.TRUE.equals(task.isRemoteReview())) {
                        completeRemoteReview(taskId, task, result, reviewTimedOut, sessionDone, finalStatus);
                        return;
                    }
                    completeTask(taskId, task.title(), finalStatus, task.toolUseId());
                    return;
                }
            } catch (Exception e) {
                log.error("RemoteAgentTaskService.poll: task {} 轮询异常: {}", taskId, e.getMessage());
                // CC :760-763 — API 错误重置 idle 计数
                consecutiveIdlePolls = 0;
                // CC :765-783 — 即使 API 失败仍检查 review timeout
                try {
                    RemoteAgentTaskState task = remoteTasks.get(taskId);
                    if (task != null && Boolean.TRUE.equals(task.isRemoteReview())
                            && task.status() == BackgroundTaskStatus.RUNNING
                            && System.currentTimeMillis() - task.pollStartedAt() > REMOTE_REVIEW_TIMEOUT_MS) {
                        completeTask(taskId, task.title(), "failed", task.toolUseId());
                        return;
                    }
                } catch (Exception ignored) {
                    // best effort
                }
            }
            } finally {
                // 恢复外层原值（MDC + projectRoot）—— 定时器线程复用防泄漏
                RequestContext.clear();
                AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot);
            }
            // CC :787-789 — 继续轮询
            scheduleNext();
        }

        /**
         * 终态 + 通知 + evict + 删 sidecar · 对齐 CC archived/checker 路径（:579-589/:590-604）
         * 与 result/sessionDone/reviewTimedOut 路径（:723-758）。
         *
         * <p>状态过渡只在<b>仍 running</b>时执行（archived/checker 路径在主 updateTaskState 之前
         * 直接到达，任务仍 running）；result 路径的终态已由主 race-guard updateTaskState 设定
         * （:693-719），此处仅通知+清理。markTaskNotified 原子防重（kill 已置 notified → 不双发）。
         */
        private void completeTask(String taskId, String notifyTitle, String status, @Nullable String toolUseId) {
            remoteTasks.computeIfPresent(taskId, (k, prev) -> {
                if (prev.status() != BackgroundTaskStatus.RUNNING) {
                    return prev;
                }
                return prev
                    .withStatus("failed".equals(status) ? BackgroundTaskStatus.FAILED : BackgroundTaskStatus.COMPLETED)
                    .withEndTime(System.currentTimeMillis());
            });
            RemoteAgentTaskState s = remoteTasks.get(taskId);
            if (s != null) {
                framework.updateTaskState(taskId, s.base());
            }
            enqueueRemoteNotification(taskId, notifyTitle, status, toolUseId);
            stopPolling(taskId);
            RemoteTaskOutput.evict(taskId);
            RemoteAgentMetadataStore.delete(sessionDirSupplier.get(), taskId);
        }

        private void completeRemoteReview(String taskId, RemoteAgentTaskState task,
                @Nullable Map<String, Object> result, boolean reviewTimedOut, boolean sessionDone, String finalStatus) {
            String reviewContent = cachedReviewContent != null
                ? cachedReviewContent : RemoteAgentLogParser.extractReviewFromLog(accumulatedLog);
            if (reviewContent != null && "completed".equals(finalStatus)) {
                // CC :738 enqueueRemoteReviewNotification（注入 review 文本直进消息队列）——
                // 与 enqueueRemoteNotification 不同，不引用 output-file（review 用 tag 提取而非
                // JSONL dump），且将 findings 直接带给本地模型。
                completeRemoteReviewSuccess(taskId, reviewContent);
                return;
            }
            // CC :744-753 — 无输出/远程错误 → failed + review 专属失败通知
            String reason;
            if (result != null && !"success".equals(String.valueOf(result.get("subtype")))) {
                reason = "remote session returned an error";
            } else if (reviewTimedOut && !sessionDone) {
                reason = "remote session exceeded 30 minutes";
            } else {
                reason = "no review output — orchestrator may have exited early";
            }
            // CC :745-748 — review 无输出/错误 → 显式覆盖为 failed（主 updateTaskState 已置 completed）
            remoteTasks.computeIfPresent(taskId, (k, prev) -> {
                if (prev.status() != BackgroundTaskStatus.RUNNING) {
                    return prev;
                }
                return prev.withStatus(BackgroundTaskStatus.FAILED).withEndTime(System.currentTimeMillis());
            });
            RemoteAgentTaskState s = remoteTasks.get(taskId);
            if (s != null) {
                framework.updateTaskState(taskId, s.base());
            }
            // CC :750 — enqueueRemoteReviewFailureNotification 内部含 markTaskNotified 原子防重
            enqueueRemoteReviewFailureNotification(taskId, reason);
            stopPolling(taskId);
            RemoteTaskOutput.evict(taskId);
            RemoteAgentMetadataStore.delete(sessionDirSupplier.get(), taskId);
        }

        /**
         * remote-review 完成路径 · 对齐 CC :736-742（reviewContent 非空 + completed →
         * enqueueRemoteReviewNotification → evict → 删 sidecar → return）。
         * 状态过渡只在仍 running 时执行（result 路径终态已由主 race-guard 设定）。
         */
        private void completeRemoteReviewSuccess(String taskId, String reviewContent) {
            remoteTasks.computeIfPresent(taskId, (k, prev) -> {
                if (prev.status() != BackgroundTaskStatus.RUNNING) {
                    return prev;
                }
                return prev.withStatus(BackgroundTaskStatus.COMPLETED).withEndTime(System.currentTimeMillis());
            });
            RemoteAgentTaskState s = remoteTasks.get(taskId);
            if (s != null) {
                framework.updateTaskState(taskId, s.base());
            }
            enqueueRemoteReviewNotification(taskId, reviewContent);
            stopPolling(taskId);
            RemoteTaskOutput.evict(taskId);
            RemoteAgentMetadataStore.delete(sessionDirSupplier.get(), taskId);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 通知（enqueueRemoteNotification / markTaskNotified）
    // ────────────────────────────────────────────────────────────────────

    /**
     * CC enqueueRemoteNotification（:166-183）— markTaskNotified 原子防重后入队。
     * 状态文案：completed→"completed successfully" / failed→"failed" / killed→"was stopped"。
     */
    private void enqueueRemoteNotification(String taskId, String title, String status, @Nullable String toolUseId) {
        // CC :168 — 原子防重；false 已通知则跳过
        if (!markTaskNotified(taskId)) {
            return;
        }
        String statusText = "completed".equals(status) ? "completed successfully"
            : "failed".equals(status) ? "failed" : "was stopped";
        String toolUseIdLine = toolUseId != null && !toolUseId.isEmpty()
            ? "\n<tool-use-id>" + toolUseId + "</tool-use-id>" : "";
        Path outputPath = taskOutputDirSupplier.get().resolve(taskId + ".output");
        String message = "<task-notification>\n"
            + "<task-id>" + taskId + "</task-id>" + toolUseIdLine + "\n"
            + "<task-type>remote_agent</task-type>\n"
            + "<output-file>" + outputPath + "</output-file>\n"
            + "<status>" + status + "</status>\n"
            + "<summary>Remote task \"" + title + "\" " + statusText + "</summary>\n"
            + "</task-notification>";
        // Phase 4 (cron-notify): 通知带<b>本地创建会话</b> sessionId（base.sessionId() ——
        // registerRemoteAgentTask 捕获的 creatingSession，非 remote CCR sessionId）→ drain 3a
        // 注入创建会话回合（会话活跃时）；null（无本地会话/恢复路径）回落全局。
        String creatingSessionId = remoteTasks.get(taskId) != null ? remoteTasks.get(taskId).base().sessionId() : null;
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(message, NotificationQueue.MODE_TASK_NOTIFICATION,
                null, null, null, false, null, false, null, creatingSessionId));
        if (log.isDebugEnabled()) {
            log.debug("RemoteAgentTaskService.enqueueRemoteNotification: taskId={} status={} creatingSessionId={}", taskId, status, creatingSessionId);
        }
    }

    /** CC enqueueRemoteReviewFailureNotification（:347-360）— review 专属失败通知。 */
    private void enqueueRemoteReviewFailureNotification(String taskId, String reason) {
        if (!markTaskNotified(taskId)) {
            return;
        }
        String message = "<task-notification>\n"
            + "<task-id>" + taskId + "</task-id>\n"
            + "<task-type>remote_agent</task-type>\n"
            + "<status>failed</status>\n"
            + "<summary>Remote review failed: " + reason + "</summary>\n"
            + "</task-notification>\n"
            + "Remote review did not produce output (" + reason + "). Tell the user to retry /ultrareview, or use /review for a local review instead.";
        // Phase 4 (cron-notify): 本地创建会话（base.sessionId()，非 remote CCR sessionId）→ drain 3a
        // 注入创建会话回合；null 回落全局。
        String creatingSessionId = remoteTasks.get(taskId) != null ? remoteTasks.get(taskId).base().sessionId() : null;
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(message, NotificationQueue.MODE_TASK_NOTIFICATION,
                null, null, null, false, null, false, null, creatingSessionId));
    }

    /**
     * CC enqueueRemoteReviewNotification（:327-342）— remote-review <b>完成</b>通知。
     * 与 enqueueRemoteNotification 不同：直接注入 review 文本到消息队列（无文件间接、无
     * output-file 引用——review 内容从 tag 提取，JSONL dump 对 plan 提取无用）。
     * Session 保持存活（claude.ai URL 可回访，TTL 清理）。
     */
    private void enqueueRemoteReviewNotification(String taskId, String reviewContent) {
        if (!markTaskNotified(taskId)) {
            return;
        }
        String message = "<task-notification>\n"
            + "<task-id>" + taskId + "</task-id>\n"
            + "<task-type>remote_agent</task-type>\n"
            + "<status>completed</status>\n"
            + "<summary>Remote review completed</summary>\n"
            + "</task-notification>\n"
            + "The remote review produced the following findings:\n\n"
            + reviewContent;
        // Phase 4 (cron-notify): 本地创建会话（base.sessionId()，非 remote CCR sessionId）→ drain 3a
        // 注入创建会话回合；null 回落全局。
        String creatingSessionId = remoteTasks.get(taskId) != null ? remoteTasks.get(taskId).base().sessionId() : null;
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(message, NotificationQueue.MODE_TASK_NOTIFICATION,
                null, null, null, false, null, false, null, creatingSessionId));
        if (log.isDebugEnabled()) {
            log.debug("RemoteAgentTaskService.enqueueRemoteReviewNotification: taskId={} 注入 review 文本 creatingSessionId={}", taskId, creatingSessionId);
        }
    }

    /**
     * CC enqueueUltraplanFailureNotification（:225-239，export）— ultraplan 专属失败通知。
     * 与 enqueueRemoteNotification 不同：不指示模型读取 output-file（JSONL dump 对 plan 提取无用），
     * 改为引导访问 claude.ai session URL 并本地重试 plan mode。
     *
     * <p>CC 当前无调用方（exported for future UltraplanTask）；Java 侧同暴露 public 供
     * ultraplan completion checker / 后续任务调用。
     */
    public void enqueueUltraplanFailureNotification(String taskId, String sessionId, String reason) {
        if (!markTaskNotified(taskId)) {
            return;
        }
        String sessionUrl = getRemoteTaskSessionUrl(sessionId);
        String message = "<task-notification>\n"
            + "<task-id>" + taskId + "</task-id>\n"
            + "<task-type>remote_agent</task-type>\n"
            + "<status>failed</status>\n"
            + "<summary>Ultraplan failed: " + reason + "</summary>\n"
            + "</task-notification>\n"
            + "The remote Ultraplan session did not produce a plan (" + reason + "). Inspect the session at "
            + sessionUrl + " and tell the user to retry locally with plan mode.";
        // Phase 4 (cron-notify): 通知带<b>本地创建会话</b> sessionId（base.sessionId()，非 remote
        // CCR sessionId）→ drain 3a 注入创建会话回合。remote sessionId 仅用于上方 URL 拼接。
        String createSessionId = remoteTasks.get(taskId) != null ? remoteTasks.get(taskId).base().sessionId() : null;
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(message, NotificationQueue.MODE_TASK_NOTIFICATION,
                null, null, null, false, null, false, null, createSessionId));
        if (log.isDebugEnabled()) {
            log.debug("RemoteAgentTaskService.enqueueUltraplanFailureNotification: taskId={} remoteSessionId={} createSessionId={}",
                taskId, sessionId, createSessionId);
        }
    }

    /**
     * CC getRemoteTaskSessionUrl（RemoteAgentTask.tsx:853-855 → product.ts:65-76 getRemoteSessionUrl）。
     * baseUrl 按 sessionId 形态选择（_local_→localhost / _staging_→staging / 否则 https://claude.ai）；
     * CC 的 cse_→session_ compat shim（product.ts:73 toCompatSessionId）依赖 tengu bridge
     * 特性门控，Java 侧 CCR sessionId 已是 API 形态，此处透传原样（若后续引入 cse_ 前缀再补转换）。
     */
    public static String getRemoteTaskSessionUrl(String sessionId) {
        String baseUrl = "https://claude.ai";
        if (sessionId != null && sessionId.contains("_local_")) {
            baseUrl = "http://localhost:4000";
        } else if (sessionId != null && sessionId.contains("_staging_")) {
            baseUrl = "https://claude-ai.staging.ant.dev";
        }
        return baseUrl + "/code/" + sessionId;
    }

    /**
     * CC markTaskNotified（:189-202）— 原子检查并翻转 notified flag。
     * 返回 true = 本次翻转（应入队）；false = 已通知（跳过）。
     */
    private boolean markTaskNotified(String taskId) {
        AtomicBoolean flipped = new AtomicBoolean(false);
        remoteTasks.computeIfPresent(taskId, (k, prev) -> {
            if (prev.notified()) {
                return prev;
            }
            flipped.set(true);
            return prev.withNotified();
        });
        if (flipped.get()) {
            RemoteAgentTaskState s = remoteTasks.get(taskId);
            if (s != null) {
                framework.updateTaskState(taskId, s.base());
            }
        }
        return flipped.get();
    }

    // ────────────────────────────────────────────────────────────────────
    // poll helpers
    // ────────────────────────────────────────────────────────────────────

    /** CC :569-574 — assistant 文本块拼接；其他事件 JSON stringify。 */
    private static String deltaText(List<Map<String, Object>> newEvents, @Nullable Boolean isRemoteReview) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : newEvents) {
            String text;
            if (msg != null && "assistant".equals(String.valueOf(msg.get("type")))) {
                Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
                text = RemoteAgentLogParser.extractTextContent(content, "\n");
            } else {
                text = RemoteAgentLogParser.jsonStringify(msg);
            }
            if (text != null && !text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** CC :610 — findLast(msg.type==='result')。 */
    private static Map<String, Object> findLastResult(List<Map<String, Object>> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (msg != null && "result".equals(String.valueOf(msg.get("type")))) {
                return msg;
            }
        }
        return null;
    }

    /** CC :660 — hasAnyOutput（assistant 或 remote-review 的 hook stdout）。 */
    private static boolean hasAnyOutput(List<Map<String, Object>> log, @Nullable Boolean isRemoteReview) {
        for (Map<String, Object> msg : log) {
            if (msg == null) {
                continue;
            }
            if ("assistant".equals(String.valueOf(msg.get("type")))) {
                return true;
            }
            if (Boolean.TRUE.equals(isRemoteReview) && "system".equals(String.valueOf(msg.get("type")))) {
                String subtype = String.valueOf(msg.get("subtype"));
                if ("hook_progress".equals(subtype) || "hook_response".equals(subtype)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** CC :681-683 — SessionStart hook 事件存在性。 */
    private static boolean hasSessionStartHook(List<Map<String, Object>> log) {
        for (Map<String, Object> msg : log) {
            if (msg == null || !"system".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            String subtype = String.valueOf(msg.get("subtype"));
            if (!"hook_started".equals(subtype) && !"hook_progress".equals(subtype)
                    && !"hook_response".equals(subtype)) {
                continue;
            }
            if ("SessionStart".equals(String.valueOf(msg.get("hook_event")))) {
                return true;
            }
        }
        return false;
    }

    /** CC :684 — hasAssistantEvents。 */
    private static boolean hasAssistantEvents(List<Map<String, Object>> log) {
        for (Map<String, Object> msg : log) {
            if (msg != null && "assistant".equals(String.valueOf(msg.get("type")))) {
                return true;
            }
        }
        return false;
    }

    /** CC :627-656 — hook stdout 心跳 <remote-review-progress> 解析，取最后一次出现。 */
    @SuppressWarnings("unchecked")
    private static RemoteAgentTaskState.ReviewProgress parseReviewProgress(List<Map<String, Object>> newEvents) {
        RemoteAgentTaskState.ReviewProgress progress = null;
        String open = "<" + RemoteAgentLogParser.REMOTE_REVIEW_PROGRESS_TAG + ">";
        String close = "</" + RemoteAgentLogParser.REMOTE_REVIEW_PROGRESS_TAG + ">";
        for (Map<String, Object> ev : newEvents) {
            if (ev == null || !"system".equals(String.valueOf(ev.get("type")))) {
                continue;
            }
            String subtype = String.valueOf(ev.get("subtype"));
            if (!"hook_progress".equals(subtype) && !"hook_response".equals(subtype)) {
                continue;
            }
            String s = String.valueOf(ev.get("stdout"));
            int closeAt = s.lastIndexOf(close);
            int openAt = closeAt == -1 ? -1 : s.lastIndexOf(open, closeAt);
            if (openAt == -1 || closeAt <= openAt) {
                continue;
            }
            try {
                Map<String, Object> p = JSON_READER.readValue(
                    s.substring(openAt + open.length(), closeAt), Map.class);
                progress = new RemoteAgentTaskState.ReviewProgress(
                    String.valueOf(p.get("stage")),
                    intVal(p.get("bugs_found")), intVal(p.get("bugs_verified")), intVal(p.get("bugs_refuted")));
            } catch (Exception ignored) {
                // 忽略 malformed progress（CC :650-652）
            }
        }
        return progress;
    }

    private static int intVal(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** newStatus 字符串 → 状态枚举（'starting' 已被调用方归一到 running）。 */
    private static BackgroundTaskStatus parseStatus(String status) {
        return switch (status) {
            case "completed" -> BackgroundTaskStatus.COMPLETED;
            case "failed" -> BackgroundTaskStatus.FAILED;
            case "killed" -> BackgroundTaskStatus.KILLED;
            default -> BackgroundTaskStatus.RUNNING;
        };
    }
}
