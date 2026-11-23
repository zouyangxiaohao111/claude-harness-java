package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImInstanceIdentityService;
import com.nexusai.domain.im_channel.inbound.ImInboundManager;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [快速绑定] 渠道选择器左栏：把「机器人默认会话」接到当前会话。
 *
 * <p>WHY：列表里一个渠道下有多个聊天（飞书同一私聊还会有 oc_/ou_ 两行），
 * 用户只想"绑定这个机器人"，不该被迫在右栏猜哪一行是机器人会话。
 */
@DisplayName("[快速绑定] 默认会话的选取与落库")
class ImChatQuickBindTest {

    private static ImChatEntry chat(String id, String peerId, String kind, String lastAt) {
        return new ImChatEntry(id, "imc-1", "feishu", peerId, null, kind, null,
                null, lastAt, null, null, null, "t", "t");
    }

    private static ImInstanceEntry inst(String platform) {
        return new ImInstanceEntry("imc-1", platform, "渠道", "connected", null, null, null, "t", "t");
    }

    @SuppressWarnings("unchecked")
    private static ImChatController controller(ImChatService chatService,
                                               ImChannelService channelService,
                                               ImInstanceIdentityService identityService,
                                               com.nexusai.domain.session.SessionService sessionService) {
        ImChatController c = new ImChatController();
        ReflectionTestUtils.setField(c, "chatService", chatService);
        ReflectionTestUtils.setField(c, "channelService", channelService);
        ReflectionTestUtils.setField(c, "identityService", identityService);
        ReflectionTestUtils.setField(c, "sessionService", sessionService);
        ReflectionTestUtils.setField(c, "sessionModeService",
                mock(com.nexusai.domain.session.SessionModeService.class));
        ReflectionTestUtils.setField(c, "inboundManager", mock(ImInboundManager.class));
        return c;
    }

    @Test
    @DisplayName("飞书同一私聊 oc_/ou_ 两行 ⇒ 优先绑 oc_（入站/可读历史那行）")
    void prefersOcRow() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        ImInstanceIdentityService identityService = mock(ImInstanceIdentityService.class);
        ImChatEntry oc = chat("c-oc", "oc_6ac6", "p2p", "2026-11-22 10:00:00");
        ImChatEntry ou = chat("c-ou", "ou_9be7", "p2p", "2026-11-22 12:00:00");
        when(chatService.listByInstanceVisible("imc-1")).thenReturn(List.of(ou, oc));
        when(chatService.bind(eq("c-oc"), eq("sess-1"), eq("manual")))
                .thenReturn(new ImChatEntry("c-oc", "imc-1", "feishu", "oc_6ac6", null, "p2p",
                        null, null, null, "sess-1", "manual", "t", "t", "t"));

        Map<String, Object> out = controller(chatService, channelService, identityService,
                        mock(com.nexusai.domain.session.SessionService.class))
                .bindDefault(Map.of("instanceId", "imc-1", "sessionId", "sess-1"));

        assertThat(out.get("chatId")).isEqualTo("c-oc");
        verify(chatService).bind("c-oc", "sess-1", "manual");
    }

    @Test
    @DisplayName("企微无任何会话 + whoami 有授权人 ⇒ 落授权人私聊行再绑（官方允许当 chat_id）")
    void wecomUpsertsAuthorizerChat() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        ImInstanceIdentityService identityService = mock(ImInstanceIdentityService.class);
        when(chatService.listByInstanceVisible("imc-1")).thenReturn(List.of());
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("wecom")));
        when(identityService.resolve("imc-1")).thenReturn(new ImInstanceIdentityService.Identity(
                true, "wecom", null, "bot-1", "zhangsan", null, "ok"));
        ImChatEntry created = new ImChatEntry("c-new", "imc-1", "wecom", "zhangsan", "机器人授权人",
                "p2p", null, null, null, null, null, null, "t", "t");
        when(chatService.upsert(eq("imc-1"), eq("wecom"), eq("zhangsan"), any(), eq("p2p"),
                any(), any(), any(), anyString())).thenReturn(created);
        when(chatService.bind(eq("c-new"), eq("sess-1"), eq("manual")))
                .thenReturn(new ImChatEntry("c-new", "imc-1", "wecom", "zhangsan", "机器人授权人",
                        "p2p", null, null, null, "sess-1", "manual", "t", "t", "t"));

        Map<String, Object> out = controller(chatService, channelService, identityService,
                        mock(com.nexusai.domain.session.SessionService.class))
                .bindDefault(Map.of("instanceId", "imc-1", "sessionId", "sess-1"));

        assertThat(out.get("chatId")).isEqualTo("c-new");
        verify(chatService).bind("c-new", "sess-1", "manual");
    }

    @Test
    @DisplayName("一个会话都没有且拿不到授权人 ⇒ 明确报错，不伪造绑定")
    void noChatsFailsLoud() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        ImInstanceIdentityService identityService = mock(ImInstanceIdentityService.class);
        when(chatService.listByInstanceVisible("imc-1")).thenReturn(List.of());
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("feishu")));

        ImChatController c = controller(chatService, channelService, identityService,
                        mock(com.nexusai.domain.session.SessionService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        c.bindDefault(Map.of("instanceId", "imc-1", "sessionId", "sess-1")))
                .hasMessageContaining("先私聊机器人发一句话");
        verify(chatService, never()).bind(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("预览：默认会话已绑在别的任务 ⇒ 回 boundSessionId + 任务标题（前端据此确认切换）")
    void previewReportsCurrentBinding() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        ImInstanceIdentityService identityService = mock(ImInstanceIdentityService.class);
        com.nexusai.domain.session.SessionService sessionService =
                mock(com.nexusai.domain.session.SessionService.class);
        ImChatEntry row = new ImChatEntry("c-oc", "imc-1", "feishu", "oc_1", null, "p2p",
                null, null, null, "sess-old", "manual", "t", "t", "t");
        when(chatService.listByInstanceVisible("imc-1")).thenReturn(List.of(row));
        when(sessionService.getById("sess-old")).thenReturn(
                new com.nexusai.model.session.dto.SessionDto("sess-old", null, null, "旧任务",
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));

        Map<String, Object> out = controller(chatService, channelService, identityService, sessionService)
                .previewBindDefault(Map.of("instanceId", "imc-1"));

        assertThat(out.get("found")).isEqualTo(true);
        assertThat(out.get("chatId")).isEqualTo("c-oc");
        assertThat(out.get("boundSessionId")).isEqualTo("sess-old");
        assertThat(out.get("boundSessionTitle")).isEqualTo("旧任务");
    }

    @Test
    @DisplayName("预览：一个会话都没有 ⇒ found=false + 可执行提示（不写库）")
    void previewWhenNoChats() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        ImInstanceIdentityService identityService = mock(ImInstanceIdentityService.class);
        when(chatService.listByInstanceVisible("imc-1")).thenReturn(List.of());

        Map<String, Object> out = controller(chatService, channelService, identityService,
                mock(com.nexusai.domain.session.SessionService.class))
                .previewBindDefault(Map.of("instanceId", "imc-1"));

        assertThat(out.get("found")).isEqualTo(false);
        assertThat(String.valueOf(out.get("message"))).contains("先私聊机器人发一句话");
    }
}
