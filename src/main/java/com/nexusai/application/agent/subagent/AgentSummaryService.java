package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 周期性子代理摘要服务 · 对齐 CC services/AgentSummary/agentSummary.ts.
 *
 * <p>L1 语义: 每 30s 为 coordinator 模式子代理 fork 一次 LLM 生成 1-2 句进度摘要,
 *             通过回调注入 AgentProgress (CC updateAgentSummary).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: start(taskId, agentId, summarizer, callback) → AgentSummaryHandle</li>
 *   <li><b>A2 Golden Trace</b>: start → scheduleNext → runSummary (read → filter → prompt → summarize → callback) → scheduleNext</li>
 *   <li><b>A3</b>: stopped=true 后不再 schedule; in-flight runSummary 完成后 finally 不再 scheduleNext</li>
 *   <li><b>A4</b>: messages.length &lt; minMessages 时跳过本轮但不停止 (CC agentSummary.ts:69-74)</li>
 *   <li><b>A5</b>: summary.trim() 非空才更新 callback + previousSummary; LLM 抛错 catch 后继续 scheduleNext</li>
 * </ul>
 *
 * <p>[IMP-SUB-02 D2] 补 clean 消息上下文 + abort in-flight (WF6-02 M1/T1/T3/T4):
 * <ul>
 *   <li><b>A6</b>: runSummary 每轮新建 per-run AbortController (CC agentSummary.ts:91 summaryAbortController),
 *       透传 {@link SummarySummarizerImpl#summarize} → provider; stop() 时 abort (CC :169-171) 中断
 *       in-flight LLM 调用; finally 清空 (CC :149).</li>
 *   <li><b>A7</b>: summarize 前经 filterIncompleteToolCalls 计算 clean 消息并作为 fork 上下文传给
 *       LLM (CC agentSummary.ts:78-84 forkContextMessages) — 修复"摘要 LLM 无 transcript 上下文".</li>
 *   <li><b>A8</b>: summarize 返回后先复查 stopped (CC agentSummary.ts:121) 再更新 callback —
 *       修复 stop 竞态下"stop 后回调仍可能触发" (T3/T4).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): ScheduledExecutorService 替代 setTimeout; Consumer callback 替代 setAppState;
 *                    AtomicBoolean stopped 替代闭包变量.
 */
@Service
public class AgentSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AgentSummaryService.class);

    /** CC SUMMARY_INTERVAL_MS 常量, 对齐 agentSummary.ts:26. 可注入改写 (测试加速). */
    private final long summaryIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final Map<String, AgentSummaryState> states = new ConcurrentHashMap<>();

    public AgentSummaryService() {
        this(30_000L, Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-summary");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 测试用: 注入自定义 interval 和 scheduler. */
    public AgentSummaryService(long summaryIntervalMs, ScheduledExecutorService scheduler) {
        this.summaryIntervalMs = summaryIntervalMs;
        this.scheduler = scheduler;
    }

    /**
     * 启动周期摘要, 返回 handle. 调用方持 handle, 任务结束调 handle.stop().
     *
     * @param taskId          coordinator 子任务 id
     * @param agentId         fork 目标 agent id
     * @param summarizer      摘要委托 (LLM + transcript 副作用)
     * @param updateCallback  摘要更新回调 (通常注入 AgentProgress / setAppState)
     * @return 句柄; 调 stop() 取消定时器 + 终止 in-flight
     */
    public AgentSummaryHandle start(String taskId, String agentId,
                                    SummarySummarizer summarizer,
                                    Consumer<String> updateCallback) {
        AgentSummaryState state = new AgentSummaryState(taskId, agentId, summarizer, updateCallback);
        states.put(agentId, state);
        log.info("[AgentSummary] 启动摘要 task={} agent={} intervalMs={}",
            taskId, agentId, summaryIntervalMs);
        state.scheduleNext();
        return new AgentSummaryHandle(state);
    }

    /** 当前活跃的 agent 列表. */
    public java.util.Set<String> activeAgents() {
        return java.util.Collections.unmodifiableSet(states.keySet());
    }

    /** 测试/管理用: 主动 shutdown scheduler. */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ────────────────────── 内部状态 ──────────────────────

    class AgentSummaryState {
        final String taskId;
        final String agentId;
        final SummarySummarizer summarizer;
        final Consumer<String> updateCallback;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        volatile ScheduledFuture<?> scheduledTask;
        volatile String previousSummary;
        /** 本轮 in-flight 摘要的 AbortController (CC agentSummary.ts:91 summaryAbortController). */
        volatile AbortController inFlight;

        AgentSummaryState(String taskId, String agentId,
                          SummarySummarizer summarizer,
                          Consumer<String> updateCallback) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.summarizer = summarizer;
            this.updateCallback = updateCallback;
        }

        void scheduleNext() {
            if (stopped.get()) return;
            scheduledTask = scheduler.schedule(this::runSummary, summaryIntervalMs, TimeUnit.MILLISECONDS);
        }

        void runSummary() {
            if (stopped.get()) return;
            // CC agentSummary.ts:91 每轮新建 summaryAbortController (stop 时 abort in-flight)
            AbortController abortController = new AbortController();
            inFlight = abortController;
            try {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentSummary] 定时器触发 agent={}", agentId);
                }
                List<AgentMessage> transcript = summarizer.readTranscript(agentId);
                if (transcript == null || transcript.size() < summarizer.minMessages()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[AgentSummary] 跳过 {}: 消息不足 ({})",
                            taskId, transcript == null ? 0 : transcript.size());
                    }
                    return;
                }
                // CC agentSummary.ts:78 filterIncompleteToolCalls → forkContextMessages (clean 上下文)
                List<AgentMessage> clean = summarizer.filterIncompleteToolCalls(transcript);
                if (log.isDebugEnabled()) {
                    log.debug("[AgentSummary] 摘要 fork {} 条 clean 消息上下文 (CC agentSummary.ts:86-88)",
                        clean.size());
                }
                String prompt = buildSummaryPrompt(previousSummary);
                String summary;
                if (summarizer instanceof SummarySummarizerImpl impl) {
                    // 透传 clean 上下文 + per-run AbortController (A6/A7)
                    summary = impl.summarize(agentId, prompt, clean, abortController);
                } else {
                    // 非本实现注入方: 走接口契约 (impl 内部自读自滤, 无 abort)
                    summary = summarizer.summarize(agentId, prompt);
                }
                // CC agentSummary.ts:121 fork 后 stopped 复查 — 防 stop 竞态下回调仍触发 (T3/T4, A8)
                if (stopped.get()) {
                    return;
                }
                // A5: trim 非空才更新
                if (summary != null && !summary.trim().isEmpty()) {
                    String trimmed = summary.trim();
                    previousSummary = trimmed;
                    log.info("[AgentSummary] {} 摘要: {}", taskId, trimmed);
                    updateCallback.accept(trimmed);
                }
            } catch (Exception e) {
                if (!stopped.get() && e instanceof RuntimeException) {
                    log.error("[AgentSummary] {} 失败: {}", taskId, e.getMessage(), e);
                }
            } finally {
                // CC agentSummary.ts:149 每轮结束清空 in-flight controller
                inFlight = null;
                // CC agentSummary.ts:151-153 finally scheduleNext (而非 initiation)
                if (!stopped.get()) {
                    scheduleNext();
                }
            }
        }

        void stop() {
            if (stopped.compareAndSet(false, true)) {
                if (scheduledTask != null) {
                    scheduledTask.cancel(false);
                }
                // CC agentSummary.ts:169-171 abort in-flight summary (中断进行中 LLM 调用)
                AbortController ac = inFlight;
                if (ac != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[AgentSummary] abort in-flight 摘要 agent={}", agentId);
                    }
                    ac.abort();
                }
                states.remove(agentId);
                log.info("[AgentSummary] 已停止 task={} agent={}", taskId, agentId);
            }
        }
    }

    /**
     * CC buildSummaryPrompt (agentSummary.ts:28-44): 模板 + previousSummary 续写.
     *
     * <p>[S5 P0 差异 3] 补全 4 Good + 4 Bad · 对齐 agentSummary.ts:35-43:
     * <ul>
     *   <li>Good (:35-38): Reading runAgent.ts / Fixing null check in validate.ts /
     *       Running auth module tests / <b>Adding retry logic</b> 一项 (补)</li>
     *   <li>Bad (:40-43): past tense / too vague / <b>too long</b> (补) / <b>branch name</b> (补)</li>
     * </ul>
     * 缺 "Adding retry logic" 提示 → LLM 摘要易用过去时; 缺 "too long"/"branch name" 反例 →
     * 摘要可能过长或含分支名 (探査 §3.8 #14).
     *
     * <p>package-private static (而非 private): 单测直接验证 prompt 含完整示例 (Pattern #14 seam).
     */
    static String buildSummaryPrompt(String previousSummary) {
        String prevLine = previousSummary != null
            ? "\nPrevious: \"" + previousSummary + "\" — say something NEW.\n"
            : "";
        return "Describe your most recent action in 3-5 words using present tense (-ing). " +
            "Name the file or function, not the branch. Do not use tools.\n" +
            prevLine + "\n" +
            "Good: \"Reading runAgent.ts\"\n" +
            "Good: \"Fixing null check in validate.ts\"\n" +
            "Good: \"Running auth module tests\"\n" +
            "Good: \"Adding retry logic to fetchUser\"\n" +
            "\n" +
            "Bad (past tense): \"Analyzed the branch diff\"\n" +
            "Bad (too vague): \"Investigating the issue\"\n" +
            "Bad (too long): \"Reviewing full branch diff and AgentTool.tsx integration\"\n" +
            "Bad (branch name): \"Analyzed adam/background-summary branch diff\"";
    }
}