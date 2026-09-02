package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AgentUsage;
import jakarta.annotation.Nullable;

/**
 * 子代理进度通道 · 对齐 CC {@code ProgressTracker} + {@code AgentProgress}
 * （LocalAgentTask.tsx:33-49）+ {@code updateAgentSummary}（LocalAgentTask.tsx:359-407）。
 *
 * <p><b>WHY（RF-2 ①）</b>：CC 周期摘要 {@code startAgentSummarization → updateAgentSummary}
 * 每 30s 产出一句摘要后，除写入 {@code task.progress.summary} 外，还会经
 * {@code emitTaskProgress}（sdkProgress.ts:10-36）把 {@code task_progress} SDK 事件推给前端
 * （VS Code subagent panel 等），并 gate 在 {@code getSdkAgentProgressSummariesEnabled()}
 * 上（LocalAgentTask.tsx:390）。Java 侧 {@code AgentSummaryService.start} 的
 * {@code updateCallback} 此前为 no-op（{@code summary -> {} }），摘要文本产出即丢，前端拿不到
 * 任何进度事件。本类作为「AgentProgress 通道」的 Java 等价物：可变累积进度（toolUseCount /
 * tokenCount / summary）+ 摘要应用时按 SDK 门发射 {@code task_progress}。
 *
 * <p><b>线程安全</b>：{@code applySummary} 由 agent-summary 定时线程调用，{@code setProgress}
 * 由子代理 query loop 线程调用，字段均 {@code volatile} 保证跨线程可见（CC 单进程单线程无此
 * 需求；Java 多线程由 volatile 提供 happens-before）。
 *
 * <p>CC 真源（LocalAgentTask.tsx:359-407 updateAgentSummary）：
 * <pre>
 * updateTaskState(taskId, task => {
 *   if (task.status !== 'running') return task
 *   captured = { tokenCount: task.progress?.tokenCount ?? 0, toolUseCount: ..., startTime, toolUseId }
 *   return { ...task, progress: { ...task.progress, toolUseCount: ..., tokenCount: ..., summary } }
 * })
 * if (captured &amp;&amp; getSdkAgentProgressSummariesEnabled()) {
 *   emitTaskProgress({ taskId, toolUseId, description: summary, startTime,
 *     totalTokens: tokenCount, toolUses: toolUseCount, summary })
 * }
 * </pre>
 * 注意：CC 摘要事件的 {@code description} 与 {@code summary} 均为摘要文本本身（非任务原始描述）。
 */
public final class AgentProgressTracker {

    private final String taskId;
    @Nullable
    private final String toolUseId;
    private final long startTime;

    private volatile long toolUseCount;
    private volatile long tokenCount;
    private volatile String summary;
    // CC ProgressTracker 中间态（LocalAgentTask.tsx:41-49）：input_tokens 是跨轮累积值只保留最新，
    // output_tokens 逐轮累加 —— tokenCount = latestInputTokens + cumulativeOutputTokens（:58-60）。
    private volatile long latestInputTokens;
    private volatile long cumulativeOutputTokens;

    /**
     * @param taskId    任务 id（CC taskId === agentId 合一，LocalAgentTask.tsx:132）
     * @param toolUseId 关联父 tool_use block id（可空 · CC toolUseContext.toolUseId）
     * @param startTime 任务开始时间戳（CC TaskStateBase.startTime，用于 duration_ms）
     */
    public AgentProgressTracker(String taskId, @Nullable String toolUseId, long startTime) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        this.taskId = taskId;
        this.toolUseId = toolUseId;
        this.startTime = startTime;
    }

    /**
     * 原始进度写入 · 直接覆盖 toolUseCount / tokenCount（测试与显式赋值用）。
     *
     * <p>子代理 query loop 的<b>逐 assistant message 累积</b>应改用 {@link #accumulateFromMessage}，
     * 本方法仅承载「绝对赋值」语义，不做 CC 的 latest-input 覆盖 / output 累加。
     */
    public void setProgress(long toolUseCount, long tokenCount) {
        this.toolUseCount = toolUseCount;
        this.tokenCount = tokenCount;
    }

    /**
     * 逐 assistant message 累积进度 · 对齐 CC {@code updateProgressFromMessage}
     * （LocalAgentTask.tsx:68-96）+ {@code getTokenCountFromTracker}（:58-60）。
     *
     * <p><b>累积算法（CC ProgressTracker 3 字段）</b>：
     * <ul>
     *   <li>{@code latestInputTokens = input_tokens + cache_creation_input_tokens + cache_read_input_tokens}
     *       —— Claude API 的 input_tokens 是跨轮累积值，只保留最新（CC :73-74）</li>
     *   <li>{@code cumulativeOutputTokens += output_tokens} —— output_tokens 逐轮，累加（CC :75）</li>
     *   <li>{@code toolUseCount += toolUseInMessage} —— 逐 tool_use block ++（CC :78）</li>
     *   <li>{@code tokenCount = latestInputTokens + cumulativeOutputTokens}（CC :58-60）</li>
     * </ul>
     *
     * <p><b>线程安全</b>：本方法由子代理 query loop 线程逐 assistant message 调用（单写者），
     * 字段 volatile 保证 agent-summary 定时线程 {@link #applySummary} 的 happens-before 可见性
     * （CC 单进程单线程无此需求；Java 多线程由 volatile 提供）。
     *
     * @param usage            本条 assistant 消息的完整 usage（CC message.message.usage；null = 无 usage 数据，跳过）
     * @param toolUseInMessage 本条消息的 tool_use block 数（CC content 中 tool_use 计数）
     */
    public void accumulateFromMessage(AgentUsage usage, int toolUseInMessage) {
        if (usage == null) {
            return;
        }
        long input = usage.inputTokens()
            + (usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L)
            + (usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L);
        this.latestInputTokens = input;
        this.cumulativeOutputTokens += usage.outputTokens();
        this.toolUseCount += toolUseInMessage;
        this.tokenCount = this.latestInputTokens + this.cumulativeOutputTokens;
    }

    /**
     * 应用周期摘要 · 对齐 CC updateAgentSummary（LocalAgentTask.tsx:359-407）。
     *
     * <p>写入 {@code summary}，并按 SDK 门发射 {@code task_progress} 事件（sdk 关闭或
     * queue 未装配时仅记录摘要、不发射——等价 CC {@code getSdkAgentProgressSummariesEnabled()}
     * 为 false 时的 no-op 发射，或测试直构无 bean 时静默跳过）。
     *
     * @param summary    摘要文本（CC summary 字段；发射时同时作为 description）
     * @param sdkEnabled SDK agentProgressSummaries 门（CC getSdkAgentProgressSummariesEnabled）
     * @param queue      SDK 事件队列（可空 · 测试直构无 bean）
     */
    public void applySummary(String summary, boolean sdkEnabled, SdkEventQueue queue) {
        this.summary = summary;
        if (sdkEnabled && queue != null && summary != null && !summary.isBlank()) {
            // CC emitTaskProgress({ description: summary, ..., summary }) —— description 与 summary
            // 均为摘要文本（sdkProgress.ts:10-36 + LocalAgentTask.tsx:397-405）。
            queue.emitTaskProgress(taskId, toolUseId, summary, startTime,
                    (int) tokenCount, (int) toolUseCount, null, summary);
        }
    }

    public String taskId() {
        return taskId;
    }

    @Nullable
    public String toolUseId() {
        return toolUseId;
    }

    public long startTime() {
        return startTime;
    }

    public long toolUseCount() {
        return toolUseCount;
    }

    public long tokenCount() {
        return tokenCount;
    }

    @Nullable
    public String summary() {
        return summary;
    }
}
