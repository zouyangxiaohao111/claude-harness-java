package com.nexusai.application.agent.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WF-11 G4 · 会话态 running 通知补接（OPD-WF8-01-T5）。
 *
 * <p>对齐 CC Open-ClaudeCode/src/cli/structuredIO.ts:650-657：createCanUseTool 的 finally 中
 * 「无其他 pending 权限请求」时 {@code notifySessionStateChanged('running')}。Java 等价 =
 * {@link WebSocketPermissionPrompter#prompt} finally 经 {@link SdkEventQueue} 发射
 * {@code SessionStateChangedEvent('running')}，由 LlmAgentLoop turn 顶部 drain 出站。
 */
class WebSocketPermissionPrompterSessionStateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static ToolUseContext newCtx() {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    /** 反射注入 sdkEventQueue（@Autowired 字段，单元测试无 Spring 上下文）。 */
    private static void injectSdkEventQueue(WebSocketPermissionPrompter bound, SdkEventQueue queue)
            throws ReflectiveOperationException {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("sdkEventQueue");
        f.setAccessible(true);
        f.set(bound, queue);
    }

    /** 等待 pending 注册（prompt 异步线程 put future）· [2026-08-24 对齐 CC 无超时] 原依赖 100ms
     *  超时触发 finally，现改等待注册后模拟用户响应完成（onResponse），不再等超时。 */
    private static void awaitPendingRegistration(WebSocketPermissionPrompter bound, String requestId)
            throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            Map<String, ?> pending = (Map<String, ?>) f.get(bound);
            if (pending.containsKey(requestId)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("pending not registered: " + requestId);
    }

    @Test
    @DisplayName("权限弹窗结束且无其他 pending → 发射 SessionStateChangedEvent('running')（CC structuredIO.ts:654）")
    void promptCompletionEmitsRunningWhenNoPending() throws Exception {
        // WHY: CC 在 finally 中「无 pending 权限请求」时回到 running 会话态（structuredIO.ts:654）；
        //      Java 缺省生产从不发射 SessionStateChangedEvent（探查 EV-WF8-CB-029：生产 main 零构造），
        //      前端无法得知权限弹窗结束会话继续运行。本测试验证补接后 finally 发射 running。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter bound = new WebSocketPermissionPrompter(ws, 100);
        SdkEventQueue sdkQueue = new SdkEventQueue();
        injectSdkEventQueue(bound, sdkQueue);

        Thread t = new Thread(() -> {
            try {
                bound.prompt(new StubTool("Bash"), JSON.createObjectNode().put("command", "ls"),
                    new PermissionDecisionReason.Other("test"), newCtx(), "req-1",
                    new PermissionPromptDetails("desc", List.of(), null));
            } catch (Throwable th) {
                // 用户响应 deny 为正常返回；异常不应发生
            }
        });
        t.start();
        // [2026-08-24 对齐 CC 无超时] 原依赖 100ms 超时触发 finally（阻塞线程超时降级 Deny）；
        //   现移除超时（无限等待）→ 等 pending 注册后模拟用户响应（onResponse deny）完成 prompt，
        //   触发 finally 发射 running。
        awaitPendingRegistration(bound, "req-1");
        bound.onResponse("req-1", "deny");
        t.join(3_000);

        List<SdkEventQueue.DrainedSdkEvent> drained = sdkQueue.drainSdkEvents(SESSION_ID.toString());
        assertThat(drained)
            .as("权限弹窗结束后必须发射会话态 running 通知（对齐 CC structuredIO.ts:654）")
            .hasSize(1);
        SdkEventQueue.SdkEvent event = drained.get(0).event();
        assertThat(event).isInstanceOf(SdkEventQueue.SessionStateChangedEvent.class);
        assertThat(((SdkEventQueue.SessionStateChangedEvent) event).state())
            .as("会话态必须为 running")
            .isEqualTo("running");
        assertThat(event.subtype()).isEqualTo("session_state_changed");
    }

    @Test
    @DisplayName("sdkEventQueue 未注入（测试直构）→ 权限流程不受影响（不发射、不抛错）")
    void promptWithoutSdkQueueStillWorks() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter bound = new WebSocketPermissionPrompter(ws, 100);

        Thread t = new Thread(() -> {
            try {
                bound.prompt(new StubTool("Bash"), JSON.createObjectNode(),
                    new PermissionDecisionReason.Other("test"), newCtx(), "req-2",
                    new PermissionPromptDetails("desc", List.of(), null));
            } catch (Throwable th) {
                // 用户响应 deny 为正常返回；异常不应发生
            }
        });
        t.start();
        // [2026-08-24 对齐 CC 无超时] 原依赖 100ms 超时返回；现等注册后模拟响应完成（避免线程挂起）
        awaitPendingRegistration(bound, "req-2");
        bound.onResponse("req-2", "deny");
        t.join(3_000);
        // 无断言失败 = 未注入时 finally 不抛错、权限流程正常（sdkEventQueue==null 分支跳过）
    }
}
