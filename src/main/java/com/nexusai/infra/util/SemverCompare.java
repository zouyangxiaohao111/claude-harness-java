package com.nexusai.infra.util;

import java.util.function.BiFunction;

/**
 * SemverCompare · 对齐 CC utils/semver.ts.
 *
 * <p>L1 语义: semver 字符串比较 + range matching。
 * 6 静态方法: gt / gte / lt / lte / satisfies / order (-1/0/1)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 6 method + Comparator 注入式 (caller-wired Bun.semver/npm semver)</li>
 *   <li><b>A2 Golden Trace</b>: order("1.2.3", "1.2.4")=-1;order("2.0.0", "1.9.9")=1;order("1.0.0", "1.0.0")=0</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output;无副作用</li>
 *   <li><b>A4 边界</b>: null version/comparator → 抛 NPE / 静默 false 视 caller 设计</li>
 *   <li><b>A5 业务场景</b>: feature flag 检查 feature version ≥ 1.4.0,plugin compat range ">=1.0.0 <2.0.0"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Bun.semver global order() → Java BiFunction 注入式 comparator;
 * TS 返回 -1|0|1 → Java int 等价 (Comparator convention);
 * TS npm semver loose:true → Java comparator 不变 (caller 控制)。
 */
public final class SemverCompare {

    /** Comparator returning -1/0/1 (TS Bun.semver.order convention). */
    public interface VersionComparator {
        int order(String a, String b);
        default boolean gt(String a, String b) { return order(a, b) > 0; }
        default boolean gte(String a, String b) { return order(a, b) >= 0; }
        default boolean lt(String a, String b) { return order(a, b) < 0; }
        default boolean lte(String a, String b) { return order(a, b) <= 0; }
    }

    private SemverCompare() {}

    /** Returns -1/0/1 by delegating to comparator. Returns 0 if comparator is null. */
    public static int order(String a, String b, VersionComparator comparator) {
        if (comparator == null) return 0;
        return comparator.order(a, b);
    }

    public static boolean gt(String a, String b, VersionComparator c) { return c.order(a, b) > 0; }
    public static boolean gte(String a, String b, VersionComparator c) { return c.order(a, b) >= 0; }
    public static boolean lt(String a, String b, VersionComparator c) { return c.order(a, b) < 0; }
    public static boolean lte(String a, String b, VersionComparator c) { return c.order(a, b) <= 0; }

    /**
     * Check if version satisfies range (e.g. {@code "1.0.0"} in {@code ">=1.0.0 <2.0.0"}).
     */
    public static boolean satisfies(String version, String range, BiFunction<String, String, Boolean> matcher) {
        if (matcher == null) return false;
        return matcher.apply(version, range);
    }
}
