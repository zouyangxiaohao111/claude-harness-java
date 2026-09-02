package com.nexusai.application.agent.system;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流消息中心 · 对齐 CC services/rateLimitMessages.ts.
 *
 * <p>L1 语义: 所有 rate limit 相关消息的单一来源.
 *            - RATE_LIMIT_ERROR_PREFIXES: 5 种错误消息前缀 (UI 组件用 startsWith 检测)
 *            - isRateLimitErrorMessage(text) → boolean
 *            - getRateLimitMessage(limits, model) → RateLimitMessage | null (error 或 warning)
 *            - getRateLimitErrorMessage/getRateLimitWarning 分别只返 error/warning
 *            - getUsingOverageText(limits) → 通知文本
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: RATE_LIMIT_ERROR_PREFIXES 5 常量;RateLimitMessage 2 字段 (message/severity);
 *       ClaudeAILimits 最小子集 (status/utilization/resetsAt/overageStatus/overageResetsAt/
 *       overageDisabledReason/rateLimitType/isUsingOverage);
 *       4 个公开函数 (isRateLimitErrorMessage/getRateLimitMessage/
 *       getRateLimitErrorMessage/getRateLimitWarning/getUsingOverageText).</li>
 *   <li><b>A2 Golden Trace</b>: getRateLimitMessage 主链:
 *       isUsingOverage+overageStatus=allowed_warning → warning;
 *       status=rejected → error (含 reset time);
 *       status=allowed_warning + utilization>=0.7 → warning (跳过 team/enterprise+overage 用户);
 *       else → null.</li>
 *   <li><b>A3</b>: 状态: NO_MESSAGE (null) / ERROR (severity=error) / WARNING (severity=warning);
 *       WARNING_THRESHOLD=0.7 防止 stale low-usage 误报.</li>
 *   <li><b>A4</b>: status=rejected + overageStatus=rejected + overageDisabledReason='out_of_credits' →
 *       "You're out of extra usage...";
 *       team/enterprise + extraUsage + 无 billingAccess → allowed_warning 跳过 (无消息);
 *       utilization < 0.7 → null (避免 stale 误报).</li>
 *   <li><b>A5</b>: 真实场景 — 用户接近 weekly limit (utilization=0.85) →
 *       warning "You've used 85% of your weekly limit · resets [time]";
 *       team user + extra usage enabled → 无 warning (seamless roll into overage).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `getSubscriptionType()` → 注入式 Supplier;
 *                    TS `getOauthAccountInfo()?.hasExtraUsageEnabled` → Supplier;
 *                    TS `hasClaudeAiBillingAccess()` → BooleanSupplier;
 *                    TS `isOverageProvisioningAllowed()` → BooleanSupplier;
 *                    TS `formatResetTime(date)` → 注入式 Function;
 *                    TS `process.env.USER_TYPE` → 注入式 Supplier.
 */
public final class RateLimitMessages {

    private static final Logger log = LoggerFactory.getLogger(RateLimitMessages.class);
    public static final double WARNING_THRESHOLD = 0.7;

    public static final String RATE_LIMIT_PREFIX_HIT = "You've hit your";
    public static final String RATE_LIMIT_PREFIX_USED = "You've used";
    public static final String RATE_LIMIT_PREFIX_OVERAGE = "You're now using extra usage";
    public static final String RATE_LIMIT_PREFIX_CLOSE = "You're close to";
    public static final String RATE_LIMIT_PREFIX_OUT_OF = "You're out of extra usage";

    public static final List<String> RATE_LIMIT_ERROR_PREFIXES = List.of(
        RATE_LIMIT_PREFIX_HIT,
        RATE_LIMIT_PREFIX_USED,
        RATE_LIMIT_PREFIX_OVERAGE,
        RATE_LIMIT_PREFIX_CLOSE,
        RATE_LIMIT_PREFIX_OUT_OF
    );

    public static final String FEEDBACK_CHANNEL_ANT = "#briarpatch-cc";

    private final Supplier<String> subscriptionTypeSupplier;
    private final Supplier<OauthAccountInfo> oauthAccountSupplier;
    private final BooleanSupplier billingAccessSupplier;
    private final BooleanSupplier overageProvisioningSupplier;
    private final ResetTimeFormatter resetTimeFormatter;
    private final Supplier<String> userTypeSupplier;

    public RateLimitMessages(Supplier<String> subscriptionTypeSupplier,
                              Supplier<OauthAccountInfo> oauthAccountSupplier,
                              BooleanSupplier billingAccessSupplier,
                              BooleanSupplier overageProvisioningSupplier,
                              ResetTimeFormatter resetTimeFormatter,
                              Supplier<String> userTypeSupplier) {
        this.subscriptionTypeSupplier = Objects.requireNonNull(subscriptionTypeSupplier);
        this.oauthAccountSupplier = Objects.requireNonNull(oauthAccountSupplier);
        this.billingAccessSupplier = Objects.requireNonNull(billingAccessSupplier);
        this.overageProvisioningSupplier = Objects.requireNonNull(overageProvisioningSupplier);
        this.resetTimeFormatter = Objects.requireNonNull(resetTimeFormatter);
        this.userTypeSupplier = Objects.requireNonNull(userTypeSupplier);
    }

    /** Rate limit message. */
    public record RateLimitMessage(String message, String severity) {}

    /** Claude.ai limits 最小子集. */
    public record ClaudeAILimits(
        String status,                       // 'rejected' | 'allowed_warning' | other
        Double utilization,                  // 0.0-1.0
        String resetsAt,                     // ISO date string
        String overageStatus,                // 'allowed_warning' | 'rejected' | other
        String overageResetsAt,
        String overageDisabledReason,        // 'out_of_credits' | other
        String rateLimitType,                // 'seven_day' | 'five_hour' | 'seven_day_opus' | 'seven_day_sonnet' | 'overage'
        Boolean isUsingOverage
    ) {}

    /** OAuth account info. */
    public record OauthAccountInfo(boolean hasExtraUsageEnabled) {
        public static final OauthAccountInfo EMPTY = new OauthAccountInfo(false);
    }

    /** Reset time formatter (注入). */
    @FunctionalInterface
    public interface ResetTimeFormatter { String format(String date, boolean relative); }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** CC isRateLimitErrorMessage. */
    public boolean isRateLimitErrorMessage(String text) {
        if (text == null) return false;
        for (String prefix : RATE_LIMIT_ERROR_PREFIXES) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    /** CC getRateLimitMessage — 主链. */
    public RateLimitMessage getRateLimitMessage(ClaudeAILimits limits, String model) {
        // 1. Overage scenarios first
        if (Boolean.TRUE.equals(limits.isUsingOverage())) {
            if ("allowed_warning".equals(limits.overageStatus())) {
                return new RateLimitMessage(
                    "You're close to your extra usage spending limit", "warning");
            }
            return null;
        }

        // 2. Rejected → error
        if ("rejected".equals(limits.status())) {
            return new RateLimitMessage(getLimitReachedText(limits, model), "error");
        }

        // 3. allowed_warning → warning (with threshold + team/enterprise skip)
        if ("allowed_warning".equals(limits.status())) {
            if (limits.utilization() != null && limits.utilization() < WARNING_THRESHOLD) {
                return null;  // stale low-usage
            }
            String subscriptionType = subscriptionTypeSupplier.get();
            boolean isTeamOrEnterprise = "team".equals(subscriptionType)
                || "enterprise".equals(subscriptionType);
            OauthAccountInfo account = oauthAccountSupplier.get();
            boolean hasExtraUsageEnabled = account != null && account.hasExtraUsageEnabled();
            if (isTeamOrEnterprise && hasExtraUsageEnabled && !billingAccessSupplier.getAsBoolean()) {
                return null;
            }
            String text = getEarlyWarningText(limits);
            if (text != null) {
                return new RateLimitMessage(text, "warning");
            }
        }

        return null;
    }

    public String getRateLimitErrorMessage(ClaudeAILimits limits, String model) {
        RateLimitMessage m = getRateLimitMessage(limits, model);
        if (m != null && "error".equals(m.severity())) {
            return m.message();
        }
        return null;
    }

    public String getRateLimitWarning(ClaudeAILimits limits, String model) {
        RateLimitMessage m = getRateLimitMessage(limits, model);
        if (m != null && "warning".equals(m.severity())) {
            return m.message();
        }
        return null;
    }

    /** CC getLimitReachedText — error message builder. */
    String getLimitReachedText(ClaudeAILimits limits, String model) {
        String resetTime = limits.resetsAt() != null
            ? resetTimeFormatter.format(limits.resetsAt(), true) : null;
        String overageResetTime = limits.overageResetsAt() != null
            ? resetTimeFormatter.format(limits.overageResetsAt(), true) : null;
        String resetMessage = resetTime != null ? " · resets " + resetTime : "";

        // Both subscription AND overage rejected
        if ("rejected".equals(limits.overageStatus())) {
            String overageResetMessage = "";
            if (limits.resetsAt() != null && limits.overageResetsAt() != null) {
                // Use earlier one
                if (limits.resetsAt().compareTo(limits.overageResetsAt()) < 0) {
                    overageResetMessage = " · resets " + resetTime;
                } else {
                    overageResetMessage = " · resets " + (overageResetTime != null ? overageResetTime : "");
                }
            } else if (resetTime != null) {
                overageResetMessage = " · resets " + resetTime;
            } else if (overageResetTime != null) {
                overageResetMessage = " · resets " + overageResetTime;
            }
            if ("out_of_credits".equals(limits.overageDisabledReason())) {
                return "You're out of extra usage" + overageResetMessage;
            }
            return formatLimitReachedText("limit", overageResetMessage, model);
        }

        if ("seven_day_sonnet".equals(limits.rateLimitType())) {
            String st = subscriptionTypeSupplier.get();
            boolean isProOrEnterprise = "pro".equals(st) || "enterprise".equals(st);
            String limit = isProOrEnterprise ? "weekly limit" : "Sonnet limit";
            return formatLimitReachedText(limit, resetMessage, model);
        }
        if ("seven_day_opus".equals(limits.rateLimitType())) {
            return formatLimitReachedText("Opus limit", resetMessage, model);
        }
        if ("seven_day".equals(limits.rateLimitType())) {
            return formatLimitReachedText("weekly limit", resetMessage, model);
        }
        if ("five_hour".equals(limits.rateLimitType())) {
            return formatLimitReachedText("session limit", resetMessage, model);
        }
        return formatLimitReachedText("usage limit", resetMessage, model);
    }

    /** CC getEarlyWarningText — warning message builder. */
    String getEarlyWarningText(ClaudeAILimits limits) {
        String limitName = null;
        switch (limits.rateLimitType() == null ? "" : limits.rateLimitType()) {
            case "seven_day":       limitName = "weekly limit"; break;
            case "five_hour":       limitName = "session limit"; break;
            case "seven_day_opus":  limitName = "Opus limit"; break;
            case "seven_day_sonnet": limitName = "Sonnet limit"; break;
            case "overage":         limitName = "extra usage"; break;
            default:                return null;
        }

        Integer used = limits.utilization() != null
            ? (int) Math.floor(limits.utilization() * 100) : null;
        String resetTime = limits.resetsAt() != null
            ? resetTimeFormatter.format(limits.resetsAt(), true) : null;
        String upsell = getWarningUpsellText(limits.rateLimitType());

        if (used != null && resetTime != null) {
            String base = "You've used " + used + "% of your " + limitName + " · resets " + resetTime;
            return upsell != null ? base + " · " + upsell : base;
        }
        if (used != null) {
            String base = "You've used " + used + "% of your " + limitName;
            return upsell != null ? base + " · " + upsell : base;
        }
        if ("overage".equals(limits.rateLimitType())) {
            limitName += " limit";
        }
        if (resetTime != null) {
            String base = "Approaching " + limitName + " · resets " + resetTime;
            return upsell != null ? base + " · " + upsell : base;
        }
        String base = "Approaching " + limitName;
        return upsell != null ? base + " · " + upsell : base;
    }

    String getWarningUpsellText(String rateLimitType) {
        String st = subscriptionTypeSupplier.get();
        OauthAccountInfo account = oauthAccountSupplier.get();
        boolean hasExtraUsageEnabled = account != null && account.hasExtraUsageEnabled();

        if ("five_hour".equals(rateLimitType)) {
            if ("team".equals(st) || "enterprise".equals(st)) {
                if (!hasExtraUsageEnabled && overageProvisioningSupplier.getAsBoolean()) {
                    return "/extra-usage to request more";
                }
                return null;
            }
            if ("pro".equals(st) || "max".equals(st)) {
                return "/upgrade to keep using NexusAI";
            }
        }
        if ("overage".equals(rateLimitType)) {
            if ("team".equals(st) || "enterprise".equals(st)) {
                if (!hasExtraUsageEnabled && overageProvisioningSupplier.getAsBoolean()) {
                    return "/extra-usage to request more";
                }
            }
        }
        return null;
    }

    /** CC getUsingOverageText — overage notification. */
    public String getUsingOverageText(ClaudeAILimits limits) {
        String resetTime = limits.resetsAt() != null
            ? resetTimeFormatter.format(limits.resetsAt(), true) : "";

        String limitName = "";
        if ("five_hour".equals(limits.rateLimitType())) {
            limitName = "session limit";
        } else if ("seven_day".equals(limits.rateLimitType())) {
            limitName = "weekly limit";
        } else if ("seven_day_opus".equals(limits.rateLimitType())) {
            limitName = "Opus limit";
        } else if ("seven_day_sonnet".equals(limits.rateLimitType())) {
            String st = subscriptionTypeSupplier.get();
            boolean isProOrEnterprise = "pro".equals(st) || "enterprise".equals(st);
            limitName = isProOrEnterprise ? "weekly limit" : "Sonnet limit";
        }
        if (limitName.isEmpty()) {
            return "Now using extra usage";
        }
        String resetMessage = !resetTime.isEmpty()
            ? " · Your " + limitName + " resets " + resetTime : "";
        return "You're now using extra usage" + resetMessage;
    }

    String formatLimitReachedText(String limit, String resetMessage, String model) {
        if ("ant".equals(userTypeSupplier.get())) {
            return "You've hit your " + limit + resetMessage
                + ". If you have feedback about this limit, post in " + FEEDBACK_CHANNEL_ANT
                + ". You can reset your limits with /reset-limits";
        }
        return "You've hit your " + limit + resetMessage;
    }
}
