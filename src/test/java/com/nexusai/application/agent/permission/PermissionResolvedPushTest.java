package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P1 收卡] 竞速落地的统一 resolved 出口：桌面点 / abort(Stop) 都要推
 * {@code /topic/sessions/{sid}/permission-resolved}，否则后端已继续、前端卡片还挂着。
 */
@DisplayName("[P1] 统一 resolved 推送（桌面 / abort）")
class PermissionResolvedPushTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-resolved-push";
    private static final String TOPIC = "/topic/sessions/" + SESSION + "/permission-resolved";

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ToolUseContext ctx(AbortController abort) {
        com.nexusai.common.SessionProjectRoot.setForSession(SESSION,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return ToolUseContext.of("a00000000000ff01", SESSION, PermissionMode.DEFAULT,
            List.of(), "", abort, List.of(),
            new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null),
            PermissionMode.DEFAULT);
    }

    private static Map<?, ?> capture(SimpMessagingTemplate ws) {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(ws, Mockito.atLeastOnce()).convertAndSend(eq(TOPIC), sent.capture());
        return (Map<?, ?>) sent.getAllValues().get(sent.getAllValues().size() - 1);
    }

    @Test
    @DisplayName("桌面裁决 ⇒ 推 resolved(source=user)")
    void desktopDecisionPushesResolved() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        AtomicReference<PermissionResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(prompter.prompt(new StubTool(), JSON.createObjectNode(),
            new PermissionDecisionReason.Other("t"), ctx(AbortController.NOOP), "req-res-1",
            new PermissionPromptDetails("d", List.of(), null))));
        t.start();
        Thread.sleep(150);
        prompter.onResponse("req-res-1", "allow");
        t.join(3_000);

        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
        Map<?, ?> payload = capture(ws);
        assertThat(payload.get("requestId")).isEqualTo("req-res-1");
        assertThat(payload.get("source")).isEqualTo("user");
    }

    @Test
    @DisplayName("abort/Stop ⇒ 推 resolved(source=abort)，future 以 deny 收敛")
    void abortPushesResolved() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        AbortController abort = new AbortController();
        AtomicReference<PermissionResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(prompter.prompt(new StubTool(), JSON.createObjectNode(),
            new PermissionDecisionReason.Other("t"), ctx(abort), "req-res-2",
            new PermissionPromptDetails("d", List.of(), null))));
        t.start();
        Thread.sleep(150);
        abort.abort();
        t.join(3_000);

        assertThat(out.get()).isInstanceOf(PermissionResult.Deny.class);
        Map<?, ?> payload = capture(ws);
        assertThat(payload.get("requestId")).isEqualTo("req-res-2");
        assertThat(payload.get("source")).isEqualTo("abort");
    }

    /**
     * [回归] 渠道裁决 allow 的 resolved 推送**不得**依赖 INFO 日志级别。
     *
     * <p>WHY：曾把 {@code notifyResolved} 写在 {@code if (log.isInfoEnabled())} 里面 ⇒ 日志级别一旦
     * 调到 WARN（生产常见），桌面卡片就永远不关。本用例把该 logger 强制降到 WARN 再走渠道 allow：
     * 若有人把 notifyResolved 塞回日志判断，这里会拿不到 resolved 帧（RED）。
     */
    @Test
    @DisplayName("渠道 allow ⇒ 推 resolved(source=channel)，且 INFO 关闭也必须推")
    void channelAllowPushesResolvedEvenWhenInfoDisabled() throws Exception {
        ch.qos.logback.classic.Logger prompterLog = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(WebSocketPermissionPrompter.class);
        ch.qos.logback.classic.Level oldLevel = prompterLog.getLevel();
        prompterLog.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
            CountDownLatch channelRegistered = new CountDownLatch(1);
            StompChannelPermissionCallbacks base = new StompChannelPermissionCallbacks(channelWs);
            ChannelPermissionCallbacks channel = new ChannelPermissionCallbacks() {
                @Override public String shortRequestId(String toolUseId) {
                    return base.shortRequestId(toolUseId);
                }
                @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
                    Runnable unsub = base.onResponse(requestId, handler);
                    channelRegistered.countDown();
                    return unsub;
                }
                @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                                  String description, JsonNode displayInput) {
                    base.sendRequest(sessionId, requestId, toolName, description, displayInput);
                }
                @Override public boolean resolve(String requestId, String behavior, String fromServer) {
                    return base.resolve(requestId, behavior, fromServer);
                }
            };
            SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
            WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
            prompter.wireRacersForTesting(null, channel);
            // 渠道资格两门：settings 两道门开；sessionModeService=null ⇒ 视为已绑定。
            com.nexusai.domain.settings.SettingsService settings =
                mock(com.nexusai.domain.settings.SettingsService.class);
            when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(true);
            when(settings.readDbChannelsEnabled()).thenReturn(true);
            ReflectionTestUtils.setField(prompter, "settingsService", settings);

            AtomicReference<PermissionResult> out = new AtomicReference<>();
            Thread t = new Thread(() -> out.set(prompter.prompt(new StubTool(), JSON.createObjectNode(),
                new PermissionDecisionReason.Other("t"), ctx(AbortController.NOOP), "req-ch-res-1",
                new PermissionPromptDetails("d", List.of(), null))));
            t.start();

            ArgumentCaptor<Object> reqCaptor = ArgumentCaptor.forClass(Object.class);
            verify(channelWs, timeout(3_000)).convertAndSend(anyString(), reqCaptor.capture());
            ChannelPermissionRequestEvent request =
                (ChannelPermissionRequestEvent) reqCaptor.getValue();
            assertThat(channelRegistered.await(3, TimeUnit.SECONDS))
                .as("channel onResponse 必须先注册（防 delete-before-call 竞态）").isTrue();

            assertThat(channel.resolve(request.getRequestId(), "allow", "wechat")).isTrue();
            t.join(3_000);

            assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
            Map<?, ?> payload = capture(ws);
            assertThat(payload.get("requestId")).isEqualTo("req-ch-res-1");
            assertThat(payload.get("source"))
                .as("渠道裁决的 resolved 必须推给前端（不能受 INFO 日志门影响）")
                .isEqualTo("channel");
        } finally {
            prompterLog.setLevel(oldLevel);
        }
    }
}
