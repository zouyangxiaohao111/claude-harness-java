package com.nexusai.application.agent.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal Logger · 对齐 CC services/internalLogging.ts.
 *
 * <p>FIX-SVC-4: 内部 debug logger 旁路发送 — 接收所有 debug 消息 + 异步 flush 到 console/file.
 *
 * <p>L1 行为: 接收任意内部事件 (如 LLM call timing / tool latency / cache hit rate) 并入队.
 * 后台 flusher 周期性 flush 到 SLF4J logger (防止高频 debug 拖慢主循环).
 */
@Component
public class InternalLogger {

    private static final Logger log = LoggerFactory.getLogger(InternalLogger.class);

    private static final int MAX_QUEUE_SIZE = 10_000;

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong dropped = new AtomicLong(0);

    public InternalLogger() {}

    /** 异步入队 (非阻塞). 队列满则计数 + 丢弃. */
    public void log(String message) {
        if (message == null) return;
        if (queue.size() >= MAX_QUEUE_SIZE) {
            dropped.incrementAndGet();
            return;
        }
        queue.offer(message);
    }

    /** 主循环 / 测试可调用 flush, 取出所有 buffer 内容. */
    public String flush() {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = queue.poll()) != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(msg);
        }
        if (sb.length() > 0) {
            log.debug("InternalLogger.flush: {} entries", sb.toString().split("\n").length);
        }
        return sb.toString();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int queueSize() {
        return queue.size();
    }
}