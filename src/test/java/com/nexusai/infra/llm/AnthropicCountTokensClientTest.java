package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RES-R5-1] {@link AnthropicCountTokensClient} 意图测试 · 对齐 CC
 * countMessagesTokensWithAPI（tokenEstimation.ts:140-201）+ countTokensWithAPI（:124-138）。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>：/context analyze 的 per-section 计数走真实 LLM
 * countTokens API，CC 语义：
 * <ol>
 *   <li><b>空内容短路 0</b>（tokenEstimation.ts:127-130）——不得发 HTTP 请求；</li>
 *   <li><b>model / config 不可得 → null</b>（getMainLoopModel 失败 → 调用失败 → null，:146/:196-199）——
 *       调用方 section 记 0，不得本地 rough 估算；</li>
 *   <li><b>成功返回 input_tokens</b>（:195）——真实端点 {@code POST /v1/messages/count_tokens}
 *       （SDK bundled cli.js countTokens）+ {@code anthropic-beta: token-counting-2024-11-01}；</li>
 *   <li><b>input_tokens 非 number → null</b>（:189-193 Vertex/Bedrock 行为防御）；</li>
 *   <li><b>HTTP 错误 → null</b>（catch → :196-199）。</li>
 * </ol>
 */
class AnthropicCountTokensClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可用的 config/model（指向本地测试服务）。 */
    private static AnthropicCountTokensClient client(String baseUrl) {
        return new AnthropicCountTokensClient(
            () -> new ProviderConfig(baseUrl, "test-key"),
            () -> "claude-sonnet-4-5");
    }

    @Test
    @DisplayName("空内容 / null → 0，不发 HTTP（tokenEstimation.ts:127-130 短路）")
    void emptyContent_returnsZero_noHttpCall() {
        CountDownLatch latch = new CountDownLatch(1);
        AnthropicCountTokensClient client = new AnthropicCountTokensClient(
            () -> {
                latch.countDown();
                throw new AssertionError("空内容不得解析 config（:127-130 短路在前）");
            },
            () -> {
                throw new AssertionError("空内容不得解析 model（:127-130 短路在前）");
            });
        assertThat(client.countTokens("")).isEqualTo(0);
        assertThat(client.countTokens(null)).isEqualTo(0);
        assertThat(latch.getCount()).as("configSupplier 不应被调用").isEqualTo(1);
    }

    @Test
    @DisplayName("model 不可得 → null（getMainLoopModel 失败 → 调用失败 → null，:146/:196-199）")
    void modelUnavailable_returnsNull() {
        AnthropicCountTokensClient client = new AnthropicCountTokensClient(
            () -> new ProviderConfig("http://127.0.0.1:1", "k"),
            () -> null);
        assertThat(client.countTokens("some content")).isNull();
    }

    @Test
    @DisplayName("config 不可用 → null（getAnthropicClient 失败 → catch → null，:161/:196-199）")
    void configUnusable_returnsNull() {
        AnthropicCountTokensClient client = new AnthropicCountTokensClient(
            () -> ProviderConfig.empty(),
            () -> "claude-sonnet-4-5");
        assertThat(client.countTokens("some content")).isNull();
    }

    @Test
    @DisplayName("成功 → input_tokens；端点/header/请求体对齐（tokenEstimation.ts:172-195）")
    void success_returnsInputTokens() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedApiKey = new AtomicReference<>();
        AtomicReference<String> capturedVersion = new AtomicReference<>();
        AtomicReference<String> capturedBeta = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startServer("/v1/messages/count_tokens",
            exchange -> {
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                capturedVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
                capturedBeta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"input_tokens\":42}");
            });

        Integer tokens = client("http://127.0.0.1:" + server.getAddress().getPort()).countTokens("hello world");

        assertThat(tokens).isEqualTo(42);
        assertThat(capturedPath.get()).as("count_tokens 端点").isEqualTo("/v1/messages/count_tokens");
        assertThat(capturedApiKey.get()).isEqualTo("test-key");
        assertThat(capturedVersion.get()).isEqualTo("2023-06-01");
        assertThat(capturedBeta.get()).as("count_tokens beta header（SDK bundled cli.js）")
            .isEqualTo("token-counting-2024-11-01");

        JsonNode body = JSON.readTree(capturedBody.get());
        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(body.path("messages").isArray()).isTrue();
        JsonNode msg = body.path("messages").get(0);
        assertThat(msg.path("role").asText()).as("单 user 消息（analyzeContext.ts:301）").isEqualTo("user");
        assertThat(msg.path("content").asText()).isEqualTo("hello world");
        assertThat(body.path("tools").isArray()).as("tools=[]（countTokensWithAPI 恒传空）").isTrue();
        assertThat(body.path("tools").size()).isZero();
    }

    @Test
    @DisplayName("input_tokens 非 number → null（tokenEstimation.ts:189-193）")
    void nonNumberInputTokens_returnsNull() throws Exception {
        HttpServer server = startServer("/v1/messages/count_tokens",
            exchange -> respond(exchange, 200, "{\"input_tokens\":\"oops\"}"));
        Integer tokens = client("http://127.0.0.1:" + server.getAddress().getPort()).countTokens("hello");
        assertThat(tokens).as("非 number input_tokens → null（:189-193）").isNull();
    }

    @Test
    @DisplayName("HTTP 非 2xx → null（catch → :196-199 logError）")
    void httpError_returnsNull() throws Exception {
        HttpServer server = startServer("/v1/messages/count_tokens",
            exchange -> respond(exchange, 500, "{\"type\":\"error\"}"));
        Integer tokens = client("http://127.0.0.1:" + server.getAddress().getPort()).countTokens("hello");
        assertThat(tokens).as("HTTP 500 → null").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // RES-C9: tools 数组路径（对齐 CC countTokensWithFallback([], toolSchemas) analyzeContext.ts:250）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C9-RED: tools 数组路径 → 请求体含 tools 数组 + dummy message（tokenEstimation.ts:172-187）")
    void toolsArray_requestBodyContainsToolsArray() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startServer("/v1/messages/count_tokens",
            exchange -> {
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"input_tokens\":300}");
            });

        List<CountTokensClient.ToolSchema> tools = List.of(
            new CountTokensClient.ToolSchema("read_file", "read a file",
                JSON.createObjectNode().put("type", "object")),
            new CountTokensClient.ToolSchema("bash", "run bash",
                JSON.createObjectNode().put("type", "object")));

        Integer tokens = client("http://127.0.0.1:" + server.getAddress().getPort())
            .countTokensForTools(tools);

        assertThat(tokens).isEqualTo(300);
        JsonNode body = JSON.readTree(capturedBody.get());
        // tools 数组随请求发送（非拼进消息文本）· CC tokenEstimation.ts:172-187
        assertThat(body.path("tools").isArray()).as("tools 字段为数组").isTrue();
        assertThat(body.path("tools").size()).as("2 个工具 schema").isEqualTo(2);
        assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("read_file");
        assertThat(body.path("tools").get(1).path("name").asText()).isEqualTo("bash");
        // dummy message（CC tokenEstimation.ts:174: messages.length > 0 ? messages : [{role:'user',content:'foo'}]）
        assertThat(body.path("messages").isArray()).isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("C9-RED: tools 空列表 → 0 短路，不发 HTTP")
    void toolsArray_empty_returnsZero() {
        AnthropicCountTokensClient client = new AnthropicCountTokensClient(
            () -> { throw new AssertionError("空 tools 不得解析 config（空列表短路在前）"); },
            () -> { throw new AssertionError("空 tools 不得解析 model（空列表短路在前）"); });
        assertThat(client.countTokensForTools(List.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("C9-RED: tools model 不可得 → null（与 countTokens(String) 同语义）")
    void toolsArray_modelUnavailable_returnsNull() {
        AnthropicCountTokensClient client = new AnthropicCountTokensClient(
            () -> new ProviderConfig("http://127.0.0.1:1", "k"),
            () -> null);
        assertThat(client.countTokensForTools(List.of(
            new CountTokensClient.ToolSchema("test", "desc", JSON.createObjectNode())))).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 本地 HttpServer 工具（与 AnthropicSdkProviderTaskBudgetTest 同款模式）
    // ════════════════════════════════════════════════════════════════════

    private static HttpServer startServer(String context, HttpHandler handler)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
