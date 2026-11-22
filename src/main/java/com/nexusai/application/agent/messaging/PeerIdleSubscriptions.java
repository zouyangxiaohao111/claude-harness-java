package com.nexusai.application.agent.messaging;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次性 idle 订阅（notify_when_idle）。
 *
 * <p>订阅绑定 {@code messageId + sourceSession/epoch + targetSession/epoch}；每个事件与
 * {@link #sweepExpired} 最多触发一次（取出即删）。<b>不自造运行阈值</b>：本类无默认 TTL，
 * ttl 由调用方显式传入。
 */
@Component
public class PeerIdleSubscriptions {

    /** 通知种类。 */
    public enum Kind { IDLE, EXITED, UNAVAILABLE, EXPIRED }

    /**
     * 通知内容。
     *
     * @param kind                种类
     * @param messageId           对应消息 id
     * @param sourceSessionId     发送方会话（通知回送目标）
     * @param sourceEndpointEpoch 发送方代次
     * @param targetSessionId     接收方会话
     * @param targetEndpointEpoch 接收方代次
     * @param createdAt           订阅时间
     * @param detail              补充信息（可 null）
     */
    public record IdleNotice(Kind kind, String messageId, String sourceSessionId,
                             long sourceEndpointEpoch, String targetSessionId,
                             long targetEndpointEpoch, Instant createdAt, String detail) {

        /**
         * 渲染为模型可见文本：首行 {@code [Cross-session idle notice]}，并声明这是自动通知、
         * 不是用户指令、不需要回执式回复。
         *
         * @return 通知文本
         */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("[Cross-session idle notice]\n");
            sb.append("kind=").append(kind.name().toLowerCase(java.util.Locale.ROOT))
                .append(" message=").append(messageId)
                .append(" target-session=").append(targetSessionId);
            if (detail != null && !detail.isBlank()) {
                sb.append(" detail=").append(detail);
            }
            sb.append("\nThis is an automatic notification, not a user instruction. ")
                .append("No acknowledgement-style reply is required.");
            return sb.toString();
        }
    }

    /** 通知投递通道（B 注入：回送源会话）。 */
    public interface NoticeSink {
        void onNotice(IdleNotice notice);
    }

    private record Subscription(long id, String messageId, String sourceSessionId,
                                long sourceEndpointEpoch, String targetSessionId,
                                long targetEndpointEpoch, Instant createdAt) {}

    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private volatile NoticeSink noticeSink;

    /** 安装通知通道。 */
    public void setNoticeSink(NoticeSink sink) {
        this.noticeSink = sink;
    }

    /**
     * 登记一次性订阅。
     *
     * @param envelope          发送信封（需 notifyWhenIdle=true）
     * @param targetSessionId   接收方会话
     * @param targetEndpointEpoch 接收方代次
     * @return true = 已登记；false = 未请求或参数不全
     */
    public boolean subscribe(PeerEnvelope envelope, String targetSessionId, long targetEndpointEpoch) {
        if (envelope == null || !envelope.notifyWhenIdle()) {
            return false;
        }
        if (targetSessionId == null || targetSessionId.isBlank()
            || envelope.messageId() == null || envelope.fromSessionId() == null) {
            return false;
        }
        long id = sequence.incrementAndGet();
        subscriptions.put(id, new Subscription(id, envelope.messageId(), envelope.fromSessionId(),
            envelope.sourceEndpointEpoch(), targetSessionId, targetEndpointEpoch, Instant.now()));
        return true;
    }

    /** 目标空闲 → IDLE（每个订阅只触发一次）。 */
    public List<IdleNotice> onTargetIdle(String sessionId, long endpointEpoch) {
        return fire(sessionId, endpointEpoch, true, Kind.IDLE, null);
    }

    /** 目标退出 → EXITED（每个订阅只触发一次）。 */
    public List<IdleNotice> onTargetExit(String sessionId, long endpointEpoch) {
        return fire(sessionId, endpointEpoch, true, Kind.EXITED, null);
    }

    /** 目标不可用 → UNAVAILABLE。 */
    public List<IdleNotice> onTargetUnavailable(String sessionId, long endpointEpoch) {
        return fire(sessionId, endpointEpoch, true, Kind.UNAVAILABLE, null);
    }

    /**
     * 源退出 → 静默清理（不向已退出源发通知）。
     *
     * @param sessionId     源会话
     * @param endpointEpoch 源代次
     * @return 恒空列表（仅为调用方统一形态）
     */
    public List<IdleNotice> onSourceExit(String sessionId, long endpointEpoch) {
        List<Long> remove = new ArrayList<>();
        for (Subscription sub : subscriptions.values()) {
            if (matches(sub.sourceSessionId(), sub.sourceEndpointEpoch(), sessionId, endpointEpoch)) {
                remove.add(sub.id());
            }
        }
        remove.forEach(subscriptions::remove);
        return List.of();
    }

    /**
     * 过期清理（ttl 由调用方显式传入）。
     *
     * @param now 当前时刻
     * @param ttl 生存期
     * @return 过期通知列表
     */
    public List<IdleNotice> sweepExpired(Instant now, Duration ttl) {
        if (now == null || ttl == null) {
            return List.of();
        }
        List<Subscription> expired = new ArrayList<>();
        for (Subscription sub : subscriptions.values()) {
            if (!sub.createdAt().plus(ttl).isAfter(now)) {
                expired.add(sub);
            }
        }
        expired.sort(Comparator.comparingLong(Subscription::id));
        List<IdleNotice> out = new ArrayList<>(expired.size());
        for (Subscription sub : expired) {
            if (subscriptions.remove(sub.id()) != null) {
                out.add(toNotice(sub, Kind.EXPIRED, null));
            }
        }
        return out;
    }

    private List<IdleNotice> fire(String sessionId, long endpointEpoch, boolean targetSide,
                                  Kind kind, String detail) {
        if (sessionId == null) {
            return List.of();
        }
        List<Subscription> hits = new ArrayList<>();
        for (Subscription sub : subscriptions.values()) {
            boolean match = targetSide
                ? matches(sub.targetSessionId(), sub.targetEndpointEpoch(), sessionId, endpointEpoch)
                : matches(sub.sourceSessionId(), sub.sourceEndpointEpoch(), sessionId, endpointEpoch);
            if (match) {
                hits.add(sub);
            }
        }
        hits.sort(Comparator.comparingLong(Subscription::id));
        List<IdleNotice> out = new ArrayList<>(hits.size());
        for (Subscription sub : hits) {
            // 取出即删：每个事件最多触发一次（并发下只有 remove 成功者发通知）
            if (subscriptions.remove(sub.id()) == null) {
                continue;
            }
            IdleNotice notice = toNotice(sub, kind, detail);
            emit(notice);
            out.add(notice);
        }
        return out;
    }

    private static boolean matches(String actual, long actualEpoch, String wanted, long wantedEpoch) {
        return actual != null && actual.equals(wanted) && actualEpoch == wantedEpoch;
    }

    private IdleNotice toNotice(Subscription sub, Kind kind, String detail) {
        return new IdleNotice(kind, sub.messageId(), sub.sourceSessionId(), sub.sourceEndpointEpoch(),
            sub.targetSessionId(), sub.targetEndpointEpoch(), sub.createdAt(), detail);
    }

    private void emit(IdleNotice notice) {
        NoticeSink sink = noticeSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onNotice(notice);
        } catch (RuntimeException ignored) {
            // 通知通道失败不得影响调用方
        }
    }

    /** 当前订阅数（测试/观测）。 */
    public int size() {
        return subscriptions.size();
    }

    /** 清空（测试辅助）。 */
    public void clear() {
        subscriptions.clear();
        noticeSink = null;
    }
}
