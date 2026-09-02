package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.properties.NexusProperties;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionReasoningEffort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C-31] OpenAiSdkProvider effort → {@code reasoning_effort} 请求体注入测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>入口打通验收</b> — LlmProvider 18-arg stream 默认实现会<b>丢弃</b> effortValue
 *       （委托 17-arg）；OpenAiSdkProvider 覆写 18-arg 后，skill frontmatter effort
 *       （SkillToolImpl contextModifier → AgentState.effortValue → ModelRequest.effortValue）
 *       必须真正到达 OpenAI 兼容请求体 {@code reasoning_effort}。</li>
 *   <li><b>映射表验收</b> — CC EFFORT_LEVELS ['low','medium','high','max'] → OpenAI
 *       reasoning_effort：low/medium/high 直通，max→high，null/无效/数值不注入。</li>
 *   <li><b>模型门控验收</b> — modelSupportsEffort(haiku-4-5)=false → 不注入。</li>
 *   <li><b>Java 扩展语义</b> — reasoning_effort 为 Java 多 provider 扩展（⊕），CC 仅 Anthropic
 *       output_config.effort + effort-2025-11-24 beta header。OpenAI 路径不走 anthropic-beta header。</li>
 * </ol>
 *
 * <p>[OpenAI-SDK 迁移] 断言目标从旧实现迁移到生产实际路径
 * {@link OpenAiSdkProvider#buildRequestParams} 的 SDK params（ChatCompletionCreateParams 直接
 * 序列化产出 {} 且无公共 body accessor → 断言 SDK 字段 reasoningEffort 是否注入，映射表语义等价）；
 * wire 层由 18-arg stream 端到端测试（本地 HttpServer 捕获真实请求体）验证。
 */
class OpenAiSdkProviderEffortTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // 1. buildRequestParams 注入（[OpenAI-SDK 迁移] SDK params.getter 断言）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("effort='high' + 支持模型 → reasoningEffort 注入 'high'（同名字符串直通）")
    void effortHigh_supportedModel_injectsReasoningEffort() throws Exception {
        Optional<String> effort = reasoningEffortWire("deepseek-chat", "high");
        assertThat(effort)
            .as("effort=high + modelSupportsEffort(deepseek-chat)=true → reasoningEffort 必须注入")
            .isPresent();
        assertThat(effort.get()).isEqualTo("high");
    }

    @Test
    @DisplayName("effort='medium' → reasoningEffort='medium'（同名字符串直通）")
    void effortMedium_mapsToMedium() throws Exception {
        assertThat(reasoningEffortWire("deepseek-chat", "medium")).hasValue("medium");
    }

    @Test
    @DisplayName("effort='low' → reasoningEffort='low'（同名字符串直通）")
    void effortLow_mapsToLow() throws Exception {
        assertThat(reasoningEffortWire("deepseek-chat", "low")).hasValue("low");
    }

    @Test
    @DisplayName("effort='max' → reasoningEffort='high'（OpenAI 无 max，取最高档 · Java 扩展 ⊕）")
    void effortMax_mapsToHigh() throws Exception {
        assertThat(reasoningEffortWire("deepseek-chat", "max"))
            .as("OpenAI reasoning_effort 无 'max' 档 → 映射到最高档 'high'（CC EFFORT_LEVELS → OpenAI 子集）")
            .hasValue("high");
    }

    @Test
    @DisplayName("effort=null（appState 未设置）→ 不注入 reasoningEffort（默认请求体不被污染）")
    void effortNull_noInjection() throws Exception {
        assertThat(reasoningEffortWire("deepseek-chat", null))
            .as("effort=null → resolveAppliedEffort 无 appState/env/模型默认（deepseek 非 opus）→ 不注入")
            .isEmpty();
    }

    @Test
    @DisplayName("effort=无效值 → 不注入 reasoningEffort（对齐 resolveAppliedEffort 校验）")
    void effortInvalid_noInjection() throws Exception {
        assertThat(reasoningEffortWire("deepseek-chat", "banana")).isEmpty();
    }

    @Test
    @DisplayName("effort=数值字符串 → 不注入（ant-only 数值分支条件不可达，不伪造 CC anthropic_internal）")
    void effortNumeric_noInjection() throws Exception {
        ChatCompletionCreateParams params = buildParams("deepseek-chat", "75");
        assertThat(params.reasoningEffort())
            .as("数值 ant-only 分支（effort.ts:62-68）Java 无 USER_TYPE → parseEffortValue=null → 不注入")
            .isEmpty();
        assertThat(params._additionalBodyProperties().containsKey("anthropic_internal"))
            .as("OpenAI 路径绝不产生 anthropic_internal（CC ant-only 分支不建模）").isFalse();
    }

    @Test
    @DisplayName("effort='high' + 不支持模型 haiku-4-5 → 无 reasoningEffort（CC claude.ts:447-449 模型门控）")
    void effortHigh_unsupportedModel_noInjection() throws Exception {
        assertThat(reasoningEffortWire("claude-haiku-4-5", "high"))
            .as("modelSupportsEffort(haiku-4-5)=false → 不写 reasoning_effort（对齐 CC 早退）")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 18-arg stream 端到端：请求体真实透传（本地 HttpServer · [OpenAI-SDK] SDK createStreaming）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("18-arg stream effort='high' + deepseek-chat → 请求体 reasoning_effort='high' 且无 anthropic-beta header")
    void stream18arg_effortHigh_injectsReasoningEffort() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedBetaHeader = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] sse = buildSseResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
            latch.countDown();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = newProviderWithProperties();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "deepseek-chat", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, "high", null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("onComplete 必须触发（正常流结束）").isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("server 必须收到请求").isTrue();

            JsonNode body = JSON.readTree(capturedBody.get());
            assertThat(body.get("reasoning_effort").asText())
                .as("18-arg stream effort 必须真正到达请求体 reasoning_effort（C-31 入口打通验收）")
                .isEqualTo("high");
            assertThat(capturedBetaHeader.get())
                .as("OpenAI 路径不走 anthropic-beta 头（reasoning_effort 为 body 参数 · Java 扩展 ⊕）")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("18-arg stream effort=null + deepseek-chat → 请求体无 reasoning_effort")
    void stream18arg_effortNull_noInjection() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] sse = buildSseResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
            latch.countDown();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = newProviderWithProperties();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "deepseek-chat", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            JsonNode body = JSON.readTree(capturedBody.get());
            assertThat(body.has("reasoning_effort"))
                .as("effort=null → 请求体无 reasoning_effort（deepseek 非 opus 无模型默认）").isFalse();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static ChatCompletionCreateParams buildParams(String model, String effortValue) throws Exception {
        // history 传 1 条 user 消息 —— SDK 校验 messages 必填（旧 buildRequestBody 恒产出 messages 数组等价）
        return OpenAiSdkProvider.buildRequestParams(
            model, null, List.of(userMsg("hi")), null, null, false, null, effortValue, null);
    }

    /** reasoning_effort 注入值（SDK params.getter）· ChatCompletionCreateParams 直接序列化产出 {} → 不走 wire。 */
    private static Optional<String> reasoningEffortWire(String model, String effortValue) throws Exception {
        return buildParams(model, effortValue).reasoningEffort()
            .map(ChatCompletionReasoningEffort::asString);
    }

    /** new OpenAiSdkProvider() + 反射注入 NexusProperties（stream 解析 reasoning 字段依赖 properties）. */
    private static OpenAiSdkProvider newProviderWithProperties() throws Exception {
        OpenAiSdkProvider provider = new OpenAiSdkProvider();
        Field f = OpenAiSdkProvider.class.getDeclaredField("properties");
        f.setAccessible(true);
        f.set(provider, new NexusProperties());
        return provider;
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    /** OpenAI SSE 流响应 · SDK ChatCompletionChunk 必填字段（id/object/created/model/choices.index/delta/finish_reason）. */
    private static byte[] buildSseResponse() {
        return ("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
            + "\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}\n"
            + "\n"
            + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
            + "\n"
            + "data: [DONE]\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
