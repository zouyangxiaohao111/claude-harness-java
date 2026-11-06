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

    /**
     * 门序结果 · CC ChannelGateResult（channelNotification.ts:142-153）。
     *
     * @param action        "register" | "skip"
     * @param kind          跳过原因（skip 时非 null）
     * @param reason        人类可读原因（CC 原文，skip 时非 null）
     * @param boundSessionId [V255 外部渠道] 归属会话 id —— 门序[3] 从绑定表查到的
     *                       「这个渠道归哪个会话」。<b>这是本仓相对 CC 的新增语义</b>：
     *                       CC 的消息靠 ambient {@code agentId: ci()} 进「当前 agent」队列，
     *                       本仓多会话必须显式携带（{@code NotificationQueue.drainForQuery}
     *                       的 [3a] 规则：具体会话的 turn 只捞 sessionId 匹配的命令 ⇒
     *                       归属写错 = 消息永远躺在队列里没人消费）。
     *                       register 时非 null（= 该渠道绑定的会话）；skip 时为 null。
     */
    public record ChannelGateResult(String action, GateKind kind, String reason, String boundSessionId) {
        /** 2-arg 兼容构造（skip 分支用；无归属会话）。 */
        public ChannelGateResult(String action, GateKind kind, String reason) {
            this(action, kind, reason, null);
        }
    }

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

    /**
     * [V254 外部渠道] channels_enabled 实时读源（DB settings 权威源）·
     * CC original: {@code isChannelsEnabled()}（channelAllowlist.ts:51-53，GrowthBook 'tengu_harbor'）。
     *
     * <p><b>为什么用 static 读源而不是构造注入 {@code SettingsService}</b>：本仓对象图深，
     * {@code ChannelNotificationGate} ← {@code McpToolPool} / {@code McpServerService} 都在
     * agent/mcp 域内，把 domain/settings 拉进来会扩大依赖面；static 读源是本仓既有范式
     * （同 {@code ProviderHeaderInjector.installGateSource} / {@code TaskSystemConfig.installAgentSwarmsSettingsSource}）。
     *
     * <p><b>不缓存</b>：前端「设置→高级」改完 → 下一条通道消息即生效（DB 实时读）。
     *
     * <p><b>返回 {@code null} = 「未配置 / 读不到」</b>（三态，不是布尔）：调用方据此回落 yml。
     * 必须能区分「DB 显式 false（用户关掉）」与「DB 没有这个值」—— 前者要尊重用户的显式关闭，
     * 后者才回落 yml 兜底。
     */
    private static volatile java.util.function.Supplier<Boolean> channelsEnabledDbSource;

    /**
     * 安装 channels_enabled 实时读源（{@code SettingsService.readDbChannelsEnabled} 的方法引用）。
     *
     * @param source 每次调用实时读 settings.channels_enabled，返回 null = 未配置/读不到；null = 卸载（仅测试用）
     */
    public static void installChannelsEnabledSource(java.util.function.Supplier<Boolean> source) {
        channelsEnabledDbSource = source;
    }

    /**
     * 读 channels_enabled 权威值（<b>null = 未配置 / 读不到</b>）。
     *
     * <p>语义：
     * <ul>
     *   <li>读源已安装 → 读源返回值（true / false / null）；</li>
     *   <li>读源未安装（POJO 单测 / 装配失败）→ <b>null</b> + warn 至多一次；</li>
     *   <li>读取抛异常 → <b>null</b> + warn（调用方回落 yml 兜底）。</li>
     * </ul>
     */
    private static Boolean dbChannelsEnabled() {
        java.util.function.Supplier<Boolean> s = channelsEnabledDbSource;
        if (s == null) {
            if (WARNED_SOURCE_MISSING.compareAndSet(false, true)) {
                log.warn("[ChannelNotificationGate] channels_enabled 实时读源未安装"
                    + "（无 Spring 上下文 / 装配失败）→ 回落 yml nexusai.mcp.channels-enabled");
            }
            return null;
        }
        try {
            return s.get();
        } catch (Exception e) {
            log.warn("[ChannelNotificationGate] 读取 channels_enabled 失败，回落 yml 兜底: {}", e.toString());
            return null;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_SOURCE_MISSING =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Supplier<Boolean> channelsEnabledSupplier;

    /**
     * [V255 外部渠道] server 名 → 归属会话 id 的解析器（null = 未注入 ⇒ 门序[3] 回落旧判据）。
     *
     * <p>生产注入：{@code ChannelBindingService.findByServerName(serverName).map(ChannelBindingEntry::sessionId).orElse(null)}。
     *
     * <p><b>为什么归属来自绑定表而不是「谁连的 MCP」</b>：CC 单进程单会话 ⇒ 消息靠 ambient
     * {@code agentId: ci()} 进当前 agent 队列；本仓多会话 ⇒ 必须显式记录「渠道归哪个会话」，
     * 且该关系要跨后端重启存活 ⇒ 落 {@code mcp_channel_binding} 表。
     */
    private Function<String, String> boundSessionResolver;

    /**
     * [V254 外部渠道] 门序[2] 的最终判定值。
     *
     * <p><b>DB 权威，yml 兜底</b>：
     * <ul>
     *   <li>DB 有明确值（true 或 false）→ <b>以 DB 为准</b>（用户可在 UI 显式开/关，
     *       且 UI 关掉后<b>重启后端也不会被 yml 顶回来</b>）；</li>
     *   <li>DB 未配置（null）/ 读源未装 / 读失败 → 回落 yml {@code nexusai.mcp.channels-enabled}。</li>
     * </ul>
     *
     * <p><b>为什么 DB 优先而不是「或」关系</b>：若两者取或，yml 开着的通道在 UI 关掉后
     * 重启又自动开 ⇒ 用户会觉得「关了没用」。DB 优先才能让 UI 的显式关闭真正生效。
     * 而 DB 未配置时回落 yml，保留「yml 一把开」的运维便利。
     *
     * <p>⚠️ 与 CC 的差异：CC 只有 GrowthBook 灰度开关（进程级），没有 DB/UI 两级。
     * 本仓是 Web 多用户形态，UI 开关是必需品（用户裁定 2026-11-03）。
     */
    private boolean runtimeChannelsEnabled() {
        Boolean dbValue = dbChannelsEnabled();
        if (dbValue != null) {
            return dbValue;
        }
        return Boolean.TRUE.equals(channelsEnabledSupplier.get());
    }

    /**
     * [批 3b] session --channels 白名单 · <b>显式 sessionId → 白名单</b> 的纯函数
     * （旧 {@code Supplier<...>} 版本在读时才解析会话来源，只能读 ThreadLocal ⇒ 派生线程失真）。
     */
    private Function<String, List<ChannelAllowlist.ChannelEntry>> allowedChannelsProvider;
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
        // 兼容形：session 无关的常量/打桩 supplier → 忽略 sessionId 的 provider（既有测试注入点）。
        //   生产走 setAllowedChannelsProvider(ChannelSessionAllowlist.sessionLookup())（显式会话查表）。
        this.allowedChannelsProvider = allowedChannelsSupplier == null
            ? sid -> List.of() : sid -> allowedChannelsSupplier.get();
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

    /**
     * 注入 session --channels 白名单 <b>显式查表器</b>（生产经
     * {@link ChannelSessionAllowlist#sessionLookup()}；sessionId 由
     * {@link #gateChannelServer} 的调用方显式传入）。
     */
    public void setAllowedChannelsProvider(Function<String, List<ChannelAllowlist.ChannelEntry>> provider) {
        if (log.isDebugEnabled()) {
            log.debug("[ChannelNotificationGate] 注入 session --channels 白名单 provider: {}",
                provider != null ? "真实会话态(ChannelSessionAllowlist.sessionLookup)" : "null(保持现态)");
        }
        if (provider != null) {
            this.allowedChannelsProvider = provider;
        }
    }

    /**
     * [V255 外部渠道] 注入「server → 归属会话」查询器（生产经
     * {@code ChannelBindingService.findByServerName}）。
     *
     * <p><b>门序[3] 的新判据</b>：这个 server 有没有绑定到某个会话？
     * <ul>
     *   <li>有绑定 → register，且 {@code boundSessionId} = 绑定表里的会话（消息投给它）；</li>
     *   <li>无绑定 → skip（SESSION 门）—— 没人认领这个渠道 ⇒ 不注册 handler。</li>
     * </ul>
     *
     * <p><b>与 CC 的语义差异（有意为之）</b>：CC 的门序[3] 判的是「server 在不在本会话的
     * {@code --channels} 白名单里」（{@code Rt.allowedChannels}，进程级全局）；
     * 本仓改为「server 有没有被绑定到某个会话」（DB 表，跨进程重启存活）。
     * 两者在「谁有权用这个 channel」上是同一个问题，只是本仓必须显式记录归属会话。
     */
    public void setBoundSessionResolver(Function<String, String> resolver) {
        if (log.isDebugEnabled()) {
            log.debug("[ChannelNotificationGate] 注入 server→归属会话 解析器: {}",
                resolver != null ? "真实绑定态(ChannelBindingService)" : "null(保持现态)");
        }
        if (resolver != null) {
            this.boundSessionResolver = resolver;
        }
    }

    /** 兼容形（session 无关常量/打桩；既有测试注入点）· 委派 {@link #setAllowedChannelsProvider}。 */
    public void setAllowedChannelsSupplier(Supplier<List<ChannelAllowlist.ChannelEntry>> supplier) {
        if (supplier != null) {
            setAllowedChannelsProvider(sid -> supplier.get());
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
     *   <li><b>session</b>（L250-257）：server 必须在<b>该 sessionId</b> 的 --channels 白名单</li>
     *   <li><b>marketplace</b>（L259-276）：plugin-kind entry 校验安装来源 == 标签（pluginSource 无/错 → fail-closed）</li>
     *   <li><b>allowlist</b>（L278-313）：approved 白名单比对（DB ledger）；server-kind entry 恒 skip（schema 仅 plugin）</li>
     * </ol>
     * skip → 连接保持、handler 不注册（L183-186）。
     *
     * <p><b>[批 3b]</b> sessionId 为显式会话源（本方法常在 connectWorker 池线程执行，MDC 不可用）。
     * null/blank → 白名单空表 → 门序[3]恒 SESSION skip（fail-closed，与 CC「server 未列入
     * --channels」同向）。
     *
     * @param sessionId 发起本次连接的服务所属会话（显式；null = 无会话 → fail-closed）
     */
    public ChannelGateResult gateChannelServer(String serverName,
                                             ServerCapabilities capabilities,
                                             String pluginSource,
                                             String sessionId) {
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
        //   [V254 外部渠道] 判定源 = DB settings.channels_enabled（权威，前端「设置→高级」改完即生效）
        //   与 yml nexusai.mcp.channels-enabled（兜底默认值）的**或**关系：
        //   DB 列 null（未配置）→ 回落 yml；DB 有值 → 以 DB 为准（用户可在 UI 关掉 yml 开的通道）。
        if (!runtimeChannelsEnabled()) {
            log.info("[ChannelNotificationGate] 门序[2 runtime] 跳过 server={}: channels 功能未启用（channels-enabled=false）",
                serverName);
            return new ChannelGateResult("skip", GateKind.DISABLED,
                "channels feature is not currently available");
        }
        // 3. session --channels（L250-257）· [批 3b] 显式 sessionId 查表（无 ThreadLocal/MDC 源）
        //
        //   [V255 外部渠道] 判据改为「server 有没有绑定到某个会话」（mcp_channel_binding 表）：
        //     · 有绑定 → register，boundSessionId = 绑定表里的会话（消息投给它）；
        //     · 无绑定 → skip（没人认领这个渠道）。
        //   绑定表未接线时（boundSessionResolver == null）→ 回落旧判据（session --channels 白名单），
        //   保持既有单测与旧路径的行为不变（fail-closed 方向一致）。
        String boundSessionId = null;
        ChannelAllowlist.ChannelEntry entry;
        if (boundSessionResolver != null) {
            boundSessionId = boundSessionResolver.apply(serverName);
            if (boundSessionId == null || boundSessionId.isBlank()) {
                log.info("[ChannelNotificationGate] 门序[3 session] 跳过 server={}: 该渠道未绑定到任何会话",
                    serverName);
                return new ChannelGateResult("skip", GateKind.SESSION,
                    "server " + serverName + " is not bound to any session");
            }
            // 绑定表已给出归属会话 ⇒ 白名单条目由绑定态推导（server-kind，dev 豁免）
            //   ⇒ 门序[5] 的 server-kind 分支会恒 skip，需在此显式标记为已授权（见下方）。
            entry = new ChannelAllowlist.ChannelEntry("server", serverName, null, true);
            if (log.isDebugEnabled()) {
                log.debug("[ChannelNotificationGate] 门序[3 session] 命中绑定: server={} sessionId={}",
                    serverName, boundSessionId);
            }
        } else {
            entry = findChannelEntry(serverName, allowedChannelsProvider.apply(sessionId));
            if (entry == null) {
                log.info("[ChannelNotificationGate] 门序[3 session] 跳过 server={}: 不在该会话 --channels "
                    + "白名单中（sessionId={}）", serverName, sessionId);
                return new ChannelGateResult("skip", GateKind.SESSION,
                    "server " + serverName + " not in --channels list for this session");
            }
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
        log.info("[ChannelNotificationGate] 门序全过 → register server={} kind={} entry={} sessionId={}",
            serverName, entry.kind(), entry.name(), boundSessionId);
        return new ChannelGateResult("register", null, null, boundSessionId);
    }

    /**
     * 无会话重载 · 等价 {@code gateChannelServer(serverName, capabilities, pluginSource, null)}
     * （门序[3 session] 恒 SESSION skip = fail-closed）。
     *
     * <p>保留给「本就不需要会话态」的调用方（既有单测直注入常量 supplier 的形态）；生产
     * channel 注册路径一律走 4 参版本并显式传会话（McpToolPool 连接 worker）。
     */
    public ChannelGateResult gateChannelServer(String serverName,
                                             ServerCapabilities capabilities,
                                             String pluginSource) {
        return gateChannelServer(serverName, capabilities, pluginSource, null);
    }
}
