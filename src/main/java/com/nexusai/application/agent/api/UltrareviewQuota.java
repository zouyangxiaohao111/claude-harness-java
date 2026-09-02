package com.nexusai.application.agent.api;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultrareview quota contract · 对齐 CC services/api/ultrareviewQuota.ts.
 *
 * <p>L1 语义: 订阅用户 ultrareview 配额 (reviews_used/limit/remaining/is_overage);
 *            非订阅者或 endpoint 错误 → null. 实际 HTTP 调用由 caller wired (axios 替换为注入式 HttpGetter).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: UltrareviewQuotaResponse record (4 字段); fetchUltrareviewQuota() → Optional;
 *       isClaudeAISubscriber supplier.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — isSubscriber=false → null; endpoint 错误 → null;
 *       正常 → UltrareviewQuotaResponse.</li>
 *   <li><b>A3</b>: 纯函数 + 注入式 (HttpGetter, subscriber); 非订阅者短路返回 null.</li>
 *   <li><b>A4</b>: HttpGetter 抛异常 → null (catch).</li>
 *   <li><b>A5</b>: 真实场景 — Claude.ai 用户 ultrareview quota peek (CC 注释: consume 服务端做).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS axios.get → Java HttpGetter 注入式 (Function);
 *                    TS Promise → Java Supplier;
 *                    TS async/await → Java 同步 (异步由 caller wired).
 */
public final class UltrareviewQuota {

    private static final Logger log = LoggerFactory.getLogger(UltrareviewQuota.class);

    public static final long FETCH_TIMEOUT_MS = 5000L;

    public record UltrareviewQuotaResponse(
        int reviewsUsed,
        int reviewsLimit,
        int reviewsRemaining,
        boolean isOverage) {

        public static UltrareviewQuotaResponse empty() {
            return new UltrareviewQuotaResponse(0, 0, 0, false);
        }
    }

    private final java.util.function.BooleanSupplier isClaudeAISubscriber;
    private final Supplier<UltrareviewQuotaResponse> httpFetcher;

    public UltrareviewQuota(java.util.function.BooleanSupplier isClaudeAISubscriber,
            Supplier<UltrareviewQuotaResponse> httpFetcher) {
        this.isClaudeAISubscriber = Objects.requireNonNull(isClaudeAISubscriber);
        this.httpFetcher = httpFetcher == null ? () -> null : httpFetcher;
    }

    public UltrareviewQuota() {
        this(() -> false, () -> null);
    }

    /** CC fetchUltrareviewQuota — 主链. */
    public UltrareviewQuotaResponse fetchUltrareviewQuota() {
        if (!isClaudeAISubscriber.getAsBoolean()) return null;
        try {
            return httpFetcher.get();
        } catch (Exception ex) {
            log.debug("fetchUltrareviewQuota failed: {}", ex.getMessage());
            return null;
        }
    }

    public boolean isOverLimit(UltrareviewQuotaResponse q) {
        if (q == null) return false;
        return q.isOverage() || q.reviewsRemaining() <= 0;
    }

    public double utilizationPct(UltrareviewQuotaResponse q) {
        if (q == null || q.reviewsLimit() <= 0) return 0.0;
        return ((double) q.reviewsUsed()) / q.reviewsLimit();
    }
}