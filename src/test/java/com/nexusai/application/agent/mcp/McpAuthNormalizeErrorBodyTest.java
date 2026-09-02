package com.nexusai.application.agent.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S03 R2-04 X-4] normalizeOAuthErrorBody 测试 · 对齐 CC auth.ts:157-190
 * （2xx + error body → 400 error-class 映射；NONSTANDARD_INVALID_GRANT_ALIASES
 * invalid_refresh_token/expired_refresh_token/token_expired → invalid_grant，auth.ts:147-151）。
 *
 * <p><b>WHY（意图验证）</b>: 旧路径（D-S03-3）把 Slack 系 200 + error body 当成功解析 →
 * parseTokens 无 access_token 返回 null → 误分类 NO_TOKENS_RETURNED，invalid_grant 永不被
 * 识别（R2-04 X-4 语义错位）。本测试用本地 {@link HttpServer} 真发 POST 断言：
 * <ol>
 *   <li>200 + invalid_refresh_token / expired_refresh_token / token_expired → InvalidGrantError</li>
 *   <li>200 + invalid_grant（RFC 标准）→ InvalidGrantError</li>
 *   <li>200 + 其他 error（unauthorized_client）→ MCPRefreshFailed(REQUEST_FAILED)</li>
 *   <li>200 + access_token（正常 token 响应）→ 解析为 Tokens（不变）</li>
 *   <li>400 + error body → MCPRefreshFailed（非 2xx 原样抛错，不受 normalize 影响）</li>
 * </ol>
 */
class McpAuthNormalizeErrorBodyTest {

    private static HttpServer server;
    private static String endpoint;
    private static volatile String nextBody = "{}";
    private static volatile int nextStatus = 200;
    private static DefaultOAuthHttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] resp = nextBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        client = new DefaultOAuthHttpClient(HttpClient.newHttpClient());
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static McpAuth.Tokens refresh() {
        return client.refreshTokens(endpoint, Map.of(
            "client_id", "cid", "refresh_token", "rt", "grant_type", "refresh_token"));
    }

    private void serve(int status, String body) {
        nextStatus = status;
        nextBody = body;
    }

    @Test
    @DisplayName("[X-4] 200 + invalid_refresh_token（Slack 实测）→ InvalidGrantError（归一 invalid_grant）")
    void ok200_withInvalidRefreshToken_throwsInvalidGrant() {
        serve(200, "{\"error\":\"invalid_refresh_token\",\"error_description\":\"expired\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.InvalidGrantError.class)
            .hasMessageContaining("invalid_refresh_token");
    }

    @Test
    @DisplayName("[X-4] 200 + expired_refresh_token → InvalidGrantError（别名归一）")
    void ok200_withExpiredRefreshToken_throwsInvalidGrant() {
        serve(200, "{\"error\":\"expired_refresh_token\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.InvalidGrantError.class);
    }

    @Test
    @DisplayName("[X-4] 200 + token_expired → InvalidGrantError（别名归一）")
    void ok200_withTokenExpired_throwsInvalidGrant() {
        serve(200, "{\"error\":\"token_expired\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.InvalidGrantError.class);
    }

    @Test
    @DisplayName("[X-4] 200 + invalid_grant（RFC 标准）→ InvalidGrantError")
    void ok200_withInvalidGrant_throwsInvalidGrant() {
        serve(200, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.InvalidGrantError.class);
    }

    @Test
    @DisplayName("[X-4] 200 + 其他 error（unauthorized_client）→ MCPRefreshFailed(REQUEST_FAILED, 400) 非 InvalidGrant")
    void ok200_withOtherError_throwsMCPRefreshFailed() {
        serve(200, "{\"error\":\"unauthorized_client\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.MCPRefreshFailed.class)
            .hasMessageContaining("unauthorized_client");
    }

    @Test
    @DisplayName("[X-4] 200 + access_token（正常 token 响应）→ 解析为 Tokens（normalize 不动正常响应）")
    void ok200_withAccessToken_parsesTokens() {
        serve(200, "{\"access_token\":\"at-new\",\"refresh_token\":\"rt-new\","
            + "\"expires_in\":3600,\"scope\":\"read\"}");
        McpAuth.Tokens tokens = refresh();
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isEqualTo("at-new");
        assertThat(tokens.refreshToken()).isEqualTo("rt-new");
    }

    @Test
    @DisplayName("[X-4] 400 + error body（非 2xx）→ MCPRefreshFailed 原样抛错（normalize 仅作用于 2xx，auth.ts:160-162）")
    void http400_withErrorBody_throwsMCPRefreshFailed() {
        serve(400, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(McpAuthNormalizeErrorBodyTest::refresh)
            .isInstanceOf(McpAuth.MCPRefreshFailed.class)
            .hasMessageContaining("HTTP 400");
    }

}
