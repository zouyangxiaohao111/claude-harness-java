package com.nexusai.domain.im_channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞书显示名解析：<b>真名优先，拿不到降级尾号</b>（2026-11-23 用户裁决）。
 *
 * <p>用本地 {@link HttpServer} 桩，⛔ 不连真实飞书。钉住四件事：
 * ① 有权限 + open_id ⇒ 真名；② 无权限 ⇒ 解析阶段 0 次 contact 调用（只探一次）；
 * ③ 接口 500 ⇒ api_error；④ 缓存命中不重复调用。
 */
@DisplayName("飞书显示名解析 · 真名优先/尾号降级")
class FeishuChatNameResolverTest {

    private HttpServer server;
    private String base;

    /** open_id → 该次 contact 响应体（默认 code=0 带 name）。 */
    private final Map<String, Function<String, String>> contactBodies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> contactHits = new ConcurrentHashMap<>();

    private volatile String botBody = "{\"code\":0,\"data\":{\"bot\":{\"app_name\":\"智能bot\"}}}";

    private FeishuChatNameResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/contact/v3/users/", ex -> {
            String openId = lastSegment(ex);
            contactHits.computeIfAbsent(openId, k -> new AtomicInteger()).incrementAndGet();
            Function<String, String> f = contactBodies.get(openId);
            String body = f == null
                    ? "{\"code\":0,\"data\":{\"user\":{\"name\":\"名字-" + openId + "\"}}}"
                    : f.apply(openId);
            respond(ex, 200, body);
        });
        server.createContext("/open-apis/bot/v3/info", ex -> respond(ex, 200, botBody));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        resolver = new FeishuChatNameResolver();
        resolver.setOpenBase(base);
        resolver.setMinIntervalMs(0);   // 测试不限速
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("有权限 + ou_ 行 ⇒ 真名")
    void resolvesRealNameForOuRow() {
        assertThat(resolver.probeContacts("tok", "ou_probe"))
                .isEqualTo(FeishuChatNameResolver.ProbeStatus.GRANTED);

        FeishuChatNameResolver.Resolved r = resolver.resolveP2pName(
                "tok", "ou_abc", FeishuChatNameResolver.ProbeStatus.GRANTED, null);

        assertThat(r.hasName()).isTrue();
        assertThat(r.name()).isEqualTo("名字-ou_abc");
        assertThat(r.reason()).isEqualTo(FeishuChatNameResolver.Reason.OK);
    }

    @Test
    @DisplayName("无权限 + ou_ 行 ⇒ 解析阶段 0 次 contact 调用（只探一次）")
    void deniedMeansNoPerRowContactCalls() {
        contactBodies.put("ou_probe", id -> "{\"code\":41050,\"msg\":\"no user authority\"}");
        assertThat(resolver.probeContacts("tok", "ou_probe"))
                .isEqualTo(FeishuChatNameResolver.ProbeStatus.DENIED);
        int afterProbe = contactHits.get("ou_probe").get();

        FeishuChatNameResolver.Resolved r = resolver.resolveP2pName(
                "tok", "ou_target", FeishuChatNameResolver.ProbeStatus.DENIED, "智能bot");

        assertThat(r.hasName()).isFalse();
        assertThat(r.reason()).isEqualTo(FeishuChatNameResolver.Reason.NO_PERMISSION);
        // 只有探针那一次；解析阶段没有再打 contact
        assertThat(contactHits.get("ou_probe").get()).isEqualTo(afterProbe);
        assertThat(contactHits.get("ou_target")).isNull();
    }

    @Test
    @DisplayName("oc_ 私聊 = 与机器人的会话 ⇒ 用机器人名（无通讯录权限也照用）")
    void ocRowUsesBotNameEvenWithoutContactsPermission() {
        FeishuChatNameResolver.Resolved r = resolver.resolveP2pName(
                "tok", "oc_6ac651416a9d7ede", FeishuChatNameResolver.ProbeStatus.DENIED, "智能bot");

        assertThat(r.hasName()).isTrue();
        assertThat(r.name()).isEqualTo("智能bot");
        assertThat(contactHits.get("oc_6ac651416a9d7ede")).isNull();
    }

    @Test
    @DisplayName("有权限但 contact 返回 500 ⇒ api_error（降级尾号）")
    void apiErrorFallsBack() {
        contactBodies.put("ou_probe", id -> "{\"code\":0,\"data\":{\"user\":{\"name\":\"探测者\"}}}");
        contactBodies.put("ou_bad", id -> "___not_json___");
        assertThat(resolver.probeContacts("tok", "ou_probe"))
                .isEqualTo(FeishuChatNameResolver.ProbeStatus.GRANTED);

        FeishuChatNameResolver.Resolved r = resolver.resolveP2pName(
                "tok", "ou_bad", FeishuChatNameResolver.ProbeStatus.GRANTED, null);

        assertThat(r.hasName()).isFalse();
        assertThat(r.reason()).isEqualTo(FeishuChatNameResolver.Reason.API_ERROR);
    }

    @Test
    @DisplayName("缓存命中 ⇒ 第二次不再调用 contact")
    void cacheHitSkipsSecondCall() {
        FeishuChatNameResolver.Resolved first = resolver.userName("tok", "ou_cache");
        FeishuChatNameResolver.Resolved second = resolver.userName("tok", "ou_cache");

        assertThat(first.name()).isEqualTo("名字-ou_cache");
        assertThat(second.name()).isEqualTo("名字-ou_cache");
        assertThat(contactHits.get("ou_cache").get()).isEqualTo(1);
    }

    @Test
    @DisplayName("拿不到机器人名 ⇒ oc_ 行走尾号（no_bot_name），不硬查通讯录")
    void missingBotNameFallsBack() {
        FeishuChatNameResolver.Resolved r = resolver.resolveP2pName(
                "tok", "oc_chat", FeishuChatNameResolver.ProbeStatus.GRANTED, null);

        assertThat(r.hasName()).isFalse();
        assertThat(r.reason()).isEqualTo(FeishuChatNameResolver.Reason.NO_BOT_NAME);
        assertThat(contactHits.get("oc_chat")).isNull();
    }

    @Test
    @DisplayName("fetchBotName 读 data.bot.app_name")
    void fetchBotNameReadsAppName() {
        botBody = "{\"code\":0,\"data\":{\"bot\":{\"app_name\":\"智能bot\"}}}";
        assertThat(resolver.fetchBotName("tok")).isEqualTo("智能bot");
    }

    // ───────────── 桩工具 ─────────────

    private static String lastSegment(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
