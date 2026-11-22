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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P5] 渠道裁决的终态契约核对（逐条锁死）。
 *
 * <ol>
 *   <li><b>allow 不写永久规则</b>：渠道 yes 只是本次放行，不得落 any allow rule
 *       （PermissionUpdateApplier / Persister 零调用）；</li>
 *   <li><b>deny 文案</b> = {@code Denied via channel <server>}（语义可归因到具体渠道）；</li>
 *   <li><b>不向渠道回执</b>：裁决后不再向 channel STOMP 出站；</li>
 *   <li><b>权限结果不进模型上下文</b>：裁决过程不追加任何消息（MessageService 零调用），
 *       模型只看到标准 tool_result（deny 时即 deny 文案）。</li>
 * </ol>
 */
@DisplayName("[P5] 渠道裁决终态契约")
class ChannelTerminalContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-p5-terminal";
    private static final String AGENT = "a0000000000cc001";
    private static final String SHORT_ID = "abcde";

    private static final class StubChannel implements ChannelPermissionCallbacks {
        final Map<String, Consumer<ChannelResponse>> pending = new ConcurrentHashMap<>();
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public String shortRequestId(String toolUseId) { return SHORT_ID; }
        @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
            pending.put(requestId.toLowerCase(), handler);
            return () -> pending.remove(requestId.toLowerCase());
        }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                          String description, JsonNode displayInput) {
            sent.add(toolName);
        }
        @Override public boolean resolve(String requestId, String behavior, String fromServer) {
            Consumer<ChannelResponse> h = pending.remove(requestId.toLowerCase());
            if (h == null) return false;
            h.accept(new ChannelResponse(behavior, fromServer));
            return true;
        }
    }

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
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

    /** 组装 prompter：channel 回调 + 资格门全过 + 更新管线/消息服务 mock（用于断言零调用）。 */
    private static WebSocketPermissionPrompter prompter(StubChannel cb,
                                                        PermissionUpdateApplier applier,
                                                        PermissionUpdatePersister persister,
                                                        com.nexusai.domain.session.MessageService messageService) {
        WebSocketPermissionPrompter p = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 100);
        SettingsService settings = mock(SettingsService.class);
        when(settings.readDbChannelsEnabled()).thenReturn(true);
        when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(p, "settingsService", settings);
        SessionModeService sessionMode = mock(SessionModeService.class);
        when(sessionMode.hasAnyChannel(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(p, "sessionModeService", sessionMode);
        ReflectionTestUtils.setField(p, "channelCallbacks", cb);
        if (applier != null) ReflectionTestUtils.setField(p, "permissionUpdateApplier", applier);
        if (persister != null) ReflectionTestUtils.setField(p, "permissionUpdatePersister", persister);
        if (messageService != null) ReflectionTestUtils.setField(p, "messageService", messageService);
        return p;
    }

    private static Thread start(WebSocketPermissionPrompter p, String requestId,
                                JsonNode input, AtomicReference<PermissionResult> out) {
        Thread t = new Thread(() -> out.set(p.prompt(new StubTool(), input,
            new PermissionDecisionReason.Other("test"), ctx(), requestId,
            new PermissionPromptDetails("运行命令", List.of(), null, null, false))));
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void awaitSent(StubChannel cb) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && cb.sent.isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(cb.sent).as("前置：channel 已出站").isNotEmpty();
    }

    @Test
    @DisplayName("allow：本次放行，不写永久规则、不追加消息")
    void allowDoesNotPersistRuleNorWriteMessages() throws Exception {
        StubChannel cb = new StubChannel();
        PermissionUpdateApplier applier = mock(PermissionUpdateApplier.class);
        PermissionUpdatePersister persister = mock(PermissionUpdatePersister.class);
        com.nexusai.domain.session.MessageService messages =
            mock(com.nexusai.domain.session.MessageService.class);
        WebSocketPermissionPrompter p = prompter(cb, applier, persister, messages);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-p5-allow";
        JsonNode input = JSON.createObjectNode().put("command", "ls");
        Thread t = start(p, requestId, input, out);
        awaitSent(cb);
        assertThat(cb.resolve(SHORT_ID, "allow", "feishu")).isTrue();
        t.join(3_000);

        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) out.get();
        assertThat(allow.updatedInput()).isSameAs(input);
        assertThat(allow.toolUseID()).isEqualTo(requestId);
        assertThat(allow.userModified()).isFalse();
        assertThat(allow.acceptFeedback()).isNull();
        assertThat(allow.contentBlocks()).isEmpty();
        assertThat(allow.reason()).isInstanceOf(PermissionDecisionReason.Other.class);
        assertThat(((PermissionDecisionReason.Other) allow.reason()).reason()).isEqualTo("channel_allowed");

        org.mockito.Mockito.verifyNoInteractions(applier);
        org.mockito.Mockito.verifyNoInteractions(persister);
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("deny 文案 = Denied via channel <server>，归因 channel_denied；不追加消息")
    void denyMessageIsAttributableToChannel() throws Exception {
        StubChannel cb = new StubChannel();
        com.nexusai.domain.session.MessageService messages =
            mock(com.nexusai.domain.session.MessageService.class);
        WebSocketPermissionPrompter p = prompter(cb, null, null, messages);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-p5-deny";
        Thread t = start(p, requestId, JSON.createObjectNode(), out);
        awaitSent(cb);
        assertThat(cb.resolve(SHORT_ID, "deny", "plugin:telegram:tg")).isTrue();
        t.join(3_000);

        assertThat(out.get()).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) out.get();
        assertThat(deny.message()).isEqualTo("Denied via channel plugin:telegram:tg");
        assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.Other.class);
        assertThat(((PermissionDecisionReason.Other) deny.reason()).reason()).isEqualTo("channel_denied");
        assertThat(deny.toolUseID()).isEqualTo(requestId);
        org.mockito.Mockito.verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("不向渠道回执：裁决后 channel STOMP 无新增出站")
    void decisionDoesNotSendBackToChannel() {
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        StompChannelPermissionCallbacks real = new StompChannelPermissionCallbacks(channelWs);
        String id = real.shortRequestId(SESSION, "tool-reply-check");
        real.sendRequest(SESSION, id, "Bash", "运行命令", JSON.createObjectNode(), "tool-reply-check");
        real.onResponse(id, r -> { });

        int before = org.mockito.Mockito.mockingDetails(channelWs).getInvocations().size();
        assertThat(real.resolve(id, "deny", "feishu")).isTrue();
        assertThat(org.mockito.Mockito.mockingDetails(channelWs).getInvocations().size())
            .as("⛔ 不向渠道回执").isEqualTo(before);
    }
}
