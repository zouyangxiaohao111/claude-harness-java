package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session Runner · 对齐 CC bridge/sessionRunner.ts.
 *
 * <p>FIX-BRIDGE-9: 简化版会话运行循环.
 */
@Component
public class SessionRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionRunner.class);

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    public record SessionState(String sessionId, Status status, long startedAt) {}

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState start(String sessionId) {
        SessionState s = new SessionState(sessionId, Status.RUNNING, System.currentTimeMillis());
        sessions.put(sessionId, s);
        log.info("SessionRunner: start session={}", sessionId);
        return s;
    }

    public SessionState complete(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s == null) return null;
        SessionState updated = new SessionState(sessionId, Status.COMPLETED, s.startedAt());
        sessions.put(sessionId, updated);
        return updated;
    }

    public SessionState fail(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s == null) return null;
        SessionState updated = new SessionState(sessionId, Status.FAILED, s.startedAt());
        sessions.put(sessionId, updated);
        return updated;
    }

    public SessionState get(String sessionId) {
        return sessions.get(sessionId);
    }
}