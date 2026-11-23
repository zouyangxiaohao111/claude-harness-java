package com.nexusai.application.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用层默认开关守卫（2026-11-23 用户裁定：浏览器 / computer-use <b>默认开启</b>）。
 *
 * <p>两个 flag 由 {@code BrowserMcpToolConfig} / {@code ComputerUseToolConfig} 读取；
 * 这里直接解析 {@code application.yml}，防止后续误改（注册≠可用：CU 仍受默认禁用集约束）。
 */
@DisplayName("application.yml · 浏览器/computer-use 默认开启")
class FeatureFlagDefaultsTest {

    @Test
    @DisplayName("nexusai.feature.browser-mcp=true 且 nexusai.feature.computer-use=true")
    void featureFlagsDefaultTrue() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
            loader.load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        PropertySource<?> ps = sources.get(0);

        assertThat(ps.getProperty("nexusai.feature.browser-mcp")).isEqualTo(true);
        assertThat(ps.getProperty("nexusai.feature.computer-use")).isEqualTo(true);
    }
}
