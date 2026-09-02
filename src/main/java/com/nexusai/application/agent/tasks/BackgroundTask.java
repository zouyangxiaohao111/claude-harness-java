package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;

import java.util.UUID;

/**
 * 后台任务数据结构 — 11 字段精确对齐 CC TaskStateBase:45-57
 *
 * <p>CC 源码 (Task.ts:45-57):
 * <pre>
 * export type TaskStateBase = {
 *   id: string              // generateTaskId(prefix) → prefix + randomBytes(8)
 *   type: TaskType          // 7 种枚举
 *   status: TaskStatus      // 5 种枚举
 *   description: string     // 人类可读描述
 *   toolUseId?: string      // 关联的 tool_use block ID
 *   startTime: number       // Date.now()
 *   endTime?: number        // 完成/失败/kill 时间
 *   totalPausedMs?: number  // 累计暂停时间
 *   outputFile: string      // 输出文件路径
 *   outputOffset: number    // 上次通知后已读字节偏移
 *   notified: boolean       // 是否已发送完成通知
 * }
 * </pre>
 *
 * <p><b>字段名必须与 CC 精确对齐:</b>
 * <ul>
 *   <li>{@code id} — CC TaskStateBase.id, NOT "taskId"</li>
 *   <li>{@code startTime} — CC TaskStateBase.startTime, NOT "createdAt"</li>
 *   <li>{@code endTime} — CC TaskStateBase.endTime, NOT "completedAt"</li>
 *   <li>{@code totalPausedMs} — L1 必须, CC TaskStateBase.line 53</li>
 *   <li>{@code outputOffset} — L1 增量读取, CC TaskStateBase.line 55</li>
 *   <li>{@code notified} — L1 防重复通知, CC TaskStateBase.line 56</li>
 * </ul>
 *
 * <p>L3 升级: TypeScript type → Java 17 record (不可变, 自动 equals/hashCode)
 *
 * <p><b>Phase 3 扩展</b>：对齐 CC AgentTool.tsx:686-764 + LocalAgentTask.tsx:197-262 —
 * 新增 {@code agentId} + {@code isBackgrounded} 两字段，支持 owner-scoped 批 kill
 * （{@code killShellTasksForAgent}）和 async agent task attribution
 * （CC taskId===agentId 合一, async path 用）。
 * <ul>
 *   <li>{@code agentId} — 拥有此 task 的 sub-agent UUID；null 表示 main-thread
 *       spawn（如 BashTool 前台 → 后台路径）</li>
 *   <li>{@code isBackgrounded} — 前台 → 后台 切换标记；false 表示前台任务尚未显式
 *       后台化（用于 {@code BackgroundTaskPredicate.isBackgroundTask}）</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackgroundTask(
    /** CC TaskStateBase.id (line 46) — NOT "taskId" */
    String id,
    /** CC TaskStateBase.type (line 47) — 7 种枚举 */
    TaskType type,
    /** CC TaskStateBase.status (line 48) — 5 种枚举 */
    BackgroundTaskStatus status,
    /** CC TaskStateBase.description (line 49) — 人类可读描述 */
    String description,
    /** CC TaskStateBase.toolUseId (line 50) — 关联 tool_use block (nullable) */
    @Nullable String toolUseId,
    /** CC TaskStateBase.startTime (line 51) — NOT "createdAt" */
    long startTime,
    /** CC TaskStateBase.endTime (line 52) — NOT "completedAt" (nullable) */
    @Nullable Long endTime,
    /** CC TaskStateBase.totalPausedMs (line 53) — 累计暂停时间 (nullable) */
    @Nullable Long totalPausedMs,
    /** CC TaskStateBase.outputFile (line 54) — 输出文件绝对路径 */
    String outputFile,
    /** CC TaskStateBase.outputOffset (line 55) — L1 增量读取偏移 */
    long outputOffset,
    /** CC TaskStateBase.notified (line 56) — L1 防重复通知标记 */
    boolean notified,
    /**
     * Phase 3: 拥有此 task 的 sub-agent UUID (CC taskId===agentId 合一) ·
     * 主线程 spawn 时为 null（CC: taskId===agentId 但 main-thread task 走前台）。
     */
    @Nullable UUID agentId,
    /**
     * Phase 3: 前台 → 后台 切换标记 (CC LocalAgentTask.tsx:197-262) ·
     * 默认 true（async spawn 一律为后台）；sync 后切后台的 task 由 caller 显式 set false → true。
     */
    boolean isBackgrounded,
    /**
     * Phase 4 (cron-notify): 创建此后台任务的会话 sessionId（对齐 CC 后台任务通知注入当前循环）·
     * CC TaskStateBase 无此字段（CC 单进程单主会话 ambient 上下文天然存在，task 通知经
     * enqueuePendingNotification 无 agentId 直进当前会话队列）；Java 多会话 web 服务须显式携带——
     * 后台任务完成通知入队 NotificationQueue 时透传 {@code QueueItem.sessionId}（cron-notify），
     * drain 时 3a 过滤注入<b>创建会话</b>回合（会话活跃时），会话空闲由 CronIdleExecutor 代跑。
     * null = 无会话上下文（main-thread spawn 无 MDC / 测试直构），回落全局（CronIdleExecutor
     * GLOBAL_SESSION_UUID）。取值 = {@code RequestContext.sessionId()}（MDC，agent loop 线程注册时）。
     */
    @Nullable String sessionId,
    /**
     * [IMP-G] G25① CC TaskOutput.exitCode 跟踪 · 对齐 CC getTaskOutputData（TaskOutputTool.tsx:84-90
     * {@code exitCode: bashTask.result?.code ?? null}）——local_bash 终态 exit code；无结果 → null。
     */
    @Nullable Integer exitCode,
    /**
     * [IMP-G] G25① CC TaskOutput.error 跟踪 · 对齐 CC getTaskOutputData（TaskOutputTool.tsx:104
     * {@code error: agentTask.error}）——local_agent 失败错误串；成功 → null。
     */
    @Nullable String error,
    /**
     * [IMP-G] G25① CC TaskOutput.prompt 跟踪 · 对齐 CC getTaskOutputData（TaskOutputTool.tsx:101
     * {@code prompt: agentTask.prompt}）——local_agent spawn prompt。
     */
    @Nullable String prompt,
    /**
     * [IMP-G] G25① CC TaskOutput.result 跟踪 · 对齐 CC getTaskOutputData（TaskOutputTool.tsx:102
     * {@code result: cleanResult || output}）——local_agent 干净最终答案。
     */
    @Nullable String result,
    /**
     * [FORK-02] 保留/复用的隔离 worktree 绝对路径 · 对齐 CC enqueueAgentNotification worktreePath
     * 参数（LocalAgentTask.tsx:198-209 + worktreeSection :251 + getWorktreeResult
     * AgentTool.tsx:644-685）——fork / isolation=worktree 子代理存活（keepWorktree）或 resume 复用时
     * 由 {@link SubagentExecutor} Step 21.0 登记到 task 上，终态通知带 {@code <worktree>} 段，
     * 父 Agent 才能拿到产物路径。null = 无隔离 worktree（通知不输出 worktree 段）。
     */
    @Nullable String worktreePath,
    /**
     * [FORK-02] 对应 worktree 分支（可空，worktreePath 为空时忽略）· 对齐 CC worktreeBranch
     * （LocalAgentTask.tsx:251 worktreeSection 内三元：worktreeBranch 存在才输出
     * {@code <worktreeBranch>} 子 tag）。
     */
    @Nullable String worktreeBranch
) {
    /** 紧凑构造函数: 默认值校验 */
    public BackgroundTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id cannot be blank");
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (description == null) description = "";
        if (outputFile == null) outputFile = "";
    }

    /**
     * 向后兼容的 11 字段构造器 (Phase 3 之前) · agentId=null, isBackgrounded=true.
     *
     * <p>用于:
     * <ul>
     *   <li>BashTool 前台 → 后台 spawn (main-thread, 无 agentId)</li>
     *   <li>所有 Phase 2 之前的旧测试桩</li>
     *   <li>sub-agent spawn 一律通过 withAgentId() 显式 set agentId</li>
     * </ul>
     */
    public BackgroundTask(
            String id, TaskType type, BackgroundTaskStatus status,
            String description, @Nullable String toolUseId,
            long startTime, @Nullable Long endTime, @Nullable Long totalPausedMs,
            String outputFile, long outputOffset, boolean notified) {
        this(id, type, status, description, toolUseId,
             startTime, endTime, totalPausedMs,
             outputFile, outputOffset, notified,
             null, true, null,
             null, null, null, null, null, null);
    }

    /**
     * Phase 4 (cron-notify): 13 参兼容构造 · 默认 sessionId=null（回落全局）。
     * 既有 13 参调用方（AutonomousAgentLoop/DreamTaskRegistry/InProcessTeammateTaskRegistry 等
     * 非通知生产域）零改动，仅通知生产方（BackgroundTaskRunner/MainSessionBackgroundService/
     * MonitorMcpTaskRunner/RemoteAgentTaskService）经 canonical 14 参或 withSessionId 显式携带。
     */
    public BackgroundTask(
            String id, TaskType type, BackgroundTaskStatus status,
            String description, @Nullable String toolUseId,
            long startTime, @Nullable Long endTime, @Nullable Long totalPausedMs,
            String outputFile, long outputOffset, boolean notified,
            @Nullable UUID agentId, boolean isBackgrounded) {
        this(id, type, status, description, toolUseId,
             startTime, endTime, totalPausedMs,
             outputFile, outputOffset, notified,
             agentId, isBackgrounded, null,
             null, null, null, null, null, null);
    }

    /**
     * Phase 4 (cron-notify): 创建带 sessionId 的副本（通知生产方注册时注入创建会话）。
     */
    public BackgroundTask withSessionId(@Nullable String newSessionId) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, newSessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * 创建一个带 notified=true 的副本 (CC 通知后标记)
     */
    public BackgroundTask withNotified() {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, true,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * 创建一个带新状态的副本
     */
    public BackgroundTask withStatus(BackgroundTaskStatus newStatus) {
        return new BackgroundTask(id, type, newStatus, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * 创建一个带 endTime 的副本 (任务完成/失败/kill 时设置)
     */
    public BackgroundTask withEndTime(long newEndTime) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, newEndTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * Phase 3: 创建一个带 agentId 的副本 (killShellTasksForAgent owner-scoped 归属)
     */
    public BackgroundTask withAgentId(@Nullable UUID newAgentId) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            newAgentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * Phase 3: 创建一个带 isBackgrounded 的副本 (前台 → 后台切换)
     */
    public BackgroundTask withIsBackgrounded(boolean newIsBackgrounded) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, newIsBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * 创建一个带新 outputOffset 的副本 (offset 增量读推进) ·
     * 对齐 CC applyTaskOffsetsAndEvictions 中 {@code {...fresh, outputOffset: ...}}
     * (Open-ClaudeCode/src/utils/task/framework.ts:230)。
     */
    public BackgroundTask withOutputOffset(long newOutputOffset) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, newOutputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * [IMP-G] G25① 带 exitCode 的副本 · 对齐 CC getTaskOutputData local_bash exitCode 跟踪。
     */
    public BackgroundTask withExitCode(@Nullable Integer newExitCode) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            newExitCode, error, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * [IMP-G] G25① 带 error 的副本 · 对齐 CC getTaskOutputData local_agent error 跟踪。
     */
    public BackgroundTask withError(@Nullable String newError) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, newError, prompt, result, worktreePath, worktreeBranch);
    }

    /**
     * [IMP-G] G25① 带 prompt 的副本 · 对齐 CC getTaskOutputData local_agent prompt 跟踪。
     */
    public BackgroundTask withPrompt(@Nullable String newPrompt) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, newPrompt, result, worktreePath, worktreeBranch);
    }

    /**
     * [IMP-G] G25① 带 result 的副本 · 对齐 CC getTaskOutputData local_agent result 跟踪。
     */
    public BackgroundTask withResult(@Nullable String newResult) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, newResult, worktreePath, worktreeBranch);
    }

    /**
     * [FORK-02] 创建带隔离 worktree 信息的副本 · 对齐 CC enqueueAgentNotification worktreePath/
     * worktreeBranch（LocalAgentTask.tsx:198-209）。SubagentExecutor Step 21.0 判定 worktree 保留
     * 后调用（{@link BackgroundTaskRunner#registerTaskWorktree}），终态通知经
     * {@code task.worktreePath()/task.worktreeBranch()} 透传。
     */
    public BackgroundTask withWorktree(@Nullable String newWorktreePath,
                                       @Nullable String newWorktreeBranch) {
        return new BackgroundTask(id, type, status, description, toolUseId,
            startTime, endTime, totalPausedMs, outputFile, outputOffset, notified,
            agentId, isBackgrounded, sessionId,
            exitCode, error, prompt, result, newWorktreePath, newWorktreeBranch);
    }
}