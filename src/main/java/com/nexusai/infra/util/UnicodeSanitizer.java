package com.nexusai.infra.util;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * UnicodeSanitizer · 对齐 CC utils/sanitization.ts.
 *
 * <p>L1 语义: 防御 Unicode 隐藏字符攻击 (HackerOne #3086545) — NFKC normalize +
 * 移除 dangerous Unicode property classes (Cf/Co/Cn) + 显式 ranges。
 * Recursive 入口: 字符串/array/object 递归 sanitize。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: partiallySanitizeUnicode(String) + recursivelySanitizeUnicode(各种 overloads)</li>
 *   <li><b>A2 Golden Trace</b>: 隐藏 Tag 字符 [-] 移除;zero-width spaces 移除;方向控制字符移除;NFKC normalize</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: empty→empty;null→null;MAX_ITERATIONS 抛 RuntimeException</li>
 *   <li><b>A5 业务场景</b>: user prompt 含 hidden Tag 字符 → 过滤后安全 send to LLM</li>
 * </ul>
 *
 * <p>L3 升级: TS regex /[\p{Cf}\p{Co}\p{Cn}]/gu → Java 简化 (CC 注释: 非所有环境支持);
 * TS string.normalize('NFKC') → Java java.text.Normalizer;
 * TS array/object recursive → Java generic overloads.
 */
public final class UnicodeSanitizer {

    public static final int MAX_ITERATIONS = 10;

    private static final Pattern ZERO_WIDTH_SPACES = Pattern.compile("[\\u200B-\\u200F]");
    private static final Pattern DIRECTIONAL_FORMATTING = Pattern.compile("[\\u202A-\\u202E]");
    private static final Pattern DIRECTIONAL_ISOLATES = Pattern.compile("[\\u2066-\\u2069]");
    private static final Pattern BYTE_ORDER_MARK = Pattern.compile("[\\uFEFF]");
    private static final Pattern PRIVATE_USE_AREA = Pattern.compile("[\\uE000-\\uF8FF]");

    private UnicodeSanitizer() {}

    /**
     * Iteratively sanitize until no more changes occur or max iterations.
     * Throws RuntimeException if max iterations reached.
     */
    public static String partiallySanitizeUnicode(String prompt) {
        if (prompt == null) return null;
        String current = prompt;
        String previous = "";
        int iterations = 0;
        while (!current.equals(previous) && iterations < MAX_ITERATIONS) {
            previous = current;
            // NFKC normalize
            current = java.text.Normalizer.normalize(current, java.text.Normalizer.Form.NFKC);
            // Strip dangerous categories
            current = current.replaceAll("[\\p{Cf}\\p{Co}\\p{Cn}]", "");
            // Strip explicit ranges (CC fallback)
            current = ZERO_WIDTH_SPACES.matcher(current).replaceAll("");
            current = DIRECTIONAL_FORMATTING.matcher(current).replaceAll("");
            current = DIRECTIONAL_ISOLATES.matcher(current).replaceAll("");
            current = BYTE_ORDER_MARK.matcher(current).replaceAll("");
            current = PRIVATE_USE_AREA.matcher(current).replaceAll("");
            iterations++;
        }
        if (iterations >= MAX_ITERATIONS) {
            throw new RuntimeException("Unicode sanitization reached maximum iterations ("
                + MAX_ITERATIONS + ") for input: " + prompt.substring(0, Math.min(100, prompt.length())));
        }
        return current;
    }

    /** Recursive sanitization for arbitrary JSON-like value. */
    @SuppressWarnings("unchecked")
    public static <T> T recursivelySanitizeUnicode(T value) {
        if (value == null) return null;
        if (value instanceof String s) {
            return (T) partiallySanitizeUnicode(s);
        }
        if (value instanceof Iterable<?> iter) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (Object item : iter) {
                result.add(recursivelySanitizeUnicode(item));
            }
            return (T) result;
        }
        if (value instanceof Map<?, ?> map) {
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(recursivelySanitizeUnicode(e.getKey()),
                    recursivelySanitizeUnicode(e.getValue()));
            }
            return (T) result;
        }
        // Primitive: return unchanged
        return value;
    }
}
