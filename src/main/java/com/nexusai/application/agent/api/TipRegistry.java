package com.nexusai.application.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tips registry (eligibility-driven) · 对齐 CC services/tips/tipRegistry.ts.
 *
 * <p>L1 语义: 决定哪些 tip 显示 — based on user subscription, environment,
 *            1P API customer status, terminal setup status, overage credit upsell.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: TipCategory enum (5); TipEntry record; 3 method (getRelevantTips/
 *       isEligibleForTip/markShown); MAX_TIPS=3.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — isEligibleForTip(category) → check 1P/subscription/environment →
 *       return true/false;getRelevantTips → filter eligible + top MAX_TIPS.</li>
 *   <li><b>A3</b>: 注入式 (1pApiCustomerSupplier + subscriptionSupplier + terminalSetupSupplier);pure functions.</li>
 *   <li><b>A4</b>: subscriber false → no tip;already shown → skip.</li>
 *   <li><b>A5</b>: 真实场景 — Claude.ai Pro 用户启动 → show 1 个 onboarding tip.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS enum union → Java enum;
 *                    TS conditional render → Java Supplier 注入式;
 *                    TS set + filter → Java List + stream.
 */
public final class TipRegistry {

    private static final Logger log = LoggerFactory.getLogger(TipRegistry.class);

    public static final int MAX_TIPS = 3;

    public enum TipCategory {
        ONBOARDING, OVERAGE_CREDIT, TERMINAL_SETUP, KAIROS_CRON, FILE_HISTORY
    }

    public record TipEntry(
        TipCategory category, String message, String ctaText, String ctaAction,
        Map<String, Object> metadata, long shownAt) {}

    private final BooleanSupplier is1PApiCustomerSupplier;
    private final BooleanSupplier isSubscriberSupplier;
    private final BooleanSupplier terminalSetupOfferedSupplier;
    private final BooleanSupplier overageCreditUpsellSupplier;
    private final BooleanSupplier kairosCronEnabledSupplier;
    private final BooleanSupplier fileHistoryEnabledSupplier;

    public TipRegistry(BooleanSupplier is1PApiCustomerSupplier,
            BooleanSupplier isSubscriberSupplier,
            BooleanSupplier terminalSetupOfferedSupplier,
            BooleanSupplier overageCreditUpsellSupplier,
            BooleanSupplier kairosCronEnabledSupplier,
            BooleanSupplier fileHistoryEnabledSupplier) {
        this.is1PApiCustomerSupplier = is1PApiCustomerSupplier == null ? () -> false : is1PApiCustomerSupplier;
        this.isSubscriberSupplier = isSubscriberSupplier == null ? () -> false : isSubscriberSupplier;
        this.terminalSetupOfferedSupplier = terminalSetupOfferedSupplier == null ? () -> false : terminalSetupOfferedSupplier;
        this.overageCreditUpsellSupplier = overageCreditUpsellSupplier == null ? () -> false : overageCreditUpsellSupplier;
        this.kairosCronEnabledSupplier = kairosCronEnabledSupplier == null ? () -> false : kairosCronEnabledSupplier;
        this.fileHistoryEnabledSupplier = fileHistoryEnabledSupplier == null ? () -> false : fileHistoryEnabledSupplier;
    }

    public TipRegistry() {
        this(null, null, null, null, null, null);
    }

    /** CC isEligibleForTip 纯函数. */
    public boolean isEligibleForTip(TipCategory category) {
        if (category == null) return false;
        return switch (category) {
            case ONBOARDING -> Boolean.TRUE.equals(isSubscriberSupplier.getAsBoolean());
            case OVERAGE_CREDIT -> Boolean.TRUE.equals(overageCreditUpsellSupplier.getAsBoolean())
                && Boolean.TRUE.equals(isSubscriberSupplier.getAsBoolean());
            case TERMINAL_SETUP -> !Boolean.TRUE.equals(terminalSetupOfferedSupplier.getAsBoolean());
            case KAIROS_CRON -> Boolean.TRUE.equals(kairosCronEnabledSupplier.getAsBoolean())
                && Boolean.TRUE.equals(is1PApiCustomerSupplier.getAsBoolean());
            case FILE_HISTORY -> Boolean.TRUE.equals(fileHistoryEnabledSupplier.getAsBoolean());
        };
    }

    /** CC getRelevantTips — 过滤 + top MAX_TIPS. */
    public List<TipEntry> getRelevantTips(List<TipEntry> allTips) {
        if (allTips == null || allTips.isEmpty()) return List.of();
        java.util.List<TipEntry> eligible = new java.util.ArrayList<>();
        for (TipEntry tip : allTips) {
            if (isEligibleForTip(tip.category())) eligible.add(tip);
        }
        if (eligible.size() <= MAX_TIPS) return eligible;
        return eligible.subList(0, MAX_TIPS);
    }

    public TipEntry markShown(TipEntry tip) {
        if (tip == null) return null;
        return new TipEntry(tip.category(), tip.message(), tip.ctaText(), tip.ctaAction(),
            tip.metadata(), System.currentTimeMillis());
    }
}