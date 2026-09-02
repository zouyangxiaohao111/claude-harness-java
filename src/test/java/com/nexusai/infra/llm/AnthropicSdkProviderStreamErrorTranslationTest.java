package com.nexusai.infra.llm;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.RateLimitException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [P-1 / P-33] Anthropic SDK 异常翻译 → LlmApiException（headers 保留）· 对齐 CC
 * claude.ts:2501/2451（throw streamingError 恒为 APIError）+ withRetry.ts:519-528
 * （getRetryAfter 读 error.headers['retry-after']）+ :814-822（getRateLimitResetDelayMs 读
 * anthropic-ratelimit-unified-reset）+ errors.ts:489-490（错误消息面读 headers）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：旧实现 doStream:342 把 raw
 * {@code AnthropicServiceException} 交 onError、chatWithOptions:901 用 emptyMap 构造
 * LlmApiException —— headers 全丢 → LlmAgentLoop Path3 {@code instanceof LlmApiException} 类型闸
 * 失守 → Anthropic 流式路径 429/529 整链不可重试（探查 retry-delay △-B / P-1）。本测试锁定：
 * <ol>
 *   <li><b>流式 429 → onError 收 LlmApiException(429) 且 headers（retry-after / unified-reset）保留</b>
 *       （P-33 集成轨迹补证）——RED teeth：把 doStream catch 换回 onError.accept(e) raw 即 fail；</li>
 *   <li><b>chatWithOptions 429 → LlmApiException(429) + headers 直证</b>——RED teeth：把
 *       translateSdkError 换回 emptyMap 构造即 fail；</li>
 *   <li><b>translateSdkError 单测</b>（RateLimitException.builder()/Headers.builder() @JvmStatic 实证：
 *       status 保留 / headers 逐项复制 / body / Kind.IMAGE / RuntimeException 透传 / 其余包装）。</li>
 * </ol>
 */
class AnthropicSdkProviderStreamErrorTranslationTest {

    private static final String MODEL = "claude-sonnet-4-5";

    // ════════════════════════════════════════════════════════════════════
    // ① 流式 429 → onError LlmApiException + headers 保留（P-33 集成轨迹）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("流式 429 → onError 收 LlmApiException(429) + retry-after/unified-reset headers 保留（P-33）")
    void streaming429_onErrorGetsLlmApiExceptionWithHeaders() throws Exception {
        String resetHeader = String.valueOf((System.currentTimeMillis() / 1000) + 600);
        HttpServer server = startServer("/v1/messages", exchange -> respond429(exchange, resetHeader));
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> gotError = new AtomicReference<>();

            // onStreamingFallback=null → 跳过非流式回退，直接走 onError（CC claude.ts:2476-2501
            // disableFallback 门 throw streamingError 等价：翻译后异常必须到达 onError）
            provider.stream(config, MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(), null, null, null, null, null,
                chunk -> {
                }, msg -> {
                }, null, null,
                null, // onStreamingFallback = null（跳过回退）
                null, // abortController = null
                err -> {
                    gotError.set(err);
                    done.countDown();
                },
                () -> {
                });

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("流式 429 应在有限时间内经 onError 送达")
                .isTrue();
            assertThat(gotError.get())
                .as("onError 必须收到 LlmApiException（翻译后，非 raw SDK 异常）")
                .isInstanceOf(LlmApiException.class);
            LlmApiException lae = (LlmApiException) gotError.get();
            assertThat(lae.status())
                .as("429 状态码保留 · CC APIError.status")
                .isEqualTo(429);
            assertThat(lae.getHeader("retry-after"))
                .as("retry-after header 保留可达 · CC withRetry.ts:519-528 getRetryAfter")
                .isEqualTo("3");
            assertThat(lae.getHeader("anthropic-ratelimit-unified-reset"))
                .as("unified-reset header 保留可达 · CC withRetry.ts:814-822 getRateLimitResetDelayMs")
                .isEqualTo(resetHeader);
            assertThat(lae.body())
                .as("429 响应体保留（rate_limit_error 面）")
                .contains("rate_limit_error");
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ② chatWithOptions 429 → LlmApiException + headers 直证（P-1 · chatWithOptions:901 emptyMap 替换）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("chatWithOptions 429 → throw LlmApiException(429) + retry-after/unified-reset headers 保留")
    void chatWithOptions429_preservesHeaders() throws Exception {
        String resetHeader = String.valueOf((System.currentTimeMillis() / 1000) + 600);
        HttpServer server = startServer("/v1/messages", exchange -> respond429(exchange, resetHeader));
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            assertThatThrownBy(() -> provider.chatWithOptions(
                config, MODEL, "system", "hello", null))
                .as("chatWithOptions 429 必须抛 LlmApiException（headers 保留，非 emptyMap 构造）")
                .isInstanceOf(LlmApiException.class)
                .satisfies(t -> {
                    LlmApiException lae = (LlmApiException) t;
                    assertThat(lae.status()).isEqualTo(429);
                    assertThat(lae.getHeader("retry-after")).isEqualTo("3");
                    assertThat(lae.getHeader("anthropic-ratelimit-unified-reset")).isEqualTo(resetHeader);
                    assertThat(lae.body()).contains("rate_limit_error");
                });
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ translateSdkError 单测（@JvmStatic builder 实证）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("translateSdkError: RateLimitException → LlmApiException(status=429, headers 逐项复制, body)")
    void translateSdkError_rateLimitException_preservesStatusHeadersBody() {
        Headers headers = Headers.builder()
            .put("retry-after", "7")
            .put("anthropic-ratelimit-unified-reset", "1899999999")
            .build();
        RateLimitException sdkEx = RateLimitException.builder()
            .headers(headers)
            .body(JsonValue.from("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}"))
            .build();

        RuntimeException translated = AnthropicSdkProvider.translateSdkError(sdkEx);

        assertThat(translated)
            .as("翻译为 LlmApiException（非 raw 透传）")
            .isInstanceOf(LlmApiException.class);
        LlmApiException lae = (LlmApiException) translated;
        assertThat(lae.status())
            .as("RateLimitException.statusCode()=429 保留 · CC APIError.status")
            .isEqualTo(429);
        assertThat(lae.getHeader("retry-after"))
            .as("headers 逐项复制（retry-after）· CC withRetry.ts:519-528")
            .isEqualTo("7");
        assertThat(lae.getHeader("anthropic-ratelimit-unified-reset"))
            .as("headers 逐项复制（unified-reset）· CC withRetry.ts:814-822")
            .isEqualTo("1899999999");
        assertThat(lae.body())
            .as("响应体保留（String.valueOf(JsonValue)）")
            .contains("rate_limit_error");
        assertThat(lae.kind())
            .as("非图片错误体 → Kind.GENERIC")
            .isEqualTo(LlmApiException.Kind.GENERIC);
    }

    @Test
    @DisplayName("translateSdkError: 图片错误体 → LlmApiException.imageError（Kind.IMAGE，LlmAgentLoop.isImageError 类型化优先）")
    void translateSdkError_imageErrorBody_kindImage() {
        RateLimitException sdkEx = RateLimitException.builder()
            .headers(Headers.builder().build())
            .body(JsonValue.from("{\"type\":\"error\",\"error\":{\"type\":\"image_error\",\"message\":\"image too large\"}}"))
            .build();

        RuntimeException translated = AnthropicSdkProvider.translateSdkError(sdkEx);

        assertThat(translated)
            .as("图片错误体 → imageError 工厂")
            .isInstanceOf(LlmApiException.class);
        assertThat(((LlmApiException) translated).kind())
            .as("Kind.IMAGE（CC 媒体错误分类 · LlmAgentLoop.isImageError:6204 类型化优先）")
            .isEqualTo(LlmApiException.Kind.IMAGE);
    }

    @Test
    @DisplayName("translateSdkError: RuntimeException 透传同一实例；其他 Throwable 包装")
    void translateSdkError_runtimePassthrough_elseWrap() {
        RuntimeException re = new IllegalStateException("plain runtime");
        assertThat(AnthropicSdkProvider.translateSdkError(re))
            .as("RuntimeException 透传（CancellationException/HttpTimeoutException 判定类型不变）")
            .isSameAs(re);

        Throwable checked = new java.io.IOException("boom");
        RuntimeException wrapped = AnthropicSdkProvider.translateSdkError(checked);
        assertThat(wrapped)
            .as("非 RuntimeException 包装为 RuntimeException")
            .isInstanceOf(RuntimeException.class);
        assertThat(wrapped.getCause())
            .as("原异常保留为 cause（ErrorClassifier.isConnectionError 5 层解包可达）")
            .isSameAs(checked);
    }

    // ════════════════════════════════════════════════════════════════════
    // 本地 HttpServer 工具（AnthropicSdkProviderStreamingNonStreamingFallbackTest 同款模式）
    // ════════════════════════════════════════════════════════════════════

    private static HttpServer startServer(String context, HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, handler);
        server.start();
        return server;
    }

    private static void respond429(HttpExchange exchange, String unifiedReset) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("retry-after", "3");
        exchange.getResponseHeaders().set("anthropic-ratelimit-unified-reset", unifiedReset);
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
            + "\"message\":\"Number of request tokens exceeded your rate limit\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(429, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
