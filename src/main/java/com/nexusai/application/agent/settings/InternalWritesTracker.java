package com.nexusai.application.agent.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Internal Writes Tracker · 对齐 CC utils/settings/internalWrites.ts.
 *
 * <p>FIX-SETTINGS-INTWRITES: internal write mark/consume window.
 *
 * <p>L1 行为: 标记一个内部写 + 消费窗口 — settings 监听器忽略这些写避免回声.
 */
@Component
public class InternalWritesTracker {

    private static final Logger log = LoggerFactory.getLogger(InternalWritesTracker.class);
    private static final int WINDOW_MS = 1000;

    private final Deque<Mark> marks = new ArrayDeque<>();

    public synchronized void mark(String key) {
        marks.add(new Mark(key, System.currentTimeMillis()));
        // 清理过期
        long now = System.currentTimeMillis();
        marks.removeIf(m -> now - m.timestamp > WINDOW_MS);
    }

    public synchronized boolean consume(String key) {
        long now = System.currentTimeMillis();
        for (Mark m : marks) {
            if (m.key().equals(key) && now - m.timestamp <= WINDOW_MS) {
                marks.remove(m);
                log.debug("InternalWritesTracker: consume key={}", key);
                return true;
            }
        }
        return false;
    }

    private record Mark(String key, long timestamp) {}
}