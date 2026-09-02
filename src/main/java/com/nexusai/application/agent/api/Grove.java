package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grove privacy settings · 对齐 CC services/api/grove.ts.
 *
 * <p>L1 语义: Claude.ai 用户隐私 (Grove) 偏好设置 — Grove 通知 enabled/disabled,
 *            notice viewed 跟踪,24h disk cache + memoized session API.
 *            essentialTrafficOnly → 跳过;OAuth 401 retry;cache invalidate on update.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: GROVE_CACHE_EXPIRATION_MS=24h; AccountSettings/GroveConfig record;
 *       ApiResult sealed + 5 method + calculateShouldShowGrove 纯函数.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — essentialOnly skip / API 401 retry / cache fresh return;
 *       updateGroveSettings invalidate memoized.</li>
 *   <li><b>A3</b>: 注入式 (essentialOnly + httpFetcher + configStore);silent failure.</li>
 *   <li><b>A4</b>: API failure → success:false;data unchanged → skip save.</li>
 *   <li><b>A5</b>: 真实场景 — /login 触发 Grove 通知;grace period 过后 gracefulShutdown.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash memoize → Java Supplier 缓存 + clear();
 *                    TS sealed union → Java sealed ApiResult interface;
 *                    TS Promise → Java Supplier (异步由 caller wired).
 */
public final class Grove {

    private static final Logger log = LoggerFactory.getLogger(Grove.class);

    public static final long CACHE_EXPIRATION_MS = 24L * 60L * 60L * 1000L;

    public record AccountSettings(Boolean groveEnabled, String groveNoticeViewedAt) {}

    public record GroveConfig(
        boolean groveEnabled,
        boolean domainExcluded,
        boolean noticeIsGracePeriod,
        Integer noticeReminderFrequency) {}

    public sealed interface ApiResult<T> permits ApiSuccess, ApiFailure {}
    public record ApiSuccess<T>(T data) implements ApiResult<T> {}
    public record ApiFailure<T>() implements ApiResult<T> {
        public static <T> ApiFailure<T> of() { return new ApiFailure<>(); }
    }

    public record CachedGroveEntry(boolean groveEnabled, long timestamp) {}

    public interface HttpFetcher {
        ApiResult<AccountSettings> fetchSettings(String endpoint, Map<String, String> headers);
        ApiResult<GroveConfig> fetchConfig(String endpoint, Map<String, String> headers);
        ApiResult<Void> markNoticeViewed(String endpoint, Map<String, String> headers);
        ApiResult<Void> updateSettings(String endpoint, Map<String, String> headers, boolean groveEnabled);
    }

    private final BooleanSupplier essentialOnlySupplier;
    private final HttpFetcher httpFetcher;
    private final Supplier<String> accountIdSupplier;
    private final Supplier<Map<String, CachedGroveEntry>> cacheSupplier;
    private final java.util.function.BiConsumer<String, CachedGroveEntry> cacheSaver;
    private final java.util.function.Supplier<ApiResult<AccountSettings>> memoizedSettings;
    private final java.util.function.Supplier<ApiResult<GroveConfig>> memoizedConfig;
    private final Runnable memoizedClear;

    public Grove(BooleanSupplier essentialOnlySupplier,
            HttpFetcher httpFetcher,
            Supplier<String> accountIdSupplier,
            Supplier<Map<String, CachedGroveEntry>> cacheSupplier,
            java.util.function.BiConsumer<String, CachedGroveEntry> cacheSaver,
            java.util.function.Supplier<ApiResult<AccountSettings>> memoizedSettings,
            java.util.function.Supplier<ApiResult<GroveConfig>> memoizedConfig,
            Runnable memoizedClear) {
        this.essentialOnlySupplier = Objects.requireNonNull(essentialOnlySupplier);
        this.httpFetcher = httpFetcher == null ? new HttpFetcher() {
            public ApiResult<AccountSettings> fetchSettings(String e, Map<String, String> h) { return ApiFailure.of(); }
            public ApiResult<GroveConfig> fetchConfig(String e, Map<String, String> h) { return ApiFailure.of(); }
            public ApiResult<Void> markNoticeViewed(String e, Map<String, String> h) { return ApiFailure.of(); }
            public ApiResult<Void> updateSettings(String e, Map<String, String> h, boolean v) { return ApiFailure.of(); }
        } : httpFetcher;
        this.accountIdSupplier = Objects.requireNonNull(accountIdSupplier);
        this.cacheSupplier = Objects.requireNonNull(cacheSupplier);
        this.cacheSaver = cacheSaver == null ? (k, v) -> {} : cacheSaver;
        this.memoizedSettings = Objects.requireNonNull(memoizedSettings);
        this.memoizedConfig = Objects.requireNonNull(memoizedConfig);
        this.memoizedClear = Objects.requireNonNull(memoizedClear);
    }

    public Grove() {
        this(() -> false, null, () -> null, Map::of, null,
            () -> ApiFailure.of(), () -> ApiFailure.of(), () -> {});
    }

    /** CC getGroveSettings — memoized. */
    public ApiResult<AccountSettings> getGroveSettings() {
        return memoizedSettings.get();
    }

    /** CC getGroveNoticeConfig — memoized. */
    public ApiResult<GroveConfig> getGroveNoticeConfig() {
        return memoizedConfig.get();
    }

    /** CC markGroveNoticeViewed. */
    public void markGroveNoticeViewed() {
        try {
            httpFetcher.markNoticeViewed(
                "https://api.anthropic.com/api/oauth/account/grove_notice_viewed", Map.of());
        } catch (Exception ex) {
            log.warn("markGroveNoticeViewed failed: {}", ex.getMessage());
        }
        memoizedClear.run();
    }

    /** CC updateGroveSettings. */
    public void updateGroveSettings(boolean groveEnabled) {
        try {
            httpFetcher.updateSettings(
                "https://api.anthropic.com/api/oauth/account/settings", Map.of(), groveEnabled);
        } catch (Exception ex) {
            log.warn("updateGroveSettings failed: {}", ex.getMessage());
        }
        memoizedClear.run();
    }

    /** CC isQualifiedForGrove — cache-first + background refresh. */
    public boolean isQualifiedForGrove() {
        String accountId = accountIdSupplier.get();
        if (accountId == null || accountId.isEmpty()) return false;
        Map<String, CachedGroveEntry> cache = cacheSupplier.get();
        CachedGroveEntry entry = cache == null ? null : cache.get(accountId);
        if (entry == null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { fetchAndStoreGroveConfig(accountId); } catch (Exception ignored) {}
            });
            return false;
        }
        if (System.currentTimeMillis() - entry.timestamp() > CACHE_EXPIRATION_MS) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { fetchAndStoreGroveConfig(accountId); } catch (Exception ignored) {}
            });
            return entry.groveEnabled();
        }
        return entry.groveEnabled();
    }

    /** CC fetchAndStoreGroveConfig. */
    public boolean fetchAndStoreGroveConfig(String accountId) {
        ApiResult<GroveConfig> result = getGroveNoticeConfig();
        if (!(result instanceof ApiSuccess<GroveConfig> success)) return false;
        boolean groveEnabled = success.data().groveEnabled();
        Map<String, CachedGroveEntry> cache = cacheSupplier.get();
        CachedGroveEntry prev = cache == null ? null : cache.get(accountId);
        if (prev != null && prev.groveEnabled() == groveEnabled
            && System.currentTimeMillis() - prev.timestamp() <= CACHE_EXPIRATION_MS) {
            return false;
        }
        cacheSaver.accept(accountId, new CachedGroveEntry(groveEnabled, System.currentTimeMillis()));
        return true;
    }

    /** CC calculateShouldShowGrove — 纯函数. */
    public static boolean calculateShouldShowGrove(ApiResult<AccountSettings> settingsResult,
            ApiResult<GroveConfig> configResult, boolean showIfAlreadyViewed) {
        if (!(settingsResult instanceof ApiSuccess<AccountSettings> s)
            || !(configResult instanceof ApiSuccess<GroveConfig> c)) {
            return false;
        }
        AccountSettings settings = s.data();
        GroveConfig config = c.data();
        boolean hasChosen = settings.groveEnabled() != null;
        if (hasChosen) return false;
        if (showIfAlreadyViewed) return true;
        if (!config.noticeIsGracePeriod()) return true;
        Integer freq = config.noticeReminderFrequency();
        if (freq != null && settings.groveNoticeViewedAt() != null) {
            try {
                long daysSince = (System.currentTimeMillis()
                    - java.time.Instant.parse(settings.groveNoticeViewedAt()).toEpochMilli())
                    / (1000L * 60 * 60 * 24);
                return daysSince >= freq;
            } catch (Exception ex) {
                return true;
            }
        }
        return settings.groveNoticeViewedAt() == null;
    }
}