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
 * [IMP-16 REWORK] AnthropicSdkProvider task_budget 请求体 + beta header 透传测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>验收 #1 参数断言（provider 请求体）</b> — 结转后的 taskBudget 必须真正到达 Anthropic
 *       Messages API 请求体：{@code output_config.task_budget = {type:'tokens', total, remaining?}}
 *       （CC claude.ts:479-500 configureTaskBudgetParams）。若 buildRequestBody 不序列化
 *       task_budget（或字段错位），provider 收不到预算 → 服务端按 summary 少算 spend。</li>
 *   <li><b>beta header</b> — CC 注入 task_budget 时追加 {@code anthropic-beta: task-budgets-2026-03-13}
 *       （claude.ts:498-500 + constants/betas.ts:16）；Java 端 17-arg stream 必须在 taskBudget 非 null
 *       时追加该 header（OD-11 偏差：无 shouldIncludeFirstPartyOnlyBetas provider 归属门控）。</li>
 *   <li><b>remaining 可省略</b> — remaining 仅当非 null 才序列化（claude.ts:494
 *       {@code remaining !== undefined}）；loop 首次调用（无压缩）remaining=undefined → 请求体无
 *       remaining 字段。</li>
 * </ol>
 *
 * @see AnthropicSdkProvider#buildMessageParams(String, String, java.util.List, com.fasterxml.jackson.databind.node.ArrayNode, Integer, TaskBudgetParam, String, com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.OutputFormat, Boolean)
 * @see AnthropicSdkProvider#TASK_BUDGETS_BETA_HEADER
 */
class AnthropicSdkProviderTaskBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // 1. 生产 wire 格式（AnthropicSdkProvider.buildMessageParams → SDK _body() 序列化；
    //    [DEC-RV-07 REWORK-2] 断言生产实际发送的请求体，不再断言废弃 buildRequestBody）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("taskBudget 注入 → 请求体 output_config.task_budget={type:'tokens',total,remaining}（CC claude.ts:491-495）")
    void taskBudgetBody_injectsOutputConfig() throws Exception {
        JsonNode body = buildParamsWire(new TaskBudgetParam(200_000, 165_000));
        JsonNode oc = body.get("output_config");
        assertThat(oc).as("output_config 必须存在（task_budget 宿主）").isNotNull();
        JsonNode tb = oc.get("task_budget");
        assertThat(tb).isNotNull();
        assertThat(tb.get("type").asText()).as("type 恒为 'tokens'（claude.ts:492）").isEqualTo("tokens");
        assertThat(tb.get("total").asInt()).as("total=200000（query.ts:699-706 注入 total）").isEqualTo(200_000);
        assertThat(tb.get("remaining").asInt()).as("remaining=165000（跨压缩结转值）").isEqualTo(165_000);
    }

    @Test
    @DisplayName("remaining=null → 请求体省略 remaining 字段（CC claude.ts:494 remaining !== undefined）")
    void taskBudgetRemainingNull_omitsRemaining() throws Exception {
        JsonNode body = buildParamsWire(new TaskBudgetParam(200_000, null));
        JsonNode tb = body.get("output_config").get("task_budget");
        assertThat(tb.get("type").asText()).isEqualTo("tokens");
        assertThat(tb.get("total").asInt()).isEqualTo(200_000);
        assertThat(tb.has("remaining")).as("remaining undefined → 不输出字段").isFalse();
    }

    @Test
    @DisplayName("taskBudget=null → 无 output_config（默认请求体不被污染）")
    void noTaskBudget_noOutputConfig() throws Exception {
        JsonNode body = buildParamsWire(null);
        assertThat(body.has("output_config")).as("未注入 taskBudget → 请求体无 output_config").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 17-arg stream 端到端：beta header + 请求体真实透传（本地 HttpServer）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("17-arg stream 带 taskBudget → 真实请求含 anthropic-beta header + 请求体 output_config.task_budget")
    void stream17arg_sendsBetaHeaderAndBody() throws Exception {
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
            AtomicReference<Throwable> error = new AtomicReference<>();
            provider.stream(
                config, "claude-sonnet-4-6", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, new TaskBudgetParam(200_000, 165_000), null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, error::set, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("onComplete 必须触发（正常流结束）").isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("server 必须收到请求").isTrue();

            assertThat(capturedBetaHeader.get())
                .as("taskBudget 注入 → anthropic-beta 合并 task-budgets + effort（sonnet-4-6 支持 effort，C-31）")
                .isEqualTo(AnthropicSdkProvider.TASK_BUDGETS_BETA_HEADER + ","
                    + AnthropicSdkProvider.EFFORT_BETA_HEADER);
            JsonNode body = JSON.readTree(capturedBody.get());
            JsonNode tb = body.get("output_config").get("task_budget");
            assertThat(tb).as("请求体必须含 output_config.task_budget").isNotNull();
            assertThat(tb.get("total").asInt()).isEqualTo(200_000);
            assertThat(tb.get("remaining").asInt()).isEqualTo(165_000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("taskBudget=null + 不支持 effort 模型 → 默认无 anthropic-beta header、请求体无 output_config（回归保护）")
    void stream17arg_nullTaskBudget_noBetaHeader() throws Exception {
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
            // [C-31] 用不支持 effort 的模型（haiku-4-5）隔离 taskBudget 回归语义：
            //   taskBudget=null 且模型不支持 effort → 无任何 beta header、无 output_config。
            provider.stream(
                config, "claude-haiku-4-5", List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(capturedBetaHeader.get()).as("taskBudget=null + 不支持 effort → 不追加 beta header").isNull();
            assertThat(JSON.readTree(capturedBody.get()).has("output_config"))
                .as("taskBudget=null → 请求体无 output_config").isFalse();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private static JsonNode buildParamsWire(TaskBudgetParam taskBudget) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-sonnet-4-6", null, List.of(), null, null, taskBudget, null, null, null);
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
