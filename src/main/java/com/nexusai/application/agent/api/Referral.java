package com.nexusai.application.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Referral / guest pass eligibility · 对齐 CC services/api/referral.ts.
 *
 * <p>L1 语义: Claude.ai 用户推荐 (guest pass) eligibility 拉取 + 24h disk cache;
 *            fire-and-forget fetch;in-flight dedupe;currency-aware 金额格式化.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: CACHE_EXPIRATION_MS=24h; ReferralCampaign constant; 7 method;
 *       ReferralEligibilityResponse/RedemptionsResponse/ReferrerRewardInfo record;
 *       CURRENCY_SYMBOLS 7 货币 + formatCreditAmount.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — shouldCheckForPasses false → 不检查;cache fresh → 返回;
 *       cache stale → 返回 stale + 后台 refresh.</li>
 *   <li><b>A3</b>: 注入式 (accountInfo + configStore + httpFetcher);in-flight dedupe via AtomicReference.</li>
 *   <li><b>A4</b>: 无 orgId → null;HTTP 失败 → null.</li>
 *   <li><b>A5</b>: 真实场景 — /passes 命令启动时 background prefetch;UI 显示 $5 credit.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash memoize → Java AtomicReference 缓存;
 *                    TS Promise → Java Supplier (异步由 caller wired).
 */
public final class Referral {

    private static final Logger log = LoggerFactory.getLogger(Referral.class);

    public static final long CACHE_EXPIRATION_MS = 24L * 60L * 60L * 1000L;
    public static final String DEFAULT_CAMPAIGN = "claude_code_guest_pass";
    public static final long FETCH_TIMEOUT_MS = 5_000L;
    public static final long REDEMPTIONS_TIMEOUT_MS = 10_000L;

    public static final Map<String, String> CURRENCY_SYMBOLS = Map.of(
        "USD", "$", "EUR", "€", "GBP", "£", "BRL", "R$",
        "CAD", "CA$", "AUD", "A$", "NZD", "NZ$", "SGD", "S$");

    public record ReferralEligibilityResponse(
        boolean eligible,
        Integer remainingPasses,
        ReferrerRewardInfo referrerReward) {}

    public record ReferralRedemptionsResponse(List<Object> redemptions) {}

    public record ReferrerRewardInfo(String currency, long amountMinorUnits) {}

    public record CachedEntry(
        boolean eligible, Integer remainingPasses, ReferrerRewardInfo referrerReward, long timestamp) {}

    public record CacheCheck(boolean eligible, boolean needsRefresh, boolean hasCache) {}

    public interface HttpFetcher {
        ReferralEligibilityResponse fetch(String endpoint, Map<String, String> headers, String campaign);
        ReferralRedemptionsResponse fetchRedemptions(String endpoint, Map<String, String> headers, String campaign);
    }

    private final Supplier<String> orgIdSupplier;
    private final Supplier<Map<String, CachedEntry>> cacheSupplier;
    private final java.util.function.BiConsumer<String, CachedEntry> cacheSaver;
    private final java.util.function.BooleanSupplier subscriberSupplier;
    private final Supplier<String> subscriptionTypeSupplier;
    private final HttpFetcher httpFetcher;

    private final AtomicReference<ReferralEligibilityResponse> inFlight = new AtomicReference<>();

    public Referral(Supplier<String> orgIdSupplier,
            Supplier<Map<String, CachedEntry>> cacheSupplier,
            java.util.function.BiConsumer<String, CachedEntry> cacheSaver,
            java.util.function.BooleanSupplier subscriberSupplier,
            Supplier<String> subscriptionTypeSupplier,
            HttpFetcher httpFetcher) {
        this.orgIdSupplier = Objects.requireNonNull(orgIdSupplier);
        this.cacheSupplier = Objects.requireNonNull(cacheSupplier);
        this.cacheSaver = cacheSaver == null ? (k, v) -> {} : cacheSaver;
        this.subscriberSupplier = Objects.requireNonNull(subscriberSupplier);
        this.subscriptionTypeSupplier = Objects.requireNonNull(subscriptionTypeSupplier);
        if (httpFetcher == null) {
            this.httpFetcher = new HttpFetcher() {
                public ReferralEligibilityResponse fetch(String e, java.util.Map<String, String> h, String c) { return null; }
                public ReferralRedemptionsResponse fetchRedemptions(String e, java.util.Map<String, String> h, String c) { return null; }
            };
        } else {
            this.httpFetcher = httpFetcher;
        }
    }

    public Referral() {
        this(() -> null, Map::of, null, () -> false, () -> null, null);
    }

    public boolean shouldCheckForPasses() {
        String orgId = orgIdSupplier.get();
        return orgId != null
            && !orgId.isBlank()
            && subscriberSupplier.getAsBoolean()
            && "max".equals(subscriptionTypeSupplier.get());
    }

    /** CC fetchReferralEligibility. */
    public ReferralEligibilityResponse fetchReferralEligibility(String campaign) {
        String url = "https://api.anthropic.com/api/oauth/organizations/"
            + orgIdSupplier.get() + "/referral/eligibility";
        return httpFetcher.fetch(url, Map.of(), campaign == null ? DEFAULT_CAMPAIGN : campaign);
    }

    /** CC fetchReferralRedemptions. */
    public ReferralRedemptionsResponse fetchReferralRedemptions(String campaign) {
        String url = "https://api.anthropic.com/api/oauth/organizations/"
            + orgIdSupplier.get() + "/referral/redemptions";
        return httpFetcher.fetchRedemptions(url, Map.of(),
            campaign == null ? DEFAULT_CAMPAIGN : campaign);
    }

    /** CC checkCachedPassesEligibility. */
    public CacheCheck checkCachedPassesEligibility() {
        if (!shouldCheckForPasses()) {
            return new CacheCheck(false, false, false);
        }
        String orgId = orgIdSupplier.get();
        if (orgId == null) return new CacheCheck(false, false, false);
        Map<String, CachedEntry> cache = cacheSupplier.get();
        CachedEntry entry = cache == null ? null : cache.get(orgId);
        if (entry == null) {
            return new CacheCheck(false, true, false);
        }
        boolean stale = System.currentTimeMillis() - entry.timestamp() > CACHE_EXPIRATION_MS;
        return new CacheCheck(entry.eligible(), stale, true);
    }

    /** CC formatCreditAmount — currency-aware. */
    public static String formatCreditAmount(ReferrerRewardInfo reward) {
        if (reward == null) return "";
        String symbol = CURRENCY_SYMBOLS.getOrDefault(reward.currency(), reward.currency() + " ");
        double amount = reward.amountMinorUnits() / 100.0;
        String formatted = (amount % 1 == 0)
            ? String.valueOf((long) amount)
            : String.format("%.2f", amount);
        return symbol + formatted;
    }

    /** CC getCachedReferrerReward. */
    public ReferrerRewardInfo getCachedReferrerReward() {
        String orgId = orgIdSupplier.get();
        if (orgId == null) return null;
        Map<String, CachedEntry> cache = cacheSupplier.get();
        CachedEntry entry = cache == null ? null : cache.get(orgId);
        return entry == null ? null : entry.referrerReward();
    }

    /** CC getCachedRemainingPasses. */
    public Integer getCachedRemainingPasses() {
        String orgId = orgIdSupplier.get();
        if (orgId == null) return null;
        Map<String, CachedEntry> cache = cacheSupplier.get();
        CachedEntry entry = cache == null ? null : cache.get(orgId);
        return entry == null ? null : entry.remainingPasses();
    }

    /** CC fetchAndStorePassesEligibility — in-flight dedupe. */
    public ReferralEligibilityResponse fetchAndStorePassesEligibility() {
        if (inFlight.get() != null) return inFlight.get();
        String orgId = orgIdSupplier.get();
        if (orgId == null) return null;
        inFlight.set(null); // 占位,后续覆盖
        try {
            ReferralEligibilityResponse response = fetchReferralEligibility(DEFAULT_CAMPAIGN);
            cacheSaver.accept(orgId, new CachedEntry(
                response.eligible(),
                response.remainingPasses(),
                response.referrerReward(),
                System.currentTimeMillis()));
            inFlight.set(null);
            return response;
        } catch (Exception ex) {
            log.warn("fetchAndStorePassesEligibility failed: {}", ex.getMessage());
            inFlight.set(null);
            return null;
        }
    }

    public String defaultCampaign() { return DEFAULT_CAMPAIGN; }
}