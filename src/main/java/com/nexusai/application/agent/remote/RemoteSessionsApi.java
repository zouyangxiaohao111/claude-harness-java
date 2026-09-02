package com.nexusai.application.agent.remote;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * CCR Sessions API 传输层契约 · 对齐 CC utils/teleport/api.ts + utils/teleport.tsx 的
 * RemoteAgentTask 状态机所需三个原语：fetchSession / pollRemoteSessionEvents / archiveRemoteSession。
 *
 * <p>CC 真源行号（grep -n 自验）:
 * <ul>
 *   <li>{@code fetchSession} — utils/teleport/api.ts:289-327（GET /v1/sessions/{id}，
 *       404→throw "Session not found: {id}"，401→throw "Session expired..."）</li>
 *   <li>{@code pollRemoteSessionEvents} — utils/teleport.tsx:633-715（GET events，
 *       after_id 增量翻页，过滤 env_manager_log/control_response，sessionStatus 来自 fetchSession）</li>
 *   <li>{@code archiveRemoteSession} — utils/teleport.tsx:1200（POST /v1/sessions/{id}/archive，
 *       200/409 视为成功，fire-and-forget）</li>
 * </ul>
 *
 * <p><b>WHY（规则九）</b>: 轮询循环（startRemoteSessionPolling，RemoteAgentTask.tsx:538-799）每 tick
 * 依赖本契约三个原语；接口隔离使状态机可脱离真实 HTTP 测试（注入 stub），
 * 生产实现 {@link HttpRemoteSessionsApi} 按 CC 语义直连 CCR。
 */
public interface RemoteSessionsApi {

    /**
     * CC fetchSession（api.ts:289-327）。
     *
     * @throws SessionNotFoundException  404 — session 已不存在（restore 判活依据）
     * @throws SessionExpiredException  401 — OAuth token 失效（restore 视为可恢复，保留 sidecar）
     * @throws RemoteApiException       其他非 200
     */
    SessionResource fetchSession(String sessionId);

    /**
     * CC pollRemoteSessionEvents（teleport.tsx:633-715）— afterId 增量拉事件。
     *
     * @param sessionId CCR session id
     * @param afterId   上次 lastEventId；null 表示从头拉
     * @return 新事件 + lastEventId + sessionStatus（fetchSession 失败时 sessionStatus 可为 null）
     */
    PollResult pollEvents(String sessionId, @Nullable String afterId);

    /**
     * CC archiveRemoteSession（teleport.tsx:1200）— best-effort 归档，200/409 视为成功。
     * fire-and-forget：调用方不等待，失败仅日志。
     */
    void archiveSession(String sessionId);

    /**
     * CC sendEventToRemoteSession（utils/teleport/api.ts:361-417）— POST
     * {@code /v1/sessions/{id}/events}，向 CCR 会话注入用户消息（RemoteSessionManager.sendMessage 走此通道）。
     *
     * <p>请求体（api.ts:376-389）：{@code {events: [{uuid, session_id, type:'user', parent_tool_use_id:null,
     * message:{role:'user', content}}]}}；uuid 缺省随机（echo 去重用，调用方先加本地 UserMessage 时传其 UUID）。
     * 成功 = 200/201（api.ts:402-407）；其余状态/异常均返回 false（api.ts:409-416，不抛）。
     *
     * @param content CC RemoteMessageContent（api.ts:349-351）= String 或 List&lt;Map&lt;String,Object&gt;&gt;
     *                content block 数组（Anthropic messages spec）
     * @param uuid    可选事件 UUID（api.ts:377）；null 则随机生成
     */
    boolean sendEventToRemoteSession(String sessionId, Object content, @Nullable String uuid);

    /**
     * CC updateSessionTitle（utils/teleport/api.ts:425-466）— PATCH {@code /v1/sessions/{id} }
     * body {@code {title}}，200 视为成功；其余状态/异常返回 false（api.ts:451-465，不抛）。
     */
    boolean updateSessionTitle(String sessionId, String title);

    /** CC SessionResource — fetchSession 返回（api.ts:289）。raw 保留 session_status 之外字段。 */
    record SessionResource(String id, @Nullable String sessionStatus, Map<String, Object> raw) {
    }

    /** CC PollRemoteSessionResponse（teleport.tsx:690-714）— 轮询响应。 */
    record PollResult(
        List<Map<String, Object>> newEvents,
        @Nullable String lastEventId,
        @Nullable String sessionStatus,
        @Nullable String branch
    ) {
        /** skipMetadata 变体（teleport.tsx:690-694）— 无 branch/sessionStatus。 */
        public static PollResult eventsOnly(List<Map<String, Object>> newEvents, @Nullable String lastEventId) {
            return new PollResult(newEvents, lastEventId, null, null);
        }
    }

    /** CC fetchSession 404 抛错（api.ts:312-314）— message 前缀 "Session not found: {id}"。 */
    class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String sessionId) {
            super("Session not found: " + sessionId);
        }
    }

    /** CC fetchSession 401 抛错（api.ts:316-318）。 */
    class SessionExpiredException extends RuntimeException {
        public SessionExpiredException() {
            super("Session expired. Please run /login to sign in again.");
        }
    }

    /** CC fetchSession 其他非 200（api.ts:320-323）。 */
    class RemoteApiException extends RuntimeException {
        public RemoteApiException(String message) {
            super(message);
        }
    }
}
