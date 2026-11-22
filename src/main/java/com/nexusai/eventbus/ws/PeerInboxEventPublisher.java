package com.nexusai.eventbus.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 跨会话待收件 · 聚合通道发布器 · {@code /topic/peer-inbox} 单 topic。
 *
 * <h2>seq 分配</h2>
 * <b>实例级</b> {@link AtomicLong}（1 起）：每次发布自增一次，与
 * {@link StreamEventSeq}（按 sessionId 的会话内序号）<b>无关</b>。进程重启后归零 —— 前端据此
 * 意识到「可能丢过帧」，调 {@code GET /api/v1/peer-inbox/counts} 对账（契约明示为预期行为）。
 *
 * <p><b>为什么 seq 在发布器里而不是在调用方</b>：seq 的定义是「本实例发布顺序」，只有发布动作
 * 自己知道顺序；调用方各自自增会出现重复/乱序。
 *
 * <h2>失败不伪装</h2>
 * 模板缺失 / {@code convertAndSend} 抛异常 ⇒ 只记日志并返回事件（调用方可日志观察），
 * ⛔ 不抛给调用方（上游是 hold 落库后的 best-effort 路径，发布失败不得回滚已落库行）。
 * 事件本身不是真源：DB + counts 接口才是。
 *
 * <p><b>测试</b>：{@link #forTest(Consumer)} 构造一个不依赖 Spring/STOMP 的实例，
 * 把事件交给内存 sink（同时仍然实例内自增 seq）。
 */
@Component
public class PeerInboxEventPublisher {

    /** 聚合 topic（单一 topic，推所有会话的事件）。 */
    public static final String TOPIC = "/topic/peer-inbox";

    private static final Logger log = LoggerFactory.getLogger(PeerInboxEventPublisher.class);

    private final AtomicLong seq = new AtomicLong();

    /** STOMP 模板；null = 无 STOMP 环境（非 Spring 直构 / 测试 sink 模式）。 */
    private final SimpMessagingTemplate template;

    /** 测试 sink（非 null 时替代 STOMP 模板；生产恒 null）。 */
    private final Consumer<PeerInboxAggregateEvent> testSink;

    @Autowired
    public PeerInboxEventPublisher(ObjectProvider<SimpMessagingTemplate> templates) {
        this(templates == null ? null : templates.getIfAvailable(), null);
    }

    /** 显式模板构造（测试/接线用；null = 只分配 seq 不发送）。 */
    public PeerInboxEventPublisher(SimpMessagingTemplate template) {
        this(template, null);
    }

    private PeerInboxEventPublisher(SimpMessagingTemplate template,
                                    Consumer<PeerInboxAggregateEvent> testSink) {
        this.template = template;
        this.testSink = testSink;
    }

    /** 测试/直构：事件交给内存 sink，不依赖 Spring。 */
    public static PeerInboxEventPublisher forTest(Consumer<PeerInboxAggregateEvent> sink) {
        return new PeerInboxEventPublisher(null, sink);
    }

    /** 本实例已分配的最大 seq（0 = 还没发过）；counts 接口用它做对账锚点。 */
    public long seq() {
        return seq.get();
    }

    /**
     * 发布一条聚合事件（seq 每次调用自增一次）。
     *
     * <p>seq 在<b>发送之前</b>分配：即使发送失败，序号也已消耗 —— 这样「seq 不连续」本身
     * 就是一条「有一帧可能丢了」的信号，而不是把失败帧的序号留给下一个事件（那样前端永远
     * 看不出缺口）。
     *
     * @param type            类型（{@link PeerInboxAggregateEvent#TYPE_HOLD} 等）
     * @param targetSessionId 归属会话
     * @param pendingCount    该会话当前「仍需处理」条数
     * @param item            仅 hold 携带（可 null）
     * @return 已分配序号的事件（永不 null）
     */
    public PeerInboxAggregateEvent publish(String type, String targetSessionId,
                                           long pendingCount, PeerInboxAggregateEvent.Item item) {
        PeerInboxAggregateEvent event = new PeerInboxAggregateEvent(
            seq.incrementAndGet(), type, targetSessionId, pendingCount, item);
        Consumer<PeerInboxAggregateEvent> sink = this.testSink;
        if (sink != null) {
            sink.accept(event);
            return event;
        }
        SimpMessagingTemplate ws = this.template;
        if (ws == null) {
            log.debug("PEER INBOX 聚合事件未发布：STOMP 模板不可用 type={} target={} seq={}"
                + "（DB/counts 仍是真源）", type, targetSessionId, event.getSeq());
            return event;
        }
        try {
            ws.convertAndSend(TOPIC, event);
            if (log.isDebugEnabled()) {
                log.debug("PEER INBOX 聚合事件已发布 type={} target={} seq={} pendingCount={}",
                    type, targetSessionId, event.getSeq(), pendingCount);
            }
        } catch (RuntimeException e) {
            log.warn("PEER INBOX 聚合事件发布失败（不伪装已发布）type={} target={} seq={} err={}",
                type, targetSessionId, event.getSeq(), e.toString());
        }
        return event;
    }
}
