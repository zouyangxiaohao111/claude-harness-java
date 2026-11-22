package com.nexusai.domain.im_channel.inbound;

import com.nexusai.application.agent.permission.StompChannelPermissionCallbacks;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.im_channel.ImChatService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站分流（{@link ImInboundRouter}）。
 *
 * <h2>WHY 这些用例</h2>
 * 分流只有三条路，但**走错一条后果都很重**：
 * <ul>
 *   <li>权限回复漏进 agent ⇒ agent 把 {@code yes abcde} 当成一句聊天回「好的我继续」，
 *       而真正的决策队列还在原地等（CC 注释：{@code intercepted BEFORE enqueue}）；</li>
 *   <li>群里的 {@code yes} 被当权限回复 ⇒ **群里任何人都能替你批准**执行命令（安全事故）；</li>
 *   <li>普通聊天没入队 ⇒ 用户发了消息，AI 不知道。</li>
 * </ul>
 */
class ImInboundRouterTest {

    private final StompChannelPermissionCallbacks callbacks = mock(StompChannelPermissionCallbacks.class);
    private final NotificationQueue queue = mock(NotificationQueue.class);
    private final ImChatService chatService = mock(ImChatService.class);
    private final com.nexusai.domain.im_channel.ImSendService imSendService =
            mock(com.nexusai.domain.im_channel.ImSendService.class);

    private ImInboundRouter router() {
        // upsert 返回一条有 id 的记录；绑定关系由各用例单独 stub
        when(chatService.upsert(anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), anyString()))
                .thenReturn(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "wecom", "peer-1", "测试群", "p2p", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t"));
        ImInboundRouter r = new ImInboundRouter();
        ReflectionTestUtils.setField(r, "chatService", chatService);
        ReflectionTestUtils.setField(r, "notificationQueue", queue);
        ReflectionTestUtils.setField(r, "channelPermissionCallbacks", callbacks);
        ReflectionTestUtils.setField(r, "imSendService", imSendService);
        return r;
    }

    private static InboundMessage msg(String text, boolean group) {
        return new InboundMessage("inst-1", "wecom", "peer-1", "测试群", "sender-1", "张三",
                group ? "group" : "p2p", "msg-1", text, List.of(),
                System.currentTimeMillis(), null);
    }

    // ───────────────────────── 权限回复 ─────────────────────────

    @Test
    @DisplayName("私聊回 yes <id> ⇒ 参与竞速，⛔ 不进 agent")
    void privateYesResolvesPermission() {
        when(callbacks.resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        router().route(msg("yes abcde", false));

        verify(callbacks).resolveFrom(eq("abcde"), eq("allow"), eq("wecom"), eq("inst-1"), eq("peer-1"));
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("私聊回 no <id> ⇒ deny")
    void privateNoDenies() {
        when(callbacks.resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        router().route(msg("no xyz12", false));

        verify(callbacks).resolveFrom(eq("xyz12"), eq("deny"), eq("wecom"), eq("inst-1"), eq("peer-1"));
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("🔴 群里的 yes ⇒ **不**当权限回复，正常进 agent（否则群里谁都能替你授权）")
    void groupYesIsNotPermissionReply() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "wecom", "peer-1", "测试群", "group", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t")));
        router().route(msg("yes abcde", true));

        verify(callbacks, never()).resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString());
        // 群里的这条会被当成普通聊天入队
        verify(queue).enqueue(any());
    }

    @Test
    @DisplayName("resolve 未命中（id 不存在/已被抢先）⇒ 仍吞掉，不进 agent")
    void resolveMissStillSwallowed() {
        when(callbacks.resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        router().route(msg("yes zzzzz", false));

        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("通道回调未接线 ⇒ 仍识别为权限回复并吞掉（只是不生效）")
    void permissionReplyWithoutCallbacks() {
        ImInboundRouter r = new ImInboundRouter();
        ReflectionTestUtils.setField(r, "chatService", chatService);
        ReflectionTestUtils.setField(r, "notificationQueue", queue);
        ReflectionTestUtils.setField(r, "channelPermissionCallbacks", null);

        r.route(msg("yes abcde", false));

        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("大小写不敏感：YES / Allow 都认")
    void caseInsensitive() {
        when(callbacks.resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        ImInboundRouter r = router();

        r.route(msg("YES abcde", false));
        // ⚠️ 必须换 messageId：同一 id 的第二条会被入站去重挡掉（这正是我们希望的行为，
        //    但本条测的是「大小写不敏感」，得让两条是**不同消息**）。
        r.route(withId(msg("Allow fghij", false), "m-2"));

        verify(callbacks).resolveFrom(eq("abcde"), eq("allow"), eq("wecom"), eq("inst-1"), eq("peer-1"));
        verify(callbacks).resolveFrom(eq("fghij"), eq("allow"), eq("wecom"), eq("inst-1"), eq("peer-1"));
    }

    // ───────────────────────── B4 · 裸回执（无编号） ─────────────────────────

    private static final com.nexusai.model.im_channel.ImChatEntry BOUND_ROW =
            new com.nexusai.model.im_channel.ImChatEntry(
                    "chat-row-1", "inst-1", "wecom", "peer-1", "张三", "p2p", null,
                    null, null, "sess-bound-1", "manual", null, "t", "t");

    @Test
    @DisplayName("🔴 裸 yes + 3 条待答 ⇒ 拒绝（不猜），提示带编号，⛔ 不进 agent")
    void bareYesWithMultiplePendingRejected() {
        when(chatService.findByInstanceAndPeer("inst-1", "peer-1"))
                .thenReturn(java.util.Optional.of(BOUND_ROW));
        when(callbacks.pendingShortIdsForSession("sess-bound-1"))
                .thenReturn(List.of("abcde", "fghij", "klmno"));

        router().route(msg("yes", false));

        verify(callbacks, never()).resolveFrom(anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(imSendService).send(any(), org.mockito.ArgumentMatchers.contains(
                "当前有 3 个待确认，请回复 yes <编号>"));
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("裸 yes + 恰好 1 条待答 ⇒ 允许，直接对那一条 allow")
    void bareYesWithSinglePendingResolves() {
        when(chatService.findByInstanceAndPeer("inst-1", "peer-1"))
                .thenReturn(java.util.Optional.of(BOUND_ROW));
        when(callbacks.pendingShortIdsForSession("sess-bound-1")).thenReturn(List.of("abcde"));
        when(callbacks.resolveFrom(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        router().route(msg("yes", false));

        verify(callbacks).resolveFrom(eq("abcde"), eq("allow"), eq("wecom"),
                eq("inst-1"), eq("peer-1"));
        verify(imSendService, never()).send(any(), anyString());
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("裸 yes + 0 条待答 ⇒ 不是权限回复，按普通聊天处理（不吞消息）")
    void bareYesWithoutPendingFallsThroughAsChat() {
        when(chatService.findByInstanceAndPeer("inst-1", "peer-1"))
                .thenReturn(java.util.Optional.of(BOUND_ROW));
        when(callbacks.pendingShortIdsForSession("sess-bound-1")).thenReturn(List.of());
        when(chatService.findById("chat-row-1")).thenReturn(java.util.Optional.of(BOUND_ROW));

        router().route(msg("yes", false));

        verify(callbacks, never()).resolveFrom(anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(queue).enqueue(any());
    }

    // ───────────────────────── 普通消息 ─────────────────────────

    @Test
    @DisplayName("普通聊天 + 已接线 ⇒ 落聊天目录 + 入队触发 agent（会话来自 bound_session_id）")
    void normalMessageEnqueuesWhenBound() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "wecom", "peer-1", "测试群", "p2p", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t")));

        router().route(msg("帮我看看这个合同", false));

        verify(chatService).upsert(eq("inst-1"), eq("wecom"), eq("peer-1"), any(),
                eq("p2p"), any(), any(), any(), eq(ImChatService.SOURCE_INBOUND));
        verify(queue).enqueue(any());
    }

    @Test
    @DisplayName("🔴 普通聊天但**未接线** ⇒ 不唤醒 agent（用户裁定：会话没绑渠道就不激活）")
    void unboundChatDoesNotWakeAgent() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "wecom", "peer-1", "测试群", "p2p", null,
                        null, null, null, null, null, "t", "t")));

        router().route(msg("这个群没接到任务", false));

        verify(chatService).upsert(anyString(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), anyString());   // 目录仍然要更新
        verify(queue, never()).enqueue(any());       // 但不唤醒 agent
    }

    @Test
    @DisplayName("非文本消息（图片/文件）⇒ 不触发 agent（媒体下载另开一期）")
    void mediaOnlyDoesNotEnqueue() {
        InboundMessage media = new InboundMessage("inst-1", "wecom", "peer-1", null, "s", "张三",
                "p2p", "m-1", null,
                List.of(new InboundMessage.Media("IMAGE", "a.png", "media-id-1")),
                System.currentTimeMillis(), null);

        router().route(media);

        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("空消息 / null ⇒ 不崩（平台偶尔发心跳类空帧）")
    void nullAndBlankSafe() {
        ImInboundRouter r = router();
        r.route(null);
        r.route(msg("   ", false));

        verify(queue, never()).enqueue(any());
    }

    // ───────────────────────── 平台重推去重 ─────────────────────────

    /**
     * 同一 messageId 第二次到达 ⇒ 不再入队、也不再刷新聊天目录。
     *
     * <p>WHY：三个平台都是至少一次投递（飞书 3 秒超时重推、企微重连补发、
     * 微信游标重复）。不去重 = 一句话唤醒两轮 agent，更糟的是「发送类」回复会真发两遍。
     */
    @Test
    @DisplayName("🔴 同一 messageId 只处理一次（平台重推不能唤醒两轮 agent）")
    void duplicateMessageIdIsIgnored() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "feishu", "peer-1", "用户A", "p2p", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t")));
        ImInboundRouter r = router();

        InboundMessage m = msg("帮我看看", false);
        r.route(m);
        r.route(m);          // 平台重推同一条

        verify(queue, times(1)).enqueue(any());
        // 落库也只该发生一次：重复消息不该刷新「最近消息」
        verify(chatService, times(1)).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("不同 messageId ⇒ 各处理一次（不能把正常连发也挡掉）")
    void distinctMessageIdsAreBothProcessed() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "feishu", "peer-1", "用户A", "p2p", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t")));
        ImInboundRouter r = router();

        r.route(withId(msg("第一句", false), "om_a"));
        r.route(withId(msg("第二句", false), "om_b"));

        verify(queue, times(2)).enqueue(any());
    }

    @Test
    @DisplayName("没有 messageId ⇒ 一律放行（微信 iLink 入站就没 id，不能因此丢消息）")
    void missingMessageIdIsAlwaysProcessed() {
        when(chatService.findById("chat-row-1"))
                .thenReturn(java.util.Optional.of(new com.nexusai.model.im_channel.ImChatEntry(
                        "chat-row-1", "inst-1", "wechat", "peer-1", "张三", "p2p", null,
                        null, null, "sess-bound-1", "manual", null, "t", "t")));
        ImInboundRouter r = router();

        InboundMessage noId = new InboundMessage("inst-1", "wechat", "peer-1", null, "s", "张三",
                "p2p", null, "没有 id 的消息", List.of(), System.currentTimeMillis(), null);
        r.route(noId);
        r.route(noId);

        verify(queue, times(2)).enqueue(any());
    }

    /** 给消息换一个 messageId（{@code msg()} 默认都叫 m-1，去重测试需要区分）。 */
    private static InboundMessage withId(InboundMessage m, String messageId) {
        return new InboundMessage(m.instanceId(), m.platform(), m.peerId(), m.peerName(),
                m.senderId(), m.senderName(), m.chatKind(), messageId, m.text(),
                m.media(), m.timestampMs(), m.raw());
    }
}
