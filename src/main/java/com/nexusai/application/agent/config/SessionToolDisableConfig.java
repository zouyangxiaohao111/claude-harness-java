package com.nexusai.application.agent.config;

import com.alibaba.fastjson2.JSON;
import com.nexusai.common.SessionKeys;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 会话级禁用工具集合统一读取 · Java 独有增强（CC {@code tools.ts} 无「会话内临时禁用」内置机制，
 * G24 用户拍板：Web UX 产品功能，Java 端以「会话级禁用集合 + llmToolsArray 追加过滤」复刻
 * CC blanket deny 的 schema 阶段剔除效果，tools.ts:262-269「A tool is filtered out if there's a
 * deny rule matching its name ... before the model sees them」）。
 *
 * <p><b>静态桥接</b>：{@link #getDisabledTools(String)} 为静态（{@code LlmAgentLoop.llmToolsArray}
 * 位于 static 上下文，:8363），Spring 注入值经构造器/桥接方法写入静态字段。POJO {@code new}
 * 场景（单测）不触发 Spring → 桥接为 null → 返回空集（不剔除任何工具）；测试用 {@link #reset()}
 * 防跨测试污染（对齐 {@link MemoryBareModeConfig} 同款静态桥接惯例）。
 *
 * <p><b>会话键归一化</b>：与 {@code MemoryBareModeConfig.normalizeSessionKey} 同款——
 * {@link SessionKeys#originalKey(String)}（"sess-xxx" 原样 / 派生 UUID 反解），不可反解 →
 * 原样当 DB 键（对齐 EffortCommand.resolveSessionKey）。
 *
 * <p>消费方：{@code LlmAgentLoop.sessionVisibleTools}（在基链 {@code sessionVisibleToolsBase}
 * —— bare → deny → coordinator —— 之后应用会话禁用剔除，R3 校正：非「deny 后、coordinator
 * 前」；{@code AgentToolUtils.applyCoordinatorToolFilter} 为纯过滤、可交换，语义等价；
 * CC 顺序「simpleTools 选择先于 filterToolsByDenyRules，tools.ts:297」，bare 是最外层）
 * + {@code SessionToolsController}（GET 工具列表 disabled 标志）。
 */
@Component
public class SessionToolDisableConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionToolDisableConfig.class);

    /** 会话级禁用工具集合 DB 读取桥接（sessions.disabled_tools，V34 列）；null = 未注入（POJO 单测/无 Spring）→ 空集 */
    private static volatile SessionMapper sessionMapper;

    /**
     * Spring 注入 SessionMapper 桥接（setter 注入 → 静态字段，对齐 {@code MemoryBareModeConfig}
     * 同款静态桥接惯例；{@code @Autowired} 不支持静态字段/方法）。
     *
     * <p>会话级禁用工具读取（V34 列 disabled_tools）依赖该 mapper；POJO {@code new} 场景（单测）
     * 不触发 Spring → 桥接为 null → 返回空集。
     *
     * @param mapper 会话 mapper（Spring 注入，可为 null → 跳过桥接）
     */
    @Autowired(required = false)
    public void bridgeSessionMapper(SessionMapper mapper) {
        setSessionMapper(mapper);
    }

    /**
     * 会话 mapper 静态桥接 setter · 测试（跨包）+ Spring 桥接共用。
     */
    public static void setSessionMapper(SessionMapper mapper) {
        sessionMapper = mapper;
        if (mapper != null) {
            log.info("SessionToolDisableConfig 注入 SessionMapper（会话级禁用工具集合读取可用，V34 列）");
        }
    }

    /**
     * 读取会话级禁用工具集合（sessions.disabled_tools，V34 列 JSON 数组）。
     *
     * <p>优先级（硬约束）：当前会话 disabled_tools → 有值则用之（JSON 数组）；null / 空 /
     * 无会话上下文 / SessionMapper 未注入 / DB 读取失败 → 空集合（不剔除任何工具）。
     *
     * @param sessionId 会话标识（TUC.sessionId() 派生 UUID / MDC "sess-xxx" / null）
     * @return 禁用工具名集合（不可变；未禁用 → 空集）
     */
    public static Set<String> getDisabledTools(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key != null && mapper != null) {
            try {
                SessionRecord s = mapper.selectOneById(key);
                if (s != null && s.getDisabledTools() != null && !s.getDisabledTools().isBlank()) {
                    List<String> list = JSON.parseArray(s.getDisabledTools(), String.class);
                    if (list != null && !list.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("会话级禁用工具集合：会话 '{}' disabled_tools={} 生效（V34 列）",
                                    key, list);
                        }
                        return Set.copyOf(list);
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("会话级禁用工具集合：会话 '{}' disabled_tools 未设置（null/空）→ 空集", key);
                }
            } catch (Exception e) {
                log.warn("会话级禁用工具集合：会话 '{}' disabled_tools 读取失败（{}），降级空集",
                        key, e.getMessage());
            }
        } else if (log.isDebugEnabled()) {
            log.debug("会话级禁用工具集合：无会话上下文（sessionId={}）或 SessionMapper 未注入 → 空集",
                    sessionId == null ? "null" : sessionId);
        }
        return Set.of();
    }

    /**
     * 会话键归一化 · [session-id-short] 入参已 short 直返（不再 originalKey 反解——调用方传
     * ctx.sessionId() 已统一形态；存量旧行读取可保留 originalKey 兜底，阶段2 删）。
     */
    private static String normalizeSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String original = SessionKeys.originalKey(sessionId);
        return original != null ? original : sessionId;
    }

    /**
     * 测试辅助：重置静态桥接（防跨测试 / 跨 Spring 上下文污染）。
     */
    public static void reset() {
        sessionMapper = null;
    }
}
