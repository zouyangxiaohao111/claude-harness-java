package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpExchange;
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
 * [C-31] AnthropicSdkProvider effort 请求体 + beta header 透传测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>验收 #1 参数断言（provider 请求体）</b> — skill frontmatter effort（SkillToolImpl
 *       contextModifier → AgentState.effortValue → ModelRequest.effortValue）必须真正到达
 *       Anthropic Messages API 请求体 {@code output_config.effort}（CC claude.ts:1458
 *       resolveAppliedEffort + claude.ts:437-463 configureEffortParams）。若 buildMessageParams
 *       不序列化 effort（或模型门控错位），skill effort 仅数据形态存在（C-31 决策 FAIL）。</li>
 *   <li><b>beta header</b> — CC 模型支持 effort 时无论是否设置 effort 值均追加
 *       {@code anthropic-beta: effort-2025-11-24}（claude.ts:451-452 effortValue undefined
 *       仍 push + :456/459 string level 也 push + constants/betas.ts:15）；不支持模型
 *       （haiku 等）不追加（claude.ts:447-449 modelSupportsEffort 早退）。</li>
 *   <li><b>max 降级</b> — effort.ts:76-79：API 拒绝非 Opus-4.6 模型的 max → 降级 'high'。</li>
 *   <li><b>task_budget + effort 共存</b> — CC output_config 单节点可同时承载两者
 *       （claude.ts:1559-1565 统一 outputConfig 构建），Java 必须同节点合并（concern ③）。</li>
 * </ol>
 *
 * <p><b>数值 ant-only 分支</b>（claude.ts:461-463 {@code anthropic_internal.effort_override}）：
 * Java 无 USER_TYPE 判定 → 条件不可达（EffortSupport 文档化登记）；本测试断言数值字符串
 * 不产生 anthropic_internal 注入（不伪造 ant 行为）。
 *
 * @see AnthropicSdkProvider#buildMessageParams(String, String, java.util.List, com.fasterxml.jackson.databind.node.ArrayNode, Integer, TaskBudgetParam, String, com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.OutputFormat, Boolean)
 * @see AnthropicSdkProvider#EFFORT_BETA_HEADER
 * @see EffortSupport
 */
class AnthropicSdkProviderEffortTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // 1. 生产 wire 格式（AnthropicSdkProvider.buildMessageParams → SDK _body() 序列化；
    //    [DEC-RV-07 REWORK-2] 断言生产实际发送的请求体，不再断言废弃 buildRequestBody）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("effort='high' + 支持模型 → output_config.effort='high'（CC claude.ts:456 string level）")
    void effortHigh_supportedModel_injectsOutputConfigEffort() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6", null, "high");
        JsonNode oc = body.get("output_config");
        assertThat(oc).as("模型支持 effort + effort 值 → output_config 必须存在").isNotNull();
        assertThat(oc.get("effort").asText()).as("output_config.effort = effort level").isEqualTo("high");
    }

    @Test
    @DisplayName("effort=null（appState 未设置）+ 支持模型 → 无 output_config（默认请求体不被污染）")
    void noEffort_supportedModel_noOutputConfig() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6", null, null);
        assertThat(body.has("output_config")).as("effort=null 且无 taskBudget → 请求体无 output_config").isFalse();
    }

    @Test
    @DisplayName("effort='high' + 不支持模型 haiku-4-5 → 无 output_config.effort（CC claude.ts:447-449 模型门控）")
    void effortHigh_unsupportedModel_noEffort() throws Exception {
        JsonNode body = buildParamsWire("claude-haiku-4-5", null, "high");
        assertThat(body.has("output_config"))
            .as("modelSupportsEffort(haiku-4-5)=false → 不写 output_config.effort").isFalse();
    }

    @Test
    @DisplayName("effort='max' + 非 Opus-4.6 模型 → 降级 'high'（effort.ts:76-79 max 降级）")
    void effortMax_unsupportedMaxModel_downgradedToHigh() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6", null, "max");
        JsonNode oc = body.get("output_config");
        assertThat(oc).as("sonnet-4-6 支持 effort → output_config 存在").isNotNull();
        assertThat(oc.get("effort").asText())
            .as("max 仅 Opus-4.6 支持 → 非 Opus-4.6 降级 'high'（effort.ts:78-79）")
            .isEqualTo("high");
    }

    @Test
    @DisplayName("effort='max' + Opus-4.6 → 保留 'max'（modelSupportsMaxEffort=true）")
    void effortMax_opus46_keptMax() throws Exception {
        JsonNode body = buildParamsWire("claude-opus-4-6", null, "max");
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("max");
    }

    @Test
    @DisplayName("effort=数值字符串 → 不注入 anthropic_internal.effort_override（ant-only 分支不可达，不伪造）")
    void effortNumeric_noAntOverride() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6", null, "75");
        assertThat(body.has("anthropic_internal"))
            .as("数值 ant-only 分支（claude.ts:461-463）Java 条件不可达 → 不伪造").isFalse();
        assertThat(body.has("output_config"))
            .as("数值非 level → parseEffortValue=null → 不写 output_config.effort").isFalse();
    }

    @Test
    @DisplayName("task_budget + effort 共存 → 同一 output_config 节点（CC claude.ts:1559-1565 统一构建）")
    void taskBudgetAndEffort_coexistInSameOutputConfig() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6",
            new TaskBudgetParam(200_000, 165_000), "high");
        JsonNode oc = body.get("output_config");
        assertThat(oc).as("output_config 必须存在").isNotNull();
        JsonNode tb = oc.get("task_budget");
        assertThat(tb).as("task_budget 节点保留").isNotNull();
        assertThat(tb.get("total").asInt()).isEqualTo(200_000);
        assertThat(oc.get("effort").asText())
            .as("effort 与 task_budget 同节点共存（非互斥）").isEqualTo("high");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 18-arg stream 端到端：beta header + 请求体真实透传（本地 HttpServer · [DEC-RV-07] SDK）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("18-arg stream effort='high' + 支持模型 → anthropic-beta: effort-2025-11-24 + 请求体 output_config.effort")
    void stream18arg_effortHigh_sendsBetaHeaderAndBody() throws Exception {
        AtomicReference<String> capturedBetaHeader = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
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
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "claude-sonnet-4-6", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, "high", null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("onComplete 必须触发（正常流结束）").isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("server 必须收到请求").isTrue();

            assertThat(capturedBetaHeader.get())
                .as("effort 注入 → anthropic-beta: effort-2025-11-24（CC constants/betas.ts:15）")
                .isEqualTo(AnthropicSdkProvider.EFFORT_BETA_HEADER);
            JsonNode body = JSON.readTree(capturedBody.get());
            assertThat(body.get("output_config").get("effort").asText())
                .as("请求体必须含 output_config.effort='high'").isEqualTo("high");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("18-arg stream effort=null + 支持模型 → 仅追加 anthropic-beta: effort-2025-11-24（CC claude.ts:451-452）")
    void stream18arg_effortNull_stillPushesBetaHeader() throws Exception {
        AtomicReference<String> capturedBetaHeader = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
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
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "claude-sonnet-4-6", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedBetaHeader.get())
                .as("effortValue undefined 仍 push EFFORT_BETA_HEADER（claude.ts:451-452）")
                .isEqualTo(AnthropicSdkProvider.EFFORT_BETA_HEADER);
            JsonNode body = JSON.readTree(capturedBody.get());
            assertThat(body.has("output_config"))
                .as("effort=null → 请求体无 output_config.effort（仅 header）").isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("18-arg stream effort=null + 不支持模型 haiku-4-5 → 无 anthropic-beta header（CC claude.ts:447-449）")
    void stream18arg_unsupportedModel_noBetaHeader() throws Exception {
        AtomicReference<String> capturedBetaHeader = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
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
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "claude-haiku-4-5", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, "high", null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedBetaHeader.get())
                .as("modelSupportsEffort(haiku-4-5)=false → 不追加 effort beta header").isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("18-arg stream taskBudget + effort → anthropic-beta 合并逗号分隔（CC betas.join(',')）")
    void stream18arg_taskBudgetAndEffort_mergedBetaHeader() throws Exception {
        AtomicReference<String> capturedBetaHeader = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBetaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
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
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            provider.stream(
                config, "claude-sonnet-4-6", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, new TaskBudgetParam(200_000, 165_000), "high", null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedBetaHeader.get())
                .as("taskBudget + effort → 逗号合并（对齐 CC betas.join(',')）")
                .isEqualTo(AnthropicSdkProvider.TASK_BUDGETS_BETA_HEADER + ","
                    + AnthropicSdkProvider.EFFORT_BETA_HEADER);
            JsonNode body = JSON.readTree(capturedBody.get());
            JsonNode oc = body.get("output_config");
            assertThat(oc.get("task_budget").get("total").asInt()).isEqualTo(200_000);
            assertThat(oc.get("effort").asText()).isEqualTo("high");
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private static JsonNode buildParamsWire(String model, TaskBudgetParam taskBudget, String effortValue)
            throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            model, null, List.of(), null, null, taskBudget, effortValue, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    private static byte[] buildSseResponse() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\"}}\n"
            + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n"
            + "\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
