package com.nexusai.infra.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nexusai.infra.properties.NexusProperties;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * openai 通道流完整性检查（断流 fail loud）+ 非流式回退（行为同步 CC）· 2026-10-10。
 *
 * <p><b>WHY（规则九/十二 + 对齐 CC）</b>：ant 事故（sess-bf736cb3，8 条 out=0）的同期对照显示
 * openai 通道正常，但读码确认 {@link OpenAiSdkProvider} 收尾处同样无完整性检查。按裁定两步对齐 CC：
 * ① <b>检测</b>（对齐 CC claude.ts:2337-2364 'no stop_reason received'）：openai 协议的停止标记 =
 * 末 chunk 的 {@code finish_reason}（OpenAI 规范必带；DeepSeek 实测每次正常流均有）→ 流结束
 * 仍未见 ⇒ 判截断；
 * ② <b>恢复</b>（对齐 CC claude.ts:2505-2562 'Error streaming, falling back to non-streaming mode'；
 * 2.1.296 用户文案 'Retrying without streaming'）：判失败后改用非流式请求重试
 * （onStreamingFallback 触达 + 非流式结果送达 + onComplete；耗尽/无回退通道 → onError）。
 *
 * <p>用例：
 * <ol>
 *   <li>截断流 + 有回退通道 → 非流式回退成功（RED teeth：去掉回退接线即 fail——修复前 onError
 *       直达、fallback 不触发）；</li>
 *   <li>截断流 + 无回退通道（onStreamingFallback=null）→ onError（fail loud，不静默）；</li>
 *   <li>完整流（finish_reason=[stop] + [DONE]）→ 正常完成，不误伤。</li>
 * </ol>
 */
class OpenAiSdkProviderStreamTruncationTest {

    private static final String MODEL = "deepseek-flash";

    // ════════════════════════════════════════════════════════════════════
    // ① 截断流 → 非流式回退成功（对齐 CC 'Retrying without streaming'）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("截断流（无 finish_reason）→ 非流式回退成功（onStreamingFallback 触发 + 结果送达 + onComplete，onError 不触发）")
    void truncatedStream_fallsBackToNonStreaming() throws Exception {
        // 路由：stream=true → SSE 截断（无 finish_reason）；stream=false → 200 JSON 非流式成功。
        HttpServer server = startServer("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(truncatedSse());
                exchange.getResponseBody().flush();
                exchange.close();
            } else {
                respond(exchange, 200,
                    "{\"id\":\"c3\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"deepseek-flash\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"recovered result\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":9,\"total_tokens\":19}}");
            }
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
            // 反射注入 NexusProperties（非流式回退的 reasoning 解析依赖 properties；StreamRequestIdTest 同款先例）
            Field pf = OpenAiSdkProvider.class.getDeclaredField("properties");
            pf.setAccessible(true);
            pf.set(provider, new NexusProperties());
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
                () -> fallbackFired.set(true), // onStreamingFallback（本通道重试引擎所在）
                null, // abortController
                err -> {
                    gotError.set(err);
                    done.countDown();
                },
                done::countDown, null, null);

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("截断流应经「判失败→非流式回退」在有限时间内完成")
                .isTrue();
            assertThat(fallbackFired)
                .as("截断流必须触发 onStreamingFallback（对齐 CC 'Retrying without streaming'）")
                .isTrue();
            assertThat(gotMsg.get())
                .as("非流式回退结果必须经 onAssistantMessage 送达")
                .isNotNull();
            assertThat(gotMsg.get().content())
                .as("回退结果 = 非流式响应 text")
                .isEqualTo("recovered result");
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
    @DisplayName("截断流 + onStreamingFallback=null → onError 显式报错（fail loud，不静默收尾）")
    void truncatedStream_withoutFallback_failsLoud() throws Exception {
        HttpServer server = startServer("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(truncatedSse());
            exchange.getResponseBody().flush();
            exchange.close();
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
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
                done::countDown, null, null);

            assertThat(done.await(10, TimeUnit.SECONDS))
                .as("截断流（无回退通道）应在有限时间内以 onError 或 onComplete 终结")
                .isTrue();
            assertThat(gotError.get())
                .as("openai 截断流（无回退通道）必须走 onError（RED teeth：修复前此处为 null——静默收尾）")
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
    @DisplayName("完整流（finish_reason=[stop] + [DONE]）→ 正常送达 + onComplete，不触发 onError/回退")
    void completeStream_notFlagged() throws Exception {
        HttpServer server = startServer("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(completeSse());
            exchange.getResponseBody().flush();
            exchange.close();
        });
        try {
            ProviderConfig config =
                new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            OpenAiSdkProvider provider = new OpenAiSdkProvider();
            // 反射注入 NexusProperties（非流式回退的 reasoning 解析依赖 properties；StreamRequestIdTest 同款先例）
            Field pf = OpenAiSdkProvider.class.getDeclaredField("properties");
            pf.setAccessible(true);
            pf.set(provider, new NexusProperties());
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
                done::countDown, null, null);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gotMsg.get())
                .as("完整流必须正常送达（不误伤）")
                .isNotNull();
            assertThat(gotMsg.get().content())
                .as("完整流正文送达")
                .isEqualTo("done");
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
    // SSE / JSON 载荷 + 工具
    // ════════════════════════════════════════════════════════════════════

    /** 截断序列：role + 半句 delta —— 无 finish_reason chunk、无 [DONE]（对齐 CC「no stop_reason received」形态）。 */
    static byte[] truncatedSse() {
        return ("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-flash\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n"
            + "\n"
            + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-flash\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"The user says 继续. Let me look at where\"},"
            + "\"finish_reason\":null}]}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 完整序列：delta + finish_reason=stop chunk + [DONE]。 */
    static byte[] completeSse() {
        return ("data: {\"id\":\"c2\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-flash\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":null}]}\n"
            + "\n"
            + "data: {\"id\":\"c2\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-flash\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
            + "\n"
            + "data: [DONE]\n"
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
