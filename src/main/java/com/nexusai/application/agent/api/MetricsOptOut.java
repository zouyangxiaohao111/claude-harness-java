package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics opt-out contract · 对齐 CC services/api/metricsOptOut.ts.
 *
 * <p>L1 语义: 检查 org 是否启用了 metrics logging — 两级缓存 (disk 24h + in-memory 1h);
 *            essentialTrafficOnly=true → enabled:false;
 *            service-key OAuth (无 user:profile scope) → enabled:false (缓存防污染);
 *            transient 错误 → enabled:false + hasError=true (不持久化到 disk).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: MetricsEnabledResponse record (metricsLoggingEnabled) + MetricsStatus record;
 *       CACHE_TTL_MS=3600000; DISK_CACHE_TTL_MS=86400000; checkMetricsEnabled + refreshMetricsStatus.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — service-key OAuth → enabled:false 短路;
 *       disk cache fresh → 返回 cache;
 *       disk cache stale → background refresh.</li>
 *   <li><b>A3</b>: 注入式 (essentialOnly + subscriber + profileScope + httpFetcher + configStore);
 *       silent failure (log + return enabled:false).</li>
 *   <li><b>A4</b>: subscriber true + profileScope false → enabled:false; HTTP 错误 → hasError=true.</li>
 *   <li><b>A5</b>: 真实场景 — bigqueryExporter 检查 enabled 后决定是否 export;
 *               daily CLI invocation dedupe via 24h disk cache.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS memoizeWithTTLAsync → Java Supplier + clear() method;
 *                    TS timestamp → Java System.currentTimeMillis();
 *                    TS Date.now() → Java long millis.
 */
public final class MetricsOptOut {

    private static final Logger log = LoggerFactory.getLogger(MetricsOptOut.class);

    public static final long CACHE_TTL_MS = 60L * 60L * 1000L;       // 1h in-memory
    public static final long DISK_CACHE_TTL_MS = 24L * 60L * 60L * 1000L; // 24h disk

    public record MetricsEnabledResponse(boolean metricsLoggingEnabled) {}
    public record MetricsStatus(boolean enabled, boolean hasError) {}

    private final java.util.function.BooleanSupplier essentialOnlySupplier;
    private final java.util.function.BooleanSupplier isClaudeAISubscriber;
    private final java.util.function.BooleanSupplier hasProfileScopeSupplier;
    private final HttpFetcher httpFetcher;
    private final Supplier<CacheEntry> diskCacheSupplier;
    private final java.util.function.Consumer<CacheEntry> diskCacheSaver;
    private final java.util.function.Supplier<MetricsStatus> apiFetcher; // in-memory memoized

    public MetricsOptOut(java.util.function.BooleanSupplier essentialOnlySupplier,
            java.util.function.BooleanSupplier isClaudeAISubscriber,
            java.util.function.BooleanSupplier hasProfileScopeSupplier,
            HttpFetcher httpFetcher,
            Supplier<CacheEntry> diskCacheSupplier,
            java.util.function.Consumer<CacheEntry> diskCacheSaver,
            java.util.function.Supplier<MetricsStatus> apiFetcher) {
        this.essentialOnlySupplier = Objects.requireNonNull(essentialOnlySupplier);
        this.isClaudeAISubscriber = Objects.requireNonNull(isClaudeAISubscriber);
        this.hasProfileScopeSupplier = Objects.requireNonNull(hasProfileScopeSupplier);
        this.httpFetcher = httpFetcher == null ? (e, h) -> new MetricsEnabledResponse(false) : (e, h) -> {
            Object r = httpFetcher.fetch(e, h);
            if (r instanceof MetricsEnabledResponse mer) return new MetricsStatus(mer.metricsLoggingEnabled(), false);
            return new MetricsStatus(false, false);
        };
        this.diskCacheSupplier = Objects.requireNonNull(diskCacheSupplier);
        this.diskCacheSaver = diskCacheSaver == null ? c -> {} : diskCacheSaver;
        this.apiFetcher = apiFetcher == null ? () -> new MetricsStatus(false, false) : apiFetcher;
    }

    public MetricsOptOut() {
        this(() -> false, () -> false, () -> false, null, () -> null, null, null);
    }

    public record CacheEntry(boolean enabled, long timestamp) {}

    public interface HttpFetcher {
        Object fetch(String endpoint, Map<String, String> headers);
    }

    /** CC checkMetricsEnabled — 主链. */
    public MetricsStatus checkMetricsEnabled() {
        // service-key OAuth → 短路 enabled:false
        if (isClaudeAISubscriber.getAsBoolean() && !hasProfileScopeSupplier.getAsBoolean()) {
            return new MetricsStatus(false, false);
        }
        CacheEntry cached = diskCacheSupplier.get();
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestamp() > DISK_CACHE_TTL_MS) {
                // stale → background refresh
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { refreshMetricsStatus(); } catch (Exception ex) {
                        log.warn("Background refresh failed: {}", ex.getMessage());
                    }
                });
            }
            return new MetricsStatus(cached.enabled(), false);
        }
        // 首次运行 → 阻塞刷新
        return refreshMetricsStatus();
    }

    /** CC refreshMetricsStatus. */
    public MetricsStatus refreshMetricsStatus() {
        if (essentialOnlySupplier.getAsBoolean()) {
            return new MetricsStatus(false, false);
        }
        MetricsStatus result;
        try {
            result = apiFetcher.get();
        } catch (Exception ex) {
            log.warn("Failed to check metrics opt-out: {}", ex.getMessage());
            return new MetricsStatus(false, true);
        }
        if (result.hasError()) return result;
        CacheEntry cached = diskCacheSupplier.get();
        if (cached != null && cached.enabled() == result.enabled()) {
            // 数据未变 → skip write
            return result;
        }
        diskCacheSaver.accept(new CacheEntry(result.enabled(), System.currentTimeMillis()));
        return result;
    }

    /** CC _clearMetricsEnabledCacheForTesting. */
    public void clearCacheForTesting() {
        // In real impl, clear memoized API cache. Java 端 caller wired.
    }

    public String endpoint() {
        return "https://api.anthropic.com/api/claude_code/organizations/metrics_enabled";
    }
}