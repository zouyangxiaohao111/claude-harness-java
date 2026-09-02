package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock rate limit headers (ant-only testing) · 对齐 CC services/mockRateLimits.ts.
 *
 * <p>L1 语义: ant 内部测试用 — 模拟 rate limit response headers (无需真 API);
 *            ⚠️ 仅测试/demo,生产不可依赖.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 9 mock header constant (status/reset/claim/overage-status/...);
 *       3 method (setMockHeader/clearMockHeaders/shouldUseMock).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — shouldUseMock (ant + env) → setMockHeader → header applied;
 *       响应 → 注入 anthropic-ratelimit-* headers.</li>
 *   <li><b>A3</b>: 注入式 (envSupplier + userTypeSupplier);silent failure on disabled.</li>
 *   <li><b>A4</b>: 非 ant → no-op;env false → no mock.</li>
 *   <li><b>A5</b>: 真实场景 — ant 团队演示 rate limit UI → mock headers 注入.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS env → Java Supplier 注入式;
 *                    TS header object → Java Map.
 */
public final class MockRateLimits {

    private static final Logger log = LoggerFactory.getLogger(MockRateLimits.class);

    public static final String HEADER_UNIFIED_STATUS = "anthropic-ratelimit-unified-status";
    public static final String HEADER_UNIFIED_RESET = "anthropic-ratelimit-unified-reset";
    public static final String HEADER_UNIFIED_CLAIM = "anthropic-ratelimit-unified-representative-claim";
    public static final String HEADER_OVERAGE_STATUS = "anthropic-ratelimit-unified-overage-status";
    public static final String HEADER_OVERAGE_RESET = "anthropic-ratelimit-unified-overage-reset";
    public static final String HEADER_OVERAGE_DISABLED_REASON = "anthropic-ratelimit-unified-overage-disabled-reason";
    public static final String HEADER_FALLBACK = "anthropic-ratelimit-unified-fallback";
    public static final String HEADER_FALLBACK_PCT = "anthropic-ratelimit-unified-fallback-percentage";
    public static final String ENV_USER_TYPE_ANT = "ant";
    public static final String ENV_FORCE_MOCK = "FORCE_MOCK_RATE_LIMITS";

    public enum Status { ALLOWED, ALLOWED_WARNING, REJECTED }
    public enum Claim { FIVE_HOUR, SEVEN_DAY, SEVEN_DAY_OPUS, SEVEN_DAY_SONNET }
    public enum OverageDisabledReason { NOT_SUBSCRIBED, OVERAGE_DISABLED }

    private final java.util.function.Supplier<String> envSupplier;
    private final java.util.function.Supplier<String> userTypeSupplier;
    private final java.util.Map<String, String> mockHeaders;

    public MockRateLimits(java.util.function.Supplier<String> envSupplier,
            java.util.function.Supplier<String> userTypeSupplier) {
        this.envSupplier = envSupplier == null ? () -> null : envSupplier;
        this.userTypeSupplier = userTypeSupplier == null ? () -> null : userTypeSupplier;
        this.mockHeaders = new java.util.concurrent.ConcurrentHashMap<>();
    }

    public MockRateLimits() {
        this(() -> System.getenv("USER_TYPE"), () -> System.getenv("USER_TYPE"));
    }

    /** CC shouldUseMock 主链. */
    public boolean shouldUseMock() {
        String env = envSupplier.get();
        if (env == null) return false;
        if (env.contains(ENV_USER_TYPE_ANT) && env.contains(ENV_FORCE_MOCK)) return true;
        // NODE_ENV=test 也启用 (test 环境)
        if (env.contains("test")) return true;
        return false;
    }

    /** CC setMockBillingAccessOverride. */
    public void setMockHeader(String key, String value) {
        if (key == null) return;
        mockHeaders.put(key, value);
        log.debug("set mock header: {}={}", key, value);
    }

    public void clearMockHeaders() {
        mockHeaders.clear();
    }

    /** CC getMockHeaders — 注入 HTTP 响应. */
    public Map<String, String> getMockHeaders() {
        return java.util.Map.copyOf(mockHeaders);
    }

    /** CC applyMockHeaders — 静态工厂. */
    public Map<String, String> buildMockRateLimitResponse(Status status, long resetEpochSec,
            Claim claim, Status overageStatus, OverageDisabledReason disabledReason,
            boolean fallback, String fallbackPct) {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        if (status != null) h.put(HEADER_UNIFIED_STATUS, status.name().toLowerCase());
        if (resetEpochSec > 0) h.put(HEADER_UNIFIED_RESET, String.valueOf(resetEpochSec));
        if (claim != null) h.put(HEADER_UNIFIED_CLAIM, claim.name().toLowerCase());
        if (overageStatus != null) h.put(HEADER_OVERAGE_STATUS, overageStatus.name().toLowerCase());
        if (disabledReason != null) h.put(HEADER_OVERAGE_DISABLED_REASON, disabledReason.name().toLowerCase());
        if (fallback) {
            h.put(HEADER_FALLBACK, "available");
            if (fallbackPct != null) h.put(HEADER_FALLBACK_PCT, fallbackPct);
        }
        return h;
    }
}