package com.nexusai.model.dto;

import java.util.List;

/** RFC 7807 Problem Details · 错误响应 */
public record Problem(
    String type,                        // "about:blank" 或 URL
    String title,                       // "Not Found" / "Validation Failed"
    int status,                         // HTTP 状态码
    String detail,                      // "Session sess-xxx not found"
    String instance,                    // 请求 URI
    String traceId,                     // 链路追踪
    List<FieldError> errors,            // 仅 400 校验错才有
    String errorCode                    // CRON-B4-3 决策 #13：CC 三元错误码（1-4）；非错误码响应为 null
) {
    public record FieldError(String field, String message, Object rejectedValue) {}

    public static Problem of(int status, String title, String detail) {
        return new Problem("about:blank", title, status, detail, null, null, null, null);
    }

    /** CRON-B4-3 决策 #13：带 errorCode 的错误响应工厂（对齐 CC errorCode 三元码） */
    public static Problem of(int status, String title, String detail, String errorCode) {
        return new Problem("about:blank", title, status, detail, null, null, null, errorCode);
    }
}