package com.nexusai.application.agent.cli;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Upstream Proxy · 对齐 CC upstreamproxy/upstreamproxy.ts + relay.ts.
 *
 * <p>FIX-M15: 简化版上游代理 (HTTP relay).
 *
 * <p>L1 行为: 注册代理目标 + 中继请求 (transparent pass-through).
 */
@Component
public class UpstreamProxy {

    public record ProxyTarget(String name, String upstream, String local) {}

    public record ProxyResult(int status, String body, Map<String, String> headers) {}

    private final Map<String, ProxyTarget> targets = new ConcurrentHashMap<>();

    public ProxyTarget register(String name, String upstream, String local) {
        ProxyTarget t = new ProxyTarget(name, upstream, local);
        targets.put(name, t);
        return t;
    }

    /** 中继: 实际 HTTP 转发留 P1 集成 OkHttp. */
    public ProxyResult relay(String name, String path, String method, String body) {
        ProxyTarget target = targets.get(name);
        if (target == null) return new ProxyResult(404, "unknown proxy", Map.of());
        // 简化: 直接返回 200 + 目标 URL 信息
        return new ProxyResult(200, "relayed to " + target.upstream() + path,
            Map.of("X-Proxy-Target", target.upstream()));
    }

    public ProxyTarget get(String name) {
        return targets.get(name);
    }
}