package com.nexusai.infra.rerank;

import com.nexusai.infra.llm.ProviderConfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAiRerankClient 协议测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：用户裁定「不新增 provider type」——同一百炼 provider 的 baseUrl
 * （{@code .../compatible-mode/v1}）必须能推导出重排端点 {@code .../compatible-api/v1/reranks}。
 * 这里钉死三件容易静默出错的事：
 * <ol>
 *   <li><b>端点推导</b>：compatible-mode → compatible-api；通用端点 → {@code /reranks}；</li>
 *   <li><b>扁平请求体</b>：{@code model/query/documents/top_n/instruct}（OpenAI/Cohere 兼容）；</li>
 *   <li><b>响应解析</b>：{@code results[].index/relevance_score/document.text} + usage。</li>
 * </ol>
 */
@DisplayName("OpenAiRerankClient: OpenAI 兼容 /reranks 协议与端点推导")
class OpenAiRerankClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = """
                {"id":"r1","object":"list","model":"qwen3-rerank",
                 "results":[
                   {"document":{"text":"doc-a"},"index":0,"relevance_score":0.93},
                   {"index":1,"relevance_score":0.12}
                 ],
                 "usage":{"total_tokens":42}}
                """;
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        };
        server.createContext("/compatible-api/v1/reranks", handler);
        server.createContext("/v1/reranks", handler);
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProviderConfig config(String basePath) {
        return new ProviderConfig(
            "http://127.0.0.1:" + server.getAddress().getPort() + basePath, "test-key", Map.of());
    }

    @Test
    @DisplayName("端点推导：百炼 compatible-mode → compatible-api；通用 → /reranks；已带 /reranks 原样")
    void rerankUrlDerivation() {
        assertThat(OpenAiRerankClient.rerankUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
            .isEqualTo("https://dashscope.aliyuncs.com/compatible-api/v1/reranks");
        assertThat(OpenAiRerankClient.rerankUrl(
                "https://w1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/"))
            .isEqualTo("https://w1.cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks");
        assertThat(OpenAiRerankClient.rerankUrl("https://api.example.com/v1"))
            .isEqualTo("https://api.example.com/v1/reranks");
        assertThat(OpenAiRerankClient.rerankUrl("https://api.example.com/v1/reranks"))
            .isEqualTo("https://api.example.com/v1/reranks");
    }

    @Test
    @DisplayName("百炼 baseUrl（compatible-mode/v1）→ 实际打到 compatible-api/v1/reranks，字段/鉴权正确")
    void callsCompatibleRerankEndpoint() throws Exception {
        OpenAiRerankClient client = new OpenAiRerankClient();

        RerankResult result = client.rerank(
            config("/compatible-mode/v1"),
            new RerankCommand("qwen3-rerank", "什么是重排序",
                List.of("doc-a", "doc-b"), 1, "retrieve relevant passages"));

        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("qwen3-rerank");
        assertThat(body.path("query").asText()).isEqualTo("什么是重排序");
        assertThat(body.path("documents").size()).isEqualTo(2);
        assertThat(body.path("top_n").asInt()).isEqualTo(1);
        assertThat(body.path("instruct").asText()).isEqualTo("retrieve relevant passages");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");

        assertThat(result.scores()).hasSize(2);
        assertThat(result.scores().get(0).index()).isZero();
        assertThat(result.scores().get(0).score()).isEqualTo(0.93);
        assertThat(result.scores().get(0).document()).isEqualTo("doc-a");
        assertThat(result.scores().get(1).document()).isNull();
        assertThat(result.totalTokens()).isEqualTo(42);
        assertThat(result.model()).isEqualTo("qwen3-rerank");
    }

    @Test
    @DisplayName("通用 OpenAI/Cohere 兼容 baseUrl → /v1/reranks；未传 topN/instruct 时不发送")
    void genericBaseUrl_omitsOptionalFields() {
        OpenAiRerankClient client = new OpenAiRerankClient();

        client.rerank(config("/v1"),
            new RerankCommand("qwen3-rerank", "q", List.of("a", "b", "c"), null, null));

        String body = lastBody.get();
        assertThat(body).doesNotContain("\"top_n\"");
        assertThat(body).doesNotContain("\"instruct\"");
    }
}
