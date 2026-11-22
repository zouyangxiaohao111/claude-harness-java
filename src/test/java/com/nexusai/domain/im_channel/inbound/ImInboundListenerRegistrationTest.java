package com.nexusai.domain.im_channel.inbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站监听实现的**注册守卫**（反射断言，不启 Spring 上下文）。
 *
 * <h2>WHY 这条必须有</h2>
 * {@link ImInboundManager} 按 instanceId 各拿一个监听器；它靠
 * {@code @Scope("prototype")} 保证「每次拿到的是新对象」。
 * 少了这个注解，第二个实例启动时拿到的是**同一个单例** ——
 * {@code running.put(instanceId, listener)} 会让两个实例映射到同一个对象，
 * 第二个实例的 {@code start()} 把第一个的字段全冲掉 ⇒ **第一个实例的连接被静默顶掉**。
 *
 * <p>这类问题编译通过、单测（mock 掉 Manager）也能过，只有真机接两个实例时才炸。
 *
 * <h2>为什么不用 {@code AnnotationConfigApplicationContext} 起真实上下文</h2>
 * 两个 Listener 都依赖 {@code ImChannelService}（要 mapper / CryptoUtil），
 * 起上下文就得拉一堆 DB 依赖。这里只验证「注解在不在」——
 * 它正是「多实例会不会互相覆盖」的唯一判据。
 */
class ImInboundListenerRegistrationTest {

    @Test
    @DisplayName("每个平台监听器都必须是 @Component + @Scope(prototype)")
    void allListenersArePrototypeComponents() throws Exception {
        String[] impls = {
                "com.nexusai.domain.im_channel.inbound.wecom.WecomInboundListener",
                "com.nexusai.domain.im_channel.inbound.wechat.WechatInboundListener",
        };
        for (String fqcn : impls) {
            Class<?> clazz;
            try {
                clazz = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                // 该平台实现还没落地时跳过（不算失败：三平台分批实现）
                continue;
            }
            assertThat(clazz.isAnnotationPresent(Component.class))
                    .as("%s 必须标 @Component（否则 ImInboundManager 找不到）", fqcn)
                    .isTrue();
            Scope scope = clazz.getAnnotation(Scope.class);
            assertThat(scope)
                    .as("%s 必须标 @Scope(prototype) —— 否则多实例共用同一个对象、互相覆盖连接", fqcn)
                    .isNotNull();
            assertThat(scope.value())
                    .as("%s 的 scope 必须是 prototype", fqcn)
                    .isEqualTo("prototype");
        }
    }

    @Test
    @DisplayName("监听器都实现了 ImInboundListener（契约一致）")
    void allListenersImplementContract() throws Exception {
        String[] impls = {
                "com.nexusai.domain.im_channel.inbound.wecom.WecomInboundListener",
                "com.nexusai.domain.im_channel.inbound.wechat.WechatInboundListener",
        };
        for (String fqcn : impls) {
            Class<?> clazz;
            try {
                clazz = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                continue;
            }
            assertThat(ImInboundListener.class.isAssignableFrom(clazz))
                    .as("%s 必须实现 ImInboundListener", fqcn)
                    .isTrue();
        }
    }
}
