package com.nexusai.application.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * NDJSON 行安全 stringify · 对齐 CC cli/ndjsonSafeStringify.ts:30-32.
 *
 * <p>L1 语义: Jackson JSON 序列化 + 转义 U+2028 (LINE SEPARATOR) / U+2029 (PARAGRAPH SEPARATOR)
 *            为 \\u2028 / \\u2029. 避免 receiver (按 ECMA-262 §11.3 line terminator 切分) 把
 *            JSON 字符串中途截断.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `ndjsonSafeStringify(Object) → String` 签名 (值 → JSON 字符串)</li>
 *   <li><b>A2 Golden Trace</b>: "{a:\\u2028b:\\u2029c:1}" 字符串输入 → 输出含 \\u2028\\u2029 转义</li>
 *   <li><b>A3</b>: 纯函数; 无副作用</li>
 *   <li><b>A4</b>: null 输入 → "null" 字符串; 空对象 {} → "{}"</li>
 *   <li><b>A5</b>: 复杂对象 — {msg:"line1\\u2028line2"} → {"msg":"line1\\\\u2028line2"}</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Jackson ObjectMapper 替代 JSON.stringify; String.replace 替代 regex callback;
 *                    CC U+2028/U+2029 → Java \\u2028 / \\u2029 Unicode escape (Jackson 自动).
 */
public final class NdjsonSafeStringify {

    private static final String U2028 = " ";
    private static final String U2029 = " ";
    private static final String ESCAPED_U2028 = "\\u2028";
    private static final String ESCAPED_U2029 = "\\u2029";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NdjsonSafeStringify() {}

    /** CC ndjsonSafeStringify(value): JSON 字符串 + 转义 U+2028/U+2029. */
    public static String ndjsonSafeStringify(Object value) {
        try {
            String json = MAPPER.writeValueAsString(value);
            // Jackson 默认已转义 U+2028/U+2029 为 \\u2028/\\u2029 (CC 同语义)
            // 此处二次校验 — 防止序列化器配置变更导致 raw 字符泄漏
            return escapeJsLineTerminators(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ndjsonSafeStringify failed: " + e.getMessage(), e);
        }
    }

    private static String escapeJsLineTerminators(String json) {
        if (json == null || json.indexOf(U2028) < 0 && json.indexOf(U2029) < 0) {
            return json == null ? "null" : json;
        }
        return json
            .replace(U2028, ESCAPED_U2028)
            .replace(U2029, ESCAPED_U2029);
    }
}