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
 * </ul>
 */
public final class SessionToolVisibility {

    /** 请求级快照在 {@code ToolUseContext.requestExtras()} 中的键（local-only，不序列化给模型）。 */
    public static final String SNAPSHOT_KEY = "__nexusTurnToolSnapshot";

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
     */
    public record Snapshot(String sessionId, Set<String> disabled, boolean askExcluded) {

        /** 该工具在本轮是否不可见/不可执行。 */
        public boolean blocks(String toolName) {
            return toolName != null && disabled.contains(toolName);
        }
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
        if (sessionId == null || sessionId.isBlank()
                || com.nexusai.common.SessionKeys.isNoSession(sessionId)) {
            // 无头任务（SessionKeys.NO_SESSION 哨兵）没有会话可承载 Ask；
            // 交互式无会话路径（headlessNoSession=false）保持不变。
            return new Snapshot(sessionId, Set.of(), headlessNoSession);
        }
        Set<String> disabled = new LinkedHashSet<>(SessionToolDisableConfig.getDisabledTools(sessionId));
        // 人格豁免：只对「默认禁用集」生效（用户显式写进 disabled_tools 的意图不被覆盖）。
        if (!disabled.isEmpty() && SessionToolDisableConfig.isDefaultDisabledSet(sessionId)) {
            Set<String> named = LlmAgentLoop.agentNamedTools(sessionId);
            if (!named.isEmpty()) {
                disabled.removeAll(named);
            }
        }
        // Brief：只决定 SendUserMessage；全局 kill-switch 关闭时恒不可见。
        boolean briefOn = briefGloballyEnabled && SessionModeState.isBrief(sessionId);
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
        return new Snapshot(sessionId, Set.copyOf(disabled), SessionModeState.isUnattended(sessionId));
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

    /** 供面板/UI：本会话 IM 工具是否可见（同源读数）。 */
    public static boolean imToolVisible(String sessionId, String toolName) {
        return !capture(sessionId).blocks(toolName);
    }
}
