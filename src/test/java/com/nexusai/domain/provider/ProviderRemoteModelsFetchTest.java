package com.nexusai.domain.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.RemoteModelsResponse;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-03] {@code ProviderService.fetchRemoteModels(id)} —— 拉取远端模型清单。
 *
 * <p><b>WHY 测这一层</b>：各家 {@code /models} 响应的<b>键名完全不同</b>（实测三种），
 * 解析逻辑若只认一个键名，就等于只支持一家；且「字段缺失」与「字段为 0」必须能区分
 * （0 会被当作窗口大小 ⇒ 压缩阈值失效）。这两点都是纯函数级的判据 ⇒ 用桩跑真实链路断言。
 *
 * <p><b>取证方式</b>：JDK {@link HttpServer} 桩充当 {@code GET {baseUrl}/models}，
 * mock 掉 mapper 与 crypto，直接调用真实方法 ⇒ 断言的是真实调用链走到底。
 */
@DisplayName("[Provider] 拉取远端模型清单")
class ProviderRemoteModelsFetchTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起桩：{@code GET /models} → 200 + 指定 JSON 体；同时记录收到的 Authorization。 */
    private String startStub(String body, AtomicReference<String> capturedAuth) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

    /** 装配 ProviderService（mock mapper + crypto + objectMapper），provider 指向本地桩。 */
    private ProviderService newService(String baseUrl) {
        ProviderRecord r = new ProviderRecord();
        r.setId("prov-remote-1");
        r.setName("probe");
        r.setType("openai_compatible");
        r.setBaseUrl(baseUrl);
        r.setApiKeyEncrypted("enc-key");

        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        Mockito.when(providerMapper.selectOneById("prov-remote-1")).thenReturn(r);

        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        Mockito.when(cryptoUtil.decrypt("enc-key")).thenReturn("sk-test");

        ProviderService svc = new ProviderService();
        ReflectionTestUtils.setField(svc, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(svc, "cryptoUtil", cryptoUtil);
        ReflectionTestUtils.setField(svc, "objectMapper", new ObjectMapper());
        return svc;
    }

    @Test
    @DisplayName("DeepSeek 形态：context_window / max_output_tokens 原样取出")
    void deepseekShape() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startStub("""
            {"object":"list","data":[
              {"id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek-V4.1-Flash",
               "context_window":1048576,"max_output_tokens":393216,
               "input_modalities":["text","image"],"output_modalities":["text"]},
              {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek","name":"DeepSeek-V4-Pro",
               "context_window":1048576,"max_output_tokens":393216}
            ]}""", auth);
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.ok()).as("桩返回 200 → 应成功").isTrue();
        assertThat(res.models()).hasSize(2);
        assertThat(res.models().get(0).id()).isEqualTo("deepseek-flash");
        assertThat(res.models().get(0).name()).isEqualTo("DeepSeek-V4.1-Flash");
        assertThat(res.models().get(0).contextWindow()).isEqualTo(1_048_576);
        assertThat(res.models().get(0).maxOutputTokens()).isEqualTo(393_216);
        assertThat(auth.get()).as("必须带 Bearer 凭据").isEqualTo("Bearer sk-test");
    }

    @Test
    @DisplayName("OpenRouter 形态：context_length / top_provider.max_completion_tokens")
    void openRouterShape() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startStub("""
            {"data":[
              {"id":"vendor/model-a","name":"Model A","context_length":200000,
               "top_provider":{"context_length":200000,"max_completion_tokens":64000}},
              {"id":"vendor/model-b","name":"Model B","context_length":8192}
            ]}""", auth);
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.ok()).isTrue();
        assertThat(res.models()).hasSize(2);
        assertThat(res.models().get(0).contextWindow()).isEqualTo(200_000);
        assertThat(res.models().get(0).maxOutputTokens()).as("top_provider.max_completion_tokens").isEqualTo(64_000);
        // 第二条没有 top_provider ⇒ 输出上限缺失 ⇒ null（前端落默认值），绝不能是 0
        assertThat(res.models().get(1).contextWindow()).isEqualTo(8192);
        assertThat(res.models().get(1).maxOutputTokens()).as("缺失 ⇒ null（不是 0：0 会让压缩阈值失效）").isNull();
    }

    @Test
    @DisplayName("标准 OpenAI 形态：只有 id ⇒ 窗口/输出上限皆 null")
    void standardOpenAiShape() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startStub("""
            {"object":"list","data":[
              {"id":"gpt-x","object":"model","created":123,"owned_by":"openai"}
            ]}""", auth);
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.ok()).isTrue();
        assertThat(res.models()).hasSize(1);
        assertThat(res.models().get(0).id()).isEqualTo("gpt-x");
        assertThat(res.models().get(0).contextWindow()).as("标准形态未给 ⇒ null").isNull();
        assertThat(res.models().get(0).maxOutputTokens()).isNull();
    }

    @Test
    @DisplayName("Anthropic 形态：display_name 作显示名")
    void anthropicShape() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startStub("""
            {"data":[{"id":"claude-x","display_name":"Claude X"}],"has_more":false}""", auth);
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.ok()).isTrue();
        assertThat(res.models().get(0).id()).isEqualTo("claude-x");
        assertThat(res.models().get(0).name()).as("display_name 归一为 name").isEqualTo("Claude X");
    }

    @Test
    @DisplayName("同批 id 重复 ⇒ 去重只留一条")
    void duplicateIdsDeduped() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startStub("""
            {"data":[{"id":"dup"},{"id":"dup"},{"id":"other"}]}""", auth);
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.models()).hasSize(2);
    }

    @Test
    @DisplayName("401 ⇒ ok=false + 中文原因（不抛异常，前端要能退回手动添加）")
    void unauthorizedReturnsFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            byte[] bytes = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProviderService svc = newService(baseUrl);

        RemoteModelsResponse res = svc.fetchRemoteModels("prov-remote-1");

        assertThat(res.ok()).isFalse();
        assertThat(res.message()).contains("鉴权");
        assertThat(res.models()).isEmpty();
    }
}
