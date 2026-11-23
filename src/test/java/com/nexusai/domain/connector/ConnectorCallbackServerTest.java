package com.nexusai.domain.connector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器 loopback 回调监听的 <b>state 校验</b>与路由语义。
 *
 * <p>锁死安全边界（对齐 {@code LoopbackCallbackHandler} / CC auth.ts）：
 * 未知 state → 400（CSRF）；state 一次性；过期 → 400；provider 带 error → 200 错误页 + 结果带错误；
 * 合法 code → 200 + future 拿到 code。
 */
class ConnectorCallbackServerTest {

    private ConnectorCallbackServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() throws Exception {
        server = new ConnectorCallbackServer(0);
        server.ensureStarted();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> call(String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/callback" + query))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<ConnectorCallbackServer.CallbackResult> register(String state, long ttlMs) {
        ConnectorCallbackServer.PendingFlow flow = new ConnectorCallbackServer.PendingFlow(
                "notion", "verifier", "client-id", null,
                "https://as/authorize", "https://as/token", null,
                "http://127.0.0.1:" + server.port() + "/callback", "read", false,
                System.currentTimeMillis() + ttlMs);
        return server.register(state, flow, ttlMs);
    }

    @Test
    @DisplayName("未知 state → 400（CSRF 防护）")
    void unknownStateRejected() throws Exception {
        HttpResponse<String> resp = call("?state=unknown&code=abc");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("Invalid state");
    }

    @Test
    @DisplayName("缺少 state → 400")
    void missingStateRejected() throws Exception {
        HttpResponse<String> resp = call("?code=abc");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("Invalid state");
    }

    @Test
    @DisplayName("合法 state + code → 200 且 future 拿到 code")
    void validStateCompletesWithCode() throws Exception {
        CompletableFuture<ConnectorCallbackServer.CallbackResult> future = register("s-ok", 60_000);
        HttpResponse<String> resp = call("?state=s-ok&code=the-code");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("Authentication Successful");
        ConnectorCallbackServer.CallbackResult result = future.get(3, TimeUnit.SECONDS);
        assertThat(result.hasError()).isFalse();
        assertThat(result.code()).isEqualTo("the-code");
    }

    @Test
    @DisplayName("state 一次性：第二次回调同一 state → 400")
    void stateIsOneShot() throws Exception {
        register("s-once", 60_000);
        assertThat(call("?state=s-once&code=1").statusCode()).isEqualTo(200);
        HttpResponse<String> second = call("?state=s-once&code=2");
        assertThat(second.statusCode()).isEqualTo(400);
        assertThat(second.body()).contains("Invalid state");
    }

    @Test
    @DisplayName("provider 拒绝（error 参数）→ 200 错误页 + future 带错误")
    void providerErrorCompletesWithError() throws Exception {
        CompletableFuture<ConnectorCallbackServer.CallbackResult> future = register("s-err", 60_000);
        HttpResponse<String> resp = call("?state=s-err&error=access_denied&error_description=denied");
        assertThat(resp.statusCode()).isEqualTo(200);
        ConnectorCallbackServer.CallbackResult result = future.get(3, TimeUnit.SECONDS);
        assertThat(result.hasError()).isTrue();
        assertThat(result.error()).contains("access_denied");
    }

    @Test
    @DisplayName("过期 state → 400 且 future 带过期错误")
    void expiredStateRejected() throws Exception {
        CompletableFuture<ConnectorCallbackServer.CallbackResult> future = register("s-expired", -1);
        HttpResponse<String> resp = call("?state=s-expired&code=abc");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("expired");
        ConnectorCallbackServer.CallbackResult result = future.get(3, TimeUnit.SECONDS);
        assertThat(result.hasError()).isTrue();
        assertThat(result.error()).isEqualTo(ConnectorCredentialService.AUTH_EXPIRED_MESSAGE);
    }
}
