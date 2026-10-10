package com.nexusai.infra.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [流空闲看门狗 · 中止通道探针] 钉死"消费若干事件后阻塞在 {@code it.next()}"时，三条候选
 * 解阻塞通道的<b>实测行为</b>——这是看门狗中止原语选型的唯一证据（SDK anthropic-java 2.53.0）：
 *
 * <ol>
 *   <li><b>close() 死锁</b>：另一线程调 {@code StreamResponse.close()} 自身会被卡住（3s 内不返回），
 *       读者也不被解开。⚠️ T0 早期 spike（"零消费即关流"）会通过——<b>场景覆盖不足的假绿</b>；
 *       真实 stall 场景 = 已消费 >0 事件 ⇒ 本探针的判据才是对的。</li>
 *   <li><b>Thread.interrupt() 无效</b>：reader.interrupt() 后 3s 仍未解开。</li>
 *   <li><b>Timeout.read 有效</b>：客户端以 {@code Timeout.default().toBuilder().read(...)} 武装读超时后，
 *       静默 ≥ 该值 ⇒ 读线程内抛 {@code SocketTimeoutException}（天然解开，无需外部动作）
 *       ⇒ 采用为看门狗中止原语（见 AnthropicSdkProvider.buildClient 换挡说明）。</li>
 * </ol>
 *
 * <p>WHY 常驻（规则九/护栏反证）：这是 <b>SDK 版本绑定的行为证据</b>——SDK 升级若修好 close() 或
 * 改掉读超时语义，本探针会第一时间翻红，阻止我们默默沿用错误原语。
 */
@DisplayName("[看门狗·探针] 中止通道实测：close 死锁 / interrupt 无效 / readTimeout 有效")
class StreamIdleAbortChannelProbeTest {

    private static final String SCRIPT =
        "event: message_start\n"
        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\","
        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
        + "event: content_block_start\n"
        + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
        + "event: content_block_delta\n"
        + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}\n\n";

    private static HttpServer startStub(AtomicBoolean closed) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                os.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
                os.flush();
                while (!closed.get()) {
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {
            }
        });
        server.start();
        return server;
    }

    /** 建流并消费 3 个事件（= 真实 stall 场景的前置：已有消费、随后静默）。 */
    private static Object[] connectAndDrain3(HttpServer server, Long readIdleMs) throws Exception {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
            .apiKey("k")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .maxRetries(0);
        if (readIdleMs != null) {
            b.timeout(defaultTimeout().toBuilder().read(Duration.ofMillis(readIdleMs)).build());
        }
        AnthropicClient client = b.build();
        MessageCreateParams params = MessageCreateParams.builder()
            .model("claude-probe").maxTokens(1L).addUserMessage("hi").build();
        StreamResponse<RawMessageStreamEvent> resp =
            client.messages().withRawResponse().createStreaming(params).parse();
        Iterator<RawMessageStreamEvent> it = resp.stream().iterator();
        for (int i = 0; i < 3; i++) {
            if (!it.hasNext()) {
                break;
            }
            it.next();
        }
        return new Object[]{resp, it};
    }

    /** Kotlin `Timeout.default()` 名字是 Java 关键字（javac 直呼不了）⇒ 反射桥（与 provider 同法）。 */
    private static Timeout defaultTimeout() {
        try {
            return (Timeout) Timeout.class.getMethod("default").invoke(null);
        } catch (ReflectiveOperationException staticMiss) {
            try {
                Object companion = Timeout.class.getField("Companion").get(null);
                return (Timeout) companion.getClass().getMethod("default").invoke(companion);
            } catch (ReflectiveOperationException companionMiss) {
                throw new IllegalStateException("Timeout.default() 反射失败（SDK 版本变更？）", companionMiss);
            }
        }
    }

    @Test
    @DisplayName("Phase A：消费后静默时 close() 死锁（close 线程 3s 不返回、读者不解）")
    void phaseA_closeDeadlocks() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        HttpServer server = startStub(closed);
        try {
            Object[] c = connectAndDrain3(server, null);
            StreamResponse<RawMessageStreamEvent> resp = (StreamResponse<RawMessageStreamEvent>) c[0];
            Iterator<RawMessageStreamEvent> it = (Iterator<RawMessageStreamEvent>) c[1];

            AtomicReference<Object> shape = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    shape.set(it.next());
                } catch (Throwable t) {
                    shape.set(t);
                }
            }, "probe-reader-A");
            reader.setDaemon(true);
            reader.start();
            Thread.sleep(500);
            assertThat(shape.get()).as("前置：第 4 次读必须已阻塞").isNull();

            AtomicReference<String> closeState = new AtomicReference<>("RUNNING");
            Thread closer = new Thread(() -> {
                try {
                    resp.close();
                    closeState.set("RETURNED");
                } catch (Throwable t) {
                    closeState.set("THREW:" + t);
                }
            }, "probe-closer-A");
            closer.setDaemon(true);
            closer.start();

            closer.join(3_000);
            assertThat(closeState.get())
                .as("close() 在消费后静默场景会死锁（3s 不返回）——若此断言翻红，说明 SDK 已修好 close，"
                    + "可考虑回归 close 原语（但需同步改 buildClient/换挡说明）")
                .isEqualTo("RUNNING");
            reader.join(2_000);
            assertThat(shape.get())
                .as("close 卡住时读者也不被解开").isNull();
        } finally {
            closed.set(true);
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Phase B：消费后静默时 Thread.interrupt() 不解阻塞（reader 3s 仍活）")
    void phaseB_interruptDoesNotUnblock() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        HttpServer server = startStub(closed);
        try {
            Object[] c = connectAndDrain3(server, null);
            Iterator<RawMessageStreamEvent> it = (Iterator<RawMessageStreamEvent>) c[1];

            AtomicReference<Object> shape = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    shape.set(it.next());
                } catch (Throwable t) {
                    shape.set(t);
                }
            }, "probe-reader-B");
            reader.setDaemon(true);
            reader.start();
            Thread.sleep(500);
            assertThat(shape.get()).as("前置：第 4 次读必须已阻塞").isNull();

            reader.interrupt();
            reader.join(3_000);
            assertThat(reader.isAlive())
                .as("interrupt 不解阻塞；若翻红说明 SDK/okio 行为已变，需重评原语选型")
                .isTrue();
        } finally {
            closed.set(true);
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Phase C：Timeout.read(idle) 有效——静默 ≥ idle 时读线程内抛 SocketTimeoutException")
    void phaseC_readTimeoutUnblocks() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        HttpServer server = startStub(closed);
        try {
            Object[] c = connectAndDrain3(server, 1_500L);   // 武装 1.5s 读超时
            Iterator<RawMessageStreamEvent> it = (Iterator<RawMessageStreamEvent>) c[1];

            AtomicReference<Object> shape = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    shape.set(it.next());
                } catch (Throwable t) {
                    shape.set(t);
                }
            }, "probe-reader-C");
            reader.setDaemon(true);
            reader.start();

            reader.join(8_000);
            assertThat(reader.isAlive())
                .as("读超时必须解开阻塞读（本探针 = 看门狗原语的正面证据）").isFalse();
            assertThat(shape.get()).as("解开形态应为超时类异常").isInstanceOf(Throwable.class);
            Throwable t = (Throwable) shape.get();
            boolean timeoutish = false;
            for (Throwable x = t; x != null && !timeoutish; x = x.getCause()) {
                timeoutish = x instanceof java.net.SocketTimeoutException
                    || (x instanceof java.io.InterruptedIOException
                        && x.getMessage() != null
                        && x.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout"));
            }
            assertThat(timeoutish)
                .as("解开异常必须是超时类（SocketTimeoutException / InterruptedIOException:timeout），"
                    + "否则 isStreamIdleReadTimeout 的判据需同步；实际 = " + t)
                .isTrue();
        } finally {
            closed.set(true);
            server.stop(0);
        }
    }
}
