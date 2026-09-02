package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * remote_agent 任务状态 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:22-59 RemoteAgentTaskState
 * （TaskStateBase + 13 专属字段）。
 *
 * <p>Java 侧组合：{@code base} = TaskStateBase 等价物（{@link BackgroundTask}），
 * 其余字段为 CC :23-58 专属字段。框架 store 存 base（SDK/offset/evict 机制），
 * 本 record 由 {@link RemoteAgentTaskService} 持有（对齐 CC {@code state.tasks[taskId]} 语义）。
 */
public record RemoteAgentTaskState(
    /** CC TaskStateBase（Task.ts:45-57）— Java BackgroundTask 11+2 字段 */
    BackgroundTask base,
    /** CC original: remoteTaskType（:24）— 5 值枚举（脏值 restore 时回退 REMOTE_AGENT, :514） */
    RemoteTaskType remoteTaskType,
    /** CC original: remoteTaskMetadata（:26，可选）— PR number/repo 等任务专属元数据 */
    @Nullable Map<String, Object> remoteTaskMetadata,
    /** CC original: sessionId（:27）— CCR session ID，API 调用用 */
    String sessionId,
    /** CC original: command（:28） */
    String command,
    /** CC original: title（:29） */
    String title,
    /** CC original: todoList（:30）— TodoList（TodoWrite tool_use 提取） */
    List<Map<String, Object>> todoList,
    /** CC original: log（:31）— SDKMessage[] 累积事件 */
    List<Map<String, Object>> log,
    /** CC original: isLongRunning（:35，可选）— 首个 result 后不标记完成 */
    @Nullable Boolean isLongRunning,
    /** CC original: pollStartedAt（:41）— 轮询开始时间；review 超时从此时钟计算 */
    long pollStartedAt,
    /** CC original: isRemoteReview（:43，可选）— teleported /ultrareview 创建 */
    @Nullable Boolean isRemoteReview,
    /** CC original: reviewProgress（:45-50，可选）— orchestrator 心跳解析的进度计数 */
    @Nullable ReviewProgress reviewProgress,
    /** CC original: isUltraplan（:51，可选） */
    @Nullable Boolean isUltraplan,
    /** CC original: ultraplanPhase（:58，可选）— needs_input / plan_ready */
    @Nullable String ultraplanPhase
) {

    /** CC original: reviewProgress（:45-50）— stage + 三个计数。 */
    public record ReviewProgress(
        @Nullable String stage,
        int bugsFound,
        int bugsVerified,
        int bugsRefuted
    ) {
    }

    // ── 便捷访问器（委托 base，供 poll/kill 读） ──

    public String taskId() {
        return base.id();
    }

    public BackgroundTaskStatus status() {
        return base.status();
    }

    public String toolUseId() {
        return base.toolUseId();
    }

    public String description() {
        return base.description();
    }

    public boolean notified() {
        return base.notified();
    }

    // ── 副本方法（对齐 CC updateTaskState 整体替换） ──

    public RemoteAgentTaskState withBase(BackgroundTask newBase) {
        return new RemoteAgentTaskState(newBase, remoteTaskType, remoteTaskMetadata,
            sessionId, command, title, todoList, log, isLongRunning, pollStartedAt,
            isRemoteReview, reviewProgress, isUltraplan, ultraplanPhase);
    }

    public RemoteAgentTaskState withStatus(BackgroundTaskStatus status) {
        return withBase(base.withStatus(status));
    }

    public RemoteAgentTaskState withEndTime(long endTime) {
        return withBase(base.withEndTime(endTime));
    }

    public RemoteAgentTaskState withNotified() {
        return withBase(base.withNotified());
    }

    public RemoteAgentTaskState withLog(List<Map<String, Object>> newLog) {
        return new RemoteAgentTaskState(base, remoteTaskType, remoteTaskMetadata,
            sessionId, command, title, todoList, newLog, isLongRunning, pollStartedAt,
            isRemoteReview, reviewProgress, isUltraplan, ultraplanPhase);
    }

    public RemoteAgentTaskState withTodoList(List<Map<String, Object>> newTodoList) {
        return new RemoteAgentTaskState(base, remoteTaskType, remoteTaskMetadata,
            sessionId, command, title, newTodoList, log, isLongRunning, pollStartedAt,
            isRemoteReview, reviewProgress, isUltraplan, ultraplanPhase);
    }

    public RemoteAgentTaskState withReviewProgress(@Nullable ReviewProgress newProgress) {
        return new RemoteAgentTaskState(base, remoteTaskType, remoteTaskMetadata,
            sessionId, command, title, todoList, log, isLongRunning, pollStartedAt,
            isRemoteReview, newProgress, isUltraplan, ultraplanPhase);
    }
}
