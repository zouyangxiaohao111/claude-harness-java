package com.nexusai.application.chat;

import com.nexusai.application.agent.messaging.PeerEndpointActivator;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * [2026-11-20 启动阻断] {@code PeerEndpointActivator} 适配器 + 代理语义回归。
 *
 * <h2>缺陷</h2>
 * <p>{@code ChatService} 带 {@code @Async}。它一旦 {@code implements PeerEndpointActivator}，
 * Spring 就选 JDK 动态代理 ⇒ bean 类型退化成接口类型 ⇒ 按具体类型注入 ChatService 的
 * {@code SessionService} 等调用方启动失败：
 * {@code Bean named 'chatService' ... was actually of type 'jdk.proxy2.$Proxy181'}。
 *
 * <h2>修复不变量（本类守着）</h2>
 * <ol>
 *   <li>{@code ChatService} 不实现 {@link PeerEndpointActivator}（否则重新踩坑）；</li>
 *   <li>无用户接口的 {@code @Async} bean 被 CGLIB 代理，按具体类型注入可用；</li>
 *   <li>{@code PeerEndpointActivator} 按接口注入成功，且行为 = 委托 ChatService；</li>
 *   <li>按需登记 / 按代次注销行为不变。</li>
 * </ol>
 */
@DisplayName("[启动阻断] PeerEndpointActivator 适配器：按类型/按接口注入 + 登记注销语义")
class ChatPeerEndpointActivatorTest {

    private static final String EXISTING = "sess-a1b2c3d4";
    private static final String MISSING = "sess-deadbeef";

    // ── 1. 生产类不许再实现接口（回归门禁） ────────────────────────────────

    @Test
    @DisplayName("ChatService 不实现 PeerEndpointActivator（否则 @Async 会切 JDK 代理）")
    void chatServiceDoesNotImplementActivator() {
        assertThat(PeerEndpointActivator.class.isAssignableFrom(ChatService.class))
            .as("★ ChatService 实现任何接口 ⇒ Spring 选 JDK 代理 ⇒ 按具体类型注入全部失败")
            .isFalse();
    }

    // ── 2. 适配器委托：登记 / not-found / 按代次注销 ───────────────────────

    @Test
    @DisplayName("适配器委托 ChatService：登记成功 + 不存在回 not-found + 按代次注销")
    void adapterDelegatesAndEpochUnregisterStillWorks() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        ChatService chat = newChatService(registry);
        ChatPeerEndpointActivator adapter = new ChatPeerEndpointActivator(providerOf(chat));

        assertThat(adapter.activateSession(EXISTING)).isEqualTo(PeerEndpointActivator.Result.ACTIVATED);
        PeerSessionRegistry.LiveSession live = registry.resolve(EXISTING).orElseThrow();
        assertThat(live.accepting()).isTrue();
        assertThat(live.closing()).isFalse();

        assertThat(adapter.activateSession(MISSING))
            .as("★ 会话行不存在才回 not-found")
            .isEqualTo(PeerEndpointActivator.Result.NOT_FOUND);

        // 注销仍按代次：错代次 no-op，正代次生效（close/delete 不留幽灵 endpoint）
        assertThat(registry.unregister(EXISTING, live.endpointEpoch() - 1)).isFalse();
        assertThat(registry.resolve(EXISTING)).isPresent();
        assertThat(registry.unregister(EXISTING, live.endpointEpoch())).isTrue();
        assertThat(registry.resolve(EXISTING)).isEmpty();
    }

    @Test
    @DisplayName("按标题登记走同一条链（委托不打折）")
    void adapterDelegatesByName() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        ChatService chat = newChatService(registry);
        ChatPeerEndpointActivator adapter = new ChatPeerEndpointActivator(providerOf(chat));

        assertThat(adapter.activateSessionsByName("beta-peer"))
            .isEqualTo(PeerEndpointActivator.Result.ACTIVATED);
        assertThat(registry.resolve(EXISTING)).isPresent();
        assertThat(adapter.activateSessionsByName("ghost-session"))
            .isEqualTo(PeerEndpointActivator.Result.NOT_FOUND);
    }

    @Test
    @DisplayName("没有 ChatService bean（非 Spring 单测）⇒ UNAVAILABLE，不抛")
    void missingChatServiceIsUnavailable() {
        ChatPeerEndpointActivator adapter = new ChatPeerEndpointActivator(null);
        assertThat(adapter.activateSession(EXISTING)).isEqualTo(PeerEndpointActivator.Result.UNAVAILABLE);
        assertThat(adapter.activateSessionsByName("x")).isEqualTo(PeerEndpointActivator.Result.UNAVAILABLE);
    }

    // ── 3. Spring 容器：按具体类型 / 按接口都注入成功 ───────────────────────

    @Test
    @DisplayName("启用 @EnableAsync：无接口的 @Async bean 走 CGLIB，按具体类型注入成功；接口侧注入适配器")
    void asyncBeanWithoutInterfacesStaysInjectableByConcreteType() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ProbeConfig.class);
            ctx.refresh();

            // 按具体类型注入（CGLIB 子类，类型仍是目标类）
            ProbeConsumer consumer = ctx.getBean(ProbeConsumer.class);
            assertThat(consumer.target).as("★ 按具体类型注入必须成功（旧坑：JDK 代理 ⇒ 注入失败）").isNotNull();
            assertThat(AopUtils.isAopProxy(consumer.target)).as("@Async ⇒ 有代理").isTrue();
            assertThat(AopUtils.isJdkDynamicProxy(consumer.target))
                .as("★ 目标类无用户接口 ⇒ 必须是 CGLIB 代理，不是 JDK 动态代理")
                .isFalse();

            // 按接口注入（适配器）
            assertThat(consumer.activator).as("按接口注入必须成功").isNotNull();
            assertThat(ctx.getBean(PeerEndpointActivator.class))
                .isInstanceOf(ChatPeerEndpointActivator.class);
            // 容器里没有 ChatService bean ⇒ 适配器优雅降级
            assertThat(consumer.activator.activateSession(EXISTING))
                .isEqualTo(PeerEndpointActivator.Result.UNAVAILABLE);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static ChatService newChatService(PeerSessionRegistry registry) {
        ChatService chat = new ChatService();
        ReflectionTestUtils.setField(chat, "sessionMapper", sessionMapper());
        ReflectionTestUtils.setField(chat, "peerSessionRegistry", registry);
        return chat;
    }

    private static SessionMapper sessionMapper() {
        SessionMapper mapper = Mockito.mock(SessionMapper.class);
        SessionRecord existing = new SessionRecord();
        existing.setId(EXISTING);
        existing.setTitle("beta-peer");
        Mockito.when(mapper.selectOneById(anyString()))
            .thenAnswer(inv -> EXISTING.equals(inv.getArgument(0)) ? existing : null);
        Mockito.when(mapper.selectListByQuery(ArgumentMatchers.any()))
            .thenReturn(List.of(existing));
        return mapper;
    }

    private static ObjectProvider<ChatService> providerOf(ChatService chat) {
        return new ObjectProvider<>() {
            @Override
            public ChatService getObject() {
                return chat;
            }
        };
    }

    /** 与 ChatService 同形（无接口 + @Async）的探针：证明代理语义，不需要起整个 app 上下文。 */
    static class AsyncProbe {
        @Async
        public void run() {
            // no-op
        }
    }

    static class ProbeConsumer {
        @org.springframework.beans.factory.annotation.Autowired
        private AsyncProbe target;

        @org.springframework.beans.factory.annotation.Autowired
        private PeerEndpointActivator activator;
    }

    @Configuration
    @EnableAsync
    static class ProbeConfig {
        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }

        @Bean
        ProbeConsumer probeConsumer() {
            return new ProbeConsumer();
        }

        @Bean
        PeerEndpointActivator peerEndpointActivator(ObjectProvider<ChatService> chatServiceProvider) {
            return new ChatPeerEndpointActivator(chatServiceProvider);
        }
    }
}
