package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Elicitation form 弹窗响应事件 · 前端 → 服务端 STOMP 发送。
 *
 * <p>destination: {@code /app/mcp/elicitation-response}
 * （{@code /app/...} 是 STOMP SEND 前缀，由
 * {@code @MessageMapping("/mcp/elicitation-response")} 映射到
 * {@link com.nexusai.apis.permission.PermissionController#handleElicitationFormResponse}）。
 *
 * <p><b>WHY</b>（[IMP-SS-01] 对齐 CC elicitationHandler.ts:114-153 form 模式用户响应链）：
 * 前端收到 {@link ElicitationFormRequestEvent} 弹表单 → 用户填写提交 → STOMP SEND 本事件 →
 * 后端 {@code ElicitationHandler.resolveFormResponse} 完成挂起的 JSON-RPC 请求（对齐 CC
 * {@code respond(result)} 回调 resolve Promise，elicitationHandler.ts:138-146）。
 *
 * <p><b>action 语义</b>（对齐 CC ElicitResult / MCP SDK ElicitResult）：
 * <ul>
 *   <li>{@code "accept"} — 用户同意/提交表单（form 填表响应，content 为用户填写载荷）</li>
 *   <li>{@code "decline"} — 用户拒绝（CC :242-243 blockingError→decline 同义）</li>
 *   <li>{@code "cancel"} — 用户取消弹窗（对齐 CC {@code onAbort → resolve({action:'cancel'})}
 *       的「取消操作」用户侧表达，elicitationHandler.ts:115-117）</li>
 * </ul>
 *
 * <p><b>字段</b>：
 * <ul>
 *   <li>{@code requestId} — 关联 {@link ElicitationFormRequestEvent#getRequestId()}
 *       （= JSON-RPC id，跨 server 由 {@code serverName} 限定）</li>
 *   <li>{@code serverName} — MCP 服务器名（跨 server 请求路由 key）</li>
 *   <li>{@code action} — 用户决策（'accept' | 'decline' | 'cancel'）</li>
 *   <li>{@code content} — 用户填写的表单载荷（CC ElicitResult.content，可 null）</li>
 * </ul>
 *
 * @see ElicitationFormRequestEvent
 * @see com.nexusai.apis.permission.PermissionController#handleElicitationFormResponse
 * @see com.nexusai.application.agent.mcp.ElicitationHandler#resolveFormResponse
 * @since hooks_v4 IMP-SS-01
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ElicitationFormResponseEvent {

    private final String requestId;
    private final String serverName;
    private final String action;
    private final Map<String, Object> content;

    /**
     * 主构造器 · Jackson 反序列化入口（对齐 STOMP inbound 真实链路）。
     *
     * <p>{@link JsonCreator} + {@link JsonProperty} 让 Jackson 在没有 default constructor 的
     * 情况下也能反序列化本类 —— Spring STOMP {@code MappingJackson2MessageConverter} 反序列化
     * 前端 {@code /app/mcp/elicitation-response} 消息依赖此入口（同
     * {@link MessagePermissionResponseEvent} 模式）。
     *
     * @param requestId  弹窗请求 ID（= JSON-RPC id）
     * @param serverName MCP 服务器名（跨 server 路由）
     * @param action     用户决策（'accept' | 'decline' | 'cancel'）
     * @param content    用户填写载荷（可 null）
     */
    @JsonCreator
    public ElicitationFormResponseEvent(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("serverName") String serverName,
            @JsonProperty("action") String action,
            @JsonProperty("content") Map<String, Object> content) {
        this.requestId = requestId;
        this.serverName = serverName;
        this.action = action;
        this.content = content;
    }

    public String getRequestId() { return requestId; }
    public String getServerName() { return serverName; }
    public String getAction() { return action; }
    public Map<String, Object> getContent() { return content; }
}
