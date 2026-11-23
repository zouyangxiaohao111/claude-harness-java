package com.nexusai.domain.im_message.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.domain.im_message.fetch.impl.FeishuImMessageFetcher;
import com.nexusai.domain.im_message.fetch.impl.WecomImMessageFetcher;
import com.nexusai.model.im_message.ImMessageEntry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三平台拉取器的<b>报文形态</b>验证（stub HTTP server，⛔ 不打任何真平台）。
 *
 * <h2>WHY 必须是这一层</h2>
 * 拉取层的正确性几乎全在<b>「实际发出的报文长什么样」</b>上：
 * <ul>
 *   <li>企微：请求体必须包成 {@code {"payload":"&lt;业务 JSON 字符串&gt;"}}（PIT-WC-022）——
 *       不包平台回 {@code -1「系统失败」}，错误信息完全不提示真因；</li>
 *   <li>企微：时间必须�� {@code yyyy-MM-dd HH:mm:ss}，塞毫秒必被拒（PIT-WC-028）；</li>
 *   <li>飞书：{@code start_time/end_time} 是<b>秒级 epoch</b>，而消息项里的
 *       {@code create_time} 是<b>毫秒</b> —— 两个单位在同一份报文里，混用会把区间拉空；</li>
 *   <li>飞书：{@code body.content} 是<b>字符串化的 JSON</b>，必须二次解析。</li>
 * </ul>
 * 这些断言只看返回值是<b>证不出来</b>的 ⇒ 必须抓实际报文。
 */
class ImMessageFetcherProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    /** 抓到的请求（路径 + query + body + Authorization）。 */
    private final List<String[]> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    /** 让「消息列表」端点返回业务错误（其它端点照常）。 */
    private boolean failMessages;

    /** 让企微消息列表返回指定错误码（0 = 正常）。 */
    private int wecomErrorCode;

    /** 让飞书消息列表返回指定 HTTP 状态（200 = 正常）。 */
    private int messagesHttpStatus = 200;

    /** 飞书消息列表在非 200 时的响应体（模拟平台错误报文）。 */
    private String messagesHttpBody;

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new String[]{path, query, body});
            }
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String payload;
            int status = 200;
            if (path.contains("/open-apis/im/v1/messages") && messagesHttpStatus != 200) {
                status = messagesHttpStatus;
                payload = messagesHttpBody == null ? "" : messagesHttpBody;
            } else {
                payload = route(path);
            }
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 按路径返回平台响应（字段形状逐字对齐各平台真实响应）。 */
    private String route(String path) {
        try {
            if (path.contains("get_cli_config")) {
                ObjectNode r = JSON.createObjectNode();
                r.put("errcode", 0);
                r.put("errmsg", "ok");
                r.put("token", "cli-token-test");
                return JSON.writeValueAsString(r);
            }
            if (path.contains("tenant_access_token")) {
                ObjectNode r = JSON.createObjectNode();
                r.put("code", 0);
                r.put("msg", "ok");
                r.put("tenant_access_token", "t-test");
                r.put("expire", 7200);
                return JSON.writeValueAsString(r);
            }
            if (path.contains("/cli/chat/messages/list")) {
                return wecomErrorCode != 0 ? wecomErrorResponse(wecomErrorCode) : wecomMessagesResponse();
            }
            if (path.contains("/open-apis/im/v1/messages")) {
                if (failMessages) {
                    return "{\"code\":99991672,\"msg\":\"no permission\"}";
                }
                return feishuMessagesResponse();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return "{\"errcode\":0,\"errmsg\":\"ok\",\"code\":0,\"data\":{}}";
    }

    /** 企微：{@code results_json} 里再套一层业务 JSON（真实响应就是两层）。 */
    private String wecomMessagesResponse() throws Exception {
        ObjectNode inner = JSON.createObjectNode();
        ArrayNode messages = inner.putArray("messages");
        ObjectNode m1 = messages.addObject();
        m1.put("msg_type", "text");
        m1.put("send_time", "2026-11-18 10:00:00");
        m1.put("text", "你好，明天几点上线？");
        m1.put("userid", "u_1");
        m1.put("user_name", "张三");
        ObjectNode m2 = messages.addObject();
        m2.put("msg_type", "image");
        m2.put("send_time", "2026-11-18 10:00:05");
        m2.put("userid", "u_1");
        m2.put("user_name", "张三");
        m2.putObject("image").put("file_name", "shot.png").put("url", "https://x/shot.png");
        inner.put("messages_count", 2);
        inner.put("has_more", false);
        inner.put("next_cursor", "");

        ObjectNode outer = JSON.createObjectNode();
        outer.put("errcode", 0);
        outer.put("errmsg", "ok");
        outer.put("results_json", JSON.writeValueAsString(inner));
        return JSON.writeValueAsString(outer);
    }

    /** 企微：业务错误（内层 {@code error.code}），如 40073 非法的 chat_id。 */
    private String wecomErrorResponse(int code) throws Exception {
        ObjectNode inner = JSON.createObjectNode();
        inner.putObject("error").put("code", code)
            .put("message", code == 40073 ? "非法的 'chat_id' (callid: test-call-id)" : "平台错误");
        ObjectNode outer = JSON.createObjectNode();
        outer.put("errcode", 0);
        outer.put("errmsg", "ok");
        outer.put("results_json", JSON.writeValueAsString(inner));
        return JSON.writeValueAsString(outer);
    }

    /** 飞书：{@code body.content} 是字符串化的 JSON；{@code create_time} 是毫秒。 */
    private String feishuMessagesResponse() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("code", 0);
        root.put("msg", "ok");
        ObjectNode data = root.putObject("data");
        ArrayNode items = data.putArray("items");

        ObjectNode t = items.addObject();
        t.put("message_id", "om_1");
        t.put("msg_type", "text");
        // ⚠️ create_time 是**毫秒**（与 URL 上的秒级 start_time/end_time 不是同一个单位）
        t.put("create_time", String.valueOf(
            ImMessageTime.toInstant("2026-11-18T10:00:00+08:00").toEpochMilli()));
        t.putObject("body").put("content", "{\"text\":\"在的\"}");
        ObjectNode s1 = t.putObject("sender");
        s1.put("sender_type", "user");
        // 🔴 拉历史接口的 id 是**字符串**（真机形态）；入站事件才是 {open_id:…} 对象。
        //    只按对象取 ⇒ 真机 senderId 全空、界面「未知发送人」，而单测全绿。
        s1.put("id", "ou_1");
        s1.put("id_type", "open_id");

        ObjectNode f = items.addObject();
        f.put("message_id", "om_2");
        f.put("msg_type", "file");
        f.put("create_time", String.valueOf(
            ImMessageTime.toInstant("2026-11-18T10:00:05+08:00").toEpochMilli()));
        f.putObject("body").put("content", "{\"file_key\":\"file_abc\",\"file_name\":\"需求.pdf\"}");
        ObjectNode s2 = f.putObject("sender");
        s2.put("sender_type", "app");
        // 另一种形态（入站事件）：id 是对象 ⇒ 必须同样能取出来
        s2.putObject("id").put("open_id", "ou_bot");

        data.put("has_more", false);
        data.put("page_token", "");
        return JSON.writeValueAsString(root);
    }

    /** 凭证替身：只回固定 JSON（协议层测试不关心凭证怎么存）。 */
    private static ImChannelService channelService(String credentialJson) {
        return new ImChannelService() {
            @Override
            public Optional<String> credentialOf(String id) {
                return Optional.of(credentialJson);
            }

            // 「你本人」的授权信息（V313/V314）：飞书拉历史不带昵称，
            // fetcher 靠这两个值把 ou_1 补成人名。
            @Override
            public Optional<String> userOpenIdOf(String id) {
                return Optional.of("ou_1");
            }

            @Override
            public Optional<String> userNameOf(String id) {
                return Optional.of("郑昌蔚");
            }

            // 机器人自己发的（sender_type=app）用实例显示名，而不是 appId
            @Override
            public Optional<com.nexusai.model.im_channel.ImInstanceEntry> findById(String id) {
                return Optional.of(new com.nexusai.model.im_channel.ImInstanceEntry(
                    id, "feishu", "智能bot", "connected", null, null, null, "t", "t"));
            }
        };
    }

    /** 造一个指向 stub 的企微拉取器。 */
    private WecomImMessageFetcher newWecomFetcher() {
        WecomImMessageFetcher fetcher = new WecomImMessageFetcher();
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"botId\":\"bot1\",\"botSecret\":\"sec1\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new WecomCliTokenService(base() + "/cgi-bin/aibot/cli/get_cli_config"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "cliClient",
            new WecomCliClient(base()));
        return fetcher;
    }

    /** 取最后一次企微消息列表请求里的 {@code begin_time}（企微形态 {@code yyyy-MM-dd HH:mm:ss}）。 */
    private String capturedWecomBeginTime() throws Exception {
        String[] req = null;
        for (String[] r : requests) {
            if (r[0].contains("/cli/chat/messages/list")) {
                req = r;
            }
        }
        assertThat(req).as("必须打到 /cli/chat/messages/list").isNotNull();
        JsonNode outer = JSON.readTree(req[2]);
        JsonNode business = JSON.readTree(outer.get("payload").asText());
        return business.path("begin_time").asText();
    }

    /** 断言 {@code begin_time} 落在「7 天前」到「6 天 23 小时前」之间（即被 clamp 进窗口）。 */
    private static void assertInsideSevenDayWindow(String beginTime) {
        assertThat(beginTime).isNotBlank();
        Instant beginAt = ImMessageTime.toInstant(beginTime);
        assertThat(beginAt).as("begin_time 必须能解析成时间").isNotNull();
        Instant now = Instant.now();
        assertThat(beginAt)
            .as("begin_time 不能早于 7 天前（否则平台报 850016）：" + beginTime)
            .isAfter(now.minus(7, ChronoUnit.DAYS));
        assertThat(beginAt)
            .as("begin_time 必须被 clamp 进 7 天窗口（含 10 分钟余量）：" + beginTime)
            .isBefore(now.minus(6, ChronoUnit.DAYS).minus(23, ChronoUnit.HOURS));
    }

    // ═══════════════════════ 企微 ═══════════════════════

    @Test
    @DisplayName("企微：请求体必须包成 {\"payload\":\"<业务 JSON 字符串>\"}，且时间格式是 yyyy-MM-dd HH:mm:ss")
    void wecomRequestUsesPayloadEnvelopeAndLocalTimeFormat() throws Exception {
        WecomImMessageFetcher fetcher = new WecomImMessageFetcher();
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"botId\":\"bot1\",\"botSecret\":\"sec1\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new WecomCliTokenService(base() + "/cgi-bin/aibot/cli/get_cli_config"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "cliClient",
            new WecomCliClient(base()));

        String since = ImMessageTime.canonical("2026-11-18T09:00:00+08:00");
        String until = ImMessageTime.canonical("2026-11-18T11:00:00+08:00");
        fetcher.fetch("inst-w", "chat-1", since, until, 50);

        String[] req = null;
        for (String[] r : requests) {
            if (r[0].contains("/cli/chat/messages/list")) {
                req = r;
            }
        }
        assertThat(req).as("必须打到 /cli/chat/messages/list").isNotNull();

        // ① 信封：payload 的值必须是**字符串**（PIT-WC-022），不是对象
        JsonNode outer = JSON.readTree(req[2]);
        assertThat(outer.has("payload")).isTrue();
        assertThat(outer.get("payload").isTextual())
            .as("payload 必须是字符串化的业务 JSON（直接塞对象会被平台拒）").isTrue();

        JsonNode business = JSON.readTree(outer.get("payload").asText());
        assertThat(business.path("chat_id").asText()).isEqualTo("chat-1");
        assertThat(business.path("begin_time").asText())
            .as("企微时间格式是 yyyy-MM-dd HH:mm:ss（PIT-WC-028），不是毫秒")
            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(business.path("end_time").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("企微：消息项逐字映射（text/image），且 direction 一律 in、messageId 为 null")
    void wecomMessageMapping() throws Exception {
        WecomImMessageFetcher fetcher = new WecomImMessageFetcher();
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"botId\":\"bot1\",\"botSecret\":\"sec1\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new WecomCliTokenService(base() + "/cgi-bin/aibot/cli/get_cli_config"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "cliClient",
            new WecomCliClient(base()));

        List<ImMessageEntry> rows = fetcher.fetch("inst-w", "chat-1",
            ImMessageTime.canonical("2026-11-18T09:00:00+08:00"),
            ImMessageTime.canonical("2026-11-18T11:00:00+08:00"), 50);

        assertThat(rows).hasSize(2);
        ImMessageEntry first = rows.get(0);
        assertThat(first.msgType()).isEqualTo("text");
        assertThat(first.content()).isEqualTo("你好，明天几点上线？");
        assertThat(first.senderId()).isEqualTo("u_1");
        assertThat(first.senderName()).isEqualTo("张三");
        assertThat(first.sentAt()).as("send_time 必须归一化成规范形态")
            .isEqualTo("2026-11-18T10:00:00.000+08:00");
        assertThat(first.direction()).as("企微读不到 bot 自己发的消息 ⇒ 一律 in").isEqualTo("in");
        assertThat(first.origin()).isEqualTo("official");
        assertThat(first.messageId()).as("企微 ChatMessage 没有 messageId ⇒ 必须为 null").isNull();

        ImMessageEntry second = rows.get(1);
        assertThat(second.msgType()).isEqualTo("image");
        assertThat(second.mediaJson()).as("媒体引用要落进 media_json（只存引用，不存二进制）")
            .contains("\"kind\":\"image\"")
            .contains("shot.png");
    }

    @Test
    @DisplayName("企微：7 天窗口 —— window() 给出 7 天前的下界（超窗不是错误）")
    void wecomWindowIsSevenDays() {
        WecomImMessageFetcher fetcher = new WecomImMessageFetcher();
        ImFetchWindow w = fetcher.window();
        assertThat(w.limited()).isTrue();
        assertThat(w.note()).contains("7 天");
        // 下界必须是「7 天前」，且不能是未来
        java.time.Instant earliest = ImMessageTime.toInstant(w.earliestSince());
        assertThat(earliest).isNotNull();
        assertThat(earliest).isBefore(java.time.Instant.now());
        assertThat(java.time.Duration.between(earliest, java.time.Instant.now()).toDays()).isBetween(6L, 7L);
    }

    @Test
    @DisplayName("企微：since 超过 7 天 ⇒ 请求里的 begin_time 被 clamp 进 7 天窗口（否则 850016）")
    void wecomClampsBeginTimeIntoSevenDayWindow() throws Exception {
        WecomImMessageFetcher fetcher = newWecomFetcher();

        // since 远早于 7 天前（真机日志：code=850016 begin_time不能早于7天前）
        String since = ImMessageTime.canonical("2026-09-01T09:00:00+08:00");
        fetcher.fetch("inst-w", "chat-1", since, null, 50);

        assertInsideSevenDayWindow(capturedWecomBeginTime());
    }

    @Test
    @DisplayName("企微：since 为空（首次扫描）⇒ begin_time 同样被 clamp 进 7 天窗口")
    void wecomClampsBeginTimeWhenSinceMissing() throws Exception {
        WecomImMessageFetcher fetcher = newWecomFetcher();

        fetcher.fetch("inst-w", "chat-1", null, null, 50);

        assertInsideSevenDayWindow(capturedWecomBeginTime());
    }

    @Test
    @DisplayName("企微：40073（非法的 chat_id）⇒ 抛可识别的异常（统一错误码 + 平台码 40073）")
    void wecomInvalidChatIdThrowsIdentifiableError() {
        wecomErrorCode = 40073;
        WecomImMessageFetcher fetcher = newWecomFetcher();

        assertThatThrownBy(() -> fetcher.fetch("inst-w", "ZhengChangWei", null, null, 50))
            .isInstanceOf(ImMessageFetchException.class)
            .satisfies(e -> {
                ImMessageFetchException fe = (ImMessageFetchException) e;
                assertThat(fe.code())
                    .as("上层要能认出「这条会话压根拉不了」")
                    .isEqualTo(ImMessageFetchException.CODE_INVALID_CHAT_ID);
                assertThat(fe.platformCode()).isEqualTo("40073");
            })
            .hasMessageContaining("40073")
            .hasMessageContaining("ZhengChangWei");
    }

    // ═══════════════════════ 飞书 ═══════════════════════

    @Test
    @DisplayName("飞书：URL 参数逐字（container_id_type=chat / sort_type / page_size / 秒级 epoch），且带 Bearer 票")
    void feishuRequestParams() throws Exception {
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        String since = ImMessageTime.canonical("2026-11-18T09:00:00+08:00");
        String until = ImMessageTime.canonical("2026-11-18T11:00:00+08:00");
        fetcher.fetch("inst-f", "oc_abc", since, until, 50);

        String[] req = null;
        for (String[] r : requests) {
            if (r[0].contains("/open-apis/im/v1/messages")) {
                req = r;
            }
        }
        assertThat(req).as("必须打到 /open-apis/im/v1/messages").isNotNull();
        assertThat(req[1]).as("container_id_type 必填").contains("container_id_type=chat");
        assertThat(req[1]).contains("container_id=oc_abc");
        assertThat(req[1]).as("翻页排序固定").contains("sort_type=ByCreateTimeAsc");
        assertThat(req[1]).contains("page_size=50");

        long expectStart = ImMessageTime.toInstant(since).getEpochSecond();
        long expectEnd = ImMessageTime.toInstant(until).getEpochSecond();
        assertThat(req[1]).as("start_time 是**秒级** epoch").contains("start_time=" + expectStart);
        assertThat(req[1]).contains("end_time=" + expectEnd);

        assertThat(authHeaders).anyMatch(h -> h != null && h.startsWith("Bearer t-test"));
    }

    @Test
    @DisplayName("飞书：body.content 二次解析 + create_time 毫秒 + sender 映射 + app 身份算 out")
    void feishuMessageMapping() throws Exception {
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        List<ImMessageEntry> rows = fetcher.fetch("inst-f", "oc_abc",
            ImMessageTime.canonical("2026-11-18T09:00:00+08:00"),
            ImMessageTime.canonical("2026-11-18T11:00:00+08:00"), 50);

        assertThat(rows).hasSize(2);
        ImMessageEntry text = rows.get(0);
        assertThat(text.messageId()).isEqualTo("om_1");
        assertThat(text.msgType()).isEqualTo("text");
        assertThat(text.content()).as("body.content 是字符串化 JSON ⇒ 必须二次解析").isEqualTo("在的");
        assertThat(text.senderId()).isEqualTo("ou_1");
        assertThat(text.senderName()).as("授权人本人 ⇒ 用授权时存下的昵称补上（报文本身不带昵称）")
            .isEqualTo("郑昌蔚");
        assertThat(text.direction()).isEqualTo("in");
        assertThat(text.origin()).isEqualTo("official");
        assertThat(text.sentAt()).as("create_time 是毫秒 ⇒ 换算后必须对得上")
            .isEqualTo("2026-11-18T10:00:00.000+08:00");

        ImMessageEntry file = rows.get(1);
        assertThat(file.messageId()).isEqualTo("om_2");
        assertThat(file.content()).isEqualTo("需求.pdf");
        assertThat(file.mediaJson()).contains("\"kind\":\"file\"").contains("file_abc");
        assertThat(file.direction()).as("sender_type=app ⇒ 是我们自己发的").isEqualTo("out");
        assertThat(file.senderId()).as("id 是对象形态（入站事件）时也必须能取出来").isEqualTo("ou_bot");
        assertThat(file.senderName()).as("机器人发的消息用实例显示名，不是 cli_xxx").isEqualTo("智能bot");
    }

    @Test
    @DisplayName("飞书：平台业务错误（code≠0，即使 HTTP 200）必须抛异常，不能静默当空列表")
    void feishuBusinessErrorThrows() {
        failMessages = true;
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        assertThatThrownBy(() -> fetcher.fetch("inst-f", "oc_abc",
            ImMessageTime.canonical("2026-11-18T09:00:00+08:00"), null, 50))
            .isInstanceOf(ImMessageFetchException.class)
            .hasMessageContaining("99991672");
    }

    @Test
    @DisplayName("飞书：HTTP 400 也要解析并透出 body 的 code/msg（不再只剩 statusCode）")
    void feishuHttp400ExposesBodyCodeAndMsg() {
        // 真机形态：机器人不在群 ⇒ HTTP 400 + {"code":230002,"msg":"Bot is not in the group"}
        messagesHttpStatus = 400;
        messagesHttpBody = "{\"code\":230002,\"msg\":\"Bot is not in the group\"}";
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        assertThatThrownBy(() -> fetcher.fetch("inst-f", "oc_group",
            ImMessageTime.canonical("2026-11-18T09:00:00+08:00"), null, 50))
            .isInstanceOf(ImMessageFetchException.class)
            .satisfies(e -> {
                ImMessageFetchException fe = (ImMessageFetchException) e;
                assertThat(fe.platformCode())
                    .as("platformCode 要取飞书 body 里的业务码（不是 HTTP 400）")
                    .isEqualTo("230002");
                assertThat(fe.getMessage())
                    .as("异常消息要带上 body 的 code/msg")
                    .contains("HTTP 400")
                    .contains("230002")
                    .contains("Bot is not in the group")
                    .as("还要有人话原因（与发送路径共用同一份码表）")
                    .contains("机器人不在该会话");
            });
    }

    @Test
    @DisplayName("飞书：HTTP 400 且 body 不是 JSON ⇒ 退回 body 片段，不吞成空消息")
    void feishuHttp400NonJsonBodyFallsBackToSnippet() {
        messagesHttpStatus = 400;
        messagesHttpBody = "gateway error";
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        assertThatThrownBy(() -> fetcher.fetch("inst-f", "oc_group",
            ImMessageTime.canonical("2026-11-18T09:00:00+08:00"), null, 50))
            .isInstanceOf(ImMessageFetchException.class)
            .satisfies(e -> {
                ImMessageFetchException fe = (ImMessageFetchException) e;
                assertThat(fe.platformCode()).as("解析不出业务码 ⇒ 用 HTTP 状态码").isEqualTo("400");
                assertThat(fe.getMessage()).contains("HTTP 400").contains("gateway error");
            });
    }

    @Test
    @DisplayName("飞书：会话 id 不是 oc_（open_id 等）⇒ 返回空列表，不抛异常、不打 warn、不发请求")
    void feishuNonChatIdReturnsEmptyWithoutWarning() {
        FeishuImMessageFetcher fetcher = new FeishuImMessageFetcher(base());
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "channelService",
            channelService("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(fetcher, "tokenService",
            new FeishuTokenService(base()));

        // 真机日志里的 open_id 行（同一个单聊在 im_chat 里的另一行）
        String openId = "ou_431b32ee4a6e4aca23b78748054ad2c4";

        ch.qos.logback.classic.Logger feishuLogger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(FeishuImMessageFetcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        feishuLogger.addAppender(appender);
        List<ImMessageEntry> rows;
        try {
            rows = fetcher.fetch("inst-f", openId,
                ImMessageTime.canonical("2026-11-18T09:00:00+08:00"), null, 50);
        } catch (ImMessageFetchException e) {
            throw new AssertionError("非 chat_id 的会话 id 不该抛异常", e);
        } finally {
            feishuLogger.detachAppender(appender);
        }

        assertThat(rows).as("不支持的 id 形态 ⇒ 空列表").isEmpty();
        assertThat(appender.list)
            .as("这**不是**错误 ⇒ 不许打 warn（否则会污染 last_error、让用户以为出故障）")
            .noneMatch(ev -> ev.getLevel() == ch.qos.logback.classic.Level.WARN);
        assertThat(requests)
            .as("连请求都不该发出去（更不该打扰平台）")
            .noneMatch(r -> r[0].contains("/open-apis/im/v1/messages"));
    }

    // ═══════════════════════ 工厂 ═══════════════════════

    @Test
    @DisplayName("工厂：未知平台返回 Unsupported（不抛异常、不返回 null）")
    void factoryFallsBackToUnsupported() {
        ImMessageFetcherFactory factory = new ImMessageFetcherFactory(List.of());
        ImMessageFetcher f = factory.of("dingtalk");
        assertThat(f).isNotNull();
        assertThat(f.platform()).isEqualTo("dingtalk");
        assertThatThrownBy(() -> f.fetch("i", "c", null, null, 10))
            .isInstanceOf(ImMessageFetchException.class)
            .hasMessageContaining("暂不支持");
    }
}
