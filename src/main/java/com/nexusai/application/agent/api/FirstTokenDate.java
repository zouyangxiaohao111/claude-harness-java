package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude Code first token date fetcher · 对齐 CC services/api/firstTokenDate.ts.
 *
 * <p>L1 语义: 登录后缓存 Claude Code 首次使用日期 (用于 telemetry);
 *            已缓存 → 跳过;无效日期 → 不保存;HTTP 失败 → 不抛错 (静默 catch).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: fetchAndStoreClaudeCodeFirstTokenDate() → void;
 *       firstTokenDateSupplier (cached value) + authHeadersSupplier + httpFetcher.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 已缓存 → 跳过;无 auth headers → log error + return;
 *       无效日期 → log + return (不保存); 有效 → 保存.</li>
 *   <li><b>A3</b>: 注入式 (cached supplier + http + saver); silent failure (不抛).</li>
 *   <li><b>A4</b>: null cached value → 拉;null auth → return;null date → return.</li>
 *   <li><b>A5</b>: 真实场景 — 首次登录后异步拉 first_token_date 并存到 global config.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS global mutable config → Java Supplier + Consumer (无 IO 副作用);
 *                    TS Date.parse → Java Instant.parse + isNaN check;
 *                    TS silent catch → Java try/catch + log.
 */
public final class FirstTokenDate {

    private static final Logger log = LoggerFactory.getLogger(FirstTokenDate.class);

    public static final long FETCH_TIMEOUT_MS = 10_000L;
    public static final String CONFIG_FIELD = "claudeCodeFirstTokenDate";

    private final Supplier<Object> cachedFirstTokenDateSupplier;
    private final Supplier<AuthResult> authHeadersSupplier;
    private final HttpFetcher httpFetcher;
    private final java.util.function.Consumer<String> saveFirstTokenDateConsumer;

    public FirstTokenDate(Supplier<Object> cachedFirstTokenDateSupplier,
            Supplier<AuthResult> authHeadersSupplier,
            HttpFetcher httpFetcher,
            java.util.function.Consumer<String> saveFirstTokenDateConsumer) {
        this.cachedFirstTokenDateSupplier = Objects.requireNonNull(cachedFirstTokenDateSupplier);
        this.authHeadersSupplier = Objects.requireNonNull(authHeadersSupplier);
        this.httpFetcher = httpFetcher == null ? (e, h) -> null : httpFetcher;
        this.saveFirstTokenDateConsumer = saveFirstTokenDateConsumer == null ? d -> {} : saveFirstTokenDateConsumer;
    }

    public FirstTokenDate() {
        this(() -> null, () -> AuthResult.error("not initialized"), null, d -> {});
    }

    public record AuthResult(Map<String, String> headers, String error) {
        public static AuthResult error(String msg) { return new AuthResult(Map.of(), msg); }
        public boolean hasError() { return error != null && !error.isEmpty(); }
    }

    public interface HttpFetcher {
        Object fetch(String endpoint, Map<String, String> headers);
    }

    /** CC fetchAndStoreClaudeCodeFirstTokenDate — 主链. */
    public void fetchAndStoreClaudeCodeFirstTokenDate() {
        try {
            Object cached = cachedFirstTokenDateSupplier.get();
            if (cached != null) return;

            AuthResult auth = authHeadersSupplier.get();
            if (auth.hasError()) {
                log.warn("Failed to get auth headers: {}", auth.error());
                return;
            }

            String url = "https://api.anthropic.com/api/organization/claude_code_first_token_date";
            Object response = httpFetcher.fetch(url, withUserAgent(auth.headers()));
            if (response == null) return;

            String firstTokenDate = extractFirstTokenDate(response);
            if (firstTokenDate != null) {
                if (!isValidDate(firstTokenDate)) {
                    log.warn("Received invalid first_token_date from API: {}", firstTokenDate);
                    return;
                }
            }
            saveFirstTokenDateConsumer.accept(firstTokenDate);
        } catch (Exception ex) {
            log.warn("fetchAndStoreClaudeCodeFirstTokenDate failed: {}", ex.getMessage());
        }
    }

    private static Map<String, String> withUserAgent(Map<String, String> headers) {
        java.util.Map<String, String> merged = new java.util.HashMap<>(headers);
        merged.put("User-Agent", "claude-code-java");
        return merged;
    }

    /** 简化 field 提取 — 实际 CC 用 axios,response.data.first_token_date. */
    private static String extractFirstTokenDate(Object response) {
        if (response instanceof Map<?, ?> map) {
            Object v = map.get("first_token_date");
            return v == null ? null : v.toString();
        }
        return null;
    }

    private static boolean isValidDate(String date) {
        try {
            long ms = java.time.Instant.parse(date).toEpochMilli();
            return !Double.isNaN((double) ms) && ms > 0;
        } catch (Exception ex) {
            return false;
        }
    }
}