package com.nexusai.domain.im_channel.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.impl.FeishuChatListStrategy;
import com.nexusai.domain.im_channel.catalog.impl.WechatChatListStrategy;
import com.nexusai.domain.im_channel.catalog.impl.WecomChatListStrategy;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.repository.im_channel.entity.WechatConversationRecord;
import com.nexusai.repository.im_channel.mapper.WechatConversationMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话列表策略（{@link ImChatListStrategy}）的行为验证。
 *
 * <h2>WHY 这些断言值得存在</h2>
 * <ul>
 *   <li><b>企微必须打两个端点</b>：{@code sessions/list} 找全会话但缺群名，
 *       {@code groups/list} 补名字。只打一个 ⇒ 界面上群名是一串 id（仓里踩过的坑）。</li>
 *   <li><b>平台拒绝不能伪装成「没有会话」</b>：飞书拉失败必须抛
 *       {@link ImSendException}，否则 AI 会以为「这个渠道没有会话可发」。</li>
 *   <li><b>工厂的兜底</b>：未知平台返回 Unsupported 而不是 null。</li>
 * </ul>
 */
class ImChatListStrategyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记录 stub 收到的请求路径（用于断言「两个端点都打了」）。 */
    private final List<String> paths = new ArrayList<>();

    // ═════════════════════ 工厂 ═════════════════════

    @Test
    @DisplayName("工厂：未知平台 ⇒ Unsupported（canRefresh=false），永不返回 null")
    void factoryUnknownPlatform() {
        ImChatListStrategyFactory f = new ImChatListStrategyFactory(List.of());
        ImChatListStrategy s = f.of("dingtalk");
        assertThat(s).isNotNull();
        assertThat(s.canRefresh()).isFalse();
        assertThat(f.of(null)).isNotNull();
        assertThat(f.of("  ")).isNotNull();
    }

    @Test
    @DisplayName("工厂：注册了就按平台分发")
    void factoryDispatchesByPlatform() {
        ImChatListStrategy wecom = mock(ImChatListStrategy.class);
        when(wecom.platform()).thenReturn("wecom");
        ImChatListStrategyFactory f = new ImChatListStrategyFactory(List.of(wecom));
        assertThat(f.of("wecom")).isSameAs(wecom);
    }

    // ═════════════════════ 企微 ═════════════════════

    @Test
    @DisplayName("企微：sessions/list 与 groups/list **两个端点都打**，且群名取非空的那个")
    void wecomHitsBothEndpointsAndMergesNames() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/cli/message/aibot/sessions/list", ex -> {
            paths.add("/cli/message/aibot/sessions/list");
            ObjectNode biz = JSON.createObjectNode();
            biz.put("sessions_count", 2);
            var arr = biz.putArray("sessions");
            var s1 = arr.addObject();
            s1.put("chat_id", "woNH-1");
            s1.put("chat_name", "");
            s1.put("chat_type", "single");
            s1.put("last_msg_time", "2026-11-18 12:00:00");
            var s2 = arr.addObject();
            s2.put("chat_id", "wrNH-1");
            s2.put("chat_name", "");
            s2.put("chat_type", "group");
            respond(ex, cliEnvelope(biz));
        });
        stub.createContext("/cli/chat/groups/list", ex -> {
            paths.add("/cli/chat/groups/list");
            ObjectNode biz = JSON.createObjectNode();
            var arr = biz.putArray("items");
            var g = arr.addObject();
            g.put("chat_id", "wrNH-1");
            g.put("chat_name", "真实群名");
            biz.put("has_more", false);
            respond(ex, cliEnvelope(biz));
        });
        stub.start();
        try {
            WecomChatListStrategy s = wecomStrategy(stub);

            List<ImChatCandidate> r = s.fetch("inst-1");

            assertThat(paths).as("两个端点都必须打")
                    .contains("/cli/message/aibot/sessions/list", "/cli/chat/groups/list");
            assertThat(r).hasSize(2);

            ImChatCandidate single = r.stream().filter(c -> "woNH-1".equals(c.peerId())).findFirst().orElseThrow();
            assertThat(single.chatKind()).isEqualTo("p2p");
            assertThat(single.lastMessageAt()).isEqualTo("2026-11-18 12:00:00");

            ImChatCandidate group = r.stream().filter(c -> "wrNH-1".equals(c.peerId())).findFirst().orElseThrow();
            assertThat(group.chatKind()).isEqualTo("group");
            assertThat(group.peerName())
                    .as("groups/list 补上的名字应该覆盖 sessions/list 的空名字")
                    .isEqualTo("真实群名");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("企微：groups/list 的时间参数是 yyyy-MM-dd HH:mm:ss 字符串，不是毫秒")
    void wecomGroupListUsesStringTimeFormat() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/cli/message/aibot/sessions/list", ex ->
                respond(ex, cliEnvelope(JSON.createObjectNode().putObject("sessions"))));
        stub.createContext("/cli/chat/groups/list", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectNode biz = JSON.createObjectNode();
            biz.putArray("items");
            biz.put("has_more", false);
            respond(ex, cliEnvelope(biz));
        });
        stub.start();
        try {
            WecomChatListStrategy s = wecomStrategy(stub);
            s.fetch("inst-1");

            assertThat(bodies).hasSize(1);
            String body = bodies.get(0);
            JsonNode biz = JSON.readTree(JSON.readTree(body).path("payload").asText());
            String begin = biz.path("begin_time").asText("");
            assertThat(begin)
                    .as("PIT-WC-028：必须是字符串时间，不是毫秒")
                    .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("企微：sessions/list 失败 ⇒ 仍能靠 groups/list 拿到结果（不整体崩）")
    void wecomSurvivesSessionsFailure() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/cli/message/aibot/sessions/list", ex ->
                respond(ex, "{\"errcode\":999,\"errmsg\":\"boom\"}"));
        stub.createContext("/cli/chat/groups/list", ex -> {
            ObjectNode biz = JSON.createObjectNode();
            var g = biz.putArray("items").addObject();
            g.put("chat_id", "g1");
            g.put("chat_name", "群1");
            biz.put("has_more", false);
            respond(ex, cliEnvelope(biz));
        });
        stub.start();
        try {
            WecomChatListStrategy s = wecomStrategy(stub);
            List<ImChatCandidate> r = s.fetch("inst-1");
            assertThat(r).hasSize(1);
            assertThat(r.get(0).peerId()).isEqualTo("g1");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("企微：实例没有凭证 ⇒ 明确抛错（不是返回空列表）")
    void wecomNoCredentialThrows() {
        WecomChatListStrategy s = new WecomChatListStrategy();
        ReflectionTestUtils.setField(s, "channelService", channelServiceWith(null));
        assertThatThrownBy(() -> s.fetch("inst-x"))
                .isInstanceOf(ImSendException.class)
                .hasMessageContaining("inst-x");
    }

    // ═════════════════════ 飞书 ═════════════════════

    @Test
    @DisplayName("飞书：平台拒绝/网络失败 ⇒ 抛 ImSendException（⛔ 不能伪装成「没有会话」）")
    void feishuPlatformErrorThrows() {
        FeishuChatListStrategy s = new FeishuChatListStrategy();
        ReflectionTestUtils.setField(s, "channelService",
                channelServiceWith("{\"appId\":\"a\",\"appSecret\":\"s\"}"));
        ReflectionTestUtils.setField(s, "tokenService", feishuTokenServiceReturning("tok"));
        // 真实网关地址不可达 ⇒ 网络异常 ⇒ 必须抛 ImSendException（不能是 NPE）
        assertThatThrownBy(() -> s.fetch("inst-1"))
                .isInstanceOf(ImSendException.class)
                .satisfies(e -> assertThat(((ImSendException) e).code())
                        .isEqualTo("IM_SEND_PLATFORM_ERROR"));
    }

    @Test
    @DisplayName("飞书：凭证缺 appId ⇒ IM_CREDENTIAL_INVALID")
    void feishuBadCredential() {
        FeishuChatListStrategy s = new FeishuChatListStrategy();
        ReflectionTestUtils.setField(s, "channelService", channelServiceWith("{\"appId\":\"a\"}"));
        assertThatThrownBy(() -> s.fetch("inst-1"))
                .isInstanceOf(ImSendException.class)
                .satisfies(e -> assertThat(((ImSendException) e).code()).isEqualTo("IM_CREDENTIAL_INVALID"));
    }

    // ═════════════════════ 微信 ═════════════════════

    @Test
    @DisplayName("微信：从 wechat_conversation 表读（本地库，不联网）")
    void wechatReadsLocalTable() throws ImSendException {
        WechatConversationRecord c1 = new WechatConversationRecord();
        c1.setConversationId("wxid-a");
        c1.setDisplayName("张三");
        c1.setIsGroup(0);
        c1.setLastMessageTime(1760000000000L);
        WechatConversationRecord c2 = new WechatConversationRecord();
        c2.setConversationId("gid-b");
        c2.setDisplayName("家人群");
        c2.setIsGroup(1);

        WechatConversationMapper mapper = mock(WechatConversationMapper.class);
        when(mapper.selectAllOrdered()).thenReturn(List.of(c1, c2));

        WechatChatListStrategy s = new WechatChatListStrategy();
        ReflectionTestUtils.setField(s, "conversationMapper", mapper);

        List<ImChatCandidate> r = s.fetch("inst-1");

        assertThat(r).hasSize(2);
        assertThat(r.get(0).chatKind()).isEqualTo("p2p");
        assertThat(r.get(1).chatKind()).isEqualTo("group");
        assertThat(r.get(1).peerName()).isEqualTo("家人群");
        assertThat(s.canRefresh()).isTrue();
    }

    @Test
    @DisplayName("微信：表还没数据 ⇒ 返回空列表（不是抛错）")
    void wechatEmptyTableReturnsEmptyList() throws ImSendException {
        WechatConversationMapper mapper = mock(WechatConversationMapper.class);
        when(mapper.selectAllOrdered()).thenReturn(List.of());
        WechatChatListStrategy s = new WechatChatListStrategy();
        ReflectionTestUtils.setField(s, "conversationMapper", mapper);
        assertThat(s.fetch("inst-1")).isEmpty();
    }

    // ═════════════════════ 工具 ═════════════════════

    /** 组装一个指向 stub 的企微策略（换票被替身挡掉，不会打真平台）。 */
    private WecomChatListStrategy wecomStrategy(HttpServer stub) {
        WecomChatListStrategy s = new WecomChatListStrategy();
        ReflectionTestUtils.setField(s, "channelService",
                channelServiceWith("{\"botId\":\"b\",\"botSecret\":\"s\"}"));
        WecomCliClient cli = new WecomCliClient(base(stub));
        ReflectionTestUtils.setField(s, "cliClient", cli);
        ReflectionTestUtils.setField(s, "messageApi", new WecomMessageApi(cli));
        ReflectionTestUtils.setField(s, "tokenService", wecomTokenServiceReturning("tok"));
        return s;
    }

    private static ImChannelService channelServiceWith(String credential) {
        ImChannelService cs = mock(ImChannelService.class);
        when(cs.credentialOf(anyString()))
                .thenReturn(credential == null ? Optional.empty() : Optional.of(credential));
        return cs;
    }

    /** 替换掉真的换票服务（会打真平台）。 */
    private static WecomCliTokenService wecomTokenServiceReturning(String token) {
        WecomCliTokenService t = mock(WecomCliTokenService.class);
        try {
            when(t.tokenFor(anyString(), anyString(), anyString())).thenReturn(token);
        } catch (ImSendException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    /** 替换掉真的换票服务（会打真平台）。 */
    private static com.nexusai.domain.im_channel.send.FeishuTokenService feishuTokenServiceReturning(String token) {
        com.nexusai.domain.im_channel.send.FeishuTokenService t =
                mock(com.nexusai.domain.im_channel.send.FeishuTokenService.class);
        try {
            when(t.tokenFor(anyString(), anyString(), anyString())).thenReturn(token);
        } catch (ImSendException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    /**
     * 企微网关的成功信封。
     *
     * <p>⚠️ 形状必须逐字对齐真实网关：{@code errcode} + {@code errmsg} + {@code results_json}
     * （<b>业务 JSON 的字符串</b>）。用 Jackson 拼而不是手写转义 —— 手写转义极易漏一个反斜杠，
     * 而漏了之后测试会「莫名其妙地空列表」，很难定位。
     */
    private static String cliEnvelope(JsonNode biz) {
        ObjectNode outer = JSON.createObjectNode();
        outer.put("errcode", 0);
        outer.put("errmsg", "ok");
        outer.put("results_json", biz.toString());
        return outer.toString();
    }

    private static String base(HttpServer stub) {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) {
        try (OutputStream os = ex.getResponseBody()) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            os.write(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
