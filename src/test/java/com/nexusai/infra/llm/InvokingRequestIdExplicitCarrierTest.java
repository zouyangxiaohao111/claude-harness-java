package com.nexusai.infra.llm;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * [A#3 tuc-invoking-req] provider 侧 {@code invokingRequestId} · <b>显式 agentContext 载体</b>行为锁。
 *
 * <h2>WHY（本批根因 · CLAUDE.md 规则九「测试验证意图」）</h2>
 * CC 的 {@code consumeInvokingRequestId()}（agentContext.ts:163-178）读 AsyncLocalStorage
 * —— Node 的 ALS 跨 await/异步自动传播；Java 侧曾以 plain {@link ThreadLocal} 等价之，但
 * <b>不跨线程继承</b>（该载体已于 S1-T7-2 整体删除）。而 {@code AnthropicSdkProvider} 的 12 个
 * per-LLM-call terminal 发射点跑在 {@code LlmAgentLoop.STREAM_EXECUTOR}（虚拟线程池，
 * {@code LlmAgentLoop:6604}；Java 虚拟线程不继承创建线程的 ThreadLocal）⇒ 旧实现
 * {@code attachInvokingRequestEdge(attrs)}（读 ambient ThreadLocal）恒得 null ⇒
 * {@code invokingRequestId} / {@code invocationKind} 生产恒空。
 *
 * <p>修法 = 显式载体：调用方在上下文有效的线程取出 {@link AgentContext} 实例，经
 * {@code ModelRequest.agentContext} → {@code ModelCaller} → {@code LlmProvider.stream} /
 * {@code ChatRequestOptions.agentContext} / {@code chatWithRaw} 显式下传，provider 侧
 * {@code attachInvokingRequestEdge(attrs, agentContext)} 消费（<b>不得</b>在新线程回放
 * ThreadLocal 再读 —— 用户铁律：回放不算合规）。
 *
 * <h2>夹具为什么必须跨真线程（同线程断言对本缺陷零覆盖力）</h2>
 * 若在测试线程设 ThreadLocal 再在测试线程断言，删除修复代码测试仍是绿的（ThreadLocal 读得到）。
 * 本类因此：
 * <ol>
 *   <li>provider 调用提交到 <b>真 {@code LlmAgentLoop.STREAM_EXECUTOR}</b>（反射取真执行器）；
 *       chat 路径提交到独立虚拟线程（镜像
 *       {@code YoloClassifierImpl.callWithMdc} 的 {@code CompletableFuture.supplyAsync} 边界）；</li>
 *   <li>断言事件发射线程 ≠ 测试线程；</li>
 *   <li>[S1-T7-2] 原第 3 条「断言发射线程上 ambient 归因上下文为 null」<b>已随该载体整体删除
 *       而移除</b>：该维度现由<b>编译期</b>保证（无 ambient 读取 API 可调）+
 *       {@code AgentContextAmbientReadInventoryTest} 守卫；运行期已无可断言对象。</li>
 *   <li>断言<b>稀疏化</b>：同一 invocation 的后续 terminal event 不再携带（CC
 *       agentContext.ts:159-161 {@code invocationEmitted} 一次翻转）。</li>
 * </ol>
 *
 * <p><b>反向实验（已实测）</b>：把 {@code emitApiTerminalEvent} 改回读线程环境变量（ambient）⇒ 本类全红。
 * 该 ambient 读取入口已随 [S1-T7-2] 整体删除 ⇒ 现在回退会直接<b>编译失败</b>。
 */
class InvokingRequestIdExplicitCarrierTest {

    private static final String MODEL = "claude-sonnet-4-6";

    /**
     * 观测点：事件属性 + 发射线程。
     *
     * <p>[S1-T7-2] 原还有一项「发射线程上的 ambient AgentContext」——随 ambient 归因载体整体删除而移除：
     * 该维度现由<b>编译期</b>保证（无 ambient 读取 API 可调）+ {@code AgentContextAmbientReadInventoryTest}
     * 守卫（断言载体在 {@code src/main} 彻底不存在）。运行期已无可断言对象。
     */
    private static final class Observations {
        final List<Map<String, Object>> successAttrs = new CopyOnWriteArrayList<>();
        final List<String> emittingThreads = new CopyOnWriteArrayList<>();
    }

    private static Observations wire(Telemetry telemetry) {
        Observations obs = new Observations();
        doAnswer(inv -> {
            obs.successAttrs.add(inv.getArgument(1));
            obs.emittingThreads.add(Thread.currentThread().getName());
            return null;
        }).when(telemetry).recordEvent(eq("tengu_api_success"), any());
        return obs;
    }

    private static AnthropicSdkProvider providerWithTelemetry(Telemetry telemetry) {
        AnthropicSdkProvider provider = new AnthropicSdkProvider();
        ReflectionTestUtils.setField(provider, "telemetry", telemetry);
        return provider;
    }

    private static ProviderConfig config(HttpServer server) {
        return new ProviderConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    /** 真 {@code LlmAgentLoop.STREAM_EXECUTOR}（虚拟线程池）· 反射取生产实例，不用仿造执行器。 */
    private static ExecutorService realStreamExecutor() {
        Object ex = ReflectionTestUtils.getField(LlmAgentLoop.class, "STREAM_EXECUTOR");
        assertThat(ex).as("生产 STREAM_EXECUTOR 必须可取得（夹具走真执行器，不用仿造线程）")
            .isInstanceOf(ExecutorService.class);
        return (ExecutorService) ex;
    }

    private static AgentContext.SubagentContext subagentCtx(String requestId) {
        return new AgentContext.SubagentContext(
            "a0123456789abcdef", null, "Explore", true, requestId, "spawn");
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. stream 家族 · 真 STREAM_EXECUTOR 线程
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stream 家族 · 真 STREAM_EXECUTOR 线程 → tengu_api_success 携带 invokingRequestId"
        + "（显式载体，不读 ambient ThreadLocal）")
    void streamFamily_onRealStreamExecutor_carriesInvokingRequestId() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            AgentContext.SubagentContext ctx = subagentCtx("req-from-subagent");
            CountDownLatch done = new CountDownLatch(1);

            // 真 STREAM_EXECUTOR 提交（生产同一点：LlmAgentLoop:6604 STREAM_EXECUTOR.execute）
            realStreamExecutor().submit(() -> provider.stream(
                config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown, null,
                ctx /* [A#3] 显式载体 */)).get(20, TimeUnit.SECONDS);

            assertThat(done.await(10, TimeUnit.SECONDS)).as("流正常结束").isTrue();
            assertThat(obs.successAttrs)
                .as("tengu_api_success 必须携带稀疏边（CC logging.ts:493-500 spread）")
                .isNotEmpty();
            assertThat(obs.successAttrs.get(0))
                .as("invokingRequestId 来自显式 agentContext（越过 STREAM_EXECUTOR 虚拟线程边界）")
                .containsEntry("invokingRequestId", "req-from-subagent")
                .containsEntry("invocationKind", "spawn");
            assertThat(obs.emittingThreads)
                .as("夹具有效性：事件在非测试线程（真 STREAM_EXECUTOR 虚拟线程）发射")
                .allSatisfy(t -> assertThat(t).isNotEqualTo(Thread.currentThread().getName()));
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. chat 家族 · 独立线程（镜像 YoloClassifierImpl.callWithMdc 的 supplyAsync 边界）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("chat 家族（chatWithRaw）· 非测试线程 → tengu_api_success 携带 invokingRequestId"
        + "（chat 自带载体，不能只靠 stream 的新参数）")
    void chatFamily_onOtherThread_carriesInvokingRequestId() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        HttpServer server = startJsonServer(jsonMessage("ok"));
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            AgentContext.SubagentContext ctx = subagentCtx("req-chat-side-query");

            // 独立虚拟线程派发：与 YoloClassifierImpl.callWithMdc 的
            // CompletableFuture.supplyAsync 边界同型（ThreadLocal 不继承）
            Thread t = Thread.ofVirtual().name("chat-side-query-thread").unstarted(
                () -> provider.chatWithRaw(config(server), MODEL, "sys", "hi", ctx));
            t.start();
            t.join(20_000);

            assertThat(obs.successAttrs).isNotEmpty();
            assertThat(obs.successAttrs.get(0))
                .as("chatWithRaw 路径必须携带显式 agentContext 的稀疏边")
                .containsEntry("invokingRequestId", "req-chat-side-query")
                .containsEntry("invocationKind", "spawn");
            assertThat(obs.emittingThreads)
                .as("夹具有效性：发射线程 = 派发线程而非测试线程")
                .allSatisfy(name -> assertThat(name).isEqualTo("chat-side-query-thread"));
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 稀疏化 · 同一 invocation 只有第一个 terminal event 携带
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("稀疏化 · 同一 invocation 两次 terminal event → 只第一个带 invokingRequestId"
        + "（CC agentContext.ts:159-161 invocationEmitted 一次翻转）")
    void sparseEdge_onlyFirstTerminalEventCarries() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            // 同一 SubagentContext 实例 = 同一次 invocation（CC 每次 spawn/resume 新建对象）
            AgentContext.SubagentContext ctx = subagentCtx("req-once");

            for (int i = 0; i < 2; i++) {
                CountDownLatch done = new CountDownLatch(1);
                realStreamExecutor().submit(() -> provider.stream(
                    config(server), MODEL,
                    List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                        "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                    List.of(userMsg("hi")), null,
                    null, null, null, null,
                    c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                    null, e -> {}, done::countDown, null,
                    ctx)).get(20, TimeUnit.SECONDS);
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(obs.successAttrs)
                .as("两次 API 调用 = 两个 terminal event，都必须发射")
                .hasSize(2);
            assertThat(obs.successAttrs.get(0))
                .as("第一个 terminal event 必须携带稀疏边（spawn/resume 边界标记）")
                .containsEntry("invokingRequestId", "req-once");
            assertThat(obs.successAttrs.get(1))
                .as("后续 terminal event 必须**不**携带（invocationEmitted 已翻转 → 稀疏边语义）")
                .doesNotContainKey("invokingRequestId")
                .doesNotContainKey("invocationKind");
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 主线程（agentContext=null）→ 不携带（CC 主线程 undefined）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主线程 agentContext=null → 事件不带 invokingRequestId（CC 主线程 undefined）")
    void mainThreadNullContext_noEdge() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        HttpServer server = startSseServer(sseSuccess());
        try {
            AnthropicSdkProvider provider = providerWithTelemetry(telemetry);
            CountDownLatch done = new CountDownLatch(1);
            provider.stream(config(server), MODEL,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    "sys", com.nexusai.application.agent.prompt.CacheScope.ORG)),
                List.of(userMsg("hi")), null,
                null, null, null, null,
                c -> {}, m -> {}, (ToolUseBlock t) -> {}, r -> {}, () -> {},
                null, e -> {}, done::countDown, null,
                null /* 主线程：无归因上下文 */);

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(obs.successAttrs).isNotEmpty();
            assertThat(obs.successAttrs.get(0))
                .as("agentContext=null → 无稀疏边（对齐 CC context?.invokingRequestId undefined）")
                .doesNotContainKey("invokingRequestId")
                .doesNotContainKey("invocationKind");
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers（与 AnthropicSdkProviderApiTelemetryTest 同款最小 HTTP 夹具）
    // ════════════════════════════════════════════════════════════════════

    private static HttpServer startSseServer(byte[] body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
                exchange.close();
            } catch (Exception e) {
                // 客户端 abort 后写 body 可能抛 → 忽略（连接已断）
            }
        });
        server.start();
        return server;
    }

    private static HttpServer startJsonServer(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] sseSuccess() {
        return ("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude\",\"role\":\"assistant\"},"
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30}}\n"
            + "\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}\n"
            + "\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonMessage(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
            + "\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":7}}";
    }
}
