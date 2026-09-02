package com.nexusai.application.agent.policy;

import java.util.Map;

/**
 * Policy limits API 响应类型 · 对齐 CC services/policyLimits/types.ts.
 *
 * <p>L1 语义: 服务端返回的策略限制 — restrictions map (policy key → {allowed}), 不在 map 内即放行.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: PolicyLimitsResponse(restrictions Map) + PolicyLimitsFetchResult 5 字段 record</li>
 *   <li><b>A2 Golden Trace</b>: success=true + restrictions 不为 null → 有效数据; null restrictions 表示 304 Not Modified</li>
 *   <li><b>A3</b>: record 不可变; 嵌套 PolicyRestriction(allowed) record</li>
 *   <li><b>A4</b>: skipRetry=true 用于 auth error 等不再重试场景</li>
 *   <li><b>A5</b>: 真实响应解析 — {"restrictions": {"fs:read": {"allowed": false}}} → PolicyLimitsResponse</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `z.record(z.string(), ...)` → Java `Map<String, PolicyRestriction>`;
 *                    `z.infer` → record; `string | null` 三态 → Java 引用可为 null.
 */
public final class PolicyLimitsTypes {

    private PolicyLimitsTypes() {}

    /** CC PolicyLimitsResponse — restrictions map (key → {allowed}). 不在 map 内即放行. */
    public record PolicyLimitsResponse(Map<String, PolicyRestriction> restrictions) {}

    /** CC restrictions map value — {allowed: boolean}. */
    public record PolicyRestriction(boolean allowed) {}

    /** CC PolicyLimitsFetchResult — 5 字段 fetch 结果. */
    public record PolicyLimitsFetchResult(
        boolean success,
        Map<String, PolicyRestriction> restrictions, // null 表示 304 Not Modified (cache valid)
        String etag,
        String error,
        Boolean skipRetry                          // true 不再重试 (auth error 等)
    ) {
        public static PolicyLimitsFetchResult notModified(String etag) {
            return new PolicyLimitsFetchResult(true, null, etag, null, null);
        }
    }
}