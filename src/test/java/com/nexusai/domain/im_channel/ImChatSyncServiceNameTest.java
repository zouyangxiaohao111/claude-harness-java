package com.nexusai.domain.im_channel;

import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书私聊补名（<b>真名优先</b>，2026-11-23 用户裁决）。
 *
 * <p>钉住三件事：① 真名写回；② 人工改名（{@code nick_name} 非空）不被覆盖；
 * ③ 无通讯录权限时整批不逐行解析。
 */
@DisplayName("飞书同步 · 私聊补名(真名优先)")
class ImChatSyncServiceNameTest {

    private static final String INSTANCE = "imc-1";

    private static ImChatEntry chat(String peerId, String peerName, String nickName) {
        return new ImChatEntry("c-" + peerId, INSTANCE, "feishu", peerId, peerName, "p2p",
                null, null, null, null, null, null, "t", "t", nickName);
    }

    private static ImChatSyncService service(ImChatService chatService,
                                             FeishuChatNameResolver resolver) {
        ImChatSyncService svc = new ImChatSyncService();
        ReflectionTestUtils.setField(svc, "chatService", chatService);
        ReflectionTestUtils.setField(svc, "nameResolver", resolver);
        return svc;
    }

    @Test
    @DisplayName("有 open_id + 权限 ⇒ 写回真名")
    void resolvesRealName() {
        ImChatService chatService = mock(ImChatService.class);
        FeishuChatNameResolver resolver = mock(FeishuChatNameResolver.class);
        when(chatService.listByInstance(INSTANCE)).thenReturn(List.of(chat("ou_abc123", null, null)));
        when(resolver.probeContacts(anyString(), any())).thenReturn(FeishuChatNameResolver.ProbeStatus.GRANTED);
        when(resolver.resolveP2pName(anyString(), eq("ou_abc123"), any(), any()))
                .thenReturn(new FeishuChatNameResolver.Resolved("张三", FeishuChatNameResolver.Reason.OK));

        int n = service(chatService, resolver).resolveFeishuP2pNames(INSTANCE, "tok");

        assertThat(n).isEqualTo(1);
        verify(chatService).upsert(eq(INSTANCE), eq("feishu"), eq("ou_abc123"), eq("张三"),
                eq("p2p"), any(), any(), any(), eq(ImChatService.SOURCE_LISTED));
    }

    @Test
    @DisplayName("人工改名（nick_name 非空）⇒ 不解析、不覆盖")
    void humanNickIsNotOverwritten() {
        ImChatService chatService = mock(ImChatService.class);
        FeishuChatNameResolver resolver = mock(FeishuChatNameResolver.class);
        when(chatService.listByInstance(INSTANCE))
                .thenReturn(List.of(chat("oc_x", "飞书私聊 · 尾号 0000", "我改的名字")));
        when(resolver.probeContacts(anyString(), any())).thenReturn(FeishuChatNameResolver.ProbeStatus.GRANTED);

        int n = service(chatService, resolver).resolveFeishuP2pNames(INSTANCE, "tok");

        assertThat(n).isZero();
        verify(resolver, never()).resolveP2pName(anyString(), anyString(), any(), any());
        verify(chatService, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("无通讯录权限 + ou_ 行 ⇒ 不写库（不逐行查）")
    void deniedOuRowsDoNotWrite() {
        ImChatService chatService = mock(ImChatService.class);
        FeishuChatNameResolver resolver = mock(FeishuChatNameResolver.class);
        when(chatService.listByInstance(INSTANCE))
                .thenReturn(List.of(chat("ou_a", "飞书私聊 · 尾号 000a", null),
                        chat("ou_b", "飞书私聊 · 尾号 000b", null)));
        when(resolver.probeContacts(anyString(), any())).thenReturn(FeishuChatNameResolver.ProbeStatus.DENIED);
        when(resolver.resolveP2pName(anyString(), anyString(), any(), any()))
                .thenReturn(new FeishuChatNameResolver.Resolved(
                        null, FeishuChatNameResolver.Reason.NO_PERMISSION));

        int n = service(chatService, resolver).resolveFeishuP2pNames(INSTANCE, "tok");

        assertThat(n).isZero();
        verify(chatService, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("oc_ 私聊（与机器人）= 即使无通讯录权限，也写机器人名")
    void ocRowGetsBotNameWithoutPermission() {
        ImChatService chatService = mock(ImChatService.class);
        FeishuChatNameResolver resolver = mock(FeishuChatNameResolver.class);
        when(chatService.listByInstance(INSTANCE))
                .thenReturn(List.of(chat("oc_6ac65141", "飞书私聊 · 尾号 4141", null)));
        when(resolver.probeContacts(anyString(), any())).thenReturn(FeishuChatNameResolver.ProbeStatus.DENIED);
        when(resolver.fetchBotName(anyString())).thenReturn("智能bot");
        when(resolver.resolveP2pName(anyString(), eq("oc_6ac65141"), any(), eq("智能bot")))
                .thenReturn(new FeishuChatNameResolver.Resolved("智能bot", FeishuChatNameResolver.Reason.OK));

        int n = service(chatService, resolver).resolveFeishuP2pNames(INSTANCE, "tok");

        assertThat(n).isEqualTo(1);
        verify(chatService).upsert(eq(INSTANCE), eq("feishu"), eq("oc_6ac65141"), eq("智能bot"),
                eq("p2p"), any(), any(), any(), eq(ImChatService.SOURCE_LISTED));
    }

    @Test
    @DisplayName("已是真名 ⇒ 不重复解析")
    void skipsAlreadyNamed() {
        ImChatService chatService = mock(ImChatService.class);
        FeishuChatNameResolver resolver = mock(FeishuChatNameResolver.class);
        when(chatService.listByInstance(INSTANCE)).thenReturn(List.of(chat("oc_x", "张三", null)));
        when(resolver.probeContacts(anyString(), any())).thenReturn(FeishuChatNameResolver.ProbeStatus.GRANTED);

        int n = service(chatService, resolver).resolveFeishuP2pNames(INSTANCE, "tok");

        assertThat(n).isZero();
        verify(resolver, never()).resolveP2pName(anyString(), anyString(), any(), any());
        verify(chatService, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }
}
