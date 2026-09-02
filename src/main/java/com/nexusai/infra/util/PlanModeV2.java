package com.nexusai.infra.util;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * PlanModeV2 · 对齐 CC utils/planModeV2.ts.
 *
 * <p>L1 语义: plan mode v2 agent 数量 + interview phase flag + pewter ledger variant。
 * <ul>
 *   <li>{@link #getPlanModeV2AgentCount} — env override 优先,sub tier 决定</li>
 *   <li>{@link #getPlanModeV2ExploreAgentCount} — env override,默认 3</li>
 *   <li>{@link #isPlanModeInterviewPhaseEnabled} — ant always-on + env + GB gate</li>
 *   <li>{@link #getPewterLedgerVariant} — trim/cut/cap or null</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 静态方法 + envVar suppliers 注入式 (testable)</li>
 *   <li><b>A2 Golden Trace</b>: env count=5→5;max+default_claude_max_20x→3;enterprise/team→3;default→1;interviewPhase ant→true;pewter raw 'trim'→'trim' else null</li>
 *   <li><b>A3 纯函数</b>: 同 input→同 output (cached or supplier pure)</li>
 *   <li><b>A4 边界</b>: env non-numeric→fallback;env >10→fallback;pewter raw invalid→null</li>
 *   <li><b>A5 业务场景</b>: plan mode v2 启动 plan_explore_agent_count=3 (env override or default);interview phase ant always-on</li>
 * </ul>
 *
 * <p>L3 升级: TS process.env indexed → Java Supplier 注入式;
 * TS GrowthBook cache → Java Supplier (caller wired);
 * TS type literal 'max'|'enterprise'|'team' → Java String equality.
 */
public final class PlanModeV2 {

    public static final int MIN_AGENT_COUNT = 1;
    public static final int MAX_AGENT_COUNT = 10;
    public static final int DEFAULT_AGENT_COUNT = 1;
    public static final int EXPLORE_AGENT_COUNT_DEFAULT = 3;
    public static final int MAX_ENTERPRISE_AGENT_COUNT = 3;

    public static final String MAX_20X_TIER = "default_claude_max_20x";

    private PlanModeV2() {}

    public static int getPlanModeV2AgentCount(
        Supplier<String> agentCountEnvSupplier,
        Supplier<String> subscriptionTypeSupplier,
        Supplier<String> rateLimitTierSupplier,
        IntSupplier parseIntFn) {
        String env = agentCountEnvSupplier == null ? null : agentCountEnvSupplier.get();
        if (env != null && !env.isEmpty()) {
            try {
                int count = parseIntFn.getAsInt();
                if (count >= MIN_AGENT_COUNT && count <= MAX_AGENT_COUNT) {
                    return count;
                }
            } catch (RuntimeException ignored) {}
        }
        String sub = subscriptionTypeSupplier == null ? null : subscriptionTypeSupplier.get();
        String tier = rateLimitTierSupplier == null ? null : rateLimitTierSupplier.get();
        if ("max".equals(sub) && MAX_20X_TIER.equals(tier)) {
            return MAX_ENTERPRISE_AGENT_COUNT;
        }
        if ("enterprise".equals(sub) || "team".equals(sub)) {
            return MAX_ENTERPRISE_AGENT_COUNT;
        }
        return DEFAULT_AGENT_COUNT;
    }

    public static int getPlanModeV2ExploreAgentCount(
        Supplier<String> exploreCountEnvSupplier,
        IntSupplier parseIntFn) {
        String env = exploreCountEnvSupplier == null ? null : exploreCountEnvSupplier.get();
        if (env != null && !env.isEmpty()) {
            try {
                int count = parseIntFn.getAsInt();
                if (count >= MIN_AGENT_COUNT && count <= MAX_AGENT_COUNT) {
                    return count;
                }
            } catch (RuntimeException ignored) {}
        }
        return EXPLORE_AGENT_COUNT_DEFAULT;
    }

    /**
     * @param userType           USER_TYPE env
     * @param envVar             CLAUDE_CODE_PLAN_MODE_INTERVIEW_PHASE
     * @param isEnvTruthyFn      injected
     * @param isEnvDefinedFalsyFn injected
     * @param gbFeatureSupplier  GrowthBook (or static fallback) lookup
     */
    public static boolean isPlanModeInterviewPhaseEnabled(
        String userType,
        Supplier<String> envVar,
        java.util.function.Predicate<String> isEnvTruthyFn,
        java.util.function.Predicate<String> isEnvDefinedFalsyFn,
        Supplier<Boolean> gbFeatureSupplier) {
        if ("ant".equals(userType)) return true;
        String env = envVar == null ? null : envVar.get();
        if (env != null && isEnvTruthyFn != null && isEnvTruthyFn.test(env)) return true;
        if (env != null && isEnvDefinedFalsyFn != null && isEnvDefinedFalsyFn.test(env)) return false;
        if (gbFeatureSupplier == null) return false;
        Boolean b = gbFeatureSupplier.get();
        return b != null && b;
    }

    public static String getPewterLedgerVariant(Supplier<String> gbFeatureSupplier) {
        if (gbFeatureSupplier == null) return null;
        String raw = gbFeatureSupplier.get();
        if ("trim".equals(raw) || "cut".equals(raw) || "cap".equals(raw)) return raw;
        return null;
    }
}
