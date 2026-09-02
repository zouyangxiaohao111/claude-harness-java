package com.nexusai.application.agent.plugin;

/**
 * Plugin identifier 解析 · 对齐 CC {@code utils/plugins/pluginIdentifier.ts:51-57}
 * {@code parsePluginIdentifier}（name 或 name@marketplace）。
 *
 * <p>不变量（CC 注释 :47-49）：仅首个 {@code @} 作为分隔符；多 {@code @} 取首（第二个 @ 之后的
 * 内容被忽略，marketplace 名不允许含 @）；无 {@code @} → marketplace 为 null。
 */
public final class PluginIdentifier {

    private PluginIdentifier() {
    }

    /** 解析结果 · CC {@code ParsedPluginIdentifier}（pluginIdentifier.ts:37-40）。 */
    public record Parsed(String name, String marketplace) {
    }

    /**
     * 解析插件标识 · CC {@code parsePluginIdentifier}（pluginIdentifier.ts:51-57）。
     *
     * <ul>
     *   <li>{@code "plugin@market"} → {@code {name:"plugin", marketplace:"market"}}</li>
     *   <li>{@code "plugin@market@place"} → {@code {name:"plugin", marketplace:"market"}}（多 @ 取首）</li>
     *   <li>{@code "plugin@"} → {@code {name:"plugin", marketplace:""}}（@ 后空串）</li>
     *   <li>{@code "plugin"} → {@code {name:"plugin", marketplace:null}}（无 @）</li>
     *   <li>{@code "@market"} → {@code {name:"", marketplace:"market"}}</li>
     * </ul>
     */
    public static Parsed parse(String plugin) {
        if (plugin == null) {
            return new Parsed("", null);
        }
        if (plugin.contains("@")) {
            // JS plugin.split('@') → parts[0] 是首个 @ 前、parts[1] 是首个与第二个 @ 之间；
            // split("@", -1) 保留尾部空串以对齐 JS split('@') 行为（"a@" → ["a", ""]）。
            String[] parts = plugin.split("@", -1);
            return new Parsed(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return new Parsed(plugin, null);
    }
}
