package com.nexusai.application.agent.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SettingsCache · 对齐 CC utils/settings/settingsCache.ts.
 *
 * <p>L1 语义: settings 缓存层 — sessionSettingsCache + perSourceCache + parseFileCache + pluginSettingsBase。
 * 用于加速 startup 时多源 settings 加载,避免重复 disk read + zod parse。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 cache types + 8 getter/setter + resetSettingsCache (1 call resets all 3)</li>
 *   <li><b>A2 Golden Trace</b>: getSessionSettingsCache → null/set;getCachedSettingsForSource → undefined miss / null cached-no-data / value cached;resetSettingsCache 清除 3 caches;pluginSettingsBase get/set/clear</li>
 *   <li><b>A3 内部 mutable</b>: static 状态 (caller 注意单线程)</li>
 *   <li><b>A4 边界</b>: getCachedSettingsForSource 未 set → undefined (用 containsKey 区分 cache miss vs cached null)</li>
 *   <li><b>A5 业务场景</b>: startup load settings from disk → cache hit on re-read;plugin loader writes pluginSettingsBase</li>
 * </ul>
 *
 * <p>L3 升级: TS module-level mutable `let` → Java static 状态 (单线程语义);
 * TS Map indexed → Java HashMap;
 * TS undefined sentinel for cache miss → Java Optional 风格 sentinel.
 */
public final class SettingsCache {

    public static final class CachedSettings {
        private final Map<String, Object> data;
        public CachedSettings(Map<String, Object> data) { this.data = data; }
        public Map<String, Object> get() { return data; }
    }

    public record ParsedSettings(Object settings, java.util.List<String> errors) {}

    private static final ThreadLocal<SettingsCache> INSTANCES = ThreadLocal.withInitial(SettingsCache::new);
    public static SettingsCache instance() { return INSTANCES.get(); }

    private Object sessionSettingsCache;
    private final Map<String, Object> perSourceCache = new HashMap<>();
    private final Map<String, ParsedSettings> parseFileCache = new HashMap<>();
    private Object pluginSettingsBase;

    public Object getSessionSettingsCache() { return sessionSettingsCache; }
    public void setSessionSettingsCache(Object value) { this.sessionSettingsCache = value; }

    /**
     * @return Object (the cached value, possibly null) if cached; a sentinel
     *         {@link #CACHE_MISS} if not yet cached; null if the cache entry
     *         is explicitly set to null (= "no settings for this source").
     */
    public Object getCachedSettingsForSource(String source) {
        if (!perSourceCache.containsKey(source)) return CACHE_MISS;
        return perSourceCache.get(source);
    }

    public static final Object CACHE_MISS = new Object() {
        @Override public String toString() { return "<<cache-miss>>"; }
    };

    public boolean hasCachedSettingsForSource(String source) {
        return perSourceCache.containsKey(source);
    }

    public void setCachedSettingsForSource(String source, Object value) {
        perSourceCache.put(source, value);
    }

    public ParsedSettings getCachedParsedFile(String path) {
        return parseFileCache.get(path);
    }

    public void setCachedParsedFile(String path, ParsedSettings value) {
        parseFileCache.put(path, value);
    }

    public void resetSettingsCache() {
        this.sessionSettingsCache = null;
        this.perSourceCache.clear();
        this.parseFileCache.clear();
    }

    public Object getPluginSettingsBase() { return pluginSettingsBase; }
    public void setPluginSettingsBase(Object value) { this.pluginSettingsBase = value; }
    public void clearPluginSettingsBase() { this.pluginSettingsBase = null; }
}
