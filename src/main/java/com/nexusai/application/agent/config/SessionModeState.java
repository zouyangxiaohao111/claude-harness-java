package com.nexusai.application.agent.config;

import com.nexusai.common.SessionKeys;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 会话级「无人值守 / Brief」状态的<b>唯一读源</b>（V329 列 unattended_mode / brief_mode）。
 *
 * <h2>为什么需要它</h2>
 * <ul>
 *   <li><b>无人值守</b>：旧实现把 {@code im_chat.bound_session_id} 当作隐式判据
 *       （绑定渠道 ⇒ 剔除 AskUserQuestion）。这与 CC {@code tfi()} 不一致 ——
 *       CC 只有<b>非交互</b>（{@code Se()}）且有渠道时才摘 Ask；交互式 + 仅 channels 不摘。
 *       现在改为<b>显式会话开关</b>：只有用户在渠道右侧打开无人值守，本会话才摘 Ask。
 *       ⛔ 绑定渠道本身不再改变 Ask 可用性（R6/R16）。</li>
 *   <li><b>Brief</b>：SendUserMessage（{@code BriefTool}）需要会话级 opt-in/状态；
 *       写侧同时把 SendUserMessage 同步进 {@code sessions.disabled_tools}，因此
 *       模型 schema / ToolSearch / 执行入口 / 面板都按同一份 {@code disabled_tools} 生效。
 *       Brief 单独开启<b>不</b>影响 Ask（R12）。</li>
 * </ul>
 *
 * <h2>为什么是静态 + 静态桥</h2>
 * 消费点 {@code LlmAgentLoop.sessionVisibleTools} 与 {@code ImUnattendedConfig} 都在
 * <b>static</b> 上下文（工具池每轮求值），拿不到 Spring Bean。仓内同款范式：
 * {@link SessionToolDisableConfig} / {@code MemoryBareModeConfig}。
 *
 * <p>读失败/未接线 ⇒ false（fail-open 到「有人值守 / Brief 关」）：宁可让 agent 多问一句，
 * 也不要让它在该问的时候闭嘴。
 */
@Component
public class SessionModeState {

    private static final Logger log = LoggerFactory.getLogger(SessionModeState.class);

    /** 会话 mapper 静态桥（sessions.unattended_mode / brief_mode，V329 列）；null = 未注入。 */
    private static volatile SessionMapper sessionMapper;

    /**
     * Spring 注入桥接（setter 注入 → 静态字段，对齐 {@link SessionToolDisableConfig#bridgeSessionMapper}）。
     *
     * @param mapper 会话 mapper（可为 null → 关闭本能力）
     */
    @Autowired(required = false)
    public void bridgeSessionMapper(SessionMapper mapper) {
        setSessionMapper(mapper);
    }

    /**
     * 静态桥 setter · 测试 + Spring + LlmAgentLoop 共用。
     *
     * <p>只在桥接<b>真的发生变化</b>时记一行（避免 prototype 每次实例化都刷日志）。
     */
    public static void setSessionMapper(SessionMapper mapper) {
        SessionMapper previous = sessionMapper;
        sessionMapper = mapper;
        if (mapper != null && mapper != previous) {
            log.info("SessionModeState 注入 SessionMapper（会话级无人值守/Brief 读取可用，V329 列）");
        }
    }

    /**
     * 会话是否<b>显式</b>开启无人值守（V329 列 unattended_mode = 1）。
     *
     * @param sessionId 会话 id（null/空白 → false）
     * @return true = 本会话无人值守 ⇒ AskUserQuestion 从工具池移除
     */
    public static boolean isUnattended(String sessionId) {
        Integer v = readColumn(sessionId, true);
        return v != null && v == 1;
    }

    /**
     * 会话 Brief 是否开启（V329 列 brief_mode = 1）。
     *
     * @param sessionId 会话 id（null/空白 → false）
     * @return true = SendUserMessage 可用（写侧同步 disabled_tools）
     */
    public static boolean isBrief(String sessionId) {
        Integer v = readColumn(sessionId, false);
        return v != null && v == 1;
    }

    /**
     * 会话级 IM 工具增量覆盖（V330 列 im_tool_overrides，JSON 对象 name→boolean）。
     *
     * <p>只有本列能表达「在动态默认集之上单独启用/禁用某个 IM 工具」；
     * {@code SendUserMessage} 不在其中（它只由 brief_mode 决定，见 {@link #isBrief}）。
     * 解析失败/非对象 → 空映射（按默认处理，绝不因坏数据变成全开）。
     *
     * @param sessionId 会话 id
     * @return 不可变映射；无覆盖/读不到 → 空映射
     */
    public static java.util.Map<String, Boolean> imToolOverrides(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key == null || mapper == null) {
            return java.util.Map.of();
        }
        try {
            String raw = mapper.selectImToolOverrides(key);
            if (raw == null || raw.isBlank()) {
                return java.util.Map.of();
            }
            java.util.Map<String, Object> parsed = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (parsed == null || parsed.isEmpty()) {
                return java.util.Map.of();
            }
            java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (var entry : parsed.entrySet()) {
                if (entry.getValue() instanceof Boolean b) {
                    out.put(entry.getKey(), b);
                }
            }
            return java.util.Map.copyOf(out);
        } catch (RuntimeException e) {
            log.warn("[SessionModeState] 读取 IM 工具覆盖失败（按默认处理）session={} err={}",
                    key, e.toString());
            return java.util.Map.of();
        }
    }

    /**
     * 读列。
     *
     * @param sessionId 会话 id
     * @param unattended true = unattended_mode；false = brief_mode
     * @return 列值；读不到 → null
     */
    private static Integer readColumn(String sessionId, boolean unattended) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key == null || mapper == null) {
            return null;
        }
        try {
            SessionRecord s = mapper.selectOneById(key);
            if (s == null) {
                return null;
            }
            return unattended ? s.getUnattendedMode() : s.getBriefMode();
        } catch (RuntimeException e) {
            log.warn("[SessionModeState] 读取会话模式失败（按未开启处理）session={} err={}",
                    key, e.toString());
            return null;
        }
    }

    /** 会话键归一化（对齐 {@link SessionToolDisableConfig} 的 normalizeSessionKey）。 */
    private static String normalizeSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String original = SessionKeys.originalKey(sessionId);
        return original != null ? original : sessionId;
    }

    /** 测试辅助：重置静态桥接（防跨测试/跨 Spring 上下文污染）。 */
    public static void reset() {
        sessionMapper = null;
    }
}
