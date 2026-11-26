package com.nexusai.application.agent.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cloud Code project 解析的 wire 测试：本地 HTTP 桩 + 可注入 Clock/Sleeper。
 * ⛔ 不访问 cloudcode-pa.googleapis.com，不发任何真实认证请求。
 */
class GoogleCodeAssistClientTest {

    @Test
    @DisplayName("loadCodeAssist 有 currentTier → 直接取 cloudaicompanionProject.id，请求体只含 metadata")
    void loadCodeAssist_currentTier() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"currentTier\":{\"id\":\"standard\"},\"cloudaicompanionProject\":{\"id\":\"proj-a\"}}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());

            String project = client.resolveProject("tok", Duration.ofSeconds(5));

            assertThat(project).isEqualTo("proj-a");
            Recorded rec = stub.requests.get(0);
            assertThat(rec.path).isEqualTo("/v1internal:loadCodeAssist");
            assertThat(rec.authorization).isEqualTo("Bearer tok");
            assertThat(rec.body).contains("\"metadata\"").contains("IDE_UNSPECIFIED")
                .contains("GEMINI");
        }
    }

    @Test
    @DisplayName("无 currentTier → onboardUser(FREE 不带 project) + LRO 轮询 GET /v1internal/{name}（不重复版本段）")
    void onboard_lroPolling() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"allowedTiers\":[{\"id\":\"FREE\",\"isDefault\":true}]}");
            stub.reply("POST /v1internal:onboardUser", 200,
                "{\"name\":\"operations/op-1\",\"done\":false}");
            stub.reply("GET /v1internal/operations/op-1", 200,
                "{\"name\":\"operations/op-1\",\"done\":true,"
                    + "\"response\":{\"cloudaicompanionProject\":{\"id\":\"proj-b\"}}}");
            AtomicLong slept = new AtomicLong();
            GoogleCodeAssistClient client = client(stub, slept);

            String project = client.resolveProject("tok", Duration.ofSeconds(60));

            assertThat(project).isEqualTo("proj-b");
            assertThat(slept.get()).isEqualTo(GoogleCodeAssistClient.POLL_INTERVAL_MILLIS);
            Recorded onboard = stub.requests.stream()
                .filter(r -> r.path.equals("/v1internal:onboardUser")).findFirst().orElseThrow();
            assertThat(onboard.body).contains("\"tierId\":\"FREE\"")
                .as("FREE tier 不能带 cloudaicompanionProject（官方 setup.ts:257-274）")
                .doesNotContain("cloudaicompanionProject");
            assertThat(stub.requests).extracting(r -> r.path)
                .contains("/v1internal:operations/op-1".substring(0, 0) + "/v1internal/operations/op-1");
        }
    }

    @Test
    @DisplayName("ineligibleTiers：异常带 reasonMessage，code=INELIGIBLE")
    void ineligible_reasonSurfaced() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"ineligibleTiers\":[{\"tierName\":\"standard\",\"reasonMessage\":\"not available in your country\"}]}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class, e -> {
                        assertThat(e.code())
                            .isEqualTo(GoogleCodeAssistClient.GoogleCodeAssistException.Code.INELIGIBLE);
                        assertThat(e.getMessage()).contains("not available in your country");
                    });
        }
    }

    @Test
    @DisplayName("数字型 cloudaicompanionProject 不接受 → PROJECT_REQUIRED（不把数字当 project id）")
    void numericProject_rejected() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"currentTier\":{\"id\":\"standard\"},\"cloudaicompanionProject\":12345}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class,
                    e -> assertThat(e.code()).isEqualTo(
                        GoogleCodeAssistClient.GoogleCodeAssistException.Code.PROJECT_REQUIRED));
        }
    }

    @Test
    @DisplayName("403 + VALIDATION_REQUIRED：可读原因 + 仅 https 验证链接；非 https 链接丢弃")
    void forbidden_validationUrl() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 403,
                "{\"error\":{\"message\":\"<b>Terms not accepted</b>\",\"details\":[{\"metadata\":"
                    + "{\"validationUrl\":\"https://codeassist.google.com/verify?x=1\"}}]}}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class, e -> {
                        assertThat(e.code()).isEqualTo(
                            GoogleCodeAssistClient.GoogleCodeAssistException.Code.INELIGIBLE);
                        assertThat(e.getMessage()).contains("Terms not accepted")
                            .doesNotContain("<b>")
                            .contains("https://codeassist.google.com/verify?x=1");
                    });
        }

        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 403,
                "{\"error\":{\"message\":\"denied\",\"details\":[{\"metadata\":"
                    + "{\"validationUrl\":\"javascript:alert(1)\"}}]}}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class,
                    e -> assertThat(e.getMessage()).doesNotContain("javascript:"));
        }
    }

    @Test
    @DisplayName("401 → NOT_AUTHENTICATED；非 2xx 其它 → HTTP_ERROR 带脱敏服务端 message")
    void httpErrors() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 401, "{}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());
            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class,
                    e -> assertThat(e.code()).isEqualTo(
                        GoogleCodeAssistClient.GoogleCodeAssistException.Code.NOT_AUTHENTICATED));
        }
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 500,
                "{\"error\":{\"message\":\"internal <x>\"}}");
            GoogleCodeAssistClient client = client(stub, new AtomicLong());
            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class, e -> {
                        assertThat(e.code()).isEqualTo(
                            GoogleCodeAssistClient.GoogleCodeAssistException.Code.HTTP_ERROR);
                        assertThat(e.getMessage()).contains("HTTP 500").contains("internal");
                    });
        }
    }

    @Test
    @DisplayName("LRO 总 deadline：轮询把时钟推过 deadline → TIMEOUT（不无限等）")
    void lro_timeout() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"allowedTiers\":[{\"id\":\"FREE\",\"isDefault\":true}]}");
            stub.reply("POST /v1internal:onboardUser", 200,
                "{\"name\":\"operations/op-1\",\"done\":false}");
            AtomicLong offset = new AtomicLong();
            GoogleCodeAssistClient client = new GoogleCodeAssistClient(
                stub.httpClient(), clock(offset), millis -> offset.addAndGet(60_000), stub.base());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class,
                    e -> assertThat(e.code())
                        .isEqualTo(GoogleCodeAssistClient.GoogleCodeAssistException.Code.TIMEOUT));
        }
    }

    @Test
    @DisplayName("取消：sleep 被 interrupt → ABORTED（可终止）")
    void lro_cancelled() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.reply("POST /v1internal:loadCodeAssist", 200,
                "{\"allowedTiers\":[{\"id\":\"FREE\",\"isDefault\":true}]}");
            stub.reply("POST /v1internal:onboardUser", 200,
                "{\"name\":\"operations/op-1\",\"done\":false}");
            GoogleCodeAssistClient client = new GoogleCodeAssistClient(stub.httpClient(),
                Clock.systemUTC(), millis -> {
                    throw new InterruptedException("cancelled");
                }, stub.base());

            assertThatThrownBy(() -> client.resolveProject("tok", Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(
                    GoogleCodeAssistClient.GoogleCodeAssistException.class,
                    e -> assertThat(e.code())
                        .isEqualTo(GoogleCodeAssistClient.GoogleCodeAssistException.Code.ABORTED));
        }
    }

    // ───────────────────────────── 辅助 ─────────────────────────────

    private static GoogleCodeAssistClient client(Stub stub, AtomicLong slept) {
        return new GoogleCodeAssistClient(stub.httpClient(), Clock.systemUTC(),
            millis -> slept.addAndGet(millis), stub.base());
    }

    private static Clock clock(AtomicLong offset) {
        return new Clock() {
            @Override public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override public Instant instant() {
                return Instant.ofEpochMilli(System.currentTimeMillis() + offset.get());
            }
        };
    }

    private record Recorded(String method, String path, String authorization, String body) {}

    /** 本地 Cloud Code 桩：按 "METHOD path" 返回预置响应。 */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final List<Reply> replies = new ArrayList<>();
        private final List<Recorded> requests = new ArrayList<>();

        private Stub(HttpServer server) {
            this.server = server;
        }

        static Stub start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Stub stub = new Stub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        }

        void reply(String key, int status, String body) {
            replies.add(new Reply(key, status, body));
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1internal";
        }

        java.net.http.HttpClient httpClient() {
            return java.net.http.HttpClient.newHttpClient();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            }
            Reply reply = replies.stream().filter(r -> r.key.equals(key)).findFirst()
                .orElse(new Reply(key, 404, "{}"));
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override public void close() {
            server.stop(0);
        }

        private record Reply(String key, int status, String body) {}
    }
}
