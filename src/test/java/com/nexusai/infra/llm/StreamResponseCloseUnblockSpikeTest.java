package com.nexusai.infra.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [流空闲看门狗 · 任务 0 spike] 证明 {@link StreamResponse#close()} 能把阻塞在
 * {@code iterator().next()} 的读线程解开。
 *
 * <p><b>⚠️ 10-10 更正（场景覆盖不足的假绿）</b>：本 spike 只覆盖"<b>零消费</b>即关流"；
 * 真实 stall 场景（已消费 ≥1 事件后静默）中 close() <b>会死锁</b>（且 interrupt 无效）
 * ——见 {@link StreamIdleAbortChannelProbeTest} 三相反证。看门狗中止原语已换挡为
 * {@code Timeout.read}（{@link AnthropicSdkProvider#buildClient} 换挡说明）。本文件保留，
 * 仅作"close 在零消费场景可用"的窄结论记录。
 *
 * <p>WHY：provider 的流消费是阻塞迭代器（AnthropicSdkProvider.doStream 的
 * {@code while(it.hasNext()) → it.next()}）；静默流下唯一能打断它的通道是传输层关流。
 * 空闲看门狗"超时中止底层请求"的全部设计都压在这一点上——**先自证扰动生效，再接线**。
 *
 * <p>桩：JDK 内置 HttpServer（虚拟线程 executor），返回 200 + text/event-stream（chunked），
 * 先写一行 SSE 注释（`:` 开头，SSE 解析器忽略——用于逼出响应头下发）后**不再发任何事件**、保持连接。
 * 读线程阻塞在首个可解析事件上；主线程先断言"扰动前确实阻塞"，再 close()，断言 5s 内解开。
 *
 * <p>[首跑教训·10-10] 零 body 写入时 HttpServer 不刷响应头 → 客户端卡在 `readResponseHeaders`
 * 直到 OkHttp 超时（`InterruptedIOException: timeout`）。修复 = 先写并 flush 一个 SSE 注释行。
 * 客户端显式 timeout(5min) 钉死超时来源，避免默认值干扰判读。
 *
 * <p>零依赖、不触网（baseUrl 指向 127.0.0.1 随机端口）。范式参照 SdkPutHeaderProbeTest。
 */
class StreamResponseCloseUnblockSpikeTest {

    @Test
    @DisplayName("close() 解开阻塞在 it.next() 的读线程（含反面对照：扰动前必须仍阻塞）")
    void close_unblocks_blocked_iterator() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());   // 虚拟线程=daemon，残留 handler 不阻塞 JVM 退出
        server.createContext("/", exchange -> {
            System.out.println("[spike] 桩收到请求: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);   // chunked：保持连接、不结束
            try (OutputStream os = exchange.getResponseBody()) {
                // SSE 注释行：逼出响应头下发（零 body 写入时 HttpServer 不刷头）；解析器会忽略注释
                os.write(": spike-keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                System.out.println("[spike] 已写 SSE 注释并 flush（响应头应已下发），进入静默");
                // 静默：不再发任何 SSE 事件，保持连接
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                // 测试结束/关流时退出
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey("k")
                .baseUrl(baseUrl)
                .maxRetries(0)
                .timeout(Duration.ofMinutes(5))   // 钉死超时来源（避免 SDK 默认值干扰判读）
                .build();
            MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-probe")
                .maxTokens(1L)
                .addUserMessage("hi")
                .build();

            StreamResponse<RawMessageStreamEvent> resp =
                client.messages().withRawResponse().createStreaming(params).parse();
            System.out.println("[spike] 流已建立（createStreaming 返回）");
            Iterator<RawMessageStreamEvent> it = resp.stream().iterator();

            AtomicReference<Object> result = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    result.set(it.next());
                } catch (Throwable t) {
                    result.set(t);
                }
            }, "spike-reader");
            reader.start();

            Thread.sleep(1_500);
            // 反面对照：扰动前必须仍阻塞（否则说明桩/流建立有问题 → 本 spike 未取证）
            assertThat(result.get()).as("close 前读线程应仍阻塞在 it.next()（否则桩无效，需停下排查）").isNull();
            System.out.println("[spike] 扰动前：读线程仍阻塞 ✓");

            resp.close();   // ← 施加扰动

            reader.join(5_000);
            assertThat(reader.isAlive()).as("close() 后 5s 内读线程应被解开").isFalse();

            // 探针证据：解开形态落 surefire 报告（异常类型 / 正常结束元素）
            Object shape = result.get();
            String shapeDesc = (shape == null) ? "null(正常结束但无元素)"
                : (shape instanceof Throwable t2 ? t2.getClass().getName() + ": " + t2.getMessage() : "event: " + shape);
            System.out.println("[spike] close() 解开阻塞读 ✓，解开形态 = " + shapeDesc);
        } finally {
            server.stop(0);
        }
    }
}
