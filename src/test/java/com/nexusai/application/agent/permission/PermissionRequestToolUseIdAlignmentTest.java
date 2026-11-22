package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.eventbus.ws.BridgePermissionRequestEvent;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P0] 同一次权限请求向 message / bridge / channel 三条 topic 各推一份事件，
 * 三份必须携带<b>同一个 toolUseId</b>。
 *
 * <p><b>WHY</b>（真机实测失败）：三份事件的 requestId 各不相同（owner / 随机 UUID / 5 位短 ID），
 * 前端只能按三条通道共用的 {@code toolUseId} 去重与结算。任何一条缺失或取了别的值 ⇒
 * 队列里堆副本 ⇒ 卡片不消失、输入框锁死（要连点两次「允许」）。
 *
 * <p>本测试同时锁死 permission-resolved 也带同一个 toolUseId —— 前端据此清**全部**副本。
 */
@DisplayName("[P0] 权限三通道 toolUseId 同值")
class PermissionRequestToolUseIdAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "a0000000000aa001";
    private static final String SESSION = "sess-p0-tooluseid";

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    /** 只捕获出站 bridge toolUseId 的最小回调实现。 */
    private static final class CapturingBridge implements BridgePermissionCallbacks {
        final AtomicReference<String> toolUseId = new AtomicReference<>();
        final AtomicReference<String> bridgeRequestId = new AtomicReference<>();
        final List<Consumer<BridgeResponse>> handlers = new CopyOnWriteArrayList<>();

        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                          JsonNode displayInput, String toolUseIdArg, String description,
                                          List<PermissionUpdate> suggestions, String blockedPath) {
            bridgeRequestId.set(requestId);
            toolUseId.set(toolUseIdArg);
        }
        @Override public Runnable onResponse(String requestId, Consumer<BridgeResponse> handler) {
            handlers.add(handler);
            return () -> handlers.remove(handler);
        }
        @Override public void cancelRequest(String requestId) { }
        @Override public void sendResponse(String requestId, BridgeResponse response) { }
        @Override public boolean resolve(String requestId, BridgeResponse response) { return false; }
    }

    private static ToolUseContext ctx() {
        com.nexusai.common.SessionProjectRoot.setForSession(SESSION,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null),
            PermissionMode.DEFAULT);
    }

    private static WebSocketPermissionPrompter prompter(SimpMessagingTemplate ws,
                                                       StompChannelPermissionCallbacks channel,
                                                       CapturingBridge bridge) {
        WebSocketPermissionPrompter p = new WebSocketPermissionPrompter(ws, 100);
        SettingsService settings = mock(SettingsService.class);
        when(settings.readDbChannelsEnabled()).thenReturn(true);
        when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(p, "settingsService", settings);
        SessionModeService sessionMode = mock(SessionModeService.class);
        when(sessionMode.hasAnyChannel(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(p, "sessionModeService", sessionMode);
        ReflectionTestUtils.setField(p, "channelCallbacks", channel);
        ReflectionTestUtils.setField(p, "bridgeCallbacks", bridge);
        return p;
    }

    @Test
    @DisplayName("三条事件 + permission-resolved 的 toolUseId 全等于同一个值")
    void allThreeChannelsShareSameToolUseId() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        StompChannelPermissionCallbacks channel = new StompChannelPermissionCallbacks(channelWs);
        CapturingBridge bridge = new CapturingBridge();
        WebSocketPermissionPrompter prompter = prompter(ws, channel, bridge);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "call_relay_tooluseid_probe";
        Thread t = new Thread(() -> out.set(prompter.prompt(new StubTool(), JSON.createObjectNode(),
            new PermissionDecisionReason.Other("test"), ctx(), requestId,
            new PermissionPromptDetails("运行命令", List.of(), null))));
        t.setDaemon(true);
        t.start();

        // 等 channel 出站（bridge 是同步发送，message 也是同步发送）
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && bridge.toolUseId.get() == null) {
            Thread.sleep(10);
        }

        ArgumentCaptor<Object> msgSent = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ws, org.mockito.Mockito.atLeastOnce())
            .convertAndSend(anyString(), msgSent.capture());
        MessagePermissionRequestEvent message = msgSent.getAllValues().stream()
            .filter(v -> v instanceof MessagePermissionRequestEvent)
            .map(v -> (MessagePermissionRequestEvent) v)
            .findFirst().orElseThrow(() -> new AssertionError("未捕获 message 事件"));

        ArgumentCaptor<Object> channelSent = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(channelWs, org.mockito.Mockito.atLeastOnce())
            .convertAndSend(anyString(), channelSent.capture());
        ChannelPermissionRequestEvent channelEvent = channelSent.getAllValues().stream()
            .filter(v -> v instanceof ChannelPermissionRequestEvent)
            .map(v -> (ChannelPermissionRequestEvent) v)
            .findFirst().orElseThrow(() -> new AssertionError("未捕获 channel 事件"));

        // 三通道同值
        assertThat(message.getToolUseId()).isEqualTo(requestId);
        assertThat(bridge.toolUseId.get())
            .as("bridge 事件的 toolUseId 必须与 message 同值（不能是 bridgeRequestId）")
            .isEqualTo(requestId)
            .isNotEqualTo(bridge.bridgeRequestId.get());
        assertThat(channelEvent.getToolUseId())
            .as("channel 事件的 toolUseId 必须与 message/bridge 同值（不能是 5 位短 ID）")
            .isEqualTo(requestId)
            .isNotEqualTo(channelEvent.getRequestId());

        // 结算：桌面答复 → permission-resolved 也必须带同一个 toolUseId
        prompter.onResponse(requestId, "allow");
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);

        ArgumentCaptor<Object> resolved = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ws, org.mockito.Mockito.atLeastOnce()).convertAndSend(
            org.mockito.ArgumentMatchers.eq("/topic/sessions/" + SESSION + "/permission-resolved"),
            resolved.capture());
        Object payload = resolved.getAllValues().get(resolved.getAllValues().size() - 1);
        assertThat(payload).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload;
        assertThat(map.get("toolUseId"))
            .as("permission-resolved 必须带 toolUseId（前端据它清全部副本）")
            .isEqualTo(requestId);
        assertThat(map.get("requestId")).isEqualTo(requestId);
    }

    @Test
    @DisplayName("StompChannelPermissionCallbacks 6 参 sendRequest ⇒ 事件原样携带 toolUseId")
    void directSendCarriesToolUseId() {
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        StompChannelPermissionCallbacks channel = new StompChannelPermissionCallbacks(channelWs);

        channel.sendRequest(SESSION, "abcde", "Bash", "运行命令",
            JSON.createObjectNode().put("command", "ls"), "call_relay_direct");

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(channelWs).convertAndSend(anyString(), sent.capture());
        ChannelPermissionRequestEvent event = (ChannelPermissionRequestEvent) sent.getValue();
        assertThat(event.getToolUseId()).isEqualTo("call_relay_direct");
        assertThat(event.getRequestId()).isEqualTo("abcde");
    }

    @Test
    @DisplayName("ChannelPermissionRequestEvent 序列化保留 toolUseId（前端线格式契约）")
    void eventSerializesToolUseId() throws Exception {
        ChannelPermissionRequestEvent e = ChannelPermissionRequestEvent.of(
            SESSION, "abcde", "call_relay_json", "Bash", "运行命令", "{}", null);
        String json = JSON.writeValueAsString(e);
        assertThat(json).contains("\"toolUseId\":\"call_relay_json\"");
    }
}
