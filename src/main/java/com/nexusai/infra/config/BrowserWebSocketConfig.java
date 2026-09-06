package com.nexusai.infra.config;

import com.nexusai.application.agent.browser.BrowserWebSocketHandler;
import com.nexusai.application.agent.browser.BrowserWsChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 浏览器扩展 WebSocket 端点配置 · 原生 WS（非 STOMP）。
 *
 * <p>端点：{@code /ws/browser} —— nexusai-in-chrome 自研 Chrome 扩展连接入口。
 *
 * <p><b>为什么单独 {@code @EnableWebSocket} 而非并入 STOMP {@link WebSocketConfig}</b>：
 * <ul>
 *   <li>扩展与后端收发<b>纯 JSON 文本帧</b>（消息协议见 {@link BrowserWsChannel} 类 Javadoc），
 *       不做 STOMP CONNECT/SUBSCRIBE/SEND 握手 —— 用 {@link BrowserWebSocketHandler}
 *       （{@code TextWebSocketHandler}）直收直发；</li>
 *   <li>Spring 6.2 中 {@code @EnableWebSocket} 与既有 {@code @EnableWebSocketMessageBroker} 可共存：
 *       前者注册 {@code webSocketHandlerMapping}（本端点 {@code /ws/browser}），后者注册
 *       {@code stompWebSocketHandlerMapping}（{@code /ws}、{@code /ws-sockjs}）——两个
 *       {@code HandlerMapping} bean 名不同、路径不重叠，互不干扰。</li>
 * </ul>
 *
 * <p>连接握手：扩展连上 {@code /ws/browser} 后发第一条消息 {@code {"type":"hello"}} 注册<b>全局连接</b>
 * （sessionId 不再必需 —— 一个扩展连接服务所有会话）→ {@link BrowserWsChannel#register}。之后
 * 各会话的工具调用经 {@link BrowserChannel#send(String, String, Map)} 转发到该全局连接执行，
 * 消息内透传 sessionId 供扩展定位/创建对应会话的 tab 组。
 *
 * @see WebSocketConfig （既有 STOMP 配置）
 * @see BrowserWebSocketHandler
 * @see BrowserWsChannel
 */
@Configuration
@EnableWebSocket
public class BrowserWebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BrowserWebSocketConfig.class);

    /** 浏览器扩展 WS 端点路径 · 对齐前端扩展对接契约（原生 WebSocket，非 STOMP）。 */
    public static final String BROWSER_WS_PATH = "/ws/browser";

    @Autowired
    private BrowserWsChannel browserWsChannel;

    /**
     * 放大 WS 帧缓冲 —— 治 1009「Message Too Big」：
     * 截图结果是大段 base64 JSON（~40KB~MB 级），后端默认文本帧缓冲 ~8KB，
     * 一发截图就超限 → 连接被 1009 掐断（此前「截图必断连/超时」根因）。
     * 16MB 文本 + 二进制缓冲覆盖最大全页 PNG；会话不设服务端空闲超时。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(16 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(16 * 1024 * 1024);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new BrowserWebSocketHandler(browserWsChannel), BROWSER_WS_PATH)
            // v1 本地单用户：放行所有 Origin（对齐既有 WebSocketConfig.setAllowedOriginPatterns("*")）
            .setAllowedOriginPatterns("*");
        log.info("注册浏览器扩展 WebSocket 端点 {}（BrowserWsChannel 注入={}）",
            BROWSER_WS_PATH, browserWsChannel != null);
    }
}
