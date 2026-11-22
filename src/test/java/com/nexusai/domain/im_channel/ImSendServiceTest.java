package com.nexusai.domain.im_channel;

import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.domain.im_channel.send.SendCapability;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImSendService} 的编排规则测试（不碰 DB / 不发网络请求）。
 *
 * <h2>WHY 用子类替身而不是 Mockito</h2>
 * {@code ImChatService} / {@code ImChannelService} 是普通 {@code @Service}，
 * 直接继承并覆写要用的方法即可，比引入 mock 框架更直白（也不会误打到真 mapper）。
 *
 * <h2>核心断言</h2>
 * <ul>
 *   <li>入参脏（null / 空 / 重复 / 空白 id）不能让整批崩；</li>
 *   <li>**一个目标失败不能影响其它目标**（这是设计 R1，也是这条链路最容易退化的地方）；</li>
 *   <li>只对成功的目标回写「最近消息」。</li>
 * </ul>
 */
class ImSendServiceTest {

    // ───────────────────────── 替身 ─────────────────────────

    /** 内存版聊天目录 + 记录 markOutbound 调用。 */
    private static final class FakeChatService extends ImChatService {
        final Map<String, ImChatEntry> chats = new HashMap<>();
        final Map<String, String> replyTokens = new HashMap<>();
        final List<String> marked = new ArrayList<>();

        @Override public Optional<ImChatEntry> findById(String id) {
            return Optional.ofNullable(chats.get(id));
        }
        @Override public Optional<String> replyTokenOf(String chatId) {
            return Optional.ofNullable(replyTokens.get(chatId));
        }
        @Override public void markOutbound(String chatId, String preview, String at) {
            marked.add(chatId);
        }
    }

    /** 内存版渠道服务：只回凭证，不碰库。 */
    private static final class FakeChannelService extends ImChannelService {
        final Map<String, String> creds = new HashMap<>();
        @Override public Optional<String> credentialOf(String id) {
            return Optional.ofNullable(creds.get(id));
        }
    }

    /** 可编排的策略：记录被发到的 peer，可指定抛错。 */
    private static class FakeStrategy extends AbstractImSendStrategy {
        final String platform;
        final List<String> sentTo = new ArrayList<>();
        RuntimeException boom;
        ImSendContext lastCtx;

        FakeStrategy(String platform) { this.platform = platform; }

        @Override public String platform() { return platform; }
        @Override public SendCapability capability() { return SendCapability.proactive(1000, true); }
        @Override public String doSend(ImSendContext ctx, String text) {
            if (boom != null) throw boom;
            sentTo.add(ctx.peerId());
            lastCtx = ctx;
            return "mid-" + ctx.peerId();
        }
    }

    private static ImChatEntry chat(String id, String instanceId, String platform, String peerId) {
        return new ImChatEntry(id, instanceId, platform, peerId, "name-" + peerId,
                "group", 3, null, null, null, null, null, "t", "t");
    }

    /** 组装一个注入了替身的服务。 */
    private static ImSendService service(FakeChatService chats, FakeChannelService channels,
                                         ImSendStrategy... strategies) {
        ImSendService s = new ImSendService();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "chatService", chats);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "channelService", channels);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "strategyFactory",
                new ImSendStrategyFactory(Arrays.asList(strategies)));
        return s;
    }

    // ───────────────────────── 入参边界 ─────────────────────────

    @Test
    @DisplayName("chatIds=null → 空结果，不抛异常")
    void nullChatIds() {
        ImSendService s = service(new FakeChatService(), new FakeChannelService());
        assertThat(s.send(null, "hi")).isEmpty();
    }

    @Test
    @DisplayName("chatIds 为空列表 → 空结果")
    void emptyChatIds() {
        ImSendService s = service(new FakeChatService(), new FakeChannelService());
        assertThat(s.send(List.of(), "hi")).isEmpty();
    }

    @Test
    @DisplayName("chatIds 含 null / 空白 / 重复 → 去重并跳过脏值，同一聊天只发一次")
    void dirtyAndDuplicateChatIds() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c1", chat("c1", "i1", "feishu", "peer1"));
        channels.creds.put("i1", "{\"appId\":\"a\"}");
        FakeStrategy feishu = new FakeStrategy("feishu");

        ImSendService s = service(chats, channels, feishu);

        List<ImSendResult> r = s.send(Arrays.asList("c1", null, "  ", "c1", " c1 "), "hi");

        assertThat(r).as("去重后只剩一个目标").hasSize(1);
        assertThat(feishu.sentTo).containsExactly("peer1");
    }

    @Test
    @DisplayName("text=null / 空白 → 每个目标都 FAILED，且不调用策略")
    void blankTextFailsEveryTarget() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c1", chat("c1", "i1", "feishu", "p1"));
        channels.creds.put("i1", "{}");
        FakeStrategy feishu = new FakeStrategy("feishu");
        ImSendService s = service(chats, channels, feishu);

        assertThat(s.send(List.of("c1"), null)).singleElement()
                .satisfies(x -> assertThat(x.status()).isEqualTo(ImSendResult.Status.FAILED));
        assertThat(s.send(List.of("c1"), "   ")).singleElement()
                .satisfies(x -> assertThat(x.status()).isEqualTo(ImSendResult.Status.FAILED));
        assertThat(feishu.sentTo).isEmpty();
    }

    // ───────────────────────── 目标解析失败 ─────────────────────────

    @Test
    @DisplayName("聊天不存在 → FAILED(IM_CHAT_NOT_FOUND)")
    void chatNotFound() {
        ImSendService s = service(new FakeChatService(), new FakeChannelService());
        ImSendResult r = s.send(List.of("nope"), "hi").get(0);
        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_CHAT_NOT_FOUND");
    }

    @Test
    @DisplayName("实例凭证缺失 → FAILED(IM_INSTANCE_NOT_FOUND)")
    void credentialMissing() {
        FakeChatService chats = new FakeChatService();
        chats.chats.put("c1", chat("c1", "i1", "feishu", "p1"));
        ImSendService s = service(chats, new FakeChannelService(), new FakeStrategy("feishu"));

        ImSendResult r = s.send(List.of("c1"), "hi").get(0);
        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_INSTANCE_NOT_FOUND");
    }

    @Test
    @DisplayName("未知平台 → SKIPPED(IM_SEND_UNSUPPORTED)，不是 FAILED")
    void unknownPlatformSkips() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c1", chat("c1", "i1", "qq", "p1"));
        channels.creds.put("i1", "{}");
        ImSendService s = service(chats, channels, new FakeStrategy("feishu"));

        ImSendResult r = s.send(List.of("c1"), "hi").get(0);
        assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(r.errorCode()).isEqualTo("IM_SEND_UNSUPPORTED");
    }

    // ───────────────────────── 逐目标独立（核心） ─────────────────────────

    @Test
    @DisplayName("一个目标抛异常不影响其余目标（单目标失败不外溢）")
    void oneFailureDoesNotBreakOthers() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("bad", chat("bad", "i1", "feishu", "boom-peer"));
        chats.chats.put("ok", chat("ok", "i1", "feishu", "good-peer"));
        channels.creds.put("i1", "{}");

        // 让 peer=boom-peer 的目标炸，其余正常
        FakeStrategy feishu = new FakeStrategy("feishu") {
            @Override public String doSend(ImSendContext ctx, String text) {
                if ("boom-peer".equals(ctx.peerId())) throw new IllegalStateException("boom");
                sentTo.add(ctx.peerId());
                return "mid";
            }
        };
        ImSendService s = service(chats, channels, feishu);

        List<ImSendResult> r = s.send(Arrays.asList("bad", "ok"), "hi");

        assertThat(r).hasSize(2);
        assertThat(r.get(0).status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.get(1).status()).as("第二个目标必须照常发出").isEqualTo(ImSendResult.Status.SENT);
        assertThat(feishu.sentTo).containsExactly("good-peer");
    }

    @Test
    @DisplayName("成功才回写 markOutbound；失败/跳过都不写")
    void markOutboundOnlyOnSuccess() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("ok", chat("ok", "i1", "feishu", "p1"));
        chats.chats.put("qq", chat("qq", "i2", "qq", "p2"));
        channels.creds.put("i1", "{}");
        channels.creds.put("i2", "{}");
        ImSendService s = service(chats, channels, new FakeStrategy("feishu"));

        s.send(Arrays.asList("ok", "qq"), "hi");

        assertThat(chats.marked).as("只有真正发出去的才更新最近消息").containsExactly("ok");
    }

    @Test
    @DisplayName("回复令牌会传进上下文（微信策略据此判断能不能发）")
    void replyTokenIsPassedToContext() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c1", chat("c1", "i1", "wechat", "wxid_1"));
        chats.replyTokens.put("c1", "ctx-token-1");
        channels.creds.put("i1", "{\"baseUrl\":\"https://ilinkai.weixin.qq.com\"}");

        FakeStrategy wechat = new FakeStrategy("wechat");
        ImSendService s = service(chats, channels, wechat);

        s.send(List.of("c1"), "hi");

        assertThat(wechat.lastCtx).isNotNull();
        assertThat(wechat.lastCtx.replyToken()).isEqualTo("ctx-token-1");
        assertThat(wechat.lastCtx.isGroup()).isTrue();
    }

    @Test
    @DisplayName("目标数超上限 → 只处理前 20 个（不报错、不崩）")
    void exceedsMaxTargets() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String id = "c" + i;
            ids.add(id);
            chats.chats.put(id, chat(id, "i1", "feishu", "p" + i));
        }
        channels.creds.put("i1", "{}");
        FakeStrategy feishu = new FakeStrategy("feishu");
        ImSendService s = service(chats, channels, feishu);

        List<ImSendResult> r = s.send(ids, "hi");

        assertThat(r).hasSize(ImSendService.MAX_TARGETS);
        assertThat(feishu.sentTo).hasSize(ImSendService.MAX_TARGETS);
    }
}
