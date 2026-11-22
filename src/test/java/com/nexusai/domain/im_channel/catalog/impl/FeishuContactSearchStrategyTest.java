package com.nexusai.domain.im_channel.catalog.impl;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImContactCandidate;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 飞书通讯录搜索（{@link FeishuContactSearchStrategy}）的行为验证。
 *
 * <h2>WHY 这些断言值得存在</h2>
 * <ul>
 *   <li><b>它是「拉列表 + 本地过滤」，不是服务端搜索</b>：飞书的按关键词搜要
 *       {@code user_access_token}（OAuth），本仓只有 {@code tenant_access_token}。
 *       所以「过滤规则」必须由测试钉住，否则改一句话就可能让「明明有这个人却搜不到」。</li>
 *   <li><b>平台拒绝不能伪装成「通讯录里没人」</b>：{@code code != 0} 必须抛异常。</li>
 *   <li><b>没有 {@code open_id} 的人要跳过</b>：给了也发不出去，只会让模型以为能发。</li>
 * </ul>
 */
class FeishuContactSearchStrategyTest {

    private static final String CRED = "{\"appId\":\"cli_x\",\"appSecret\":\"secret\"}";

    private HttpServer stub;
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.start();
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    // ═════════════════════ 过滤规则（纯函数） ═════════════════════

    @Test
    @DisplayName("匹配：中文名包含即命中；英文名大小写不敏感；空关键词不命中")
    void matchesRules() {
        assertThat(FeishuContactSearchStrategy.matches("张", "张三", "zhangsan")).isTrue();
        assertThat(FeishuContactSearchStrategy.matches("zhang", "张三", "ZhangSan")).isTrue();
        assertThat(FeishuContactSearchStrategy.matches("ZHANG", "张三", "zhangsan")).isTrue();
        assertThat(FeishuContactSearchStrategy.matches("李", "张三", "zhangsan")).isFalse();
        assertThat(FeishuContactSearchStrategy.matches("", "张三", "zhangsan")).isFalse();
        assertThat(FeishuContactSearchStrategy.matches("张", null, null)).isFalse();
    }

    // ═════════════════════ 正常路径 ═════════════════════

    @Test
    @DisplayName("只返回命中的人，且 userId 是 open_id（ou_）—— 这正是发给陌生人的路径")
    void returnsOnlyMatches() throws Exception {
        stub.createContext("/open-apis/contact/v3/users", ex -> {
            queries.add(ex.getRequestURI().getQuery());
            respond(ex, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":["
                    + "{\"open_id\":\"ou_zhang\",\"name\":\"张三\",\"en_name\":\"Zhang San\"},"
                    + "{\"open_id\":\"ou_li\",\"name\":\"李四\",\"en_name\":\"Li Si\"}]}}");
        });

        List<ImContactCandidate> hits = strategy().search("inst-fs", "张");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).userId()).isEqualTo("ou_zhang");
        assertThat(hits.get(0).name()).isEqualTo("张三");
        assertThat(hits.get(0).alias()).isEqualTo("Zhang San");
        // 请求必须声明 user_id_type=open_id，否则返回的可能是别的 id 类型
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).contains("user_id_type=open_id");
    }

    @Test
    @DisplayName("分页：has_more=true 要继续翻，第二页命中也要收进来")
    void followsPagination() throws Exception {
        stub.createContext("/open-apis/contact/v3/users", ex -> {
            String q = ex.getRequestURI().getQuery();
            queries.add(q);
            if (q != null && q.contains("page_token=")) {
                respond(ex, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":["
                        + "{\"open_id\":\"ou_zhang2\",\"name\":\"张五\"}]}}");
            } else {
                respond(ex, "{\"code\":0,\"data\":{\"has_more\":true,\"page_token\":\"p2\",\"items\":["
                        + "{\"open_id\":\"ou_li\",\"name\":\"李四\"}]}}");
            }
        });

        List<ImContactCandidate> hits = strategy().search("inst-fs", "张");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).userId()).isEqualTo("ou_zhang2");
        assertThat(queries).hasSize(2);
    }

    @Test
    @DisplayName("没有 open_id 的人被跳过（拿不到 id 就等于发不出去）")
    void skipsUsersWithoutOpenId() throws Exception {
        stub.createContext("/open-apis/contact/v3/users", ex ->
                respond(ex, "{\"code\":0,\"data\":{\"has_more\":false,\"items\":["
                        + "{\"name\":\"张三\"},"
                        + "{\"open_id\":\"\",\"name\":\"张三\"},"
                        + "{\"open_id\":\"ou_ok\",\"name\":\"张三\"}]}}"));

        List<ImContactCandidate> hits = strategy().search("inst-fs", "张");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).userId()).isEqualTo("ou_ok");
    }

    // ═════════════════════ 失败路径 ═════════════════════

    @Test
    @DisplayName("平台 code!=0 ⇒ 抛异常（⛔ 不能伪装成「通讯录里没人」）")
    void platformErrorThrows() {
        stub.createContext("/open-apis/contact/v3/users", ex ->
                respond(ex, "{\"code\":99991672,\"msg\":\"no permission\"}"));

        assertThatThrownBy(() -> strategy().search("inst-fs", "张"))
                .isInstanceOf(ImSendException.class)
                .hasMessageContaining("99991672");
    }

    @Test
    @DisplayName("HTTP 非 2xx ⇒ 抛异常")
    void httpErrorThrows() {
        stub.createContext("/open-apis/contact/v3/users", ex -> {
            byte[] b = "oops".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        assertThatThrownBy(() -> strategy().search("inst-fs", "张"))
                .isInstanceOf(ImSendException.class);
    }

    @Test
    @DisplayName("关键词空白 ⇒ 参数错误，不发请求")
    void blankKeywordRejected() {
        assertThatThrownBy(() -> strategy().search("inst-fs", "   "))
                .isInstanceOf(ImSendException.class)
                .hasMessageContaining("关键词");
    }

    @Test
    @DisplayName("实例没有凭证 ⇒ 明确报错")
    void missingCredentialThrows() {
        ImChannelService cs = mock(ImChannelService.class);
        when(cs.credentialOf(anyString())).thenReturn(Optional.empty());
        FeishuContactSearchStrategy s = new FeishuContactSearchStrategy(
                cs, tokenServiceReturning("tok"), baseUrl());

        assertThatThrownBy(() -> s.search("inst-fs", "张"))
                .isInstanceOf(ImSendException.class)
                .hasMessageContaining("inst-fs");
    }

    // ═════════════════════ 辅助 ═════════════════════

    private FeishuContactSearchStrategy strategy() {
        ImChannelService cs = mock(ImChannelService.class);
        when(cs.credentialOf(anyString())).thenReturn(Optional.of(CRED));
        return new FeishuContactSearchStrategy(cs, tokenServiceReturning("tok"), baseUrl());
    }

    private static FeishuTokenService tokenServiceReturning(String token) {
        FeishuTokenService t = mock(FeishuTokenService.class);
        try {
            when(t.tokenFor(anyString(), anyString(), anyString())).thenReturn(token);
        } catch (ImSendException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
