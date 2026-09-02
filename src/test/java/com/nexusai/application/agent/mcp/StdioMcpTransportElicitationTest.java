package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * [2026-08-12 WF-B △-6/△-7] StdioMcpTransport elicitation/create 传输层接线测试 · 对齐 CC
 * registerElicitationHandler setRequestHandler（elicitationHandler.ts:77-171）。
 *
 * <p><b>WHY</b>: 旧实现把 server→client 的 {@code elicitation/create} <b>请求</b>（带 requestId）
 * 当 notification 处理，{@code handleRequest} 返回值被丢弃 —— 配置 Elicitation hook 的
 * accept/decline 决策生产不生效，且 server 请求无响应悬挂。本测试钉死传输层契约：
 * <ul>
 *   <li>elicitation/create 请求 → 调用 handleRequest → 决策作为 JSON-RPC result 回传（不丢弃）</li>
 *   <li>hook accept → {@code {action:'accept', content}}；hook decline / blockingError → {@code {action:'decline'}}</li>
 *   <li>无 hook 决策（含 handler 未接线）→ fail-closed {@code {action:'decline'}}（Java 无 form 队列/UI，不悬挂）</li>
 *   <li>既有 client→server 请求响应主流程不被破坏（pending future 仍按 id 完成）</li>
 * </ul>
 */
@DisplayName("[WF-B] StdioMcpTransport elicitation/create 传输层接线")
class StdioMcpTransportElicitationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 构建 HookResult（仅 elicitationResponse/blockingError 两字段有意义）。 */
    private static GenericHook.HookResult result(boolean preventContinuation,
                                                 HookBlockingError blockingError,
                                                 ElicitationResponse elicitationResponse) {
        return new GenericHook.HookResult(preventContinuation, blockingError, null, null, null,
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, PermissionBehavior.ALLOW,
            null, null, null, null, elicitationResponse, null);
    }

    /** 最小 stub registry：executeEvent 返回预置结果。 */
    private static HookRegistry stubRegistry(GenericHook.HookResult toReturn) {
        return new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                return toReturn;
            }
        };
    }

    /** 挂接假 Process + ElicitationHandler 的 transport（绕过 start，不启动 stdout 线程）。 */
    private static StdioMcpTransport transportWith(ElicitationHandler handler, OutputStream out) {
        Process fake = mock(Process.class);
        when(fake.getOutputStream()).thenReturn(out);
        StdioMcpTransport transport = new StdioMcpTransport();
        transport.attachProcessForTesting(fake);
        if (handler != null) {
            transport.setElicitationHandler(handler);
        }
        return transport;
    }

    private static String elicitationCreateLine(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"elicitation/create\""
            + ",\"params\":{\"serverName\":\"srv-a\",\"message\":\"Please confirm\"}}";
    }

    // ═══════════ 1. hook accept → 决策作为 result 回传（返回值不再丢弃）═══════════

    @Test
    @DisplayName("elicitation/create 请求 → hook accept → JSON-RPC result {action:accept, content} 回传")
    void elicitationRequest_hookAccept_returnsAccept() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = transportWith(new ElicitationHandler(stubRegistry(
            result(false, null, new ElicitationResponse("accept", Map.of("k", "v"))))), out);

        transport.handleLine(elicitationCreateLine(5));

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt())
            .as("JSON-RPC result 必须回传请求 id（不悬挂 server）")
            .isEqualTo(5);
        assertThat(frame.get("result").get("action").asText())
            .as("hook accept 决策透传到传输层响应")
            .isEqualTo("accept");
        assertThat(frame.get("result").get("content").get("k").asText())
            .as("hook content 载荷透传")
            .isEqualTo("v");
    }

    // ═══════════ 2. hook decline / blockingError → {action:'decline'} 回传 ═══════════

    @Test
    @DisplayName("elicitation/create 请求 → hook blockingError → result {action:decline} 回传")
    void elicitationRequest_hookBlockingError_returnsDecline() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = transportWith(new ElicitationHandler(stubRegistry(
            result(true, new HookBlockingError("denied by hook", "cmd"),
                new ElicitationResponse("accept", null)))), out);

        transport.handleLine(elicitationCreateLine(7));

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt()).isEqualTo(7);
        assertThat(frame.get("result").get("action").asText())
            .as("blockingError 优先 → decline（CC runElicitationHooks :241-243）")
            .isEqualTo("decline");
    }

    // ═══════════ 3. 无 hook 决策 → fail-closed decline 回传 ═══════════

    @Test
    @DisplayName("elicitation/create 请求 → 无 hook 决策 → fail-closed result {action:decline} 回传")
    void elicitationRequest_noHookDecision_failClosedDecline() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = transportWith(new ElicitationHandler(stubRegistry(
            result(false, null, null))), out);

        transport.handleLine(elicitationCreateLine(9));

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("result").get("action").asText())
            .as("无 form 队列/UI → fail-closed decline，不悬挂 server 请求")
            .isEqualTo("decline");
    }

    // ═══════════ 4. handler 未接线 → fail-closed decline 回传 ═══════════

    @Test
    @DisplayName("elicitation/create 请求 → ElicitationHandler 未注入 → fail-closed decline 回传")
    void elicitationRequest_noHandler_failClosedDecline() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = transportWith(null, out);

        transport.handleLine(elicitationCreateLine(11));

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt()).isEqualTo(11);
        assertThat(frame.get("result").get("action").asText())
            .as("handler 未接线（@Autowired(required=false)）→ fail-closed decline")
            .isEqualTo("decline");
    }

    // ═══════════ 5. 既有 client→server 响应主流程不破坏 ═══════════

    @Test
    @DisplayName("client→server 请求的响应仍按 id 完成 pending future（主流程不破坏）")
    void clientServerResponse_stillCompletesPendingFuture() {
        StdioMcpTransport transport = transportWith(null, new ByteArrayOutputStream());

        CompletableFuture<JsonNode> fut = transport.sendRequest("tools/call", Map.of());
        transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");

        assertThat(fut.join().path("content").get(0).path("text").asText())
            .as("带 id 无 method 的帧仍走 pending 响应分支（非 elicitation 请求误入）")
            .isEqualTo("ok");
    }

    // ═══════════ 6. 未知 server→client 请求 → -32601 错误（不悬挂）═══════════

    @Test
    @DisplayName("未知 server→client 请求 → JSON-RPC -32601 Method not found（不悬挂）")
    void unknownServerRequest_returnsMethodNotFound() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = transportWith(null, out);

        transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"sampling/createMessage\",\"params\":{}}");

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt()).isEqualTo(13);
        assertThat(frame.get("error").get("code").asInt())
            .as("未处理 server→client 请求按协议回 -32601")
            .isEqualTo(-32601);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-SS-01] form 模式用户响应链（挂起 → resolveFormResponse → 写帧）
    // ════════════════════════════════════════════════════════════════════════

    /** 无 hook 决策 + ws 已注入的 handler · 对齐 CC elicitationHandler.ts:114-153（无 hook → 入队等用户）。 */
    private static ElicitationHandler formHandler(SimpMessagingTemplate ws) {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null)));
        handler.setWebSocket(ws);
        return handler;
    }

    @Test
    @DisplayName("[IMP-SS-01] form 模式：无 hook 决策 + ws 接线 → 请求挂起不写帧，resolve 后写 accept（CC :127-150/:138-146）")
    void formMode_suspends_thenResolveWritesAccept() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ElicitationHandler handler = formHandler(ws);
        StdioMcpTransport transport = transportWith(handler, out);

        transport.handleLine(elicitationCreateLine(21));

        assertThat(out.toString(StandardCharsets.UTF_8))
            .as("无 hook 决策时请求挂起（对齐 CC 入队 AppState 等用户），不立即写 decline")
            .isEmpty();

        // 用户弹窗提交 accept → resolveFormResponse → 传输层把决策写为 JSON-RPC result
        // （requestId = JSON-RPC id "21"，serverName 由 resolveServerName 解析 "srv-a"）
        boolean resolved = handler.resolveFormResponse("21", "srv-a", "accept", Map.of("k", "v"));

        assertThat(resolved).isTrue();
        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt()).isEqualTo(21);
        assertThat(frame.get("result").get("action").asText())
            .as("用户 form 响应经 respond 回调 resolve → JSON-RPC result 回传")
            .isEqualTo("accept");
    }
}
