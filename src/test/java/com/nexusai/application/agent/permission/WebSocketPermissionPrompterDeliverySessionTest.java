package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [team-hang] 权限弹窗的**投递会话**：teammate 的请求必须送到 Leader 会话（有 UI 的那个），
 * ⛔ 不能送到 teammate 自己的 {@code no-session} 哨兵 —— 那是**无人订阅**的 topic，会导致
 * 工具线程永久阻塞。
 *
 * <p><b>实机证据（2026-09-20 e2e）</b>：
 * <pre>
 * 15:45:09.102 WebSocketPermissionPrompter - PERMISSION prompt: tool=Bash requestId=call_01_ET_AeIKEw2XhxgDmBbTMYfz0823
 * 15:45:09.102 WebSocketPermissionPrompter - PERMISSION STOMP → topic=/topic/sessions/no-session/permission-requests ...
 * 15:45:09.115 StompBridgePermissionCallbacks - BRIDGE sendRequest → topic=/topic/sessions/no-session/permission-bridge-requests ... sessionId=no-session
 * </pre>
 * 前端只订阅「当前活跃会话」的 permission topic（front/src/hooks/useChatSocket.ts
 * {@code subscribePermTopics(client, sid)}，sid = 活跃会话）⇒ 该请求无人接收 ⇒
 * {@code prompt()} 末尾的 {@code future.get()} 永不返回。jstack（16:04 现场）：
 * {@code tool-exec-343958749965000} park 在
 * {@code WebSocketPermissionPrompter.prompt:752 → CompletableFuture.get()}；
 * {@code teammate-worker-d@teamtest-0920b} 因此 park 在
 * {@code StreamingToolExecutor.drainNextBatch:2802 → CompletableFuture.anyOf(...).join()}。
 * 全程不超时、不报错、不退出（worker-d 15:45:08 → 16:04 仍未恢复）。
 *
 * <p><b>CC 对齐</b>：CC {@code utils/swarm/inProcessRunner.ts:117-135 createInProcessCanUseTool}
 * 把 teammate 的 'ask' 决策 push 到 **leader 的** {@code setToolUseConfirmQueue}
 * （leader 的 ToolUseConfirm 弹窗 + worker badge），bridge 不可用时回落到 leader 的 inbox。
 * 落点始终是 **Leader 会话**，从不是 teammate 自己。
 *
 * <p><b>反向实验</b>：把 {@code prompt()} 里的投递会话改回 {@code ctx.sessionId()}
 * ⇒ 第 1 个测试断言的 topic 变成 {@code /topic/sessions/no-session/permission-requests} ⇒ 变红。
 */
@DisplayName("[team-hang] 权限提示投递会话 = Leader 会话（teammate 不能发到 no-session 死 topic）")
class WebSocketPermissionPrompterDeliverySessionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** teammate 的 TUC sessionId 实机值 = 「确无会话」哨兵。 */
    private static final String NO_SESSION_SENTINEL = "no-session";
    /** Leader（= 有 UI 的会话）的 sessionId。 */
    private static final String LEADER_SESSION_ID = "sess-e5315213";

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "bash"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ToolUseContext teammateCtx() {
        return ToolUseContext.of(AGENT_ID, NO_SESSION_SENTINEL)
            .withTeammateIdentity(new TeammateIdentity(
                "worker-d@teamtest-0920b", "worker-d", "teamtest-0920b",
                null, false, LEADER_SESSION_ID));
    }

    private static ToolUseContext plainCtx() {
        return ToolUseContext.of(AGENT_ID, LEADER_SESSION_ID);
    }

    @Test
    @DisplayName("teammate 的 prompt 投递到 Leader 会话 topic（非 no-session 哨兵），且用户作答能解除阻塞")
    void teammatePrompt_isDeliveredToLeaderSessionTopic() throws Exception {
        AtomicReference<String> publishedTopic = new AtomicReference<>();
        AtomicReference<Object> publishedPayload = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        // 用「默认 Answer」而不是对 convertAndSend 打桩：convertAndSend 有重载，
        // 匹配器写法会让 javac 报 ambiguous（实测），默认 Answer 对任一重载都成立。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class, inv -> {
            if ("convertAndSend".equals(inv.getMethod().getName())) {
                publishedTopic.set((String) inv.getArgument(0));
                publishedPayload.set(inv.getArgument(1));
                published.countDown();
            }
            return null;
        });
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 60_000);
        String requestId = "call_01_ET_AeIKEw2XhxgDmBbTMYfz0823";
        ToolUseContext ctx = teammateCtx();

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perm-teammate-test");
            t.setDaemon(true);
            return t;
        });
        try {
            java.util.concurrent.Future<PermissionResult> pending = exec.submit(() ->
                prompter.prompt(new StubTool(), JSON.createObjectNode().put("command", "ls"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("list dirs", List.of(), null, null, false)));

            assertThat(published.await(5, TimeUnit.SECONDS))
                .as("prompt 必须把弹窗推到某个 topic（否则用户永远看不到）").isTrue();
            assertThat(publishedTopic.get())
                .as("teammate 的权限请求必须投递到 **Leader 会话**（CC inProcessRunner.ts:117-135 "
                    + "把 teammate 的 ask 推给 leader 的 ToolUseConfirm）。投到 "
                    + "no-session 哨兵 = 无人订阅 = 工具线程永久阻塞（实机 worker-d 挂 10+ 分钟）")
                .isEqualTo("/topic/sessions/" + LEADER_SESSION_ID + "/permission-requests");

            // [P-b] 事件自称的 sessionId 必须与投递 topic 的会话一致 —— 只改 topic、事件仍自称
            //   ctx.sessionId()（no-session）时「事件自称 no-session 却出现在 leader topic 上」是
            //   事实错误（前端入队/回包路由现在恰好都用订阅 sid + 路径变量，所以行为等价、抓不住）。
            //   本断言把「投递会话 = 事件 sessionId」钉成不变量。
            assertThat(publishedPayload.get())
                .as("STOMP 载荷必须是 MessagePermissionRequestEvent")
                .isInstanceOf(MessagePermissionRequestEvent.class);
            MessagePermissionRequestEvent event = (MessagePermissionRequestEvent) publishedPayload.get();
            assertThat(event.getSessionId())
                .as("事件自称的 sessionId 必须 = 投递会话（Leader 会话），不得是 teammate 的 "
                    + "no-session 哨兵 —— 否则「事件自称 no-session 却挂在 leader topic 上」")
                .isEqualTo(LEADER_SESSION_ID);
            assertThat(event.getRequestId()).isEqualTo(requestId);

            // 用户作答 → future 完成 → 阻塞解除（这是 CC 语义下唯一/正确的逃逸通道，不引入超时）
            prompter.onResponse(requestId, "allow");
            PermissionResult result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result).as("用户允许后工具线程必须能继续")
                .isInstanceOf(PermissionResult.Allow.class);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("非 teammate（主会话/普通子代理）投递会话零变化 = ctx.sessionId()")
    void nonTeammatePrompt_keepsCtxSessionIdTopic() throws Exception {
        AtomicReference<String> publishedTopic = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        // 用「默认 Answer」而不是对 convertAndSend 打桩：convertAndSend 有重载，
        // 匹配器写法会让 javac 报 ambiguous（实测），默认 Answer 对任一重载都成立。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class, inv -> {
            if ("convertAndSend".equals(inv.getMethod().getName())) {
                publishedTopic.set((String) inv.getArgument(0));
                published.countDown();
            }
            return null;
        });
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 60_000);
        String requestId = "req-plain-1";

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perm-plain-test");
            t.setDaemon(true);
            return t;
        });
        try {
            java.util.concurrent.Future<PermissionResult> pending = exec.submit(() ->
                prompter.prompt(new StubTool(), JSON.createObjectNode().put("command", "ls"),
                    new PermissionDecisionReason.Other("test"), plainCtx(), requestId,
                    new PermissionPromptDetails("list dirs", List.of(), null, null, false)));
            assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(publishedTopic.get())
                .as("无 teammate 身份 ⇒ 投递会话保持 ctx.sessionId()（主会话行为零变化）")
                .isEqualTo("/topic/sessions/" + LEADER_SESSION_ID + "/permission-requests");
            prompter.onResponse(requestId, "allow");
            assertThat(pending.get(5, TimeUnit.SECONDS)).isInstanceOf(PermissionResult.Allow.class);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("owningSessionId: 有 teammate 身份取 parentSessionId；无身份/无 parent 回落 ctx.sessionId()")
    void owningSessionId_rules() {
        assertThat(WebSocketPermissionPrompter.owningSessionId(teammateCtx()))
            .as("teammate ⇒ Leader 会话（TeammateIdentity.parentSessionId, CC types.ts:19）")
            .isEqualTo(LEADER_SESSION_ID);
        assertThat(WebSocketPermissionPrompter.owningSessionId(plainCtx()))
            .as("无 teammate 身份 ⇒ 沿用 ctx.sessionId()（零变化）")
            .isEqualTo(LEADER_SESSION_ID);
        // parentSessionId 缺失/空白 ⇒ 回落 ctx.sessionId()（不抛、不改语义）
        ToolUseContext blankParent = ToolUseContext.of(AGENT_ID, NO_SESSION_SENTINEL)
            .withTeammateIdentity(new TeammateIdentity(
                "x@t", "x", "t", null, false, "  "));
        assertThat(WebSocketPermissionPrompter.owningSessionId(blankParent))
            .isEqualTo(NO_SESSION_SENTINEL);
        assertThat(WebSocketPermissionPrompter.owningSessionId(null)).isNull();
    }
}
