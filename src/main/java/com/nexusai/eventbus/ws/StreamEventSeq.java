package com.nexusai.eventbus.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流事件**会话内单调序号**分配器 · 对齐 dsh `SessionEvent.seq`（runtime/src/client/sessions/session.ts:679-733）。
 *
 * <p>WHY：会话流是「一次性推送 + 非持久 topic」——帧推给零订阅者或中途丢一帧，前端无从得知，
 * 只能等用户 F5。dsh 用 seq 让前端能做**缺口检测**：收到 seq 大于「已见最大 + 1」即判定丢帧，
 * 缓冲后续事件并重拉尾页补齐（`acceptLiveEvent` → `repairGap`）。
 *
 * <p>口径：按 {@code sessionId} 各自独立递增（1 起）。跨会话无需可比；同会话内严格 +1。
 * 事件已带 seq 时幂等返回（不覆盖）。sessionId 为空（无会话广播/测试直构）→ 不分配（前端跳过该帧）。
 *
 * <p>语义边界：只对**发送到会话 stream topic 的事件**调用（chunk / tool_call / tool_result /
 * usage / complete / insert / boundary / error / cancelled）。权限、状态、标题等旁路 topic 不参与。
 */
public final class StreamEventSeq {

    private static final Logger log = LoggerFactory.getLogger(StreamEventSeq.class);

    /** sessionId → 下一个待分配序号（已分配过的最大值）。 */
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private StreamEventSeq() {}

    /**
     * 给事件分配会话内单调序号（幂等：seq 已 > 0 原样返回）。
     *
     * @param event 待发送事件（null → null）
     * @param <T>   事件类型
     * @return 同一对象（已盖章），便于内联 `convertAndSend(topic, StreamEventSeq.stamp(e))`
     */
    public static <T extends StreamEvent> T stamp(T event) {
        if (event == null) {
            return null;
        }
        if (event.getSeq() > 0) {
            return event;
        }
        String sid = event.getSessionId();
        if (sid == null || sid.isBlank()) {
            return event;   // 无会话归属（如全局广播）→ 不参与缺口检测
        }
        long seq = COUNTERS.computeIfAbsent(sid, k -> new AtomicLong()).incrementAndGet();
        event.setSeq(seq);
        return event;
    }

    /**
     * 统一发送入口：先盖会话内序号再投递（流事件必走这里，保证前端可做缺口检测）。
     *
     * <p>替换 `ws.convertAndSend(topic, evt)` 一行即可，不改变事件构造与 topic 语义。
     * ws/evt 为 null → 静默跳过（与既有 null 守卫同向）。
     */
    public static void send(org.springframework.messaging.simp.SimpMessagingTemplate ws,
                            String topic, StreamEvent evt) {
        if (ws == null || evt == null) {
            return;
        }
        ws.convertAndSend(topic, stamp(evt));
    }

    /** 会话删除/测试清理（防 map 无限增长）。 */
    public static void reset(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        COUNTERS.remove(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[StreamEventSeq] 清理会话序号 session={}", sessionId);
        }
    }

    /** 当前已分配的最大序号（诊断/测试用）。 */
    public static long lastSeq(String sessionId) {
        AtomicLong c = sessionId == null ? null : COUNTERS.get(sessionId);
        return c == null ? 0L : c.get();
    }
}
