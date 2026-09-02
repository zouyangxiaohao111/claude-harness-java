package com.nexusai.application.agent.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings Change Detector · 对齐 CC utils/settings/changeDetector.ts.
 *
 * <p>FIX-SETTINGS-WATCH: settings 文件变更检测 + 订阅通知.
 *
 * <p>L1 行为: 检测 settings 配置变更 (基于 hash) 并通知订阅者.
 * 当前实现是内存版本; 真实 filesystem watch (inotify/FSEvents) 留 P1.
 */
@Component
public class SettingsChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(SettingsChangeDetector.class);

    private final Map<String, Integer> lastHashes = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.Consumer<String>> subscribers = new ConcurrentHashMap<>();

    /** 检查设置 hash 是否变更; 变更则通知 subscribers. */
    public void check(String key, String newContent) {
        int hash = newContent == null ? 0 : newContent.hashCode();
        Integer lastHash = lastHashes.get(key);
        if (lastHash == null || lastHash != hash) {
            lastHashes.put(key, hash);
            notify(key, newContent);
        }
    }

    public void subscribe(String key, java.util.function.Consumer<String> callback) {
        subscribers.put(key, callback);
    }

    public void unsubscribe(String key) {
        subscribers.remove(key);
    }

    private void notify(String key, String content) {
        java.util.function.Consumer<String> sub = subscribers.get(key);
        if (sub != null) {
            try {
                sub.accept(content);
            } catch (Exception e) {
                log.warn("SettingsChangeDetector: notify failed: {}", e.getMessage());
            }
        }
    }
}