package com.nexusai.domain.im_channel.inbound.feishu;

import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.inbound.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书事件归一化的边界测试。
 *
 * <h2>为什么这些用例值得写</h2>
 * 归一化是「平台协议 → 本仓语义」唯一一堵墙，出错的方式**不会抛异常**、只会静默丢消息
 * 或者让机器人乱回。所以每条都钉一个具体后果：
 * <ul>
 *   <li>群消息不判 @ ⇒ 机器人在群里对着别人的聊天插嘴；</li>
 *   <li>不判 senderType=app ⇒ bot 自己发的消息会回环，无限自问自答；</li>
 *   <li>不剥 {@code @_user_N} ⇒ 模型看到一串占位符，理解成垃圾输入。</li>
 * </ul>
 * ⚠️ 纯 POJO + 纯函数，不碰 Spring、不碰库。
 */
class FeishuInboundListenerTest {

    private final FeishuInboundListener listener = new FeishuInboundListener();

    // ── 工具方法 ─────────────────────────────────────────

    private static P2MessageReceiveV1 event(EventMessage msg, String senderType) {
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(msg);
        if (senderType != null) {
            UserId uid = new UserId();
            uid.setOpenId("ou_user_1");
            EventSender sender = new EventSender();
            sender.setSenderId(uid);
            sender.setSenderType(senderType);
            data.setSender(sender);
        }
        P2MessageReceiveV1 evt = new P2MessageReceiveV1();
        evt.setEvent(data);
        return evt;
    }

    private static EventMessage msg(String chatType, String msgType, String content) {
        EventMessage m = new EventMessage();
        m.setMessageId("om_1");
        m.setChatId("oc_chat_1");
        m.setChatType(chatType);
        m.setMessageType(msgType);
        m.setContent(content);
        m.setCreateTime("1763500000000");
        return m;
    }

    private static MentionEvent botMention() {
        MentionEvent me = new MentionEvent();
        me.setKey("@_user_1");
        me.setName("智能bot");
        me.setMentionedType("bot");
        UserId id = new UserId();
        id.setOpenId("ou_bot");
        me.setId(id);
        return me;
    }

    // ── 用例 ─────────────────────────────────────────────

    @Test
    void 单聊文本_归一后peerId取chatId() {
        InboundMessage m = listener.toInbound("imc-1",
                event(msg("p2p", "text", "{\"text\":\"你好\"}"), "user"));

        assertNotNull(m);
        // ✅ 飞书单聊也带 chat_id —— 这是它比企微好用的地方（企微单聊帧里没有 chatid）
        assertEquals("oc_chat_1", m.peerId());
        assertEquals("p2p", m.chatKind());
        assertEquals("你好", m.text());
        assertEquals("om_1", m.messageId());
        assertEquals("feishu", m.platform());
        assertEquals(1763500000000L, m.timestampMs());
    }

    @Test
    void 群消息_没at机器人_整条丢弃() {
        // 不判 @ 的后果：机器人在群里对着别人的聊天乱回
        InboundMessage m = listener.toInbound("imc-1",
                event(msg("group", "text", "{\"text\":\"今天吃什么\"}"), "user"));

        assertNull(m, "群消息未 @ 机器人必须丢弃");
    }

    @Test
    void 群消息_at了机器人_保留并剥掉占位符() {
        EventMessage raw = msg("group", "text", "{\"text\":\"@_user_1 帮我看看这个报错\"}");
        MentionEvent[] mentions = {botMention()};
        raw.setMentions(mentions);

        InboundMessage m = listener.toInbound("imc-1", event(raw, "user"));

        assertNotNull(m);
        assertEquals("group", m.chatKind());
        // 剥掉后模型看到的是干净正文，而不是 "@_user_1 帮我看看这个报错"
        assertEquals("帮我看看这个报错", m.text());
    }

    @Test
    void 机器人自己发的_丢弃_避免自问自答() {
        InboundMessage m = listener.toInbound("imc-1",
                event(msg("p2p", "text", "{\"text\":\"我是机器人\"}"), "app"));

        assertNull(m, "senderType=app 必须丢弃，否则会自己回自己");
    }

    @Test
    void 图片消息_变成media且text为空() {
        InboundMessage m = listener.toInbound("imc-1",
                event(msg("p2p", "image", "{\"image_key\":\"img_v2_abc\"}"), "user"));

        assertNotNull(m);
        assertEquals(1, m.media().size());
        assertEquals("IMAGE", m.media().get(0).kind());
        assertEquals("img_v2_abc", m.media().get(0).ref());
        assertTrue(m.text() == null || m.text().isBlank());
    }

    @Test
    void 文件消息_带出文件名() {
        InboundMessage m = listener.toInbound("imc-1",
                event(msg("p2p", "file", "{\"file_key\":\"file_v2_x\",\"file_name\":\"周报.pdf\"}"), "user"));

        assertNotNull(m);
        assertEquals("FILE", m.media().get(0).kind());
        assertEquals("周报.pdf", m.media().get(0).fileName());
        assertEquals("file_v2_x", m.media().get(0).ref());
    }

    @Test
    void 富文本post_拼成纯文本() {
        String post = "{\"title\":\"标题\",\"content\":[[{\"tag\":\"text\",\"text\":\"第一段\"}]]}";
        InboundMessage m = listener.toInbound("imc-1", event(msg("p2p", "post", post), "user"));

        assertNotNull(m);
        assertTrue(m.text().contains("标题"));
        assertTrue(m.text().contains("第一段"));
    }

    @Test
    void 不支持的消息类型_丢弃而不是崩() {
        // sticker 等暂不支持：必须返回 null，不能抛（抛了会打断 SDK 收包循环）
        assertNull(listener.toInbound("imc-1", event(msg("p2p", "sticker", "{}"), "user")));
    }

    @Test
    void 凭证解析_兼容下划线键名() {
        var c = listener.parseCredential("{\"app_id\":\"cli_x\",\"app_secret\":\"s\"}");
        assertEquals("cli_x", c.appId());
        assertEquals("s", c.appSecret());

        var c2 = listener.parseCredential("{\"appId\":\"cli_y\",\"appSecret\":\"t\"}");
        assertEquals("cli_y", c2.appId());
    }

    @Test
    void 凭证缺字段_抛错而不是带着null去连() {
        boolean threw = false;
        try {
            listener.parseCredential("{\"appId\":\"cli_x\"}");
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "缺 appSecret 必须显式失败");
    }

    // ── 长连接状态回写（N2：卡片状态必须来自真实连接结果） ─────────

    /** 可观测的假传输：不真连飞书，只记录 start/close 并可让首次握手失败。 */
    private static final class FakeTransport implements FeishuInboundListener.WsTransport {
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean failReady;
        volatile boolean closed;

        @Override public void start() { starts.incrementAndGet(); }

        @Override public void awaitReady(long timeoutMs) throws Exception {
            if (failReady) {
                throw new IllegalStateException("websocket handshake did not complete");
            }
        }

        @Override public void close() { closed = true; }
    }

    private static ImChannelService channelMock() {
        ImChannelService s = mock(ImChannelService.class);
        when(s.credentialOf("imc-1"))
                .thenReturn(Optional.of("{\"appId\":\"cli_x\",\"appSecret\":\"s\"}"));
        when(s.userOpenIdOf("imc-1")).thenReturn(Optional.empty());
        when(s.userNameOf("imc-1")).thenReturn(Optional.empty());
        when(s.findById("imc-1")).thenReturn(Optional.empty());
        return s;
    }

    private static FeishuInboundListener listenerWith(ImChannelService cs,
                                                      FeishuInboundListener.WsTransport transport) {
        FeishuInboundListener l = new FeishuInboundListener();
        ReflectionTestUtils.setField(l, "channelService", cs);
        ReflectionTestUtils.setField(l, "transportFactory",
                (FeishuInboundListener.TransportFactory) (appId, appSecret, dispatcher,
                                                          onReconnecting, onReconnected) -> transport);
        return l;
    }

    @Test
    void 启动后首次握手成功_回写connected() {
        ImChannelService cs = channelMock();
        FakeTransport t = new FakeTransport();
        FeishuInboundListener l = listenerWith(cs, t);

        l.start("imc-1", msg -> { });

        assertEquals(1, t.starts.get());
        // 握手在后台线程完成 ⇒ 用 timeout 等回调，不能同步断言
        verify(cs, timeout(2000)).markStatus(eq("imc-1"), eq("connected"), anyString());
        assertTrue(l.isRunning());
    }

    @Test
    void 首次握手失败_回写error而不是假装已连接() {
        ImChannelService cs = channelMock();
        FakeTransport t = new FakeTransport();
        t.failReady = true;
        FeishuInboundListener l = listenerWith(cs, t);

        l.start("imc-1", msg -> { });

        verify(cs, timeout(2000)).markStatus(eq("imc-1"), eq("error"), contains("飞书长连接失败"));
    }

    @Test
    void 停止后_回写disconnected() {
        ImChannelService cs = channelMock();
        FakeTransport t = new FakeTransport();
        FeishuInboundListener l = listenerWith(cs, t);
        l.start("imc-1", msg -> { });
        verify(cs, timeout(2000)).markStatus(eq("imc-1"), eq("connected"), anyString());

        l.stop();

        verify(cs).markStatus("imc-1", "disconnected", "已停止");
        assertFalse(l.isRunning());
        assertTrue(t.closed);
    }
}
