package com.nexusai.infra.util;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PluginOnlyPolicy · 对齐 CC utils/settings/pluginOnlyPolicy.ts.
 *
 * <p>L1 语义: 检查 customization surface 是否被 managed {@code strictPluginOnlyCustomization} 锁定。
 * 锁定后仅 admin-trusted sources (plugin/policySettings/built-in/bundled) 可加载。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 (isRestrictedToPluginOnly + isSourceAdminTrusted) + 5-element ADMIN_TRUSTED set</li>
 *   <li><b>A2 Golden Trace</b>: policy=true→所有 surface restricted;array=[surface]→该 surface restricted;policy=undefined→not restricted;plugin source→admin trusted</li>
 *   <li><b>A3 纯函数</b>: 依赖 settingsSupplier 注入 (testable)</li>
 *   <li><b>A4 边界</b>: null supplier→false;null source→false;empty policy→false</li>
 *   <li><b>A5 业务场景</b>: managed policy=strictPluginOnlyCustomization=true → user/project customization 锁住;plugin/built-in 仍加载</li>
 * </ul>
 *
 * <p>L3 升级: TS Map indexed → Java Map.get key;
 * TS ReadonlySet → Java Set.of immutable;
 * TS typeof undefined → Java Objects.requireNonNullElse.
 */
public final class PluginOnlyPolicy {

    // [S5-7 决策] 对齐 CC CUSTOMIZATION_SURFACES (Open-ClaudeCode/src/utils/settings/types.ts:248-253
    //   = ['skills','agents','hooks','mcp']). 旧常量 slashCommands/subAgents/settings 与 CC 不一致
    //   (CC 无这些 surface), 删除不留兼容壳. AgentMcpServers 权限闸用 SURFACE_MCP.
    public static final String SURFACE_SKILLS = "skills";
    public static final String SURFACE_AGENTS = "agents";
    public static final String SURFACE_HOOKS = "hooks";
    public static final String SURFACE_MCP = "mcp";

    public static final Set<String> ADMIN_TRUSTED_SOURCES = Set.of(
        "plugin", "policySettings", "built-in", "builtin", "bundled");

    private static final List<String> ALL_SURFACES = List.of(
        SURFACE_SKILLS, SURFACE_AGENTS, SURFACE_HOOKS, SURFACE_MCP);

    private PluginOnlyPolicy() {}

    /**
     * Returns true iff the customization surface is locked to plugin-only.
     * Mirrors CC: true → lock all; array → check membership; absent → not locked.
     *
     * @param surface        the surface to check
     * @param settingsSupplier returns the policy settings; null treated as absent
     */
    public static boolean isRestrictedToPluginOnly(
        String surface,
        Supplier<Map<String, Object>> settingsSupplier) {
        if (settingsSupplier == null) return false;
        Map<String, Object> settings = settingsSupplier.get();
        if (settings == null) return false;
        Object policy = settings.get("strictPluginOnlyCustomization");
        if (Boolean.TRUE.equals(policy)) return true;
        if (policy instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) policy;
            return list.contains(surface);
        }
        return false;
    }

    /**
     * Returns true iff the customization's source is admin-trusted
     * (plugin / policySettings / built-in / builtin / bundled).
     */
    public static boolean isSourceAdminTrusted(String source) {
        return source != null && ADMIN_TRUSTED_SOURCES.contains(source);
    }

    /**
     * Convenience: returns true iff a customization should load given
     * its surface and source. Pattern: {@code !restricted || isAdminTrusted}.
     */
    public static boolean shouldLoadCustomization(
        String surface,
        String source,
        Supplier<Map<String, Object>> settingsSupplier) {
        return !isRestrictedToPluginOnly(surface, settingsSupplier)
            || isSourceAdminTrusted(source);
    }
}
