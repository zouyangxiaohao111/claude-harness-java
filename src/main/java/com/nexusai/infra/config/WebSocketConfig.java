package com.nexusai.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 配置 · STOMP over WebSocket
 *
 * <p>端点：
 * <ul>
 *   <li>{@code /ws}        — 原生 WebSocket（主）</li>
 *   <li>{@code /ws-sockjs} — SockJS 回退（兼容老浏览器 / 代理）</li>
 * </ul>
 *
 * <p>消息前缀：
 * <ul>
 *   <li>客户端订阅（server → client）：{@code /topic}（simple broker）</li>
 *   <li>客户端发送（client → server）：{@code /app}</li>
 *   <li>点对点：{@code /user}（user destination，前缀已注册但 Phase 5 暂未使用）</li>
 * </ul>
 *
 * <p>v1 本地单用户：用 {@code setAllowedOriginPatterns("*")} 放行所有 Origin。
 * v2 接入鉴权后应改为允许的域名白名单。
 *
 * @see <a href="/Users/zhengwei/Desktop/开发/nexusai-ui/docs/api/websocket.md">WebSocket 协议文档</a>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // server → client 订阅前缀
        config.enableSimpleBroker("/topic", "/queue");
        // client → server 发送前缀
        config.setApplicationDestinationPrefixes("/app");
        // 点对点前缀（@SendToUser 用到，Phase 5 暂未使用）
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 主端点 — 原生 WebSocket
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*");

        // SockJS 回退端点 — 兼容老浏览器 / 不支持原生 WS 的代理
        registry.addEndpoint("/ws-sockjs")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }
}