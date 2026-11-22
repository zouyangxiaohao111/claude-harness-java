package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P1 端到端] 权限确认出站到本会话绑定的原生 IM（方案 a：全部发 + 同一编号 + 先答算谁）。
 *
 * <p>用手写 fake 渠道适配器（mock 发送服务），不真发 IM。验收点：
 * <ol>
 *   <li>绑 3 个渠道 ⇒ 3 条出站、编号一致、脱敏；</li>
 *   <li>其中一个渠道抛异常 ⇒ 其余照发、future 仍 pending；</li>
 *   <li>渠道 A 先答 ⇒ 落地；渠道 B 后答 ⇒ 明确 false（已处理），结果不变；</li>
 *   <li>来源连接不在本请求登记集合里 ⇒ 丢弃（伪造）；</li>
 *   <li>无绑定 ⇒ 不发；桌面先答 ⇒ 渠道回执 false。</li>
 * </ol>
 */
@DisplayName("[P1] 渠道权限中继 · 多绑定出站 + 回执闭环")
class ChannelImMultiRelayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-multi-relay";
    private static final String AGENT = "a0000000000abc01";
    private static final Pattern SHORT_ID = Pattern.compile("yes ([a-z]{3,10})");

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ImChatEntry chat(String id, String instance, String peer) {
        return new ImChatEntry(id, instance, "feishu", peer, peer, "p2p", 2,
            null, null, SESSION, "manual", null, null, null, null);
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

    /** 一个可控的测试环境：prompter + 真 Stomp 回调 + fake 发送。 */
    private static final class Env {
        final WebSocketPermissionPrompter prompter;
        final StompChannelPermissionCallbacks callbacks;
        final ImSendService sendService;
        final org.springframework.messaging.simp.SimpMessagingTemplate ws;
        final org.springframework.messaging.simp.SimpMessagingTemplate channelWs;
        final List<String> sentTexts = new CopyOnWriteArrayList<>();
        final AtomicReference<PermissionResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> boom = new AtomicReference<>();
        Thread thread;

        Env(List<ImChatEntry> chats, boolean failSecondChat, boolean bound) throws Exception {
            ws = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            prompter = new WebSocketPermissionPrompter(ws, 100);
            channelWs = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            callbacks = new StompChannelPermissionCallbacks(channelWs);

            ImChatService chatService = mock(ImChatService.class);
            when(chatService.listBySession(SESSION)).thenReturn(chats);
            sendService = mock(ImSendService.class);
            when(sendService.send(anyList(), anyString())).thenAnswer(inv -> {
                List<String> ids = inv.getArgument(0);
                String text = inv.getArgument(1);
                sentTexts.add(text);
                if (failSecondChat && ids.contains("chat-2")) {
                    throw new RuntimeException("fake adapter down: chat-2");
                }
                List<ImSendResult> out = new ArrayList<>();
                for (String ignored : ids) {
                    out.add(new ImSendResult(ImSendResult.Status.SENT, "m", null, null, List.of()));
                }
                return out;
            });

            ChannelPermissionImNotifier notifier = new ChannelPermissionImNotifier();
            ReflectionTestUtils.setField(notifier, "imChatService", chatService);
            ReflectionTestUtils.setField(notifier, "imSendService", sendService);

            SettingsService settings = mock(SettingsService.class);
            when(settings.readDbChannelsEnabled()).thenReturn(true);
            when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(true);
            ReflectionTestUtils.setField(prompter, "settingsService", settings);
            SessionModeService sessionMode = mock(SessionModeService.class);
            when(sessionMode.hasAnyChannel(anyString())).thenReturn(bound);
            ReflectionTestUtils.setField(prompter, "sessionModeService", sessionMode);
            ReflectionTestUtils.setField(prompter, "imNotifier", notifier);
            ReflectionTestUtils.setField(prompter, "channelCallbacks", callbacks);
        }

        void start(String requestId) {
            thread = new Thread(() -> {
                try {
                    ObjectNode input = JSON.createObjectNode();
                    input.put("command", "cat ~/.config/secret");
                    input.put("api_key", "sk-live-abcdefghijk12345");
                    result.set(prompter.prompt(new StubTool(), input,
                        new PermissionDecisionReason.Other("test"), ctx(), requestId,
                        new PermissionPromptDetails("运行命令", List.of(), null)));
                } catch (Throwable th) {
                    boom.set(th);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String awaitShortId() throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (String t : sentTexts) {
                    Matcher m = SHORT_ID.matcher(t);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("未捕获出站短编号: " + sentTexts);
        }
    }

    @Test
    @DisplayName("3 个绑定渠道 ⇒ 3 条出站、编号一致、脱敏；未来仍 pending")
    void fansOutToAllBoundChatsWithSingleId() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1"), chat("chat-2", "i2", "p2"),
            chat("chat-3", "i3", "p3")), false, true);
        env.start("req-multi-1");
        String id = env.awaitShortId();

        assertThat(env.sentTexts).hasSize(3);
        assertThat(env.sentTexts).allMatch(t -> t.contains("yes " + id));
        assertThat(env.sentTexts.get(0))
            .as("载荷含工具名，且假 token 必须被脱敏")
            .contains("[权限确认] Bash")
            .doesNotContain("sk-live-abcdefghijk12345");
        assertThat(env.result.get()).as("无人回答 ⇒ 仍 pending（不加兜底）").isNull();

        // 渠道 A 先答（大写 + 合法来源连接）
        assertThat(env.callbacks.resolveFrom(id, "ALLOW", "feishu", "i1", "p1")).isTrue();
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);

        // 渠道 B 后答（迟到）⇒ 明确 false，结果不变
        assertThat(env.callbacks.resolveFrom(id, "deny", "feishu", "i2", "p2")).isFalse();
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("单渠道发送抛异常 ⇒ 其余照发、future 仍 pending")
    void oneChatFailureDoesNotAffectOthers() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1"), chat("chat-2", "i2", "p2"),
            chat("chat-3", "i3", "p3")), true, true);
        env.start("req-multi-2");
        String id = env.awaitShortId();

        assertThat(env.sentTexts).as("chat-2 失败不影响另外两条").hasSize(3);
        assertThat(env.result.get()).isNull();
        assertThat(env.boom.get()).isNull();

        assertThat(env.callbacks.resolveFrom(id, "allow", "feishu", "i3", "p3")).isTrue();
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("伪造来源连接 ⇒ 回执被丢弃（pending 保留）")
    void forgedOriginIsDropped() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1")), false, true);
        env.start("req-multi-3");
        String id = env.awaitShortId();

        assertThat(env.callbacks.resolveFrom(id, "allow", "feishu", "i9", "p9")).isFalse();
        assertThat(env.result.get()).isNull();

        assertThat(env.callbacks.resolveFrom(id, "allow", "feishu", "i1", "p1")).isTrue();
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("同一个 requestId 重复通知同一渠道 ⇒ 只发一次（不刷两张卡）")
    void duplicateNotifyIsDeduped() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1")), false, true);
        env.start("req-multi-4");
        String id = env.awaitShortId();

        // 直接再触发一次同 requestId 的通知（模拟重试/重复调用）
        ChannelPermissionImNotifier notifier =
            (ChannelPermissionImNotifier) ReflectionTestUtils.getField(env.prompter, "imNotifier");
        notifier.notifyBoundChats(SESSION, id, "Bash", "运行命令", JSON.createObjectNode());

        assertThat(env.sentTexts).as("同渠道同 requestId 只发一次").hasSize(1);
        env.callbacks.resolveFrom(id, "allow", "feishu", "i1", "p1");
        env.thread.join(3_000);
    }

    @Test
    @DisplayName("无绑定渠道 ⇒ 一条都不发（桌面路径照旧）")
    void noBindingMeansNoSend() throws Exception {
        Env env = new Env(List.of(), false, false);
        env.start("req-multi-5");
        Thread.sleep(400);

        verify(env.sendService, never()).send(anyList(), anyString());
        assertThat(env.result.get()).isNull();
        env.prompter.onResponse("req-multi-5", "allow");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("桌面先答 ⇒ 渠道回执明确 false（已处理），且不二次生效")
    void desktopFirstThenChannelReplyIsRejected() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1")), false, true);
        env.start("req-multi-6");
        String id = env.awaitShortId();

        env.prompter.onResponse("req-multi-6", "allow");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);

        assertThat(env.callbacks.resolveFrom(id, "deny", "feishu", "i1", "p1"))
            .as("桌面已裁决 ⇒ 渠道回执必须返回 false（已处理/已过期）")
            .isFalse();
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("[P0] 两门开 + 只绑 IM（生产存在 McpToolPool 但无合格 server）⇒ 真实出站发生")
    void imBindingAloneQualifiesEvenWithProductionPool() throws Exception {
        Env env = new Env(List.of(chat("chat-1", "i1", "p1")), false, true);
        // 模拟生产：McpToolPool bean 存在，但没有任何合格 channel server
        com.nexusai.application.agent.mcp.McpToolPool pool =
            mock(com.nexusai.application.agent.mcp.McpToolPool.class);
        when(pool.activeServers()).thenReturn(java.util.Set.of());
        ReflectionTestUtils.setField(env.prompter, "channelRelayServerSource", pool);

        env.start("req-im-only");
        String id = env.awaitShortId();

        assertThat(env.sentTexts).as("只绑 IM 也必须真的推送（此前被 MCP server 资格闸门挡死）").hasSize(1);

        // STOMP 路径同样必须脱敏（假 token 不得出现在任何出站载荷）
        org.mockito.ArgumentCaptor<Object> sent = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(env.channelWs, Mockito.atLeastOnce()).convertAndSend(
            org.mockito.ArgumentMatchers.anyString(), sent.capture());
        boolean anyPreviewLeaks = sent.getAllValues().stream()
            .filter(v -> v instanceof com.nexusai.eventbus.ws.ChannelPermissionRequestEvent)
            .map(v -> {
                try {
                    return JSON.writeValueAsString(v);
                } catch (Exception e) {
                    return String.valueOf(v);
                }
            })
            .anyMatch(json -> json.contains("sk-live-abcdefghijk12345"));
        assertThat(anyPreviewLeaks).as("STOMP inputPreview 必须与 IM 文本共用脱敏").isFalse();

        env.callbacks.resolveFrom(id, "allow", "feishu", "i1", "p1");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }
}
