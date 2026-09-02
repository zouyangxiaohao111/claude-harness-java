package com.nexusai.application.agent.team;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swarm Backend Detection · 对齐 CC utils/swarm/backends/detection.ts.
 *
 * <p>FIX-SWARM-BACKENDS: tmux/iTerm/it2 后端检测 (简化: 只返回 capability).
 *
 * <p>L1 行为: 给定环境 (env vars), 检测可用 backend.
 * 当前实现是 stub — 真实 tmux/iTerm 检测留 P1 接入.
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
@Component
public class SwarmDetection {

    public enum Backend { TMUX, ITERM2, IT2, IN_PROCESS, NONE }

    private final Map<String, Backend> cached = new ConcurrentHashMap<>();

    public Backend detect() {
        return cached.computeIfAbsent("default", k -> {
            if (System.getenv("TMUX") != null) return Backend.TMUX;
            if (System.getenv("ITERM_SESSION_ID") != null) return Backend.ITERM2;
            if (System.getenv("IT2_SESSION_ID") != null) return Backend.IT2;
            return Backend.IN_PROCESS;
        });
    }

    public boolean isAvailable(Backend backend) {
        return backend != Backend.NONE;
    }
}