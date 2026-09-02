package com.nexusai.infra.util;

import java.util.HashSet;
import java.util.Set;

/**
 * SetUtils · 对齐 CC utils/set.ts.
 *
 * <p>L1 语义: 4 个 Set 运算 — difference/intersects/every/union (CC 注释: hot path,优化速度)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 静态泛型方法 (difference/intersects/every/union) — Set&lt;A&gt; 输入</li>
 *   <li><b>A2 Golden Trace</b>: difference(1,2,3,1,2,3)(2,3,4)={1};intersects({1,2},{2,3})=true;every({1,2},{1,2})=true;union({1},{2})={1,2}</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: empty set→empty result;intersects 两空→false;every A empty→true (vacuous)</li>
 *   <li><b>A5 业务场景</b>: tool permission union/intersection calc;path exclude (ripgrep --glob !dir/**)</li>
 * </ul>
 *
 * <p>L3 升级: TS Set → Java Set/HashSet;
 * TS for-of → Java for loop (optimized hot path).
 */
public final class SetUtils {

    private SetUtils() {}

    /** Difference a - b (elements in a but not b). */
    public static <A> Set<A> difference(Set<A> a, Set<A> b) {
        if (a == null) return new HashSet<>();
        Set<A> result = new HashSet<>();
        for (A item : a) {
            if (b == null || !b.contains(item)) result.add(item);
        }
        return result;
    }

    /** Returns true if a and b share at least one element. Empty set → false. */
    public static <A> boolean intersects(Set<A> a, Set<A> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return false;
        for (A item : a) {
            if (b.contains(item)) return true;
        }
        return false;
    }

    /** Returns true if every element of a is in b. */
    public static <A> boolean every(Set<A> a, Set<A> b) {
        for (A item : a) {
            if (!b.contains(item)) return false;
        }
        return true;
    }

    /** Union a ∪ b. */
    public static <A> Set<A> union(Set<A> a, Set<A> b) {
        Set<A> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }
}
