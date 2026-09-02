package com.nexusai.application.agent.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [S02 X-7] 工具调用超时 race + 30s 进度日志 · 对齐 CC client.ts:3054-3118
 * （Promise.race([client.callTool, timeoutPromise]) → TelemetrySafeError「MCP tool
 * timeout」+ setInterval 每 30s「Tool 'x' still running」）。
 *
 * <p><b>WHY（规则九）</b>：transport 层 sendRequest 可能悬挂（SSE 流断 / stdio 无响应），
 * 旧 Java 端 tools/call future 无超时 → LLM turn 永久等待（CC 注释「SSE stream breaks
 * mid-request」场景）。本测试锁定：
 * <ol>
 *   <li>永不完结的 tools/call + 注入短 toolTimeoutMs → 限时内失败（不悬挂）</li>
 *   <li>进度日志（注入短间隔）按间隔触发（30s 语义验证）</li>
 *   <li>超时错误不触发会话过期重试链（unwrapSessionExpired 白名单：只认
 *       McpSessionExpiredException）→ 不新建 transport</li>
 * </ol>
 */
@DisplayName("[S02 X-7] 工具调用超时 race + 30s 进度日志")
class McpToolPoolCallTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "slow-svc";

    private static McpTransport.TransportConfig httpConfig(String name) {
        return new McpTransport.TransportConfig("http://slow-svc:3000", List.of(), Map.of(), null, name, "http");
    }

    @Test
    @DisplayName("tools/call 永不完结 → 注入短超时限时失败（timed out）+ 不触发会话过期重试")
    void hangingCall_timesOut_withoutSessionRetry() {
        HangCallFactory factory = new HangCallFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER, httpConfig(SERVER));
        assertThat(factory.created.get()).isEqualTo(1);
        pool.setToolTimeoutOverrideMs(300);

        long start = System.currentTimeMillis();
        Throwable thrown = catchThrowable(() -> pool.callTool(SERVER, "slow_tool", Map.of()).join());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).as("工具调用超时必须限时失败（不悬挂）").isLessThan(5_000);
        assertThat(thrown).isNotNull();
        assertThat(thrown).hasMessageContaining("timed out after");
        // 超时错误不进入会话过期重试链 → 不新建 transport（CC：超时仅 reject 不重试）
        assertThat(factory.created.get()).as("超时不触发会话过期重试（无新 transport）").isEqualTo(1);
        // 超时后 transport 不关闭（CC 语义：仅 reject，client.ts:3073-3089）
        assertThat(pool.isServerConnected(SERVER)).as("超时后 transport 保持连接（CC 仅 reject 语义）").isTrue();
    }

    @Test
    @DisplayName("30s 进度日志按注入间隔触发（工具仍在运行 debug 日志）")
    void progressLog_firesAtInjectedInterval() {
        HangCallFactory factory = new HangCallFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER, httpConfig(SERVER));
        // 注入短超时（防测试悬挂）+ 短进度间隔（CC 默认 30s 语义，间隔可注入钩子）
        pool.setToolTimeoutOverrideMs(600);
        pool.setProgressLogIntervalOverrideMs(50);

        ch.qos.logback.classic.Logger poolLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(McpToolPool.class);
        Level original = poolLogger.getLevel();
        poolLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        poolLogger.addAppender(appender);
        try {
            Throwable thrown = catchThrowable(() -> pool.callTool(SERVER, "slow_tool", Map.of()).join());
            assertThat(thrown).as("进度日志测试仍需在超时内失败").isNotNull();

            long progressCount = appender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                    && e.getFormattedMessage().contains("仍在运行"))
                .count();
            assertThat(progressCount).as("注入 50ms 间隔 + 600ms 超时 → 进度日志必须至少触发 2 次"
                    + "（对齐 CC 每 30s「Tool 'x' still running (Ns elapsed)」client.ts:3054-3066）")
                .isGreaterThanOrEqualTo(2);
        } finally {
            poolLogger.detachAppender(appender);
            poolLogger.setLevel(original);
        }
    }

    // ═══════════════ fakes ═══════════════

    /** tools/call 永不完结的假 factory（initialize/tools/list 正常）。 */
    static class HangCallFactory extends McpTransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final List<HangCallTransport> instances = new CopyOnWriteArrayList<>();

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            HangCallTransport t = new HangCallTransport();
            instances.add(t);
            created.incrementAndGet();
            return t;
        }
    }

    /** start 即 CONNECTED；initialize/tools/list 空能力；tools/call 永不完结。 */
    static class HangCallTransport implements McpTransport {
        private final java.util.concurrent.atomic.AtomicReference<State> state =
            new java.util.concurrent.atomic.AtomicReference<>(State.NOT_CONNECTED);

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
                // 永不完结（悬挂模拟）——超时必须由 McpToolPool 侧 race 触发
                return new CompletableFuture<>();
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
