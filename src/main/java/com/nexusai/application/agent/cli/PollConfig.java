package com.nexusai.application.agent.cli;

import org.springframework.stereotype.Component;

/**
 * Poll Config · 对齐 CC bridge/pollConfig.ts.
 *
 * <p>FIX-BRIDGE-10: bridge 轮询配置.
 */
@Component
public class PollConfig {

    public enum Mode { FIXED, EXPONENTIAL_BACKOFF }

    public record Config(Mode mode, long baseIntervalMs, long maxIntervalMs,
                         int maxAttempts) {}

    public Config defaultConfig() {
        return new Config(Mode.EXPONENTIAL_BACKOFF, 1000, 60_000, 5);
    }

    public Config reset() {
        return defaultConfig();
    }
}