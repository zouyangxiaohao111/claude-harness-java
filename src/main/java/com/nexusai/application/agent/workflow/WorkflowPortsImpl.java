package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.workflow.registry.WorkflowRegistry;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * WorkflowPorts Spring 实现 · CC original: {@code createWorkflowPorts}
 * (Open-ClaudeCode/src/workflow/ports.ts:71-202)。
 *
 * <p>等价装配：telemetry 订阅 + {@code taskRegistrar}（内存 RunBinding Map）+ 8 项 ports。
 *
 * <p><b>注入现有依赖</b>：
 * <ul>
 *   <li>{@link ProgressBus} — P0 内存进度总线（<b>W-3b 定案</b>：CC 无 emitTaskProgress 调用点，{@code workflow_progress} 是预留缝——Java 不补 emitTaskProgress 通道；进度出站 = WorkflowService.getRunAsync/listRuns/getRun 查询 + 前端直读 store，SdkEventQueue.TaskProgressEvent.workflowProgress 字段保留（协议对齐））</li>
 *   <li>{@link AnalyticsTracker} — telemetry（{@code logEvent} 等价）</li>
 *   <li>{@link ObjectMapper} — journal jsonl 序列化</li>
 *   <li>{@link CwdResolution} — projectRoot/runsDir 解析（会话绑定启动目录，对齐
 *       {@code getProjectRoot} bootstrap/state.ts + memory session-bound-dir-is-cc-startup-dir）</li>
 *   <li>{@link ToolUseContext} — 宿主 bundle 载荷（setAppState/toolUseId/agentId）</li>
 *   <li>{@link AbortController} — signal 语义（对齐 CC AbortSignal）</li>
 *   <li>{@link TaskType#LOCAL_WORKFLOW} — taskId 前缀 'w'（对齐 Task.ts:79-87 TASK_ID_PREFIXES）</li>
 * </ul>
 *
 * <p><b>P0 边界</b>（DEC-P0-04/05/06）：agentRunner fail-fast（registry 必设）；
 * {@code runId = opts.runId ?? 生成Id}；taskRegistrar 落内存不接真 runner（P3 W-4b）。
 */
@Component
public class WorkflowPortsImpl implements WorkflowPorts {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPortsImpl.class);

    /** taskRegistrar bindings Map：runId → RunBinding（kill 路由用）· 对齐 CC ports.ts:75 bindings */
    private final Map<String, RunBinding> bindings = new ConcurrentHashMap<>();

    private final ProgressBus bus;
    private final AnalyticsTracker analytics;
    private final ObjectMapper objectMapper;
    private final Supplier<String> runsDirProvider;

    /** W-2a 真注册表（claude-code default）· 对齐 CC buildRegistry() 产物 */
    private final AgentAdapterRegistry agentAdapterRegistry;

    /** 单例 taskRegistrar（共享 bindings Map，对齐 CC 闭包） */
    private final TaskRegistrar taskRegistrar = new InMemoryTaskRegistrar();

    private final AgentRunner agentRunner = new FailFastAgentRunner();
    private final ProgressEmitter progressEmitter = this::emitToBus;
    private final PermissionGate permissionGate = host -> false;
    private final WorkflowLogger logger = new Slf4jWorkflowLogger();
    private final HostFactory hostFactory = this::createHostContext;

    /** journalStore 惰性缓存（对齐 CC createWorkflowPorts 时创建一次；runsDir 按会话首访解析） */
    private volatile JournalStore journalStore;

    /**
     * W-4b: local_workflow 真 runner · 对齐 CC {@code registerLocalWorkflowTask} /
     * {@code completeWorkflowTask} / {@code failWorkflowTask} / {@code killWorkflowTask}
     * （LocalWorkflowTask.tsx:53-216）经 {@link BackgroundTaskRunner} 落地（registerTask 入
     * TaskFrameworkService store + taskAbortControllers 存 task-scoped AbortController +
     * 终态 updateTaskState + emitTerminatedSdk）。可为 null（测试直构不接 runner → 落内存
     * RunBinding 的 P0 行为，tasks 不入框架 store，stopTask NOT_FOUND fail-closed）。
     */
    private final BackgroundTaskRunner backgroundTaskRunner;

    /**
     * Spring 注入构造（默认 runsDirProvider = 会话绑定项目根 + {@link WorkflowConstants#WORKFLOW_RUNS_DIR}
     * = .{appName}/workflow-runs，appName 默认 nexusai → .nexusai/workflow-runs，决策 D6/D7 迁移自有根）。
     *
     * <p>W-2a 新增 {@code SubagentExecutor} 注入：经 {@link WorkflowRegistry#buildRegistry}
     * 装配 claude-code default adapter（对齐 CC buildRegistry()，registry.ts:9-13）。
     * {@code @Lazy} 打破潜在 bean 循环（SubagentExecutor → 懒 toolRegistry → WorkflowTool →
     * WorkflowService → 本类；@Lazy 与 ToolRegistrationConfig 的 toolRegistry @Lazy 同型防护）。
     *
     * <p>D-1 新增 {@link AgentWorktreeManager}（Spring @Component，依赖 WorktreeService）注入：
     * 经 buildRegistry 传入 ClaudeCodeBackendAdapter，供 {@code isolation:'worktree'} fail-closed
     * 建树（CC claudeCodeBackend.ts:219-234）；与 SubagentExecutor 无循环（WorktreeService 无
     * 反向依赖）。
     *
     * <p>W-4b 新增 {@link BackgroundTaskRunner} 注入：taskRegistrar 落真 local_workflow 任务
     * （对齐 ports.ts:91-175 + LocalWorkflowTask.tsx）。{@code @Lazy} 与 SubagentExecutor 同型防护
     * （BackgroundTaskRunner → SpawnInProcess → SubagentExecutor 链上避免循环解析）。
     */
    @Autowired
    public WorkflowPortsImpl(ProgressBus bus, AnalyticsTracker analytics, ObjectMapper objectMapper,
                             @org.springframework.context.annotation.Lazy SubagentExecutor subagentExecutor,
                             AgentWorktreeManager worktreeManager,
                             @org.springframework.context.annotation.Lazy BackgroundTaskRunner backgroundTaskRunner) {
        this(bus, analytics, objectMapper, WorkflowPortsImpl::defaultRunsDir, subagentExecutor, worktreeManager,
                backgroundTaskRunner);
    }

    /**
     * 测试构造：固定 runsDir（脱离会话解析，单测可复现）。
     */
    WorkflowPortsImpl(ProgressBus bus, AnalyticsTracker analytics, ObjectMapper objectMapper, String runsDir,
                      SubagentExecutor subagentExecutor, AgentWorktreeManager worktreeManager,
                      BackgroundTaskRunner backgroundTaskRunner) {
        this(bus, analytics, objectMapper, () -> runsDir, subagentExecutor, worktreeManager, backgroundTaskRunner);
    }

    /**
     * 全量构造：自定义 runsDirProvider（生产按会话解析；测试注入固定值）。
     */
    WorkflowPortsImpl(ProgressBus bus, AnalyticsTracker analytics, ObjectMapper objectMapper,
                      Supplier<String> runsDirProvider, SubagentExecutor subagentExecutor,
                      AgentWorktreeManager worktreeManager, BackgroundTaskRunner backgroundTaskRunner) {
        this.bus = bus;
        this.analytics = analytics;
        this.objectMapper = objectMapper;
        this.runsDirProvider = runsDirProvider;
        this.backgroundTaskRunner = backgroundTaskRunner;
        // W-2a 装配真注册表（claude-code default）· 对齐 CC buildRegistry()；D-1 注入 worktreeManager
        //（isolation:'worktree' fail-closed 建树，见 ClaudeCodeBackendAdapter）
        this.agentAdapterRegistry = WorkflowRegistry.buildRegistry(subagentExecutor, worktreeManager);
        // telemetry 订阅（独立于 store）· 对齐 CC ports.ts:81-89
        bus.subscribe(this::onProgressEvent);
        log.info("WorkflowPorts 装配完成：bindings Map 空表、agentAdapterRegistry 注册 claude-code default（W-2a），telemetry 订阅就绪");
    }

    /**
     * 默认 runsDir · 对齐 CC {@code getRunsDir() = join(getProjectRoot(), '.claude', 'workflow-runs')}
     * (persistence.ts:32-34)。决策 D6/D7：目录迁至 nexusai 自有
     * {@code <projectRoot>/<WORKFLOW_RUNS_DIR>}（appName=nexusai → {@code .nexusai/workflow-runs}）。
     * projectRoot = 会话绑定启动目录（boundProject/originalCwd 层，非可变 getCwd——防 worktree/
     * 子目录 desync，ports.ts:55-59 注释）。
     */
    /** 包可见：WorkflowServiceImpl 生产 runsDirProvider 复用（W-3c 持久化同根）。 */
    static String defaultRunsDir() {
        String projectRoot = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
        if (log.isDebugEnabled()) {
            log.debug("WorkflowPorts.defaultRunsDir: projectRoot={} runsDir={}/{}",
                    projectRoot, projectRoot, WorkflowConstants.WORKFLOW_RUNS_DIR);
        }
        return projectRoot + "/" + WorkflowConstants.WORKFLOW_RUNS_DIR;
    }

    // ────────────────────────────────────────────────────────────────────
    // telemetry 订阅（对齐 CC ports.ts:81-89）
    // ────────────────────────────────────────────────────────────────────

    /**
     * run_done → tengu_workflow_done telemetry · 对齐 CC ports.ts:81-89
     * {@code logEvent('tengu_workflow_done', {status: 0|1|2, runId})}（completed→0 failed→1 其余→2）。
     *
     * <p>AnalyticsMetadata 品牌 cast 约束（仅 boolean/number/undefined）：runId 为 string，
     * 经 AnalyticsTracker 的 Map 承载（Java 端无该 cast 约束，track 仅内存收集）。
     */
    private void onProgressEvent(ProgressEvent event) {
        if (event instanceof ProgressEvent.RunDone runDone) {
            int status = switch (runDone.status()) {
                case COMPLETED -> 0;
                case FAILED -> 1;
                case KILLED -> 2;
            };
            // [R1 B10] AnalyticsTracker.track 已删（logEvent 统一通道）· runId 为运行标识符非
            //   code/filepath，经 verified() 包装放行（对齐 CC ports.ts:83-87 logEvent metadata）。
            analytics.logEvent("tengu_workflow_done",
                    Map.of("status", status, "runId", AnalyticsTracker.verified(runDone.runId())));
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts telemetry tengu_workflow_done: runId={} status={}",
                        runDone.runId(), status);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 8 项 ports（对齐 engine/ports.ts:136-149）
    // ────────────────────────────────────────────────────────────────────

    @Override
    public AgentRunner agentRunner() {
        return agentRunner;
    }

    @Override
    public AgentAdapterRegistry agentAdapterRegistry() {
        return agentAdapterRegistry;
    }

    @Override
    public ProgressEmitter progressEmitter() {
        return progressEmitter;
    }

    @Override
    public TaskRegistrar taskRegistrar() {
        return taskRegistrar;
    }

    @Override
    public JournalStore journalStore() {
        JournalStore local = this.journalStore;
        if (local == null) {
            synchronized (this) {
                local = this.journalStore;
                if (local == null) {
                    local = new FileJournalStore(runsDirProvider.get(), objectMapper);
                    this.journalStore = local;
                    if (log.isDebugEnabled()) {
                        log.debug("WorkflowPorts.journalStore: 创建 FileJournalStore（首次访问）runsDir={}",
                                runsDirProvider.get());
                    }
                }
            }
        }
        return local;
    }

    @Override
    public PermissionGate permissionGate() {
        return permissionGate;
    }

    @Override
    public WorkflowLogger logger() {
        return logger;
    }

    @Override
    public HostFactory hostFactory() {
        return hostFactory;
    }

    /** progressEmitter.emit → bus.emit（对齐 CC ports.ts:188-191） */
    private void emitToBus(ProgressEvent event) {
        bus.emit(event);
    }

    // ────────────────────────────────────────────────────────────────────
    // hostFactory（对齐 CC ports.ts:42-65 makeHostFactory）
    // ────────────────────────────────────────────────────────────────────

    /**
     * 构造 WorkflowHostContext · 对齐 CC makeHostFactory（ports.ts:42-65）：
     * <pre>
     * ({context, canUseTool, parentMessage}) => {
     *   const ctx = context as WorkflowHostBundle['toolUseContext'] & {agentId?}
     *   return { handle: makeHostHandle(buildHostBundle(ctx, canUseTool, parentMessage)),
     *            cwd: getProjectRoot(), budgetTotal: null, ...(ctx.toolUseId ? {toolUseId} : {}) }
     * }
     * </pre>
     */
    private WorkflowHostContext createHostContext(HostFactory.HostFactoryArgs args) {
        Object context = args.context();
        if (!(context instanceof ToolUseContext toolUseContext)) {
            throw new IllegalStateException(
                    "hostFactory context 必须是 ToolUseContext，实际: "
                            + (context == null ? "null" : context.getClass().getName()));
        }
        WorkflowHostBundle bundle = WorkflowHostBundle.build(
                toolUseContext, args.canUseTool(), args.parentMessage());
        HostHandle handle = HostHandle.create(bundle);
        // cwd 用 projectRoot 而非 getCwd()：与 journalStore 的 runsDir 同根（ports.ts:55-59）
        String cwd = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
        String toolUseId = toolUseContext.toolUseId();
        if (log.isDebugEnabled()) {
            log.debug("WorkflowPorts.hostFactory: cwd={} toolUseId={} agentId={}",
                    cwd, toolUseId, toolUseContext.agentId());
        }
        return new WorkflowHostContext(handle, cwd, null, toolUseId);
    }

    // ────────────────────────────────────────────────────────────────────
    // taskRegistrar 实现（对齐 CC ports.ts:91-175）+ RunBinding
    // ────────────────────────────────────────────────────────────────────

    /**
     * runId → RunBinding · CC original: {@code RunBinding} (src/workflow/ports.ts:31-39)。
     *
     * @param runId                CC original: {@code runId}（resume 可复用外部 id）
     * @param taskId               CC original: {@code taskId}（registerLocalWorkflowTask 返回值；P0 生成 'w-' 前缀）
     * @param setAppState          CC original: {@code setAppState}（toolUseContext.setAppStateForTasks ??
     *                             setAppState；React 面板状态更新）
     * @param abortController      CC original: {@code abortController}（内部 abort 控制器）
     * @param workflowName         CC original: {@code workflowName}
     * @param agentAbortControllers CC original: {@code agentAbortControllers: Map<number, AbortController>} —
     *                             backend 启动 agent 时登记；killAgent 用其精确 abort
     */
    record RunBinding(String runId, String taskId,
                      Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                      AbortController abortController, String workflowName,
                      Map<Integer, AbortController> agentAbortControllers) {
    }

    /** taskId 生成 · CC 任务 id 前缀 'w'（Task.ts:79-87 TASK_ID_PREFIXES） */
    private static String generateTaskId() {
        return TaskType.LOCAL_WORKFLOW.getIdPrefix() + "-" + UUID.randomUUID();
    }

    /**
     * 内存版 taskRegistrar · 对齐 CC ports.ts:91-175。
     * P0 不接 TaskType.LOCAL_WORKFLOW 真 runner（P3 W-4b），register 落内存 RunBinding。
     */
    private final class InMemoryTaskRegistrar implements TaskRegistrar {

        @Override
        public RegisterResult register(RegisterOpts opts, HostHandle host) {
            if (opts == null || opts.workflowName() == null || opts.workflowName().isBlank()) {
                throw new IllegalArgumentException("workflowName 必填");
            }
            WorkflowHostBundle bundle = readHostBundle(host);
            // setAppState = bundle.toolUseContext.setAppStateForTasks ?? setAppState（ports.ts:94-96；
            // Java ToolUseContext 无 setAppStateForTasks，直接用 setAppState）
            Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState =
                    bundle.toolUseContext() != null ? bundle.toolUseContext().setAppState() : null;
            AbortController abortController = new AbortController();
            String taskId = generateTaskId();
            // DEC-P0-05：runId = opts.runId ?? taskId（resume 复用外部 runId，ports.ts:106）
            String runId = opts.runId() != null ? opts.runId() : taskId;
            RunBinding binding = new RunBinding(runId, taskId, setAppState, abortController,
                    opts.workflowName(), new ConcurrentHashMap<>());
            bindings.put(runId, binding);
            // W-4b: 真 local_workflow 任务注册（对齐 ports.ts:98-105 registerLocalWorkflowTask +
            //   LocalWorkflowTask.tsx:53-83）—— description = summary ?? workflowName（ports.ts:99）。
            //   backgroundTaskRunner 未接线（测试直构）→ 仅落内存 RunBinding（P0 行为）。
            if (backgroundTaskRunner != null) {
                String description = (opts.summary() != null && !opts.summary().isBlank())
                        ? opts.summary() : opts.workflowName();
                String sessionId = (bundle.toolUseContext() != null
                        && bundle.toolUseContext().sessionId() != null)
                        ? bundle.toolUseContext().sessionId() : null;
                backgroundTaskRunner.registerWorkflowTask(taskId, description, opts.workflowName(),
                        abortController, opts.toolUseId(), sessionId);
            }
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts.taskRegistrar.register: runId={} taskId={} workflowName={} 绑定数={}",
                        runId, taskId, opts.workflowName(), bindings.size());
            }
            return new RegisterResult(runId, abortController);
        }

        @Override
        public void complete(String runId, String summary) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return; // CC ports.ts:120-122 未登记 no-op
            }
            // W-4b：completeWorkflowTask（对齐 LocalWorkflowTask.tsx:85-96 ——
            //   status=completed + endTime + notified + emitTerminatedSdk）
            if (backgroundTaskRunner != null) {
                backgroundTaskRunner.completeWorkflowTask(b.taskId());
            }
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts.taskRegistrar.complete: runId={} workflowName={} summary={}",
                        runId, b.workflowName(), summary);
            }
            bindings.remove(runId);
        }

        @Override
        public void fail(String runId, String error) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return; // CC ports.ts:127-129 未登记 no-op
            }
            // W-4b：failWorkflowTask（对齐 LocalWorkflowTask.tsx:98-111 ——
            //   status=failed + endTime + notified + emitTerminatedSdk）
            if (backgroundTaskRunner != null) {
                backgroundTaskRunner.failWorkflowTask(b.taskId());
            }
            log.warn("WorkflowPorts.taskRegistrar.fail: runId={} workflowName={} error={}",
                    runId, b.workflowName(), error);
            bindings.remove(runId);
        }

        @Override
        public void kill(String runId) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return; // CC ports.ts:134-136 未登记 no-op
            }
            // W-4b：killWorkflowTask（对齐 LocalWorkflowTask.tsx:117-132 —— only-if-running +
            //   task.abortController?.abort() + status=killed + endTime + notified +
            //   emitTerminatedSdk）。下方 P0 行仍保留 abort 内部控制器（幂等，双保险：
            //   killWorkflowTask 经 taskAbortControllers 存的是同一实例）。
            if (backgroundTaskRunner != null) {
                backgroundTaskRunner.killWorkflowTask(b.taskId());
            }
            b.abortController().abort();
            // 同时 abort 全部 in-flight agent（防边界时序——backend 漏 task abort）· CC ports.ts:139-147
            for (AbortController ac : b.agentAbortControllers().values()) {
                try {
                    ac.abort();
                } catch (Exception e) {
                    log.warn("WorkflowPorts.taskRegistrar.kill: agent abort 异常（no-op 兜底）runId={} reason={}",
                            runId, e.toString());
                }
            }
            b.agentAbortControllers().clear();
            log.warn("WorkflowPorts.taskRegistrar.kill: runId={} workflowName={} 已中止 run + {} 个 in-flight agent",
                    runId, b.workflowName(), b.agentAbortControllers().size());
            bindings.remove(runId);
        }

        @Override
        public void registerAgentAbort(String runId, int agentId, AbortController abortController) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return;
            }
            b.agentAbortControllers().put(agentId, abortController);
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts.taskRegistrar.registerAgentAbort: runId={} agentId={}", runId, agentId);
            }
        }

        @Override
        public void unregisterAgentAbort(String runId, int agentId) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return;
            }
            b.agentAbortControllers().remove(agentId);
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts.taskRegistrar.unregisterAgentAbort: runId={} agentId={}", runId, agentId);
            }
        }

        @Override
        public boolean killAgent(String runId, int agentId) {
            RunBinding b = bindings.get(runId);
            if (b == null) {
                return false;
            }
            AbortController ac = b.agentAbortControllers().remove(agentId);
            if (ac == null) {
                return false;
            }
            try {
                ac.abort();
            } catch (Exception e) {
                log.warn("WorkflowPorts.taskRegistrar.killAgent: abort 异常（no-op 兜底）runId={} agentId={} reason={}",
                        runId, agentId, e.toString());
            }
            if (log.isDebugEnabled()) {
                log.debug("WorkflowPorts.taskRegistrar.killAgent: 已精确 abort agent runId={} agentId={}",
                        runId, agentId);
            }
            return true;
        }

        @Override
        public PendingAction pendingAction(String runId) {
            return null; // v1: skip/retry 未接线（seam 保留）· CC ports.ts:172-174
        }
    }

    /** 解包 host → bundle · 对齐 CC readHostBundle（hostHandle.ts:40-42） */
    private static WorkflowHostBundle readHostBundle(HostHandle host) {
        Object bundle = HostHandle.unwrap(host);
        if (bundle instanceof WorkflowHostBundle wb) {
            return wb;
        }
        throw new IllegalStateException("HostHandle 载荷必须是 WorkflowHostBundle，实际: "
                + (bundle == null ? "null" : bundle.getClass().getName()));
    }

    // ────────────────────────────────────────────────────────────────────
    // 其余端口实现
    // ────────────────────────────────────────────────────────────────────

    /**
     * agentRunner fail-fast 死代码兜底 · 对齐 CC ports.ts:180-187（DEC-P0-04）。
     * 达此路径 = registry 未注册，直接失败（hook 必走 agentAdapterRegistry）。
     */
    private static final class FailFastAgentRunner implements AgentRunner {
        @Override
        public CompletableFuture<AgentRunResult> runAgentToResult(AgentRunParams params, HostHandle host) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "workflow agentRunner fallback reached — agentAdapterRegistry 必须设置（P0 空表，P1 W-2a 注入 adapter）"));
        }
    }

    /** logger · 对齐 CC ports.ts:196-200（委托 logForDebugging 等价 = slf4j debug） */
    private static final class Slf4jWorkflowLogger implements WorkflowLogger {
        private static final Logger log = LoggerFactory.getLogger(Slf4jWorkflowLogger.class);

        @Override
        public void debug(String message) {
            if (log.isDebugEnabled()) {
                log.debug("{}", message);
            }
        }

        @Override
        public void warn(String message, Object... args) {
            // 对齐 CC ports.ts:197（"[workflow warn] " 前缀）；SLF4J 格式参数透传（W-1c 引擎 varargs 调用）
            log.warn("[workflow warn] " + message, args);
        }

        @Override
        public void event(String name, Map<String, Object> metadata) {
            if (log.isDebugEnabled()) {
                log.debug("workflow event: {} {}", name, metadata); // 对齐 CC ports.ts:198-199
            }
        }
    }
}
