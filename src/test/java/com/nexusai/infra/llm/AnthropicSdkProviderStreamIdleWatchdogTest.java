package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
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
 * [流空闲看门狗 · T4] AnthropicSdkProvider 看门狗接线（规格 §3 · 对齐 CC 2.1.296）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：三条用例各钉一个"错了会静默"的点：
 * <ol>
 *   <li><b>静默 → 读超时解阻塞 → stall 映射</b>：中止原语 = {@code Timeout.read(idleTimeoutMs)}
 *       （实测 close() 死锁 / interrupt 无效 ⇒ 唯一可控通道，见 {@link StreamIdleAbortChannelProbeTest}）。
 *       必须经 {@code onError} 交付（⛔ 不向调用线程抛——调用方在 STREAM_EXECUTOR，抛出会被吞）；
 *       progress = PARTIAL_OUTPUT（有输出、块未完成 = CC Gan exe 223,030,407）。</li>
 *   <li><b>正常完成不受影响</b>（对照）：完整流照常 onComplete、无 stall 误报。</li>
 *   <li><b>forceNonStreaming 绕流式</b>（CC retryWithoutStreaming · exe 223,028,125）：
 *       判据 = 出站请求体<b>没有</b> {@code "stream":true}。</li>
 * </ol>
 */
@DisplayName("[看门狗 T4] AnthropicSdkProvider 读超时解阻塞→stall / 正常对照 / 强制非流式")
class AnthropicSdkProviderStreamIdleWatchdogTest {

    private static final String SSE_HEAD =
        "event: message_start\n"
        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\","
        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        + "event: content_block_start\n"
        + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
        + "event: content_block_delta\n"
        + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}\n\n";

    /** 静默脚本：发头部事件（含文本增量）后保持连接不说话。 */
    private static final String SSE_STALL = SSE_HEAD;

    /** 完整脚本：头部事件 + 收尾（块 stop / stop_reason / message_stop）。 */
    private static final String SSE_COMPLETE = SSE_HEAD
        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    private static final String JSON_MESSAGE =
        "{\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude\","
        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    // ══════════════════════════════ 用例 1 ══════════════════════════════

    @Test
    @DisplayName("静默 ≥ idleTimeoutMs → 读超时解阻塞 → onError(StreamIdleTimeoutError, PARTIAL_OUTPUT)")
    void idleTimeout_unblocksSilentRead_andMapsToStall() throws Exception {
        try (SseStub stub = new SseStub(SSE_STALL, true, true)) {
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicReference<String> text = new AtomicReference<>("");
            CountDownLatch completed = new CountDownLatch(1);

            long t0 = System.currentTimeMillis();
            provider.stream(config(stub), "claude-x", List.of(sys("sys")), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> text.updateAndGet(t -> t + c), m -> { }, (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set,
                completed::countDown,
                null, null,
                new StreamIdleControl(new AbortController(), false, 1_500L));   // 武装 1.5s 流式读超时
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(err.get())
                .as("静默到读超时后必须经 onError 交付 stall（交付走回调；不向调用线程抛）")
                .isInstanceOf(StreamIdleTimeoutError.class);
            StreamIdleTimeoutError sit = (StreamIdleTimeoutError) err.get();
            assertThat(sit.progress())
                .as("有输出且块未完成 → PARTIAL_OUTPUT（CC Gan exe 223,030,407）")
                .isEqualTo(StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT);
            assertThat(sit.stopReasonReceived()).as("未收到 stop_reason").isFalse();
            assertThat(completed.getCount()).as("stall 时 onComplete 不得触发").isEqualTo(1);
            assertThat(text.get()).as("超时前已送达的增量保留").isEqualTo("hel");
            assertThat(elapsed)
                .as("返回耗时 ≈ 读超时（1.5s 量级）而非更久——证明是读超时通道在解阻塞")
                .isLessThan(8_000L);
        }
    }

    // ══════════════════════════════ 用例 2（对照） ══════════════════════════════

    @Test
    @DisplayName("对照：完整流 → onComplete 照常触发，无 stall 误报")
    void normalCompletion_unaffected() throws Exception {
        try (SseStub stub = new SseStub(SSE_COMPLETE, true, false)) {
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicReference<String> text = new AtomicReference<>("");
            CountDownLatch completed = new CountDownLatch(1);

            provider.stream(config(stub), "claude-x", List.of(sys("sys")), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> text.updateAndGet(t -> t + c), m -> { }, (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set,
                completed::countDown,
                null, null,
                StreamIdleControl.NONE);

            assertThat(completed.await(10, TimeUnit.SECONDS)).as("完整流必须正常收尾").isTrue();
            assertThat(err.get()).as("不得产生 stall 误报").isNull();
            assertThat(text.get()).as("文本增量照常交付").isEqualTo("hel");
        }
    }

    // ══════════════════════════════ 用例 3 ══════════════════════════════

    @Test
    @DisplayName("forceNonStreaming → 出站请求无 \"stream\":true（CC retryWithoutStreaming 路径）")
    void forceNonStreaming_bypassesStream() throws Exception {
        try (SseStub stub = new SseStub(JSON_MESSAGE, false, false)) {
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            AtomicReference<String> assistantText = new AtomicReference<>();
            AtomicReference<Throwable> err = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            provider.stream(config(stub), "claude-x", List.of(sys("sys")), List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> { }, m -> assistantText.set(m.content()), (ToolUseBlock t) -> { }, r -> { }, () -> { },
                null, err::set,
                completed::countDown,
                null, null,
                new StreamIdleControl(null, true, 0L));

            assertThat(completed.await(10, TimeUnit.SECONDS))
                .as("forceNonStreaming 必须走非流式发送并正常完成").isTrue();
            assertThat(err.get()).as("非流式成功时不得报错").isNull();
            assertThat(assistantText.get()).as("非流式响应文本必须还原").isEqualTo("ok");
            assertThat(stub.lastBody.get())
                .as("出站请求体不得带 \"stream\":true（必须是 messages.create 非流式请求）")
                .doesNotContain("\"stream\":true");
        }
    }

    // ══════════════════════════════ helpers ══════════════════════════════

    private static ProviderConfig config(SseStub stub) {
        return new ProviderConfig(stub.baseUrl(), "test-key");
    }

    private static SystemPromptBlock sys(String text) {
        return new SystemPromptBlock(text, CacheScope.ORG);
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto("m1", null, Role.user, "user", text, null, List.of(),
            null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of());
    }

    /** 本地桩：sse=true 发 text/event-stream（holdOpen=true 时发完脚本保持静默）；否则回 JSON。 */
    private static final class SseStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<String> lastBody = new AtomicReference<>();

        SseStub(String script, boolean sse, boolean holdOpen) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ex -> {
                lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    if (sse) {
                        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                        ex.sendResponseHeaders(200, 0);
                        try (OutputStream os = ex.getResponseBody()) {
                            // 先写注释逼出响应头（零 body 写入时 HttpServer 不刷头——T0 首跑教训）
                            os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                            os.write(script.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            if (holdOpen) {
                                while (!closed.get()) {
                                    Thread.sleep(50);
                                }
                            }
                        }
                    } else {
                        byte[] body = script.getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, body.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(body);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // 客户端断开后写入失败属预期（读超时中止本地读，服务端连接随后由 close() 收）
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
