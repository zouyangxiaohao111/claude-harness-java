package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 错误响应（server → client 请求处理失败时回给对端的 error 对象）。
 *
 * <p>{@code code} 用 JSON-RPC 2.0 标准码：{@code -32601} method not found、
 * {@code -32602} invalid params、{@code -32603} internal error。
 */
public class AcpRpcException extends RuntimeException {

    /** JSON-RPC: Method not found。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC: Invalid params。 */
    public static final int INVALID_PARAMS = -32602;
    /** JSON-RPC: Internal error。 */
    public static final int INTERNAL_ERROR = -32603;

    private final int code;
    private final transient JsonNode data;

    public AcpRpcException(int code, String message) {
        this(code, message, null);
    }

    public AcpRpcException(int code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }
}
