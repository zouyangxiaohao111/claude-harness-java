package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ACP 客户端主链 · initialize 版本协商 / session/new / session/prompt / session/cancel /
 * session/update 分发 / 权限请求往返。
 */
@DisplayName("[acp] AcpClient 协议主链")
class AcpClientProtocolTest {

    private static Map<String, Object> initOk(JsonNode frame) {
        return Map.of("protocolVersion", 1, "agentCapabilities", Map.of());
    }

    @Test
    @DisplayName("initialize 版本协商：对端返回 1 ⇒ 通过")
    void initializeNegotiates() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), initOk(frame))));
            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);

            JsonNode result = client.initialize(3_000);

            assertThat(result.path("protocolVersion").asInt()).isEqualTo(1);
            // 客户端必须显式带 protocolVersion 与空的 clientCapabilities
            JsonNode sent = received(server, "initialize").get(0);
            assertThat(sent.path("params").path("protocolVersion").asInt()).isEqualTo(1);
            assertThat(sent.path("params").path("clientCapabilities").isObject()).isTrue();
            transport.close();
        }
    }

    @Test
    @DisplayName("对端版本高于客户端 ⇒ 拒绝连接")
    void rejectsHigherProtocolVersion() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("protocolVersion", 99))));
            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);

            assertThatThrownBy(() -> client.initialize(3_000))
                    .isInstanceOf(AcpException.class)
                    .hasMessageContaining("高于");
            transport.close();
        }
    }

    @Test
    @DisplayName("initialize 响应缺 protocolVersion ⇒ 拒绝")
    void rejectsMissingProtocolVersion() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of())));
            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);

            assertThatThrownBy(() -> client.initialize(3_000))
                    .isInstanceOf(AcpException.class)
                    .hasMessageContaining("protocolVersion");
            transport.close();
        }
    }

    @Test
    @DisplayName("session/new + prompt：参数形状正确，update 按序分发")
    void newSessionAndPrompt() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("protocolVersion", 1))));
            server.on("session/new", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("sessionId", "s-1"))));
            server.on("session/prompt", frame -> {
                server.notifyClient("session/update", Map.of(
                        "sessionId", "s-1",
                        "update", Map.of("sessionUpdate", "agent_message_chunk",
                                "content", Map.of("type", "text", "text", "hello"))));
                return List.of(FakeAcpServer.response(frame.get("id").asLong(), Map.of("stopReason", "end_turn")));
            });
            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);
            List<String> updates = new CopyOnWriteArrayList<>();
            client.setUpdateListener(update -> updates.add(update.path("sessionUpdate").asText()));

            client.initialize(3_000);
            String sessionId = client.newSession(Path.of("."), 3_000);
            JsonNode promptResult = client.prompt("do it", 3_000);

            assertThat(sessionId).isEqualTo("s-1");
            // prompt 的 result 到达 = 本轮已结算；但 session/update 走独立通知线程，
            // 可能晚于 result 被处理 ⇒ 先有界等待再断言（否则偶发「列表还是空」）。
            boolean seen = awaitUpdate(updates, "agent_message_chunk", 2_000);
            assertThat(promptResult.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(seen)
                    .as("等待 2000ms 仍未收到 agent_message_chunk；实际 updates=%s", updates)
                    .isTrue();
            assertThat(updates).containsExactly("agent_message_chunk");

            JsonNode newSession = received(server, "session/new").get(0);
            assertThat(newSession.path("params").path("cwd").asText()).isNotBlank();
            assertThat(newSession.path("params").path("mcpServers").isArray()).isTrue();

            JsonNode prompt = received(server, "session/prompt").get(0);
            assertThat(prompt.path("params").path("sessionId").asText()).isEqualTo("s-1");
            assertThat(prompt.path("params").path("prompt").get(0).path("type").asText()).isEqualTo("text");
            assertThat(prompt.path("params").path("prompt").get(0).path("text").asText()).isEqualTo("do it");
            transport.close();
        }
    }

    @Test
    @DisplayName("cancel 发的是通知（无 id）")
    void cancelIsNotification() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("protocolVersion", 1))));
            server.on("session/new", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("sessionId", "s-1"))));
            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);
            client.initialize(3_000);
            client.newSession(Path.of("."), 3_000);

            client.cancel();

            JsonNode cancel = receiveEventually(server, "session/cancel");
            assertThat(cancel.has("id")).isFalse();
            assertThat(cancel.path("params").path("sessionId").asText()).isEqualTo("s-1");
            transport.close();
        }
    }

    @Test
    @DisplayName("权限请求往返：allow 策略回第一个 allow 选项的 optionId")
    void permissionRequestRoundTrip() throws Exception {
        try (FakeAcpServer server = new FakeAcpServer().start()) {
            server.on("initialize", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("protocolVersion", 1))));
            server.on("session/new", frame -> List.of(
                    FakeAcpServer.response(frame.get("id").asLong(), Map.of("sessionId", "s-1"))));

            CompletableFuture<JsonNode> permissionResponse = new CompletableFuture<>();
            server.on("session/prompt", frame -> {
                long promptId = frame.get("id").asLong();
                Map<String, Object> permissionParams = Map.of(
                        "sessionId", "s-1",
                        "toolCall", Map.of("toolCallId", "t-1", "title", "Run tests", "kind", "execute"),
                        "options", List.of(
                                Map.of("optionId", "opt-allow", "name", "Allow", "kind", "allow_once"),
                                Map.of("optionId", "opt-reject", "name", "Reject", "kind", "reject_once")));
                server.requestClient(700, "session/request_permission", permissionParams, response -> {
                    permissionResponse.complete(response);
                    server.respond(promptId, Map.of("stopReason", "end_turn"));
                });
                return List.of();
            });

            AcpTransport transport = server.clientTransport().start();
            AcpClient client = new AcpClient(transport);
            client.setPermissionBridge(new AcpPermissionBridge(null, null, Path.of("."),
                    AcpPermissionBridge.POLICY_ALLOW));
            client.initialize(3_000);
            client.newSession(Path.of("."), 3_000);
            JsonNode promptResult = client.prompt("run", 5_000);

            assertThat(promptResult.path("stopReason").asText()).isEqualTo("end_turn");
            JsonNode response = permissionResponse.get(3, TimeUnit.SECONDS);
            assertThat(response.path("result").path("outcome").path("outcome").asText())
                    .isEqualTo("selected");
            assertThat(response.path("result").path("outcome").path("optionId").asText())
                    .isEqualTo("opt-allow");
            transport.close();
        }
    }

    private static List<JsonNode> received(FakeAcpServer server, String method) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode frame : server.received()) {
            if (method.equals(frame.path("method").asText(""))) {
                out.add(frame);
            }
        }
        return out;
    }

    /**
     * 有界等待某条 update 进入列表（最长 {@code timeoutMs}，10ms 间隔）。
     *
     * <p>WHY 不能直接断言：{@code session/update} 在客户端独立通知线程上处理，
     * 而 {@code session/prompt} 的 result 在读线程上完成 future ⇒ 两者无先后保证。
     * 超时返回 false，由调用方断言并打印实际列表（失败信息可读）。
     */
    private static boolean awaitUpdate(List<String> updates, String expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (updates.contains(expected)) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return updates.contains(expected);
            }
        }
        return updates.contains(expected);
    }

    private static JsonNode receiveEventually(FakeAcpServer server, String method) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> frames = received(server, method);
            if (!frames.isEmpty()) {
                return frames.get(frames.size() - 1);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("未收到帧: " + method);
    }
}
