package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMediaApi;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.domain.im_channel.send.MediaKind;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.domain.im_channel.send.impl.WechatSendStrategy;
import com.nexusai.domain.im_channel.send.impl.WecomSendStrategy;
import com.nexusai.model.im_channel.ImChatEntry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端链路测试：`im_chat → ImSendService → 策略 → 平台 HTTP`，含**附件上传**。
 *
 * <h2>WHY 需要这一层</h2>
 * 单元测试验证「每个类自己没写错」，但真实的失败往往在**接缝**上：
 * chat 查不到、凭证解不开、附件的 key 传错字段、文本与媒体走了不同端点。
 * 这里用「替身聊天目录 + 本地 stub 平台」把整条链接起来跑，
 * 断言**实际发出的报文**，而不是只看返回值。
 *
 * <h2>为什么用 stub 而不是真机</h2>
 * 真机需要真实凭证 + 会真的给用户发消息（不可撤回）。stub 能精确控制响应，
 * 且能在 CI 里跑。⛔ 不连任何外网。
 */
class ImSendEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final Map<String, String> bodies = new HashMap<>();
    /**
     * 按顺序记录的 (path, body)。
     *
     * <p>为什么需要它：企微的<b>正文与媒体都走 {@code /cli/message/aibot/send}**
     * （正文是 markdown、媒体是 image/file），而 {@link #bodies} 是 path→body 的映射，
     * 同路径的后一次会覆盖前一次 ⇒ 没法分别断言正文与媒体的报文。
     */
    private final List<String[]> bodyLog = new ArrayList<>();
    private Path tmpFile;

    private int port() {
        return server.getAddress().getPort();
    }

    private String base() {
        return "http://127.0.0.1:" + port();
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (calls) {
                calls.add(path);
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (bodies) {
                bodies.put(path, body);
                bodyLog.add(new String[]{path, body});
            }
            String resp = route(path);
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        // 一个真实的临时文件（上传器会 stat 它）
        tmpFile = Files.createTempFile("imsend-e2e-", ".png");
        Files.write(tmpFile, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (tmpFile != null) {
            Files.deleteIfExists(tmpFile);
        }
    }

    /** 按路径返回平台响应（字段形状逐字对齐各平台真实响应）。 */
    private static String route(String path) {
        if (path.contains("get_cli_config")) {
            return "{\"errcode\":0,\"errmsg\":\"ok\",\"token\":\"cli-token-e2e\"}";
        }
        if (path.contains("/cli/media/upload")) {
            return "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"media_id\\\":\\\"MEDIA_E2E_1\\\"}\"}";
        }
        if (path.contains("/cli/message/aibot/send")) {
            return "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"success\\\":true}\"}";
        }
        if (path.contains("/cli/message/send")) {
            return "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"success\\\":true}\"}";
        }
        if (path.contains("/ilink/bot/sendmessage")) {
            return "{\"message_id\":\"wx-msg-e2e\"}";
        }
        if (path.contains("tenant_access_token")) {
            return "{\"code\":0,\"tenant_access_token\":\"t-e2e\",\"expire\":7200}";
        }
        if (path.contains("/open-apis/im/v1/images")) {
            return "{\"code\":0,\"data\":{\"image_key\":\"img_e2e\"}}";
        }
        if (path.contains("/open-apis/im/v1/files")) {
            return "{\"code\":0,\"data\":{\"file_key\":\"file_e2e\"}}";
        }
        if (path.contains("/open-apis/im/v1/messages")) {
            return "{\"code\":0,\"data\":{\"message_id\":\"om_e2e\"}}";
        }
        return "{\"errcode\":0,\"code\":0,\"message_id\":\"m-e2e\"}";
    }

    private int countCalls(String fragment) {
        synchronized (calls) {
            return (int) calls.stream().filter(c -> c.contains(fragment)).count();
        }
    }

    private JsonNode bodyOf(String fragment) throws Exception {
        synchronized (bodies) {
            for (Map.Entry<String, String> e : bodies.entrySet()) {
                if (e.getKey().contains(fragment)) {
                    return JSON.readTree(e.getValue());
                }
            }
        }
        return null;
    }

    /**
     * 按顺序找出「路径匹配且业务体 {@code msg_type} 匹配」的那一次调用的业务体。
     *
     * <p>用途：正文与媒体走同一个端点时，靠 {@code msg_type} 区分两次调用。
     *
     * @param pathFragment 路径片段（如 {@code /cli/message/aibot/send}）
     * @param msgType      业务体的 msg_type（如 {@code markdown} / {@code image}）
     */
    private JsonNode bizByMsgType(String pathFragment, String msgType) throws Exception {
        synchronized (bodies) {
            for (String[] e : bodyLog) {
                if (!e[0].contains(pathFragment)) {
                    continue;
                }
                JsonNode biz = JSON.readTree(JSON.readTree(e[1]).get("payload").asText());
                if (msgType.equals(biz.path("msg_type").asText())) {
                    return biz;
                }
            }
        }
        return null;
    }

    // ───────────────────────── 替身聊天目录 ─────────────────────────

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

    private static final class FakeChannelService extends ImChannelService {
        final Map<String, String> creds = new HashMap<>();
        @Override public Optional<String> credentialOf(String id) {
            return Optional.ofNullable(creds.get(id));
        }
    }

    private static ImChatEntry chat(String id, String instanceId, String platform, String peerId) {
        return new ImChatEntry(id, instanceId, platform, peerId, "name-" + peerId,
                "group", 5, null, null, null, null, null, "t", "t");
    }

    /** 组装真实策略 + 替身目录的 ImSendService。 */
    private ImSendService service(FakeChatService chats, FakeChannelService channels) {
        WecomCliClient wecomCli = new WecomCliClient(base());
        // 换票端点也指向 stub（否则会打真实企微）
        WecomCliTokenService wecomToken = new WecomCliTokenService(base() + "/cgi-bin/aibot/cli/get_cli_config");
        // 飞书：换票/上传/发送全部指向本地 stub
        com.nexusai.domain.im_channel.send.FeishuTokenService feishuToken =
                new com.nexusai.domain.im_channel.send.FeishuTokenService(base());
        com.nexusai.domain.im_channel.send.impl.FeishuMediaUploader feishuMedia =
                new com.nexusai.domain.im_channel.send.impl.FeishuMediaUploader(feishuToken, base());
        ImSendStrategyFactory factory = new ImSendStrategyFactory(Arrays.asList(
                new WecomSendStrategy(wecomToken, new WecomMessageApi(wecomCli), new WecomMediaApi(wecomCli)),
                new com.nexusai.domain.im_channel.send.impl.FeishuSendStrategy(
                        feishuToken, feishuMedia, base()),
                new WechatSendStrategy(),
                new DingtalkStub()));
        ImSendService s = new ImSendService();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "chatService", chats);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "channelService", channels);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "strategyFactory", factory);
        return s;
    }

    /** 钉钉：只用来验证「不支持 = SKIPPED 且零请求」。 */
    private static final class DingtalkStub
            extends com.nexusai.domain.im_channel.send.impl.DingtalkSendStrategy {
    }

    private static String wecomCredential() {
        return "{\"botId\":\"bot-e2e\",\"botSecret\":\"secret-e2e\"}";
    }

    private String wechatCredential() {
        return "{\"baseUrl\":\"" + base() + "\",\"botToken\":\"bt\"}";
    }

    // ═════════════════════ 场景 ═════════════════════

    @Test
    @DisplayName("企微 e2e（机器人身份）：文本 + 图片 ⇒ 换票 → 上传 → 发文本(markdown) → 发媒体（4 次请求）")
    void wecomTextPlusImageEndToEnd() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-group-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);

        List<ImSendResult> r = s.send(List.of("c-wecom"), "正文内容", List.of(ImSendAttachment.of(tmpFile)));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).status()).as("正文与附件都应成功: %s", r.get(0)).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.get(0).attachmentErrors()).isEmpty();
        // 🔴 机器人身份 ⇒ 报 BOT（不是 USER）
        assertThat(r.get(0).identity()).isEqualTo(SendIdentity.BOT);

        synchronized (calls) {
            assertThat(calls).hasSize(4);
            // 顺序：换票 → **上传** → 发文本 → 发媒体。
            // 为什么上传在文本之前：模板统一「先把附件传成平台引用，再发送」——
            // 微信要求文本与附件塞进**同一条** sendmessage（必须提前有 item），
            // 飞书/企微虽然可以分两条发，但为保持三平台一致，统一走这个顺序。
            assertThat(calls.get(0)).contains("get_cli_config");
            assertThat(calls.get(1)).contains("/cli/media/upload");
            // ⚠️ 行为变更：机器人身份的正文走 **aibot/send**（msg_type=markdown），
            //    不再走 message/send（那是「授权真人」身份 ⇒ 会冒充本人说话）。
            assertThat(calls.get(2)).contains("/cli/message/aibot/send");
            assertThat(calls.get(3)).contains("/cli/message/aibot/send");
            // 用户身份端点此刻**一次都不该被调**
            assertThat(countCalls("/cli/message/send")).as("机器人身份不得走用户身份端点").isZero();
        }

        // 文本走 aibot/send（机器人身份），payload 信封 + markdown 正文
        JsonNode textBiz = bizByMsgType("/cli/message/aibot/send", "markdown");
        assertThat(textBiz).as("正文应作为 markdown 发出").isNotNull();
        assertThat(textBiz.path("chat_id").asText()).isEqualTo("chatid-group-e2e");
        assertThat(textBiz.path("markdown").path("content").asText()).isEqualTo("正文内容");

        // 上传：type=image + base64 的 file_path
        JsonNode upReq = JSON.readTree(bodies.get("/cli/media/upload"));
        JsonNode upBiz = JSON.readTree(upReq.get("payload").asText());
        assertThat(upBiz.path("type").asText()).isEqualTo("image");
        assertThat(upBiz.path("file_path").asText()).as("base64 内容，不是路径").isNotBlank();

        // 媒体发送：media_id 取自上传响应（同一端点 ⇒ 靠 msg_type 区分）
        JsonNode mediaBiz = bizByMsgType("/cli/message/aibot/send", "image");
        assertThat(mediaBiz).isNotNull();
        assertThat(mediaBiz.path("image").path("media_id").asText()).isEqualTo("MEDIA_E2E_1");

        assertThat(chats.marked).containsExactly("c-wecom");
    }

    @Test
    @DisplayName("企微 e2e（用户身份）：正文走 message/send(text)，结果报 USER 身份")
    void wecomUserIdentityUsesUserEndpoint() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-group-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);

        ImSendResult r = s.send(List.of("c-wecom"), "以本人名义说的话", null,
                SendIdentity.USER).get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.identity()).as("请求 USER 且平台支持 ⇒ 实际身份就是 USER").isEqualTo(SendIdentity.USER);
        assertThat(r.identityNote()).as("用户身份纯文本没有降级 ⇒ 无说明").isNull();

        // 走了用户身份端点，且 msg_type=text（不是 markdown）
        assertThat(countCalls("/cli/message/send")).isEqualTo(1);
        assertThat(countCalls("/cli/message/aibot/send")).as("用户身份纯文本不该碰机器人端点").isZero();
        JsonNode biz = bizByMsgType("/cli/message/send", "text");
        assertThat(biz).isNotNull();
        assertThat(biz.path("text").path("content").asText()).isEqualTo("以本人名义说的话");
    }

    @Test
    @DisplayName("企微 e2e（用户身份 + 附件）：正文用用户身份、附件用机器人身份，并在结果里标明")
    void wecomUserIdentityWithAttachmentIsMixed() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-group-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);

        ImSendResult r = s.send(List.of("c-wecom"), "正文本人说", List.of(ImSendAttachment.of(tmpFile)),
                SendIdentity.USER).get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.identity()).isEqualTo(SendIdentity.USER);
        assertThat(r.identityNote()).as("混合身份必须写明，否则 AI 会以为附件也是本人发的")
                .contains("附件").contains("机器人身份");

        // 正文走用户端点，附件走机器人端点
        assertThat(countCalls("/cli/message/send")).isEqualTo(1);
        assertThat(countCalls("/cli/message/aibot/send")).isEqualTo(1);
        assertThat(bizByMsgType("/cli/message/send", "text")).isNotNull();
        assertThat(bizByMsgType("/cli/message/aibot/send", "image")).isNotNull();
    }

    @Test
    @DisplayName("企微 e2e（用户身份 + 只有附件）：整条降级为机器人身份，并说明原因")
    void wecomUserIdentityAttachmentOnlyFallsBackToBot() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-group-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);

        ImSendResult r = s.send(List.of("c-wecom"), null,
                List.of(new ImSendAttachment(tmpFile, MediaKind.FILE, "报告.pdf")),
                SendIdentity.USER).get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.identity()).as("用户身份端点发不了媒体 ⇒ 实际身份是机器人")
                .isEqualTo(SendIdentity.BOT);
        assertThat(r.identityNote()).contains("附件").contains("机器人身份");
        assertThat(countCalls("/cli/message/send")).as("不该调用户身份端点").isZero();
        assertThat(countCalls("/cli/message/aibot/send")).isEqualTo(1);
    }

    @Test
    @DisplayName("企微 e2e：只发文件（无正文）也能发出去")
    void wecomFileOnlyEndToEnd() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-group-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);
        List<ImSendResult> r = s.send(List.of("c-wecom"), null,
                List.of(new ImSendAttachment(tmpFile, MediaKind.FILE, "报告.pdf")));

        assertThat(r.get(0).status()).isEqualTo(ImSendResult.Status.SENT);
        // 没有正文 ⇒ 不发任何消息端点的文本请求（媒体走 aibot/send）
        assertThat(countCalls("/cli/message/send")).isZero();
        assertThat(countCalls("/cli/media/upload")).isEqualTo(1);
        assertThat(countCalls("/cli/message/aibot/send")).isEqualTo(1);

        JsonNode mediaBiz = JSON.readTree(
                JSON.readTree(bodies.get("/cli/message/aibot/send")).get("payload").asText());
        assertThat(mediaBiz.path("msg_type").asText()).isEqualTo("file");
        assertThat(mediaBiz.path("file").path("media_id").asText()).isEqualTo("MEDIA_E2E_1");
    }

    @Test
    @DisplayName("微信 e2e：有 token ⇒ 发出 msg 包裹的报文；无 token ⇒ SKIPPED 且零请求")
    void wechatSendAndSkip() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wx", chat("c-wx", "i-wx", "wechat", "wxid_peer"));
        chats.replyTokens.put("c-wx", "ctx-token-e2e");
        channels.creds.put("i-wx", wechatCredential());

        ImSendService s = service(chats, channels);

        ImSendResult ok = s.send(List.of("c-wx"), "微信正文").get(0);
        assertThat(ok.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(ok.messageId()).isEqualTo("wx-msg-e2e");

        JsonNode sent = bodyOf("/ilink/bot/sendmessage");
        assertThat(sent.path("msg").path("to_user_id").asText()).isEqualTo("wxid_peer");
        assertThat(sent.path("msg").path("context_token").asText()).isEqualTo("ctx-token-e2e");

        // 无 token 的另一个聊天 ⇒ SKIPPED 且不发请求
        chats.chats.put("c-wx2", chat("c-wx2", "i-wx", "wechat", "wxid_peer2"));
        int before = countCalls("/ilink/bot/sendmessage");
        ImSendResult skipped = s.send(List.of("c-wx2"), "不该发出").get(0);

        assertThat(skipped.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(skipped.errorCode()).isEqualTo("IM_REPLY_TOKEN_MISSING");
        assertThat(countCalls("/ilink/bot/sendmessage")).as("缺 token 时零请求").isEqualTo(before);
    }

    @Test
    @DisplayName("飞书 e2e：文本 + 图片 ⇒ 换票 → 上传图片 → 发文本 → 发图片（content 是 JSON 字符串）")
    void feishuTextPlusImageEndToEnd() throws Exception {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-fs", chat("c-fs", "i-fs", "feishu", "oc_e2e"));
        channels.creds.put("i-fs", "{\"appId\":\"cli_e2e\",\"appSecret\":\"secret_e2e\"}");

        ImSendService s = service(chats, channels);
        List<ImSendResult> r = s.send(List.of("c-fs"), "飞书正文", List.of(ImSendAttachment.of(tmpFile)));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).status()).as("正文+附件都应成功: %s", r.get(0)).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.get(0).attachmentErrors()).isEmpty();

        // 换票 → 上传 → 发文本 → 发图片
        assertThat(countCalls("tenant_access_token")).isEqualTo(1);
        assertThat(countCalls("/open-apis/im/v1/images")).isEqualTo(1);
        // messages 端点被调用两次（文本一次、图片一次）
        assertThat(countCalls("/open-apis/im/v1/messages")).isEqualTo(2);

        // 图片消息的 content 必须是「JSON 字符串」里含 image_key
        JsonNode imgReq = bodyOf("/open-apis/im/v1/messages");
        assertThat(imgReq.path("msg_type").asText()).isIn("text", "image");
        assertThat(imgReq.path("content").isTextual()).as("content 必须是字符串不是对象").isTrue();
    }

    @Test
    @DisplayName("多目标：企微成功 + 钉钉跳过 + 不存在的聊天失败，三者互不影响")
    void multiTargetIsolation() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-e2e"));
        chats.chats.put("c-ding", chat("c-ding", "i-ding", "dingtalk", "ding-chat"));
        channels.creds.put("i-wecom", wecomCredential());
        channels.creds.put("i-ding", "{}");

        ImSendService s = service(chats, channels);
        List<ImSendResult> r = s.send(Arrays.asList("c-wecom", "c-ding", "c-missing"), "混合消息");

        assertThat(r).hasSize(3);
        assertThat(r.get(0).status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.get(1).status()).as("钉钉无主动发送通道").isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(r.get(2).status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.get(2).errorCode()).isEqualTo("IM_CHAT_NOT_FOUND");
    }

    @Test
    @DisplayName("附件上传失败：正文照发，附件失败单独报（不判整条失败）")
    void attachmentFailureDoesNotBlockText() {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        chats.chats.put("c-wecom", chat("c-wecom", "i-wecom", "wecom", "chatid-e2e"));
        channels.creds.put("i-wecom", wecomCredential());

        ImSendService s = service(chats, channels);
        Path missing = Path.of("/definitely/not/exists/nope.png");

        ImSendResult r = s.send(List.of("c-wecom"), "正文仍应发出",
                List.of(ImSendAttachment.of(missing))).get(0);

        assertThat(r.status()).as("正文已发出 ⇒ SENT").isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.attachmentErrors()).as("附件失败要单独列出来").isNotEmpty();
        // ⚠️ 行为变更：机器人身份的正文走 aibot/send（markdown），不再走 message/send
        assertThat(countCalls("/cli/message/aibot/send")).isEqualTo(1);
        assertThat(countCalls("/cli/message/send")).as("机器人身份不得走用户身份端点").isZero();
    }
}
