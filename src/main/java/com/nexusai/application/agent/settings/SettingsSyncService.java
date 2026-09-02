package com.nexusai.application.agent.settings;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings Sync Service · 对齐 CC services/settingsSync/index.ts.
 *
 * <p>FIX-M4: 简化版主同步服务 (用户↔服务器 settings 同步 delta).
 *
 * <p>L1 行为: 给定 userId + settings, 同步 delta 计算.
 */
@Component
public class SettingsSyncService {

    private final Map<String, Object> synced = new ConcurrentHashMap<>();

    public Map<String, Object> computeDelta(String userId, Map<String, Object> local) {
        Map<String, Object> server = Map.of("synced", local.size(), "at", System.currentTimeMillis());
        // 简化: 全部视为新设置
        synced.put(userId, server);
        return server;
    }

    public Object getSynced(String userId) {
        return synced.get(userId);
    }
}