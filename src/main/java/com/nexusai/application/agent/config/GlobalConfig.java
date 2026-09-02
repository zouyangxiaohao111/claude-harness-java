package com.nexusai.application.agent.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global Config 总模块 · 对齐 CC utils/config.ts (1817 行).
 *
 * <p>FIX-UTIL-CONFIG: 简化版 global/project config 读写 + 迁移.
 *
 * <p>L1 行为: 给定 scope (global/project) + key, 读/写配置.
 */
@Component
public class GlobalConfig {

    public enum Scope { GLOBAL, PROJECT, USER }

    private final Map<String, Map<String, Object>> byScope = new ConcurrentHashMap<>();

    public Object get(Scope scope, String key) {
        Map<String, Object> map = byScope.get(scope.name());
        if (map == null) return null;
        return map.get(key);
    }

    public void set(Scope scope, String key, Object value) {
        byScope.computeIfAbsent(scope.name(), k -> new ConcurrentHashMap<>()).put(key, value);
    }

    public boolean migrate(String fromKey, String toKey) {
        Map<String, Object> global = byScope.get(Scope.GLOBAL.name());
        if (global == null) return false;
        Object value = global.get(fromKey);
        if (value == null) return false;
        global.put(toKey, value);
        global.remove(fromKey);
        return true;
    }

    public Map<String, Object> dump(Scope scope) {
        return Map.copyOf(byScope.getOrDefault(scope.name(), Map.of()));
    }
}