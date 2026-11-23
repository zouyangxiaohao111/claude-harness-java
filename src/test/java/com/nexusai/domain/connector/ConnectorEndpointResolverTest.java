package com.nexusai.domain.connector;

import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** URL/header 模板渲染（官方 MCP 接入）。 */
class ConnectorEndpointResolverTest {

    @Test
    @DisplayName("URL 占位符：值 URL-encode（& → %26，空 → %20）")
    void rendersUrlEncoded() {
        String out = ConnectorEndpointResolver.renderUrl(
                "https://mcp.example/mcp?key={key}&c={company}",
                Map.of("key", "a&b", "company", "acme co"));
        assertThat(out).isEqualTo("https://mcp.example/mcp?key=a%26b&c=acme+co");
    }

    @Test
    @DisplayName("header 占位符：原样替换（不 encode）")
    void rendersHeaderRaw() {
        assertThat(ConnectorEndpointResolver.renderHeader("Bearer {token}",
                Map.of("token", "a b&c"))).isEqualTo("Bearer a b&c");
    }

    @Test
    @DisplayName("缺字段 → ValidationException（点名字段，不生成半截 URL）")
    void missingFieldFails() {
        assertThatThrownBy(() -> ConnectorEndpointResolver.renderUrl(
                "https://x/mcp?key={key}", Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> ConnectorEndpointResolver.renderHeader(
                "X-Test: {token}", Map.of("token", "  ")))
                .isInstanceOf(ValidationException.class);
    }
}
