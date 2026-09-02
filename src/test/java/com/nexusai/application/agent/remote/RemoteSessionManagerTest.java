package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RemoteSessionManager 协调器定向测试 · 对齐 CC remote/RemoteSessionManager.ts 实际行为。
 *
 * <p><b>WHY（规则九）</b>: RemoteSessionManager 是 WS 接收通道 + HTTP POST 发送通道的汇聚点
 * （TS :95-324）。权限请求（can_use_tool）必须落 pendingPermissionRequests 且能经
 * respondToPermissionRequest 回发 allow/deny（TS :247-282）；control_cancel_request 必须
 * 清理 pending（TS :159-172）；不支持的 subtype 必须回 error 响应防服务端挂起（TS :198-213）。
 * 这些路由错一处即权限流死锁 —— 测试锁死每条分支。
 */
@DisplayName("[W6-B] RemoteSessionManager 协调器（WS 路由 + 权限流 + 消息发送，对齐 CC RemoteSessionManager.ts）")
class RemoteSessionManagerTest {

    /** 记录发送/权限回调的 manager Callbacks。 */
    static class ManagerRecorder implements RemoteSessionManager.Callbacks {
        final List<String> receivedTypes = new ArrayList<>();
        final List<String> permissionRequestIds = new ArrayList<>();
        final List<String> permissionCancelledIds = new ArrayList<>();
        final List<String> cancelledToolUseIds = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int connected;
        int disconnected;

        @Override
        public void onMessage(Map<String, Object> message) {
            receivedTypes.add(String.valueOf(message.get("type")));
        }

        @Override
        public void onPermissionRequest(Map<String, Object> request, String requestId) {
            permissionRequestIds.add(requestId);
        }

        @Override
        public void onPermissionCancelled(String requestId, String toolUseId) {
            permissionCancelledIds.add(requestId);
            cancelledToolUseIds.add(toolUseId);
        }

        @Override
        public void onConnected() {
            connected++;
        }

        @Override
        public void onDisconnected() {
            disconnected++;
        }

        @Override
        public void onError(Exception error) {
            errors.add(error.getMessage());
        }
    }

    /** 记录 sendEventToRemoteSession 调用的 RemoteSessionsApi 桩。 */
    static class StubApi implements RemoteSessionsApi {
        String sentSessionId;
        Object sentContent;
        String updatedSessionId;
        String updatedTitle;
        boolean eventResult = true;
        boolean titleResult = true;

        @Override
        public SessionResource fetchSession(String sessionId) {
            return new SessionResource(sessionId, "running", Map.of());
        }

        @Override
        public PollResult pollEvents(String sessionId, String afterId) {
            return PollResult.eventsOnly(List.of(), null);
        }

        @Override
        public void archiveSession(String sessionId) {
        }

        @Override
        public boolean sendEventToRemoteSession(String sessionId, Object content, String uuid) {
            this.sentSessionId = sessionId;
            this.sentContent = content;
            return eventResult;
        }

        @Override
        public boolean updateSessionTitle(String sessionId, String title) {
            this.updatedSessionId = sessionId;
            this.updatedTitle = title;
            return titleResult;
        }
    }

    private ScheduledExecutorService scheduler;
    private SessionsWebSocketTest.FakeConnector connector;
    private ManagerRecorder rec;
    private StubApi api;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-mgr");
            t.setDaemon(true);
            return t;
        });
        connector = new SessionsWebSocketTest.FakeConnector();
        rec = new ManagerRecorder();
        api = new StubApi();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /** 构造 manager + 真实 SessionsWebSocket（FakeConnector 捕获 listener）→ connect + 驱动 open。 */
    private RemoteSessionManager connectManager(RemoteSessionManager.RemoteSessionConfig config) {
        RemoteSessionManager manager = new RemoteSessionManager(config, rec, api,
            (cfg, wsCallbacks) -> new SessionsWebSocket(cfg.sessionId(), cfg.orgUuid(), cfg.getAccessToken(),
                wsCallbacks, () -> "https://api.anthropic.com", connector, scheduler,
                SessionsWebSocket.RECONNECT_DELAY_MS, SessionsWebSocket.PING_INTERVAL_MS,
                SessionsWebSocket.FORCED_RECONNECT_DELAY_MS));
        manager.connect();
        connector.listener.onOpen(connector.ws);
        return manager;
    }

    private static RemoteSessionManager.RemoteSessionConfig config() {
        return new RemoteSessionManager.RemoteSessionConfig("sess-1", () -> "token", "org-1", false, false);
    }

    /** can_use_tool 权限请求（CC SDKControlPermissionRequest 形状）。 */
    private static SessionsWebSocket.SessionsMessage canUseToolMessage(String requestId) {
        return new SessionsWebSocket.SessionsMessage("control_request", Map.of(
            "request_id", requestId,
            "request", Map.of("subtype", "can_use_tool", "tool_name", "Bash",
                "tool_use_id", "tu-1", "input", Map.of("command", "ls"))));
    }

    // ── 连接 / 消息路由 ──

    @Test
    @DisplayName("connect: 建 WS + onConnected 转发（TS :108-141）")
    void connectWiresWebSocketAndForwardsConnected() {
        RemoteSessionManager manager = connectManager(config());
        assertThat(rec.connected).isEqualTo(1);
        assertThat(manager.isConnected()).isTrue();
        assertThat(manager.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("can_use_tool 权限请求 → onPermissionRequest（TS :189-197）")
    void canUseToolRoutesToPermissionCallback() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}", true);
        assertThat(rec.permissionRequestIds).containsExactly("r1");
    }

    @Test
    @DisplayName("respondToPermissionRequest allow → 发 control_response behavior=allow+updatedInput（TS :247-282）")
    void respondAllowSendsControlResponse() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}", true);
        manager.respondToPermissionRequest("r1", new RemoteSessionManager.Allow(Map.of("command", "ls")));
        assertThat(connector.ws.sentText).hasSize(1);
        String json = connector.ws.sentText.get(0);
        assertThat(json).contains("\"type\":\"control_response\"");
        assertThat(json).contains("\"behavior\":\"allow\"");
        assertThat(json).contains("\"updatedInput\"");
    }

    @Test
    @DisplayName("respondToPermissionRequest deny → 发 control_response behavior=deny+message（TS :270-273）")
    void respondDenySendsControlResponse() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}", true);
        manager.respondToPermissionRequest("r1", new RemoteSessionManager.Deny("denied by user"));
        String json = connector.ws.sentText.get(0);
        assertThat(json).contains("\"behavior\":\"deny\"");
        assertThat(json).contains("\"message\":\"denied by user\"");
    }

    @Test
    @DisplayName("respondToPermissionRequest 无 pending → 仅日志不发送（TS :252-259）")
    void respondWithoutPendingIsNoop() {
        RemoteSessionManager manager = connectManager(config());
        manager.respondToPermissionRequest("no-such", new RemoteSessionManager.Allow(Map.of()));
        assertThat(connector.ws.sentText).isEmpty();
    }

    @Test
    @DisplayName("control_cancel_request → 清理 pending + onPermissionCancelled（TS :159-172）")
    void controlCancelClearsPending() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"tool_use_id\":\"tu-9\"}}", true);
        connector.listener.onText(connector.ws, "{\"type\":\"control_cancel_request\",\"request_id\":\"r1\"}", true);
        assertThat(rec.permissionCancelledIds).containsExactly("r1");
        assertThat(rec.cancelledToolUseIds).containsExactly("tu-9");
        // 取消后再 respond → 无 pending，不发
        manager.respondToPermissionRequest("r1", new RemoteSessionManager.Allow(Map.of()));
        assertThat(connector.ws.sentText).isEmpty();
    }

    @Test
    @DisplayName("control_response → 仅日志不转发（TS :175-178）")
    void controlResponseIsAcknowledged() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_response\",\"response\":{}}", true);
        assertThat(rec.receivedTypes).isEmpty();
    }

    @Test
    @DisplayName("不支持的 control_request subtype → 回 error 响应防服务端挂起（TS :198-213）")
    void unsupportedSubtypeSendsErrorResponse() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r9\",\"request\":{\"subtype\":\"unknown_sub\"}}", true);
        assertThat(connector.ws.sentText).hasSize(1);
        assertThat(connector.ws.sentText.get(0)).contains("\"subtype\":\"error\"");
        assertThat(connector.ws.sentText.get(0)).contains("Unsupported control request subtype: unknown_sub");
    }

    @Test
    @DisplayName("SDKMessage（非 control_*）→ onMessage 转发（TS :180-183）")
    void sdkMessagesForwardedToCallback() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", true);
        assertThat(rec.receivedTypes).containsExactly("assistant");
    }

    // ── 发送 / 中断 / 断开 ──

    @Test
    @DisplayName("sendMessage → HTTP POST sendEventToRemoteSession（TS :219-242）")
    void sendMessagePostsEvent() {
        RemoteSessionManager manager = connectManager(config());
        boolean ok = manager.sendMessage("hello");
        assertThat(ok).isTrue();
        assertThat(api.sentSessionId).isEqualTo("sess-1");
        assertThat(api.sentContent).isEqualTo("hello");
    }

    @Test
    @DisplayName("cancelSession → 发 interrupt control_request（TS :294-297）")
    void cancelSessionSendsInterrupt() {
        RemoteSessionManager manager = connectManager(config());
        manager.cancelSession();
        assertThat(connector.ws.sentText).hasSize(1);
        assertThat(connector.ws.sentText.get(0)).contains("\"type\":\"control_request\"");
        assertThat(connector.ws.sentText.get(0)).contains("\"subtype\":\"interrupt\"");
    }

    @Test
    @DisplayName("disconnect → 关 WS + 清 pending（TS :309-314）")
    void disconnectClosesAndClearsPending() {
        RemoteSessionManager manager = connectManager(config());
        connector.listener.onText(connector.ws, "{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\"}}", true);
        manager.disconnect();
        assertThat(manager.isConnected()).isFalse();
        assertThat(connector.ws.closeSent.get()).isEqualTo(1);
        // pending 已清 → 取消后无响应发送
        manager.respondToPermissionRequest("r1", new RemoteSessionManager.Allow(Map.of()));
        assertThat(connector.ws.sentText).isEmpty();
    }

    @Test
    @DisplayName("isSDKMessage type guard：非 control_* 为 SDK 消息（TS :22-34）")
    void isSdkMessageTypeGuard() {
        assertThat(RemoteSessionManager.isSDKMessage(new SessionsWebSocket.SessionsMessage("assistant", Map.of()))).isTrue();
        assertThat(RemoteSessionManager.isSDKMessage(new SessionsWebSocket.SessionsMessage("control_request", Map.of()))).isFalse();
        assertThat(RemoteSessionManager.isSDKMessage(new SessionsWebSocket.SessionsMessage("control_response", Map.of()))).isFalse();
        assertThat(RemoteSessionManager.isSDKMessage(new SessionsWebSocket.SessionsMessage("control_cancel_request", Map.of()))).isFalse();
    }
}
