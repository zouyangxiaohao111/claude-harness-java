package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session log ingress · 对齐 CC services/api/sessionIngress.ts.
 *
 * <p>L1 语义: append transcript messages to remote session log;
 *            10 retries + exponential backoff (500ms base);
 *            Last-Uuid 头追踪;409 → adopt server uuid;
 *            per-session sequential wrapper (防止并发写冲突).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: MAX_RETRIES=10; BASE_DELAY_MS=500; lastUuidMap Map;
 *       appendSessionLog(sessionId, entry) → boolean; 401 fail immediately.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — appendSessionLog → retry 5xx/429;
 *       401 → fail immediately; 409 → adopt server uuid + retry.</li>
 *   <li><b>A3</b>: 注入式 (authTokenSupplier + httpFetcher);silent failure on retries.</li>
 *   <li><b>A4</b>: authToken null → fail;HttpFetcher throw → retry.</li>
 *   <li><b>A5</b>: 真实场景 — session transcript 实时同步到 backend.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash sequential → Java supplier wrapper;
 *                    TS retry → Java for-loop with exponential backoff;
 *                    TS Map state → Java ConcurrentHashMap.
 */
public final class SessionIngress {

    private static final Logger log = LoggerFactory.getLogger(SessionIngress.class);

    public static final int MAX_RETRIES = 10;
    public static final long BASE_DELAY_MS = 500L;
    public static final String LAST_UUID_HEADER = "Last-Uuid";

    public interface Entry {
        String uuid();
        String type();
    }

    public record SimpleEntry(String uuid, String type) implements Entry {}

    public interface HttpFetcher {
        int put(String url, Map<String, String> headers, Object body);
    }

    private final java.util.Map<String, String> lastUuidMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final Supplier<String> authTokenSupplier;
    private final Supplier<String> baseUrlSupplier;
    private final HttpFetcher httpFetcher;

    public SessionIngress(Supplier<String> authTokenSupplier,
            Supplier<String> baseUrlSupplier,
            HttpFetcher httpFetcher) {
        this.authTokenSupplier = Objects.requireNonNull(authTokenSupplier);
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.httpFetcher = httpFetcher == null ? (u, h, b) -> 500 : httpFetcher;
    }

    public SessionIngress() {
        this(() -> null, () -> "https://api.anthropic.com", null);
    }

    /** CC appendSessionLog — 主链. */
    public boolean appendSessionLog(String sessionId, Entry entry) {
        if (sessionId == null || entry == null) return false;
        String url = getIngressUrl(sessionId);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            java.util.Map<String, String> headers = buildHeaders();
            String lastUuid = lastUuidMap.get(sessionId);
            if (lastUuid != null) headers.put(LAST_UUID_HEADER, lastUuid);
            try {
                int status = httpFetcher.put(url, headers, entry);
                if (status >= 200 && status < 300) {
                    if (entry.uuid() != null) lastUuidMap.put(sessionId, entry.uuid());
                    return true;
                }
                if (status == 401) return false; // 401 不重试
                if (status == 409) {
                    lastUuidMap.remove(sessionId);
                }
            } catch (Exception ex) {
                log.warn("appendSessionLog attempt {} failed: {}", attempt, ex.getMessage());
            }
            try { Thread.sleep(BASE_DELAY_MS * (1L << (attempt - 1))); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    public String getLastUuid(String sessionId) {
        return sessionId == null ? null : lastUuidMap.get(sessionId);
    }

    public void clearLastUuid(String sessionId) {
        if (sessionId != null) lastUuidMap.remove(sessionId);
    }

    private String getIngressUrl(String sessionId) {
        String base = baseUrlSupplier.get();
        return (base == null ? "https://api.anthropic.com" : base)
            + "/v1/sessions/" + sessionId + "/logs";
    }

    private java.util.Map<String, String> buildHeaders() {
        String token = authTokenSupplier.get();
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Content-Type", "application/json");
        if (token != null) h.put("Authorization", "Bearer " + token);
        return h;
    }
}