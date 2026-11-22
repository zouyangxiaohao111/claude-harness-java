package com.nexusai.domain.im_channel;

import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书「本人所在全部群」同步（user_access_token）。
 *
 * <p>用本地 {@link HttpServer} 桩，⛔ 不连真实飞书。重点钉住四件事：
 * ① 失败必须抛业务异常，不能 total=0 假成功；② 请求头必须带<b>用户</b>令牌；
 * ③ 拉不到消息也要建行（计 skipped）；④ 已存在的行走 updated 而不是重复插入。
 */
@DisplayName("飞书同步 · 本人所在群(user token)")
class ImChatSyncServiceUserChatsTest {

    private static final String INSTANCE_ID = "imc-1";
    private static final ImInstanceEntry INSTANCE = new ImInstanceEntry(
            INSTANCE_ID, "feishu", "飞书", "connected", null, null, null, "t", "t");

    private HttpServer server;
    private String base;

    private volatile int chatsStatus = 200;
    private volatile String chatsBody = "{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}";
    private volatile int msgStatus = 200;
    private volatile Function<String, String> msgBody = id -> "{\"code\":0,\"data\":{\"items\":[]}}";

    private final AtomicReference<String> chatsAuth = new AtomicReference<>();
    private final AtomicReference<String> msgAuth = new AtomicReference<>();
    private final AtomicInteger chatsHits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/im/v1/chats", ex -> {
            chatsHits.incrementAndGet();
            chatsAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, chatsStatus, chatsBody);
        });
        server.createContext("/open-apis/im/v1/messages", ex -> {
            msgAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, msgStatus, msgBody.apply(queryParam(ex.getRequestURI().getRawQuery(), "container_id")));
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ───────────── 用例 ─────────────

    @Test
    @DisplayName("没有 user token ⇒ 业务异常，不写库、不打平台")
    void noTokenThrows() {
        ImChannelService channel = channel(null, false);
        ImChatService chats = mock(ImChatService.class);

        assertThatThrownBy(() -> service(channel, chats).syncFeishuUserChats(INSTANCE_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("未授权本人身份");

        verify(chats, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
        assertThat(chatsHits.get()).isZero();
    }

    @Test
    @DisplayName("token 过期且续期失败 ⇒ 明确提示重新授权（不是「未授权」）")
    void expiredNoRefreshThrows() {
        ImChannelService channel = channel(null, true);
        ImChatService chats = mock(ImChatService.class);

        assertThatThrownBy(() -> service(channel, chats).syncFeishuUserChats(INSTANCE_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("重新")
                .hasMessageContaining("授权");
        verify(chats, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("2 个群各 1 条消息 ⇒ created=2、预览/时间正确、请求头是用户令牌")
    void twoChatsWithMessages() {
        chatsBody = "{\"code\":0,\"data\":{\"items\":["
                + "{\"chat_id\":\"oc_a\",\"name\":\"群A\",\"chat_mode\":\"group\"},"
                + "{\"chat_id\":\"oc_b\",\"name\":\"群B\",\"chat_mode\":\"group\"}],"
                + "\"has_more\":false}}";
        msgBody = id -> messageJson("oc_a".equals(id) ? "你好A" : "你好B", 1730000000000L);
        ImChatService chats = mock(ImChatService.class);

        var result = service(channel("user-tok", false), chats).syncFeishuUserChats(INSTANCE_ID);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isZero();
        verify(chats).upsert(eq(INSTANCE_ID), eq("feishu"), eq("oc_a"), eq("群A"), eq("group"),
                isNull(), eq("你好A"), argThat(s -> s != null && !s.isBlank()),
                eq(ImChatService.SOURCE_LISTED));
        verify(chats).upsert(eq(INSTANCE_ID), eq("feishu"), eq("oc_b"), eq("群B"), eq("group"),
                isNull(), eq("你好B"), argThat(s -> s != null && !s.isBlank()),
                eq(ImChatService.SOURCE_LISTED));
        // ⭐ 变异探针：换成 tenant token ⇒ 这两条断言必须红
        assertThat(chatsAuth.get()).isEqualTo("Bearer user-tok");
        assertThat(msgAuth.get()).isEqualTo("Bearer user-tok");
    }

    @Test
    @DisplayName("已存在的行 ⇒ updated 计数，不重复插入")
    void existingRowsUpdated() {
        chatsBody = "{\"code\":0,\"data\":{\"items\":["
                + "{\"chat_id\":\"oc_a\",\"name\":\"群A\",\"chat_mode\":\"group\"},"
                + "{\"chat_id\":\"oc_b\",\"name\":\"群B\",\"chat_mode\":\"group\"}],"
                + "\"has_more\":false}}";
        msgBody = id -> messageJson("x", 1730000000000L);
        ImChatService chats = mock(ImChatService.class);
        when(chats.findByInstanceAndPeer(INSTANCE_ID, "oc_a")).thenReturn(Optional.of(chat("oc_a")));
        when(chats.findByInstanceAndPeer(INSTANCE_ID, "oc_b")).thenReturn(Optional.of(chat("oc_b")));

        var result = service(channel("user-tok", false), chats).syncFeishuUserChats(INSTANCE_ID);

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(2);
        verify(chats, times(2)).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("消息拉取失败但群列表成功 ⇒ 行仍创建、计 skipped，整体仍成功")
    void messageFailureStillCreatesRow() {
        chatsBody = "{\"code\":0,\"data\":{\"items\":["
                + "{\"chat_id\":\"oc_a\",\"name\":\"群A\",\"chat_mode\":\"group\"},"
                + "{\"chat_id\":\"oc_b\",\"name\":\"群B\",\"chat_mode\":\"group\"}],"
                + "\"has_more\":false}}";
        msgStatus = 500;
        ImChatService chats = mock(ImChatService.class);

        var result = service(channel("user-tok", false), chats).syncFeishuUserChats(INSTANCE_ID);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        verify(chats).upsert(eq(INSTANCE_ID), eq("feishu"), eq("oc_a"), eq("群A"), eq("group"),
                isNull(), isNull(), isNull(), eq(ImChatService.SOURCE_LISTED));
    }

    @Test
    @DisplayName("scope 不足(99991672) ⇒ 透传错误码 + 中文解释，不写库")
    void scopeErrorPassesCode() {
        chatsBody = "{\"code\":99991672,\"msg\":\"Access denied. One of the following scopes is required: [im:chat:read]\"}";
        ImChatService chats = mock(ImChatService.class);

        assertThatThrownBy(() -> service(channel("user-tok", false), chats).syncFeishuUserChats(INSTANCE_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("99991672")
                .hasMessageContaining("scope");
        verify(chats, never()).upsert(anyString(), anyString(), anyString(), any(),
                anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("平台 5xx ⇒ BadGateway（不伪装成空列表）")
    void serverErrorThrows() {
        chatsStatus = 500;
        ImChatService chats = mock(ImChatService.class);

        assertThatThrownBy(() -> service(channel("user-tok", false), chats).syncFeishuUserChats(INSTANCE_ID))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("HTTP 500");
    }

    // ───────────── 工具 ─────────────

    private ImChatSyncService service(ImChannelService channel, ImChatService chats) {
        ImChatSyncService svc = new ImChatSyncService();
        ReflectionTestUtils.setField(svc, "channelService", channel);
        ReflectionTestUtils.setField(svc, "chatService", chats);
        ReflectionTestUtils.setField(svc, "feishuOpenBase", base);
        return svc;
    }

    private static ImChannelService channel(String token, boolean rawTokenExists) {
        ImChannelService channel = mock(ImChannelService.class);
        when(channel.list()).thenReturn(List.of(INSTANCE));
        when(channel.userTokenOf(INSTANCE_ID))
                .thenReturn(token == null ? Optional.empty() : Optional.of(token));
        when(channel.hasUserToken(INSTANCE_ID)).thenReturn(rawTokenExists);
        return channel;
    }

    private static ImChatEntry chat(String peerId) {
        return new ImChatEntry("c-" + peerId, INSTANCE_ID, "feishu", peerId, "群" + peerId, "group",
                null, null, null, null, null, null, "t", "t");
    }

    private static String messageJson(String text, long epochMillis) {
        String content = ("{\"text\":\"" + text + "\"}").replace("\"", "\\\"");
        return "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[{"
                + "\"message_id\":\"om_1\",\"msg_type\":\"text\",\"create_time\":\"" + epochMillis + "\","
                + "\"body\":{\"content\":\"" + content + "\"}}]}}";
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && key.equals(pair.substring(0, i))) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
