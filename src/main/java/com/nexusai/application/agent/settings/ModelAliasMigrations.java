package com.nexusai.application.agent.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Model alias 重写 migrations · 对齐 CC migrations/ 子集.
 *
 * <p>L1 语义: 把已移除/过期的 model alias 重写到新 alias. 仅 userSettings.model 字段, 不触其他 scope
 *            (project/local/policy) — CC 注释明示"我们不能重写那些, 读 merged settings 会导致无限重跑".
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 个 static migrate 方法 (fennec/opus/sonnet), 返回 Map 不可变</li>
 *   <li><b>A2 Golden Trace</b>: fennec-latest → opus; opus → opus[1m] (条件); sonnet[1m] → sonnet-4-5-20250929[1m]</li>
 *   <li><b>A3</b>: 幂等 — 二次 migrate 无变化; 非 userSettings scope 不动 (model == null 或非 string 不变)</li>
 *   <li><b>A4</b>: 模型字符串以 fastMode 标志 (fennec-fast-latest → opus[1m] + fastMode=true)</li>
 *   <li><b>A5</b>: 真实 settings 样本 — 完整 Map 包含 model + 其他键, 迁移后其他键保留</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 纯 Map→Map 函数; LinkedHashMap 保序; Map.copyOf 不可变.
 */
public final class ModelAliasMigrations {

    private ModelAliasMigrations() {}

    /** userSettings source key (CC settings.ts 路径约定). */
    public static final String USER_SETTINGS_SOURCE = "userSettings";

    /** CC migrateOpusToOpus1m.ts:34 — opus → opus[1m] 默认迁移目标. */
    public static final String OPUS_1M = "opus[1m]";

    /** CC migrateSonnet1mToSonnet45.ts:34 — sonnet[1m] → sonnet-4-5-20250929[1m]. */
    public static final String SONNET_45_1M = "sonnet-4-5-20250929[1m]";

    /** CC migrateFennecToOpus.ts — fennec-fast-latest 同时开启 fastMode. */
    public static final String FAST_MODE_KEY = "fastMode";

    /**
     * 迁移 fennec → opus alias (CC migrateFennecToOpus.ts:18-45).
     *
     * <p>仅触 userSettings.model 字段. 3 类重写:
     * <ul>
     *   <li>fennec-latest[1m] → opus[1m]</li>
     *   <li>fennec-latest → opus</li>
     *   <li>fennec-fast-latest / opus-4-5-fast → opus[1m] + fastMode=true</li>
     * </ul>
     *
     * @return 迁移后 userSettings (不可变); 无需迁移时返回原 Map 引用
     */
    public static Map<String, Object> migrateFennecToOpus(Map<String, Object> userSettings) {
        if (userSettings == null) return Map.of();
        Object model = userSettings.get("model");
        if (!(model instanceof String s)) return userSettings;

        Map<String, Object> next = null;
        if (s.startsWith("fennec-latest[1m]")) {
            next = new LinkedHashMap<>(userSettings);
            next.put("model", OPUS_1M);
        } else if (s.startsWith("fennec-latest")) {
            next = new LinkedHashMap<>(userSettings);
            next.put("model", "opus");
        } else if (s.startsWith("fennec-fast-latest") || s.startsWith("opus-4-5-fast")) {
            next = new LinkedHashMap<>(userSettings);
            next.put("model", OPUS_1M);
            next.put(FAST_MODE_KEY, true);
        }
        return next == null ? userSettings : Map.copyOf(next);
    }

    /**
     * 迁移 opus → opus[1m] (CC migrateOpusToOpus1m.ts:24-43). 幂等 — model 非 'opus' 不变.
     *
     * @param userSettings 当前 userSettings
     * @param isOpus1mMergeEnabled  是否启用 opus[1m] 合并 (feature gate)
     * @param parseModel           把 alias 解析为内部 Model 对象的函数 (CC parseUserSpecifiedModel)
     * @param defaultMainLoop      默认 main loop model (CC getDefaultMainLoopModelSetting)
     * @return 迁移后 userSettings (不可变); 无需迁移时返回原 Map 引用
     */
    public static Map<String, Object> migrateOpusToOpus1m(
            Map<String, Object> userSettings,
            boolean isOpus1mMergeEnabled,
            java.util.function.Function<String, Object> parseModel,
            String defaultMainLoop) {
        if (!isOpus1mMergeEnabled || userSettings == null) return userSettings;
        Object model = userSettings.get("model");
        if (!"opus".equals(model)) return userSettings;

        // CC: parseUserSpecifiedModel(migrated) === parseUserSpecifiedModel(default) → undefined; else migrated
        Object parsedMigrated = parseModel.apply(OPUS_1M);
        Object parsedDefault = defaultMainLoop == null ? null : parseModel.apply(defaultMainLoop);
        Map<String, Object> next = new LinkedHashMap<>(userSettings);
        if (parsedMigrated.equals(parsedDefault)) {
            next.remove("model");  // CC undefined → 字段不存在
        } else {
            next.put("model", OPUS_1M);
        }
        return Map.copyOf(next);
    }

    /**
     * 迁移 sonnet[1m] → sonnet-4-5-20250929[1m] (CC migrateSonnet1mToSonnet45.ts:25-48).
     *
     * <p>幂等通过 {@code sonnet1m45MigrationComplete} flag 控制 — 调用方负责检查 flag.
     * 本方法不查 flag (纯迁移), 调用方需在迁移前检查 + 迁移后 setFlag.
     *
     * @return 迁移后 userSettings (不可变); 无需迁移时返回原 Map 引用
     */
    public static Map<String, Object> migrateSonnet1mToSonnet45(Map<String, Object> userSettings) {
        if (userSettings == null) return Map.of();
        Object model = userSettings.get("model");
        if (!"sonnet[1m]".equals(model)) return userSettings;
        Map<String, Object> next = new LinkedHashMap<>(userSettings);
        next.put("model", SONNET_45_1M);
        return Map.copyOf(next);
    }

    /** 工具: 应用 completion flag (CC sonnet1m45MigrationComplete). */
    public static Map<String, Object> setMigrationFlag(Map<String, Object> globalConfig, String flagKey) {
        Map<String, Object> next = new LinkedHashMap<>(globalConfig == null ? Map.of() : globalConfig);
        next.put(flagKey, true);
        return Map.copyOf(next);
    }

    /** 工具: 检查 completion flag. */
    public static boolean isMigrationComplete(Map<String, Object> globalConfig, String flagKey) {
        return globalConfig != null && Boolean.TRUE.equals(globalConfig.get(flagKey));
    }

    /** 工具: 列出所有支持的 model alias 集合 (测试用). */
    public static Set<String> allOldAliases() {
        return Set.of(
            "fennec-latest", "fennec-latest[1m]",
            "fennec-fast-latest", "opus-4-5-fast",
            "opus", "sonnet[1m]"
        );
    }

    /**
     * 迁移: legacy Opus 4.0/4.1 显式 model 字符串 → 'opus' alias (CC migrateLegacyOpusToCurrent.ts:29-57).
     *
     * <p>仅 1P 用户 + isLegacyModelRemapEnabled 启用时迁移. 4 个 legacy model 字符串:
     * <ul>
     *   <li>claude-opus-4-20250514</li>
     *   <li>claude-opus-4-1-20250805</li>
     *   <li>claude-opus-4-0</li>
     *   <li>claude-opus-4-1</li>
     * </ul>
     *
     * @return 迁移结果 (userSettings + globalConfig timestamp); 不满足条件返回原 Map
     */
    public record LegacyOpusResult(Map<String, Object> userSettings, Map<String, Object> globalConfig) {}

    public static LegacyOpusResult migrateLegacyOpusToCurrent(
            Map<String, Object> userSettings,
            Map<String, Object> globalConfig,
            boolean isFirstParty,
            boolean isLegacyModelRemapEnabled) {
        if (!isFirstParty || !isLegacyModelRemapEnabled) {
            return new LegacyOpusResult(userSettings, globalConfig);
        }
        Object model = userSettings == null ? null : userSettings.get("model");
        if (!isLegacyOpusModel(model)) {
            return new LegacyOpusResult(userSettings, globalConfig);
        }
        Map<String, Object> nextSettings = userSettings == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(userSettings);
        nextSettings.put("model", "opus");
        Map<String, Object> nextConfig = new LinkedHashMap<>(globalConfig == null ? Map.of() : globalConfig);
        nextConfig.put("legacyOpusMigrationTimestamp", System.currentTimeMillis());
        return new LegacyOpusResult(Map.copyOf(nextSettings), Map.copyOf(nextConfig));
    }

    private static boolean isLegacyOpusModel(Object model) {
        return "claude-opus-4-20250514".equals(model)
            || "claude-opus-4-1-20250805".equals(model)
            || "claude-opus-4-0".equals(model)
            || "claude-opus-4-1".equals(model);
    }

    /**
     * 迁移: Sonnet 4.5 显式字符串 → 'sonnet' / 'sonnet[1m]' alias (CC migrateSonnet45ToSonnet46.ts:29-67).
     *
     * <p>仅 Pro/Max/Team Premium 1P 用户. 4 个 Sonnet 4.5 model 字符串 (含 [1m] 后缀):
     * <ul>
     *   <li>claude-sonnet-4-5-20250929</li>
     *   <li>claude-sonnet-4-5-20250929[1m]</li>
     *   <li>sonnet-4-5-20250929</li>
     *   <li>sonnet-4-5-20250929[1m]</li>
     * </ul>
     */
    public record Sonnet45To46Result(Map<String, Object> userSettings, Map<String, Object> globalConfig) {}

    public static Sonnet45To46Result migrateSonnet45ToSonnet46(
            Map<String, Object> userSettings,
            Map<String, Object> globalConfig,
            boolean isFirstParty,
            boolean isSubscriber) {
        if (!isFirstParty || !isSubscriber) {
            return new Sonnet45To46Result(userSettings, globalConfig);
        }
        Object model = userSettings == null ? null : userSettings.get("model");
        if (!isSonnet45Model(model)) {
            return new Sonnet45To46Result(userSettings, globalConfig);
        }
        boolean has1m = model.toString().endsWith("[1m]");
        Map<String, Object> nextSettings = new LinkedHashMap<>(userSettings);
        nextSettings.put("model", has1m ? "sonnet[1m]" : "sonnet");

        Map<String, Object> nextConfig = globalConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(globalConfig);
        // CC: numStartups > 1 → set timestamp (新用户跳过)
        Number numStartups = (Number) nextConfig.getOrDefault("numStartups", 0);
        if (numStartups.longValue() > 1) {
            nextConfig.put("sonnet45To46MigrationTimestamp", System.currentTimeMillis());
        }
        return new Sonnet45To46Result(Map.copyOf(nextSettings), Map.copyOf(nextConfig));
    }

    private static boolean isSonnet45Model(Object model) {
        return "claude-sonnet-4-5-20250929".equals(model)
            || "claude-sonnet-4-5-20250929[1m]".equals(model)
            || "sonnet-4-5-20250929".equals(model)
            || "sonnet-4-5-20250929[1m]".equals(model);
    }

    /**
     * 迁移: Pro 1P 用户 → Opus 4.5 默认 (CC resetProToOpusDefault.ts:7-51).
     *
     * <p>gate: apiProvider=firstParty AND isProSubscriber=true. 仅在 settings.model 未设置时记录 timestamp.
     * 总是设 opusProMigrationComplete=true 标志 (幂等).
     */
    public static Map<String, Object> resetProToOpusDefault(
            Map<String, Object> globalConfig,
            Map<String, Object> userSettings,
            boolean isFirstParty,
            boolean isPro) {
        Map<String, Object> next = new LinkedHashMap<>(globalConfig == null ? Map.of() : globalConfig);
        if (!isFirstParty || !isPro) {
            next.put("opusProMigrationComplete", true);
            return Map.copyOf(next);
        }
        Object currentModel = userSettings == null ? null : userSettings.get("model");
        if (currentModel == null) {
            // 用户在默认 → 记录 timestamp 触发一次性通知
            next.put("opusProMigrationTimestamp", System.currentTimeMillis());
        }
        next.put("opusProMigrationComplete", true);
        return Map.copyOf(next);
    }

    /**
     * 迁移: globalConfig.autoUpdates=false → settings.env.DISABLE_AUTOUPDATER='1'
     * (CC migrateAutoUpdatesToSettings.ts:13-61).
     *
     * <p>仅迁移 user-explicit 关闭 (非 native autoUpdatesProtectedForNative=true).
     * 总是覆盖 env.DISABLE_AUTOUPDATER; 成功后从 globalConfig 删除 autoUpdates + autoUpdatesProtectedForNative.
     */
    public record AutoUpdatesResult(Map<String, Object> userSettings, Map<String, Object> globalConfig) {}

    public static AutoUpdatesResult migrateAutoUpdatesToSettings(
            Map<String, Object> globalConfig,
            Map<String, Object> userSettings) {
        if (globalConfig == null) {
            return new AutoUpdatesResult(userSettings, globalConfig);
        }
        Object autoUpdates = globalConfig.get("autoUpdates");
        Boolean protectedForNative = (Boolean) globalConfig.get("autoUpdatesProtectedForNative");
        // 仅迁移 user-explicit 关闭 (autoUpdates=false), 非 native 自动保护
        if (autoUpdates != Boolean.FALSE || Boolean.TRUE.equals(protectedForNative)) {
            return new AutoUpdatesResult(userSettings, globalConfig);
        }
        Map<String, Object> nextSettings = userSettings == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(userSettings);
        Map<String, Object> env = nextSettings.get("env") instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) nextSettings.get("env"))
            : new LinkedHashMap<>();
        env.put("DISABLE_AUTOUPDATER", "1");
        nextSettings.put("env", Map.copyOf(env));

        // 删除 autoUpdates + autoUpdatesProtectedForNative
        Map<String, Object> nextConfig = new LinkedHashMap<>(globalConfig);
        nextConfig.remove("autoUpdates");
        nextConfig.remove("autoUpdatesProtectedForNative");
        return new AutoUpdatesResult(Map.copyOf(nextSettings), Map.copyOf(nextConfig));
    }
}