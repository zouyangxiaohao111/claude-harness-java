package com.nexusai.infra.util;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ModelDeprecation · 对齐 CC utils/model/deprecation.ts.
 *
 * <p>L1 语义: 模型 deprecation 状态查询 — 哪些模型已弃用 + 弃用时间。
 * <ul>
 *   <li>{@link #isDeprecated(String, Map)} — model id 是否在 deprecation 列表</li>
 *   <li>{@link #getDeprecationDate(String, Map)} — 弃用日期 (Instant or null)</li>
 *   <li>{@link #getDaysUntilDeprecation(String, Map, java.time.Instant)} — 距弃用天数</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Trace):
 * <ul>
 *   <li><b>A1</b>: 3 静态方法 + DeprecationInfo record</li>
 *   <li><b>A2 Golden Trace</b>: model in map→true + 弃用 date;not in map→false;past date→days 0 负数;future date→positive</li>
 *   <li><b>A3 纯函数</b>: stateless;现在 supplier 注入</li>
 *   <li><b>A4 边界</b>: null model→false;null map→empty;now=now→days=0;now after dep→negative</li>
 *   <li><b>A5 业务场景</b>: "claude-3-haiku" deprecated Jan 1, 2026;warning "deprecation coming"</li>
 * </ul>
 *
 * <p>L3 升级: TS Record → Java Map (caller wired);
 * TS Date → Java Instant;
 * TS getDaysUntil → Java Duration.between.
 */
public final class ModelDeprecation {

    public record DeprecationInfo(java.time.Instant deprecatedAt, String replacement) {}

    private ModelDeprecation() {}

    public static boolean isDeprecated(String modelId, Map<String, DeprecationInfo> deprecations) {
        if (modelId == null || deprecations == null) return false;
        return deprecations.containsKey(modelId);
    }

    public static java.time.Instant getDeprecationDate(
        String modelId, Map<String, DeprecationInfo> deprecations) {
        if (!isDeprecated(modelId, deprecations)) return null;
        DeprecationInfo info = deprecations.get(modelId);
        return info == null ? null : info.deprecatedAt();
    }

    public static long getDaysUntilDeprecation(
        String modelId, Map<String, DeprecationInfo> deprecations, java.time.Instant now) {
        if (!isDeprecated(modelId, deprecations)) return Long.MAX_VALUE;
        java.time.Instant dep = getDeprecationDate(modelId, deprecations);
        if (dep == null) return Long.MAX_VALUE;
        java.time.Instant ref = now == null ? java.time.Instant.now() : now;
        return Duration.between(ref, dep).toDays();
    }
}
