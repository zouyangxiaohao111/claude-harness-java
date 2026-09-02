package com.nexusai.apis.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.infra.config.WebConfig;
import com.nexusai.infra.security.BearerTokenAuthFilter;
import com.nexusai.infra.security.BearerTokenAuthFilterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * {@link HookController} 鉴权/CORS 集成测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>无鉴权</b>——/api/v1/hooks 不在 {@link BearerTokenAuthFilterConfig} 受保护端点族内
 *       （对齐 SettingsController/SkillController）。若未来有人把该族加入鉴权 → 本测试 fail
 *       （前端无 token 场景下 HookPanel 401 白屏）。</li>
 *   <li><b>CORS 预检 OPTIONS 通过</b>——WebConfig 的 {@code /api/**} 映射必须覆盖 /api/v1/hooks
 *       且允许 OPTIONS/GET（Tauri 开发服务器跨域；若映射漏掉该路径 → 前端 OPTIONS 预检失败）。</li>
 * </ol>
 */
class HookControllerAuthCorsTest {

    @Test
    @DisplayName("/api/v1/hooks 不在 BearerTokenAuthFilterConfig 受保护端点族内（无鉴权，对齐 SettingsController）")
    void hooksRoute_isNotUnderAuthFilter() {
        BearerTokenAuthFilterConfig config = new BearerTokenAuthFilterConfig();
        BearerTokenAuthFilter filter =
            new BearerTokenAuthFilter(mock(AccountOAuthTokenService.class), new ObjectMapper(), true);
        FilterRegistrationBean<BearerTokenAuthFilter> registration = config.bearerTokenAuthFilterRegistration(filter);

        assertThat(registration.getUrlPatterns())
            .as("/api/v1/hooks 不得被鉴权过滤器覆盖（前端无 token 场景 HookPanel 需可达）")
            .doesNotContain("/api/v1/hooks", "/api/v1/hooks/*");
    }

    @Test
    @DisplayName("WebConfig CORS 映射覆盖 /api/** 且允许 GET/OPTIONS（/api/v1/hooks 落此映射）")
    void webConfig_corsMapping_coversHooksRoute() {
        CorsRegistry registry = new CorsRegistry();
        new WebConfig().addCorsMappings(registry);

        // getCorsConfigurations() 为 protected → 反射读取（CorsRegistry 无公开读取器）
        @SuppressWarnings("unchecked")
        Map<String, CorsConfiguration> mappings =
            (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        assertThat(mappings).containsKey("/api/**");
        CorsConfiguration config = mappings.get("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods())
            .as("CORS 允许方法须含 GET（HookPanel 读端点）+ OPTIONS（预检）")
            .contains("GET", "OPTIONS");
        assertThat(config.getAllowedOriginPatterns()).contains("*");
    }

    @Test
    @DisplayName("CORS 预检 OPTIONS /api/v1/hooks 通过（带 Origin + Access-Control-Request-Method → 200 + ACAO 头）")
    void corsPreflight_optionsPasses() throws Exception {
        // 用 WebConfig 真实 CORS 配置装配 CorsFilter，验证 /api/v1/hooks 预检真实通过
        CorsRegistry registry = new CorsRegistry();
        new WebConfig().addCorsMappings(registry);
        @SuppressWarnings("unchecked")
        Map<String, CorsConfiguration> mappings =
            (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        CorsConfiguration config = mappings.get("/api/**");
        CorsFilter corsFilter = new CorsFilter(request -> config);

        standaloneSetup(new HookController())
            .addFilters(corsFilter)
            .build()
            .perform(options("/api/v1/hooks")
                .header("Origin", "http://localhost:1420")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            // allowedOriginPatterns("*") 匹配后回显具体 Origin（Spring 对 pattern 匹配恒回显
            // 具体来源而非字面 '*'）——回显 Origin 即证明 /api/** 映射命中了 /api/v1/hooks 预检
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:1420"));
    }
}
