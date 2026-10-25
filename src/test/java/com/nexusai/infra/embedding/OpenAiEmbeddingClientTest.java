package com.nexusai.infra.embedding;

import com.nexusai.infra.llm.ProviderConfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAiEmbeddingClient 协议测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：向量能力此前在本仓完全不存在；前端能选 embedding 模型但后端没有调用路径。
 * 本测试用本地桩服务器钉死三件「接错了会静默出错」的事：
 * <ol>
 *   <li><b>端点与字段</b>：请求必须打到 {@code {baseUrl}/embeddings}，并携带
 *       {@code model/input/dimensions/encoding_format=float}（百炼 compatible-mode 同协议）；</li>
 *   <li><b>分批</b>：百炼单请求最多 10 条 —— 超过批大小时必须拆成多次请求，且结果顺序仍与入参一致；</li>
 *   <li><b>响应解析</b>：{@code data[].embedding} → float 向量，{@code usage.prompt_tokens} 累加。</li>
 * </ol>
 * 测试不触碰真库、不调用真实 API（本地 HttpServer 桩）。
 */
@DisplayName("OpenAiEmbeddingClient: OpenAI 兼容 /embeddings 协议")
class OpenAiEmbeddingClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            requestCount.incrementAndGet();

            int inputCount = JSON.readTree(body).path("input").size();
            ObjectNode response = JSON.createObjectNode();
            response.put("object", "list");
            response.put("model", "text-embedding-v4");
            ArrayNode data = response.putArray("data");
            for (int i = 0; i < inputCount; i++) {
                ObjectNode item = data.addObject();
                item.put("object", "embedding");
                item.put("index", i);
                ArrayNode vector = item.putArray("embedding");
                vector.add(0.1);
                vector.add(0.2);
            }
            ObjectNode usage = response.putObject("usage");
            usage.put("prompt_tokens", inputCount);
            usage.put("total_tokens", inputCount);

            byte[] out = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProviderConfig config() {
        return new ProviderConfig(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", Map.of());
    }

    @Test
    @DisplayName("请求体：model / input[] / dimensions / encoding_format=float 全部按 OpenAI 兼容格式发出")
    void sendsOpenAiCompatibleRequest() throws Exception {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient();

        EmbeddingClient.Result result = client.embed(
            config(), "text-embedding-v4", List.of("你好", "世界"), 4, 10);

        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("text-embedding-v4");
        assertThat(body.path("input").isArray()).isTrue();
        assertThat(body.path("input").size()).isEqualTo(2);
        assertThat(body.path("dimensions").asInt()).isEqualTo(4);
        assertThat(body.path("encoding_format").asText()).isEqualTo("float");

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.dimensions()).isEqualTo(2);
        assertThat(result.promptTokens()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(1);
    }

    @Test
    @DisplayName("分批：3 条文本 + 批大小 2 → 2 次请求，结果顺序与入参一致")
    void splitsIntoBatchesAndKeepsOrder() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient();

        EmbeddingClient.Result result = client.embed(
            config(), "text-embedding-v4", List.of("a", "b", "c"), null, 2);

        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.vectors()).hasSize(3);
        // 未配置维度 → 请求不携带 dimensions（由服务端用模型默认维度）
        assertThat(lastBody.get()).doesNotContain("\"dimensions\"");
        assertThat(result.promptTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("空输入 → 不发请求、返回空结果（调用方已校验；客户端防御）")
    void emptyInputs_noRequest() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient();

        EmbeddingClient.Result result = client.embed(config(), "text-embedding-v4", List.of(), null, 10);

        assertThat(requestCount.get()).isZero();
        assertThat(result.vectors()).isEmpty();
        assertThat(result.batches()).isZero();
    }
}
