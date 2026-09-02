package com.nexusai.application.agent.settings;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote-managed settings 同步缓存 (叶子状态模块) · 对齐 CC services/remoteManagedSettings/syncCacheState.ts.
 *
 * <p>L1 语义: 拆分自 syncCache.ts 以打破 settings.ts → syncCache.ts → auth.ts → settings.ts 的循环.
 *            仅依赖叶子 (path/envUtils/fileRead/jsonRead/types/settingsCache 仅类型导入),
 *            避免把 settings SCC 拖入 eager-evaluated 启动路径.
 *            Eligibility 是三态: undefined (未决→null) / false (不满足→null) / true (通过→继续).
 *            getRemoteManagedSettingsSyncFromCache() 首次成功从磁盘加载时调用 resetSettingsCache(),
 *            触发 merged cache 重建以纳入 policySettings 层 (gh-23085 修复).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 个 mutator (setSessionCache / resetSyncCache / setEligibility / resetSettingsCache);
 *       1 个 path resolver (getSettingsPath); 1 个 getter (getRemoteManagedSettingsSyncFromCache).
 *       三态 eligibility 用 Optional&lt;Boolean&gt; 表达 (empty=undefined).</li>
 *   <li><b>A2 Golden Trace</b>: getRemoteManagedSettingsSyncFromCache 主链:
 *       ineligible→null; eligible+sessionCache→sessionCache; eligible+no cache+file exists→load+cache+reset+return;
 *       eligible+no cache+file missing→null; parse-error→null. resetSyncCache 主链: 清 sessionCache + 清 eligibility.</li>
 *   <li><b>A3</b>: 状态机: ELIGIBLE_TRIPLED (UNSET → FALSE → TRUE); sessionCache 状态: NULL → LOADED.
 *       纯函数式 supplier/consumer 注入;无内部副作用.</li>
 *   <li><b>A4</b>: null SettingsJson 安全处理;non-object (array/primitive) → null;
 *       文件读取异常 (NoSuchFile/IOException/parse-error) → null (try/catch 静默).</li>
 *   <li><b>A5</b>: 真实场景 — eligible=true + 远程 settings 文件存在 → 加载 Map&lt;String,Object&gt;;
 *       eligible=false (e.g. ANTHROPIC_BASE_URL 自定义) → 返回 null 不读磁盘.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `SettingsJson = z.record(z.string(), z.unknown())` → Java `Map&lt;String,Object&gt;`;
 *                    TS `let sessionCache: SettingsJson | null` → Java `volatile Map&lt;String,Object&gt;` (thread-safe);
 *                    TS `let eligible: boolean | undefined` → Java `Optional&lt;Boolean&gt;`;
 *                    TS `getClaudeConfigHomeDir()` → 注入式 Supplier&lt;Path&gt; (testable, no static fs).
 */
public final class RemoteManagedSettingsSyncCache {

    private static final Logger log = LoggerFactory.getLogger(RemoteManagedSettingsSyncCache.class);
    private static final String SETTINGS_FILENAME = "remote-settings.json";

    private final Supplier<Path> configHomeSupplier;
    private final Supplier<String> fileReader;     // path → content (注入, 默认 Files.readString)
    private final Consumer<Void> settingsCacheResetter;  // 触发 merged cache 重建

    private volatile Map<String, Object> sessionCache;
    private volatile Optional<Boolean> eligible = Optional.empty();  // empty = undefined (未决)

    public RemoteManagedSettingsSyncCache(Supplier<Path> configHomeSupplier,
                                          Supplier<String> fileReader,
                                          Consumer<Void> settingsCacheResetter) {
        this.configHomeSupplier = Objects.requireNonNull(configHomeSupplier, "configHomeSupplier");
        this.fileReader = Objects.requireNonNull(fileReader, "fileReader");
        this.settingsCacheResetter = Objects.requireNonNull(settingsCacheResetter, "settingsCacheResetter");
    }

    /** CC setSessionCache — 直接注入缓存 (async-fetch 链路使用). */
    public void setSessionCache(Map<String, Object> value) {
        this.sessionCache = value;
    }

    /** CC resetSyncCache — 清 sessionCache + 重置 eligibility 到 undefined. */
    public void resetSyncCache() {
        this.sessionCache = null;
        this.eligible = Optional.empty();
    }

    /** CC setEligibility — 设置 eligibility 并返回新值. */
    public boolean setEligibility(boolean v) {
        this.eligible = Optional.of(v);
        return v;
    }

    /** CC getSettingsPath — 拼接 ${claudeConfigHome}/remote-settings.json. */
    public Path getSettingsPath() {
        return configHomeSupplier.get().resolve(SETTINGS_FILENAME);
    }

    /**
     * CC getRemoteManagedSettingsSyncFromCache — 主链 getter.
     * 返回 null 表示 ineligible 或缓存/磁盘中均无 settings.
     * 首次从磁盘成功加载时触发 settingsCacheResetter (gh-23085 修复).
     */
    public Map<String, Object> getRemoteManagedSettingsSyncFromCache() {
        // eligibility 必须是 true, 否则一律 null (undefined/false 都不读)
        if (eligible.filter(Boolean.TRUE::equals).isEmpty()) {
            return null;
        }
        if (sessionCache != null) {
            return sessionCache;
        }
        Map<String, Object> loaded = loadSettingsFromDisk();
        if (loaded != null) {
            sessionCache = loaded;
            // 首次成功加载触发 merged cache 重建 — 让下一次 getSettings_DEPRECATED()
            // 能看到 policySettings 层 (此 getter 在 eligible=true 之前一直返回 null)
            settingsCacheResetter.accept(null);
            log.info("[RemoteManagedSettingsSyncCache] first disk load; merged settings cache reset");
            return loaded;
        }
        return null;
    }

    private Map<String, Object> loadSettingsFromDisk() {
        try {
            // fileReader 抛 NoSuchFile / IOException → 被外层 catch 转 null (与 CC 一致)
            String content = stripBom(fileReader.get());
            Object parsed = JsonLooseParse.parseObject(content);
            if (!(parsed instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (Exception e) {
            log.warn("[RemoteManagedSettingsSyncCache] disk load failed: {}", e.getMessage());
            return null;
        }
    }

    /** 剥离 UTF-8 BOM (CC stripBOM 等价). */
    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '﻿') {
            return s.substring(1);
        }
        return s;
    }
}
