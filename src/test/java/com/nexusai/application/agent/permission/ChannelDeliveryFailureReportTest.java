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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [D 未送达上抛] 渠道把权限通知「跳过/失败」时，桌面要能看到「该渠道未激活，通知未送达」。
 *
 * <p><b>WHY</b>：微信 iLink 主动发消息必须带对方来信里的 {@code context_token}；
 * 没先给机器人发过消息的人，策略层返回 {@code SKIPPED / IM_REPLY_TOKEN_MISSING}。
 * 此前这个事实只进后端日志 —— 用户以为「绑好了就能在手机上收到确认」，
 * 结果工具一直卡在等审批。
 *
 * <p>锁死三件事：
 * <ol>
 *   <li>SKIPPED ⇒ 推 {@code /permission-delivery}，带上原因码；</li>
 *   <li>全部送达 ⇒ 不推该事件（不产生噪音）；</li>
 *   <li>多渠道部分失败 ⇒ 只列失败的那个渠道；<b>future 仍然 pending</b>
 *       （未送达 ≠ 拒绝 —— 不能因为手机收不到就自动放行/拒绝）。</li>
 * </ol>
 *
 * <p>⚠️ 变异判据：把 SKIPPED 当成功 ⇒ 用例 1 变红；把「未送达」当拒绝（complete 成 Deny）
 * ⇒ 用例 1 的 `future 仍 pending` 断言变红。
 */
@DisplayName("[D] 渠道通知未送达 · 上抛到桌面")
class ChannelDeliveryFailureReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-delivery-report";
    private static final String AGENT = "a0000000000abc02";
    private static final String DELIVERY_TOPIC = "/topic/sessions/" + SESSION + "/permission-delivery";

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ImChatEntry chat(String id, String instance, String platform, String peer) {
        return new ImChatEntry(id, instance, platform, peer, peer, "p2p", 2,
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

    /** 一个可控环境：真 prompter + 真中继回调 + mock 发送（按 chatId 决定 SENT/SKIPPED）。 */
    private static final class Env {
        final WebSocketPermissionPrompter prompter;
        final StompChannelPermissionCallbacks callbacks;
        final SimpMessagingTemplate ws;
        final List<Object[]> pushes = new CopyOnWriteArrayList<>();
        final AtomicReference<PermissionResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> boom = new AtomicReference<>();
        Thread thread;

        /** @param skippedChatIds 这些 chatId 返回 SKIPPED（IM_REPLY_TOKEN_MISSING），其余 SENT */
        Env(List<ImChatEntry> chats, java.util.Set<String> skippedChatIds) {
            ws = mock(SimpMessagingTemplate.class);
            Mockito.doAnswer(inv -> {
                pushes.add(new Object[]{inv.getArgument(0), inv.getArgument(1)});
                return null;
            }).when(ws).convertAndSend(anyString(), Mockito.any(Object.class));
            prompter = new WebSocketPermissionPrompter(ws, 100);
            callbacks = new StompChannelPermissionCallbacks(mock(SimpMessagingTemplate.class));

            ImChatService chatService = mock(ImChatService.class);
            when(chatService.listBySession(SESSION)).thenReturn(chats);
            ImSendService sendService = mock(ImSendService.class);
            when(sendService.send(anyList(), anyString())).thenAnswer(inv -> {
                List<String> ids = inv.getArgument(0);
                List<ImSendResult> out = new ArrayList<>();
                for (String id : ids) {
                    out.add(skippedChatIds.contains(id)
                        ? ImSendResult.skipped("IM_REPLY_TOKEN_MISSING",
                            "需要对方先给机器人发一条消息（当前会话没有可用的回复令牌）")
                        : new ImSendResult(ImSendResult.Status.SENT, "m", null, null, List.of()));
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
            when(sessionMode.hasAnyChannel(anyString())).thenReturn(true);
            ReflectionTestUtils.setField(prompter, "sessionModeService", sessionMode);
            ReflectionTestUtils.setField(prompter, "imNotifier", notifier);
            ReflectionTestUtils.setField(prompter, "channelCallbacks", callbacks);
        }

        void start(String requestId) {
            thread = new Thread(() -> {
                try {
                    ObjectNode input = JSON.createObjectNode();
                    input.put("command", "rm -rf /tmp/x");
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

        /** 是否已经推过 permission-delivery（非阻塞，用于「不该推」的断言）。 */
        boolean hasDeliveryPush() {
            for (Object[] p : pushes) {
                if (DELIVERY_TOPIC.equals(p[0])) {
                    return true;
                }
            }
            return false;
        }

        /** 取第一条推给 permission-delivery 的载荷；没有 ⇒ null。 */
        @SuppressWarnings("unchecked")
        Map<String, Object> awaitDeliveryPayload() throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (Object[] p : pushes) {
                    if (DELIVERY_TOPIC.equals(p[0])) {
                        return (Map<String, Object>) p[1];
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }
    }

    @Test
    @DisplayName("微信未激活（SKIPPED）⇒ 推未送达事件，含原因码；future 仍 pending（未送达≠拒绝）")
    void skippedChannelIsReportedAndFutureStaysPending() throws Exception {
        Env env = new Env(List.of(chat("chat-wx", "i-wx", "wechat", "wxid-zhangsan")),
            java.util.Set.of("chat-wx"));
        env.start("req-delivery-1");

        Map<String, Object> payload = env.awaitDeliveryPayload();
        assertThat(payload).as("SKIPPED 必须上抛（不能只进日志）").isNotNull();
        assertThat(payload.get("requestId")).isEqualTo("req-delivery-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) payload.get("failures");
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).get("channel")).isEqualTo("wechat");
        assertThat(failures.get(0).get("peerName")).isEqualTo("wxid-zhangsan");
        assertThat(failures.get(0).get("reasonCode")).isEqualTo("IM_REPLY_TOKEN_MISSING");

        // ⛔ 未送达 ≠ 拒绝：future 必须还挂着等桌面
        assertThat(env.result.get()).as("未送达不得收敛 future（既不放行也不拒绝）").isNull();
        assertThat(env.boom.get()).isNull();

        // 桌面仍然可以正常批准（证明决策链没被未送达打断）
        env.prompter.onResponse("req-delivery-1", "allow");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("全部送达 ⇒ 不推未送达事件（无噪音）")
    void allSentMeansNoDeliveryEvent() throws Exception {
        Env env = new Env(List.of(chat("chat-fs", "i-fs", "feishu", "ou_1")), java.util.Set.of());
        env.start("req-delivery-2");

        // 等出站真正发生：用桌面回执收口，再检查有没有 delivery 推送
        Thread.sleep(400);
        assertThat(env.hasDeliveryPush()).as("全部送达不得推未送达事件").isFalse();
        env.prompter.onResponse("req-delivery-2", "allow");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Allow.class);
        assertThat(env.hasDeliveryPush()).as("全部送达不得推未送达事件").isFalse();
    }

    @Test
    @DisplayName("多渠道部分失败 ⇒ 只标失败的那个渠道；成功的渠道不进 failures")
    void onlyFailedChannelIsReported() throws Exception {
        Env env = new Env(List.of(
            chat("chat-wx", "i-wx", "wechat", "wxid-zhangsan"),
            chat("chat-fs", "i-fs", "feishu", "ou_1")), java.util.Set.of("chat-wx"));
        env.start("req-delivery-3");

        Map<String, Object> payload = env.awaitDeliveryPayload();
        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) payload.get("failures");
        assertThat(failures).as("只列失败渠道（飞书已送达 ⇒ 不标）").hasSize(1);
        assertThat(failures.get(0).get("channel")).isEqualTo("wechat");
        assertThat(env.result.get()).isNull();

        env.prompter.onResponse("req-delivery-3", "deny");
        env.thread.join(3_000);
        assertThat(env.result.get()).isInstanceOf(PermissionResult.Deny.class);
    }
}
