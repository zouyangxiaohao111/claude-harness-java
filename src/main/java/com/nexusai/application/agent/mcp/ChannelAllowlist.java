package com.nexusai.application.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP channel plugin allowlist · 对齐 CC services/mcp/channelAllowlist.ts（76L）。
 *
 * <p>L1 语义: 纯静态工具类 —— 实例方法面（白名单列表/比对/总开关，原 @Component +
 * ChannelAllowlistService/McpProperties 注入）已按 D-B10-05 删除：DB 真源 =
 * {@code domain/mcp_channel_allowlist/ChannelAllowlistService}
 * （listAll:35/isAllowed:85 承担 CC channelAllowlist.ts:37-76 语义，DIM-10）。
 *
 * <p>[impl-I-3 T3 / R1] {@link #parsePluginIdentifier(String)} 对齐 CC
 * utils/plugins/pluginIdentifier.ts:51-57 —— <b>只按首个 {@code @} 切、不剥 {@code plugin:} 前缀</b>
 * （剥前缀属 {@link #parseChannelEntries(List)}，main.tsx:1650-1675 的 --channels 标签解析）。
 * 真实 pluginSource = {@code name@marketplace}（CC mcpPluginIntegration.ts:341 addPluginScopeToServers），无前缀。
 *
 * <p>ChannelEntry（CC original: bootstrap/state.ts:37-39 单一联合类型
 * {@code {kind:'plugin',name,marketplace,dev?} | {kind:'server',name,dev?}}）：
 * 原双轨（本类 3 字段 + ChannelNotificationGate 内嵌 4 字段）已按 D-B10-07 合并为
 * 本 record（声明序 kind,name,marketplace,dev，CC 顺序）；parseChannelEntries 构造
 * dev=null（CC 无 dev → undefined，interactiveHelpers.tsx:267-270/279-282 按条目注入）。
 */
public final class ChannelAllowlist {

    private static final Logger log = LoggerFactory.getLogger(ChannelAllowlist.class);

    private ChannelAllowlist() {
        // 纯静态工具类
    }

    /** CC parsePluginIdentifier 产物 · ParsedPluginIdentifier（pluginIdentifier.ts:37-40）。 */
    public record PluginIdentifier(String name, String marketplace) {}

    /**
     * 解析插件标识符 · CC original: {@code parsePluginIdentifier}
     * （utils/plugins/pluginIdentifier.ts:51-57）。
     *
     * <p>CC 行为：{@code plugin.includes('@')} → {@code const parts = plugin.split('@')} →
     * {@code {name: parts[0] || '', marketplace: parts[1]}} —— <b>split 全部 {@code @}</b>、
     * 只取 parts[0]/parts[1]（第二个 {@code @} 后忽略）、<b>不剥 {@code plugin:} 前缀</b>。
     * {@code 'slack@anthropic'} → {name='slack', marketplace='anthropic'}；
     * {@code 'plugin:slack@anthropic'} → {name='plugin:slack', marketplace='anthropic'}；
     * {@code 'plugin@market@place'} → {name='plugin', marketplace='market'}；
     * bare {@code 'slack'} → {name='slack', marketplace=null}。
     */
    public static PluginIdentifier parsePluginIdentifier(String plugin) {
        if (plugin == null || plugin.isEmpty()) {
            return new PluginIdentifier(null, null);
        }
        if (plugin.contains("@")) {
            // split("@", -1) 保留尾部空串（对齐 JS split 默认保留），如 'slack@' → ['slack','']
            String[] parts = plugin.split("@", -1);
            return new PluginIdentifier(parts[0], parts[1]);
        }
        return new PluginIdentifier(plugin, null);
    }

    /** --channels 标签条目 · CC original: ChannelEntry（bootstrap/state.ts:37-39，单一 4 字段 record）。 */
    public record ChannelEntry(String kind, String name, String marketplace, Boolean dev) {}

    /**
     * 解析 --channels 标签 · CC original: {@code parseChannelEntries}（main.tsx:1650-1680）。
     *
     * <p>{@code plugin:name@marketplace} → {kind='plugin', name, marketplace, dev=null}
     * （剥 7 字符前缀）；{@code server:name} → {kind='server', name, dev=null}（剥 7 字符前缀）；
     * 非法标签 → 丢弃并告警（CC 中 {@code process.exit(1)}；Java web 无 CLI 退出语义，log.warn + skip）。
     *
     * <p><b>与 {@link #parsePluginIdentifier} 职责不同</b>：本方法解析会话 --channels 标签
     * （用户声明的信任声明），parsePluginIdentifier 解析插件真实来源标识 —— 实施者不得混用。
     */
    public static List<ChannelEntry> parseChannelEntries(List<String> raw) {
        List<ChannelEntry> entries = new ArrayList<>();
        if (raw == null) return entries;
        for (String c : raw) {
            if (c == null) continue;
            if (c.startsWith("plugin:")) {
                String rest = c.substring(7);
                int at = rest.indexOf('@');
                if (at <= 0 || at == rest.length() - 1) {
                    log.warn("[ChannelAllowlist] 非法 --channels 标签（plugin-kind 缺 @marketplace），丢弃: {}", c);
                } else {
                    entries.add(new ChannelEntry("plugin", rest.substring(0, at), rest.substring(at + 1), null));
                }
            } else if (c.startsWith("server:") && c.length() > 7) {
                entries.add(new ChannelEntry("server", c.substring(7), null, null));
            } else {
                log.warn("[ChannelAllowlist] 非法 --channels 标签（须 plugin:name@marketplace / server:name），丢弃: {}", c);
            }
        }
        return entries;
    }
}
