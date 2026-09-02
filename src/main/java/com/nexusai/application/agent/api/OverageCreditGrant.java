package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overage credit grant contract · 对齐 CC services/api/overageCreditGrant.ts.
 *
 * <p>L1 语义: 后端解析 tier-specific amounts + role-based claim permission,
 *            CLI 只读 response;1 小时 disk cache + invalidate 允许强制 refetch;
 *            formatGrantAmount 仅 USD (backend 后续扩展).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: OverageCreditGrantInfo record (5 字段) + CachedGrantEntry record;
 *       CACHE_TTL_MS=3600000; getCachedOverageCreditGrant / refreshOverageCreditGrantCache /
 *       invalidateOverageCreditGrantCache / formatGrantAmount.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 无 orgId → null;cache miss → refresh → fetch → save;
 *       info 未变 + timestamp fresh → skip write.</li>
 *   <li><b>A3</b>: 注入式 (accountInfo + configStore + httpFetcher); 静默失败 (log + return null).</li>
 *   <li><b>A4</b>: orgId null → null; cache 过时 (>1h) → null; HTTP 失败 → null.</li>
 *   <li><b>A5</b>: 真实场景 — upsell surface 显示 $5 grant 时拉 cache;空时 fire-and-forget refresh.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash isEqual → Java record.equals;
 *                    TS Map → Java Map;
 *                    TS Map.assign → Java Map.copyOf + put.
 */
public final class OverageCreditGrant {

    private static final Logger log = LoggerFactory.getLogger(OverageCreditGrant.class);

    public static final long CACHE_TTL_MS = 60L * 60L * 1000L;

    public record OverageCreditGrantInfo(
        boolean available,
        boolean eligible,
        boolean granted,
        Long amountMinorUnits,
        String currency) {
    }

    public record CachedGrantEntry(OverageCreditGrantInfo info, long timestamp) {}

    private final Supplier<String> orgIdSupplier;
    private final Supplier<Map<String, CachedGrantEntry>> cacheSupplier;
    private final java.util.function.BiConsumer<String, CachedGrantEntry> cacheSaver;
    private final HttpFetcher httpFetcher;

    public OverageCreditGrant(Supplier<String> orgIdSupplier,
            Supplier<Map<String, CachedGrantEntry>> cacheSupplier,
            java.util.function.BiConsumer<String, CachedGrantEntry> cacheSaver,
            HttpFetcher httpFetcher) {
        this.orgIdSupplier = Objects.requireNonNull(orgIdSupplier);
        this.cacheSupplier = Objects.requireNonNull(cacheSupplier);
        this.cacheSaver = cacheSaver == null ? (k, v) -> {} : cacheSaver;
        this.httpFetcher = httpFetcher;
    }

    public OverageCreditGrant() {
        this(() -> null, Map::of, null, null);
    }

    public interface HttpFetcher {
        OverageCreditGrantInfo fetch(String endpoint, Map<String, String> headers);
    }

    /** CC getCachedOverageCreditGrant. */
    public OverageCreditGrantInfo getCachedOverageCreditGrant() {
        String orgId = orgIdSupplier.get();
        if (orgId == null || orgId.isBlank()) return null;
        CachedGrantEntry cached = cacheSupplier.get().get(orgId);
        if (cached == null) return null;
        if (System.currentTimeMillis() - cached.timestamp() > CACHE_TTL_MS) return null;
        return cached.info();
    }

    /** CC invalidateOverageCreditGrantCache. */
    public void invalidateOverageCreditGrantCache() {
        String orgId = orgIdSupplier.get();
        if (orgId == null) return;
        Map<String, CachedGrantEntry> cache = cacheSupplier.get();
        if (!cache.containsKey(orgId)) return;
        // 删除该 org entry — caller wired via cacheSaver with null entry semantics
        cacheSaver.accept(orgId, null);
    }

    /** CC refreshOverageCreditGrantCache. */
    public OverageCreditGrantInfo refreshOverageCreditGrantCache() {
        String orgId = orgIdSupplier.get();
        if (orgId == null) return null;
        OverageCreditGrantInfo info;
        try {
            info = httpFetcher.fetch(baseUrl() + "/api/oauth/organizations/" + orgId
                + "/overage_credit_grant", Map.of());
        } catch (Exception ex) {
            log.warn("fetchOverageCreditGrant failed: {}", ex.getMessage());
            return null;
        }
        if (info == null) return null;
        // Skip write if data unchanged + timestamp still fresh
        CachedGrantEntry prev = cacheSupplier.get().get(orgId);
        boolean dataUnchanged = prev != null && infoEquals(prev.info(), info);
        if (dataUnchanged && prev != null
            && System.currentTimeMillis() - prev.timestamp() <= CACHE_TTL_MS) {
            return prev.info();
        }
        cacheSaver.accept(orgId, new CachedGrantEntry(
            dataUnchanged && prev != null ? prev.info() : info,
            System.currentTimeMillis()));
        return info;
    }

    /** CC formatGrantAmount — 仅 USD. */
    public static String formatGrantAmount(OverageCreditGrantInfo info) {
        if (info == null || info.amountMinorUnits() == null || info.currency() == null) return null;
        if ("USD".equalsIgnoreCase(info.currency())) {
            double dollars = info.amountMinorUnits() / 100.0;
            return dollars == Math.floor(dollars)
                ? String.format("$%d", (long) dollars)
                : String.format("$%.2f", dollars);
        }
        return null;
    }

    private static boolean infoEquals(OverageCreditGrantInfo a, OverageCreditGrantInfo b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.available() == b.available()
            && a.eligible() == b.eligible()
            && a.granted() == b.granted()
            && java.util.Objects.equals(a.amountMinorUnits(), b.amountMinorUnits())
            && java.util.Objects.equals(a.currency(), b.currency());
    }

    private static String baseUrl() {
        return "https://api.anthropic.com";
    }
}