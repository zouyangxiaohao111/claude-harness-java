package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * remote-agent sidecar 元数据 · 对齐 CC sessionStorage.ts:305-318 RemoteAgentMetadata。
 *
 * <p>CC original（src/utils/sessionStorage.ts:305-318）:
 * <pre>
 * export type RemoteAgentMetadata = {
 *   taskId: string
 *   remoteTaskType: string
 *   /** CCR session ID — used to fetch live status from the Sessions API on resume. *​/
 *   sessionId: string
 *   title: string
 *   command: string
 *   spawnedAt: number
 *   toolUseId?: string
 *   isLongRunning?: boolean
 *   isUltraplan?: boolean
 *   isRemoteReview?: boolean
 *   remoteTaskMetadata?: Record&lt;string, unknown&gt;
 * }
 * </pre>
 *
 * <p><b>WHY（规则九）</b>: sidecar 是 --resume 恢复的唯一持久化身份（CC 注释 :332-336：
 * "status is always fetched fresh from CCR on restore — only identity is persisted locally"）。
 * status/notified 不落盘 —— 恢复时从 CCR 重新取活。字段命名须与 CC 逐一对齐，
 * JSON 序列化键名 = record 组件名（camelCase，与 CC JSON.stringify 产出一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteAgentMetadata(
    /** CC original: taskId（sessionStorage.ts:306） */
    String taskId,
    /** CC original: remoteTaskType（:307）— 未校验字符串，restore 时经 isRemoteTaskType 守卫（RemoteAgentTask.tsx:514） */
    String remoteTaskType,
    /** CC original: sessionId（:309）— CCR session ID，restore 时 fetchSession 判活 */
    String sessionId,
    /** CC original: title（:310） */
    String title,
    /** CC original: command（:311） */
    String command,
    /** CC original: spawnedAt（:312）— 任务 spawn 时间戳，restore 重建时作为 startTime */
    long spawnedAt,
    /** CC original: toolUseId（:313，可选） */
    @Nullable String toolUseId,
    /** CC original: isLongRunning（:314，可选） */
    @Nullable Boolean isLongRunning,
    /** CC original: isUltraplan（:315，可选） */
    @Nullable Boolean isUltraplan,
    /** CC original: isRemoteReview（:316，可选） */
    @Nullable Boolean isRemoteReview,
    /** CC original: remoteTaskMetadata（:317，可选）— PR number/repo 等任务专属元数据 */
    @Nullable Map<String, Object> remoteTaskMetadata,
    /**
     * Phase 4 (cron-notify): 本地创建会话 sessionId（CC original: 无 —— CC 单进程单主会话 ambient；
     * Java 多会话 web 服务显式携带）。与 {@link #sessionId()}（CCR remote 会话，API 轮询用）<b>不同</b>：
     * 本字段是<b>本地</b>发起该远程任务的会话，远程任务完成通知经 {@code QueueItem.sessionId} 注入该
     * 本地会话回合（drain 3a）。--resume 恢复时随 sidecar 读回，恢复后通知仍归创建会话。
     * null = 无本地会话上下文（回落全局）。
     */
    @Nullable String creatingSessionId
) {
    public RemoteAgentMetadata {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId cannot be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId cannot be blank");
        if (remoteTaskType == null) remoteTaskType = "";
        if (title == null) title = "";
        if (command == null) command = "";
    }
}
