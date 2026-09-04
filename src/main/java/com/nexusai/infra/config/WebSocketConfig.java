package com.nexusai.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

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

    /**
     * [打字机卡死修复 · WS 传输阈值] 覆写 WebSocketTransportRegistration。
     *
     * <p>WHY（2026-09-04 根因）：后端每条 SSE chunk 立即 convertAndSend 一条 STOMP 帧，DeepSeek
     * thinking 长 reasoning 一轮可产生数百上千帧；前端渲染慢 → TCP 接收窗口填满 → 写线程阻塞超
     * <b>Spring 默认 sendTimeLimit=10s</b> 被强杀（日志 "Terminating ... Send time 10144ms exceeded
     * the allowed limit 10000"）→ STOMP 断连 → message.complete 推给零订阅者静默丢弃 → 打字机永久卡死。
     *
     * <p>本方法把传输终止阈值放宽为护栏（不消除触发源，须配 LlmAgentLoop chunk 节流）：
     * <ul>
     *   <li>sendTimeLimit 10s → 60s（单次发送允许阻塞时长）</li>
     *   <li>sendBufferSizeLimit 512KB → 2MB（单会话 outbound 缓冲上限）</li>
     *   <li>timeToFirstMessage 默认 30s → 120s（首次消息超时，防止建立后迟迟不写被误杀）</li>
     * </ul>
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setSendTimeLimit(60_000)
            .setSendBufferSizeLimit(2 * 1024 * 1024)
            .setTimeToFirstMessage(120_000);
    }

    /**
     * [打字机卡死修复 · outbound 线程池扩容] 覆写 configureClientOutboundChannel。
     *
     * <p>WHY（2026-09-04）：Spring simple broker 默认 clientOutboundChannel core=max=<b>2</b>。
     * convertAndSend 是同步写 —— 单个慢消费者（浏览器渲染不过来）阻塞写线程时，2 个线程都可能被占，
     * 造成<b>跨会话全局 stall</b>。扩容到 8/16 缓解「一个慢会话拖垮所有会话」；queueCapacity 有界
     * （默认无界 Integer.MAX_VALUE 会无界堆积内存）。仍须配 chunk 节流治本 —— 线程数不消除单线程
     * 阻塞在慢消费者上的事实，只是留出余量不拖垮他人。
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
            .corePoolSize(8)
            .maxPoolSize(16)
            .queueCapacity(8192);
    }
}