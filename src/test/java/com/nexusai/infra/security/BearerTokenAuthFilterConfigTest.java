package com.nexusai.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.repository.oauth_account.mapper.AccountOAuthTokenMapper;
import jakarta.servlet.FilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [WF-D require-oauth-auth] · {@link BearerTokenAuthFilterConfig} 的 @Value 配置接线验证。
 *
 * <p><b>WHY (意图验证 · 规则九)</b>: {@code requireOAuthAuth} 原为「2 参构造器默认 true + Javadoc
 * 口头约束」，上线前若忘记把过渡态 {@code false} 翻回 {@code true}，{@code /api/v1/schedules/**}
 * 会静默回到零鉴权（fail-open）。本改动把它提升为 {@code nexusai.security.require-oauth-auth}
 * 配置项（默认 true=deny-all，对齐 CC auth.ts:1960 {@code validateForceLoginOrg} fail-closed：
 * 无法校验 org 时 {@code return { valid: false } } 拒绝，非放行）。测试须锁定：配置缺失仍 deny
 * （fail-closed 兜底，不因 yml 键缺失而放行），且显式 false 才激活 fail-open 逃逸阀——
 * 才体现「默认安全、逃逸阀受配置项显式控制」这一意图为何重要。
 *
 * <p>用 {@link ApplicationContextRunner} 而非 @SpringBootTest — 避免 Quartz/DB/WebSocket 耗时依赖
 * （镜像 R32B7a2_ConfigToolAutoConfigurationConditionalTest）。
 */
class BearerTokenAuthFilterConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    /** 记录链上「下一层」是否被调用，用于断言 filter 是否放行。 */
    private static FilterChain chainWithProbe(AtomicBoolean nextInvoked) {
        return (request, response) -> nextInvoked.set(true);
    }

    private static MockHttpServletRequest requestWithoutAuth() {
        return new MockHttpServletRequest("GET", "/api/v1/schedules");
    }

    @Test
    @DisplayName("配置缺失（未设 nexusai.security.require-oauth-auth）→ deny-all（无 token 401，不放行）")
    void defaultDeniesWhenConfigMissing() {
        // WHY: @Value 默认 :true 兜底 —— yml 键缺失仍 deny（对齐 CC fail-closed），
        // 不得因「配置项不存在」而 fail-open。
        runner.run(ctx -> {
            BearerTokenAuthFilter filter = ctx.getBean(BearerTokenAuthFilter.class);
            assertThat(ReflectionTestUtils.getField(filter, "requireOAuthAuth"))
                .as("配置缺失时注入默认 true").isEqualTo(true);

            AtomicBoolean next = new AtomicBoolean(false);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(requestWithoutAuth(), res, chainWithProbe(next));

            assertThat(res.getStatus()).as("配置缺失仍须 deny-all → 401").isEqualTo(401);
            assertThat(next.get()).as("配置缺失不得放行到下一层").isFalse();
        });
    }

    @Test
    @DisplayName("显式 false → 条件放行（fail-open 逃逸阀受配置项控制，无 token 也 next=true 且非 401）")
    void explicitFalseEnablesPassThrough() {
        // WHY: fail-open 逃逸阀仅在显式置 false 时激活（token 生产链未接通过渡态），
        // 验证该阀确实由配置项（而非构造器硬编码）控制。
        runner.withPropertyValues("nexusai.security.require-oauth-auth=false")
            .run(ctx -> {
                BearerTokenAuthFilter filter = ctx.getBean(BearerTokenAuthFilter.class);
                assertThat(ReflectionTestUtils.getField(filter, "requireOAuthAuth"))
                    .as("显式 false 应注入 false").isEqualTo(false);

                AtomicBoolean next = new AtomicBoolean(false);
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(requestWithoutAuth(), res, chainWithProbe(next));

                assertThat(res.getStatus()).as("条件放行不得写 401").isNotEqualTo(401);
                assertThat(next.get()).as("条件放行必须直接放行到下一层").isTrue();
            });
    }

    @Test
    @DisplayName("FilterRegistrationBean 覆盖全部受保护端点族（schedules/memory/sessions/away-summary/dream/config/export/attachments）")
    void registrationCoversAllProtectedEndpointFamilies() {
        // WHY (IMP-MV2-17 鉴权收敛)：过滤器注册覆盖面是鉴权收敛的 enforcement 点 —— 漏注册一族
        // 即该族端点回到零鉴权（F2 R-1 / D3 ?1 / F3 EVD-F3-21 四端点族）。
        // 断言锁定全部 16 pattern（每族双 pattern 覆盖裸路径 + 子路径；away-summary/dream 为精确路径）。
        //   + /api/agent/dream（OPD-CM5-E-06，ExtractMemoriesController 手动整合 REST 载体）
        //   + /api/v1/session-memory/export（OPD-CM5-F-24，会话记忆导出）
        //   + /api/v1/attachments/content/upload/image（附件双模式 plan §6 鉴权补登：
        //     /content 附件表文件字节流式预览、/upload 大文件 multipart 落盘、/image 图片拉取——
        //     upload 为精确路径单 pattern；image/content 端点均在子路径，content 按族惯例补裸路径）
        runner.run(ctx -> {
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<BearerTokenAuthFilter> registration =
                ctx.getBean("bearerTokenAuthFilterRegistration", FilterRegistrationBean.class);

            assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                "/api/v1/schedules", "/api/v1/schedules/*",
                "/api/v1/memory", "/api/v1/memory/*",
                "/api/v1/sessions", "/api/v1/sessions/*",
                "/api/agent/away-summary",
                "/api/agent/dream",
                "/api/v1/session-memory/config", "/api/v1/session-memory/config/*",
                "/api/v1/session-memory/export", "/api/v1/session-memory/export/*",
                "/api/v1/attachments/content", "/api/v1/attachments/content/*",
                "/api/v1/attachments/upload",
                "/api/v1/attachments/image/*");
        });
    }

    /**
     * 测试配置: 显式注册 {@link BearerTokenAuthFilterConfig} + mock {@link AccountOAuthTokenService}
     * 与 {@link ObjectMapper} 两个依赖 bean，满足 @Bean 方法注入。
     *
     * <p>WHY: 避开 {@code @ComponentScan}（会触发整个应用扫描加载 Quartz/DB/WebSocket）。
     * mock AccountOAuthTokenService 免构造其 @Autowired 的 MyBatis mapper。
     * 因 Mockito mock 是 AccountOAuthTokenService 的子类，其父类 {@code @Autowired accountOAuthTokenMapper}
     * 字段仍会被 Spring 的 {@code AutowiredAnnotationBeanPostProcessor} 扫描并尝试注入，
     * 故需同时 mock {@link AccountOAuthTokenMapper} 满足该字段注入（否则上下文启动失败）。
     */
    @Configuration
    @Import(BearerTokenAuthFilterConfig.class)
    static class TestConfig {
        @Bean
        AccountOAuthTokenService accountOAuthTokenService() {
            return mock(AccountOAuthTokenService.class);
        }

        @Bean
        AccountOAuthTokenMapper accountOAuthTokenMapper() {
            return mock(AccountOAuthTokenMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
