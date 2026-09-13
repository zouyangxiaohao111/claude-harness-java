package com.nexusai.application.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>会话解析：{@link #sessionLookup()} 返回 <b>sessionId → 白名单</b> 的显式查表器，
 * 由调用方（{@link ChannelNotificationGate#gateChannelServer} 的第 4 参 sessionId）显式传入会话；
 * 无会话（null/blank）→ 空表 fail-closed。
 *
 * <p><b>[批 3b]</b> 旧实现 {@code currentRequestSupplier()} 返回的 Supplier 在求值时读
 * 裸 MDC 的 {@code sessionId()}（SLF4J MDC / ThreadLocal）——被 gate 在 connectWorker 池线程
 * 求值，MDC 恒 null 或读到该池线程上一个任务残留的别会话 id（靠 McpToolPool 的 MDC 回放才「看起来
 * 工作」）；用户铁律「会话态一律显式传参，回放不算合规」⇒ 改为显式 sessionId 查表。
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
     * 显式会话查表器 · CC original: {@code getAllowedChannels()} 消费点
     * （gate 门序[3 session]，channelNotification.ts:250）。
     *
     * <p>返回 {@code sessionId → 白名单} 的纯函数（无隐式源、无 ThreadLocal、无回放）：
     * 会话由调用方显式传入（{@link ChannelNotificationGate#gateChannelServer} 第 4 参）；
     * null/blank/未写入 → 空表 fail-closed。注入点消费语义 = CC {@code STATE.allowedChannels}
     * 进程内可读（McpServerService.start / startEnabledBatch 接线）。
     *
     * @return sessionId → 该会话白名单（恒非 null 列表）
     */
    public java.util.function.Function<String, List<ChannelAllowlist.ChannelEntry>> sessionLookup() {
        return this::getForSession;
    }
}
