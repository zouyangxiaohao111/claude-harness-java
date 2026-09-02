package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [Q-11-5 DIV-2] 会话过期同调用内重试 · 对齐 CC client.ts:1859-1922
 * ({@code MAX_SESSION_RETRIES=1} + {@code error instanceof McpSessionExpiredError && attempt < MAX} → continue)。
 *
 * <p>WHY（规则九 · 验收标准「会话过期同调用内自动重试」）：此前 conn-fix 批仅实现「当前调用
 * 上抛 + 下次调用重建连接」的最小对齐；CC 真源在 call() 主循环内对
 * {@code McpSessionExpiredError} 同调用重试一次（清缓存 → ensureConnectedClient 新 session →
 * 重试），重试仍失败才抛原错误。若 Java 不实现同调用重试，一次会话过期的工具调用直接变
 * ToolResult.error 让 LLM 看到「MCP call failed」，而 CC 在同一 LLM turn 内透明重试成功
 * ——行为分裂。本测试锁定三件事：(a) 会话过期 → 清缓存 + 新建 transport + 重试成功；
 * (b) 重试仍失败 → 抛原 {@link McpSessionExpiredException}（attempt 边界，不无限重试）；
 * (c) 非会话错误 → 不重试（只重试会话过期这一种可恢复错误）。
 */
@DisplayName("[Q-11-5 DIV-2] 会话过期同调用内重试")
class McpToolPoolSessionRetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "svc";

    private static McpTransport.TransportConfig httpConfig(String name) {
        return new McpTransport.TransportConfig("http://svc:3000", List.of(), Map.of(), null, name, "http");
    }

    // ═══════════════ (a) 会话过期 → 清缓存 + 重试成功 ═══════════════

    /**
     * WHY：CC call() 捕获 McpSessionExpiredError（attempt=0 < MAX=1）→ 清缓存后同调用重试一次
     * （client.ts:1913-1922），重试经 ensureConnectedClient 重建连接（新 session）。Java 等价：
     * 首个 transport 的 tools/call 抛会话过期 → activeTransports 移除 → 重试 create 新 transport
     * 并成功返回结果。若未实现重试，首个失败会直接冒泡 → 本测试红。
     */
    @Test
    @DisplayName("会话过期 → 清缓存 + 新建 transport + 同调用重试成功")
    void sessionExpired_retriesOnceAndSucceeds() throws Exception {
        FakeRetryFactory factory = new FakeRetryFactory(1, false); // 首个 tools/call 会话过期，之后成功
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER, httpConfig(SERVER));
        assertThat(factory.created.get()).isEqualTo(1);
        // 装配填充 fetch 缓存（重试需验证清缓存语义）
        assertThat(pool.toolsCache().get(SERVER)).isNotNull();

        JsonNode result = pool.callTool(SERVER, "my_tool", Map.of("x", 1)).join();

        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("ok-after-retry");
        // 清缓存后重试 → 新建 transport（CC ensureConnectedClient 新 session 语义）
        assertThat(factory.created.get()).isEqualTo(2);
        assertThat(pool.toolsCache().get(SERVER)).isNull(); // 清 fetch 缓存（对齐 clearServerCache）
        assertThat(pool.activeServers()).contains(SERVER);  // 保留已注册工具（不清 server.tools）
        assertThat(factory.instances.get(0).toolsCallCount.get()).isEqualTo(1); // 首次失败
        assertThat(factory.instances.get(1).toolsCallCount.get()).isEqualTo(1); // 重试成功
    }

    // ═══════════════ (b) 重试仍失败 → 抛原错误（attempt 边界） ═══════════════

    /**
     * WHY：CC attempt >= MAX_SESSION_RETRIES 时不再重试（client.ts:1914-1915 判定失败），
     * 抛原错误。Java 等价：两个 transport 的 tools/call 均会话过期 → 第二次（attempt=1）命中
     * {@code 1 < 1 = false} → 不新建第三个 transport，抛 {@link McpSessionExpiredException}。
     * 若重试无限循环 / 第三次仍建连 → created > 2 或 future 悬挂 → 本测试红。
     */
    @Test
    @DisplayName("会话过期重试仍失败 → 抛原错误（不无限重试）")
    void sessionExpired_retryAlsoFails_propagatesOriginal() {
        FakeRetryFactory factory = new FakeRetryFactory(2, false); // 两次 tools/call 均会话过期
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER, httpConfig(SERVER));

        Throwable thrown = catchThrowable(() -> pool.callTool(SERVER, "my_tool", Map.of()).join());

        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOf(McpSessionExpiredException.class);
        // attempt 边界：恰好 2 个 transport（assemble 1 + 重试 1），不再第三次建连
        assertThat(factory.created.get()).isEqualTo(2);
    }

    // ═══════════════ (c) 非会话错误 → 不重试 ═══════════════

    /**
     * WHY：CC 重试条件仅 {@code error instanceof McpSessionExpiredError}（client.ts:1914）；
     * 其它错误（协议错误、超时等）不可恢复，直接抛。Java 等价：tools/call 抛
     * IllegalStateException → 不新建 transport（created 停在 1），原错误冒泡。若把重试扩大到
     * 任意错误，会重复调用副作用型工具 → 本测试红。
     */
    @Test
    @DisplayName("非会话错误 → 不重试，原错误冒泡")
    void nonSessionError_doesNotRetry() {
        FakeRetryFactory factory = new FakeRetryFactory(0, true); // tools/call 恒抛非会话错误
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER, httpConfig(SERVER));

        Throwable thrown = catchThrowable(() -> pool.callTool(SERVER, "my_tool", Map.of()).join());

        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(factory.created.get()).isEqualTo(1); // 无重试 → 无新 transport
    }

    /** 对齐 CC 常数断言：MAX_SESSION_RETRIES 必须为 1（client.ts:1859）。 */
    @Test
    @DisplayName("MAX_SESSION_RETRIES 对齐 CC = 1")
    void maxSessionRetries_isOne() {
        assertThat(McpToolPool.MAX_SESSION_RETRIES).isEqualTo(1);
    }

    // ═══════════════ fakes ═══════════════

    /** 返回可控制的 FakeRetryTransport，并跟踪创建实例。 */
    static class FakeRetryFactory extends McpTransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final List<FakeRetryTransport> instances = new CopyOnWriteArrayList<>();
        /** 剩余应「会话过期」失败的 tools/call 次数；耗尽后成功。 */
        final AtomicInteger sessionExpiredFailures;
        /** true → tools/call 一律以非会话错误失败（验证无重试路径）。 */
        final boolean nonSessionError;

        FakeRetryFactory(int sessionExpiredFailures, boolean nonSessionError) {
            this.sessionExpiredFailures = new AtomicInteger(sessionExpiredFailures);
            this.nonSessionError = nonSessionError;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            FakeRetryTransport t = new FakeRetryTransport(this);
            instances.add(t);
            created.incrementAndGet();
            return t;
        }
    }

    /** 模拟 HTTP transport：start 即 CONNECTED；initialize/tools/list 空能力；tools/call 按工厂配置失败/成功。 */
    static class FakeRetryTransport implements McpTransport {
        private final AtomicReference<State> state = new AtomicReference<>(State.NOT_CONNECTED);
        private final FakeRetryFactory factory;
        /** 本 transport 收到的 tools/call 次数（验证首次失败 / 重试成功各一次）。 */
        final AtomicInteger toolsCallCount = new AtomicInteger();

        FakeRetryTransport(FakeRetryFactory factory) {
            this.factory = factory;
        }

        @Override
        public void start(TransportConfig config) {
            state.set(State.CONNECTED);
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-http");
                r.putObject("capabilities");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putArray("tools");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/call".equals(method)) {
                toolsCallCount.incrementAndGet();
                if (factory.nonSessionError) {
                    return CompletableFuture.failedFuture(new IllegalStateException("boom (non-session)"));
                }
                if (factory.sessionExpiredFailures.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0) {
                    return CompletableFuture.failedFuture(
                        new McpSessionExpiredException(SERVER, "MCP server \"svc\" session expired (test)"));
                }
                ObjectNode r = MAPPER.createObjectNode();
                r.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", "ok-after-retry");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("unexpected method " + method));
        }

        @Override
        public void sendNotification(String method, Object params) {
        }

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {
        }

        @Override
        public void close() {
            state.set(State.CLOSED);
        }

        @Override
        public State getState() {
            return state.get();
        }
    }
}
