package com.nexusai.infra.llm;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流完整性校验（断流 fail loud）· 2026-10-10 sess-bf736cb3 事故。
 *
 * <p><b>WHY（CLAUDE.md 规则九/十二 · 测试验证意图 + 显式失败）</b>：
 * 生产事故（2026-10-10 10:47-10:50 / 15:45-15:50，sess-bf736cb3，DeepSeek /anthropic 端点）：
 * 服务端在「思考生成中途」结束响应——客户端侧零异常（「事件映射失败」全文件 0 命中）、
 * HTTP 层为「正常完成」（run exit=NORMAL），但 SSE 里没有终止事件：reasoning 半句截断 +
 * output_tokens=0（全库对照：正常收尾轮 out>0，合法「想完不说」也有 out>0；当日全库仅此 8 条
 * out=0）。本仓此前把「流结束」一律当「正常说完」（buildAssistantMessage: finishReason==null
 * → 默认 end_turn），断流被静默包装成「正常的空回复」→ 用户看到「没有工具调用直接结束」。
 *
 * <p>本测试锁定修复后的意图：
 * <ol>
 *   <li><b>截断流（无完成块 + 无 stop_reason——CC claude.ts:2337-2364 判定形态）→ 判失败走既有
 *       「非流式回退」链</b>（onStreamingFallback 触发 + 非流式结果送达 + onComplete +
 *       onError 不触发）——RED teeth：去掉 doStream 的流完整性校验即 fail（静默收尾）；</li>
 *   <li><b>截断流 + 无回退通道（onStreamingFallback=null）→ onError 显式报错</b>（不再静默）——
 *       RED teeth：同上去掉校验即 fail（onError 保持 null）；</li>
 *   <li><b>完整流（含 message_delta+message_stop）→ 正常完成，不误伤</b>（guard：修复不得把
 *       正常收尾判成失败——含「空 content + 有 usage」的合法收尾形态）。</li>
 * </ol>
 *
 * <p><b>生产形态复刻（关键）</b>：假服务器以 chunked 编码发送 SSE 后 <b>正常结束响应</b>
 * （handler 返回，HTTP 层无错）——对齐生产「0 异常 + 走 onComplete」的实证信号；
 * 而 {@code AnthropicSdkProviderStreamingNonStreamingFallbackTest} 第①例的「Content-Length
 * 不足 + 提前 EOF」是 IOException 形态（另一条路，已有测试）。
 */
class AnthropicSdkProviderStreamTruncationTest {

    private static final String MODEL = "claude-sonnet-4-5";

    // ════════════════════════════════════════════════════════════════════
    // ① 截断流 → 非流式回退成功（核心 RED）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("截断流（半句输出 + 无 message_delta/message_stop）→ 判失败并走非流式回退（onStreamingFallback + 结果送达 + onComplete，onError 不触发）")
    void truncatedStream_fallsBackToNonStreaming() throws Exception {
        HttpServer server = startServer("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                // chunked（0=未知长度）· 写完整截断序列后 handler 正常返回：
                // HTTP 层正常终止（对齐生产：服务端"以为发完了"），SSE 层缺终止事件。
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(truncatedSse());
                exchange.getResponseBody().flush();
                // 优雅终止 chunked（对齐生产：HTTP 层正常完成「服务端以为发完了」、SSE 缺终止事件）。
                exchange.close();
            } else {
                respond(exchange, 200,
                    "{\"content\":[{\"type\":\"text\",\"text\":\"recovered result\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"output_tokens\":9}}");
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

            provider.stream(config, MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(), null, null, null, null, null,
                chunk -> {
                }, gotMsg::set, null, null,
                () -> fallbackFired.set(true), // onStreamingFallback（提供回退通道）
                null, // abortController
                gotError::set,
                () -> {
                    completed.set(true);
                    done.countDown();
                }, null, null, StreamIdleControl.NONE);

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("截断流应经「判失败→非流式回退」在有限时间内完成")
                .isTrue();
            assertThat(fallbackFired)
                .as("截断流必须触发 onStreamingFallback（不再静默当正常完成）· 事故：c88bf15a 等 8 条")
                .isTrue();
            assertThat(gotMsg.get())
                .as("非流式回退结果必须经 onAssistantMessage 送达")
                .isNotNull();
            assertThat(gotMsg.get().content())
                .as("回退结果 = 非流式响应 text")
                .isEqualTo("recovered result");
            assertThat(completed)
                .as("回退成功后必须 onComplete")
                .isTrue();
            assertThat(gotError.get())
                .as("回退成功不得触发 onError")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 截断流 + 无回退通道 → onError 显式报错（fail loud）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("截断流 + onStreamingFallback=null → onError 收显式异常（fail loud，不静默收尾）")
    void truncatedStream_withoutFallback_failsLoud() throws Exception {
        HttpServer server = startServer("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(truncatedSse());
            exchange.getResponseBody().flush();
            exchange.close(); // 优雅终止 chunked（截断形态同①）
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AssistantMessage> gotMsg = new AtomicReference<>();
            AtomicReference<Throwable> gotError = new AtomicReference<>();

            provider.stream(config, MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(), null, null, null, null, null,
                chunk -> {
                }, gotMsg::set, null, null,
                null, // onStreamingFallback = null（无回退通道：断流必须显式失败）
                null,
                err -> {
                    gotError.set(err);
                    done.countDown();
                },
                done::countDown, null, null, StreamIdleControl.NONE);

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("截断流（无回退通道）应在有限时间内以 onError 或 onComplete 终结")
                .isTrue();
            assertThat(gotError.get())
                .as("无回退通道时断流必须走 onError（RED teeth：修复前此处为 null——静默收尾）")
                .isNotNull();
            assertThat(gotMsg.get())
                .as("断流不得以 onAssistantMessage 静默送达半截消息")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 完整流 → 正常完成（不误伤 guard）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("完整流（message_delta+message_stop · 空 content + 有 usage）→ 正常完成，不触发失败/回退")
    void completeStream_notFlagged() throws Exception {
        HttpServer server = startServer("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(completeSse());
            exchange.getResponseBody().flush();
            exchange.close(); // 优雅终止 chunked（完整流）
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            AnthropicSdkProvider provider = new AnthropicSdkProvider();
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean fallbackFired = new AtomicBoolean(false);
            AtomicReference<AssistantMessage> gotMsg = new AtomicReference<>();
            AtomicReference<Throwable> gotError = new AtomicReference<>();

            provider.stream(config, MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(), null, null, null, null, null,
                chunk -> {
                }, gotMsg::set, null, null,
                () -> fallbackFired.set(true),
                null,
                gotError::set,
                done::countDown, null, null, StreamIdleControl.NONE);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gotMsg.get())
                .as("完整流必须正常送达（不误伤：空 content + 有 usage 的合法收尾）")
                .isNotNull();
            assertThat(fallbackFired)
                .as("完整流不得触发回退")
                .isFalse();
            assertThat(gotError.get())
                .as("完整流不得触发 onError")
                .isNull();
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ 判据矩阵（纯函数 · 不依赖 wire）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("streamLooksTruncated 判据矩阵（对齐 CC claude.ts:2337-2364）：无事件/无完成块且无 stop_reason 命中；有完成块或见 stop_reason 放行")
    void streamLooksTruncated_matrix() {
        // ① 无任何事件（连 message_start 都没有）→ 命中（CC !partialMessage：proxy 返回 200 非 SSE）
        AnthropicSdkProvider.StreamState s0 = new AnthropicSdkProvider.StreamState();
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s0))
            .as("无事件 → 判截断（① · CC !partialMessage）").isTrue();

        // ② 有 message_start + 正文半句（块未闭合）+ 无 stop_reason → 命中（CC 二条件 AND）
        AnthropicSdkProvider.StreamState s1 = new AnthropicSdkProvider.StreamState();
        s1.sawMessageStart = true;
        s1.content.append("The user says 继续. Let me look at where");
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s1))
            .as("message_start + 正文半句 + 无 stop_reason → 判截断（②）").isTrue();

        // ② 事故同款：思考半句（块未闭合）+ 无 stop_reason → 命中
        AnthropicSdkProvider.StreamState s2 = new AnthropicSdkProvider.StreamState();
        s2.sawMessageStart = true;
        s2.reasoning.append("不过抽取 3-5 片是 OK 的（之前那个执行者抽了");
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s2))
            .as("message_start + 思考半句 + 无 stop_reason → 判截断（② · 事故 c88bf15a 形态）").isTrue();

        // 放行：完成块到达 + stop_reason（合法「想完不说」）——CC newMessages>0
        AnthropicSdkProvider.StreamState s3 = new AnthropicSdkProvider.StreamState();
        s3.sawMessageStart = true;
        s3.sawCompletedBlock = true;
        s3.reasoning.append("想完了，不需要输出正文");
        s3.outputTokens = 777;
        s3.finishReason = "stop";
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s3))
            .as("有完成块 + stop_reason → 放行（合法 reasoning-only，当日对照 788 条）").isFalse();

        // 放行：正常文本收尾（块闭合）
        AnthropicSdkProvider.StreamState s4 = new AnthropicSdkProvider.StreamState();
        s4.sawMessageStart = true;
        s4.sawCompletedBlock = true;
        s4.content.append("done");
        s4.finishReason = "stop";
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s4)).isFalse();

        // 放行：工具轮（工具块完成 = 参数可执行；随后即使流断也不判截断——CC newMessages>0 语义）
        AnthropicSdkProvider.StreamState s5 = new AnthropicSdkProvider.StreamState();
        s5.sawMessageStart = true;
        s5.sawCompletedBlock = true;
        s5.toolCalls.put(0, new AnthropicSdkProvider.ToolCallAccumulator());
        s5.finishReason = "tool_calls";
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s5)).isFalse();

        // 信任原则锁定：有 stop_reason 即合法完成（即便无完成块）——CC 'check stopReason to avoid false positives'
        AnthropicSdkProvider.StreamState s6 = new AnthropicSdkProvider.StreamState();
        s6.sawMessageStart = true;
        s6.finishReason = "stop";
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s6))
            .as("见 stop_reason → 放行（对齐 CC：stopReason 是合法完成的凭据）").isFalse();

        // 信任原则锁定：有完成块即放行（即便无 stop_reason）——CC newMessages>0
        AnthropicSdkProvider.StreamState s7 = new AnthropicSdkProvider.StreamState();
        s7.sawMessageStart = true;
        s7.sawCompletedBlock = true;
        s7.content.append("块已闭合的内容");
        assertThat(AnthropicSdkProvider.streamLooksTruncated(s7))
            .as("有完成块 → 放行（对齐 CC：块完整即视为有可用产出）").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // SSE 载荷 + 工具（AnthropicSdkProviderStreamingNonStreamingFallbackTest 同款模式）
    // ════════════════════════════════════════════════════════════════════

    /** 截断序列：message_start + 半句 text —— 块未闭合（无 content_block_stop）、无 message_delta / message_stop（对齐 CC 「no content blocks completed AND no stop_reason」形态）。 */
    static byte[] truncatedSse() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_trunc\",\"model\":\"claude\","
            + "\"role\":\"assistant\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n"
            + "\n"
            + "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n"
            + "\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
            + "\"text\":\"The user says 继续. Let me look at where\"}}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 完整序列：message_start + message_delta(stop_reason+usage) + message_stop（对齐 TaskBudgetTest 最小合法序列）。 */
    static byte[] completeSse() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_ok\",\"model\":\"claude\","
            + "\"role\":\"assistant\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n"
            + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":3}}\n"
            + "\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static HttpServer startServer(String context, HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
