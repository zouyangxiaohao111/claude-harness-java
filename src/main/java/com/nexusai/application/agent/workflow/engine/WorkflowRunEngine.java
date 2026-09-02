package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.workflow.JournalEntry;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.RunStatus;
import com.nexusai.application.agent.workflow.WorkflowAbortedError;
import com.nexusai.application.agent.workflow.WorkflowConstants;
import com.nexusai.application.agent.workflow.WorkflowError;
import com.nexusai.application.agent.workflow.WorkflowMeta;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowRunResult;
import com.nexusai.application.agent.workflow.script.ParsedScript;
import com.nexusai.application.agent.workflow.script.WorkflowScriptExecutor;
import com.nexusai.application.agent.workflow.script.WorkflowScriptParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * workflow run 状态机 · 对齐 CC {@code engine/runWorkflow.ts:31-139 runWorkflow}。
 *
 * <p><b>W-1c 完整实现</b>（Execute-W1c）：取代 W-1e 的「P0 编排壳」（壳 Javadoc 明示
 * 「W-1c 接线后替换」）。接线完整 WorkflowHooksImpl / EngineContext / Semaphore / Budget /
 * journal 重放。</p>
 *
 * <p><b>run 生命周期</b>（runWorkflow.ts）：parse → journal 加载 → createEngineContext → emit
 * run_started → runSubWorkflow（depth+1 共享 ctx）→ makeHooks → execute → 终态路由 →
 * emitTerminalPhaseDone → emit run_done。</p>
 *
 * <ul>
 *   <li><b>parse fail → run_done failed 直接返回不抛</b>（DETACHED 不炸，runWorkflow.ts:36-48）</li>
 *   <li><b>终态路由</b>（runWorkflow.ts:119-131）：WorkflowAbortedError → killed；其他 throw → failed；
 *       正常 → completed</li>
 *   <li><b>terminal phase_done 补发</b>（runWorkflow.ts:108-115）：脚本结束无后续 phase() 时补发最后
 *       phase 的 phase_done，防 UI 卡转圈</li>
 * </ul>
 *
 * <p><b>与 W-1e 的编译契约</b>：{@code WorkflowServiceImpl} 用 {@code new WorkflowRunEngine()} +
 * {@code run(opts)}（routeTerminal 比较 {@code result.status() == RunStatus.COMPLETED}，故
 * {@link WorkflowRunResult#status()} 返回顶层 {@link RunStatus}；emit run_done 时转
 * {@link ProgressEvent.RunStatus}）。</p>
 *
 * <p>执行引擎接线（G-2）：{@link WorkflowScriptExecutor} 由构造注入；未注入时 parser
 * 默认编译 {@code RestrictedScriptExecutor}（受限 DSL 解释器，生产 WorkflowServiceImpl →
 * {@code new WorkflowRunEngine()} 走此路径，替换 NOT_WIRED）。W-1c 单测注入 fake executor
 * 驱动状态机（completed/killed/failed 三态）。</p>
 */
public final class WorkflowRunEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunEngine.class);

    private final WorkflowScriptParser parser;
    private final WorkflowScriptExecutor scriptExecutor;

    /** W-1e 契约：无参构造（executor=null → parser 默认编译 RestrictedScriptExecutor）。 */
    public WorkflowRunEngine() {
        this(new WorkflowScriptParser(), null);
    }

    /** W-1e 契约：注入 parser。 */
    public WorkflowRunEngine(WorkflowScriptParser parser) {
        this(parser, null);
    }

    /** W-1c 单测契约：注入 parser + executor（null → parser 默认 RestrictedScriptExecutor）。 */
    public WorkflowRunEngine(WorkflowScriptParser parser, WorkflowScriptExecutor scriptExecutor) {
        this.parser = parser != null ? parser : new WorkflowScriptParser();
        this.scriptExecutor = scriptExecutor;
    }

    /**
     * 执行一次 workflow run · 对齐 CC runWorkflow (runWorkflow.ts:31-139)。
     *
     * @param opts run 入参（script/runId/ports/host/signal/cwd/budgetTotal/maxConcurrency/resume/...）
     * @return WorkflowRunResult（completed/failed/killed）；parse fail 时已 emit run_done failed 且不抛
     */
    public CompletableFuture<WorkflowRunResult> run(RunWorkflowOptions opts) {
        WorkflowPorts ports = opts.ports();

        // 1. parseScript：失败 → emit run_done failed 直接返回不抛（DETACHED 不炸，runWorkflow.ts:36-48）
        ParsedScript parsed;
        try {
            parsed = parser.parse(opts.script(), scriptExecutor);
        } catch (Exception e) {
            String error = e.getMessage();
            log.error("workflow({}) 脚本解析失败：{}（run_done failed 直接返回，DETACHED 不炸）",
                    opts.runId(), error);
            ports.progressEmitter().emit(new ProgressEvent.RunDone(
                    opts.runId(), ProgressEvent.RunStatus.FAILED, null, error));
            return CompletableFuture.completedFuture(WorkflowRunResult.failed(error));
        }

        String workflowName = opts.workflowName() != null
                ? opts.workflowName()
                : (parsed.meta() != null ? parsed.meta().name() : "workflow");

        // 2. journal 加载（runWorkflow.ts:52-60）
        List<JournalEntry> journal = new ArrayList<>();
        boolean journalInvalidated = false;
        try {
            if (opts.resume() && !opts.scriptChanged()) {
                journal = ports.journalStore().read(opts.runId());
                log.info("workflow({}) resume 加载 journal {} 条（runWorkflow.ts:55-56）", opts.runId(), journal.size());
            } else if (opts.scriptChanged()) {
                ports.journalStore().truncate(opts.runId());
                journalInvalidated = true;
                log.info("workflow({}) scriptChanged：truncate journal，全量重跑（runWorkflow.ts:57-59）", opts.runId());
            }
        } catch (IOException e) {
            log.warn("workflow({}) journal 加载失败，按空 journal 继续：{}", opts.runId(), e.getMessage());
        }

        // 3. createEngineContext → emit run_started（runWorkflow.ts:62-80）
        EngineContext ctx = EngineContext.create(ports, opts.host(), opts.signal(), opts.runId(),
                workflowName, opts.cwd(), opts.budgetTotal(), opts.maxConcurrency(), journal);
        if (journalInvalidated) {
            ctx.setJournalInvalidated(true);
        }
        ports.progressEmitter().emit(new ProgressEvent.RunStarted(
                opts.runId(), workflowName, toRootMeta(parsed.meta())));
        log.info("workflow({}) run_started：workflowName={}，cwd={}，budgetTotal={}，maxConcurrency(clamp)={}",
                opts.runId(), workflowName, opts.cwd(), opts.budgetTotal(),
                Semaphore.clampMaxConcurrency(opts.maxConcurrency()));

        // 4. runSubWorkflow（depth+1 共享 ctx）+ makeHooks（runWorkflow.ts:83-103）
        //    args 透传：hooks.ts:335 `runSubWorkflow({ ...sub, args })` → runWorkflow.ts:97 子脚本 execute 收 args（GAP-1 修正）
        SubWorkflowRunner subRunner = (name, args) -> runSubWorkflow(ctx, name, args, opts);
        WorkflowHooksImpl hooks = new WorkflowHooksImpl(ctx, subRunner);

        // 5. execute + 终态路由（runWorkflow.ts:117-131）
        CompletableFuture<Object> execFuture;
        try {
            execFuture = parsed.execute(hooks, opts.args(), ctx.resources().budget());
        } catch (Exception e) {
            execFuture = CompletableFuture.failedFuture(e);
        }
        return execFuture
                .thenApply(returnValue -> {
                    if (log.isDebugEnabled()) {
                        log.debug("workflow({}) execute 正常返回（→ completed）", opts.runId());
                    }
                    return WorkflowRunResult.completed(returnValue);
                })
                .exceptionally(ex -> {
                    Throwable t = unwrap(ex);
                    if (t instanceof WorkflowAbortedError) {
                        log.warn("workflow({}) 执行被 kill（WorkflowAbortedError → KILLED，runWorkflow.ts:126-127）",
                                opts.runId());
                        return WorkflowRunResult.killed();
                    }
                    log.error("workflow({}) 执行失败：{}（runWorkflow.ts:129）", opts.runId(), t.getMessage(), t);
                    return WorkflowRunResult.failed(t.getMessage());
                })
                .thenApply(result -> {
                    // 6. 收尾：terminal phase_done 补发 + emit run_done（runWorkflow.ts:132-138）
                    emitTerminalAndDone(ports, opts.runId(), ctx, result);
                    return result;
                });
    }

    /** 补发 terminal phase_done + emit run_done · 对齐 runWorkflow.ts:108-115 + 132-138。 */
    private void emitTerminalAndDone(WorkflowPorts ports, String runId, EngineContext ctx,
                                     WorkflowRunResult result) {
        if (ctx.currentPhase() != null) {
            ports.progressEmitter().emit(new ProgressEvent.PhaseDone(runId, ctx.currentPhase()));
        }
        ProgressEvent.RunStatus status = switch (result.status()) {
            case COMPLETED -> ProgressEvent.RunStatus.COMPLETED;
            case FAILED -> ProgressEvent.RunStatus.FAILED;
            case KILLED -> ProgressEvent.RunStatus.KILLED;
        };
        ports.progressEmitter().emit(new ProgressEvent.RunDone(runId, status, result.returnValue(), result.error()));
        log.info("workflow({}) run_done：status={}，returnValue={}，error={}",
                runId, result.status(), result.returnValue(), result.error());
    }

    /**
     * 子 workflow 执行 · 对齐 CC runSubWorkflow (runWorkflow.ts:83-101)：共享 ctx（journal/并发/预算/
     * 计数器），depth 临时 +1，workflow() 嵌套仅允许一层；调用方 args 透传给子脚本 execute
     * （runWorkflow.ts:97 直传 sub.args —— GAP-1 修正，原实现硬编码 null 静默丢弃 args）。
     *
     * @param ctx  共享 EngineContext（journal/并发/预算/计数器）
     * @param name 子命名 workflow 名（决策 D6/D7：相对 cwd/.{appName}/workflows 解析，nexusai 优先
     *            + cwd/.claude/workflows 回落）
     * @param args 调用方 workflow('name', args) 传入的参数 · CC original: sub.args (runWorkflow.ts:97)
     * @param opts 父 run 选项（cwd/runId 等）
     * @return 子 workflow 返回结果
     */
    private CompletableFuture<Object> runSubWorkflow(EngineContext ctx, String name, Object args,
                                                     RunWorkflowOptions opts) {
        String script;
        try {
            script = resolveNamedScript(name, opts.cwd());
        } catch (WorkflowError e) {
            log.warn("workflow({}) 子 workflow 解析失败：{}", opts.runId(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
        ParsedScript subParsed;
        try {
            subParsed = parser.parse(script, scriptExecutor);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new WorkflowError(
                    "Sub-workflow script error: " + e.getMessage()));
        }
        int prevDepth = ctx.resources().depth();
        ctx.resources().setDepth(prevDepth + 1);
        try {
            WorkflowHooksImpl subHooks = new WorkflowHooksImpl(ctx, (n, a) -> runSubWorkflow(ctx, n, a, opts));
            log.info("workflow({}) 子 workflow '{}' 开始执行（depth+1，runWorkflow.ts:96-97）：args={}",
                    opts.runId(), name, args);
            return subParsed.execute(subHooks, args, ctx.resources().budget());
        } finally {
            ctx.resources().setDepth(prevDepth);
        }
    }

    /** 命名 workflow 解析 · 对齐 resolveNamedWorkflow（子集）：P0 仅 name（script/scriptPath 变体归 W-1e）。 */
    private String resolveNamedScript(String name, String cwd) throws WorkflowError {
        // 决策 D6/D7：nexusai 目录优先 + .claude/workflows 回落（既有命名 workflow 兼容）
        String[] bases = {
                Path.of(cwd, WorkflowConstants.WORKFLOW_DIR_NAME).toString(),
                Path.of(cwd, ".claude/workflows").toString(),
        };
        for (String base : bases) {
            for (String ext : WorkflowConstants.WORKFLOW_SCRIPT_EXTENSIONS) {
                Path p = Path.of(base, name + ext);
                if (Files.isRegularFile(p)) {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new WorkflowError("read sub-workflow failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        throw new WorkflowError("Sub-workflow \"" + name + "\" not found (looked in "
                + WorkflowConstants.WORKFLOW_DIR_NAME + "/ 与 .claude/workflows/)");
    }

    /**
     * script 包 {@code WorkflowMeta} → root {@link WorkflowMeta} 转换。
     *
     * <p><b>W-1c 协调声明</b>：并发 subagent 在 root（W-1a，ProgressEvent 载荷）与 script（W-1b，
     * ParsedScript.meta）各建了一个 WorkflowMeta 类型。引擎在此转换，避免改动任一方。
     * 主 agent 合入时以 DEC-P0-01 reconcile（归一单类型）。</p>
     */
    private static WorkflowMeta toRootMeta(com.nexusai.application.agent.workflow.script.WorkflowMeta m) {
        if (m == null) {
            return null;
        }
        List<WorkflowMeta.PhaseMeta> phases = null;
        if (m.phases() != null && !m.phases().isEmpty()) {
            phases = m.phases().stream()
                    .map(p -> new WorkflowMeta.PhaseMeta(p.title(), p.detail()))
                    .toList();
        }
        return new WorkflowMeta(m.name(), m.description(), m.whenToUse(), phases);
    }

    /** 解包 CompletionException/ExecutionException。 */
    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
