package com.nexusai.infra.config;

import com.nexusai.application.agent.browser.BrowserWsChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览器扩展 WS 端点与既有 STOMP 端点<b>共存</b>验证 · {@code @EnableWebSocket} +
 * {@code @EnableWebSocketMessageBroker} 同上下文。
 *
 * <p><b>WHY（意图验证，规则九）</b>：{@link BrowserWebSocketConfig} 用 {@code @EnableWebSocket}
 * 注册原生 WS 端点 {@code /ws/browser}，与既有 {@link WebSocketConfig}（{@code @EnableWebSocketMessageBroker}
 * STOMP 端点 {@code /ws}、{@code /ws-sockjs}）必须互不干扰。Spring 6.2 中两者产生的
 * {@code HandlerMapping} bean 名不同（{@code webSocketHandlerMapping} vs
 * {@code stompWebSocketHandlerMapping}）、路径不重叠 —— 若该前提被破坏（bean 冲突 / 路径抢占），
 * 应用启动即失败，浏览器扩展与前端 STOMP 通道至少一方不可用。
 *
 * <p><b>为什么独立轻量上下文而非全量 {@code @SpringBootTest}</b>：全量上下文在本 worktree 被
 * 既有 V56 迁移冲突 / SQLite 未迁移 schema 阻塞（非本阶段引入），故只装配两个 WS 配置类 +
 * 通道 stub，无 DB / Flyway —— 精确验证 WebSocket 层共存。
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
    WebSocketConfig.class,
    BrowserWebSocketConfig.class,
    BrowserWsChannel.class
})
@DisplayName("浏览器扩展 WS 端点与既有 STOMP 端点共存（@EnableWebSocket + @EnableWebSocketMessageBroker）")
class BrowserWebSocketConfigContextTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("两个 HandlerMapping bean 均注册（webSocketHandlerMapping + stompWebSocketHandlerMapping）")
    void bothHandlerMappingsRegistered() {
        Map<String, HandlerMapping> mappings = ctx.getBeansOfType(HandlerMapping.class);

        assertThat(mappings)
            .as("浏览器原生 WS 端点（@EnableWebSocket）与 STOMP 端点（@EnableWebSocketMessageBroker）"
                + "必须各自注册独立的 HandlerMapping bean，互不覆盖")
            .containsKeys("webSocketHandlerMapping", "stompWebSocketHandlerMapping");
    }

    @Test
    @DisplayName("BrowserWsChannel 已装配（BrowserWebSocketConfig 注入成功）")
    void channelBeanWired() {
        assertThat(ctx.getBean(BrowserWsChannel.class))
            .as("BrowserWebSocketConfig 需要 BrowserWsChannel 完成扩展连接注册表 + 结果路由")
            .isNotNull();
    }

    @Test
    @DisplayName("既有 STOMP 配置仍被识别为 WebSocketMessageBrokerConfigurer（未被我方配置破坏）")
    void stompConfigurerIntact() {
        Map<String, WebSocketMessageBrokerConfigurer> configurers =
            ctx.getBeansOfType(WebSocketMessageBrokerConfigurer.class);

        assertThat(configurers)
            .as("既有 WebSocketConfig（STOMP）必须仍注册为 broker configurer")
            .containsKey("webSocketConfig");
    }
}
