package com.nexusai.infra.llm;

import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.properties.NexusProperties;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D-4] OpenAiSdkProvider 流式 requestId 兜底测试 · 对齐非流式 {@code chatWithRaw}
 * DEC-RV-14a 兜底语义（SDK 0.25.0 无 withRawResponse → 响应侧 request-id 头不可达）。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: openai-java 0.25.0 {@code CompletionService} 无
 * {@code withRawResponse}（Anthropic 有），流式响应侧 x-request-id 头无法提取，故与
 * 非流式 {@code chatWithRaw} 一致走请求侧自建 ID（{@link RequestContext#requestId()} =
 * MDC reqId = userMessageId）兜底。Java 此前流式 {@code buildAssistantMessage} 走 6-arg
 * 构造 → requestId 恒 null（连兜底都未接），子 agent 上下文 invokingRequestId 归因断链。
 * 本测试锁定：
 * <ol>
 *   <li>设置 MDC reqId → 流式 AssistantMessage.requestId = MDC reqId（请求侧兜底）</li>
 *   <li>未设 MDC → 流式 AssistantMessage.requestId null（对齐 CC {@code ?? undefined}）</li>
 * </ol>
 */
@DisplayName("[D-4] OpenAiSdkProvider 流式 requestId 兜底（DEC-RV-14a 请求侧自建 ID）")
class OpenAiSdkProviderStreamRequestIdTest {

    @AfterEach
    void clearMdc() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("设置 MDC reqId → 流式 AssistantMessage.requestId = 请求侧兜底（DEC-RV-14a）")
    void stream_requestIdFallsBackToRequestContext() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            // 网关虽返回 x-request-id，但 SDK 0.25.0 不暴露响应头 → 走请求侧兜底（R-REQ-1）
            exchange.getResponseHeaders().set("x-request-id", "req_openai_abc123");
            byte[] sse = buildSseResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = newProviderWithProperties();

            RequestContext.set("sess-x", "msg-stream-fallback-1");
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> got = new AtomicReference<>();
            provider.stream(config, "deepseek-chat", List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
                List.of(userMsg("hi")), null, null, null, null, null,
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().requestId())
                .as("SDK 无响应头通道 → 流式 requestId 兜底为 MDC reqId（userMessageId · DEC-RV-14a）")
                .isEqualTo("msg-stream-fallback-1");
        } finally {
            server.stop(0);
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("未设 MDC → 流式 AssistantMessage.requestId null（对齐 CC ?? undefined）")
    void stream_missingRequestContextIsNull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] sse = buildSseResponse();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = newProviderWithProperties();

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> got = new AtomicReference<>();
            provider.stream(config, "deepseek-chat", List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
                List.of(userMsg("hi")), null, null, null, null, null,
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().requestId())
                .as("无 MDC reqId → 请求侧兜底 null（对齐 CC ?? undefined）")
                .isNull();
        } finally {
            server.stop(0);
            RequestContext.clear();
        }
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

    /** OpenAI SSE 流响应 · SDK ChatCompletionChunk 必填字段（id/object/created/model/choices）。 */
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
