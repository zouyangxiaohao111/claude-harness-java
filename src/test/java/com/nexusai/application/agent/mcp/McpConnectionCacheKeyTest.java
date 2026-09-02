package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [impl-I-4 T1] 连接缓存键含 config + ensureConnectedClient 惰性重连测试。
 *
 * <p>WHY（规则九）：旧实现 {@code activeTransports} 以 serverName 单键缓存连接——同 name 换
 * config 复用旧连接（悬挂到旧 server），transport CLOSED 后继续用 CLOSED 连接（失败悬挂）。
 * CC {@code getServerCacheKey}（client.ts:581-586）+ {@code ensureConnectedClient}（:1688-1704）
 * 要求 key 含 config + 惰性重连，否则工具调用在 server 重启/换配置后必然失败。
 */
@DisplayName("[impl-I-4 T1] 连接缓存键含 config + 惰性重连")
class McpConnectionCacheKeyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    private McpTransport.TransportConfig config(String url) {
        return new McpTransport.TransportConfig("python", List.of(url), Map.of(), null, null, "stdio");
    }

    // ═══════════ 1. getServerCacheKey 含 config（CC client.ts:581-586）═══════════

    @Test
    @DisplayName("getServerCacheKey: 同 name 换 config → key 不同（CC `${name}-${jsonStringify(serverRef)}`）")
    void cacheKey_differsByConfig() {
        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), client);
        String keyA = pool.getServerCacheKey("srv", config("a"));
        String keyB = pool.getServerCacheKey("srv", config("b"));
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(keyA).startsWith("srv-");
        // env 顺序无关：同内容不同插入序 → 同 key
        McpTransport.TransportConfig c1 = new McpTransport.TransportConfig("python", List.of("x"),
            Map.of("A", "1", "B", "2"), null, null, "stdio");
        McpTransport.TransportConfig c2 = new McpTransport.TransportConfig("python", List.of("x"),
            Map.of("B", "2", "A", "1"), null, null, "stdio");
        assertThat(pool.getServerCacheKey("srv", c1)).isEqualTo(pool.getServerCacheKey("srv", c2));
    }

    // ═══════════ 2. ensureConnectedClient 幂等（已连返回同对象）═══════════

    @Test
    @DisplayName("ensureConnectedClient: 已连接 → 返回同对象（幂等，CC :1693-1695）")
    void ensureConnected_idempotent_returnsSameTransport() {
        TrackingTransport t = new TrackingTransport();
        McpToolPool pool = new McpToolPool(new TrackingFactory(t), new ToolRegistry(), client);
        pool.assembleToolPool("srv", config("a"));

        McpTransport first = pool.ensureConnectedClient("srv", null);
        McpTransport second = pool.ensureConnectedClient("srv", config("a"));
        assertThat(first).isSameAs(t);
        assertThat(second).isSameAs(t);
        assertThat(t.created.get()).isEqualTo(1);
    }

    // ═══════════ 3. 换 config 自动换连接（旧行为：复用 configA 连接）═══════════

    @Test
    @DisplayName("换 config → 自动建新连接（旧行为复用 configA 连接 = RED）")
    void configChange_reconnects() {
        TrackingTransport t1 = new TrackingTransport();
        TrackingTransport t2 = new TrackingTransport();
        McpToolPool pool = new McpToolPool(new SequenceFactory(t1, t2), new ToolRegistry(), client);

        pool.assembleToolPool("srv", config("a"));
        assertThat(t1.created.get()).isEqualTo(1);

        // 同 name 换 config B → key 变化 → 重建（新 transport 实例）
        pool.assembleToolPool("srv", config("b"));
        assertThat(t2.created.get()).isEqualTo(1);
        assertThat(pool.activeServers()).contains("srv");
    }

    // ═══════════ 4. transport CLOSED → 惰性重连（旧行为：继续用 CLOSED 连接 = RED）═══════════

    @Test
    @DisplayName("transport CLOSED → ensureConnectedClient 清缓存 + 重建（CC onclose 惰性重连）")
    void closedTransport_reconnectsOnNextCall() {
        TrackingTransport t1 = new TrackingTransport();
        TrackingTransport t2 = new TrackingTransport();
        McpToolPool pool = new McpToolPool(new SequenceFactory(t1, t2), new ToolRegistry(), client);
        pool.assembleToolPool("srv", config("a"));
        assertThat(t1.created.get()).isEqualTo(1);

        t1.state = McpTransport.State.CLOSED;   // 模拟 onclose / 进程退出
        McpTransport reconnected = pool.ensureConnectedClient("srv", null);
        assertThat(t2.created.get()).isEqualTo(1);
        assertThat(reconnected).isSameAs(t2);
        // CLOSED 重建前清 fetch 缓存（对齐 CC onclose 清 4 个 fetch 缓存）
        assertThat(pool.toolsCache().has("srv")).isFalse();
    }

    // ═══════════ 5. 从未装配 → 抛「MCP server not connected」═══════════

    @Test
    @DisplayName("从未装配 → ensureConnectedClient 抛异常（CC :1698-1703）")
    void neverAssembled_throws() {
        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), client);
        assertThatThrownBy(() -> pool.ensureConnectedClient("ghost", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP server not connected");
    }

    // ═══════════ 6. callTool 经 ensureConnectedClient（CLOSED → 重连成功）═══════════

    @Test
    @DisplayName("callTool: CLOSED 后调用 → 惰性重连成功（不悬挂）")
    void callTool_afterClose_reconnects() {
        TrackingTransport t1 = new TrackingTransport();
        TrackingTransport t2 = new TrackingTransport();
        McpToolPool pool = new McpToolPool(new SequenceFactory(t1, t2), new ToolRegistry(), client);
        pool.assembleToolPool("srv", config("a"));

        t1.state = McpTransport.State.CLOSED;
        JsonNode result = pool.callTool("srv", "echo", Map.of("message", "hi")).join();
        assertThat(t2.created.get()).isEqualTo(1);
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("hi");
    }

    // ═══════════ fakes ═══════════

    /** 每次 create 返回新 tracking transport，可强制 CLOSED 状态。 */
    static class SequenceFactory extends McpTransportFactory {
        private final McpTransport[] transports;
        private int idx;

        SequenceFactory(McpTransport... transports) {
            this.transports = transports;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            McpTransport t = transports[Math.min(idx, transports.length - 1)];
            idx++;
            return t;
        }
    }

    /** 每次 create 返回同一 tracking transport（幂等测试用）。 */
    static class TrackingFactory extends McpTransportFactory {
        private final McpTransport transport;

        TrackingFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) { return transport; }
    }

    /** tools/call 返回 echo，tools/list 返回空，initialize 返回空 caps。 */
    static class TrackingTransport implements McpTransport {
        final AtomicInteger created = new AtomicInteger();
        volatile McpTransport.State state = McpTransport.State.CONNECTED;

        TrackingTransport() {
            created.incrementAndGet();
        }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities");
            } else if ("tools/list".equals(method)) {
                result.putArray("tools");
            } else if ("tools/call".equals(method)) {
                ObjectNode content = result.putArray("content").addObject();
                content.put("type", "text");
                String msg = "";
                if (params instanceof Map<?, ?> m && m.get("arguments") instanceof Map<?, ?> args) {
                    Object v = args.get("message");
                    msg = v == null ? "" : String.valueOf(v);
                }
                content.put("text", msg);
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public McpTransport.State getState() { return state; }
    }
}
