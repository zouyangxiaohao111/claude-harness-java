package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本会话「生效工具可见集」的<b>唯一解析器</b>。
 *
 * <p>schema（{@code LlmAgentLoop.sessionVisibleTools}）、ToolSearch 候选池、执行守卫
 * （{@code StreamingToolExecutor} / {@code ToolRegistry}）与工具面板全部读这里，
 * 避免四表面各写一份过滤后再漂移。
 *
 * <h2>三层来源（互不合并成一份存储）</h2>
 * <ul>
 *   <li><b>用户工具开关</b>：{@code sessions.disabled_tools} 及其动态默认集（CU+KB）；
 *       默认集仍可被人格点名豁免（既有语义，本类不重写）。</li>
 *   <li><b>Brief</b>：{@code sessions.brief_mode} 只决定 {@code SendUserMessage} 一项，
 *       ⛔ 不是整组 IM 开关；另受全局 kill-switch 约束。</li>
 *   <li><b>IM 组其余工具</b>：{@code sessions.im_tool_overrides} 增量（true/false）；
 *       无覆盖 ⇒ 默认不启用（R13），但不会写回 disabled_tools（不固化默认集）。
 *       例外：{@link SessionToolCategory#IM_NO_BINDING_TOOL_NAMES}（本机微信记录）不需要渠道
 *       绑定到本会话 ⇒ 无覆盖时默认可用，显式覆盖 false 仍关闭。</li>
 *   <li><b>无人值守</b>：{@code sessions.unattended_mode} ⇒ 剔除 AskUserQuestion。</li>
 *   <li><b>无人值守 + 协调者</b>（2026-11-23）：IM 组<b>条件扩展</b> —— 无覆盖时默认放行
 *       （{@link #isUnattendedCoordinator(String)}）。显式 {@code im_tool_overrides=false} 仍优先；
 *       {@code SendUserMessage} 的 brief 门把会话级 opt-in 视为已开（全局 kill-switch 仍最高优先）。</li>
 * </ul>
 */
public final class SessionToolVisibility {

    /** 请求级快照在 {@code ToolUseContext.requestExtras()} 中的键（local-only，不序列化给模型）。 */
    public static final String SNAPSHOT_KEY = "__nexusTurnToolSnapshot";

    /**
     * [app-selection] 本轮「强调」应用在 requestExtras 中的键。
     *
     * <p>⛔ 只用于强调通知（{@code app_emphasis_change}），<b>不参与 allowlist / 不改 schema</b>；
     * 工具可见性的门 = 会话级 {@code sessions.enabled_apps}（{@link #effectiveAllowedApps}）。
     */
    public static final String SELECTED_APPS_KEY = "selectedApps";

    private static final Logger log = LoggerFactory.getLogger(SessionToolVisibility.class);

    /** 全局 Brief kill-switch（BriefTool 的 {@code nexusai.brief.enabled}）；默认开。 */
    private static volatile boolean briefGloballyEnabled = true;

    private SessionToolVisibility() {}

    /**
     * 本轮生效快照（不可变）。
     *
     * @param sessionId   捕获时的会话 id（读取时必须匹配，防止跨会话复用）
     * @param disabled    应禁用的工具名集合（已含 Brief/IM/会话开关结果）
     * @param askExcluded 是否剔除 AskUserQuestion（无人值守）
     * @param allowedMcpServers [app-selection] 本轮允许的 MCP server（规范化名）集合；
     *        {@code null} = 未指定（不裁剪，与历史行为一致）；空集 = 显式什么都不选
     */
    public record Snapshot(String sessionId, Set<String> disabled, boolean askExcluded,
                           Set<String> allowedMcpServers) {

        public Snapshot {
            allowedMcpServers = allowedMcpServers == null ? null : Set.copyOf(allowedMcpServers);
        }

        /** 兼容旧构造点（无 allowlist 维度 = 不裁剪）。 */
        public Snapshot(String sessionId, Set<String> disabled, boolean askExcluded) {
            this(sessionId, disabled, askExcluded, null);
        }

        /** 本轮是否携带 MCP server allowlist（null = 未指定 ⇒ false）。 */
        public boolean hasMcpAllowlist() {
            return allowedMcpServers != null;
        }

        /** 该工具在本轮是否不可见/不可执行（disabled 黑名单 + 本轮 MCP allowlist）。 */
        public boolean blocks(String toolName) {
            return toolName != null
                && (disabled.contains(toolName)
                    || !isMcpServerAllowed(allowedMcpServers, toolName));
        }
    }

    /**
     * MCP allowlist 纯判据（schema 侧 {@link Snapshot#blocks} 与执行侧池裁剪共用）。
     *
     * @param allowedMcpServers 规范化 server 名集合；{@code null} = 未指定（不裁剪）
     * @param toolName          工具全名
     * @return false = 该工具属于某个未被本轮选中的 MCP server
     */
    public static boolean isMcpServerAllowed(Set<String> allowedMcpServers, String toolName) {
        if (allowedMcpServers == null || toolName == null) {
            return true;
        }
        // 浏览器 / computer-use 是内置工具，不是应用层应用（应用层只含 MCP）⇒ allowlist 一律放行。
        //   它们的开关仍走工具面板（sessions.disabled_tools / 默认禁用集）。
        if (toolName.startsWith(SessionToolCategory.BR_PREFIX)
                || toolName.startsWith(SessionToolCategory.CU_PREFIX)) {
            return true;
        }
        String server = SessionToolCategory.mcpServerOf(toolName);
        return server == null || allowedMcpServers.contains(server);
    }

    /**
     * [app-selection] 从 {@code requestExtras} 读本轮选中应用。
     *
     * @return {@code null} = 键缺失/类型不符（未指定）；空列表 = 显式什么都不选
     */
    public static java.util.List<String> selectedAppsFromExtras(Map<String, Object> extras) {
        if (extras == null) {
            return null;
        }
        Object raw = extras.get(SELECTED_APPS_KEY);
        if (raw == null) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        } else if (raw instanceof String[] array) {
            for (String s : array) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        } else if (raw instanceof String s) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        } else {
            return null;
        }
        return java.util.List.copyOf(out);
    }

    /**
     * 会话当前<b>有效启动集</b>（schema 门）：
     * <ol>
     *   <li>{@code sessions.enabled_apps} 显式值优先（null = 未设置 / [] = 全不启动 / 数组 = 启动集）；</li>
     *   <li>未设置 ⇒ 应用目录 defaultOpen 种子（MCP 内 connector 安装=false、其余=true）；</li>
     *   <li>种子供应器未安装或种子读取失败 ⇒ null = 不裁剪（fail-soft 方向，⛔ 不退化成空集）。</li>
     * </ol>
     *
     * @param sessionId 会话 id
     * @return 允许的应用（规范化 key）；null = 不裁剪；空集 = 全不启动
     */
    public static Set<String> effectiveAllowedApps(String sessionId) {
        java.util.List<String> raw = com.nexusai.application.agent.config
            .SessionEnabledAppsConfig.getEnabledApps(sessionId);
        if (raw != null) {
            return normalizeSelectedApps(raw);
        }
        Set<String> seed = com.nexusai.application.agent.app.AppEnablement.defaultOpenKeys();
        return seed == null ? null : normalizeSelectedApps(new java.util.ArrayList<>(seed));
    }

    /**
     * 会话当前有效启动集的<b>展示用 key</b>（原始值优先；未设置 ⇒ defaultOpen 种子）。
     *
     * <p>与 {@link #effectiveAllowedApps(String)} 的差别：本方法给通知/UI 用（raw key），
     * 后者给过滤判据用（规范化 key）。
     *
     * @param sessionId 会话 id
     * @return 应用 key 列表（去重）；null = 无来源（种子供应器未安装）
     */
    public static java.util.List<String> effectiveEnabledAppKeys(String sessionId) {
        java.util.List<String> raw = com.nexusai.application.agent.config
            .SessionEnabledAppsConfig.getEnabledApps(sessionId);
        if (raw != null) {
            java.util.TreeSet<String> dedup = new java.util.TreeSet<>();
            for (String v : raw) {
                if (v != null && !v.isBlank()) {
                    dedup.add(v.trim());
                }
            }
            return java.util.List.copyOf(dedup);
        }
        Set<String> seed = com.nexusai.application.agent.app.AppEnablement.defaultOpenKeys();
        return seed == null ? null : java.util.List.copyOf(new java.util.TreeSet<>(seed));
    }

    /**
     * 选中应用 → 允许的 MCP server 名集合（与 MCP 工具名同口径规范化；空集 = 显式什么都不选）。
     *
     * <p>应用层只含 MCP：key 为 server 名，判据走 {@code mcpServerOf(toolName)}。
     */
    public static Set<String> normalizeSelectedApps(java.util.List<String> selectedApps) {
        if (selectedApps == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String app : selectedApps) {
            if (app == null || app.isBlank()) {
                continue;
            }
            out.add(com.nexusai.application.agent.mcp.McpStringUtils
                .normalizeNameForMCP(app.trim()));
        }
        return Set.copyOf(out);
    }

    /**
     * [app-selection] 执行侧工具池裁剪（与 schema 侧 {@link Snapshot#blocks} 共用同一判据）。
     *
     * @param tools             工具池（可 null → 原样返回）
     * @param allowedMcpServers 允许的 server 集合；{@code null} = 未指定 → 原样返回
     * @return 只保留非 MCP 工具与选中 server 的 MCP 工具
     */
    public static java.util.List<Tool> filterByMcpAllowlist(java.util.List<Tool> tools,
                                                           Set<String> allowedMcpServers) {
        if (tools == null || allowedMcpServers == null) {
            return tools;
        }
        return tools.stream()
            .filter(t -> t != null && isMcpServerAllowed(allowedMcpServers, t.name()))
            .toList();
    }

    /** Spring 启动时桥接全局 Brief kill-switch（由 SessionModeService 注入）。 */
    public static void setBriefGloballyEnabled(boolean enabled) {
        briefGloballyEnabled = enabled;
    }

    /** 测试辅助：恢复全局开关默认值。 */
    public static void resetGlobalFlags() {
        briefGloballyEnabled = true;
    }

    /**
     * 解析某会话当前生效可见集。
     *
     * @param sessionId 会话 id（null/空白 ⇒ 空禁用集、不摘 Ask）
     * @return 快照
     */
    public static Snapshot capture(String sessionId) {
        return capture(sessionId, false);
    }

    /**
     * 解析某会话当前生效可见集（可声明「无会话的非交互执行」）。
     *
     * @param sessionId       会话 id（null/空白 ⇒ 空禁用集）
     * @param headlessNoSession true = 无会话的非交互执行（如无头定时任务）⇒ 无人可答选择题，Ask 不可用
     * @return 快照
     */
    public static Snapshot capture(String sessionId, boolean headlessNoSession) {
        Set<String> allowedMcpServers = effectiveAllowedApps(sessionId);
        if (sessionId == null || sessionId.isBlank()
                || com.nexusai.common.SessionKeys.isNoSession(sessionId)) {
            // 无头任务（SessionKeys.NO_SESSION 哨兵）没有会话可承载 Ask；
            // 交互式无会话路径（headlessNoSession=false）保持不变。
            return new Snapshot(sessionId, Set.of(), headlessNoSession, allowedMcpServers);
        }
        Set<String> disabled = new LinkedHashSet<>(SessionToolDisableConfig.getDisabledTools(sessionId));
        // 人格豁免：只对「默认禁用集」生效（用户显式写进 disabled_tools 的意图不被覆盖）。
        if (!disabled.isEmpty() && SessionToolDisableConfig.isDefaultDisabledSet(sessionId)) {
            Set<String> named = LlmAgentLoop.agentNamedTools(sessionId);
            if (!named.isEmpty()) {
                disabled.removeAll(named);
            }
        }
        boolean unattended = SessionModeState.isUnattended(sessionId);
        // [coordinator-unattended] 无人值守 + 协调者 ⇒ IM 组条件扩展（默认放行）。
        //   显式 im_tool_overrides=false 仍优先（见下方循环），本标志只改「无覆盖」的默认值。
        boolean imAllOn = unattended && isCoordinatorActive(sessionId);
        // Brief：只决定 SendUserMessage；全局 kill-switch 关闭时恒不可见。
        //   无人值守 + 协调者时把会话级 opt-in 视为已开（kill-switch 仍是最高优先）。
        boolean briefOn = briefGloballyEnabled && (SessionModeState.isBrief(sessionId) || imAllOn);
        String briefTool = ToolNameConstants.BRIEF_TOOL_NAME;
        disabled.remove(briefTool);
        if (!briefOn) {
            disabled.add(briefTool);
        }
        // IM 组：显式增量覆盖优先；无覆盖时，渠道类工具默认不启用，
        // 本机微信记录类工具（不依赖渠道绑定到本会话）默认可用。
        Map<String, Boolean> overrides = SessionModeState.imToolOverrides(sessionId);
        for (String name : SessionToolCategory.IM_TOOL_NAMES) {
            if (briefTool.equals(name)) {
                continue;
            }
            Boolean override = overrides.get(name);
            if (override != null) {
                if (override) {
                    disabled.remove(name);
                } else {
                    disabled.add(name);
                }
                continue;
            }
            if (imAllOn) {
                // [coordinator-unattended] 无人值守 + 协调者 ⇒ 渠道类 IM 工具默认放行
                if (log.isDebugEnabled()) {
                    log.debug("[SessionToolVisibility] {} 默认可用（无人值守 + 协调者）session={}",
                            name, sessionId);
                }
                continue;
            }
            if (SessionToolCategory.IM_NO_BINDING_TOOL_NAMES.contains(name)) {
                // 本机微信记录：读本地已同步索引，不需要渠道绑定到会话 ⇒ 默认可用
                if (log.isDebugEnabled()) {
                    log.debug("[SessionToolVisibility] {} 默认可用（本机微信记录，无需渠道绑定）session={}",
                            name, sessionId);
                }
                continue;
            }
            disabled.add(name);
        }
        return new Snapshot(sessionId, Set.copyOf(disabled), unattended, allowedMcpServers);
    }

    /**
     * 从 TUC 取本轮快照；缺失/会话不匹配/类型不符 ⇒ 实时解析（不跨会话复用）。
     *
     * <p>只接受本类产出的 {@link Snapshot} 实例：外部 JSON 只能构造 Map，无法伪造。
     *
     * @param sessionId 当前会话 id
     * @param extras    TUC requestExtras（可 null）
     * @return 快照
     */
    public static Snapshot resolve(String sessionId, Map<String, Object> extras) {
        return resolve(sessionId, extras, false);
    }

    /**
     * 从 TUC 取本轮快照（可声明无会话的非交互执行）。
     *
     * @param sessionId         当前会话 id
     * @param extras            TUC requestExtras（可 null）
     * @param headlessNoSession true = 无会话的非交互执行 ⇒ Ask 不可用
     * @return 快照
     */
    public static Snapshot resolve(String sessionId, Map<String, Object> extras, boolean headlessNoSession) {
        Object raw = extras == null ? null : extras.get(SNAPSHOT_KEY);
        if (raw instanceof Snapshot snapshot
                && sessionId != null && sessionId.equals(snapshot.sessionId())) {
            return snapshot;
        }
        return capture(sessionId, headlessNoSession);
    }

    /**
     * 把本轮快照挂到 requestExtras（其余键原样保留）。
     *
     * @param extras 原有 extras（可 null）
     * @param snapshot 本轮快照
     * @return 新映射
     */
    public static Map<String, Object> attach(Map<String, Object> extras, Snapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extras != null) {
            out.putAll(extras);
        }
        out.put(SNAPSHOT_KEY, snapshot);
        return Map.copyOf(out);
    }

    /**
     * [coordinator-unattended] 本会话是否「无人值守 + 协调者」——IM 组条件扩展的<b>唯一判定</b>。
     *
     * <p>两个工具池消费点（{@code LlmAgentLoop.sessionVisibleToolsBase} 的 coordinator 白名单
     * 扩展、本类 {@link #capture} 的 IM 默认值）都走这里，避免各写一份判定后漂移。
     *
     * @param sessionId 会话 id（null/空白 → false）
     * @return true = IM 组无覆盖时默认放行
     */
    public static boolean isUnattendedCoordinator(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return SessionModeState.isUnattended(sessionId) && isCoordinatorActive(sessionId);
    }

    /**
     * 会话级协调者门（DB 会话列 → 回落 feature+env，唯一入口
     * {@code staticCoordinatorModeActiveForSession}）。
     */
    private static boolean isCoordinatorActive(String sessionId) {
        return com.nexusai.application.agent.prompt.PromptAlignSettingsResolver
            .staticCoordinatorModeActiveForSession(sessionId, LlmAgentLoop.coordinatorModeBean());
    }

    /** 供面板/UI：本会话 IM 工具是否可见（同源读数）。 */
    public static boolean imToolVisible(String sessionId, String toolName) {
        return !capture(sessionId).blocks(toolName);
    }
}
