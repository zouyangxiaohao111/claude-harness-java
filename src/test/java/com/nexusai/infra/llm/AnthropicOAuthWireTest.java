package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude 订阅 OAuth 令牌的 <b>wire 形状</b>断言（O1–O6）· 不调真模型。
 *
 * <p><b>WHY</b>：订阅态与 API Key 态在出站形状上完全不同（凭据头、身份头、system 首段身份句），
 * 且这些差异<b>只在真实 HTTP 请求上可见</b>——SDK 的 {@code .authToken(...)}、客户端级
 * {@code putHeader(...)}、params 级 {@code anthropic-beta} 三者谁覆盖谁，靠读代码推不出来
 * （已实测：{@code PrepareRequest} 先 {@code putAllHeaders(clientHeaders)} 再
 * {@code replaceAllHeaders(paramsHeaders)} ⇒ params 层同名头整条替换客户端层）。
 *
 * <p>取证方式：JDK 内置 {@link HttpServer}（127.0.0.1 随机端口）拦截**真实出站**
 * {@code POST /v1/messages}，桩在回响应之前快照请求头与请求体。
 *
 * <p>O1–O5 = 订阅令牌（{@code sk-ant-oat-...}）路径；O6 = 普通 API Key（{@code sk-ant-api03-...}）
 * 对照，保证老路径零变化（这条是「没把功能改坏」的唯一证据）。
 *
 * @see AnthropicSdkProvider#isClaudeOAuthToken(String)
 */
@DisplayName("[Claude OAuth] wire 形状 O1–O6")
class AnthropicOAuthWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 订阅令牌样本：真实 access token 形如 {@code sk-ant-oat01-...}，这里取 {@code sk-ant-oat-01-...}
     * 以便同时满足「{@code Authorization} 以 {@code Bearer sk-ant-oat-} 开头」这条字面断言；
     * 判定本身是 {@code contains("sk-ant-oat")}，两种拼写都命中（见 {@link #oauthTokenDetection()}）。
     */
    private static final String OAUTH_TOKEN = "sk-ant-oat-01-WIRE-TEST-TOKEN";

    /** 普通 API Key 样本。 */
    private static final String API_KEY = "sk-ant-api03-plain-key";

    private static final String ORIGINAL_SYSTEM = "ORIGINAL-SYSTEM-PROMPT";

    /** /v1/messages 最小合法非流式响应（SDK 会做形状校验，缺字段会抛）。 */
    private static final String MESSAGE_BODY = "{"
        + "\"id\":\"msg_oauth_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-wire\","
        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
        + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 起本地桩并发出一次 {@code provider.chat(...)}（最简非流式入口，走 chatWithRaw →
     * buildClient + buildMessageParams 全链），返回桩侧快照。
     */
    private Captured callChat(String apiKey, String systemPrompt) throws IOException {
        AtomicReference<Map<String, List<String>>> headersSink = new AtomicReference<>();
        AtomicReference<String> bodySink = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", (HttpExchange exchange) -> {
            // 先快照再回响应：即便 SDK 因响应形状抛错，取证依然成立
            headersSink.set(normalize(exchange.getRequestHeaders()));
            bodySink.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = MESSAGE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ProviderConfig config = new ProviderConfig(baseUrl, apiKey);
        // 关键：真实出站（不是直接调静态方法）——单测两端（buildClient / buildMessageParams）都过
        new AnthropicSdkProvider().chat(config, "claude-sonnet-4-6", systemPrompt, "hi");

        Map<String, List<String>> headers = headersSink.get();
        String body = bodySink.get();
        assertThat(headers).as("桩必须真的收到了一次 /v1/messages 请求").isNotNull();
        assertThat(body).as("桩必须真的收到了请求体").isNotNull();
        // 探针原文（即使断言失败也能在 surefire 报告里看到实际头/体）
        System.out.println("[Claude OAuth wire] 请求头：" + headers);
        System.out.println("[Claude OAuth wire] 请求体：" + body);
        return new Captured(headers, JSON.readTree(body));
    }

    private record Captured(Map<String, List<String>> headers, JsonNode body) {
    }

    /** Header 名 → 小写单值列表（HttpServer 已做大小写不敏感查找，此处再归一化便于断言）。 */
    private static Map<String, List<String>> normalize(com.sun.net.httpserver.Headers headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    /** 取首值（不存在 → null）。 */
    private static String first(Map<String, List<String>> headers, String name) {
        List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    // ════════════════════════════════════════════════════════════════════
    // O1–O5 · 订阅 OAuth 令牌
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("O1–O5 · 订阅令牌 → Bearer 凭据 + 无 x-api-key + CC 身份头 + system 首段身份句")
    void oauthToken_sendsClaudeCodeWire() throws IOException {
        Captured c = callChat(OAUTH_TOKEN, ORIGINAL_SYSTEM);

        // O1 · 凭据头走 Authorization: Bearer
        assertThat(first(c.headers(), "authorization"))
            .as("O1：订阅令牌必须落 Authorization: Bearer sk-ant-oat-...")
            .isNotNull()
            .startsWith("Bearer sk-ant-oat-");

        // O2 · 不发 x-api-key（SDK 只在 apiKey 非空时写该头）
        assertThat(first(c.headers(), "x-api-key"))
            .as("O2：OAuth 路径不得携带 x-api-key")
            .isNull();

        // O3 · anthropic-beta 含两条订阅 beta
        String beta = first(c.headers(), "anthropic-beta");
        assertThat(beta)
            .as("O3：anthropic-beta 必须含 claude-code-20250219（实际值：" + beta + "）")
            .isNotNull()
            .contains("claude-code-20250219")
            .contains("oauth-2025-04-20");

        // O4 · CC 身份头
        String ua = first(c.headers(), "user-agent");
        assertThat(ua)
            .as("O4：user-agent 必须为 claude-cli/2.1.75（实际值：" + ua + "）")
            .isEqualTo("claude-cli/2.1.75");
        assertThat(first(c.headers(), "x-app")).as("O4：x-app 必须为 cli").isEqualTo("cli");

        // O5 · system 两段：身份句 + 原文
        JsonNode system = c.body().get("system");
        assertThat(system).as("O5：system 必须是数组").isNotNull();
        assertThat(system.isArray()).as("O5：system 必须是数组（不是字符串）").isTrue();
        assertThat(system).hasSizeGreaterThanOrEqualTo(2);
        assertThat(system.get(0).get("text").asText())
            .as("O5：system[0] 必须是逐字身份句")
            .isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
        assertThat(system.get(0).has("cache_control"))
            .as("O5：身份段不带 cache_control")
            .isFalse();
        assertThat(system.get(1).get("text").asText())
            .as("O5：system[1] 是原有 system 原文")
            .isEqualTo(ORIGINAL_SYSTEM);
    }

    @Test
    @DisplayName("O5b · 订阅令牌 + 无原 system → system 仍为只含身份段的数组（不可省略）")
    void oauthToken_withoutSystem_stillSendsIdentityOnly() throws IOException {
        Captured c = callChat(OAUTH_TOKEN, null);

        JsonNode system = c.body().get("system");
        assertThat(system).as("O5b：原 system 为空时也必须发 system").isNotNull();
        assertThat(system.isArray()).isTrue();
        assertThat(system).hasSize(1);
        assertThat(system.get(0).get("text").asText())
            .isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
    }

    @Test
    @DisplayName("O0 · 凭证前缀判定：真实形态 sk-ant-oat01-… 与 sk-ant-oat-… 都命中；api key 不命中")
    void oauthTokenDetection() {
        assertThat(AnthropicSdkProvider.isClaudeOAuthToken("sk-ant-oat01-abc"))
            .as("真实 OAuth access token 形态 sk-ant-oat01-… 必须命中").isTrue();
        assertThat(AnthropicSdkProvider.isClaudeOAuthToken("sk-ant-oat-abc"))
            .as("sk-ant-oat-… 形态必须命中").isTrue();
        assertThat(AnthropicSdkProvider.isClaudeOAuthToken(API_KEY))
            .as("普通 API Key 不得被误判为 OAuth").isFalse();
        assertThat(AnthropicSdkProvider.isClaudeOAuthToken(null))
            .as("null 不得抛 NPE 也不得判为 OAuth").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // O6 · 普通 API Key 对照（老路径零变化）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("O6 · 普通 apiKey 对照 → x-api-key 凭据、无 Bearer、system 不含身份句")
    void plainApiKey_keepsExistingWire() throws IOException {
        Captured c = callChat(API_KEY, ORIGINAL_SYSTEM);

        assertThat(first(c.headers(), "x-api-key"))
            .as("O6：普通 key 必须走 x-api-key")
            .isEqualTo(API_KEY);
        String authorization = first(c.headers(), "authorization");
        assertThat(authorization == null || !authorization.startsWith("Bearer "))
            .as("O6：普通 key 不得出现 Authorization: Bearer（实际值：" + authorization + "）")
            .isTrue();

        JsonNode system = c.body().get("system");
        assertThat(system).isNotNull();
        assertThat(system.get(0).get("text").asText())
            .as("O6：普通 key 的 system 首段必须是原 system（不得注入身份句）")
            .isEqualTo(ORIGINAL_SYSTEM);
        for (JsonNode block : system) {
            assertThat(block.get("text").asText())
                .as("O6：普通 key 的 system 任何一段都不得含身份句")
                .doesNotContain("You are Claude Code");
        }
    }
}
