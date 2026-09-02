package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Bridge 双工协议 sendResponse 测试 · 对齐 CC bridgePermissionCallbacks.ts:20。
 *
 * <p>验证意图（规则九）：RV-07 补齐双工协议后，本地 racer 胜出必须能通过
 * {@link BridgePermissionCallbacks#sendResponse} 出站回传 dismiss（对齐 CC interactiveHandler.ts:140-192
 * onAbort/onAllow/onReject 的 sendResponse + cancelRequest）。若接口缺 sendResponse（v4 缺口），
 * 本地胜出无法通知远程表面 dismiss，本测试「sendResponse 出站推送 dismiss topic」断言必红。
 */
@DisplayName("Bridge 双工协议 sendResponse（对齐 CC bridgePermissionCallbacks.ts:20）")
class BridgeSendResponseDuplexTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("sendResponse 出站推送 dismiss topic（本地 racer 胜出 → 远程表面 dismiss）")
    void sendResponse_pushesDismissTopic() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks bridge = new StompBridgePermissionCallbacks(ws);

        // sendRequest 先注册 requestId → sessionId 路由（对齐 CC sendRequest）
        ObjectNode input = JSON.createObjectNode().put("command", "ls");
        bridge.sendRequest("sess-1", "req-1", "Bash", input, "tooluse-1",
            "run ls", List.of(), null);

        bridge.sendResponse("req-1",
            new BridgePermissionCallbacks.BridgeResponse("allow", null, null, null));

        verify(ws).convertAndSend(
            eq(StompBridgePermissionCallbacks.dismissTopicFor("sess-1")), anyMap());
    }

    @Test
    @DisplayName("cancelRequest 移除 pending resolver + 出站 dismiss（对齐 CC cancelRequest）")
    void cancelRequest_removesPendingAndDismisses() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks bridge = new StompBridgePermissionCallbacks(ws);
        bridge.sendRequest("sess-1", "req-1", "Bash", JSON.createObjectNode(), "tooluse-1",
            "run", List.of(), null);

        AtomicInteger invoked = new AtomicInteger(0);
        bridge.onResponse("req-1", r -> invoked.incrementAndGet());
        bridge.cancelRequest("req-1");

        // pending resolver 已移除 → 远程 resolve 不再命中
        assertThat(bridge.resolve("req-1", new BridgePermissionCallbacks.BridgeResponse("allow", null, null)))
            .isFalse();
        assertThat(invoked.get()).isZero();
        verify(ws).convertAndSend(
            eq(StompBridgePermissionCallbacks.dismissTopicFor("sess-1")), anyMap());
    }

    @Test
    @DisplayName("sendResponse 移除 pending resolver（本地胜出后远程 resolve 不再命中）")
    void sendResponse_removesPendingResolver() {
        StompBridgePermissionCallbacks bridge =
            new StompBridgePermissionCallbacks(mock(SimpMessagingTemplate.class));
        bridge.sendRequest("sess-1", "req-1", "Bash", JSON.createObjectNode(), "tooluse-1",
            "run", List.of(), null);

        AtomicInteger invoked = new AtomicInteger(0);
        bridge.onResponse("req-1", r -> invoked.incrementAndGet());
        bridge.sendResponse("req-1", new BridgePermissionCallbacks.BridgeResponse("allow", null, null));

        assertThat(bridge.resolve("req-1", new BridgePermissionCallbacks.BridgeResponse("deny", null, null)))
            .isFalse();
        assertThat(invoked.get()).isZero();
    }
}
