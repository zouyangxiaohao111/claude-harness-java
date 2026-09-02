package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.eventbus.ws.ElicitationFormRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [2026-08-12 探查 △-02] ElicitationHandler 决策消费测试 · 对齐 CC runElicitationHooks /
 * runElicitationResultHooks (elicitationHandler.ts:227-250 / :272-295).
 *
 * <p><b>WHY</b>: 旧实现 fire-and-forget（executeEvent 返回值丢弃），配置 Elicitation hook
 * 的 accept/decline/cancel 决策不生效。△-01 把 elicitationResponse/elicitationResultResponse/
 * blockingError 回填到 HookResult 顶层后，本测试钉死 ElicitationHandler 的消费语义：
 * <ul>
 *   <li>blockingError 优先 → {@code {action:'decline'}}（CC :227-233/:272-283）</li>
 *   <li>elicitationResponse / elicitationResultResponse → action + content（CC :245-250/:290-295）</li>
 *   <li>无决策 → null（CC :252-255 返回 undefined）</li>
 *   <li>resolveDecision 按事件分流：Elicitation 消费 elicitationResponse，
 *       ElicitationResult 消费 elicitationResultResponse</li>
 * </ul>
 */
class ElicitationHandlerDecisionTest {

    private static GenericHook.HookResult result(boolean preventContinuation,
                                                 HookBlockingError blockingError,
                                                 ElicitationResponse elicitationResponse,
                                                 ElicitationResponse elicitationResultResponse) {
        return new GenericHook.HookResult(preventContinuation, blockingError, null, null, null,
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, PermissionBehavior.ALLOW,
            null, null, null, null, elicitationResponse, elicitationResultResponse);
    }

    /** 最小 stub registry：executeEvent 返回预置结果（不依赖 Spring）。 */
    private static HookRegistry stubRegistry(GenericHook.HookResult toReturn) {
        return new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                return toReturn;
            }
        };
    }

    // ═══════════ 1. blockingError 优先 → decline（Elicitation）═══════════

    @Test
    @DisplayName("△-02 Elicitation: blockingError → {action:'decline'} (CC elicitationHandler.ts:227-233)")
    void elicitation_blockingError_decline() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, new HookBlockingError("Elicitation denied by hook", "cmd"),
                new ElicitationResponse("accept", Map.of("k", "v")), null)));

        ElicitationResponse decision = handler.handleRequest("server-a", "msg");

        assertThat(decision)
            .as("blockingError 优先于 elicitationResponse (CC runElicitationHooks 先查 block)")
            .isNotNull()
            .extracting(ElicitationResponse::action)
            .isEqualTo("decline");
    }

    // ═══════════ 2. elicitationResponse → action/content（Elicitation）═══════════

    @Test
    @DisplayName("△-02 Elicitation: elicitationResponse → {action, content} (CC elicitationHandler.ts:245-250)")
    void elicitation_response_accepted() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, new ElicitationResponse("accept", Map.of("k", "v")), null)));

        ElicitationResponse decision = handler.handleRequest("server-a", "msg");

        assertThat(decision)
            .isNotNull()
            .extracting(ElicitationResponse::action)
            .isEqualTo("accept");
        assertThat(decision.content())
            .containsEntry("k", "v");
    }

    // ═══════════ 3. 无决策 → null（Elicitation）═══════════

    @Test
    @DisplayName("△-02 Elicitation: 无 elicitationResponse/blockingError → null (CC :252-255)")
    void elicitation_noDecision_null() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null, null)));

        ElicitationResponse decision = handler.handleRequest("server-a", "msg");

        assertThat(decision).isNull();
    }

    // ═══════════ 4. ElicitationResult: elicitationResultResponse override ═══════════

    @Test
    @DisplayName("△-02 ElicitationResult: elicitationResultResponse override (CC elicitationHandler.ts:290-295)")
    void elicitationResult_response_override() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null, new ElicitationResponse("cancel", Map.of("reason", "hook")))));

        ElicitationResponse decision = handler.handleResponse("server-a", "accept");

        assertThat(decision)
            .as("ElicitationResult hook 可 override 用户响应 (CC runElicitationResultHooks)")
            .isNotNull()
            .extracting(ElicitationResponse::action)
            .isEqualTo("cancel");
    }

    // ═══════════ 5. 事件分流：Elicitation 不消费 elicitationResultResponse ═══════════

    @Test
    @DisplayName("△-02 分流: Elicitation 只消费 elicitationResponse, 不误读 elicitationResultResponse")
    void elicitation_doesNotConsumeResultResponse() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null, new ElicitationResponse("cancel", null))));

        ElicitationResponse decision = handler.handleRequest("server-a", "msg");

        assertThat(decision)
            .as("Elicitation 事件必须消费 elicitationResponse 而非 elicitationResultResponse")
            .isNull();
    }

    // ═══════════ 6. hookRegistry 为 null → 降级 null（不 NPE）═══════════

    @Test
    @DisplayName("△-02 hookRegistry 未接线 → null 降级, 不抛 NPE")
    void noRegistry_returnsNull() {
        ElicitationHandler handler = new ElicitationHandler(null);

        assertThat(handler.handleRequest("server-a", "msg")).isNull();
        assertThat(handler.handleResponse("server-a", "accept")).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [WF-B] 传输层接线通知测试（✗-2 / △-11）
    // ════════════════════════════════════════════════════════════════════════

    /** 记录 executeEvent 事件的 stub registry（不返回决策）。 */
    private static class RecordingRegistry extends HookRegistry {
        final List<HookEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public GenericHook.HookResult executeEvent(HookEvent event) {
            events.add(event);
            return null;
        }
    }

    /** 记录事件并返回预置结果的 stub registry。 */
    private static HookRegistry stubRegistryRecording(GenericHook.HookResult toReturn,
                                                      List<HookEvent> out) {
        return new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                out.add(event);
                return toReturn;
            }
        };
    }

    @Test
    @DisplayName("[WF-B] handleResponse 后发 elicitation_response 通知（CC elicitationHandler.ts:283-301）")
    void handleResponse_firesElicitationResponseNotification() {
        RecordingRegistry registry = new RecordingRegistry();
        ElicitationHandler handler = new ElicitationHandler(registry);

        handler.handleResponse("server-a", "accept");

        boolean fired = registry.events.stream().anyMatch(ev ->
            ev.type() == HookEventType.NOTIFICATION
                && "elicitation_response".equals(ev.data().get("notification_type")));
        assertThat(fired)
            .as("ElicitationResult 每次响应后发 elicitation_response 通知（observability，✗-2）")
            .isTrue();
    }

    @Test
    @DisplayName("[WF-B] handleResponse blockingError → 通知消息用最终 action=decline（CC :282-288）")
    void handleResponse_blockingError_notificationDecline() {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        ElicitationHandler handler = new ElicitationHandler(stubRegistryRecording(
            result(true, new HookBlockingError("denied", "cmd"),
                new ElicitationResponse("accept", null), null), events));

        handler.handleResponse("server-a", "accept");

        String msg = events.stream()
            .filter(ev -> ev.type() == HookEventType.NOTIFICATION)
            .map(ev -> String.valueOf(ev.data().get("message")))
            .findFirst().orElse("");
        assertThat(msg)
            .as("blockingError → 通知消息反映最终 action=decline，而非用户原始 accept")
            .contains("decline");
    }

    @Test
    @DisplayName("[WF-B] fireElicitationComplete 发 elicitation_complete 通知（CC elicitationHandler.ts:183-186）")
    void fireElicitationComplete_firesNotification() {
        RecordingRegistry registry = new RecordingRegistry();
        ElicitationHandler handler = new ElicitationHandler(registry);

        handler.fireElicitationComplete("srv-a", "el-1");

        boolean fired = registry.events.stream().anyMatch(ev ->
            ev.type() == HookEventType.NOTIFICATION
                && "elicitation_complete".equals(ev.data().get("notification_type"))
                && String.valueOf(ev.data().get("message")).contains("el-1"));
        assertThat(fired)
            .as("server 完成通知处理时同时发 elicitation_complete 通知（△-11）")
            .isTrue();
    }

    @Test
    @DisplayName("[WF-B] handleRequest 完整重载传 mode/url/elicitation_id 到事件（CC runElicitationHooks 全量 params）")
    void handleRequest_fullContext_passesFields() {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        ElicitationHandler handler = new ElicitationHandler(stubRegistryRecording(null, events));

        handler.handleRequest("srv-a", "msg", "url", "https://x.example", "el-9", null);

        HookEvent ev = events.get(0);
        assertThat(ev.type()).isEqualTo(HookEventType.ELICITATION);
        assertThat(ev.data().get("mcp_server_name")).isEqualTo("srv-a");
        assertThat(ev.data().get("mode")).isEqualTo("url");
        assertThat(ev.data().get("url")).isEqualTo("https://x.example");
        assertThat(ev.data().get("elicitation_id")).isEqualTo("el-9");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-SS-01] form 模式用户响应链 + abort→cancel（对齐 CC elicitationHandler.ts:77-171）
    // ════════════════════════════════════════════════════════════════════════

    /** 注入 Mockito mock SimpMessagingTemplate 并返回已注入的 handler。 */
    private static ElicitationHandler handlerWithWs(HookRegistry registry, SimpMessagingTemplate ws) {
        ElicitationHandler handler = new ElicitationHandler(registry);
        handler.setWebSocket(ws);
        return handler;
    }

    @Test
    @DisplayName("[IMP-SS-01] beginFormElicitation hook 决策 → 已完成的 future（CC :245-250 直接返回）")
    void beginForm_hookDecision_completedFuture() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, new ElicitationResponse("accept", Map.of("k", "v")), null)));

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", null, null, null, null, "5");

        assertThat(future).isCompletedWithValueMatching(r -> "accept".equals(r.action()));
    }

    @Test
    @DisplayName("[IMP-SS-01] beginFormElicitation 无弹窗通道（ws 未注入）→ fail-closed decline（CC :252-255 无决策 + 既有传输层 fail-closed）")
    void beginForm_noWs_failClosedDecline() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null, null)));

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", null, null, null, null, "5");

        assertThat(future).isCompletedWithValueMatching(r -> "decline".equals(r.action()));
    }

    @Test
    @DisplayName("[IMP-SS-01] beginFormElicitation 弹窗已推 + 挂起（CC elicitationHandler.ts:127-150 setAppState queue.push）")
    void beginForm_wsWired_suspendsAndPushes() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "请确认", "form", null, null, Map.of("type", "string"), "7");

        assertThat(future).isNotCompleted();
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(org.mockito.ArgumentMatchers.eq(ElicitationHandler.ELICITATION_TOPIC),
            payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isInstanceOf(ElicitationFormRequestEvent.class);
        ElicitationFormRequestEvent ev = (ElicitationFormRequestEvent) payloadCaptor.getValue();
        assertThat(ev.getRequestId()).isEqualTo("7");
        assertThat(ev.getServerName()).isEqualTo("srv-a");
        assertThat(ev.getMessage()).isEqualTo("请确认");
        assertThat(ev.getMode()).isEqualTo("form");
        assertThat(ev.getRequestedSchema()).isNotNull();
    }

    @Test
    @DisplayName("[IMP-SS-01] resolveFormResponse 完成挂起 future（CC :138-146 respond(result) resolve）")
    void resolveForm_completesPending() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", "form", null, null, null, "7");
        boolean resolved = handler.resolveFormResponse("7", "srv-a", "accept", Map.of("k", "v"));

        assertThat(resolved).isTrue();
        assertThat(future).isCompletedWithValueMatching(r ->
            "accept".equals(r.action()) && Map.of("k", "v").equals(r.content()));
    }

    @Test
    @DisplayName("[IMP-SS-01] resolveFormResponse 未知 requestId → false（已超时/已 abort 忽略）")
    void resolveForm_unknownId_false() {
        ElicitationHandler handler = new ElicitationHandler(stubRegistry(
            result(false, null, null, null)));

        assertThat(handler.resolveFormResponse("nope", "srv-a", "accept", null)).isFalse();
    }

    @Test
    @DisplayName("[IMP-SS-01-返工-4-6] resolveFormResponse content 含 null 值不悬挂（ElicitationResponse Map.copyOf NPE 防护）")
    void resolveForm_contentWithNullValue_doesNotHang() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);
        // 超时设长：本用例验证的是「响应即时 resolve」，而非 60s 超时兜底——必须证明 future 不被 NPE 卡死
        handler.setFormDecisionTimeoutMs(60_000L);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", "form", null, null, null, "7");

        // 前端表单空字段常见 content 形如 {"field":null} —— Map.copyOf 对 null value 抛 NPE
        Map<String, Object> formContent = new java.util.LinkedHashMap<>();
        formContent.put("emptyField", null);
        formContent.put("realField", "value");

        boolean resolved = handler.resolveFormResponse("7", "srv-a", "accept", formContent);

        // 意图：content 含 null 值必须被清洗后构造 ElicitationResponse（Map.copyOf null value NPE 防护），
        // future 必须即时完成（2s 内）而非无限期悬挂——「前端不响应不悬挂」不变量。
        assertThat(resolved).isTrue();
        ElicitationResponse decision = future.get(2, TimeUnit.SECONDS);
        assertThat(decision.action()).isEqualTo("accept");
        // null 值字段被剔除，非空字段保留
        assertThat(decision.content())
            .containsEntry("realField", "value")
            .doesNotContainKey("emptyField");
    }

    @Test
    @DisplayName("[IMP-SS-01] abortFormElicitation → cancel（CC :115-117 onAbort → resolve({action:'cancel'})）")
    void abortForm_completesCancel() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", "form", null, null, null, "7");
        boolean aborted = handler.abortFormElicitation("7", "srv-a");

        assertThat(aborted).isTrue();
        assertThat(future).isCompletedWithValueMatching(r -> "cancel".equals(r.action()));
    }

    @Test
    @DisplayName("[IMP-SS-01] abortAllPendingForServer 关闭时 abort 该 server 全部挂起 → cancel（对齐 client.ts:2958-2962）")
    void abortAllForServer_cancelsAll() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);

        CompletableFuture<ElicitationResponse> f1 =
            handler.beginFormElicitation("srv-a", "m1", "form", null, null, null, "1");
        CompletableFuture<ElicitationResponse> f2 =
            handler.beginFormElicitation("srv-a", "m2", "form", null, null, null, "2");
        handler.beginFormElicitation("srv-b", "m3", "form", null, null, null, "3");

        int aborted = handler.abortAllPendingForServer("srv-a");

        assertThat(aborted).isEqualTo(2);
        assertThat(f1).isCompletedWithValueMatching(r -> "cancel".equals(r.action()));
        assertThat(f2).isCompletedWithValueMatching(r -> "cancel".equals(r.action()));
        // srv-b 的挂起不受影响
        assertThat(handler.resolveFormResponse("3", "srv-b", "accept", null)).isTrue();
    }

    @Test
    @DisplayName("[IMP-SS-01-返工] abortFormElicitation 仍跑 ElicitationResult hook（override 可把 cancel 改为 decline，CC :159-165）")
    void abortForm_resultHookCanOverrideCancel() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        // elicitationResultResponse = decline → abort→cancel 后 result hook 把 final 决策 override 为 decline
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, new ElicitationResponse("decline", null))), ws);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", "form", null, null, null, "7");
        boolean aborted = handler.abortFormElicitation("7", "srv-a");

        assertThat(aborted).isTrue();
        // 意图：abort→cancel 的 Promise resolve 后 result hook 仍执行（对齐 CC runElicitationResultHooks），
        // hook override 优先 → final = decline 而非 cancel
        assertThat(future).isCompletedWithValueMatching(r -> "decline".equals(r.action()));
    }

    @Test
    @DisplayName("[IMP-SS-01-返工] abortAllPendingForServer 每个 cancel 均跑 ElicitationResult hook（result hook override 生效）")
    void abortAll_resultHookCanOverrideCancel() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, new ElicitationResponse("decline", null))), ws);

        CompletableFuture<ElicitationResponse> f1 =
            handler.beginFormElicitation("srv-a", "m1", "form", null, null, null, "1");
        CompletableFuture<ElicitationResponse> f2 =
            handler.beginFormElicitation("srv-a", "m2", "form", null, null, null, "2");

        int aborted = handler.abortAllPendingForServer("srv-a");

        assertThat(aborted).isEqualTo(2);
        // 意图：传输层 close 的每个 abort→cancel 都走 result hook（对齐 CC onAbort → resolve → runElicitationResultHooks）
        assertThat(f1).isCompletedWithValueMatching(r -> "decline".equals(r.action()));
        assertThat(f2).isCompletedWithValueMatching(r -> "decline".equals(r.action()));
    }

    @Test
    @DisplayName("[IMP-SS-01] form 超时 fail-closed → decline（前端不响应不悬挂）")
    void beginForm_timeout_failClosedDecline() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ElicitationHandler handler = handlerWithWs(stubRegistry(
            result(false, null, null, null)), ws);
        handler.setFormDecisionTimeoutMs(50L);

        CompletableFuture<ElicitationResponse> future =
            handler.beginFormElicitation("srv-a", "msg", "form", null, null, null, "9");

        ElicitationResponse decision = future.get(2, TimeUnit.SECONDS);
        assertThat(decision.action()).isEqualTo("decline");
    }
}
