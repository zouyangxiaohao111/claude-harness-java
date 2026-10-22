package com.nexusai.application.agent.permission;

import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [2026-10-22 会话状态同步] {@link WebSocketPermissionPrompter#listPendingForSession} 快照语义。
 *
 * <p>WHY（规则九 · 测试验证意图）：权限请求只走一次性 STOMP 推送；若请求到达时该会话不在前端
 * 订阅集合（非当前会话且无活跃流），事件被静默丢弃 —— 用户看不到黄点、切回也不弹。
 * 修法 = 服务端保留「仍在等待用户」的请求快照，切会话/重连时经
 * {@code GET /sessions/{id}/state} 重放（对齐 dsh 的 stable-rpcId replay）。
 *
 * <p>验收条件：快照只包含**仍有 pending future** 的请求（已响应/已中止的必须剔除）；
 * 无会话 / 无快照 → 空列表，绝不返回已终结的请求（否则前端会渲染一个点不动的幽灵弹窗）。
 */
@ExtendWith(MockitoExtension.class)
class WebSocketPermissionPrompterPendingSnapshotTest {

    @Mock private SimpMessagingTemplate ws;

    private WebSocketPermissionPrompter prompter;

    @BeforeEach
    void setUp() throws Exception {
        prompter = new WebSocketPermissionPrompter(ws, 30_000);
    }

    @SuppressWarnings("unchecked")
    private void putSnapshot(String sessionId, String requestId) throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pendingBySession");
        f.setAccessible(true);
        Map<String, Map<String, MessagePermissionRequestEvent>> bySession =
            (Map<String, Map<String, MessagePermissionRequestEvent>>) f.get(prompter);
        bySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(requestId, mock(MessagePermissionRequestEvent.class));

        Field g = WebSocketPermissionPrompter.class.getDeclaredField("pendingSessionOf");
        g.setAccessible(true);
        ((Map<String, String>) g.get(prompter)).put(requestId, sessionId);
    }

    @SuppressWarnings("unchecked")
    private void putPendingFuture(String requestId) throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        ((Map<String, CompletableFuture<PermissionResult>>) f.get(prompter))
            .put(requestId, new CompletableFuture<>());
    }

    @Test
    @DisplayName("会话无快照 → 空列表")
    void noSnapshot_returnsEmpty() {
        assertThat(prompter.listPendingForSession("sess-none")).isEmpty();
    }

    @Test
    @DisplayName("仍有 pending future → 返回该请求（切会话可重放）")
    void liveRequest_isReturned() throws Exception {
        putSnapshot("sess-1", "r-live");
        putPendingFuture("r-live");

        assertThat(prompter.listPendingForSession("sess-1"))
            .as("仍在等待用户的请求必须出现在会话状态里")
            .hasSize(1);
    }

    @Test
    @DisplayName("快照残留但 future 已终结 → 剔除（不返回幽灵请求）")
    void resolvedRequest_isPruned() throws Exception {
        putSnapshot("sess-1", "r-dead");
        // 故意不建 pending future = 已响应/中止后的残留

        assertThat(prompter.listPendingForSession("sess-1")).isEmpty();
    }

    @Test
    @DisplayName("null / 空白会话 → 空列表（不 NPE）")
    void blankSession_isSafe() {
        assertThat(prompter.listPendingForSession(null)).isEmpty();
        assertThat(prompter.listPendingForSession("  ")).isEmpty();
    }
}
