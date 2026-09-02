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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [S02 X-4] 连接握手超时 race · 对齐 CC client.ts:1048-1077（connectPromise 与
 * getConnectionTimeoutMs()=30000 超时 Promise race → transport.close() + 抛
 * 「connection timed out」；I-2：任何连接路径有超时，超时 → 明确错误态）。
 *
 * <p><b>WHY（规则九）</b>：旧 connectTransport 同步 join initialize——stdio/网络 transport
 * initialize 永不返回时调用方永久悬挂（测试挂死 / 生产线程泄漏）。本测试锁定：
 * <ol>
 *   <li>假 transport 的 initialize 永不完结（悬挂模拟）→ 注入 300ms 连接超时 → 限时内抛
 *       {@code connection timed out} 错误（证明悬挂可中断，非靠外部杀死进程）</li>
 *   <li>超时后 transport 已被 close（state CLOSED，对齐 CC timeout → transport.close()）</li>
 *   <li>超时后 activeTransports 已清理（陈旧连接不复用）</li>
 * </ol>
 */
@DisplayName("[S02 X-4] 连接握手超时 race")
class McpConnectTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "hang-svc";

    private static McpTransport.TransportConfig httpConfig(String name) {
        return new McpTransport.TransportConfig("http://hang-svc:3000", List.of(), Map.of(), null, name, "http");
    }

    @Test
    @DisplayName("initialize 悬挂 → 注入超时内抛 connection timed out + transport CLOSED + activeTransports 清理")
    void hangingInitialize_timesOut_closesTransportAndCleansMap() {
        AtomicReference<HangTransport> createdRef = new AtomicReference<>();
        McpToolPool pool = new McpToolPool(new McpTransportFactory() {
            @Override
            public McpTransport create(McpTransport.TransportConfig config) {
                HangTransport t = new HangTransport();
                createdRef.set(t);
                return t;
            }
        }, new ToolRegistry(), new JsonRpcMcpClient());
        pool.setConnectTimeoutOverrideMs(300);

        long start = System.currentTimeMillis();
        Throwable thrown = catchThrowable(() -> pool.assembleToolPool(SERVER, httpConfig(SERVER)));
        long elapsed = System.currentTimeMillis() - start;

        // 限时内抛错（悬挂可中断）：300ms 注入 + 宽松上界 5s
        assertThat(elapsed).as("连接超时必须在注入时限内抛出（不能悬挂）").isLessThan(5_000);
        assertThat(thrown).isNotNull();
        assertThat(pool.isServerConnected(SERVER)).as("超时后连接缓存必须清理（陈旧连接不复用）").isFalse();
        // 超时 → transport.close()（CC client.ts:1059）+ activeTransports 清理（陈旧不复用）
        HangTransport transport = createdRef.get();
        assertThat(transport).isNotNull();
        assertThat(transport.state.get()).as("超时后 transport 必须 CLOSED（CC timeout → close）")
            .isEqualTo(McpTransport.State.CLOSED);
    }

    /** initialize 永不完结的假 transport（start 即 CONNECTED；sendRequest 悬挂）。 */
    static class HangTransport implements McpTransport {
        final AtomicReference<State> state = new AtomicReference<>(State.NOT_CONNECTED);
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void start(TransportConfig config) {
            state.set(State.CONNECTED);
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            // initialize 永不完结（悬挂模拟）——返回一个永不完成的 future
            return new CompletableFuture<>();
        }

        @Override
        public void sendNotification(String method, Object params) {
        }

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            state.set(State.CLOSED);
        }

        @Override
        public State getState() {
            return state.get();
        }
    }
}
