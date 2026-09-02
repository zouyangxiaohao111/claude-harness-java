package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Elicitation form 弹窗请求事件 · 服务端 → 前端 STOMP 推送。
 *
 * <p>topic: {@code /topic/mcp/elicitation-requests}
 *
 * <p><b>WHY</b>（[IMP-SS-01] 对齐 CC elicitationHandler.ts:114-153 form 模式用户响应链）：
 * CC 无 hook 决策时 form 模式入队 AppState 由用户填表单响应（elicitationHandler.ts:127-150
 * {@code setAppState(queue.push({serverName, requestId, params, signal, waitingState, respond}))}）；
 * Java web 后端无内嵌 UI，等价实现 = STOMP 推本事件 → 前端弹表单 → 用户提交后回传
 * {@link ElicitationFormResponseEvent}（STOMP inbound）。
 *
 * <p>字段对齐 CC {@code ElicitRequestParams}（message / mode / url / elicitationId /
 * requestedSchema）+ queue event（serverName / requestId）。
 *
 * <p><b>abort→cancel</b>：前端弹窗「取消」操作回传 {@code action='cancel'} 的
 * {@link ElicitationFormResponseEvent}（对齐 CC {@code respond({action:'cancel'})}）；
 * 后端连接关闭/超时由 ElicitationHandler 侧 resolve cancel / fail-closed decline。
 *
 * @see ElicitationFormResponseEvent
 * @see com.nexusai.application.agent.mcp.ElicitationHandler#beginFormElicitation
 * @since hooks_v4 IMP-SS-01
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ElicitationFormRequestEvent {

    /** 弹窗唯一请求 ID · 关联 {@link ElicitationFormResponseEvent#getRequestId()}（= JSON-RPC id）。 */
    private final String requestId;

    /** MCP 服务器名 · CC original: {@code serverName}（elicitations 匹配 key）。 */
    private final String serverName;

    /** 弹窗展示消息 · CC original: {@code message}。 */
    private final String message;

    /** elicitation 模式 · CC original: {@code mode}（'form' | 'url'，可 null）。 */
    private final String mode;

    /** URL 模式链接 · CC original: {@code url}（可 null）。 */
    private final String url;

    /** URL 模式 elicitation id · CC original: {@code elicitationId}（可 null）。 */
    private final String elicitationId;

    /** form 模式请求 schema · CC original: {@code requestedSchema}（前端渲染表单用，可 null）。 */
    private final JsonNode requestedSchema;

    /** 服务端推送时间戳（毫秒）· 前端可显示倒计时/超时。 */
    private final long timestampMs;

    /**
     * @param requestId     弹窗唯一请求 ID（= JSON-RPC id，跨 server 由 {@code serverName} 限定）
     * @param serverName    MCP 服务器名（响应路由 key）
     * @param message       弹窗展示消息
     * @param mode          elicitation 模式（'form' | 'url'，可 null）
     * @param url           URL 模式链接（可 null）
     * @param elicitationId URL 模式 elicitation id（可 null）
     * @param requestedSchema form 模式请求 schema（前端渲染表单用，可 null）
     */
    public ElicitationFormRequestEvent(String requestId,
                                       String serverName,
                                       String message,
                                       String mode,
                                       String url,
                                       String elicitationId,
                                       JsonNode requestedSchema) {
        this(requestId, serverName, message, mode, url, elicitationId, requestedSchema,
            Instant.now().toEpochMilli());
    }

    /** 显式时间戳构造器（测试可注入确定性时间戳）。 */
    public ElicitationFormRequestEvent(String requestId,
                                       String serverName,
                                       String message,
                                       String mode,
                                       String url,
                                       String elicitationId,
                                       JsonNode requestedSchema,
                                       long timestampMs) {
        this.requestId = requestId;
        this.serverName = serverName;
        this.message = message;
        this.mode = mode;
        this.url = url;
        this.elicitationId = elicitationId;
        this.requestedSchema = requestedSchema;
        this.timestampMs = timestampMs;
    }

    /** 当前时间戳便捷工厂。 */
    public static ElicitationFormRequestEvent of(String requestId,
                                                 String serverName,
                                                 String message,
                                                 String mode,
                                                 String url,
                                                 String elicitationId,
                                                 JsonNode requestedSchema) {
        return new ElicitationFormRequestEvent(
            requestId, serverName, message, mode, url, elicitationId, requestedSchema);
    }

    public String getRequestId() { return requestId; }
    public String getServerName() { return serverName; }
    public String getMessage() { return message; }
    public String getMode() { return mode; }
    public String getUrl() { return url; }
    public String getElicitationId() { return elicitationId; }
    public JsonNode getRequestedSchema() { return requestedSchema; }
    public long getTimestampMs() { return timestampMs; }
}
