package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.AgentProgressUpdate;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.AgentRunResultSkipped;
import com.nexusai.application.agent.workflow.JournalEntry;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.StructuredOutputValidator;
import com.nexusai.application.agent.workflow.TaskRegistrar;
import com.nexusai.application.agent.workflow.WorkflowAbortedError;
import com.nexusai.application.agent.workflow.WorkflowConstants;
import com.nexusai.application.agent.workflow.WorkflowError;
import com.nexusai.application.agent.workflow.WorkflowJournal;
import com.nexusai.application.agent.workflow.WorkflowLogger;
import com.nexusai.application.agent.workflow.agent.AgentAdapter;
import com.nexusai.application.agent.workflow.agent.AgentAdapterContext;
import com.nexusai.application.agent.workflow.agent.AdapterNotFoundError;
import com.nexusai.application.agent.workflow.script.WorkflowHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * makeHooks 等价实现 · 对齐 CC {@code engine/hooks.ts:47-339 makeHooks}。
 *
 * <p>W-1c 引擎核心。实现 {@link WorkflowHooks}（script 包接口）的 6 个 hook：
 * <ul>
 *   <li><b>agent</b>（hooks.ts:59-263）：cap 闸 → agentId 盖章 → schema 前置编译 → journal key →
 *       journal 命中重放/分叉截断 → 信号量临界区（abort 不占槽 + budget 检查在临界区内）→
 *       pendingAction skip → agent_started/done/progress 事件 → registry.resolve（配置错误不重试）→
 *       retry-once（dead/非 abort throw 重试一次；kill 不重试；throw 降级 dead{runagent-threw}）→
 *       budget 不双计 → journal push + append</li>
 *   <li><b>parallel/pipeline</b>（hooks.ts:265-313）：MAX_ITEMS_PER_CALL 闸；单项失败 log + null 占位</li>
 *   <li><b>phase</b>（hooks.ts:315-321）：切阶段先 emit 旧 phase_done 再新 phase_started</li>
 *   <li><b>log</b>（hooks.ts:323-325）、<b>workflow</b>（hooks.ts:327-336，depth&gt;=1 拒）</li>
 * </ul>
 */
public final class WorkflowHooksImpl implements WorkflowHooks {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHooksImpl.class);

    private final EngineContext ctx;
    private final SubWorkflowRunner subRunner;

    public WorkflowHooksImpl(EngineContext ctx, SubWorkflowRunner subRunner) {
        this.ctx = ctx;
        this.subRunner = subRunner;
    }

    /** 发射进度事件（自动注入 runId · 对齐 hooks.ts:52-57 emit）。 */
    private void emit(ProgressEvent event) {
        ctx.ports().progressEmitter().emit(event);
    }

    // ─────────────────────────── agent() ───────────────────────────

    @Override
    public CompletableFuture<Object> agent(String prompt, Map<String, Object> opts) {
        // 1. cap 闸（hooks.ts:61-64）
        if (ctx.resources().agentCountBox().get() >= WorkflowConstants.MAX_TOTAL_AGENTS) {
            log.error("workflow({}) agent() 调用超上限 MAX_TOTAL_AGENTS={}（hooks.ts:61-64）",
                    ctx.runId(), WorkflowConstants.MAX_TOTAL_AGENTS);
            return CompletableFuture.failedFuture(new WorkflowError(
                    "workflow exceeds total agent cap (" + WorkflowConstants.MAX_TOTAL_AGENTS + ")"));
        }

        // 2. agentId 盖章（每个 agent() 唯一 id，含 journal 命中；hooks.ts:68）
        int agentId = ctx.resources().agentIdSeq().getAndIncrement();

        // 3. 拼 params = { prompt, ...opts } + schema 前置编译（配置错误不重试；hooks.ts:70-73）
        AgentRunParams params = buildParams(prompt, opts);
        if (params.schema() != null) {
            try {
                StructuredOutputValidator.assertValidJsonSchema(params.schema());
            } catch (IllegalArgumentException e) {
                log.error("workflow({}) agent#{} schema 非法（配置错误不重试，hooks.ts:73）：{}",
                        ctx.runId(), agentId, e.getMessage());
                return CompletableFuture.failedFuture(e);
            }
        }
        String key = WorkflowJournal.agentCallKey(prompt, params);
        String label = params.label();
        String phase = params.phase() != null ? params.phase() : ctx.currentPhase();
        if (log.isDebugEnabled()) {
            log.debug("workflow({}) agent#{} 开始：label={}，key={}（hooks.ts:74）", ctx.runId(), agentId, label, key);
        }

        // 4. journal 命中分支（hooks.ts:87-114）：命中合法 → 复用缓存，不扣预算
        if (!ctx.journalInvalidated() && ctx.journalIndex() < ctx.journal().size()) {
            JournalEntry entry = ctx.journal().get(ctx.journalIndex());
            if (entry.key().equals(key)) {
                AgentRunResult cached = StructuredOutputValidator.validateStructuredResult(
                        entry.result(), params.schema());
                if (entry.result() instanceof AgentRunResultOk && cached instanceof AgentRunResultDead) {
                    ctx.ports().logger().warn("workflow({}) agent#{} 缓存结果不匹配其 schema，重跑（hooks.ts:94-98）",
                            ctx.runId(), agentId);
                    invalidateJournal();
                } else {
                    ctx.setJournalIndex(ctx.journalIndex() + 1);
                    emit(new ProgressEvent.AgentDone(ctx.runId(), agentId, label, phase, entry.result()));
                    if (log.isDebugEnabled()) {
                        log.debug("workflow({}) agent#{} journal 命中重放缓存结果，不调后端（hooks.ts:99-108）",
                                ctx.runId(), agentId);
                    }
                    return CompletableFuture.completedFuture(resultToOutput(entry.result()));
                }
            } else {
                // key 分叉 → 丢弃后续 journal 条目，从此刻起全 live（hooks.ts:110-113）
                log.warn("workflow({}) agent#{} journal key 分叉，截断后续条目（hooks.ts:110-113）",
                        ctx.runId(), agentId);
                invalidateJournal();
            }
        }

        // 5. 信号量临界区（hooks.ts:116-134）：abort 不占槽 + budget 检查在临界区内
        CompletableFuture<Semaphore.Permit> permitFuture = ctx.resources().semaphore()
                .acquire(ctx.signal())
                .exceptionally(ex -> {
                    // 等待中 abort：信号量已移除 waiter 且未消耗 permit → WorkflowAbortedError（hooks.ts:116-122）
                    log.warn("workflow({}) agent#{} 信号量 acquire 失败（abort），抛 WorkflowAbortedError", ctx.runId(), agentId);
                    throw new WorkflowAbortedError();
                });

        return permitFuture.thenCompose(permit -> runAgentCritical(permit, params, agentId, key, label, phase));
    }

    /** 临界区主体：在池线程执行，finally 释放 permit（对齐 hooks.ts:123-262 的 try/finally）。 */
    private CompletableFuture<Object> runAgentCritical(Semaphore.Permit permit, AgentRunParams params,
                                                       int agentId, String key, String label, String phase) {
        CompletableFuture<Object> body;
        try {
            body = CompletableFuture.supplyAsync(() -> runAgentBody(params, agentId, key, label, phase));
        } catch (Exception e) {
            body = CompletableFuture.failedFuture(e);
        }
        return body.whenComplete((v, e) -> permit.release().run());
    }

    /** 临界区同步主体（在信号量槽内运行；任何抛错经 future 传递，finally 已释放 permit）。 */
    private Object runAgentBody(AgentRunParams params, int agentId, String key, String label, String phase) {
        // abort 检查（hooks.ts:124）
        if (ctx.signal().isCancelled()) {
            log.warn("workflow({}) agent#{} 临界区内检测到 abort（hooks.ts:124）", ctx.runId(), agentId);
            throw new WorkflowAbortedError();
        }
        // budget 检查在临界区内：队列 waiter 唤醒时看到最新 spent（防 N waiter 同时 overspend，hooks.ts:124-128）
        ctx.resources().budget().assertCanSpend();

        // pendingAction skip（hooks.ts:130-135）
        TaskRegistrar.PendingAction pending = ctx.ports().taskRegistrar().pendingAction(ctx.runId());
        if (pending != null && "skip".equals(pending.kind())) {
            AgentRunResult skipped = new AgentRunResultSkipped();
            emit(new ProgressEvent.AgentDone(ctx.runId(), agentId, label, phase, skipped));
            if (log.isDebugEnabled()) {
                log.debug("workflow({}) agent#{} pendingAction=skip，返回 null（hooks.ts:130-135）", ctx.runId(), agentId);
            }
            return null;
        }

        ctx.resources().agentCountBox().incrementAndGet();
        emit(new ProgressEvent.AgentStarted(ctx.runId(), agentId, label, phase));
        if (log.isDebugEnabled()) {
            log.debug("workflow({}) agent#{} 启动（agent_started，hooks.ts:137-138）", ctx.runId(), agentId);
        }

        // registry.resolve 在临界区内但错误不重试（配置错误 AdapterNotFoundError 直接抛，hooks.ts:183）
        AgentAdapter adapter;
        try {
            adapter = (AgentAdapter) ctx.ports().agentAdapterRegistry().resolve(params);
        } catch (AdapterNotFoundError e) {
            log.error("workflow({}) agent#{} 无匹配 adapter：{}（配置错误不重试，hooks.ts:183）",
                    ctx.runId(), agentId, e.getMessage());
            throw e;
        }

        AgentAdapterContext adapterCtx = buildAdapterCtx(agentId, label, phase);
        AgentRunResult result = invokeBackendWithRetry(adapter, params, adapterCtx, agentId, label);

        // budget 不双计：仅 ok 累计（dead 不调 addOutputTokens；retry-ok 在最终 ok 计一次，hooks.ts:248-250）
        if (result instanceof AgentRunResultOk ok) {
            ctx.resources().budget().addOutputTokens(ok.outputTokens());
        }

        emit(new ProgressEvent.AgentDone(ctx.runId(), agentId, label, phase, result));

        // journal push（push 序=完成序；read 按 seq 重排，hooks.ts:253-258）
        JournalEntry entry = new JournalEntry(key, agentId, result);
        ctx.journal().add(entry);
        ctx.setJournalIndex(ctx.journalIndex() + 1);
        try {
            ctx.ports().journalStore().append(ctx.runId(), entry);
        } catch (IOException e) {
            log.error("workflow({}) journal append 失败：{}", ctx.runId(), e.getMessage(), e);
            throw new WorkflowError("journal append failed: " + e.getMessage(), e);
        }
        return resultToOutput(result);
    }

    /** 后端调用 + retry-once（hooks.ts:184-247）。 */
    private AgentRunResult invokeBackendWithRetry(AgentAdapter adapter, AgentRunParams params,
                                                  AgentAdapterContext adapterCtx, int agentId, String label) {
        Attempt first = attemptBackend(adapter, params, adapterCtx);
        if (!first.errored() && !(first.value() instanceof AgentRunResultDead)) {
            return first.value();
        }
        // dead 或非 abort throw → 重试一次；WorkflowAbortedError（kill）不重试（hooks.ts:191-193）
        if (first.errored()) {
            ctx.ports().logger().warn("workflow({}) agent#{} threw ({}); retrying once",
                    ctx.runId(), agentId, first.errorMessage());
        } else {
            AgentRunResultDead dead = (AgentRunResultDead) first.value();
            StringBuilder msg = new StringBuilder("workflow(").append(ctx.runId()).append(") agent#").append(agentId)
                    .append(" returned dead");
            if (dead.reason() != null) {
                msg.append(" (").append(dead.reason()).append(")");
            }
            if (dead.detail() != null && !dead.detail().isEmpty()) {
                msg.append(": ").append(dead.detail(), 0, Math.min(dead.detail().length(), 150));
            }
            msg.append("; retrying once");
            ctx.ports().logger().warn(msg.toString());
        }

        Attempt retry = attemptBackend(adapter, params, adapterCtx);
        if (!retry.errored()) {
            return retry.value();
        }
        // retry 仍 throw → 降级 dead{runagent-threw}（单个 agent 不拖垮 workflow，hooks.ts:236-246）
        log.warn("workflow({}) agent#{} retry 仍失败，降级 dead{runagent-threw}（hooks.ts:236-246）",
                ctx.runId(), agentId);
        return new AgentRunResultDead(AgentRunResult.DeadReason.RUNAGENT_THREW, retry.errorMessage());
    }

    /** 单次后端尝试 · CC attemptBackend（hooks.ts:202-209）：WorkflowAbortedError 不捕获（kill 不重试）。 */
    private Attempt attemptBackend(AgentAdapter adapter, AgentRunParams params, AgentAdapterContext adapterCtx) {
        try {
            AgentRunResult raw = adapter.run(params, adapterCtx).join();
            // schema 模式引擎边界二次校验（hooks.ts:188）· adapter 侧「提取 + no-structured-output
            // 分类」已由 StructuredOutputExtractor.classifySchemaMode 接线（ClaudeCodeBackendAdapter，
            // W-2b 落实，见 rework），此处在引擎统一做 Ajv 式形状校验（含 String JSON 输出解析），
            // 不匹配 → dead{invalid-structured-output}。
            return Attempt.result(StructuredOutputValidator.validateStructuredResult(raw, params.schema()));
        } catch (WorkflowAbortedError e) {
            throw e;
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof WorkflowAbortedError) {
                throw (WorkflowAbortedError) c;
            }
            return Attempt.error(c != null ? String.valueOf(c.getMessage()) : String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return Attempt.error(String.valueOf(e.getMessage()));
        }
    }

    /** 构建 adapterCtx：onProgress 闭包 + registerAgentAbort/unregisterAgentAbort 注入（hooks.ts:141-180）。 */
    private AgentAdapterContext buildAdapterCtx(int agentId, String label, String phase) {
        Consumer<AgentProgressUpdate> onProgress = update ->
                emit(new ProgressEvent.AgentProgress(ctx.runId(), agentId, label, phase,
                        update.tokenCount(), update.toolCount()));
        TaskRegistrar tr = ctx.ports().taskRegistrar();
        BiConsumer<Integer, AbortController> regAbort = (id, ac) -> tr.registerAgentAbort(ctx.runId(), id, ac);
        Consumer<Integer> unregAbort = id -> tr.unregisterAgentAbort(ctx.runId(), id);
        return new AgentAdapterContext(ctx.host(), ctx.signal(), ctx.runId(), agentId,
                onProgress, regAbort, unregAbort);
    }

    /** invalidateJournal 三件套（hooks.ts:79-83）：journalInvalidated + slice(0, journalIndex) + truncate。 */
    private void invalidateJournal() {
        ctx.setJournalInvalidated(true);
        ctx.setJournal(new ArrayList<>(ctx.journal().subList(0, ctx.journalIndex())));
        try {
            ctx.ports().journalStore().truncate(ctx.runId());
        } catch (IOException e) {
            log.warn("workflow({}) journal truncate 失败：{}", ctx.runId(), e.getMessage());
        }
    }

    // ─────────────────────────── parallel() ───────────────────────────

    @Override
    public CompletableFuture<List<Object>> parallel(List<Supplier<CompletableFuture<Object>>> thunks) {
        if (thunks.size() > WorkflowConstants.MAX_ITEMS_PER_CALL) {
            return CompletableFuture.failedFuture(new WorkflowError(
                    "parallel exceeds the per-call items cap (" + WorkflowConstants.MAX_ITEMS_PER_CALL + ")"));
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>(thunks.size());
        for (int i = 0; i < thunks.size(); i++) {
            final int idx = i;
            CompletableFuture<Object> f;
            try {
                f = thunks.get(idx).get();
            } catch (Exception e) {
                // null-on-error 契约不变，但必须记录日志（hooks.ts:276-281）
                ctx.ports().logger().warn("parallel thunk #" + idx + " failed: " + messageOf(e));
                futures.add(CompletableFuture.completedFuture(null));
                continue;
            }
            futures.add(f.handle((v, e) -> {
                if (e != null) {
                    ctx.ports().logger().warn("parallel thunk #" + idx + " failed: " + messageOf(unwrap(e)));
                    return null;
                }
                return v;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    // ─────────────────────────── pipeline() ───────────────────────────

    @Override
    public CompletableFuture<List<Object>> pipeline(List<Object> items, List<PipelineStage> stages) {
        if (items.size() > WorkflowConstants.MAX_ITEMS_PER_CALL) {
            return CompletableFuture.failedFuture(new WorkflowError(
                    "pipeline exceeds the per-call items cap (" + WorkflowConstants.MAX_ITEMS_PER_CALL + ")"));
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            futures.add(runPipelineItem(items, stages, i));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /** 单个 item 顺序过 stages（prev 链）；失败 → log + null 占位（hooks.ts:298-311）。 */
    private CompletableFuture<Object> runPipelineItem(List<Object> items, List<PipelineStage> stages, int index) {
        CompletableFuture<Object> chain = CompletableFuture.completedFuture(items.get(index));
        for (int s = 0; s < stages.size(); s++) {
            final int si = s;
            final int idx = index;
            chain = chain.thenCompose(prev -> {
                try {
                    return stages.get(si).apply(prev, items.get(idx), idx);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
        }
        return chain.handle((v, e) -> {
            if (e != null) {
                ctx.ports().logger().warn("pipeline item #" + index + " failed: " + messageOf(unwrap(e)));
                return null;
            }
            return v;
        });
    }

    // ─────────────────────────── phase() / log() / workflow() ───────────────────────────

    @Override
    public void phase(String title) {
        if (ctx.currentPhase() != null) {
            emit(new ProgressEvent.PhaseDone(ctx.runId(), ctx.currentPhase()));
        }
        ctx.setCurrentPhase(title);
        emit(new ProgressEvent.PhaseStarted(ctx.runId(), title));
        if (log.isDebugEnabled()) {
            log.debug("workflow({}) phase_started：{}（hooks.ts:315-321）", ctx.runId(), title);
        }
    }

    @Override
    public void log(String message) {
        emit(new ProgressEvent.WorkflowLog(ctx.runId(), message));
    }

    @Override
    public CompletableFuture<Object> workflow(String nameOrRef, Object args) {
        if (ctx.resources().depth() >= 1) {
            return CompletableFuture.failedFuture(new WorkflowError(
                    "workflow() nesting allows only one level"));
        }
        return subRunner.run(nameOrRef, args);
    }

    // ─────────────────────────── 工具方法 ───────────────────────────

    /** params = { prompt, ...opts }（hooks.ts:70）；opts 为 Map 透传。 */
    private AgentRunParams buildParams(String prompt, Map<String, Object> opts) {
        return new AgentRunParams(
                prompt,
                opts.get("schema"),
                asString(opts.get("model")),
                asInteger(opts.get("maxTokens")),
                asString(opts.get("agentType")),
                asString(opts.get("isolation")),
                asStringList(opts.get("allowedTools")),
                asString(opts.get("label")),
                asString(opts.get("phase")));
    }

    /** resultToOutput：ok → output，否则 null（hooks.ts:341-343）。 */
    private Object resultToOutput(AgentRunResult result) {
        return result instanceof AgentRunResultOk ok ? ok.output() : null;
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : null;
    }

    private static Integer asInteger(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (!(v instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    /** 后端尝试结果：errorMessage != null 表示抛错（区别于返回 dead）。 */
    private record Attempt(AgentRunResult value, String errorMessage) {
        boolean errored() {
            return errorMessage != null;
        }

        static Attempt result(AgentRunResult v) {
            return new Attempt(v, null);
        }

        static Attempt error(String msg) {
            return new Attempt(null, msg);
        }
    }

    /** 解包 CompletionException/ExecutionException。 */
    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String messageOf(Throwable t) {
        return t != null && t.getMessage() != null ? t.getMessage() : String.valueOf(t);
    }
}
