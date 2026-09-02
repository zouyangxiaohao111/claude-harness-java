package com.nexusai.application.agent.workflow;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.workflow.RunStatus;
import com.nexusai.application.agent.workflow.engine.RunWorkflowOptions;
import com.nexusai.application.agent.workflow.engine.WorkflowRunEngine;
import com.nexusai.application.agent.workflow.notifications.WorkflowNotifications;
import com.nexusai.application.agent.workflow.persistence.WorkflowRunPersistence;
import com.nexusai.application.agent.workflow.progress.ProgressStore;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import com.nexusai.application.agent.workflow.script.ScriptError;
import com.nexusai.application.agent.workflow.script.WorkflowScriptParser;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * WorkflowService 实现（makeService 等价 + Spring 进程单例）· CC original: {@code makeService}
 * (Open-ClaudeCode/src/workflow/service.ts:122-318) + {@code getWorkflowService} (service.ts:98-111)。
 *
 * <p><b>进程单例语义</b>（service.ts:98-111）：bus → store → createWorkflowPorts → makeService → cached。
 * Java 用 Spring {@code @Component} 单例等价（P0-core-doc §6.4）；{@link #getWorkflowService()}
 * 静态访问器返回 {@code @PostConstruct} 登记的实例，对齐 CC {@code getWorkflowService()}。
 * 测试经 {@link #makeService} 注入 fake ports/store + cwdOverride（service.ts:119-123）。
 *
 * <p><b>launch 流程</b>（service.ts:188-257）：resolveSource（script > scriptPath > name）→
 * parseScript 快速校验（失败抛「Script validation failed」<b>不进后台</b>）→ buildHost →
 * {@code taskRegistrar.register}（resumeFromRunId → runId 复用）→ inline 脚本持久化（失败仅 log）
 * → <b>detached</b> {@code runWorkflow}（resume → resume=true）→ 完成时按 status 路由
 * complete/fail/kill（service.ts:241-250）。
 *
 * <p><b>P2 接线</b>（service.ts:103-109）：构造内（bus/queue 非 null 时）执行
 * {@code attachRunStatePersistence(bus, store)}（run_done → state.json 写盘 + cleanupOldRuns，
 * W-3c）+ {@code installWorkflowNotifications(this)}（running → 终态通知，W-3d）。
 * {@code getRunAsync/loadPersistedRuns/subscribe} 为 P2 面板/工具辅助查询（service.ts:75-92）。
 */
@Component
public final class WorkflowServiceImpl implements WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceImpl.class);

    /** 进程单例缓存 · CC original: {@code cached} (service.ts:95)。 */
    private static volatile WorkflowService cached;

    /** 面板水合上限 · CC original: LOAD_PERSISTED_LIMIT (service.ts:30)。 */
    private static final int LOAD_PERSISTED_LIMIT = 20;

    private final WorkflowPorts ports;
    private final ProgressStore store;
    /** 测试注入：覆盖 projectRoot（makeService 用，service.ts:119-121）。 */
    private final String cwdOverride;
    /** runsDir 单一源 · 生产 = WorkflowPortsImpl.defaultRunsDir 等价；测试注入 tmp（service.ts:126）。 */
    private final Supplier<String> runsDirProvider;
    /** 进度总线（run_done 持久化订阅用）· 测试 makeService 路径为 null（不接线，对齐 CC makeService）。 */
    private final ProgressBus bus;
    /** 通知队列（终态通知出站）· 测试 makeService 路径为 null（不接线）。 */
    private final NotificationQueue notificationQueue;
    /** 磁盘持久化（W-3c）· 持 runsDirProvider 单例。 */
    private final WorkflowRunPersistence persistence;

    /** 编译期校验 parser（W-1b）。 */
    private final WorkflowScriptParser parser;
    /** detached 执行引擎（W-1c）。 */
    private final WorkflowRunEngine runEngine;

    /**
     * loadPersistedRuns 进程单例 flag · CC original: {@code persistedLoaded} (service.ts:183)。
     * 首次调用置 true、扫描失败重置 false（允许下次重试）。
     */
    private volatile boolean persistedLoaded = false;

    /**
     * Spring 注入（进程单例）· CC original: getWorkflowService (service.ts:98-111)。
     *
     * <p>bus + notificationQueue 为构造后接线提供（run_done 持久化 + 终态通知），
     * runsDirProvider 生产默认 = {@code WorkflowPortsImpl.defaultRunsDir}（会话绑定项目根）。
     */
    @Autowired
    public WorkflowServiceImpl(WorkflowPorts ports, ProgressStore store, ProgressBus bus,
                               NotificationQueue notificationQueue) {
        this(ports, store, null, WorkflowPortsImpl::defaultRunsDir, bus, notificationQueue);
    }

    /** makeService 等价（测试注入 fake ports/store + cwdOverride；无 bus/queue → 不接线，对齐 CC makeService）。 */
    WorkflowServiceImpl(WorkflowPorts ports, ProgressStore store, String cwdOverride) {
        this(ports, store, cwdOverride, WorkflowPortsImpl::defaultRunsDir, null, null);
    }

    /**
     * 全量构造 · 对齐 CC makeService (service.ts:122-127) + getWorkflowService 接线（service.ts:103-109）。
     *
     * <p>bus != null 时接线：{@code attachRunStatePersistence(bus, store)}（service.ts:106，run_done
     * → 写盘 + cleanup）+ {@code installWorkflowNotifications(this)}（service.ts:108，终态通知）。
     * store 先于本构造订阅 bus（Spring 依赖序），故 run_done 时 store.get 已是终态
     * （persistence.ts:176-177 注释）。
     *
     * @param ports             注入的 ports
     * @param store             注入的 store（ProgressStore 构造时已订阅 bus）
     * @param cwdOverride       测试注入的临时目录（覆盖 projectRoot）
     * @param runsDirProvider   runsDir 单一源（生产默认 / 测试注入 tmp）
     * @param bus               进度总线（null = 测试 makeService 不接线）
     * @param notificationQueue 通知队列（null = 测试 makeService 不接线）
     */
    WorkflowServiceImpl(WorkflowPorts ports, ProgressStore store, String cwdOverride,
                        Supplier<String> runsDirProvider, ProgressBus bus, NotificationQueue notificationQueue) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.store = Objects.requireNonNull(store, "store");
        this.cwdOverride = cwdOverride;
        this.runsDirProvider = Objects.requireNonNull(runsDirProvider, "runsDirProvider");
        this.bus = bus;
        this.notificationQueue = notificationQueue;
        this.persistence = new WorkflowRunPersistence(runsDirProvider);
        this.parser = new WorkflowScriptParser();
        this.runEngine = new WorkflowRunEngine();
        // 构造后接线（service.ts:103-109）：仅生产路径（bus/queue 非 null）
        if (bus != null) {
            persistence.attachRunStatePersistence(bus, store);
            log.info("WorkflowRunPersistence 已接线 run_done → 写盘 + cleanupOldRuns（service.ts:106，persistence.ts:190-210）");
        }
        if (notificationQueue != null) {
            WorkflowNotifications.installWorkflowNotifications(this, notificationQueue);
            log.info("WorkflowNotifications 已接线 running → 终态通知（service.ts:108，notifications.ts:41-69）");
        }
    }

    /**
     * 构造注入的 makeService · CC original: makeService (service.ts:122-127)。
     *
     * @param ports        注入的 ports（测试 fake）
     * @param store        注入的 store（测试 fake）
     * @param cwdOverride  测试注入的临时目录（覆盖 projectRoot，避开真实项目目录写盘）
     * @return 独立 service 实例（非单例，测试用）
     */
    public static WorkflowService makeService(WorkflowPorts ports, ProgressStore store, String cwdOverride) {
        return new WorkflowServiceImpl(ports, store, cwdOverride);
    }

    /** 登记进程单例 · CC original: getWorkflowService 的 cached 赋值（service.ts:109）。 */
    @PostConstruct
    void registerProcessSingleton() {
        cached = this;
    }

    /**
     * 返回已实例化的进程单例 · CC original: getWorkflowService (service.ts:98-111) /
     * peekWorkflowService (service.ts:329-331)。
     *
     * @return 进程单例
     * @throws IllegalStateException Spring 未装配时（未上线前 P0 阶段 tool 尚未接线，调用方应 fail-loud）
     */
    public static WorkflowService getWorkflowService() {
        WorkflowService s = cached;
        if (s != null) {
            return s;
        }
        throw new IllegalStateException(
                "WorkflowService 尚未初始化（Spring bean 未装配）。W-2d WorkflowTool 已接线 launch，"
                        + "Spring 启动 @PostConstruct 后即可用；如需使用请先装配 WorkflowServiceImpl Spring bean。");
    }

    @Override
    public WorkflowPorts ports() {
        return ports;
    }

    @Override
    public CompletableFuture<LaunchResult> launch(LaunchInput input, ToolUseContext ctx, Object canUseTool) {
        if (input == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("LaunchInput 不能为 null"));
        }
        log.info("WorkflowService.launch 入口：name={} scriptPath={} resumeFromRunId={} maxConcurrency={}（CC service.ts:188-257）",
                input.name(), input.scriptPath(), input.resumeFromRunId(), input.maxConcurrency());
        try {
            ResolvedSource src = resolveSource(input, ctx);

            // parseScript 快速校验：失败抛「Script validation failed」不进后台（service.ts:190-194）
            try {
                parser.parse(src.script());
            } catch (ScriptError e) {
                log.warn("WorkflowService.launch 脚本校验失败：{}（Script validation failed，不进后台，service.ts:190-194）",
                        e.getMessage());
                throw new IllegalArgumentException("Script validation failed: " + e.getMessage());
            }

            WorkflowHostContext host = buildHost(ctx, canUseTool);

            // taskRegistrar.register：resumeFromRunId → runId 复用（service.ts:197-206）
            TaskRegistrar.RegisterResult reg = ports.taskRegistrar().register(
                    new TaskRegistrar.RegisterOpts(
                            src.workflowName(),
                            src.workflowFile(),
                            input.description(),
                            host.toolUseId(),
                            input.resumeFromRunId()),
                    host.handle());
            String runId = reg.runId();

            // inline 入口：脚本持久化到 run 目录（对称 WorkflowTool），返回可复用路径。
            // 写失败降级仅 log，不阻塞 run（service.ts:210-223）
            String persistedScriptPath = null;
            if (src.workflowFile() == null && input.script() != null) {
                try {
                    persistedScriptPath = InlineScriptPersister
                            .persist(input.script(), runId, host.cwd())
                            .toString();
                    log.info("WorkflowService.launch inline 脚本已持久化：{}（service.ts:213-223）", persistedScriptPath);
                } catch (Exception e) {
                    log.warn("workflow inline script persist failed: {}（service.ts:217-221，降级不阻塞）", e.getMessage());
                }
            }

            // detached run：不 await，调用方立即拿 runId；完成时路由到 registrar（service.ts:226-250）
            RunWorkflowOptions opts = new RunWorkflowOptions(
                    src.script(),
                    input.args(),
                    runId,
                    src.workflowName(),
                    ports,
                    host.handle(),
                    reg.signal(),
                    host.cwd(),
                    host.budgetTotal(),
                    input.maxConcurrency(),
                    input.resumeFromRunId() != null,
                    false);
            runEngine.run(opts).whenComplete((result, err) -> routeTerminal(runId, result, err));

            log.info("workflow launched: {} ({})", runId, src.workflowName());
            return CompletableFuture.completedFuture(new LaunchResult(runId, persistedScriptPath));
        } catch (Exception e) {
            log.warn("WorkflowService.launch 失败：{}", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /** detached 完成路由 · CC original: service.ts:241-250（.then 按 status 路由 complete/fail/kill）。 */
    private void routeTerminal(String runId, WorkflowRunResult result, Throwable err) {
        try {
            if (err != null) {
                log.warn("WorkflowService routeTerminal：runId={} 引擎异常 → fail（service.ts:250）", runId);
                ports.taskRegistrar().fail(runId, err.getMessage());
            } else if (result != null && result.status() == RunStatus.COMPLETED) {
                if (log.isDebugEnabled()) {
                    log.debug("WorkflowService routeTerminal：runId={} completed → complete（service.ts:242-243）", runId);
                }
                ports.taskRegistrar().complete(runId, null);
            } else if (result != null && result.status() == RunStatus.FAILED) {
                log.warn("WorkflowService routeTerminal：runId={} failed → fail（service.ts:244-245）", runId);
                ports.taskRegistrar().fail(runId, result.error() != null ? result.error() : "failed");
            } else {
                log.info("WorkflowService routeTerminal：runId={} killed → kill（service.ts:246-247）", runId);
                ports.taskRegistrar().kill(runId);
            }
        } catch (Exception e) {
            log.warn("workflow terminal route failed for {}: {}（shutdown 期 kill 可能触发渲染异常，单点不阻断循环）",
                    runId, e.getMessage());
        }
    }

    @Override
    public void kill(String runId) {
        log.info("WorkflowService.kill：runId={}（service.ts:259-261 → taskRegistrar.kill，同时 abort 全部 in-flight agent）",
                runId);
        ports.taskRegistrar().kill(runId);
    }

    @Override
    public boolean killAgent(String runId, int agentId) {
        boolean hit = ports.taskRegistrar().killAgent(runId, agentId);
        log.info("WorkflowService.killAgent：runId={} agentId={} 命中={}（service.ts:262-264，精确 abort 单个 agent）",
                runId, agentId, hit);
        return hit;
    }

    @Override
    public void shutdown() {
        log.info("WorkflowService.shutdown 入口：仅 kill running，completed/failed 不受影响（service.ts:266-281）");
        for (RunProgress run : store.list()) {
            if (run.status() != RunProgress.Status.RUNNING) {
                continue;
            }
            try {
                ports.taskRegistrar().kill(run.runId());
            } catch (Exception e) {
                log.warn("workflow shutdown: kill {} failed: {}（单点失败不阻断其余 run 清理，service.ts:272-279）",
                        run.runId(), e.getMessage());
            }
        }
    }

    @Override
    public List<RunProgress> listRuns() {
        return store.list();
    }

    @Override
    public RunProgress getRun(String runId) {
        return store.get(runId);
    }

    /**
     * 异步按 runId 查找 · CC original: service.ts:285-289
     * {@code async getRunAsync(id){ const mem = store.get(id); if (mem) return mem; return (await readRunState(runsDirProvider(), id)) ?? undefined }}。
     *
     * <p>内存命中返回；miss 从磁盘 state.json 读（不注入内存）。Java 磁盘读为同步 IO，
     * 以 completedFuture 承载（与 resolveSource 的 Files.readString 同款同步风格）。
     *
     * @param runId 目标 run id
     * @return RunProgress 或 null（内存 + 磁盘双 miss）
     */
    @Override
    public CompletableFuture<RunProgress> getRunAsync(String runId) {
        RunProgress mem = store.get(runId);
        if (mem != null) {
            if (log.isDebugEnabled()) {
                log.debug("getRunAsync 内存命中：runId={}（service.ts:286-287）", runId);
            }
            return CompletableFuture.completedFuture(mem);
        }
        // service.ts:288 miss → 磁盘 readRunState ?? null
        String runsDir = WorkflowRunPersistence.getRunsDir(runsDirProvider.get());
        RunProgress fromDisk = persistence.readRunState(runsDir, runId);
        if (fromDisk != null && log.isDebugEnabled()) {
            log.debug("getRunAsync 磁盘命中：runId={} status={}（service.ts:288，不注入内存）",
                    runId, fromDisk.status());
        }
        return CompletableFuture.completedFuture(fromDisk);
    }

    /**
     * 扫描磁盘水合历史 run · CC original: service.ts:290-309 {@code async loadPersistedRuns()}。
     *
     * <p>进程单例 flag（persistedLoaded）首次置 true、后续立即返回（service.ts:291-292）；
     * 扫描失败 log + 重置 flag 允许下次重试（service.ts:302-308）。最多水合最新
     * {@code LOAD_PERSISTED_LIMIT=20} 个（service.ts:296-300）；store.hydrate 跳过已有 runId
     * （内存优先，store.ts:189-193）。
     */
    @Override
    public void loadPersistedRuns() {
        if (persistedLoaded) {
            return;
        }
        persistedLoaded = true;
        try {
            String runsDir = runsDirProvider.get();
            List<RunProgress> runs = persistence.listPersistedRuns(runsDir, LOAD_PERSISTED_LIMIT);
            for (RunProgress run : runs) {
                store.hydrate(run);
            }
            log.info("loadPersistedRuns 完成：水合 {} 个历史 run（上限 {}，service.ts:290-309）",
                    runs.size(), LOAD_PERSISTED_LIMIT);
        } catch (Exception e) {
            // service.ts:302-308 扫描失败不阻断面板：log + 重置 flag 下次重试
            log.warn("[workflow warn] loadPersistedRuns failed: {}（重置 flag 允许下次重试，service.ts:302-308）",
                    e.getMessage());
            persistedLoaded = false;
        }
    }

    /**
     * 订阅快照变更 · CC original: service.ts:310 {@code subscribe: fn => store.subscribe(fn)}。
     *
     * @param listener 变更通知（每次 store 快照重建触发）
     * @return 退订 Runnable
     */
    @Override
    public Runnable subscribe(Runnable listener) {
        return store.subscribe(listener);
    }

    @Override
    public List<String> listNamed(String workflowDir) {
        String projectRoot = resolveProjectRoot(null);
        List<String> names = workflowDir != null
                ? NamedWorkflows.list(workflowDir)
                : NamedWorkflows.listWithFallback(projectRoot);
        if (log.isDebugEnabled()) {
            log.debug("WorkflowService.listNamed：projectRoot={} 命中 {} 个命名 workflow（service.ts:312-316）",
                    projectRoot, names.size());
        }
        return names;
    }

    // ────────────────────────────── 内部（makeService 等价，service.ts:128-179）──────────────────────────────

    /** resolveSource 结果 · CC original: service.ts:141-150 {@code {script, workflowFile?, workflowName}}。 */
    private record ResolvedSource(String script, @Nullable String workflowFile, String workflowName) {
    }

    /**
     * 三源解析 · CC original: resolveSource (service.ts:141-179)。
     *
     * <p>script > scriptPath（readFile）> name（{@code resolveNamedWorkflow}）；缺 → Error。
     * {@code workflowName = name ?? title ?? 'workflow'}（service.ts:153）。
     */
    private ResolvedSource resolveSource(LaunchInput input, ToolUseContext ctx) throws Exception {
        String workflowName = input.name() != null ? input.name()
                : (input.title() != null ? input.title() : "workflow");
        if (input.script() != null) {
            if (log.isDebugEnabled()) {
                log.debug("resolveSource：script 内联，workflowName={}（service.ts:154-156）", workflowName);
            }
            return new ResolvedSource(input.script(), null, workflowName);
        }
        if (input.scriptPath() != null) {
            String script = Files.readString(Path.of(input.scriptPath()));
            if (log.isDebugEnabled()) {
                log.debug("resolveSource：scriptPath={}，workflowName={}（service.ts:157-162）", input.scriptPath(), workflowName);
            }
            return new ResolvedSource(script, input.scriptPath(), workflowName);
        }
        if (input.name() != null) {
            NamedWorkflows.NamedWorkflow found = NamedWorkflows.resolveWithFallback(resolveProjectRoot(ctx), input.name());
            if (found == null) {
                throw new IllegalArgumentException("Named workflow \"" + input.name()
                        + "\" not found (looked in " + WorkflowConstants.WORKFLOW_DIR_NAME
                        + "/ 与 .claude/workflows/)");
            }
            if (log.isDebugEnabled()) {
                log.debug("resolveSource：name={} → path={}（service.ts:163-176）", input.name(), found.path());
            }
            return new ResolvedSource(found.content(), found.path(), workflowName);
        }
        throw new IllegalArgumentException("One of script, name, or scriptPath must be provided");
    }

    /**
     * 构造 host 上下文 · CC original: buildHost (service.ts:128-139)。
     *
     * <p>{@code cwd} 用 projectRoot（对齐 ports.ts hostFactory / journalStore 同根，
     * 防 worktree/子目录 desync，service.ts:133-136）；{@code budgetTotal = null}（turn 级预算注入点）。
     */
    private WorkflowHostContext buildHost(ToolUseContext toolUseContext, Object canUseTool) {
        WorkflowHostBundle bundle = WorkflowHostBundle.build(toolUseContext, canUseTool, null);
        HostHandle handle = HostHandle.create(bundle);
        String cwd = resolveProjectRoot(toolUseContext);
        String toolUseId = toolUseContext != null ? toolUseContext.toolUseId() : null;
        if (log.isDebugEnabled()) {
            log.debug("buildHost：cwd={} budgetTotal=null toolUseId={}（service.ts:128-139）", cwd, toolUseId);
        }
        return new WorkflowHostContext(handle, cwd, null, toolUseId);
    }

    /**
     * 解析 projectRoot · CC original: {@code getProjectRoot()}（service.ts:136，bootstrap/state.ts）。
     *
     * <p>Java 等价 = 会话绑定项目（boundProject，memory：session-bound-dir-is-cc-startup-dir），
     * 经 {@link CwdResolution#getCwd(String)} 四层解析（override → sessionCwd → boundProject → user.dir）；
     * 无会话（cron/后台/测试）回落 {@code user.dir}。
     */
    private String resolveProjectRoot(ToolUseContext ctx) {
        if (cwdOverride != null) {
            return cwdOverride;
        }
        if (ctx != null && ctx.sessionId() != null) {
            return CwdResolution.getCwd(ctx.sessionId());
        }
        return System.getProperty("user.dir");
    }
}
