package com.nexusai.infra.util;

/**
 * IdleTimeoutConfig · 对齐 CC utils/idleTimeout.ts (config 解析 部分).
 *
 * <p>L1 语义: 解析 {@code CLAUDE_CODE_EXIT_AFTER_STOP_DELAY} 环境变量,得到 idle timeout 配置。
 * 决定 SDK 模式 idle 多久后自动退出 process。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: parseDelayMs(String env)→int (MAX_VALUE=未配置/无效;positive int=有效)</li>
 *   <li><b>A2 Golden Trace</b>: "30000"→30000;null→Integer.MAX_VALUE;"abc"→Integer.MAX_VALUE;"0"→Integer.MAX_VALUE;"-5"→Integer.MAX_VALUE;""→Integer.MAX_VALUE</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null/empty/非数字/≤0 → MAX_VALUE (未配置语义)</li>
 *   <li><b>A5 业务场景</b>: SDK 模式设置 30000 ms idle delay 后 30s 自动退出</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS parseInt + isNaN + >0 → Java Integer.parseInt + try/catch + positive check;
 * TS undefined → Java Integer.MAX_VALUE sentinel (caller 检查 isConfigured())。
 */
public final class IdleTimeoutConfig {

    public static final int UNCONFIGURED = Integer.MAX_VALUE;

    private IdleTimeoutConfig() {}

    /**
     * Returns the delay in milliseconds parsed from {@code CLAUDE_CODE_EXIT_AFTER_STOP_DELAY},
     * or {@link #UNCONFIGURED} if unset / unparseable / non-positive.
     */
    public static int parseDelayMs(String envValue) {
        if (envValue == null || envValue.isEmpty()) return UNCONFIGURED;
        int parsed;
        try {
            parsed = Integer.parseInt(envValue.trim());
        } catch (NumberFormatException e) {
            return UNCONFIGURED;
        }
        return parsed > 0 ? parsed : UNCONFIGURED;
    }

    /** Returns true iff the parsed delay represents a configured positive value. */
    public static boolean isConfigured(int delayMs) {
        return delayMs != UNCONFIGURED && delayMs > 0;
    }
}
