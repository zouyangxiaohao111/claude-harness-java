package com.nexusai.infra.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出站帧投递统计（OBS2）· <b>纯逻辑，无 Spring 依赖</b>（便于单测）。
 *
 * <p><b>WHY（2026-09-17 事故）</b>：用户遇「前端整屏停止更新」，事后无法回答「那一刻后端到底有没有把帧
 * 写给这个会话」—— STOMP simple broker 的 topic 非持久，帧推给零订阅者时静默丢弃，<b>既不抛异常也不留痕</b>，
 * 于是「后端没推」与「前端没渲染」在日志上无法分辨。本类把出站侧变成可计数、可对时的量：
 * 每帧经 {@code clientOutboundChannel} 放行时累计「帧数」，并记下「最后一次放行时刻」——
 * 前端心跳显示「JS 活着」时，后端这一行就能答「那一刻还在写帧吗」。
 *
 * <p><b>口径（诚实标注，别把 preSend 读成写完）</b>：
 * <ul>
 *   <li>{@link #recordSend} 由 {@code ChannelInterceptor.preSend} 调用 —— 是「帧被放行进出站通道」的时点，
 *       真正落到 socket 由传输层之后完成。<b>不是</b>「对端已收到」。</li>
 *   <li>{@link #recordFailure} 由 {@code afterSendCompletion} 的异常参数调用 —— 这一条才是「写出失败」。</li>
 * </ul>
 * 判据因此是「放行帧数在涨 = 后端侧没问题，问题在前端/网络」；「放行帧数不涨 = 根本没到通道这一层」。
 *
 * <p><b>有界性</b>：每会话一条记录，会话断开由 {@link #removeSession} 清理 —— 否则每次重连换一个
 * {@code simpSessionId}，长跑应用会无界增长。
 */
public final class OutboundDeliveryStats {

    /** 单会话计数（可变，由所属 map 保护）。 */
    public static final class SessionCount {
        private final String simpSessionId;
        /** 累计放行帧数（单调递增，永不重置）。 */
        private long framesSent;
        /** 本窗口放行帧数（{@link #drainSummary} 取差值后清零）。 */
        private long windowFrames;
        /** 累计写出异常次数。 */
        private long failures;
        /** 最后一次放行时刻（epoch ms；0 = 从未）。 */
        private long lastSendAt;
        /** 最后一次写出异常时刻（epoch ms；0 = 从未失败）。 */
        private long lastFailureAt;

        private SessionCount(String simpSessionId) {
            this.simpSessionId = simpSessionId;
        }

        public String simpSessionId() {
            return simpSessionId;
        }

        public long framesSent() {
            return framesSent;
        }

        public long failures() {
            return failures;
        }

        /** 最后一次放行时刻（epoch ms；0 = 从未放过帧）。 */
        public long lastSendAt() {
            return lastSendAt;
        }

        /** 最后一次写出异常时刻（epoch ms；0 = 从未失败）。 */
        public long lastFailureAt() {
            return lastFailureAt;
        }
    }

    /** simpSessionId → 计数（会话断开即 remove，规模 = 活连接数）。 */
    private final Map<String, SessionCount> sessions = new ConcurrentHashMap<>();

    /**
     * 记一帧经出站通道放行。
     *
     * @param simpSessionId STOMP 会话 id；{@code null}/空 → <b>静默跳过</b>（广播帧等非会话帧没有它，
     *                      不是错误，⛔ 绝不能因此 NPE 打断投递）
     * @param nowMs         事件时刻（epoch ms）· 由调用方传入，便于测试确定化
     */
    public void recordSend(String simpSessionId, long nowMs) {
        if (simpSessionId == null || simpSessionId.isEmpty()) {
            return;
        }
        SessionCount c = sessions.computeIfAbsent(simpSessionId, SessionCount::new);
        synchronized (c) {
            c.framesSent++;
            c.windowFrames++;
            c.lastSendAt = nowMs;
        }
    }

    /**
     * 记一次写出异常（传输层报错，帧没送达该会话）。
     *
     * @param simpSessionId STOMP 会话 id；{@code null}/空 → 静默跳过
     * @param nowMs         事件时刻（epoch ms）
     */
    public void recordFailure(String simpSessionId, long nowMs) {
        if (simpSessionId == null || simpSessionId.isEmpty()) {
            return;
        }
        SessionCount c = sessions.computeIfAbsent(simpSessionId, SessionCount::new);
        synchronized (c) {
            c.failures++;
            c.lastFailureAt = nowMs;
            // ⛔ 失败不推进 lastSendAt —— 那一位专指「成功放行」，混进去就把「还在写帧」读错了。
        }
    }

    /** 会话断开 → 丢弃其计数（防 simpSessionId 随重连无限累积）。 */
    public void removeSession(String simpSessionId) {
        if (simpSessionId == null || simpSessionId.isEmpty()) {
            return;
        }
        sessions.remove(simpSessionId);
    }

    /** 当前登记的会话数（= 有出站活动的活连接数）。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 取某会话的计数；无 → {@code null}。 */
    public SessionCount get(String simpSessionId) {
        return simpSessionId == null ? null : sessions.get(simpSessionId);
    }

    /**
     * 汇总本窗口并清零窗口计数（每 N 秒打一行日志用）。
     *
     * <p>格式（每会话一段）：{@code <simpSessionId>=+<本窗口帧>/总<累计帧>/[失败<次数>/]最后写出 <距今ms>ms 前}
     * —— 从未放过帧显示 {@code 最后写出 从未}。
     *
     * @param nowMs 汇总时刻（epoch ms）
     * @return 汇总串；<b>无任何登记会话 → 空串</b>（空闲应用不打噪声日志，由调用方判空跳过）
     */
    public String drainSummary(long nowMs) {
        if (sessions.isEmpty()) {
            return "";
        }
        List<SessionCount> ordered = new ArrayList<>(sessions.values());
        ordered.sort((a, b) -> a.simpSessionId.compareTo(b.simpSessionId));
        StringBuilder sb = new StringBuilder();
        for (SessionCount c : ordered) {
            long window;
            long failures;
            long last;
            long lastFailure;
            synchronized (c) {
                window = c.windowFrames;
                c.windowFrames = 0;
                failures = c.failures;
                last = c.lastSendAt;
                lastFailure = c.lastFailureAt;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.simpSessionId).append("=+").append(window).append("/总").append(c.framesSent);
            if (failures > 0) {
                sb.append("/失败").append(failures).append("(最后 ").append(nowMs - lastFailure).append("ms 前)");
            }
            sb.append("/最后写出 ").append(last == 0 ? "从未" : (nowMs - last) + "ms 前");
        }
        return sb.toString();
    }
}
