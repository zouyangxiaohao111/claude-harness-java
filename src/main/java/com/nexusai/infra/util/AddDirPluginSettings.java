package com.nexusai.infra.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AddDirPluginSettings · 对齐 CC utils/plugins/addDirPluginSettings.ts.
 *
 * <p>L1 语义: 读取 --add-dir 目录中的 plugin settings (enabledPlugins + extraKnownMarketplaces),
 * lowest priority (callers must spread standard settings on top)。
 * <ul>
 *   <li>{@code getAddDirEnabledPlugins(envReader)} → Map</li>
 *   <li>{@code getAddDirExtraMarketplaces(envReader)} → Map</li>
 * </ul>
 *
 * <p>CC 注释: settings.local.json 在 settings.json 之后;CLI-order 后者覆盖前者;lowest priority。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + FileReader 注入式 (read JSON)</li>
 *   <li><b>A2 Golden Trace</b>: missing directory→empty;settings.json + settings.local.json merge (local wins);null enabledPlugins→empty map</li>
 *   <li><b>A3 副作用</b>: 注入式 fileReader</li>
 *   <li><b>A4 边界</b>: null env→empty;missing file→continue;null settings→empty</li>
 *   <li><b>A5 业务场景</b>: 插件 manager 读 --add-dir 目录的 settings → low priority base</li>
 * </ul>
 *
 * <p>L3 升级: TS file fs → Java Supplier 注入式 (read JSON as Map);
 * TS object spread → Java LinkedHashMap.putAll.
 */
public final class AddDirPluginSettings {

    public static final String[] SETTINGS_FILES = { "settings.json", "settings.local.json" };

    private AddDirPluginSettings() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getAddDirEnabledPlugins(
        Supplier<java.util.List<Map<String, Object>>> settingsListSupplier) {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> list =
            settingsListSupplier == null ? null : settingsListSupplier.get();
        if (list == null) return result;
        for (Map<String, Object> settings : list) {
            if (settings == null) continue;
            Object ep = settings.get("enabledPlugins");
            if (ep instanceof Map) {
                result.putAll((Map<String, Object>) ep);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getAddDirExtraMarketplaces(
        Supplier<java.util.List<Map<String, Object>>> settingsListSupplier) {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> list =
            settingsListSupplier == null ? null : settingsListSupplier.get();
        if (list == null) return result;
        for (Map<String, Object> settings : list) {
            if (settings == null) continue;
            Object em = settings.get("extraKnownMarketplaces");
            if (em instanceof Map) {
                result.putAll((Map<String, Object>) em);
            }
        }
        return result;
    }
}
