package com.nexusai.domain.im_channel.oauth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 飞书 user token 续期的协议层测试。
 *
 * <h2>WHY 这些断言值得存在</h2>
 * 用户提出的问题是「为什么每次都要授权」—— 答案就是这里：
 * 授权时申请的 {@code offline_access} 会给 refresh_token，只要它有效就不该再让用户点同意。
 * 续期逻辑里有两个**静默出错**的坑，出错时不会抛异常、只会表现成「过两小时又要授权」：
 * <ol>
 *   <li>刷新响应不带 {@code refresh_token} 时被写成 null ⇒ 下次刷新没凭据（飞书/Google 都这样）；</li>
 *   <li>飞书业务错误配 HTTP 200 ⇒ 只看状态码会把失败当成功，存一个空 token 进去。</li>
 * </ol>
 * ⚠️ 用本地 stub，⛔ 不打真飞书。
 */
class FeishuUserTokenRefresherTest {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    /** 下一次 stub 返回的响应体。 */
    private volatile String nextResponse = "{}";
    private volatile int nextStatus = 200;

    private FeishuUserTokenRefresher refresher;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            synchronized (requestBodies) {
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            byte[] out = nextResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        refresher = new FeishuUserTokenRefresher(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("正常续期：返回新 access token，请求体是 refresh_token grant")
    void refreshReturnsNewAccessToken() throws Exception {
        nextResponse = "{\"access_token\":\"u-new\",\"refresh_token\":\"r-new\","
                + "\"expires_in\":7200,\"scope\":\"im:message\"}";

        var r = refresher.refresh("cli_x", "secret", "r-old");

        assertThat(r.accessToken()).isEqualTo("u-new");
        assertThat(r.refreshToken()).isEqualTo("r-new");
        assertThat(r.expiresAt()).isNotNull();
        synchronized (requestBodies) {
            String body = requestBodies.get(0);
            assertThat(body).contains("grant_type=refresh_token")
                    .contains("refresh_token=r-old")
                    .contains("client_id=cli_x");
            // 客户端认证走 Basic（与 device flow 一致）
            assertThat(authHeaders.get(0)).startsWith("Basic ");
        }
    }

    @Test
    @DisplayName("🔴 响应不带 refresh_token ⇒ 必须保留旧值（否则下次续期没凭据）")
    void keepsOldRefreshTokenWhenResponseOmitsIt() throws Exception {
        // 飞书/Google 刷新时经常不再下发 refresh_token
        nextResponse = "{\"access_token\":\"u-new\",\"expires_in\":7200}";

        var r = refresher.refresh("cli_x", "secret", "r-old");

        assertThat(r.accessToken()).isEqualTo("u-new");
        assertThat(r.refreshToken())
                .as("响应没给新 refresh_token 时必须沿用旧的 —— 写成 null 会让下一次续期彻底失败")
                .isEqualTo("r-old");
    }

    @Test
    @DisplayName("🔴 HTTP 200 + 业务错误 ⇒ 必须抛错，不能当成功（否则会存空 token）")
    void businessErrorWithHttp200Throws() {
        nextStatus = 200;
        nextResponse = "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}";

        assertThatThrownBy(() -> refresher.refresh("cli_x", "secret", "r-old"))
                .isInstanceOf(FeishuUserTokenRefresher.RefreshException.class)
                .hasMessageContaining("invalid_grant");
    }

    @Test
    @DisplayName("参数不全 ⇒ 直接抛错，不发请求")
    void missingParamsThrowsWithoutRequest() {
        assertThatThrownBy(() -> refresher.refresh("cli_x", "secret", ""))
                .isInstanceOf(FeishuUserTokenRefresher.RefreshException.class);
        synchronized (requestBodies) {
            assertThat(requestBodies).isEmpty();
        }
    }

    @Test
    @DisplayName("响应没给 expires_in ⇒ expiresAt 为 null（上层按「视为过期」处理，会再续一次）")
    void noExpiresInGivesNullExpiry() throws Exception {
        nextResponse = "{\"access_token\":\"u-new\",\"refresh_token\":\"r-new\"}";

        var r = refresher.refresh("cli_x", "secret", "r-old");

        assertThat(r.accessToken()).isEqualTo("u-new");
        assertThat(r.expiresAt()).isNull();
    }
}
