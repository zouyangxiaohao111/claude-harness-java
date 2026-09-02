package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [canUseTool v4 Gap①] bridge/channel 竞速回调生产接线 Spring 上下文测试.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v3 对抗复验判 canUseTool PARTIAL — 残留缺口①：
 * {@link BridgePermissionCallbacks} / {@link ChannelPermissionCallbacks} 是接口，生产无
 * {@code @Component} 实现 → WebSocketPermissionPrompter {@code @Autowired(required=false)}
 * 注入 null → startBridgeRace / startChannelRace 直接 return，CCR 远程弹窗 / 通道中继竞速
 * 生产永不参与（用户明确要求"限制就开放"）。
 *
 * <p>本测试用 {@link ApplicationContextRunner} 真实拉起 Spring 上下文（import 真实
 * {@code @Component} + mock SimpMessagingTemplate），证明：
 * <ol>
 *   <li>{@link StompBridgePermissionCallbacks} / {@link StompChannelPermissionCallbacks}
 *       是 @Component bean — Spring 真实实例化（不再注入 null）。</li>
 *   <li>生产 WebSocketPermissionPrompter 的 bridgeCallbacks / channelCallbacks 字段注入真实
 *       Stomp bean（竞速生产参与的前提，不再是 null → 直接 return）。</li>
 * </ol>
 *
 * @see StompBridgePermissionCallbacks
 * @see StompChannelPermissionCallbacks
 * @see WebSocketPermissionPrompter
 * @since canUseTool v4 修复
 */
@DisplayName("[canUseTool v4 Gap①] bridge/channel 竞速回调生产接线 Spring 上下文")
class BridgeChannelSpringContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BridgeChannelSpringConfig.class);

    @Test
    @DisplayName("StompBridge/ChannelPermissionCallbacks 是 @Component bean（Spring 真实实例化）")
    void stompBridgeAndChannelCallbacksAreSpringBeans() {
        // WHY: gap① — 接口生产无 @Component 实现 → 注入 null。必须证明 Spring 上下文真实创建
        //   这两个 bean（bridge/channel 竞速生产可达的前提）。
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StompBridgePermissionCallbacks.class))
                .as("bridge 竞速回调必须有生产 @Component 实现 bean（v4 修复 gap①）")
                .isNotNull();
            assertThat(ctx.getBean(StompChannelPermissionCallbacks.class))
                .as("channel 竞速回调必须有生产 @Component 实现 bean（v4 修复 gap①）")
                .isNotNull();
        });
    }

    @Test
    @DisplayName("WebSocketPermissionPrompter 注入真实 bridge/channel bean（非 null，不再直接 return）")
    void prompterInjectsRealBridgeChannelBeans() throws Exception {
        // WHY: 生产 prompter 的 @Autowired(required=false) 字段在 Spring 容器内必须解析到真实
        //   @Component 实现。若仍注入 null → startBridgeRace/startChannelRace 直接 return（v3
        //   缺口① 未修复）。
        runner.run(ctx -> {
            WebSocketPermissionPrompter prompter = ctx.getBean(WebSocketPermissionPrompter.class);
            Object bridge = readField(prompter, "bridgeCallbacks");
            Object channel = readField(prompter, "channelCallbacks");
            assertThat(bridge)
                .as("prompter.bridgeCallbacks 必须注入真实 StompBridgePermissionCallbacks bean")
                .isInstanceOf(StompBridgePermissionCallbacks.class);
            assertThat(channel)
                .as("prompter.channelCallbacks 必须注入真实 StompChannelPermissionCallbacks bean")
                .isInstanceOf(StompChannelPermissionCallbacks.class);
        });
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    @Configuration
    @Import({
        StompBridgePermissionCallbacks.class,
        StompChannelPermissionCallbacks.class,
        WebSocketPermissionPrompter.class
    })
    static class BridgeChannelSpringConfig {
        /** WebSocketPermissionPrompter 的 @Autowired 必填依赖. */
        @Bean
        SimpMessagingTemplate wsTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }
}
