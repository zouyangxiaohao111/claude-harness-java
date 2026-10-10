package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.properties.NexusProperties;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [流空闲看门狗 · T6] OpenAiSdkProvider 看门狗接线（规格 §3 · OpenAI 通道）。
 *
 * <p><b>WHY（规则九）</b>：OpenAI 通道的中止原语 = {@code timeout(Duration)}（实测=读空闲语义：
 * 活跃流存活、静默 ≥ 阈值在读线程内抛 {@code SocketTimeoutException: timeout}）。三处必须钉：
 * ①静默 → stall（progress 五态经 OpenAI chunk 映射：非空 content=anyOutputShown、finish_reason=
 * anyBlockFinished）；②完整流（含 data:[DONE]）不受干扰；③forceNonStreaming → <b>跳过流式直接
 * 非流式发送</b>（与 Anthropic 同形；合并带来 nonStreamingSend 完整管线后接线，原 fail-loud 删除——
 * 回退失败时交付 NOTHING stall 交判决矩阵，⛔ 非静默）。
 */
@DisplayName("[看门狗 T6] OpenAiSdkProvider 读超时→stall / 完整对照 / 强制非流式跳过流式")
class OpenAiSdkProviderStreamIdleWatchdogTest {

    private static String chunkJson(String content, String finishReason) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":"
            + (content == null ? "{}" : "{\"role\":\"assistant\",\"content\":\"" + content + "\"}")
            + ",\"finish_reason\":" + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]}\n\n";
    }

    /** 静默脚本：发一个内容 chunk 后保持连接不说话（无 finish、无 [DONE]）。 */
    private static final String SSE_STALL = chunkJson("hel", null);

    /** 完整脚本：内容 chunk + finish chunk + [DONE]。 */
    private static final String SSE_COMPLETE = chunkJson("hel", null)
        + chunkJson(null, "stop")
        + "data: [DONE]\n\n";

    // ══════════════════════════════ 用例 1 ══════════════════════════════

    @Test
    @DisplayName("静默 ≥ idleTimeoutMs → 读超时解阻塞 → onError(StreamIdleTimeoutError, PARTIAL_OUTPUT)")
    void idleTimeout_mapsToStall() throws Exception {
        try (SseStub stub = new SseStub(SSE_STALL, true)) {
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
            injectProperties(provider);
            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicReference<String> text = new AtomicReference<>("");
            CountDownLatch completed = new CountDownLatch(1);

            long t0 = System.currentTimeMillis();
            provider.stream(config(stub), "deepseek-chat",
                java.util.List.<com.nexusai.application.agent.prompt.SystemPromptBlock>of(),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> text.updateAndGet(t -> t + c), m -> { }, (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set,
                completed::countDown,
                null, null,
                new StreamIdleControl(new AbortController(), false, 1_500L));
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(err.get()).as("静默到读超时后必须经 onError 交付 stall")
                .isInstanceOf(StreamIdleTimeoutError.class);
            StreamIdleTimeoutError sit = (StreamIdleTimeoutError) err.get();
            assertThat(sit.progress())
                .as("有 content 输出、无 finish_reason → PARTIAL_OUTPUT（OpenAI chunk → 三标志映射）")
                .isEqualTo(StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT);
            assertThat(completed.getCount()).as("stall 时 onComplete 不得触发").isEqualTo(1);
            assertThat(text.get()).as("超时前增量保留").isEqualTo("hel");
            assertThat(elapsed).as("≈ 读超时（1.5s 量级）").isLessThan(8_000L);
        }
    }

    // ══════════════════════════════ 用例 2（对照） ══════════════════════════════

    @Test
    @DisplayName("对照：完整流（finish + [DONE]）→ onComplete 照常，无 stall 误报")
    void normalCompletion_unaffected() throws Exception {
        try (SseStub stub = new SseStub(SSE_COMPLETE, false)) {
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
            injectProperties(provider);
            AtomicReference<Throwable> err = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            provider.stream(config(stub), "deepseek-chat",
                java.util.List.<com.nexusai.application.agent.prompt.SystemPromptBlock>of(),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> { }, m -> { }, (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set,
                completed::countDown,
                null, null,
                StreamIdleControl.NONE);

            assertThat(completed.await(10, TimeUnit.SECONDS)).as("完整流必须正常收尾").isTrue();
            assertThat(err.get()).as("不得产生 stall 误报").isNull();
        }
    }

    // ══════════════════════════════ 用例 3 ══════════════════════════════

    @Test
    @DisplayName("forceNonStreaming → 跳过流式直接非流式发送（retryWithoutStreaming；与 Anthropic 同形）")
    void forceNonStreaming_bypassesStreamToNonStreaming() throws Exception {
        // 只回非流式 JSON 的最小桩：入口应完全不发流式请求（streamRequests 断言 0）
        java.util.concurrent.atomic.AtomicInteger streamRequests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                streamRequests.incrementAndGet();   // ⛔ 不应发生（已跳过流式）
            }
            byte[] json = ("{\"id\":\"c3\",\"object\":\"chat.completion\",\"created\":0,"
                + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"non-streaming degraded reply\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":9,\"total_tokens\":19}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
            injectProperties(provider);
            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicReference<AssistantMessage> gotMsg = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            provider.stream(new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "k"),
                "deepseek-chat",
                java.util.List.<com.nexusai.application.agent.prompt.SystemPromptBlock>of(),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> { }, gotMsg::set, (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set, completed::countDown,
                null, null,
                new StreamIdleControl(null, true, 0L));

            assertThat(completed.await(10, TimeUnit.SECONDS)).as("降级非流式必须正常收尾").isTrue();
            assertThat(err.get()).as("不得报错（合并后接线：fail-loud 已删除）").isNull();
            assertThat(gotMsg.get()).as("非流式完整回复必须送达").isNotNull();
            assertThat(gotMsg.get().content())
                .as("回复内容 = 非流式 JSON 的 message.content").isEqualTo("non-streaming degraded reply");
            assertThat(streamRequests.get()).as("⛔ 不得发出任何流式请求（跳过流式直达非流式）").isZero();
        } finally {
            server.stop(0);
        }
    }

    // ══════════════════════════════ helpers ══════════════════════════════

    /**
     * 反射注入 NexusProperties（chunk 解析的 reasoning 字段依赖它；缺失时 parseChunk 每 chunk 抛 NPE
     * 被 catch 吞 —— finish_reason 一并丢失 → [stream-integrity] 判截断。OpenAiSdkProviderStreamTruncationTest
     * 同款先例）。合并（master 流完整性校验）后本注入成为「完整流正常完成」用例的必要条件。
     */
    private static void injectProperties(OpenAiSdkProvider provider) throws Exception {
        java.lang.reflect.Field pf = OpenAiSdkProvider.class.getDeclaredField("properties");
        pf.setAccessible(true);
        pf.set(provider, new NexusProperties());
    }

    private static ProviderConfig config(SseStub stub) {
        return new ProviderConfig(stub.baseUrl(), "test-key");
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto("m1", null, Role.user, "user", text, null, List.of(),
            null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of());
    }

    /** 本地桩：sse=true 发 text/event-stream（holdOpen=true 时发完脚本保持静默）。 */
    private static final class SseStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SseStub(String script, boolean holdOpen) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                try {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                        os.write(script.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        if (holdOpen) {
                            while (!closed.get()) {
                                Thread.sleep(50);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // 客户端读超时中止本地读后服务端写失败属预期
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            closed.set(true);
            server.stop(0);
        }
    }
}
