package com.nexusai.domain.im_channel.send;

import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.impl.DingtalkSendStrategy;
import com.nexusai.domain.im_channel.send.impl.FeishuSendStrategy;
import com.nexusai.domain.im_channel.send.impl.WechatSendStrategy;
import com.nexusai.domain.im_channel.send.impl.WecomSendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 装配测试：四个平台的策略能起来、工厂能收全、能力声明都合法。
 *
 * <h2>WHY 需要这条</h2>
 * 单测都通过但**应用起不来**是很常见的事故，根源通常是：
 * <ul>
 *   <li>类有多个构造器却没标 {@code @Autowired} ⇒ Spring 找不到该用哪个（
 *       {@code FeishuSendStrategy} 就踩过这个坑，会直接启动失败）；</li>
 *   <li>某个 {@code @Component} 的依赖没被注册 ⇒ NoSuchBeanDefinitionException。</li>
 * </ul>
 * 这里的断言能让这类问题在 CI 阶段暴露，而不是等到用户重启后端。
 *
 * <p>⚠️ 用 {@link AnnotationConfigApplicationContext} **只注册被测类**，
 * 不加载 {@code @SpringBootTest}（那会迁移用户真库）。
 */
class ImSendStrategiesWiringTest {

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(
                // 票服务与网关客户端
                FeishuTokenService.class,
                WecomCliTokenService.class,
                WecomCliClient.class,
                WecomMessageApi.class,
                // 媒体上传器（各平台策略的构造依赖）
                com.nexusai.domain.im_channel.cli.WecomMediaApi.class,
                com.nexusai.domain.im_channel.send.impl.WechatMediaUploader.class,
                com.nexusai.domain.im_channel.send.impl.FeishuMediaUploader.class,
                // 四个平台策略
                FeishuSendStrategy.class,
                WecomSendStrategy.class,
                WechatSendStrategy.class,
                DingtalkSendStrategy.class,
                // 工厂
                ImSendStrategyFactory.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    @DisplayName("Spring 能装配四个策略，工厂收全 4 个平台")
    void allFourPlatformsAreWired() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            ImSendStrategyFactory factory = ctx.getBean(ImSendStrategyFactory.class);

            assertThat(factory.supportedPlatforms())
                    .containsExactlyInAnyOrder("feishu", "wecom", "wechat", "dingtalk");
            assertThat(factory.all()).hasSize(4);
        }
    }

    @Test
    @DisplayName("每个策略的 capability() 都合法且非 null（发送前判定的前提）")
    void everyCapabilityIsValid() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            ImSendStrategyFactory factory = ctx.getBean(ImSendStrategyFactory.class);
            for (ImSendStrategy s : factory.all()) {
                SendCapability cap = s.capability();
                assertThat(cap).as("%s.capability() 不能为 null", s.platform()).isNotNull();
                if (cap.canSendProactively()) {
                    assertThat(cap.maxTextChars()).as("%s 文本上限必须 > 0", s.platform()).isPositive();
                } else {
                    assertThat(cap.unsupportedReason())
                            .as("%s 声明不支持时必须给原因", s.platform()).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("能力矩阵与平台约束一致（微信要 token、钉钉不支持、企微/飞书可主动发）")
    void capabilityMatrixMatchesPlatformReality() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            ImSendStrategyFactory factory = ctx.getBean(ImSendStrategyFactory.class);

            SendCapability feishu = factory.of("feishu").capability();
            assertThat(feishu.canSendProactively()).isTrue();
            assertThat(feishu.needsReplyToken()).isFalse();
            assertThat(feishu.supportsGroup()).isTrue();

            SendCapability wecom = factory.of("wecom").capability();
            assertThat(wecom.canSendProactively()).as("企微换 CLI token 后可主动发（含群聊）").isTrue();
            assertThat(wecom.supportsGroup()).isTrue();

            SendCapability wechat = factory.of("wechat").capability();
            assertThat(wechat.canSendProactively()).isTrue();
            assertThat(wechat.needsReplyToken()).as("微信必须带 context_token").isTrue();

            SendCapability dingtalk = factory.of("dingtalk").capability();
            assertThat(dingtalk.canSendProactively()).isFalse();
            assertThat(dingtalk.unsupportedReason()).contains("钉钉");
        }
    }

    @Test
    @DisplayName("未注册的 platform 走兜底策略，不会抛异常")
    void unknownPlatformFallsBack() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            ImSendStrategyFactory factory = ctx.getBean(ImSendStrategyFactory.class);

            ImSendContext ctxObj = new ImSendContext("i", "unknown", "p", "p2p", "{}", null);
            ImSendResult r = factory.of("unknown").send(ctxObj, "hi");

            assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
            assertThat(r.reason()).isNotBlank();
        }
    }
}
