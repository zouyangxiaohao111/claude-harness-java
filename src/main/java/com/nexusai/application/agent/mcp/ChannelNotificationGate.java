package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.mcp.config.McpProperties;
import com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Channel notification gate (KAIROS/KAIROS_CHANNELS) · 对齐 CC services/mcp/channelNotification.ts。
 *
 * <p>CC source: services/mcp/channelNotification.ts (316 LOC)。
 * 4 main exports: gateChannelServer, findChannelEntry, getEffectiveChannelAllowlist,
 * wrapChannelMessage + 2 schemas。
 *
 * <p>[impl-I-3 T1 / OPD-MCP-04] <b>自有门控（去 claude.ai OAuth 依赖）</b>：
 * 删除 auth（OAuth）门（channelNotification.ts L222-228 注释：auth 是产品策略非协议要求，
 * console parity 后可去）与 org policy 门（L235-245：managed 分支，Java 无 team/enterprise 订阅，
 * 恒非 managed）。门序 = capability（L200-206）→ runtime channelsEnabled（L211-217）→
 * session --channels（L250-257）→ marketplace（L259-276，fail-closed）→ allowlist（L278-313）。
 * {@code getEffectiveChannelAllowlist} 恒走 ledger 分支（Java 无 org 订阅，channelNotification.ts:127-138）。
 */
@Component
public class ChannelNotificationGate {

    private static final Logger log = LoggerFactory.getLogger(ChannelNotificationGate.class);
    public static final String CHANNEL_PERMISSION_METHOD = "notifications/claude/channel/permission";
    public static final String CHANNEL_PERMISSION_REQUEST_METHOD = "notifications/claude/channel/permission_request";

    /** Gate 跳过原因 · CC ChannelGateResult kind（channelNotification.ts:142-153；AUTH/POLICY 已按 OPD-MCP-04 删除）。 */
    public enum GateKind { CAPABILITY, DISABLED, SESSION, MARKETPLACE, ALLOWLIST }

    public record ChannelGateResult(String action, GateKind kind, String reason) {}

    /** --channels 会话条目 · CC original: ChannelEntry（main.tsx:1650-1675 解析产物，
     *  bootstrap/state.ts:37-39 单一联合类型）→ 统一由 {@link ChannelAllowlist.ChannelEntry} 承担
     *  （D-B10-07 双轨合并，同包直接引用）。 */

    public record PermissionsParams(
        String content,
        Map<String, String> meta
    ) {}

    public record PermissionsContent(
        String requestId,
        String behavior
    ) {}

    public record ChannelPermissionRequestParams(
        String requestId,
        String toolName,
        String description,
        String inputPreview
    ) {}

    public record ServerCapabilities(Map<String, Object> experimental) {}

    public record AllowlistSource(List<ChannelAllowlistEntry> entries, String source) {}

    private static final java.util.regex.Pattern SAFE_META_KEY =
        java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private final Supplier<Boolean> channelsEnabledSupplier;
    private Supplier<List<ChannelAllowlist.ChannelEntry>> allowedChannelsSupplier;
    private final Supplier<List<ChannelAllowlistEntry>> ledgerAllowlistSupplier;
    private final Function<String, String> escapeXmlAttrFn;

    /**
     * 注入式构造（测试用）· 全部依赖经函数接口注入，便于单测覆盖门序各段。
     *
     * @param channelsEnabledSupplier  runtime gate · CC original: isChannelsEnabled()
     *                                 （channelAllowlist.ts:51-53，tengu_harbor）→ Java McpProperties.channelsEnabled
     * @param allowedChannelsSupplier  session --channels 白名单 · CC getAllowedChannels()（channelNotification.ts:250）
     * @param ledgerAllowlistSupplier  approved 白名单 · CC getChannelAllowlist()（channelAllowlist.ts:37-44）→ DB 真源
     * @param escapeXmlAttrFn          XML 属性转义 · CC escapeXmlAttr（utils/xml.ts:14-16）
     */
    public ChannelNotificationGate(
        Supplier<Boolean> channelsEnabledSupplier,
        Supplier<List<ChannelAllowlist.ChannelEntry>> allowedChannelsSupplier,
        Supplier<List<ChannelAllowlistEntry>> ledgerAllowlistSupplier,
        Function<String, String> escapeXmlAttrFn
    ) {
        this.channelsEnabledSupplier = channelsEnabledSupplier == null ? () -> false : channelsEnabledSupplier;
        this.allowedChannelsSupplier = allowedChannelsSupplier == null ? List::of : allowedChannelsSupplier;
        this.ledgerAllowlistSupplier = ledgerAllowlistSupplier == null ? List::of : ledgerAllowlistSupplier;
        this.escapeXmlAttrFn = escapeXmlAttrFn == null ? ChannelNotificationGate::escapeXmlAttr : escapeXmlAttrFn;
    }

    /**
     * Spring 构造 · 装配自有门控（OPD-MCP-04）：channelsEnabled 读 Java 配置、
     * ledger 白名单走 DB 表（ChannelAllowlistService）、session --channels 默认空
     * （Java 无 CLI 会话态，可经 {@link #setAllowedChannelsSupplier} 注入）。
     */
    @Autowired
    public ChannelNotificationGate(McpProperties mcpProperties, ChannelAllowlistService ledgerService) {
        this(
            mcpProperties != null ? mcpProperties::channelsEnabled : () -> false,
            List::of,
            ledgerService != null ? ledgerService::listAll : List::of,
            ChannelNotificationGate::escapeXmlAttr);
    }

    /** 注入 session --channels 白名单 supplier（生产经 ChannelSessionAllowlist.currentRequestSupplier()，测试直注入）。 */
    public void setAllowedChannelsSupplier(Supplier<List<ChannelAllowlist.ChannelEntry>> supplier) {
        if (log.isDebugEnabled()) {
            log.debug("[ChannelNotificationGate] 注入 session --channels 白名单 supplier: {}",
                supplier != null ? "真实会话态(ChannelSessionAllowlist)" : "null(保持现态)");
        }
        if (supplier != null) {
            this.allowedChannelsSupplier = supplier;
        }
    }

    /** CC escapeXmlAttr（utils/xml.ts:14-16）：escapeXml(& < >) + 引号（" → &quot;，' → &apos;）。 */
    public static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * CC wrapChannelMessage（channelNotification.ts:106-116）：{@code <channel source="..."{attrs}>\n{content}\n</channel>}。
     * SAFE_META_KEY 过滤 key（L104），value 经 escapeXmlAttr 转义。
     */
    public String wrapChannelMessage(String serverName, String content, Map<String, String> meta) {
        StringBuilder attrs = new StringBuilder();
        if (meta != null) {
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (SAFE_META_KEY.matcher(e.getKey()).matches()) {
                    attrs.append(" ").append(e.getKey()).append("=\"")
                         .append(escapeXmlAttrFn.apply(e.getValue())).append("\"");
                }
            }
        }
        return "<channel source=\"" + escapeXmlAttrFn.apply(serverName) + "\"" + attrs + ">\n"
            + content + "\n</channel>";
    }

    /**
     * CC getEffectiveChannelAllowlist（channelNotification.ts:127-138）·
     * org-vs-ledger 二选一，Java 无 team/enterprise 订阅 → <b>恒走 ledger 分支</b>。
     */
    public AllowlistSource getEffectiveChannelAllowlist() {
        return new AllowlistSource(ledgerAllowlistSupplier.get(), "ledger");
    }

    /** CC findChannelEntry（channelNotification.ts:161-173）· server-kind 精确名 / plugin-kind 按 plugin:X:Y 第二段。 */
    public ChannelAllowlist.ChannelEntry findChannelEntry(String serverName, List<ChannelAllowlist.ChannelEntry> channels) {
        if (serverName == null || channels == null) return null;
        String[] parts = serverName.split(":");
        for (ChannelAllowlist.ChannelEntry c : channels) {
            if ("server".equals(c.kind())) {
                if (serverName.equals(c.name())) return c;
            } else if (parts.length > 1 && "plugin".equals(parts[0]) && parts[1].equals(c.name())) {
                return c;
            }
        }
        return null;
    }

    /**
     * CC gateChannelServer（channelNotification.ts:191-316）· 自有门序（OPD-MCP-04）：
     * <ol>
     *   <li><b>capability</b>（L200-206）：{@code experimental['claude/channel']} truthy（{} 或 true）；
     *       缺失/undefined/显式 false 全失败</li>
     *   <li><b>runtime</b>（L211-217）：channelsEnabled（Java 配置，CC tengu_harbor）</li>
     *   <li><b>session</b>（L250-257）：server 必须在 --channels 白名单</li>
     *   <li><b>marketplace</b>（L259-276）：plugin-kind entry 校验安装来源 == 标签（pluginSource 无/错 → fail-closed）</li>
     *   <li><b>allowlist</b>（L278-313）：approved 白名单比对（DB ledger）；server-kind entry 恒 skip（schema 仅 plugin）</li>
     * </ol>
     * skip → 连接保持、handler 不注册（L183-186）。
     */
    public ChannelGateResult gateChannelServer(String serverName,
                                             ServerCapabilities capabilities,
                                             String pluginSource) {
        // 1. capability（channelNotification.ts:200-206）
        Object exp = capabilities != null ? capabilities.experimental() : null;
        Map<?, ?> expMap = (exp instanceof Map<?, ?>) ? (Map<?, ?>) exp : null;
        Object has = expMap != null ? expMap.get("claude/channel") : null;
        if (has == null || Boolean.FALSE.equals(has)) {
            log.info("[ChannelNotificationGate] 门序[1 capability] 跳过 server={}: 未声明 claude/channel 能力",
                serverName);
            return new ChannelGateResult("skip", GateKind.CAPABILITY,
                "server did not declare claude/channel capability");
        }
        // 2. runtime gate（L211-217）
        if (!Boolean.TRUE.equals(channelsEnabledSupplier.get())) {
            log.info("[ChannelNotificationGate] 门序[2 runtime] 跳过 server={}: channels 功能未启用（channels-enabled=false）",
                serverName);
            return new ChannelGateResult("skip", GateKind.DISABLED,
                "channels feature is not currently available");
        }
        // 3. session --channels（L250-257）
        ChannelAllowlist.ChannelEntry entry = findChannelEntry(serverName, allowedChannelsSupplier.get());
        if (entry == null) {
            log.info("[ChannelNotificationGate] 门序[3 session] 跳过 server={}: 不在本次会话 --channels 白名单中",
                serverName);
            return new ChannelGateResult("skip", GateKind.SESSION,
                "server " + serverName + " not in --channels list for this session");
        }
        // 4. marketplace 校验（L259-276）· R1: pluginSource ? parsePluginIdentifier(pluginSource).marketplace : undefined
        if ("plugin".equals(entry.kind())) {
            String actualMarketplace = pluginSource != null
                ? ChannelAllowlist.parsePluginIdentifier(pluginSource).marketplace()
                : null;
            if (actualMarketplace == null || !actualMarketplace.equals(entry.marketplace())) {
                log.warn("[ChannelNotificationGate] 门序[4 marketplace] 跳过 server={}: 标签声明 plugin:{}@{}，"
                        + "实际安装来源为 {}",
                    serverName, entry.name(), entry.marketplace(),
                    actualMarketplace != null ? actualMarketplace : "未知来源");
                return new ChannelGateResult("skip", GateKind.MARKETPLACE,
                    "you asked for plugin:" + entry.name() + "@" + entry.marketplace()
                        + " but the installed " + entry.name() + " plugin is from "
                        + (actualMarketplace != null ? actualMarketplace : "an unknown source"));
            }
            // 5. approved allowlist（L278-301）· entry.dev 豁免（per-entry，非会话级）
            if (!Boolean.TRUE.equals(entry.dev())) {
                AllowlistSource src = getEffectiveChannelAllowlist();
                boolean allowed = src.entries().stream()
                    .anyMatch(e -> entry.name().equals(e.plugin()) && entry.marketplace().equals(e.marketplace()));
                if (!allowed) {
                    log.info("[ChannelNotificationGate] 门序[5 allowlist] 跳过 server={}: 插件 {}@{} 不在已批准 channel 白名单",
                        serverName, entry.name(), entry.marketplace());
                    return new ChannelGateResult("skip", GateKind.ALLOWLIST,
                        "plugin " + entry.name() + "@" + entry.marketplace()
                            + " is not on the approved channels allowlist (use --dangerously-load-development-channels for local dev)");
                }
            }
        } else {
            // server-kind：allowlist schema 是 {marketplace, plugin}，server entry 恒不匹配（L302-313）
            if (!Boolean.TRUE.equals(entry.dev())) {
                log.info("[ChannelNotificationGate] 门序[5 allowlist] 跳过 server={}: server-kind entry 恒不匹配 {marketplace,plugin} schema",
                    serverName);
                return new ChannelGateResult("skip", GateKind.ALLOWLIST,
                    "server " + entry.name() + " is not on the approved channels allowlist (use --dangerously-load-development-channels for local dev)");
            }
        }
        log.info("[ChannelNotificationGate] 门序全过 → register server={} kind={} entry={}",
            serverName, entry.kind(), entry.name());
        return new ChannelGateResult("register", null, null);
    }
}
