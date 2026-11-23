package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 配置「面板 → env 镜像 → 运行时」接线（[2026-11-23] 对比报告 ④-3-1）。
 *
 * <p>三处 {@code OAuthServerConfig} 构造（McpAuthHeaderProvider / McpToolPool×2）统一走
 * {@link McpOAuth#oauthServerConfig}：本用例锁死「env 里的 oauth 字段真的被读出来」。
 * 变异自检：把该方法的 oauth 参数改回 null → 本用例必须红。
 */
class McpOAuthWiringTest {

    @Test
    @DisplayName("env 镜像的 oauth 字段全部喂进 OAuthServerConfig；headers 剥除镜像键")
    void oauthConfigFromEnvMirror() {
        String json = """
                {"clientId":"cid-1","callbackPort":38001,
                 "authServerMetadataUrl":"https://as.example.com/meta",
                 "xaa":true,"scopes":"read write",
                 "authorizationEndpoint":"https://as.example.com/authorize",
                 "tokenEndpoint":"https://as.example.com/token"}
                """;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("X-Api-Key", "k1");
        env.put(McpOAuth.ENV_OAUTH_MIRROR_KEY, Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        McpAuth.OAuthServerConfig cfg = McpOAuth.oauthServerConfig(
                "http", "https://mcp.example.com/mcp", env);

        assertThat(cfg.clientId()).isEqualTo("cid-1");
        assertThat(cfg.configuredCallbackPort()).isEqualTo("38001");
        assertThat(cfg.authServerMetadataUrl()).isEqualTo("https://as.example.com/meta");
        assertThat(cfg.scopes()).isEqualTo("read write");
        assertThat(cfg.authorizationEndpoint()).isEqualTo("https://as.example.com/authorize");
        assertThat(cfg.tokenEndpoint()).isEqualTo("https://as.example.com/token");
        // headers 是真实 HTTP 头（已剥 __mcp_oauth__，绝不把镜像键发出去）
        assertThat(cfg.headers()).containsEntry("X-Api-Key", "k1");
        assertThat(cfg.headers()).doesNotContainKey(McpOAuth.ENV_OAUTH_MIRROR_KEY);
    }

    @Test
    @DisplayName("无镜像/坏镜像 → 空配置（不抛，回退 DCR/随机端口）")
    void noMirrorYieldsEmptyConfig() {
        McpAuth.OAuthServerConfig cfg = McpOAuth.oauthServerConfig(
                "sse", "https://mcp.example.com/sse", Map.of());
        assertThat(cfg.clientId()).isNull();
        assertThat(cfg.configuredCallbackPort()).isNull();
        assertThat(cfg.scopes()).isNull();
        assertThat(cfg.authorizationEndpoint()).isNull();

        McpAuth.OAuthServerConfig bad = McpOAuth.oauthServerConfig(
                "sse", "u", Map.of(McpOAuth.ENV_OAUTH_MIRROR_KEY, "not-base64"));
        assertThat(bad.clientId()).isNull();
    }
}
