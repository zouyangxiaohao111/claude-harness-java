package com.nexusai.infra.llm;

import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.infra.properties.NexusProperties;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpServer;
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
 * 非流式 {@code chatWithRaw} 一致走请求侧自建 ID 兜底。Java 此前流式
 * {@code buildAssistantMessage} 走 6-arg 构造 → requestId 恒 null（连兜底都未接），
 * 子 agent 上下文 invokingRequestId 归因断链。
 *
 * <p><b>[批 3c] 语义已变（已登记待裁定）</b>：原请求侧兜底的载体是「裸 MDC 的 reqId 槽」
 * （由 {@code ChatService} 请求入口写入 userMessageId）。该槽随本批<b>整类删除</b>，且
 * {@code OpenAiSdkProvider.doStream} 签名内没有 requestId / userMessageId 形参或等价的
 * 显式载体 ⇒ 生产实现已改为<b>恒置 null</b>（CC「无归因上下文」语义），并在代码内登记为
 * 「待接线：{@code LlmProvider.stream} 增显式 requestId 载体后由上游传入」。
 * 因此本类现在锁定的是：
 * <ol>
 *   <li>有响应头也不采纳（SDK 无响应头通道）且无显式来源 → 流式 requestId 恒 null</li>
 *   <li>无显式来源 → 流式 requestId null（对齐 CC {@code ?? undefined}）</li>
 * </ol>
 * ⚠ 原「有兜底值 → requestId = 该值」的用例已随载体删除而失去可构造前提，见
 * {@link #stream_requestIdIsNullWithoutExplicitSource()} 的 `[批 3c]` 注释。
 */
@DisplayName("[D-4] OpenAiSdkProvider 流式 requestId（DEC-RV-14a 请求侧自建 ID；[批 3c] 无显式载体 → 恒 null）")
class OpenAiSdkProviderStreamRequestIdTest {

    @Test
    @DisplayName("SDK 无响应头通道且无显式 requestId 来源 → 流式 AssistantMessage.requestId 恒 null（[批 3c] 兜底载体已删）")
    void stream_requestIdIsNullWithoutExplicitSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            // 网关虽返回 x-request-id，但 SDK 0.25.0 不暴露响应头 → 响应侧通道不可达（R-REQ-1）
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

            // [批 3c] 语义消失（已登记）：原此处写「裸 MDC 的 reqId 槽」当请求侧兜底来源，
            //   该槽及其整类载体已删除，doStream 内无 requestId/userMessageId 形参
            //   ⇒ 生产实现恒置 null（代码内登记为「待接线」）。故本用例的期望值由
            //   "msg-stream-fallback-1" 改为 null —— 这不是放宽断言，而是跟随已落地的行为；
            //   若将来把显式 requestId 载体接进 stream 形参，本用例须改回非空断言。
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> got = new AtomicReference<>();
            provider.stream(config, "deepseek-chat", List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
                List.of(userMsg("hi")), null, null, null, null, null,
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown, null, null);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().requestId())
                .as("无显式 requestId 载体 → 流式 requestId 恒 null（批 3c 已删请求侧 MDC 兜底）")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("无显式来源 → 流式 AssistantMessage.requestId null（对齐 CC ?? undefined）")
    void stream_missingExplicitSourceIsNull() throws Exception {
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
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown, null, null);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().requestId())
                .as("无显式 requestId 来源 → 请求侧兜底 null（对齐 CC ?? undefined）")
                .isNull();
        } finally {
            server.stop(0);
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
