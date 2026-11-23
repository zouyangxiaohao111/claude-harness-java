package com.nexusai.application.agent.config;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 会话级「已启动应用」的唯一读取点（V337 列 {@code sessions.enabled_apps}）。
 *
 * <p>三态：{@code null} = 未设置（按应用目录 defaultOpen 种子）；空列表 = 显式全不启动；
 * 非空 = 显式启动集。schema 门与执行侧 allowlist 都读这里（每轮 selectedApps 只是强调层，
 * 不参与 allowlist）。
 *
 * <p>静态桥接写法照 {@link SessionToolDisableConfig}（生产由 {@code LlmAgentLoop.setSessionMapper}
 * 注入；POJO 单测未注入 → null = 未设置）。
 */
public final class SessionEnabledAppsConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionEnabledAppsConfig.class);

    private static volatile SessionMapper sessionMapper;

    private SessionEnabledAppsConfig() {}

    /**
     * 静态桥接 setter（生产注入点 = {@code LlmAgentLoop.setSessionMapper}，与
     * SessionToolDisableConfig / SessionModeState 同一处；测试直接调用）。
     * ⚠️ 本类是纯静态工具、不是 Spring bean ⇒ ⛔ 不要在本类上加 @Autowired 方法（不会被调用）。
     */
    public static void setSessionMapper(SessionMapper mapper) {
        sessionMapper = mapper;
    }

    /** 测试辅助：重置静态桥接。 */
    public static void reset() {
        sessionMapper = null;
    }

    /**
     * 读取会话级已启动应用原始值。
     *
     * @param sessionId 会话 id（null/空白 / 未注入 mapper / 会话不存在 / 解析失败 → null）
     * @return null = 未设置（按 defaultOpen 种子）；空列表 = 全不启动；非空 = 显式启动集
     */
    public static List<String> getEnabledApps(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionMapper mapper = sessionMapper;
        if (mapper == null) {
            return null;
        }
        try {
            SessionRecord s = mapper.selectOneById(sessionId);
            if (s == null) {
                return null;
            }
            String raw = s.getEnabledApps();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            List<String> list = com.alibaba.fastjson2.JSON.parseArray(raw, String.class);
            if (list == null) {
                return null;
            }
            List<String> out = new java.util.ArrayList<>(list.size());
            for (String v : list) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("[SessionEnabledAppsConfig] 解析 enabled_apps 失败（按未设置处理）session={}: {}",
                sessionId, e.toString());
            return null;
        }
    }
}
