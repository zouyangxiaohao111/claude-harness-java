package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth utilization fetcher · 对齐 CC services/api/usage.ts.
 *
 * <p>L1 语义: 拉取 Claude.ai 用户 utilization (5-hour/7-day/opus/sonnet rate limits + extra_usage);
 *            非订阅者 → empty {};token expired → null;HTTP 失败 → throw.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: RateLimit/ExtraUsage/Utilization record; fetchUtilization() → Utilization;
 *       isClaudeAISubscriber + hasProfileScope + getClaudeAIOAuthTokens 注入式.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 非订阅者 → empty {};token expired → null;HTTP → Utilization.</li>
 *   <li><b>A3</b>: 注入式 (subscriber/scope/tokens/http); 短路逻辑清晰.</li>
 *   <li><b>A4</b>: missing scope → empty {}; token expired → null; auth error → throw.</li>
 *   <li><b>A5</b>: 真实场景 — Claude.ai 用户登录后拉 utilization 显示在 UI 中.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS discriminated union RateLimit/ExtraUsage → Java record;
 *                    TS Promise → Java Supplier (异步由 caller wired);
 *                    TS Date → Java Instant + ISO 8601 string.
 */
public final class Usage {

    private static final Logger log = LoggerFactory.getLogger(Usage.class);

    public static final long FETCH_TIMEOUT_MS = 5000L;

    public record RateLimit(Double utilization, String resetsAt) {
        public static RateLimit empty() { return new RateLimit(null, null); }
    }

    public record ExtraUsage(
        boolean isEnabled,
        Double monthlyLimit,
        Double usedCredits,
        Double utilization) {
    }

    public record Utilization(
        RateLimit fiveHour,
        RateLimit sevenDay,
        RateLimit sevenDayOauthApps,
        RateLimit sevenDayOpus,
        RateLimit sevenDaySonnet,
        ExtraUsage extraUsage) {
        public static Utilization empty() {
            return new Utilization(null, null, null, null, null, null);
        }
    }

    public interface OAuthTokens {
        String getAccessToken();
        long getExpiresAt();
    }

    public interface HttpFetcher {
        Object fetch(String endpoint, java.util.Map<String, String> headers);
    }

    private final BooleanSupplier isClaudeAISubscriber;
    private final BooleanSupplier hasProfileScope;
    private final Supplier<OAuthTokens> oauthTokensSupplier;
    private final HttpFetcher httpFetcher;

    public Usage(BooleanSupplier isClaudeAISubscriber,
            BooleanSupplier hasProfileScope,
            Supplier<OAuthTokens> oauthTokensSupplier,
            HttpFetcher httpFetcher) {
        this.isClaudeAISubscriber = Objects.requireNonNull(isClaudeAISubscriber);
        this.hasProfileScope = Objects.requireNonNull(hasProfileScope);
        this.oauthTokensSupplier = Objects.requireNonNull(oauthTokensSupplier);
        this.httpFetcher = httpFetcher == null ? (e, h) -> null : httpFetcher;
    }

    public Usage() {
        this(() -> false, () -> false, () -> null, null);
    }

    /** CC fetchUtilization — 主链. */
    public Utilization fetchUtilization() {
        if (!isClaudeAISubscriber.getAsBoolean() || !hasProfileScope.getAsBoolean()) {
            return Utilization.empty();
        }
        OAuthTokens tokens = oauthTokensSupplier.get();
        if (tokens != null && isOAuthTokenExpired(tokens.getExpiresAt())) {
            return null;
        }
        try {
            java.util.Map<String, String> headers = java.util.Map.of(
                "Content-Type", "application/json",
                "User-Agent", "claude-code-java");
            Object response = httpFetcher.fetch(getOauthBaseUrl() + "/api/oauth/usage", headers);
            return parseUtilization(response);
        } catch (Exception ex) {
            throw new RuntimeException("fetchUtilization failed: " + ex.getMessage(), ex);
        }
    }

    public static boolean isOAuthTokenExpired(long expiresAt) {
        if (expiresAt <= 0) return true;
        return expiresAt < System.currentTimeMillis();
    }

    public String getOauthBaseUrl() {
        return "https://api.anthropic.com";
    }

    @SuppressWarnings("unchecked")
    public static Utilization parseUtilization(Object response) {
        if (response == null) return Utilization.empty();
        if (!(response instanceof Map)) return Utilization.empty();
        Map<String, Object> map = (Map<String, Object>) response;
        return new Utilization(
            parseRateLimit(map.get("five_hour")),
            parseRateLimit(map.get("seven_day")),
            parseRateLimit(map.get("seven_day_oauth_apps")),
            parseRateLimit(map.get("seven_day_opus")),
            parseRateLimit(map.get("seven_day_sonnet")),
            parseExtraUsage(map.get("extra_usage")));
    }

    @SuppressWarnings("unchecked")
    private static RateLimit parseRateLimit(Object o) {
        if (o == null || !(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        Double u = m.get("utilization") == null ? null
            : ((Number) m.get("utilization")).doubleValue();
        String r = m.get("resets_at") == null ? null : m.get("resets_at").toString();
        return new RateLimit(u, r);
    }

    @SuppressWarnings("unchecked")
    private static ExtraUsage parseExtraUsage(Object o) {
        if (o == null || !(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        boolean enabled = Boolean.TRUE.equals(m.get("is_enabled"));
        Double monthly = m.get("monthly_limit") == null ? null
            : ((Number) m.get("monthly_limit")).doubleValue();
        Double used = m.get("used_credits") == null ? null
            : ((Number) m.get("used_credits")).doubleValue();
        Double util = m.get("utilization") == null ? null
            : ((Number) m.get("utilization")).doubleValue();
        return new ExtraUsage(enabled, monthly, used, util);
    }
}