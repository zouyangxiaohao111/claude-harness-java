package com.nexusai.application.agent.mcp;

import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 会话态 channel 白名单注册表（--channels 等价物）· 对齐 CC bootstrap/state.ts。
 *
 * <p>CC original: {@code getAllowedChannels()/setAllowedChannels()}（state.ts:1676-1682，
 * 进程内 {@code STATE.allowedChannels}）+ {@code parseChannelEntries}（main.tsx:1650-1695，
 * {@code --channels} 标签解析产物）。CC 的会话白名单是进程内会话状态而非 CLI 假实现；
 * Java web 以 {@code sessionId} 键控的 in-memory 注册表承担同一语义（键 = web 会话 id，
 * 值 = {@link ChannelAllowlist.ChannelEntry} 列表，即 {@code parseChannelEntries} 的产物）。
 *
 * <p>fail-closed：{@link #getForSession} 对未知会话 / 未写入白名单 / 空列表一律返回
 * {@link List#of()}（空表），消费方（gate 门序[3 session]）在空表下恒 SESSION skip——
 * 安全默认与 CC「server 必须显式列入 --channels 才注册 handler」一致（channelNotification.ts:247-257）。
 *
 * <p>请求上下文解析：{@link #currentRequestSupplier()} 经 {@link RequestContext#sessionId()}
 * （SLF4J MDC，RequestContext.java:52）解析当前 web 请求的会话；无会话上下文 → 空表 fail-closed。
 */
@Component
public class ChannelSessionAllowlist {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionAllowlist.class);

    /** 会话 id → --channels 白名单条目（值不可变；线程安全容器）。 */
    private final Map<String, List<ChannelAllowlist.ChannelEntry>> bySession = new ConcurrentHashMap<>();

    /**
     * 写入指定会话的 --channels 白名单 · CC original: {@code setAllowedChannels(entries)}
     * （state.ts:1680-1682，{@code --channels} 解析后写入 STATE.allowedChannels）。
     *
     * <p>生产写入缝：由会话引导 / REST 端点等会话态载体调用（归属见 09-open-decisions §S07）；
     * 覆盖语义（last-wins）对齐 CC 单赋值。null 值按清空处理（等价空表 fail-closed）。
     *
     * @param sessionId 目标 web 会话 id；null/blank → 忽略（无键可写，记 warn）
     * @param entries   白名单条目（{@code parseChannelEntries} 产物）；null → 等价 {@code setForSession(sessionId, List.of())}
     */
    public void setForSession(String sessionId, List<ChannelAllowlist.ChannelEntry> entries) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[ChannelSessionAllowlist] setForSession 忽略：sessionId 为空");
            return;
        }
        List<ChannelAllowlist.ChannelEntry> safe = entries == null ? List.of() : List.copyOf(entries);
        bySession.put(sessionId, safe);
        if (log.isDebugEnabled()) {
            log.debug("[ChannelSessionAllowlist] setForSession sessionId={} entries={}",
                sessionId, safe.stream().map(e -> e.kind() + ":" + e.name()).toList());
        }
    }

    /**
     * 读取指定会话的 --channels 白名单 · CC original: {@code getAllowedChannels()}
     * （state.ts:1676-1678）。未知会话 / 未写入 / 空 → 恒 {@link List#of()}（fail-closed 空表）。
     *
     * @param sessionId 目标 web 会话 id；null → 空表（无会话上下文 fail-closed）
     * @return 白名单条目（不可变）；绝不为 null
     */
    public List<ChannelAllowlist.ChannelEntry> getForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ChannelAllowlist.ChannelEntry> entries = bySession.get(sessionId);
        if (entries == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ChannelSessionAllowlist] getForSession sessionId={} → 空表（未写入，fail-closed）", sessionId);
            }
            return List.of();
        }
        if (log.isDebugEnabled()) {
            log.debug("[ChannelSessionAllowlist] getForSession sessionId={} → {} 条",
                sessionId, entries.size());
        }
        return entries;
    }

    /**
     * 清理指定会话的白名单（会话销毁清理缝）。
     *
     * <p>生命周期挂载点（会话销毁路径）当前无生产接线（09-open-decisions §S07 登记观察项）：
     * in-memory 注册表与 CC {@code STATE.allowedChannels} 同为进程内状态、无持久化，语义一致；
     * 清理缺失仅造成陈旧白名单滞留（不构成安全风险——白名单只影响是否注册，不影响 fail-closed 方向）。
     *
     * @param sessionId 目标 web 会话 id；null → no-op
     */
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<ChannelAllowlist.ChannelEntry> removed = bySession.remove(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[ChannelSessionAllowlist] clearSession sessionId={} 移除 {} 条",
                sessionId, removed != null ? removed.size() : 0);
        }
    }

    /**
     * 当前请求会话的白名单 supplier · CC original: {@code getAllowedChannels()} 消费点
     * （gate 门序[3 session]，channelNotification.ts:250）。
     *
     * <p>经 {@link RequestContext#sessionId()}（MDC）解析当前 web 请求的会话；无会话上下文
     * → 空表 fail-closed。{@link ChannelNotificationGate#setAllowedChannelsSupplier} 注入点
     * 的消费语义 = CC {@code STATE.allowedChannels} 进程内可读（McpServerService.start:542 /
     * startEnabledBatch:653 接线）。
     *
     * @return 当前会话白名单；无会话/未写入 → 空表（恒非 null）
     */
    public Supplier<List<ChannelAllowlist.ChannelEntry>> currentRequestSupplier() {
        return () -> getForSession(RequestContext.sessionId());
    }
}
