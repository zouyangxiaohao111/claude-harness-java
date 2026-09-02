package com.nexusai.application.agent.cli;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport Environment · 对齐 CC utils/teleport/ (955 行).
 *
 * <p>FIX-M13: 简化版远程环境选择/Git bundle 上传.
 *
 * <p>L1 行为: 列出可用环境 + 选择默认环境.
 */
@Component
public class TeleportEnvironment {

    public record Environment(String id, String name, String url, boolean isDefault) {}

    private final Map<String, Environment> environments = new ConcurrentHashMap<>();

    public Environment register(String id, String name, String url) {
        Environment env = new Environment(id, name, url, false);
        environments.put(id, env);
        return env;
    }

    public Environment setDefault(String id) {
        Environment env = environments.get(id);
        if (env == null) return null;
        Environment updated = new Environment(env.id(), env.name(), env.url(), true);
        environments.put(id, updated);
        return updated;
    }

    public List<Environment> list() {
        return List.copyOf(environments.values());
    }

    public Environment get(String id) {
        return environments.get(id);
    }

    /** 选择默认环境 (若无显式默认, 返回第一个). */
    public Environment selectDefault() {
        return environments.values().stream()
            .filter(Environment::isDefault)
            .findFirst()
            .orElse(environments.values().stream().findFirst().orElse(null));
    }
}