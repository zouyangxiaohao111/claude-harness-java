package com.nexusai.infra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Mailbox · 对齐 CC utils/mailbox.ts.
 *
 * <p>L1 语义: 单线程 mailbox 抽象 — 子 agent 通过 {@code send(Message)} 投递消息,
 * 主线程通过 {@code poll(Predicate)} 或 {@code receive(Predicate)} 消费。
 * 内部维护 {@code _revision} counter 用于 React-style 公约;{@code subscribe} 接口用于订阅。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: Mailbox class + Message record + MessageSource enum (5 项) + send/poll/receive/subscribe 6 method</li>
 *   <li><b>A2 Golden Trace</b>: send 后 length=1;receive()匹配→resolve + 不入队;send 后 receive 不匹配→入队;waiter fn 匹配→直接 resolve 不入队</li>
 *   <li><b>A3 线程安全</b>: AtomicLong revision;ArrayList not thread-safe (CC 同样单线程)</li>
 *   <li><b>A4 边界</b>: poll 空→null;receive 无 waiter→等待;null msg 抛 NPE</li>
 *   <li><b>A5 业务场景</b>: teammate inbox message routing;system notification 订阅</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS class mutable array → Java ArrayList + AtomicLong revision;
 * TS Promise → Java CompletableFuture;
 * TS EventEmitter subscribe → Java Consumer&lt;Void&gt; listener list。
 */
public final class Mailbox {

    public enum MessageSource { user, teammate, system, tick, task }

    public record Message(
        String id,
        MessageSource source,
        String content,
        String from,
        String color,
        String timestamp) {}

    private static final Logger log = LoggerFactory.getLogger(Mailbox.class);

    private final List<Message> queue = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final List<Runnable> subscribers = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong();

    public int length() { return queue.size(); }
    public long revision() { return revision.get(); }

    /**
     * Send a message. If a registered waiter accepts the message (Predicate true),
     * resolve that waiter instead of queueing.
     *
     * <p>Fire-and-forget：不返回投递结果。CC original: {@code send(msg: Message): void}
     * (Open-ClaudeCode/src/utils/mailbox.ts:33)。投递到 waiter 还是入队是内部实现细节，
     * 发送方不得观察。
     */
    public void send(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        revision.incrementAndGet();
        for (int i = 0; i < waiters.size(); i++) {
            Waiter w = waiters.get(i);
            if (w.fn.test(msg)) {
                waiters.remove(i);
                w.future.complete(msg);
                if (log.isDebugEnabled()) {
                    log.debug("Mailbox.send 消息投递至 waiter 直接 resolve（不入队）：id={}, source={}, revision={}",
                        msg.id(), msg.source(), revision.get());
                }
                notifySubscribers();
                return;
            }
        }
        queue.add(msg);
        if (log.isDebugEnabled()) {
            log.debug("Mailbox.send 消息入队（无匹配 waiter）：id={}, source={}, queueSize={}, revision={}",
                msg.id(), msg.source(), queue.size(), revision.get());
        }
        notifySubscribers();
    }

    /**
     * Synchronous poll. Returns and removes the first message matching {@code fn}.
     */
    public Message poll(Predicate<Message> fn) {
        Predicate<Message> f = fn == null ? m -> true : fn;
        for (int i = 0; i < queue.size(); i++) {
            Message m = queue.get(i);
            if (f.test(m)) {
                queue.remove(i);
                notifySubscribers();
                return m;
            }
        }
        return null;
    }

    /**
     * Asynchronous receive. Returns immediately if a queued message matches; else
     * registers a waiter resolved when a future {@code send} matches.
     */
    public CompletableFuture<Message> receive(Predicate<Message> fn) {
        Predicate<Message> f = fn == null ? m -> true : fn;
        Message found = poll(f);
        if (found != null) return CompletableFuture.completedFuture(found);
        Waiter w = new Waiter(f);
        waiters.add(w);
        return w.future;
    }

    /** Subscribe to inbox notifications; returns unsubscribe Runnable. */
    public Runnable subscribe(Runnable listener) {
        if (listener == null) return () -> {};
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    private void notifySubscribers() {
        for (Runnable s : subscribers) {
            try { s.run(); } catch (RuntimeException ignored) {}
        }
    }

    private static final class Waiter {
        final Predicate<Message> fn;
        final CompletableFuture<Message> future = new CompletableFuture<>();
        Waiter(Predicate<Message> fn) { this.fn = fn; }
    }
}
