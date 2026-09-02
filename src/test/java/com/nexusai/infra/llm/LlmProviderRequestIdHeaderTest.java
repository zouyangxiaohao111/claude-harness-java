package com.nexusai.infra.llm;

import com.nexusai.infra.properties.NexusProperties;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session D P1-7] LlmProvider request-id header 提取测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:624-628} extractRequestId
 * ({@code _request_id} 属性, API request_id 格式 {@code req_xxx}).
 *
 * <p><b>WHY (意图验证)</b>: CC SDK 把 HTTP 响应头的 request id 挂到响应对象
 * {@code _request_id} 上 (非枚举属性), yoloClassifier 提取后写入
 * {@code stage1RequestId} / {@code stage2RequestId} (yoloClassifier.ts:798/884),
 * 供 server-side api_usage 日志 join (types/permissions.ts:382).
 * Java 端 LlmProvider 无 SDK, 等价物是从 HTTP 响应头提取:
 * <ul>
 *   <li>Anthropic API: {@code request-id} header (req_xxx 格式, Anthropic 官方标准 header)</li>
 *   <li>OpenAI 兼容网关: {@code x-request-id} (标准), 兜底 {@code request_id}</li>
 * </ul>
 *
 * <p><b>测试基建</b>: JDK 内置 {@link HttpServer} 起本地 127.0.0.1 随机端口服务
 * (参考 R33H6_ExecHttpHookTest / ExecHttpHookEndToEndTest 先例),
 * 桩响应携带/不携带 request id header, 验证 {@link LlmProvider.LlmRawResponse#requestId()}
 * 提取与 null 兜底 (对齐 CC {@code ?? undefined} 语义).
 *
 * @since Session D (P1-7)
 */
@DisplayName("[D] LlmProvider request-id header 提取 (CC extractRequestId)")
class LlmProviderRequestIdHeaderTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        server = null;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 起本地 HTTP 服务 (127.0.0.1 随机端口), 挂载指定 handler.
     * WHY: loopback 仅测试本地桩, 不涉及 SSRF guard (provider 侧无 SSRF 校验).
     */
    private String startServer(HttpExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 桩 handler: 固定响应体 + 可选 request id header. */
    private static void respond(HttpExchange exchange, String body, String requestIdHeader)
            throws IOException {
        if (requestIdHeader != null) {
            exchange.getResponseHeaders().add("request-id", requestIdHeader);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @FunctionalInterface
    private interface HttpExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    // ─────────── 1. AnthropicSdkProvider: request-id header（[DEC-RV-07] SDK 实现）───────────

    @Test
    @DisplayName("AnthropicSdkProvider 提取 request-id header → LlmRawResponse.requestId (CC _request_id)")
    void anthropicProvider_extractsRequestIdHeader() throws Exception {
        baseUrl = startServer(exchange -> respond(exchange,
            "{\"id\":\"msg_test123\",\"content\":[{\"type\":\"text\",\"text\":\"allow\"}]}",
            "req_01AbCdEf1234567890"));

        AnthropicSdkProvider provider = new AnthropicSdkProvider();
        LlmProvider.LlmRawResponse raw = provider.chatWithRaw(
            new ProviderConfig(baseUrl, "fake-key"), "claude-test", "sys", "user");

        assertThat(raw.requestId())
            .as("Anthropic request-id header 必须提取为 requestId (CC yoloClassifier.ts:798)")
            .isEqualTo("req_01AbCdEf1234567890");
        assertThat(raw.id()).isEqualTo("msg_test123");
        assertThat(raw.content()).isEqualTo("allow");
    }

    @Test
    @DisplayName("AnthropicSdkProvider 无 request-id header → requestId null (CC ?? undefined 兜底)")
    void anthropicProvider_missingRequestIdHeaderIsNull() throws Exception {
        baseUrl = startServer(exchange -> respond(exchange,
            "{\"id\":\"msg_test456\",\"content\":[{\"type\":\"text\",\"text\":\"deny\"}]}",
            null));

        AnthropicSdkProvider provider = new AnthropicSdkProvider();
        LlmProvider.LlmRawResponse raw = provider.chatWithRaw(
            new ProviderConfig(baseUrl, "fake-key"), "claude-test", "sys", "user");

        assertThat(raw.requestId())
            .as("无 request-id header 时 requestId 必须为 null (对齐 CC ?? undefined)")
            .isNull();
    }

    // ─────────── 2. OpenAiSdkProvider: requestId 恒 null（[OpenAI-SDK 迁移] R-REQ-1）───────────
    //   openai-java 0.25.0 无 withRawResponse（Anthropic 有）+ OpenAIOkHttpClient$Builder 无
    //   httpClient 注入点（OkHttp 拦截器方案不可用）→ DEC-OA-1 方案 C：requestId=null。
    //   content + id 提取仍验证（CC root.id = response id）。

    /** OpenAI 兼容桩响应（含 SDK ChatCompletion 必填字段：id/object/created/model/choices.index/message.role/finish_reason）. */
    private static String openAiCompletionBody(String id, String content) {
        return "{\"id\":\"" + id + "\",\"object\":\"chat.completion\",\"created\":1700000000,"
            + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
            + "\"finish_reason\":\"stop\"}]}";
    }

    @Test
    @DisplayName("OpenAiSdkProvider requestId 恒 null（SDK 0.25.0 无 withRawResponse · DEC-OA-1 方案 C）+ id/content 提取")
    void openAiProvider_requestIdNullIdAndContentExtracted() throws Exception {
        baseUrl = startServer(exchange -> {
            // 网关虽返回 x-request-id，但 SDK 0.25.0 不暴露响应头 → requestId 恒 null（R-REQ-1）
            exchange.getResponseHeaders().add("x-request-id", "req_openai_abc123");
            respondBody(exchange, openAiCompletionBody("chatcmpl-test1", "allow"));
        });

        OpenAiSdkProvider provider = new OpenAiSdkProvider();
        provider.properties = new NexusProperties();
        LlmProvider.LlmRawResponse raw = provider.chatWithRaw(
            new ProviderConfig(baseUrl, "fake-key"), "gpt-test", "sys", "user");

        assertThat(raw.requestId())
            .as("[OpenAI-SDK] R-REQ-1 兜底（DEC-RV-14a）· SDK 0.25.0 无 withRawResponse → requestId 走请求侧兜底；测试未设 MDC → RequestContext.requestId()=null（DEC-OA-1 方案 C + DEC-RV-14a）")
            .isNull();
        assertThat(raw.id()).isEqualTo("chatcmpl-test1");
        assertThat(raw.content()).isEqualTo("allow");
    }

    @Test
    @DisplayName("OpenAiSdkProvider 无 request id header → requestId null（SDK 无响应头通道 + 测试无 MDC → 请求侧兜底 null，与 CC ?? undefined 语义一致）")
    void openAiProvider_missingRequestIdHeaderIsNull() throws Exception {
        baseUrl = startServer(exchange -> respond(exchange,
            openAiCompletionBody("chatcmpl-test3", "ask"), null));

        OpenAiSdkProvider provider = new OpenAiSdkProvider();
        provider.properties = new NexusProperties();
        LlmProvider.LlmRawResponse raw = provider.chatWithRaw(
            new ProviderConfig(baseUrl, "fake-key"), "gpt-test", "sys", "user");

        assertThat(raw.requestId())
            .as("SDK 0.25.0 无响应头通道 + 无 MDC → 请求侧兜底 null（DEC-RV-14a：MDC reqId 为空时仍为 null，对齐 CC ?? undefined）")
            .isNull();
    }

    @Test
    @DisplayName("DEC-RV-14a 兜底：设置 RequestContext 后 OpenAiSdkProvider requestId = MDC reqId（请求侧自建 ID）")
    void openAiProvider_requestIdFallsBackToRequestContext() throws Exception {
        baseUrl = startServer(exchange -> respond(exchange,
            openAiCompletionBody("chatcmpl-fallback", "allow"), null));

        OpenAiSdkProvider provider = new OpenAiSdkProvider();
        provider.properties = new NexusProperties();
        com.nexusai.common.RequestContext.set("sess-x", "msg-fallback-1");
        try {
            LlmProvider.LlmRawResponse raw = provider.chatWithRaw(
                new ProviderConfig(baseUrl, "fake-key"), "gpt-test", "sys", "user");
            assertThat(raw.requestId())
                .as("DEC-RV-14a：SDK 无响应头通道 → requestId 兜底为请求侧 MDC reqId（userMessageId）")
                .isEqualTo("msg-fallback-1");
            assertThat(raw.id()).isEqualTo("chatcmpl-fallback");
        } finally {
            com.nexusai.common.RequestContext.clear();
        }
    }

    /** 只写响应体 (header 已在闭包内单独设置). */
    private static void respondBody(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
