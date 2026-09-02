package com.nexusai.infra.util;

import java.util.function.Function;

/**
 * SemanticBoolean · 对齐 CC utils/semanticBoolean.ts.
 *
 * <p>L1 语义: parse boolean that also accepts "true"/"false" string literals。
 * <ul>
 *   <li>{@link #parseBoolean(Object)} → Boolean (true/false/null)</li>
 *   <li>{@link #parseBooleanOrDefault(Object, boolean)} → boolean (default on null)</li>
 * </ul>
 *
 * <p>CC 注释: zod z.coerce.boolean() 错误: "false" → true (JS truthiness).
 * Java 等价: 严格字符串比较 → boolean;其他 → null.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 (parseBoolean + parseBooleanOrDefault) + Function 注入式</li>
 *   <li><b>A2 Golden Trace</b>: "true"→true; "false"→false; true→true;false→false;null→null;"1"→null;"0"→null</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: null→null;String "True"→null (case sensitive);Boolean wrapper→unbox</li>
 *   <li><b>A5 业务场景</b>: tool input model 生成 "replace_all":"false" 解析为 false (而非 JS truthiness 的 true)</li>
 * </ul>
 *
 * <p>L3 升级: TS Zod z.preprocess → Java Function 注入式;
 * TS string === "true" → Java String.equals;
 * TS Boolean.valueOf → Java Boolean unbox.
 */
public final class SemanticBoolean {

    private SemanticBoolean() {}

    /**
     * Parse a value as boolean. Accepts:
     * - {@code Boolean} → unboxed
     * - {@code "true"} → true
     * - {@code "false"} → false
     * - null or anything else → null
     */
    public static Boolean parseBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String s = (String) value;
            if ("true".equals(s)) return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Parse with a default value for null/non-boolean input.
     */
    public static boolean parseBooleanOrDefault(Object value, boolean defaultValue) {
        Boolean result = parseBoolean(value);
        return result == null ? defaultValue : result;
    }

    /** Functional version (for dependency injection). */
    public static Function<Object, Boolean> asFunction() {
        return SemanticBoolean::parseBoolean;
    }
}
