package com.nexusai.domain.session;

import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionModeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 会话「无人值守 / Brief」状态编排（V329）。
 *
 * <h2>唯一状态源</h2>
 * <ul>
 *   <li>{@code sessions.unattended_mode} / {@code sessions.brief_mode}：会话级显式状态；</li>
 *   <li>{@code sessions.disabled_tools}：SendUserMessage（及整组 IM 工具）的 schema 可见性 ——
 *       本服务在 Brief 切换时<b>同步写入</b>，使 schema / ToolSearch / 执行入口 / 面板同源。</li>
 * </ul>
 *
 * <h2>已确认的状态转换（设计 §13.3 / R14–R20）</h2>
 * <ul>
 *   <li>T3 开无人值守：唯一硬前置 = 渠道实例 ≥ 1；开 ⇒ Brief on + 整组 IM 工具 on + Ask 剔除
 *       （Ask 的剔除由 {@code ImUnattendedConfig} 读同一状态完成，不写 disabled_tools）。</li>
 *   <li>T4 手动退出：只关 unattended；Brief 保持；渠道/绑定保留。</li>
 *   <li>T5 Brief 单独开：只改 Brief（SendUserMessage），Ask 不受影响。</li>
 *   <li>T6 移除本会话最后一个渠道：unattended off ∧ Brief off（R14）， Ask 恢复。</li>
 *   <li>Q8（已裁定）：无人值守开启时关 Brief ⇒ 先由前端弹确认；确认后<b>一次原子写入</b>
 *       brief=false + unattended=false；未确认的 API 调用返回业务错误，{@code /brief} 命令拒绝并给指引。</li>
 * </ul>
 */
@Service
public class SessionModeService {

    private static final Logger log = LoggerFactory.getLogger(SessionModeService.class);

    /** 渠道未配置时的用户文案（与设计稿「请先配置渠道」逐字一致）。 */
    public static final String NEED_CHANNEL_MESSAGE = "请先配置渠道";

    /**
     * [Q8] 无人值守开启时关 Brief 需要用户先确认的文案（API 业务错误文案，面向用户）。
     * ⛔ 不出现内部词（Q8 / 级联）。
     */
    public static final String BRIEF_CONFLICT_MESSAGE =
            "无人值守正在开启，关闭回答直达会同时退出无人值守。请确认后再关闭。";

    /** [Q8] 斜杠命令无弹窗能力 ⇒ 拒绝并给可执行指引（不改任何状态）。 */
    public static final String BRIEF_COMMAND_CONFLICT_MESSAGE =
            "无人值守正在开启，无法从这里关闭回答直达。请先在界面关闭无人值守，"
            + "或在界面确认「关闭回答直达并退出无人值守」。";

    @Autowired private SessionService sessionService;
    @Autowired private ImChannelService channelService;
    /** 「本会话是否还有渠道」判据（im_chat 绑定 / MCP 渠道绑定）；未接线 ⇒ 只按 im_chat 判。 */
    @Autowired(required = false) private com.nexusai.domain.im_channel.ImChatService imChatService;
    @Autowired(required = false) private com.nexusai.domain.mcp_channel_binding.ChannelBindingService channelBindingService;

    /** 全局 Brief kill-switch（与 BriefTool 同一配置键）；关闭时无论会话状态都不暴露 SendUserMessage。 */
    @org.springframework.beans.factory.annotation.Value("${nexusai.brief.enabled:true}")
    private boolean briefGloballyEnabled = true;

    @jakarta.annotation.PostConstruct
    void bridgeBriefKillSwitch() {
        com.nexusai.application.agent.tool.SessionToolVisibility.setBriefGloballyEnabled(briefGloballyEnabled);
    }

    /**
     * 读当前状态（不存在 → 404，由 {@link SessionService#getRecord} 抛出）。
     *
     * @param sessionId 会话 id
     * @return 状态 DTO
     */
    public SessionModeDto getState(String sessionId) {
        requireSession(sessionId);
        int channels = channelCount();
        return new SessionModeDto(
                sessionId,
                SessionModeState.isUnattended(sessionId),
                SessionModeState.isBrief(sessionId),
                channels,
                // [F1] 能否开启 = 本会话是否已有绑定渠道（与 setUnattended 前置、onChannelClosed 同源）。
                //   channels 仍是全局实例数，仅供界面展示「一共接了几个账号」。
                hasAnyChannel(sessionId));
    }

    /**
     * 开启/退出无人值守。
     *
     * @param sessionId 会话 id
     * @param enabled   true = 开启；false = 手动退出
     * @return 落定后的状态
     * @throws ValidationException 开启时无任何已接入渠道
     */
    public SessionModeDto setUnattended(String sessionId, boolean enabled) {
        requireSession(sessionId);
        if (enabled) {
            // [F1] 开启前置必须与「关闭/自动退出」同源：本会话**已绑定渠道**。
            //   ⛔ 旧判据读全局实例数 ⇒ 没绑任何渠道的会话，只要别处连了一个 IM 账号
            //   就能开无人值守；开了以后 Ask 被摘、消息却无处可送。
            if (!hasAnyChannel(sessionId)) {
                throw new ValidationException(NEED_CHANNEL_MESSAGE);
            }
            // 先落 Brief/IM 工具，再落无人值守：若第二步失败，最坏是 Brief 开但无人值守未开
            // （Ask 仍可用，安全方向）；反过来会产生「无人值守但 Brief 关」的不一致组合。
            applyBrief(sessionId, true, true);
            sessionService.setUnattendedMode(sessionId, true);
            if (log.isInfoEnabled()) {
                log.info("[SessionMode] 开启无人值守: session={} ⇒ brief=on + IM 工具 on + Ask 剔除",
                        sessionId);
            }
        } else {
            sessionService.setUnattendedMode(sessionId, false);
            if (log.isInfoEnabled()) {
                log.info("[SessionMode] 退出无人值守: session={} ⇒ Ask 恢复；Brief 保持={}",
                        sessionId, SessionModeState.isBrief(sessionId));
            }
        }
        return getState(sessionId);
    }

    /**
     * 切换 Brief（与 SendUserMessage 工具开关、{@code /brief} 同一份会话状态）。
     *
     * <p>[Q8] 无人值守开启时关 Brief：未带确认 ⇒ 拒绝且状态不变（前端据此弹确认框）；
     * 带 {@code cascadeExitUnattended=true} ⇒ <b>一次原子写入</b> brief=false + unattended=false。
     *
     * @param sessionId 会话 id
     * @param enabled   true = 开；false = 关
     * @return 落定后的状态
     * @throws IllegalStateException 无人值守开启且未带确认
     */
    public SessionModeDto setBrief(String sessionId, boolean enabled) {
        return setBrief(sessionId, enabled, false);
    }

    /**
     * [Q8] 切换 Brief · 支持「确认后同时退出无人值守」。
     *
     * @param cascadeExitUnattended true = 用户已确认「关 Brief 同时退出无人值守」
     */
    public SessionModeDto setBrief(String sessionId, boolean enabled, boolean cascadeExitUnattended) {
        requireSession(sessionId);
        if (!enabled && SessionModeState.isUnattended(sessionId)) {
            if (!cascadeExitUnattended) {
                // 未确认 ⇒ 不动任何状态，交前端弹确认框
                throw new IllegalStateException(BRIEF_CONFLICT_MESSAGE);
            }
            // 原子级联：一次 DB update 落两列，再同步工具可见性（不产生半状态）
            sessionService.setBriefAndUnattendedMode(sessionId, false, false);
            syncBriefTools(sessionId, false, false);
            if (log.isInfoEnabled()) {
                log.info("[SessionMode] 确认关闭 Brief ⇒ 同时退出无人值守: session={}", sessionId);
            }
            return getState(sessionId);
        }
        applyBrief(sessionId, enabled, false);
        return getState(sessionId);
    }

    /**
     * 某个聊天接到了会话（「配置渠道」的会话级动作）。
     *
     * <p>R13/R19：配置渠道 ⇒ Brief on + IM 相关工具 on；Ask 不动。
     *
     * @param sessionId 会话 id
     */
    public void onChannelBound(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            requireSession(sessionId);
            applyBrief(sessionId, true, true);
            if (log.isInfoEnabled()) {
                log.info("[SessionMode] 渠道接到会话: session={} ⇒ brief=on + IM 工具 on", sessionId);
            }
        } catch (RuntimeException e) {
            // 渠道接线不应因模式落库失败而失败：记 WARN，保持聊天绑定结果。
            log.warn("[SessionMode] 渠道接到会话后同步 Brief/IM 失败（忽略）session={} err={}",
                    sessionId, e.toString());
        }
    }

    /**
     * 本会话最后一个渠道被移除（解绑最后一个聊天）。
     *
     * <p>R14：unattended off ∧ Brief off；Ask 恢复、SendUserMessage 隐藏。
     *
     * @param sessionId 会话 id
     */
    public void onLastChannelClosed(String sessionId) {
        onChannelClosed(sessionId);
    }

    /**
     * 本会话的某个渠道被移除后收敛状态（改接 / 解绑 / 删实例 / V255 解绑共用）。
     *
     * <p>R14：只有在<b>本会话已无任何渠道</b>时，才 unattended off ∧ Brief off；
     * 还有别的渠道 ⇒ 什么都不做（不能误关）。⛔ 不动其他 IM 工具增量（未裁定）。
     *
     * @param sessionId 可能已失去渠道的会话 id
     */
    public void onChannelClosed(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            requireSession(sessionId);
            if (hasAnyChannel(sessionId)) {
                return;
            }
            sessionService.setUnattendedMode(sessionId, false);
            applyBrief(sessionId, false, false);
            if (log.isInfoEnabled()) {
                log.info("[SessionMode] 移除最后一个渠道: session={} ⇒ unattended=off + brief=off（R14）",
                        sessionId);
            }
        } catch (RuntimeException e) {
            log.warn("[SessionMode] 渠道移除后同步状态失败（忽略）session={} err={}",
                    sessionId, e.toString());
        }
    }

    /**
     * 本会话是否还有渠道（im_chat 绑定 或 MCP 渠道绑定）。
     *
     * <p>读失败按「还有渠道」处理（fail-safe：宁可保留模式，不误关用户状态）。
     *
     * @param sessionId 会话 id
     * @return true = 至少有一个渠道
     */
    public boolean hasAnyChannel(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            if (imChatService != null && !imChatService.listBySession(sessionId).isEmpty()) {
                return true;
            }
            if (channelBindingService != null
                    && !channelBindingService.listBySession(sessionId).isEmpty()) {
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("[SessionMode] 查询会话渠道失败（按仍有渠道处理）session={} err={}",
                    sessionId, e.toString());
            return true;
        }
    }

    /**
     * 落 Brief 列并同步 SendUserMessage / IM 组到 disabled_tools。
     *
     * @param sessionId        会话 id
     * @param enabled          Brief 目标状态
     * @param enableWholeImGroup true = 同时启用整组 IM 工具（开无人值守 / 配置渠道时）
     */
    private void applyBrief(String sessionId, boolean enabled, boolean enableWholeImGroup) {
        sessionService.setBriefMode(sessionId, enabled);
        syncBriefTools(sessionId, enabled, enableWholeImGroup);
    }

    /** Brief 落库后的工具可见性副作用（与 DB 写入分离，供原子级联复用）。 */
    private void syncBriefTools(String sessionId, boolean enabled, boolean enableWholeImGroup) {
        // Brief 只写 brief_mode（SendUserMessage 的唯一开关）；⛔ 不再镜像 disabled_tools，
        // 否则会把动态默认禁用集固化成显式全集，进而取消人格豁免（P1-5）。
        if (enableWholeImGroup) {
            // 渠道接到会话 / 开启无人值守：只对 IM 组做增量 true，保留其他工具开关与 SendUserMessage 的 Brief 语义。
            java.util.Map<String, Boolean> patch = new java.util.LinkedHashMap<>();
            for (String name : SessionToolCategory.IM_TOOL_NAMES) {
                if (!ToolNameConstants.BRIEF_TOOL_NAME.equals(name)) {
                    patch.put(name, true);
                }
            }
            sessionService.patchImToolOverrides(sessionId, patch);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SessionMode] applyBrief: session={} brief={} wholeIm={}",
                    sessionId, enabled, enableWholeImGroup);
        }
    }

    /** 会话必须存在（不存在 → 404，交给全局异常映射）。 */
    private void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionService.getRecord(sessionId) == null) {
            throw new com.nexusai.infra.exception.NotFoundException(
                    "Session " + sessionId + " not found");
        }
    }

    /** 已接入渠道实例数（任何异常 → 0，开启动作会因此被拒而不是误开）。 */
    private int channelCount() {
        try {
            return channelService.list().size();
        } catch (RuntimeException e) {
            log.warn("[SessionMode] 统计渠道实例失败（按 0 处理）err={}", e.toString());
            return 0;
        }
    }
}
