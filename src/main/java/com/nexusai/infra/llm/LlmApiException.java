package com.nexusai.infra.llm;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM API 调用异常 · 携带 HTTP status + response headers + body。
 *
 * <p>对齐 CC withRetry.ts:519-528 getRetryAfter 从 {@code error.headers['retry-after']} 提取。
 * 扩展自 RuntimeException 保持向后兼容（现有 catch 块不受影响）。
 *
 * @param status  HTTP 状态码（如 429 / 500 / 529）
 * @param headers HTTP 响应头（key → values，大小写不敏感查找）
 * @param body    响应体（截断前 300 字符）
 */
public class LlmApiException extends RuntimeException {

    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;

    /**
     * [R27-6 / R26-3] 错误分类标记 · 由 OpenAiSdkProvider 翻译 SDK 异常（T-OA-07）后写入.
     *
     * <p>对齐 CC query.ts:970 区分 image_error / stream_error;Java 端用此字段替代 LlmAgentLoop
     * isImageError() 的字符串匹配,实现类型化错误传播 — 调用方 {@code instanceof} 判断即可,
     * 不用每次重做 message contains 检查.
     */
    public enum Kind { GENERIC, IMAGE }

    private final Kind kind;

    /**
     * 构造 LLM API 异常。
     *
     * @param status  HTTP 状态码
     * @param headers HTTP 响应头映射（key 为 header 名，value 为值列表）
     * @param body    响应体原文
     */
    public LlmApiException(int status, Map<String, List<String>> headers, String body) {
        this(status, headers, body, Kind.GENERIC);
    }

    /**
     * [R27-6 / R26-3] 构造 LLM API 异常 (带 Kind 分类).
     */
    public LlmApiException(int status, Map<String, List<String>> headers, String body, Kind kind) {
        super("HTTP " + status + ": " + truncate(body, 300));
        this.status = status;
        this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
        this.body = body == null ? "" : body;
        this.kind = kind == null ? Kind.GENERIC : kind;
    }

    /**
     * [R27-6 / R26-3] 图片尺寸/缩放专用错误工厂 · 调用于 OpenAiSdkProvider 翻译 SDK 异常后.
     *
     * <p>典型触发: 4xx with body containing "image_error", "image_too_large",
     * "image dimensions exceed", "image_scaling_failed".
     *
     * @param status  HTTP 状态码 (e.g. 400/413/422)
     * @param headers 响应头
     * @param body    响应体
     * @return Kind=IMAGE 的 LlmApiException,调用方可用 {@code ex.kind() == Kind.IMAGE} 判定
     */
    public static LlmApiException imageError(int status,
                                             Map<String, List<String>> headers,
                                             String body) {
        return new LlmApiException(status, headers, body, Kind.IMAGE);
    }

    /**
     * [R27-6 / R26-3] 探测 body 是否属图片错误 · 工厂 imageError() 内部判定逻辑.
     *
     * <p>与 LlmAgentLoop.isImageError() 文本匹配规则保持一致(SSOT);提取为 public static 让
     * OpenAiSdkProvider 可在翻译 SDK 异常时立即判断并选择 imageError() / 普通构造器.
     */
    public static boolean isImageErrorBody(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("image_error")
            || lower.contains("image_too_large")
            || lower.contains("image dimensions exceed")
            || lower.contains("image size")
            || lower.contains("image_scaling")
            || (lower.contains("image") && (lower.contains("too large") || lower.contains("dimension")));
    }

    /** HTTP 状态码（如 429 / 500 / 529）。 */
    public int status() { return status; }

    /** HTTP 响应头（不可变映射，key 大小写保留原始形式）。 */
    public Map<String, List<String>> headers() { return headers; }

    /** 响应体原文。 */
    public String body() { return body; }

    /** [R27-6 / R26-3] 错误分类 (GENERIC / IMAGE) */
    public Kind kind() { return kind; }

    /**
     * 大小写不敏感查找单个 header 的首个值。
     *
     * <p>对齐 CC {@code error.headers['retry-after']} 访问模式。
     *
     * @param name header 名（大小写不敏感）
     * @return header 的首个值，或 null 表示不存在
     */
    public String getHeader(String name) {
        if (name == null || headers.isEmpty()) return null;
        // 精确匹配优先
        List<String> vals = headers.get(name);
        if (vals != null && !vals.isEmpty()) return vals.get(0);
        // 大小写不敏感回退
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
