package com.nexusai.infra.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * ⚠️ <b>本类不是一次性探针，不要当废料清掉。</b>
 * {@code :171} / {@code :292} 两条断言是「{@code DynamicHeaderExpander} 的禁止清单 ↔ SDK 实际注入头名」
 * 的<b>长期漂移守卫</b>：{@code DynamicHeaderExpanderTest.sdkCredentialHeaderNamesMustBeForbidden}
 * 只用硬编码字面量、不读 SDK，故「SDK 升级改了头名」这件事<b>只有本类能发现</b>。
 * <b>删除本类前必须先把那两条漂移守卫迁走</b>，否则清单与 SDK 之间的漂移将再无守卫。
 *
 * <p>[T1/T2 前置实测探针] 官方 SDK client builder 的 {@code putHeader(String,String)}：
 * 自定义头是否真的上到线上请求？与 SDK 依 {@code .apiKey()} 自动注入的凭据头撞名时谁赢？
 *
 * <p><b>WHY（规则九·验证意图）</b>：设计
 * {@code docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.2} 把
 * 「每一次 LLM 推理请求都带上 provider 自定义 header」这一整条链路的唯一注入动作，
 * 押在 {@code AnthropicOkHttpClient.Builder#putHeader} / {@code OpenAIOkHttpClient.Builder#putHeader}
 * 上。若该方法只是把 header 存进某个从不被读取的字段（或只在某个未走到的分支生效），
 * 整条链会在测试全绿的情况下**静默断头**——正是本仓 B1 断裂（header 进库但永不上线）的翻版。
 * 故必须用**真实 HTTP 出站**（本地 JDK {@link HttpServer} 桩）实测，而非看 SDK 返回值。
 *
 * <p><b>T1</b>：{@code putHeader("x-probe","v1")} → 断言桩收到 {@code x-probe: v1}。
 * <p><b>T2</b>：{@code putHeader("x-api-key","CUSTOM")} + {@code .apiKey("k")} → 记录桩实际收到的值。
 * <b>只作记录，不改变任何设计决策</b>（设计 D5 已在写侧禁止覆盖敏感头，撞名优先级对设计无影响）。
 *
 * <p><b>测试基建</b>：JDK 内置 {@code com.sun.net.httpserver.HttpServer}（127.0.0.1 随机端口），
 * 与 {@link LlmProviderRequestIdHeaderTest} 同款先例。桩在**回响应之前**就把收到的请求头快照，
 * 因此即使 SDK 因响应体不合法而抛错，T1 的取证依然成立（请求头已到达 = putHeader 已上线上）。
 *
 * @since 2026-09-12 provider-custom-headers 前置实测
 */
@DisplayName("[T1/T2 探针] SDK putHeader 是否上线上 + 撞名优先级")
class SdkPutHeaderProbeTest {

    private static final Logger log = LoggerFactory.getLogger(SdkPutHeaderProbeTest.class);

    /** Anthropic /v1/messages 最小合法响应（SDK 会校验形状；非法则抛，但请求头已取证）。 */
    private static final String ANTHROPIC_BODY = "{"
        + "\"id\":\"msg_probe_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-probe\","
        + "\"content\":[],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    /** OpenAI /v1/chat/completions 最小合法响应。 */
    private static final String OPENAI_BODY = "{"
        + "\"id\":\"chatcmpl-probe-1\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"gpt-probe\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 起本地桩：**先**快照请求头（大小写归一化）到 {@code sink}，**再**回响应体。
     *
     * <p>顺序是关键——SDK 若因响应不合法抛错，头仍已被取证。
     *
     * @param body  响应体
     * @param sink  收到的请求头快照落点
     * @return baseUrl（{@code http://127.0.0.1:<随机端口>}，无尾斜杠）
     */
    private String startStub(String body, AtomicReference<Map<String, List<String>>> sink)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            sink.set(normalize(exchange.getRequestHeaders()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Header 名 → 小写单值（同名多值用 ", " 连接，便于断言与打印）。 */
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

    private static void dump(String tag, Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            sb.append("\n    ").append(e.getKey()).append(": ").append(e.getValue());
        }
        // 探针证据：用 System.out 保证即使断言失败/日志级别过滤也能在 surefire 报告里看到原文
        System.out.println("[T1/T2 探针] " + tag + " 桩收到的请求头：" + sb);
        log.info("[T1/T2 探针] {} 桩收到 header 数={} x-probe={} x-api-key={} authorization={}",
            tag, headers.size(), first(headers, "x-probe"),
            first(headers, "x-api-key"), first(headers, "authorization"));
    }

    // ════════════════════════════════════════════════════════════════════
    // T1 · Anthropic：putHeader 是否真的上到线上请求
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1 · AnthropicOkHttpClient.putHeader 出现在真实出站请求上")
    void t1_anthropic_putHeader_reachesWire() throws IOException {
        AtomicReference<Map<String, List<String>>> sink = new AtomicReference<>();
        String baseUrl = startStub(ANTHROPIC_BODY, sink);

        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey("k")
            .baseUrl(baseUrl)
            .putHeader("x-probe", "v1")
            .build();

        Throwable parseError = null;
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-probe")
                .maxTokens(1L)
                .addUserMessage("hi")
                .build();
            client.messages().create(params);
        } catch (Throwable t) {
            // 响应不合法导致的解析/服务错误不影响 T1 取证（头已在桩侧快照）
            parseError = t;
        }

        Map<String, List<String>> headers = sink.get();
        assertThat(headers).as("stub 必须真的收到了一次请求（否则 T1 未取证）").isNotNull();
        dump("T1-anthropic", headers);
        if (parseError != null) {
            log.warn("[T1/T2 探针] Anthropic SDK 调用后抛错（响应体形状），但请求头已取证: {}", parseError.toString());
        }

        assertThat(first(headers, "x-probe"))
            .as("T1：putHeader 注入的 x-probe 必须出现在真实出站请求上")
            .isEqualTo("v1");
        assertThat(first(headers, "x-api-key")).as("SDK 依 .apiKey() 自动注入的凭据头仍在").isEqualTo("k");
    }

    // ════════════════════════════════════════════════════════════════════
    // T2 · Anthropic：putHeader("x-api-key", "CUSTOM") vs .apiKey("k") 谁赢
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2 · 撞名优先级：putHeader(x-api-key) vs .apiKey()（只记录，不改设计）")
    void t2_anthropic_credentialHeaderCollision() throws IOException {
        AtomicReference<Map<String, List<String>>> sink = new AtomicReference<>();
        String baseUrl = startStub(ANTHROPIC_BODY, sink);

        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey("k")
            .baseUrl(baseUrl)
            .putHeader("x-api-key", "CUSTOM")
            .build();

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-probe")
                .maxTokens(1L)
                .addUserMessage("hi")
                .build();
            client.messages().create(params);
        } catch (Throwable t) {
            log.warn("[T1/T2 探针] T2 Anthropic SDK 调用后抛错（响应体形状）: {}", t.toString());
        }

        Map<String, List<String>> headers = sink.get();
        assertThat(headers).as("stub 必须真的收到了一次请求").isNotNull();
        dump("T2-anthropic", headers);

        String actual = first(headers, "x-api-key");
        String winner = "CUSTOM".equals(actual) ? "putHeader(CUSTOM) 赢" : ("k".equals(actual) ? ".apiKey(k) 赢" : "都不对 → " + actual);
        System.out.println("[T1/T2 探针] T2-anthropic 撞名结果：" + winner);
        log.info("[T1/T2 探针] T2-anthropic x-api-key 实际值={} → {}", actual, winner);

        // 只断言「请求确实发出且该头存在」——谁赢不断言（T2 不改任何设计决策，仅作文档记录）
        assertThat(actual).as("T2 取证前提：x-api-key 头必须存在").isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // T2b · 调用顺序是否有语义影响（设计 §6.2 声称「调用顺序不再有语义影响」）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2b · 逆序（先 putHeader 后 apiKey）是否仍为 putHeader 赢 → 验证「顺序无语义影响」")
    void t2b_anthropic_builderOrderDoesNotMatter() throws IOException {
        AtomicReference<Map<String, List<String>>> sink = new AtomicReference<>();
        String baseUrl = startStub(ANTHROPIC_BODY, sink);

        // 与 T2 相反的调用顺序
        AnthropicClient client = AnthropicOkHttpClient.builder()
            .putHeader("x-api-key", "CUSTOM")
            .apiKey("k")
            .baseUrl(baseUrl)
            .build();

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-probe")
                .maxTokens(1L)
                .addUserMessage("hi")
                .build();
            client.messages().create(params);
        } catch (Throwable t) {
            log.warn("[T1/T2 探针] T2b Anthropic SDK 调用后抛错（响应体形状）: {}", t.toString());
        }

        Map<String, List<String>> headers = sink.get();
        assertThat(headers).as("stub 必须真的收到了一次请求").isNotNull();
        dump("T2b-anthropic-逆序", headers);

        String actual = first(headers, "x-api-key");
        System.out.println("[T1/T2 探针] T2b 逆序 x-api-key 实际值=" + actual
            + "（T2 正序为 CUSTOM；两者相同 ⇒ 调用顺序无语义影响）");
        log.info("[T1/T2 探针] T2b 逆序 x-api-key 实际值={}", actual);

        assertThat(actual).as("T2b 取证前提：x-api-key 头必须存在").isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // T1o / T2o · OpenAI 同款（设计对两个 SDK 押同一假设，必须同验）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1o · OpenAIOkHttpClient.putHeader 出现在真实出站请求上")
    void t1_openai_putHeader_reachesWire() throws IOException {
        AtomicReference<Map<String, List<String>>> sink = new AtomicReference<>();
        String baseUrl = startStub(OPENAI_BODY, sink);

        OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey("k")
            .baseUrl(baseUrl + "/v1")
            .putHeader("x-probe", "v1")
            .build();

        Throwable parseError = null;
        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("gpt-probe")
                .addUserMessage("hi")
                .build();
            ChatCompletion resp = client.chat().completions().create(params);
            log.info("[T1/T2 探针] T1o OpenAI create 返回 id={}", resp.id());
        } catch (Throwable t) {
            parseError = t;
        }

        Map<String, List<String>> headers = sink.get();
        assertThat(headers).as("stub 必须真的收到了一次请求（否则 T1o 未取证）").isNotNull();
        dump("T1-openai", headers);
        if (parseError != null) {
            log.warn("[T1/T2 探针] OpenAI SDK 调用后抛错（响应体形状），但请求头已取证: {}", parseError.toString());
        }

        assertThat(first(headers, "x-probe"))
            .as("T1o：putHeader 注入的 x-probe 必须出现在真实出站请求上")
            .isEqualTo("v1");
        assertThat(first(headers, "authorization")).as("SDK 依 .apiKey() 自动注入的 Bearer 仍在").isEqualTo("Bearer k");
    }

    @Test
    @DisplayName("T2o · OpenAI 撞名优先级：putHeader(Authorization) vs .apiKey()（只记录，不改设计）")
    void t2_openai_credentialHeaderCollision() throws IOException {
        AtomicReference<Map<String, List<String>>> sink = new AtomicReference<>();
        String baseUrl = startStub(OPENAI_BODY, sink);

        OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey("k")
            .baseUrl(baseUrl + "/v1")
            .putHeader("Authorization", "Bearer CUSTOM")
            .build();

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("gpt-probe")
                .addUserMessage("hi")
                .build();
            client.chat().completions().create(params);
        } catch (Throwable t) {
            log.warn("[T1/T2 探针] T2o OpenAI SDK 调用后抛错（响应体形状）: {}", t.toString());
        }

        Map<String, List<String>> headers = sink.get();
        assertThat(headers).as("stub 必须真的收到了一次请求").isNotNull();
        dump("T2-openai", headers);

        String actual = first(headers, "authorization");
        String winner = "Bearer CUSTOM".equals(actual)
            ? "putHeader(CUSTOM) 赢"
            : ("Bearer k".equals(actual) ? ".apiKey(k) 赢" : "都不对 → " + actual);
        System.out.println("[T1/T2 探针] T2o-openai 撞名结果：" + winner);
        log.info("[T1/T2 探针] T2o-openai authorization 实际值={} → {}", actual, winner);

        assertThat(actual).as("T2o 取证前提：authorization 头必须存在").isNotNull();
    }
}
