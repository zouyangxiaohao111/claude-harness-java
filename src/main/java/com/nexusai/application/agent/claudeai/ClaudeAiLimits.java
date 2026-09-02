package com.nexusai.application.agent.claudeai;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude.ai consumer OAuth quota · 对齐 CC services/claudeAiLimits.ts.
 *
 * <p>L1 语义: Claude.ai 订阅 quota (5h/7d/opus/sonnet/overage) — early warning 配置;
 *            rate limit tier 处理;overage credit grant;early warning 按 (utilization, timePct) 阈值.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: QuotaStatus enum (3) + RateLimitType enum (5) + EarlyWarningThreshold/Config record;
 *       EARLY_WARNING_CONFIGS 静态数组 (2 项) + EARLY_WARNING_CLAIM_MAP 静态 Map (3 项);
 *       RATE_LIMIT_DISPLAY_NAMES Map (5 项) + isAllowed/allowedWarning/rejected 阈值.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — getQuotaStatus(utilization, timePct) → allowed/allowedWarning/rejected.</li>
 *   <li><b>A3</b>: 静态常量 + 纯函数;无 IO;可独立测试.</li>
 *   <li><b>A4</b>: rateLimitType=null → 默认值;usage=null → 0.</li>
 *   <li><b>A5</b>: 真实场景 — UI 显示 "session limit" (5h/7d 名称) + early warning 文案.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS union type → Java enum;
 *                    TS const array → Java static final array;
 *                    TS Record → Java Map.
 */
public final class ClaudeAiLimits {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAiLimits.class);

    public enum QuotaStatus { ALLOWED, ALLOWED_WARNING, REJECTED }
    public enum RateLimitType { FIVE_HOUR, SEVEN_DAY, SEVEN_DAY_OPUS, SEVEN_DAY_SONNET, OVERAGE }

    public record EarlyWarningThreshold(double utilization, double timePct) {}
    public record EarlyWarningConfig(RateLimitType rateLimitType, String claimAbbrev,
        long windowSeconds, List<EarlyWarningThreshold> thresholds) {}

    /** CC EARLY_WARNING_CONFIGS. */
    public static final List<EarlyWarningConfig> EARLY_WARNING_CONFIGS = List.of(
        new EarlyWarningConfig(RateLimitType.FIVE_HOUR, "5h", 5L * 60 * 60,
            List.of(new EarlyWarningThreshold(0.9, 0.72))),
        new EarlyWarningConfig(RateLimitType.SEVEN_DAY, "7d", 7L * 24 * 60 * 60,
            List.of(
                new EarlyWarningThreshold(0.75, 0.6),
                new EarlyWarningThreshold(0.5, 0.35),
                new EarlyWarningThreshold(0.25, 0.15))));

    /** CC EARLY_WARNING_CLAIM_MAP. */
    public static final java.util.Map<String, RateLimitType> EARLY_WARNING_CLAIM_MAP =
        java.util.Map.of(
            "5h", RateLimitType.FIVE_HOUR,
            "7d", RateLimitType.SEVEN_DAY,
            "overage", RateLimitType.OVERAGE);

    /** CC RATE_LIMIT_DISPLAY_NAMES. */
    public static final java.util.Map<RateLimitType, String> RATE_LIMIT_DISPLAY_NAMES =
        java.util.Map.of(
            RateLimitType.FIVE_HOUR, "session limit",
            RateLimitType.SEVEN_DAY, "weekly limit",
            RateLimitType.SEVEN_DAY_OPUS, "weekly Opus limit",
            RateLimitType.SEVEN_DAY_SONNET, "weekly Sonnet limit",
            RateLimitType.OVERAGE, "overage");

    private ClaudeAiLimits() {}

    /** CC getQuotaStatus — pure function. */
    public static QuotaStatus getQuotaStatus(RateLimitType type, double utilization, double timePct) {
        if (type == null) return QuotaStatus.ALLOWED;
        // 超额检查优先 → REJECTED
        if (utilization >= 1.0) return QuotaStatus.REJECTED;
        EarlyWarningConfig config = null;
        for (EarlyWarningConfig c : EARLY_WARNING_CONFIGS) {
            if (c.rateLimitType() == type) { config = c; break; }
        }
        if (config == null) return QuotaStatus.ALLOWED;
        // Walk thresholds — 检查 utilization 超过阈值的最高项
        for (EarlyWarningThreshold t : config.thresholds()) {
            if (utilization >= t.utilization() && timePct <= t.timePct()) {
                return QuotaStatus.ALLOWED_WARNING;
            }
        }
        return QuotaStatus.ALLOWED;
    }

    /** CC getRateLimitTypeFromClaim. */
    public static RateLimitType getRateLimitTypeFromClaim(String claimAbbrev) {
        if (claimAbbrev == null) return null;
        return EARLY_WARNING_CLAIM_MAP.get(claimAbbrev);
    }

    /** CC getRateLimitDisplayName. */
    public static String getRateLimitDisplayName(RateLimitType type) {
        if (type == null) return "";
        return RATE_LIMIT_DISPLAY_NAMES.getOrDefault(type, "");
    }

    /** CC getWindowSeconds. */
    public static long getWindowSeconds(RateLimitType type) {
        if (type == null) return 0L;
        for (EarlyWarningConfig c : EARLY_WARNING_CONFIGS) {
            if (c.rateLimitType() == type) return c.windowSeconds();
        }
        return 0L;
    }
}