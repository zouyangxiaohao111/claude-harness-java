package com.nexusai.infra.llm;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [REWORK-5 R-A13 返工 R-1] per-LLM-call 遥测事件行为测试（A-13 新增行为锁）。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: R-A13 原聚焦测试
 * {@code AnthropicSdkProviderEffortTest} 仅锁 effort/beta header 行为，对 A-13 新增的
 * per-LLM-call {@code tengu_api_success}/{\code tengu_api_error} 遥测发射零断言——
 * 删掉 A13 代码测试照样绿（R-A13 独立反思 §4 R-1 判定为 PASS 阻断缺陷）。本测试：
 * <ol>
 *   <li><b>doStream 成功 → {@code tengu_api_success}</b>（CC claude.ts:2858
 *       {@code logAPISuccessAndDuration}）——SSE 正常流结束，onComplete 触发，事件必须发射。</li>
 *   <li><b>doStream 失败/abort → {@code tengu_api_error}</b>（CC claude.ts:2720/:2776 + :2738
 *       abort 先 logAPIError）——流式异常 / 硬中断均属错误面，必须发射 error。</li>
 *   <li><b>chat 系列成功/失败 → {@code tengu_api_success}/{\code tengu_api_error}</b>
 *       （非流式 chatWithRaw/chatWithOptions/chatWithOptionsMessage，CC claude.ts:2858/:2720）。</li>
 *   <li><b>OTel 分名对齐（R-3）</b>——analytics 用 {@code tengu_api_*}，OTel 用
 *       {@code api_request}/{\code api_error}（CC logging.ts:718/:368）+ snake_case attrs。</li>
 * </ol>
 *
 * <p><b>mock 策略</b>: Telemetry 方法非 final，Mockito {@code mock(Telemetry.class)}
 * 经 {@code ReflectionTestUtils.setField} 注入 provider 私有字段 {@code telemetry}
 * （main {@code emitApiTerminalEvent} 对 null telemetry 静默跳过，测试注入后事件真实发射）。
 *
 * @see AnthropicSdkProvider#emitApiTerminalEvent(String, java.util.Map)
 * @see AnthropicSdkProvider#otelEventName(String)
 * @see AnthropicSdkProvider#otelAttrs(String, java.util.Map)
 */
class AnthropicSdkProviderApiTelemetryTest {

    private static final String MODEL = "claude-sonnet-4-6";

    // ════════════════════════════════════════════════════════════════════
    // 1. doStream 流式成功 → tengu_api_success（CC claude.ts:2858）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("doStream SSE 正常流结束 → recordEvent(tengu_api_success) + logOTelEvent(api_request) · OTel snake_case attrs")
    void doStream_success_emitsTenguApiSuccess() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            CountDownLatch done = new CountDownLatch(1);
            provider.stream(config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("onComplete 必须触发（正常流结束）").isTrue();

            // analytics 事件名 = tengu_api_success（CC logEvent）
            verify(telemetry).recordEvent(eq("tengu_api_success"), any());
            // OTel 分名 = api_request（CC logging.ts:718）+ snake_case attrs（R-3 对齐）
            ArgumentCaptor<Map<String, Object>> otelAttrs = ArgumentCaptor.forClass(Map.class);
            verify(telemetry).logOTelEvent(eq("api_request"), otelAttrs.capture());
            Map<String, Object> o = otelAttrs.getValue();
            // 断言 OTel snake_case 键存在（R-3 对齐 CC logging.ts:718-726）；token 精确值受
            // SDK message_start usage 解析（input_tokens 未被 SDK 提取 → 0，WARN「usage is not set」
            // 为既有行为），output_tokens=42 来自 message_delta.usage（AnthropicSdkProviderTest 实证）。
            assertThat(o).as("OTel attrs 必须含 snake_case input_tokens 键（CC logging.ts:719）")
                .containsKey("input_tokens");
            assertThat(o).as("OTel attrs 必须含 snake_case output_tokens=42（message_delta.usage 实证）")
                .containsEntry("output_tokens", "42");
            assertThat(o).as("OTel attrs 必须含 snake_case cache_read_tokens 键（CC logging.ts:720）")
                .containsKey("cache_read_tokens");
            assertThat(o).as("OTel attrs 必须含 snake_case cache_creation_tokens 键（CC logging.ts:721）")
                .containsKey("cache_creation_tokens");
            assertThat(o).as("OTel attrs 必须含 snake_case duration_ms（CC logging.ts:723）")
                .containsKey("duration_ms");
            assertThat(o.get("duration_ms")).as("duration_ms 必须为 String（CC String(durationMs)）")
                .isInstanceOf(String.class);
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. doStream 流式失败 → tengu_api_error（CC claude.ts:2720/:2776）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("doStream HTTP 500 → recordEvent(tengu_api_error) + logOTelEvent(api_error)（CC claude.ts:2720）")
    void doStream_failure_emitsTenguApiError() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startErrorServer(500);
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            CountDownLatch errored = new CountDownLatch(1);
            // onStreamingFallback=null → 流式错误不回退，直接 onError（CC claude.ts:2476-2501 门控外）
            provider.stream(config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {},
                null, // onStreamingFallback = null（不回退）
                null, e -> errored.countDown(), () -> {});

            assertThat(errored.await(10, TimeUnit.SECONDS)).as("onError 必须触发（流式 500）").isTrue();
            verify(telemetry).recordEvent(eq("tengu_api_error"), any());
            verify(telemetry).logOTelEvent(eq("api_error"), any());
            verify(telemetry, never()).recordEvent(eq("tengu_api_success"), any());
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. doStream abort → tengu_api_error（CC claude.ts:2720 → :2738 abort 先记录）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("doStream 硬中断 abort → recordEvent(tengu_api_error)（CC claude.ts:2738 abort 先 logAPIError）")
    void doStream_abort_emitsTenguApiError() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            com.nexusai.application.agent.tool.AbortController abort =
                new com.nexusai.application.agent.tool.AbortController();
            CountDownLatch errored = new CountDownLatch(1);
            // stream() 同步阻塞 → abort 必须在独立线程触发（SSE 分段写 message_start 后暂停 150ms 命中）
            Thread aborter = new Thread(() -> {
                try {
                    Thread.sleep(50);
                    abort.abort("timeout");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            aborter.start();
            // stream() 同步执行：abort 触发后 doStream 在 :315 发射 error 事件并 return
            provider.stream(config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {},
                () -> {}, // onStreamingFallback（abort 分支早退，不回退）
                abort, e -> errored.countDown(), () -> {});

            assertThat(errored.await(10, TimeUnit.SECONDS)).as("abort 后 onError(CancellationException) 必须触发").isTrue();
            verify(telemetry).recordEvent(eq("tengu_api_error"), any());
            verify(telemetry, never()).recordEvent(eq("tengu_api_success"), any());
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. chat 系列（非流式）成功/失败 → tengu_api_success / tengu_api_error
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("chatWithRaw 非流式成功 → recordEvent(tengu_api_success) + logOTelEvent(api_request)")
    void chatWithRaw_success_emitsTenguApiSuccess() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startJsonServer(jsonMessage("chat raw ok"));
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            provider.chatWithRaw(config(server), MODEL, "sys", "hi");
            verify(telemetry).recordEvent(eq("tengu_api_success"), any());
            verify(telemetry).logOTelEvent(eq("api_request"), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatWithRaw 非流式 500 → recordEvent(tengu_api_error)（CC claude.ts:2720）")
    void chatWithRaw_failure_emitsTenguApiError() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startErrorServer(500);
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            try {
                provider.chatWithRaw(config(server), MODEL, "sys", "hi");
            } catch (RuntimeException expected) {
                // chatWithRaw 包装 RuntimeException 透传
            }
            verify(telemetry).recordEvent(eq("tengu_api_error"), any());
            verify(telemetry, never()).recordEvent(eq("tengu_api_success"), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatWithOptions 非流式成功 → recordEvent(tengu_api_success) + logOTelEvent(api_request)")
    void chatWithOptions_success_emitsTenguApiSuccess() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startJsonServer(jsonMessage("chat opts ok"));
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            provider.chatWithOptions(config(server), MODEL, "sys", "hi", null);
            verify(telemetry).recordEvent(eq("tengu_api_success"), any());
            verify(telemetry).logOTelEvent(eq("api_request"), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatWithOptions 非流式 500 → recordEvent(tengu_api_error)")
    void chatWithOptions_failure_emitsTenguApiError() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startErrorServer(500);
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            try {
                provider.chatWithOptions(config(server), MODEL, "sys", "hi", null);
            } catch (RuntimeException expected) {
                // chatWithOptions AnthropicServiceException → translateSdkError 透传
            }
            verify(telemetry).recordEvent(eq("tengu_api_error"), any());
            verify(telemetry, never()).recordEvent(eq("tengu_api_success"), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatWithOptionsMessage 非流式成功 → recordEvent(tengu_api_success) + logOTelEvent(api_request)")
    void chatWithOptionsMessage_success_emitsTenguApiSuccess() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        HttpServer server = startJsonServer(jsonMessage("chat msg ok"));
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            provider.chatWithOptionsMessage(config(server), MODEL, "sys", "hi", null);
            verify(telemetry).recordEvent(eq("tengu_api_success"), any());
            verify(telemetry).logOTelEvent(eq("api_request"), any());
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. telemetry 未注入（null）→ 零行为变化（既有测试基线不回归）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("telemetry 未注入（null）→ 事件跳过，不抛 NPE（既有测试零行为变化）")
    void telemetryNull_noEmissionNoNpe() throws Exception {
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = new AnthropicSdkProvider(); // telemetry = null
            CountDownLatch done = new CountDownLatch(1);
            provider.stream(config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown);
            assertThat(done.await(10, TimeUnit.SECONDS)).as("telemetry=null 时正常流仍走 onComplete").isTrue();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static AnthropicSdkProvider providerWithTelemetry(Telemetry telemetry) {
        AnthropicSdkProvider provider = new AnthropicSdkProvider();
        ReflectionTestUtils.setField(provider, "telemetry", telemetry);
        return provider;
    }

    private static ProviderConfig config(HttpServer server) {
        return new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    /** SSE 成功流：message_start（带 usage）+ message_delta + message_stop，分段写以便 abort 命中。 */
    private static HttpServer startSseServer(byte[] body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                int marker = indexOf(body, "\n\n");
                if (marker > 0) {
                    exchange.getResponseBody().write(body, 0, marker);
                    exchange.getResponseBody().flush();
                    Thread.sleep(150);
                    exchange.getResponseBody().write(body, marker, body.length - marker);
                } else {
                    exchange.getResponseBody().write(body);
                }
                exchange.getResponseBody().flush();
                exchange.close();
            } catch (Exception e) {
                // 客户端 abort 后写 body 可能抛 → 忽略（连接已断）
            }
        });
        server.start();
        return server;
    }

    /** 2xx JSON 响应（chat 非流式成功）。 */
    private static HttpServer startJsonServer(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 指定 status 的错误响应（流式 + 非流式失败）。 */
    private static HttpServer startErrorServer(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] bytes = jsonError(status).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int indexOf(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** SSE 成功流：message_start（带 usage）+ message_delta + message_stop。 */
    private static byte[] sseSuccess() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\"},"
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30}}\n"
            + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}\n"
            + "\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonMessage(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
            + "\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":7}}";
    }

    private static String jsonError(int status) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\","
            + "\"status\":" + status + "}}";
    }
}
