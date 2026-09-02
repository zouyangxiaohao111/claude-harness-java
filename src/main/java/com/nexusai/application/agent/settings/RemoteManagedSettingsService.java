package com.nexusai.application.agent.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Remote Managed Settings Service · 对齐 CC services/remoteManagedSettings/index.ts.
 *
 * <p>FIX-M3: 简化版主同步服务 (轮询 + 同步 settings).
 *
 * <p>L1 行为: 后台轮询 remote managed settings, 缓存 + 提供 get.
 */
@Component
public class RemoteManagedSettingsService {

    private static final Logger log = LoggerFactory.getLogger(RemoteManagedSettingsService.class);

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(1);

    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public void startBackgroundPolling(long intervalSeconds) {
        if (running) return;
        running = true;
        EXECUTOR.scheduleAtFixedRate(this::pollOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("RemoteManagedSettingsService: polling every {}s", intervalSeconds);
    }

    public void stopBackgroundPolling() {
        running = false;
    }

    public void pollOnce() {
        // 真实实现需要 HTTP 拉取; 当前是 no-op
        log.trace("RemoteManagedSettingsService: poll (stub)");
    }

    public Object get(String key) {
        return cache.get(key);
    }

    public boolean isEligible(String orgId) {
        // 简化版: 所有 org 都 eligible
        return true;
    }
}