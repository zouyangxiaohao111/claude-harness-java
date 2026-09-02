package com.nexusai.infra.llm;

import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
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
 * [D-4] AnthropicSdkProvider 流式捕获 request_id 测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/api/claude.ts:1832-1834}
 * {@code .withResponse() → streamRequestId = result.request_id} +
 * {@code :2201 requestId: streamRequestId ?? undefined}。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC 把流式响应的 request_id（HTTP {@code request-id}
 * 头，{@code req_xxx} 格式，<b>非</b> message id {@code msg_xxx}）挂到 assistant message 的
 * {@code requestId} 字段，供 AgentTool.tsx:723/:778 {@code invokingRequestId:
 * assistantMessage?.requestId} 做子 agent spawn/resume 边界 analytics 归因。Java 此前
 * 流式 {@code buildAssistantMessage} 走 6-arg 构造 → requestId 恒 null（值源未捕获），
 * 子 agent 上下文 invokingRequestId 归因断链。本测试锁定：
 * <ol>
 *   <li>流式响应携带 request-id 头 → AssistantMessage.requestId 透传真实值（req_xxx）</li>
 *   <li>流式响应无 request-id 头 → AssistantMessage.requestId null（对齐 CC {@code ?? undefined}）</li>
 * </ol>
 */
@DisplayName("[D-4] AnthropicSdkProvider 流式捕获 request_id（CC claude.ts:1832-1834 withResponse）")
class AnthropicSdkProviderStreamRequestIdTest {

    private static final String MODEL = "claude-sonnet-4-6";

    @Test
    @DisplayName("流式响应带 request-id 头 → AssistantMessage.requestId 透传 req_xxx（CC streamRequestId=result.request_id）")
    void stream_capturesRequestIdHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("request-id", "req_01AbCdEf1234567890");
            byte[] sse = buildSseResponse();
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> got = new AtomicReference<>();
            provider.stream(config, MODEL, List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
                List.of(userMsg("hi")), null, null, null, null, null,
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("onComplete 必须触发").isTrue();
            assertThat(got.get()).as("流式结束必须产出 AssistantMessage").isNotNull();
            assertThat(got.get().requestId())
                .as("流式 request-id 头必须透传为 AssistantMessage.requestId（CC claude.ts:1834/:2201）")
                .isEqualTo("req_01AbCdEf1234567890");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("流式响应无 request-id 头 → AssistantMessage.requestId null（对齐 CC ?? undefined）")
    void stream_missingRequestIdHeaderIsNull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] sse = buildSseResponse();
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });
        server.start();
        try {
            ProviderConfig config = new ProviderConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> got = new AtomicReference<>();
            provider.stream(config, MODEL, List.of(new SystemPromptBlock("sys", CacheScope.NULL)),
                List.of(userMsg("hi")), null, null, null, null, null,
                c -> {}, got::set, null, null, null, null, e -> {}, done::countDown);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().requestId())
                .as("无 request-id 头时 requestId 必须 null（对齐 CC ?? undefined）")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    /** 最小有效 Anthropic SSE 流（message_start → message_delta → message_stop）。 */
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
