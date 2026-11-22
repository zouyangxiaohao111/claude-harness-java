package com.nexusai.domain.im_channel.send;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.impl.WechatSendStrategy;
import com.nexusai.domain.im_channel.send.impl.WecomSendStrategy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议行为测试：用本地 stub server 抓真实发出的报文，验证「线上到底发了什么」。
 *
 * <h2>WHY 不能只测返回值</h2>
 * 「返回 SENT」和「发对了」是两件事。请求体少包一层 {@code payload}、或者把
 * {@code content} 写成对象而不是字符串，都会在真实平台上被拒 —— 而这些只看
 * 返回值/单元分支是发现不了的。这里把报文抓下来逐字段断言。
 *
 * <p>用 JDK 自带 {@link HttpServer}（不引第三方依赖），每个用例起一个临时端口，用完关掉。
 * ⛔ 不连真实平台：真实环境需要真凭证，且会产生副作用（真给用户发消息）。
 */
class ImSendStrategyProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final List<String> hits = new ArrayList<>();
    /** stub 的响应体（每个用例自己设）。 */
    private volatile String responseBody = "{}";

    private int port() {
        return server.getAddress().getPort();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (hits) {
                hits.add(exchange.getRequestURI().getPath());
            }
            lastPath.set(exchange.getRequestURI().toString());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] in = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(in, StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
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

    private int hits() {
        synchronized (hits) {
            return hits.size();
        }
    }

    // ═════════════════════ 企业微信 ═════════════════════

    @Test
    @DisplayName("企微：请求体必须包 payload 信封，业务 JSON 是其中的字符串")
    void wecomWrapsBusinessBodyInPayloadEnvelope() throws Exception {
        responseBody = "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"success\\\":true}\"}";
        WecomCliClient client = new WecomCliClient("http://127.0.0.1:" + port());
        WecomMessageApi api = new WecomMessageApi(client);

        api.sendText("cli-token-abc", "chatid-group-1", "你好");

        JsonNode sent = JSON.readTree(lastBody.get());
        assertThat(sent.has("payload")).as("PIT-WC-022：业务体必须包在 payload 里").isTrue();
        // payload 的值必须是**字符串**（序列化后的 JSON），不是对象
        assertThat(sent.get("payload").isTextual()).isTrue();
        JsonNode business = JSON.readTree(sent.get("payload").asText());
        assertThat(business.path("chat_id").asText()).isEqualTo("chatid-group-1");
        assertThat(business.path("msg_type").asText()).isEqualTo("text");
        assertThat(business.path("text").path("content").asText()).isEqualTo("你好");
        assertThat(lastAuth.get()).as("网关要 Bearer").isEqualTo("Bearer cli-token-abc");
    }

    @Test
    @DisplayName("企微：errcode=0 但 results_json 里 success=false ⇒ 必须当失败")
    void wecomTreatsSuccessFalseAsFailure() {
        responseBody = "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"success\\\":false}\"}";
        WecomCliClient client = new WecomCliClient("http://127.0.0.1:" + port());
        WecomMessageApi api = new WecomMessageApi(client);

        ImSendException e = org.junit.jupiter.api.Assertions.assertThrows(ImSendException.class,
                () -> api.sendText("t", "chatid", "hi"));

        assertThat(e.code()).isEqualTo("IM_SEND_PLATFORM_ERROR");
        assertThat(e.getMessage()).contains("success=false");
    }

    @Test
    @DisplayName("企微：853008（不在最近会话）⇒ 明确原因，不可重试")
    void wecomMapsNotRecentSession() {
        responseBody = "{\"errcode\":853008,\"errmsg\":\"not in recent session\"}";
        WecomCliClient client = new WecomCliClient("http://127.0.0.1:" + port());
        WecomMessageApi api = new WecomMessageApi(client);

        ImSendException e = org.junit.jupiter.api.Assertions.assertThrows(ImSendException.class,
                () -> api.sendText("t", "chatid", "hi"));

        assertThat(e.platformCode()).isEqualTo("853008");
        assertThat(e.getMessage()).contains("最近会话");
        assertThat(e.retryable()).isFalse();
    }

    // ═════════════════════ 微信 ═════════════════════

    private ImSendContext wechatCtx(String token) {
        String credential = "{\"baseUrl\":\"http://127.0.0.1:" + port() + "\",\"botToken\":\"bt\"}";
        return new ImSendContext("inst-wx", "wechat", "wxid_peer", "p2p", credential, token);
    }

    @Test
    @DisplayName("微信：报文结构（msg 包裹 + context_token + item_list）")
    void wechatRequestBodyShape() throws Exception {
        responseBody = "{\"message_id\":\"msg-1\"}";
        WechatSendStrategy s = new WechatSendStrategy();

        ImSendResult r = s.send(wechatCtx("ctx-token-xyz"), "hello");

        assertThat(r.status()).as("有 message_id 才算成功").isEqualTo(ImSendResult.Status.SENT);
        assertThat(lastPath.get()).contains("/ilink/bot/sendmessage");

        JsonNode root = JSON.readTree(lastBody.get());
        JsonNode msg = root.path("msg");
        assertThat(msg.path("to_user_id").asText()).isEqualTo("wxid_peer");
        assertThat(msg.path("context_token").asText()).isEqualTo("ctx-token-xyz");
        assertThat(msg.path("message_type").asInt()).isEqualTo(2);
        assertThat(msg.path("message_state").asInt()).isEqualTo(2);
        assertThat(msg.path("item_list").get(0).path("type").asInt()).isEqualTo(1);
        assertThat(msg.path("item_list").get(0).path("text_item").path("text").asText()).isEqualTo("hello");
        assertThat(root.has("base_info")).as("该字段必须存在").isTrue();
    }

    @Test
    @DisplayName("微信：响应无 message_id ⇒ 必须失败（⛔ 不能拿 client_id 冒充成功）")
    void wechatFailsWhenMessageIdMissing() {
        responseBody = "{\"client_id\":\"nexusai_123\"}";
        WechatSendStrategy s = new WechatSendStrategy();

        ImSendResult r = s.send(wechatCtx("ctx-token"), "hi");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_SEND_PLATFORM_ERROR");
        assertThat(r.reason()).contains("message_id");
    }

    @Test
    @DisplayName("微信：没有 context_token ⇒ SKIPPED 且一个请求都不发")
    void wechatWithoutTokenSendsNothing() {
        WechatSendStrategy s = new WechatSendStrategy();

        ImSendResult r = s.send(wechatCtx(null), "hi");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(r.errorCode()).isEqualTo("IM_REPLY_TOKEN_MISSING");
        assertThat(hits()).as("缺 token 时绝不能发请求（会发出空白消息）").isZero();
    }

    @Test
    @DisplayName("微信：平台返回 ret=-14 ⇒ 带平台码失败")
    void wechatMapsPlatformErrorCode() {
        responseBody = "{\"ret\":-14,\"errmsg\":\"session dead\"}";
        WechatSendStrategy s = new WechatSendStrategy();

        ImSendResult r = s.send(wechatCtx("ctx"), "hi");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.reason()).contains("-14");
    }

    // ═════════════════════ 企微策略层（换票失败路径） ═════════════════════

    @Test
    @DisplayName("企微：凭证非法 JSON ⇒ IM_CREDENTIAL_INVALID，且不发请求、不 NPE")
    void wecomInvalidCredentialDoesNotThrowNpe() {
        WecomSendStrategy s = new WecomSendStrategy(
                new com.nexusai.domain.im_channel.cli.WecomCliTokenService(),
                new WecomMessageApi(new WecomCliClient("http://127.0.0.1:" + port())),
                new com.nexusai.domain.im_channel.cli.WecomMediaApi(
                        new WecomCliClient("http://127.0.0.1:" + port())));

        ImSendResult r = s.send(new ImSendContext("i", "wecom", "chatid", "group", "not-json", null), "hi");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_CREDENTIAL_INVALID");
        assertThat(hits()).isZero();
    }

    @Test
    @DisplayName("企微：凭证缺 botSecret ⇒ 明确失败，不发请求")
    void wecomMissingSecret() {
        WecomSendStrategy s = new WecomSendStrategy(
                new com.nexusai.domain.im_channel.cli.WecomCliTokenService(),
                new WecomMessageApi(new WecomCliClient("http://127.0.0.1:" + port())),
                new com.nexusai.domain.im_channel.cli.WecomMediaApi(
                        new WecomCliClient("http://127.0.0.1:" + port())));

        ImSendResult r = s.send(new ImSendContext("i", "wecom", "chatid", "group",
                "{\"botId\":\"b1\"}", null), "hi");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_CREDENTIAL_INVALID");
        assertThat(r.reason()).contains("botSecret");
        assertThat(hits()).isZero();
    }
}
