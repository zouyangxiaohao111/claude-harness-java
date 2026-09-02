package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * nexusai-in-chrome 浏览器扩展 WebSocket 处理器 · 端点 {@code /ws/browser}（原生 WS，非 STOMP）。
 *
 * <p><b>职责</b>：扩展连接生命周期 + 消息协议路由（薄协议适配层，业务在 {@link BrowserWsChannel}）：
 * <ul>
 *   <li>连接建立 → 等扩展发 {@code hello} 注册<b>全局连接</b>（{@link BrowserWsChannel#register}，
 *       hello 不再要求 sessionId —— 一个扩展连接服务所有会话）；</li>
 *   <li>{@code tool_result} / {@code tool_error} → {@link BrowserWsChannel#resolve} 完成对应
 *       挂起 future（{@code id} 路由，与 sessionId 无关）；</li>
 *   <li>连接关闭 / 传输错误 → {@link BrowserWsChannel#unregisterByWsSession} 清理全局引用。</li>
 * </ul>
 *
 * <p><b>消息协议（与 {@link BrowserWsChannel} 类 Javadoc 同步）</b>：
 * <pre>
 *   扩展 → 后端: {"type":"hello"}                                      （建连后第一条消息；sessionId 可选，仅诊断）
 *   后端 → 扩展: {"type":"tool_call","id":"&lt;uuid&gt;","sessionId":"&lt;会话ID&gt;","tool":"...","args":{...}}
 *   扩展 → 后端: {"type":"tool_result","id":"&lt;同id&gt;","result":{...}}
 *   扩展 → 后端: {"type":"tool_error","id":"&lt;同id&gt;","error":"&lt;文案&gt;"}
 * </pre>
 *
 * <p><b>非法消息 fail loud（规则十二）</b>：JSON 解析失败 → 主动关闭连接（1003 NOT_ACCEPTABLE），
 * 不让坏连接挂着假装已绑定；未知消息类型 → log warn 忽略（向前兼容扩展新消息）。
 *
 * @see BrowserWsChannel
 * @see com.nexusai.infra.config.BrowserWebSocketConfig
 */
public class BrowserWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BrowserWebSocketHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserWsChannel channel;

    /**
     * @param channel 浏览器扩展通信桥（连接注册表 + 结果路由）
     */
    public BrowserWebSocketHandler(BrowserWsChannel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("BrowserWsChannel is null");
        }
        this.channel = channel;
    }

    /**
     * 连接建立 · 不立即注册 —— 等扩展发 {@code hello} 后再注册全局连接。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (log.isInfoEnabled()) {
            log.info("BrowserWebSocketHandler: 扩展 WS 已建立，等待 hello 注册全局连接 wsSessionId={}",
                session.getId());
        }
    }

    /**
     * 文本消息路由 · 按 {@code type} 分发（hello / tool_result / tool_error）。
     *
     * <p>扩展与后端经原生 WebSocket 收发纯 JSON 文本帧（非 STOMP frame），故用
     * {@link TextWebSocketHandler} 而非 STOMP subprotocol handler。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode msg;
        try {
            msg = JSON.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("BrowserWebSocketHandler: 扩展消息 JSON 解析失败（关闭连接 1003）wsSessionId={} err={}",
                session.getId(), e.getMessage());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        if (msg == null || !msg.isObject()) {
            log.warn("BrowserWebSocketHandler: 扩展消息非 JSON 对象（关闭连接 1003）wsSessionId={}",
                session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String type = msg.path("type").asText("");
        switch (type) {
            case "hello" -> {
                // 全局连接：hello 不再要求 sessionId（扩展 popup 一次连接服务所有会话）；
                // 可选携带的 sessionId 仅作诊断日志，不参与路由。
                String sessionId = msg.path("sessionId").asText("");
                channel.register(session);
                if (log.isInfoEnabled()) {
                    log.info("BrowserWebSocketHandler: 扩展 hello 已注册全局连接 sessionId={}（可选）wsSessionId={}",
                        sessionId.isBlank() ? "<未携带>" : sessionId, session.getId());
                }
            }
            case "tool_result", "tool_error" -> {
                String callId = msg.path("id").asText("");
                if (callId.isBlank()) {
                    log.warn("BrowserWebSocketHandler: {} 缺少 id（忽略）wsSessionId={}",
                        type, session.getId());
                    return;
                }
                channel.resolve(callId, msg);
            }
            default -> {
                if (log.isWarnEnabled()) {
                    log.warn("BrowserWebSocketHandler: 未知消息类型（忽略）type={} wsSessionId={}",
                        type, session.getId());
                }
            }
        }
    }

    /**
     * 连接关闭 → 清理全局引用（身份感知，不会误删新连接）。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        channel.unregisterByWsSession(session);
        if (log.isInfoEnabled()) {
            log.info("BrowserWebSocketHandler: 扩展 WS 已关闭 wsSessionId={} status={}",
                session.getId(), status);
        }
    }

    /**
     * 传输错误 → 清理全局引用（连接已不可用，保留会导致 send 发往死连接）。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("BrowserWebSocketHandler: 扩展 WS 传输错误 wsSessionId={} err={}",
            session.getId(), exception.toString());
        channel.unregisterByWsSession(session);
    }
}
