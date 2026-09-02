package com.nexusai.infra.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RV-03-04] 流式→非流式回退意图测试 · DEC-RV-03 · 对齐 CC claude.ts:2404-2562。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>：CC 流式失败（非用户中止/非 SDK 超时/未门控禁用）
 * 时回退非流式重试（claude.ts:2505-2562），并把流式 529 预置进非流式 529 预算
 * （claude.ts:2559 is529Error(streamingError) ? 1 : 0 → withRetry.ts:186 ?? 0）。Java 此前
 * doStream 静默丢弃 onStreamingFallback 且无非流式回退（死回调），本测试锁定：
 * <ol>
 *   <li><b>流式中途失败 → 非流式回退成功</b>（onStreamingFallback 触发 + 非流式结果经
 *       onAssistantMessage 送达 + onComplete，onError 不触发）——RED teeth：revert doStream
 *       catch 的回退接线 / 丢弃 onStreamingFallback 即 fail；</li>
 *   <li><b>流式 529 → 预置计数 1</b>（claude.ts:2559 公式）——RED teeth：把预置改成恒 0 即 fail；</li>
 *   <li><b>disableFallback 门 → 不回退</b>（claude.ts:2469-2501）——RED teeth：删门即 fail。</li>
 * </ol>
 */
class AnthropicSdkProviderStreamingNonStreamingFallbackTest {

    private static final String MODEL = "claude-sonnet-4-5";

    // ════════════════════════════════════════════════════════════════════
    // ① 流式中途失败 → 非流式回退成功（端到端 · 本地 HttpServer）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("流式中途失败 → onStreamingFallback 触发 + 非流式回退结果经 onAssistantMessage 送达 + onComplete，onError 不触发")
    void streamingFailure_fallsBackToNonStreaming() throws Exception {
        // 路由：stream=true → 2xx SSE 头 + 部分事件后截断（客户端迭代 IOException → doStream catch）；
        //       stream=false → 200 JSON text（非流式回退成功）
        HttpServer server = startServer("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                // 声明 200 字节但只写 ~31 字节后关闭 → 客户端按 Content-Length 等待更多字节，
                // 连接提前 EOF → 流式迭代抛 IOException（进 doStream catch → 非流式回退）
                exchange.sendResponseHeaders(200, 200);
                exchange.getResponseBody().write(
                    "event: message_start\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                exchange.close();
            } else {
                respond(exchange, 200,
                    "{\"content\":[{\"type\":\"text\",\"text\":\"fallback result\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"output_tokens\":7}}");
            }
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean fallbackFired = new AtomicBoolean(false);
            AtomicReference<AssistantMessage> gotMsg = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicReference<Throwable> gotError = new AtomicReference<>();

            provider.stream(config, MODEL, List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock("sys", com.nexusai.application.agent.prompt.CacheScope.ORG)), List.of(), null,
                null, null, null, null,   // maxOutputTokensOverride / taskBudget / effortValue / querySource
                chunk -> {
                }, gotMsg::set, null, null,
                () -> fallbackFired.set(true), // onStreamingFallback · CC claude.ts:2508-2512
                null, // abortController = null（无硬中断）
                gotError::set,
                () -> {
                    completed.set(true);
                    done.countDown();
                });

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("流式失败→非流式回退应在有限时间内完成")
                .isTrue();
            assertThat(fallbackFired)
                .as("流式中途失败必须触发 onStreamingFallback（CC claude.ts:2508-2512）")
                .isTrue();
            assertThat(gotMsg.get())
                .as("非流式回退结果必须经 onAssistantMessage 送达")
                .isNotNull();
            assertThat(gotMsg.get().content())
                .as("回退结果 = 非流式响应 text")
                .isEqualTo("fallback result");
            assertThat(gotMsg.get().outputTokens())
                .as("非流式响应 usage.output_tokens 提取")
                .isEqualTo(7L);
            assertThat(completed)
                .as("回退成功后必须 onComplete（与 onError 互斥）")
                .isTrue();
            assertThat(gotError.get())
                .as("回退成功不得触发 onError")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 流式 529 → 预置计数 1（CC claude.ts:2559）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("流式 529 → initialConsecutive529Errors 预置 1；非 529 → 0（claude.ts:2559）")
    void streaming529_presetsConsecutiveCountToOne() {
        assertThat(AnthropicSdkProvider.computeInitialConsecutive529Errors(
            new LlmApiException(529, Map.of(), "overloaded")))
            .as("流式 529 预置计数 1 · claude.ts:2559 is529Error(streamingError) ? 1 : 0")
            .isEqualTo(1);
        assertThat(AnthropicSdkProvider.computeInitialConsecutive529Errors(
            new LlmApiException(500, Map.of(), "server error")))
            .as("非 529 流式错误预置 0")
            .isZero();
        assertThat(AnthropicSdkProvider.computeInitialConsecutive529Errors(
            new RuntimeException("connection reset")))
            .as("非 LlmApiException 预置 0")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ disableFallback 门 → 不回退（CC claude.ts:2469-2501）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("disableFallback 门 / 用户中止 / SDK 超时 → 不回退；其余流式错误 → 回退")
    void gate_blocksOrAllowsFallback() {
        AtomicBoolean notAborted = new AtomicBoolean(false);
        AtomicBoolean aborted = new AtomicBoolean(true);
        IOException streamErr = new IOException("premature EOF");

        // 门控禁用（env CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK / feature flag）→ 不回退
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(streamErr, notAborted, true))
            .as("disableFallback 门开启 → 直接抛流式错误（claude.ts:2476-2501）")
            .isFalse();
        // 用户中止（signal.aborted）→ 不回退（claude.ts:2443 rethrow）
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(streamErr, aborted, false))
            .as("用户中止 → rethrow，不回退")
            .isFalse();
        // SDK 超时（Java HttpTimeoutException / SocketTimeoutException）→ 不回退（claude.ts:2457）
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(
            new HttpTimeoutException("timed out"), notAborted, false))
            .as("SDK 超时 → throw APIConnectionTimeoutError，不回退")
            .isFalse();
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(
            new SocketTimeoutException("read timed out"), notAborted, false))
            .as("SocketTimeoutException → 不回退")
            .isFalse();
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(
            new java.util.concurrent.CancellationException("abort"), notAborted, false))
            .as("CancellationException（AbortController 硬中断）→ 不回退")
            .isFalse();
        // 其余流式错误（连接中断等）→ 回退（claude.ts:2505-2512，无 isRetryable 门）
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(streamErr, notAborted, false))
            .as("流式连接中断 → 回退非流式")
            .isTrue();
        assertThat(AnthropicSdkProvider.shouldUseNonStreamingFallback(
            new LlmApiException(529, Map.of(), "overloaded"), notAborted, false))
            .as("流式 529 → 回退（预置计数 1）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 本地 HttpServer 工具（AnthropicCountTokensClientTest 同款模式）
    // ════════════════════════════════════════════════════════════════════

    private static HttpServer startServer(String context, HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
