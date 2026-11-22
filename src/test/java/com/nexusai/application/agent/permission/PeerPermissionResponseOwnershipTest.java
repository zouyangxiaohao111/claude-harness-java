package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.apis.permission.PermissionController;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.MessagePermissionResponseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 批 · 审批响应归属 + 默认本地用户准入（非 Spring）· 2026-11-19 语义重排后。
 *
 * <p>验证意图：
 * <ul>
 *   <li>默认 local-user 验证器：正常用户从 UI 的响应可信（userId 缺省 "default"）；</li>
 *   <li>错归属不消耗 pending（正确会话仍能看到该请求）；</li>
 *   <li>可归因为 peer 的响应一律丢弃且<b>不消耗、不取消</b> pending ——
 *       peer 消息不能充当用户批准；合法终态仍由真实 UI 响应或 abort 给出；</li>
 *   <li>leader inbox 响应在 remove 之前比对发起会话，错会话拒绝且 entry 保留。</li>
 * </ul>
 */
class PeerPermissionResponseOwnershipTest {

    private static final String OWNER_SESSION = "sess-dddddddd";
    private static final String OTHER_SESSION = "sess-eeeeeeee";
    private static final String LEAD_SESSION = "11111111-1111-1111-1111-111111111111";

    private final PeerExecutionOrigin peerOrigin = new PeerExecutionOrigin(
        "sess-ffffffff", "Peer F", PeerExecutionOrigin.SOURCE_KIND_PEER,
        "msg-0004", 13L, "default", "peer-f [abcdef]", List.of());

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
    }

    @Test
    void wrongSessionDoesNotConsumePending() throws Exception {
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class));
        CompletableFuture<PermissionResult> future = startPrompt(prompter, peerContext(), "req-wrong");
        waitUntilRegistered(prompter, "req-wrong");

        assertEquals(WebSocketPermissionPrompter.ResponseAdmission.WRONG_SESSION,
            prompter.admitMessageResponse(OTHER_SESSION, "req-wrong"));
        // pending 仍存在（错归属不 remove）：正确会话仍能看到该请求
        assertEquals(WebSocketPermissionPrompter.ResponseAdmission.ACCEPT,
            prompter.admitMessageResponse(OWNER_SESSION, "req-wrong"));

        // 清理：显式 deny 结束（避免测试线程悬挂）
        prompter.onResponse("req-wrong", "deny");
        assertInstanceOf(PermissionResult.Deny.class, future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void peerAttributedResponsesAreDroppedWithoutConsumingPending() throws Exception {
        // 服务端归因：这些响应可归因为 peer 回合（不是真实用户）→ 绝不能解 pending。
        PeerUiApprovalAuthority.installAttributor(claim -> true);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class));
        PermissionController controller = new PermissionController();
        inject(controller, "prompter", prompter);

        CompletableFuture<PermissionResult> future = startPrompt(prompter, peerContext(), "req-attack");
        waitUntilRegistered(prompter, "req-attack");

        for (int i = 0; i < 5; i++) {
            controller.handlePermissionResponse(OWNER_SESSION,
                new MessagePermissionResponseEvent("req-attack", "allow"));
            assertFalse(future.isDone(), "第 " + i + " 次 peer 归因响应不得终结合法待审批项");
        }
        // pending 仍在（不是 UNKNOWN = 没有被 remove），对真实用户依旧可准入。
        assertEquals(WebSocketPermissionPrompter.ResponseAdmission.ACCEPT,
            prompter.admitMessageResponse(OWNER_SESSION, "req-attack"));

        // 归因清除后，真实用户批准生效。
        PeerUiApprovalAuthority.installAttributor(null);
        controller.handlePermissionResponse(OWNER_SESSION,
            new MessagePermissionResponseEvent("req-attack", "allow"));
        assertInstanceOf(PermissionResult.Allow.class, future.get(2, TimeUnit.SECONDS),
            "peer 归因响应被丢弃后，真实用户的批准仍应生效");
    }

    @Test
    void controllerDropsWrongSessionAndPeerAttributedWithoutConsumingPending() throws Exception {
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class));
        PermissionController controller = new PermissionController();
        inject(controller, "prompter", prompter);

        CompletableFuture<PermissionResult> future = startPrompt(prompter, peerContext(), "req-controller");
        waitUntilRegistered(prompter, "req-controller");

        // 错会话：必须丢弃。
        controller.handlePermissionResponse(OTHER_SESSION,
            new MessagePermissionResponseEvent("req-controller", "allow"));
        // 可归因为 peer 的正确会话：同样丢弃。
        PeerUiApprovalAuthority.installAttributor(claim -> true);
        controller.handlePermissionResponse(OWNER_SESSION,
            new MessagePermissionResponseEvent("req-controller", "allow"));
        PeerUiApprovalAuthority.installAttributor(null);

        assertFalse(future.isDone(), "假批准不得终结合法待审批项");
        assertEquals(WebSocketPermissionPrompter.ResponseAdmission.ACCEPT,
            prompter.admitMessageResponse(OWNER_SESSION, "req-controller"),
            "pending 必须仍在（ACCEPT 不是 UNKNOWN）");

        // 清理：既有终态（abort/显式 deny）可结束该请求。
        prompter.onResponse("req-controller", "deny");
        assertInstanceOf(PermissionResult.Deny.class, future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void defaultLocalUserCanApprovePendingFromPeerTurn() throws Exception {
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class));
        PermissionController controller = new PermissionController();
        inject(controller, "prompter", prompter);

        CompletableFuture<PermissionResult> future = startPrompt(prompter, peerContext(), "req-trusted");
        waitUntilRegistered(prompter, "req-trusted");

        assertEquals("default", PeerUiApprovalAuthority.getUserId());
        assertEquals(WebSocketPermissionPrompter.ResponseAdmission.ACCEPT,
            prompter.admitMessageResponse(OWNER_SESSION, "req-trusted"));
        controller.handlePermissionResponse(OWNER_SESSION,
            new MessagePermissionResponseEvent("req-trusted", "deny"));
        assertInstanceOf(PermissionResult.Deny.class, future.get(2, TimeUnit.SECONDS),
            "默认本地用户应当能对 peer 回合产生的待审批项作出响应");
    }

    @Test
    void leaderResponseChecksSessionOwnershipBeforeRemove() {
        LeaderPermissionConfirmBridge bridge = new LeaderPermissionConfirmBridge();
        bridge.setWs(mock(SimpMessagingTemplate.class));
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        LeaderPermissionBridge.ToolUseConfirmEntry entry = new LeaderPermissionBridge.ToolUseConfirmEntry(
            "Bash", "tool-use-leader", "desc", Map.of("command", "ls"),
            "worker1", "cyan", System.currentTimeMillis(),
            (updatedInput, updates) -> allow.set(updatedInput),
            reject::set,
            () -> { });
        bridge.onConfirmQueueUpdate(LEAD_SESSION, queue -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });
        assertEquals(1, bridge.pendingCount(), "发起后 entry 在 pending 中");

        // 错会话的批准：拒绝且不 remove、不触发回调。
        assertEquals(LeaderPermissionConfirmBridge.ResponseResult.REJECTED_WRONG_SESSION,
            bridge.onResponse(OTHER_SESSION, "tool-use-leader", "allow",
                List.of(), null, List.of(), null, null));
        assertEquals(1, bridge.pendingCount(), "错归属不得消耗 entry");
        assertNull(allow.get(), "错归属不得触发 onAllow");

        // 正确会话仍可消费。
        assertEquals(LeaderPermissionConfirmBridge.ResponseResult.CONSUMED,
            bridge.onResponse(LEAD_SESSION, "tool-use-leader", "allow",
                List.of(), null, List.of(), null, null));
        assertEquals(0, bridge.pendingCount());
        assertEquals(Map.of("command", "ls"), allow.get());
        assertNull(reject.get());
    }

    // ── fixtures ──

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private CompletableFuture<PermissionResult> startPrompt(
            WebSocketPermissionPrompter prompter, ToolUseContext ctx, String requestId) {
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        Thread t = new Thread(() -> future.complete(
            prompter.prompt(tool(), JsonNodeFactory.instance.objectNode().put("command", "echo hi"),
                null, ctx, requestId)), "peer-perm-test");
        t.setDaemon(true);
        t.start();
        return future;
    }

    private static void waitUntilRegistered(WebSocketPermissionPrompter prompter, String requestId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            WebSocketPermissionPrompter.ResponseAdmission a =
                prompter.admitMessageResponse(OWNER_SESSION, requestId);
            if (a == WebSocketPermissionPrompter.ResponseAdmission.ACCEPT) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("permission prompt was not registered in time: " + requestId);
    }

    private ToolUseContext peerContext() {
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn(OWNER_SESSION);
        when(ctx.peerExecutionOrigin()).thenReturn(peerOrigin);
        when(ctx.isNonInteractiveSession()).thenReturn(false);
        when(ctx.abortController()).thenReturn(null);
        when(ctx.teammateIdentity()).thenReturn(null);
        return ctx;
    }

    private static Tool tool() {
        return new Tool() {
            @Override
            public String name() {
                return "Bash";
            }

            @Override
            public String description() {
                return "bash";
            }

            @Override
            public JsonNode inputSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }
}
