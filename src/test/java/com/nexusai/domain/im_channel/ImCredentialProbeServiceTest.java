package com.nexusai.domain.im_channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「测试连接」真探测的边界（只指向本地 stub，⛔ 不连真实飞书）。
 *
 * <h2>WHY</h2>
 * 这条链路的价值就是**不假装成功**：凭证明明错了还说「连接成功」，用户会一路错到发消息。
 * 所以三条都钉死：成功要有机器人信息、凭证错要透传失败、缺字段不发网络请求。
 */
class ImCredentialProbeServiceTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"app_secret\":\"good\"")) {
                respond(ex, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-abc\",\"expire\":7200}");
            } else {
                // 真实飞书的错误也是 HTTP 200 + code!=0
                respond(ex, "{\"code\":10003,\"msg\":\"invalid param\"}");
            }
        });
        server.createContext("/open-apis/bot/v3/info", ex ->
                respond(ex, "{\"code\":0,\"msg\":\"ok\",\"bot\":{\"app_name\":\"测试机器人\"}}"));
        server.createContext("/open-apis/im/v1/chats", ex ->
                respond(ex, "{\"code\":0,\"data\":{\"items\":[{\"chat_id\":\"oc_1\"}],\"has_more\":true}}"));

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void 凭证有效_返回机器人名与可见会话数() {
        var r = new ImCredentialProbeService(base).probeFeishu("cli_ok1", "good");

        assertTrue(r.ok(), "换票成功就必须判成功");
        assertEquals("测试机器人", r.botName());
        assertEquals("1+", r.chatVisible(), "has_more=true 时要表达为 1+");
        assertTrue(r.message().contains("凭证有效"));
    }

    @Test
    void 凭证错误_透传失败而不是假装成功() {
        var r = new ImCredentialProbeService(base).probeFeishu("cli_bad1", "bad");

        assertFalse(r.ok(), "换票失败必须判失败");
        assertEquals("TOKEN_EXCHANGE_FAILED", r.code());
        assertFalse(r.message().contains("凭证有效"));
    }

    @Test
    void 缺字段_直接失败不发网络请求() {
        var r = new ImCredentialProbeService(base).probeFeishu("", "");

        assertFalse(r.ok());
        assertEquals("INVALID_CREDENTIAL", r.code());
    }
}
