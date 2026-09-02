package com.nexusai.application.agent.sdk;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK control protocol Zod schemas · 对齐 CC entrypoints/sdk/controlSchemas.ts.
 *
 * <p>L1 语义: SDK 与 CLI 通信的 control 协议 — SDKControlRequest/Response/cancel/permission.
 *            用于 Python SDK 等其他语言 SDK 实现与 CLI 进程通信.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 8 message type enum; 6 record (SDKControlRequest/Response/Cancel/Permission/
 *       StreamlinedText/ToolUseSummary/PostTurnSummary); SchemaValidator interface.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — validate(message) → 解析 type/subtype/fields → 通过/失败;
 *       SDKControlRequest 主入口 (type=control_request + request_id + request subtype).</li>
 *   <li><b>A3</b>: 注入式 (validator);纯函数 validate.</li>
 *   <li><b>A4</b>: 未知 type → throw;缺少 request_id → throw.</li>
 *   <li><b>A5</b>: 真实场景 — Python SDK 发送 control_request → CLI 解析 → 返回 control_response.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Zod schema → Java record + 静态 validator;
 *                    TS discriminated union → Java sealed interface;
 *                    TS type guard → Java 静态方法.
 */
public final class SDKControlSchemas {

    private static final Logger log = LoggerFactory.getLogger(SDKControlSchemas.class);

    public enum ControlType { CONTROL_REQUEST, CONTROL_RESPONSE, CONTROL_CANCEL_REQUEST }

    public enum ControlSubtype {
        CAN_USE_TOOL, HOOK_CALLBACK, ELICITATION, MCP_MESSAGE, PERMISSION_UPDATE
    }

    public sealed interface SDKControlMessage
            permits SDKControlRequest, SDKControlResponse, SDKControlCancelRequest {
        String type();
        String requestId();
    }

    public record SDKControlRequest(
        String type, String requestId, String subtype, Map<String, Object> request)
            implements SDKControlMessage {}

    public record SDKControlResponse(
        String type, String requestId, String subtype, Object response)
            implements SDKControlMessage {}

    public record SDKControlCancelRequest(String type, String requestId)
            implements SDKControlMessage {}

    public record PermissionUpdate(
        String toolName, String rule, String behavior) {}

    public interface SchemaValidator {
        void validate(String type, Map<String, Object> data);
    }

    private final SchemaValidator validator;

    public SDKControlSchemas(SchemaValidator validator) {
        this.validator = validator;
    }

    public SDKControlSchemas() {
        this(null);
    }

    /** CC validateControlRequest 纯函数. */
    public static SDKControlRequest validateControlRequest(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("raw null");
        String type = (String) raw.get("type");
        if (!"control_request".equals(type)) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
        Object requestIdObj = raw.get("request_id");
        if (!(requestIdObj instanceof String requestId) || requestId.isEmpty()) {
            throw new IllegalArgumentException("missing request_id");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) raw.get("request");
        if (request == null) {
            throw new IllegalArgumentException("missing request");
        }
        String subtype = (String) request.get("subtype");
        if (subtype == null) {
            throw new IllegalArgumentException("missing request.subtype");
        }
        return new SDKControlRequest(type, requestId, subtype, request);
    }

    /** CC validateControlResponse. */
    public static SDKControlResponse validateControlResponse(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("raw null");
        String type = (String) raw.get("type");
        if (!"control_response".equals(type)) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
        Object requestIdObj = raw.get("request_id");
        if (!(requestIdObj instanceof String requestId) || requestId.isEmpty()) {
            throw new IllegalArgumentException("missing request_id");
        }
        String subtype = (String) raw.get("subtype");
        Object response = raw.get("response");
        return new SDKControlResponse(type, requestId, subtype, response);
    }

    public static SDKControlCancelRequest validateControlCancelRequest(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("raw null");
        String type = (String) raw.get("type");
        if (!"control_cancel_request".equals(type)) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
        Object requestIdObj = raw.get("request_id");
        if (!(requestIdObj instanceof String requestId) || requestId.isEmpty()) {
            throw new IllegalArgumentException("missing request_id");
        }
        return new SDKControlCancelRequest(type, requestId);
    }

    /** CC isControlRequest type guard. */
    public static boolean isControlRequest(Object message) {
        if (message == null) return false;
        if (message instanceof SDKControlRequest) return true;
        if (message instanceof Map) {
            return "control_request".equals(((Map<?, ?>) message).get("type"));
        }
        return false;
    }
}