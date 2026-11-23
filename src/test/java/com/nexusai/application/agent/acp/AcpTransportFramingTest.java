package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 帧编解码 · 粘帧 / 坏帧 / 未知 id / 通知分发 / server→client 请求往返 / EOF。
 *
 * <p>这些用例锁的是「真机最会出问题的地方」：外部 CLI 把日志混进 stdout、一次 write
 * 带多帧、Windows CRLF、对端退出后请求必须失败而不是永久挂住。
 */
@DisplayName("[acp] AcpTransport 帧编解码")
class AcpTransportFramingTest {

    @Test
    @DisplayName("正常请求拿到 result")
    void requestCompletes() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("ping", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("ok", true))));
            AcpTransport transport = server.clientTransport().start();

            JsonNode result = transport.request("ping", Map.of(), 3_000);

            assertThat(result.path("ok").asBoolean()).isTrue();
            transport.close();
        }
    }

    @Test
    @DisplayName("粘帧 + CRLF + 坏帧 + 未知 id 都不打断连接")
    void stickyGarbageAndUnknownId() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("cmd", frame -> {
                // 一次写出：坏帧、两帧粘在一起（含 CRLF）、以及一个未知 id 的响应
                server.writeRaw("this-is-not-json");
                server.writeRaw("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":1}}\r\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"stray\":true}}");
                return List.of();
            });
            AcpTransport transport = server.clientTransport().start();

            JsonNode result = transport.request("cmd", Map.of(), 3_000);

            assertThat(result.path("ok").asInt()).isEqualTo(1);
            transport.close();
        }
    }

    @Test
    @DisplayName("server→client 通知按 method 分发")
    void notificationDispatched() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            AcpTransport transport = server.clientTransport().start();
            CountDownLatch latch = new CountDownLatch(1);
            String[] seen = new String[1];
            transport.setNotificationHandler((method, params) -> {
                seen[0] = method + ":" + params.path("value").asText();
                latch.countDown();
            });

            server.notifyClient("session/update", Map.of("value", "hello"));

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(seen[0]).isEqualTo("session/update:hello");
            transport.close();
        }
    }

    @Test
    @DisplayName("server→client 请求的响应能回写（id 与 result 对齐）")
    void serverRequestRoundTrip() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            AcpTransport transport = server.clientTransport().start();
            transport.setRequestHandler((method, params) ->
                    "session/request_permission".equals(method) ? AcpClient.cancelledOutcome() : null);

            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            server.requestClient(900, "session/request_permission", Map.of("options", List.of()),
                    response::complete);

            JsonNode result = response.get(3, TimeUnit.SECONDS);
            assertThat(result.path("id").asLong()).isEqualTo(900);
            assertThat(result.path("result").path("outcome").path("outcome").asText())
                    .isEqualTo("cancelled");
            transport.close();
        }
    }

    @Test
    @DisplayName("对端 EOF ⇒ 在飞请求失败，不永久挂住")
    void eofFailsPending() throws Exception {
        FakeAcpServer server = new FakeAcpServer().start();
        server.on("never", frame -> List.of());
        AcpTransport transport = server.clientTransport().start();

        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            server.close();
        }, "close-server").start();

        assertThatThrownBy(() -> transport.request("never", Map.of(), 5_000))
                .isInstanceOf(AcpException.class);
        transport.close();
    }
}
