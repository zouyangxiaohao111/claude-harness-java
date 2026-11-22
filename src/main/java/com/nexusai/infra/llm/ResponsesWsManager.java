package com.nexusai.infra.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responses WS 会话状态与连接池（进程内、按 `provider|sessionId` 分区）。
 *
 * <p>对齐 codex：
 * <ul>
 *   <li>连接按会话缓存并复用（`ModelClientSession.websocket_session`）；</li>
 *   <li>传输降级是 <b>sticky</b> 的——`force_http_fallback` 把 `disable_websockets` 原子标志置位，
 *       此后该会话不再尝试 WS（client.rs:865-884 / :1417-1425）；</li>
 *   <li>增量续接状态（`previous_response_id` + 已覆盖 item 摘要）随连接存活；连接重建即清空。</li>
 * </ul>
 *
 * <p>空闲连接 10 分钟后关闭（服务端 60 分钟硬上限之前主动回收；重建后自动回全量）。
 */
final class ResponsesWsManager {

    /** 空闲回收阈值：远小于服务端 60 分钟连接上限。 */
    static final long IDLE_TTL_MS = 10 * 60 * 1000L;
    /** 清扫节流。 */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();
    private volatile long lastSweepAt = System.currentTimeMillis();

    SessionState state(String key) {
        sweepIfDue();
        return states.computeIfAbsent(key, k -> new SessionState());
    }

    /** 是否已对该会话永久禁用 WS（codex `disable_websockets` 等价物）。 */
    boolean isDisabled(String key) {
        SessionState st = states.get(key);
        return st != null && st.disabled;
    }

    /** 永久禁用该会话的 WS（426 / 消息超限 / 重试耗尽）。 */
    void disable(String key) {
        SessionState st = states.computeIfAbsent(key, k -> new SessionState());
        st.disabled = true;
        st.closeSession();
    }

    /** 进程退出时统一关闭（由 provider 的 @PreDestroy 调用）。 */
    void closeAll() {
        for (SessionState st : states.values()) {
            st.closeSession();
        }
        states.clear();
    }

    private void sweepIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastSweepAt < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepAt = now;
        for (Map.Entry<String, SessionState> e : states.entrySet()) {
            SessionState st = e.getValue();
            ResponsesWsSession session = st.session;
            if (session != null && now - session.lastUsedAt() > IDLE_TTL_MS) {
                st.closeSession();
            }
        }
    }

    /** 单个会话的 WS 状态。`lock` 串行化同一会话的 send/receive/重建。 */
    static final class SessionState {
        final Object lock = new Object();
        /** codex `disable_websockets`：置位后该会话永久走 HTTP。 */
        volatile boolean disabled;
        volatile ResponsesWsSession session;
        /** 上一次 `response.completed` 的 response id（增量续接的锚）。 */
        volatile String lastResponseId;
        /** 除 input / previous_response_id 外的请求字段指纹（不一致则回全量）。 */
        volatile String fingerprint;
        /** 当前会话内容的逐项摘要（本次 input ++ 上一轮 output），用于前缀比对。 */
        volatile List<String> conversationDigests = List.of();
        volatile long lastUsedAt = System.currentTimeMillis();

        void closeSession() {
            ResponsesWsSession s = session;
            session = null;
            invalidateContinuation();
            if (s != null) {
                s.close();
            }
        }

        void invalidateContinuation() {
            lastResponseId = null;
            conversationDigests = List.of();
        }
    }
}
