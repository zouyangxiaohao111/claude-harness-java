package com.nexusai.application.agent.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Settings migration 框架 + replBridgeEnabled→remoteControlAtStartup 迁移 · 对齐
 * CC migrations/migrateReplBridgeEnabledToRemoteControlAtStartup.ts.
 *
 * <p>L1 语义: 旧键 `replBridgeEnabled` 是泄漏到 user-facing config 的实现细节.
 *            迁移: 复制值到新键 `remoteControlAtStartup` (Boolean 强转) + 删除旧键.
 *            幂等: 仅当旧键存在 AND 新键未设置时才执行.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `migrate(Map) → Map` 签名 (UnaryOperator&lt;Map&gt; 包装 config saveGlobalConfig 模式)</li>
 *   <li><b>A2 Golden Trace</b>: {replBridgeEnabled:true} → {remoteControlAtStartup:true} (无 replBridgeEnabled)</li>
 *   <li><b>A3</b>: 幂等 — 二次 migrate 无变化; 旧键不存在 → 不变; 新键已设置 → 不覆盖</li>
 *   <li><b>A4</b>: 旧值非 boolean (字符串/数字) → Boolean 强制转换 (truthy/falsy); null 旧值 → 不迁移</li>
 *   <li><b>A5</b>: 真实场景 — {replBridgeEnabled:"yes", otherKey:"keep"} → {remoteControlAtStartup:true, otherKey:"keep"}</li>
 * </ul>
 *
 * <p>L3 (Java idiom): UnaryOperator&lt;Map&gt; 替代 CC saveGlobalConfig(prev =&gt; ...);
 *                    LinkedHashMap 保序 (CC object spread 顺序); Map.copyOf 不可变返回.
 */
public final class SettingMigrations {

    /** CC migrateReplBridgeEnabledToRemoteControlAtStartup.ts — 旧键 → 新键. */
    public static final String OLD_KEY = "replBridgeEnabled";
    public static final String NEW_KEY = "remoteControlAtStartup";

    private SettingMigrations() {}

    /**
     * 迁移: replBridgeEnabled → remoteControlAtStartup.
     *
     * @param config 当前 settings Map (允许任何内容)
     * @return 迁移后 Map (不可变); 未触发迁移时返回原 Map 引用
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> migrateReplBridgeEnabledToRemoteControlAtStartup(Map<String, Object> config) {
        if (config == null) return Map.of();
        if (!config.containsKey(OLD_KEY)) return config;     // 旧键不存在 → 不变
        if (config.containsKey(NEW_KEY)) return config;     // 新键已设置 → 不覆盖

        Object oldValue = config.get(OLD_KEY);
        Map<String, Object> next = new LinkedHashMap<>(config);
        next.put(NEW_KEY, toBoolean(oldValue));
        next.remove(OLD_KEY);
        return Map.copyOf(next);
    }

    /**
     * 通用迁移应用器 — 把 UnaryOperator 包装成 record-prev → record-next 形式,
     * 对齐 CC saveGlobalConfig(c =&gt; ...) 闭包.
     */
    public static Map<String, Object> applyMigration(Map<String, Object> config, UnaryOperator<Map<String, Object>> migration) {
        if (migration == null) return config;
        return migration.apply(config);
    }

    /** 工具: 把任意值强制转 boolean (CC `Boolean(oldValue)`). */
    public static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equalsIgnoreCase(s);
        if (value instanceof Number n) return n.doubleValue() != 0;
        return true;  // 任何对象存在即 truthy
    }
}