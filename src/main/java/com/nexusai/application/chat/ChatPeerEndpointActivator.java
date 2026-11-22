package com.nexusai.application.chat;

import com.nexusai.application.agent.messaging.PeerEndpointActivator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PeerEndpointActivator} 的独立适配器：把「按需登记 endpoint」这件事从 {@link ChatService} 上摘下来。
 *
 * <h2>为什么需要这个类（2026-11-20 启动阻断）</h2>
 * <p>{@code ChatService} 带 {@code @Async}，Spring 的 AOP 代理选择规则是
 * 「目标类有用户提供的接口 ⇒ JDK 动态代理，否则 CGLIB」。上一批让 ChatService
 * {@code implements PeerEndpointActivator} 之后，它的 bean 类型退化成 {@code jdk.proxy2.$Proxy}，
 * 所有按具体类型注入 ChatService 的地方（{@code SessionService} 等 8 处）启动期全部失败：
 * <pre>Bean named 'chatService' is expected to be of type
 * 'com.nexusai.application.chat.ChatService' but was actually of type 'jdk.proxy2.$Proxy181'</pre>
 *
 * <h2>修法</h2>
 * <p>ChatService 不再实现任何接口（恢复 CGLIB 代理 ⇒ 具体类型注入继续可用）；
 * 需要接口形态的地方由本适配器提供。本类没有 {@code @Async}，不会被代理。
 *
 * <h2>为什么用 ObjectProvider 惰性取</h2>
 * <p>依赖链 {@code ChatService → ToolRegistry → SendMessageTool → MessageRecipientResolver → 本接口}；
 * 解析侧本来就是 {@link ObjectProvider} 惰性取用，本类再懒一层 ⇒ 不引入启动期循环依赖，
 * 且非 Spring 单测（没有 ChatService bean）时返回 {@code UNAVAILABLE} 而不是崩。
 *
 * <h2>行为不变</h2>
 * <p>纯委托：登记仍走 {@link ChatService#activateSession(String)} 的同一条登记链
 * （标题快照 + 有效权限模式 + 句柄记账）⇒ close/delete 仍能按代次注销，不留幽灵 endpoint。
 */
@Component
public class ChatPeerEndpointActivator implements PeerEndpointActivator {

    private final ObjectProvider<ChatService> chatServiceProvider;

    public ChatPeerEndpointActivator(ObjectProvider<ChatService> chatServiceProvider) {
        this.chatServiceProvider = chatServiceProvider;
    }

    private ChatService chatService() {
        ObjectProvider<ChatService> provider = chatServiceProvider;
        if (provider == null) {
            return null;
        }
        try {
            return provider.getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Result activateSession(String sessionId) {
        ChatService chat = chatService();
        if (chat == null) {
            return Result.UNAVAILABLE;
        }
        return chat.activateSession(sessionId);
    }

    @Override
    public Result activateSessionsByName(String name) {
        ChatService chat = chatService();
        if (chat == null) {
            return Result.UNAVAILABLE;
        }
        return chat.activateSessionsByName(name);
    }
}
