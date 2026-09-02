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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S3 needs-auth 伪工具接线] McpToolPool.processBatchServer 三段接线测试（Q-02/Q-21）。
 *
 * <p>WHY（规则九）：needs-auth 伪工具产出是「当 MCP server 返回 401/needs-auth 时，模型应看到
 * {@code mcp__<server>__authenticate} 而非真实工具」的意图落地。仅断言 McpAuthTool 本身可用
 * （McpAuthToolTest）不够——必须验证 batch 连接路径把 401 降级为伪工具、缓存跳过路径不重连、
 * 非认证失败仍 fail-soft 空回调，三段对齐 CC client.ts:2318/:2331/:2388-2396。
 */
@DisplayName("[S3] needs-auth 伪工具接线")
class McpNeedsAuthWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger CONNECT_ATTEMPTS = new AtomicInteger();

    private McpTransport.TransportConfig httpConfig(String url) {
        return new McpTransport.TransportConfig(null, null, Map.of(), null, "svc", "http");
    }

    private static McpTransport.TransportConfig sseConfig(String url) {
        return new McpTransport.TransportConfig(null, null, Map.of(), null, "svc", "sse");
    }

    // ───────────── 连接阶段 401 → needs-auth 伪工具（CC client.ts:2331）─────────────

    /**
     * WHY：连接期 initialize 返回 401（McpAuthError）→ 该 server 真实工具不可用，必须产出
     * mcp__svc__authenticate 伪工具替换（CC connectToServer UnauthorizedError → handleRemoteAuthFailure
     * → type='needs-auth' → tools=[createMcpAuthTool]，client.ts:1105-1107/:2331）。
     */
    @Test
    @DisplayName("连接期 401 → 产出 mcp__svc__authenticate 伪工具")
    void connect401_producesAuthenticatePseudoTool() {
        CONNECT_ATTEMPTS.set(0);
        McpToolPool pool = new McpToolPool(new AuthFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry>[] captured = new List[1];
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> captured[0] = tools).join();

        assertThat(captured[0]).hasSize(1);
        assertThat(captured[0].get(0).mcpToolName()).isEqualTo("mcp__svc__authenticate");
        assertThat(captured[0].get(0).tool()).isInstanceOf(McpAuthTool.class);
        // 伪工具 isEnabled → 每轮 turn assembleToolPool 会登记进 ToolRegistry → dispatch 可触发 OAuth
        assertThat(captured[0].get(0).tool().isEnabled()).isTrue();
    }

    // ───────────── 缓存跳过路径（CC client.ts:2307-2322 / :2318）─────────────

    /**
     * WHY：第一次批连接 401 后 needs-auth 缓存置位（15min TTL），第二次批连接必须跳过连接
     * （不重试 connect），直接产出伪工具——避免每 15min 对无法成功的 server 重复网络往返
     * （CC client.ts:2301-2314 注释）。验证 connect 未被二次触发（CONNECT_ATTEMPTS 计数）。
     */
    @Test
    @DisplayName("needs-auth 缓存置位后第二次批连接跳过连接直接产伪工具")
    void cacheSkipped_secondBatchSkipsConnect() {
        CONNECT_ATTEMPTS.set(0);
        McpToolPool pool = new McpToolPool(new AuthFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry> first = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<McpToolPool.McpToolEntry> second = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 第一次：连接 → 401 → 伪工具 + 缓存置位
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> first.addAll(tools)).join();
        int attemptsAfterFirst = CONNECT_ATTEMPTS.get();
        // 第二次：缓存命中 → 跳过连接，直接产伪工具
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> second.addAll(tools)).join();

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).mcpToolName()).isEqualTo("mcp__svc__authenticate");
        // 第二次未触发新 connect（缓存跳过）
        assertThat(CONNECT_ATTEMPTS.get()).isEqualTo(attemptsAfterFirst);
    }

    // ───────────── 非认证连接失败 → fail-soft 空回调（CC client.ts:2388-2396）─────────────

    /**
     * WHY：普通连接失败（非 401）不得产出伪工具——CC 仅 UnauthorizedError 走 needs-auth，
     * 其余错误 type='failed' tools=[]（client.ts:2330-2334/:2388-2396）。
     */
    @Test
    @DisplayName("非认证连接失败 → fail-soft 空工具回调（不产伪工具）")
    void nonAuthConnectError_failSoftEmptyTools() {
        McpToolPool pool = new McpToolPool(new PlainFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry>[] captured = new List[1];
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> captured[0] = tools).join();

        assertThat(captured[0]).isEmpty();
    }

    // ───────────── sse 同样走 needs-auth（CC client.ts:1121-1123）─────────────

    @Test
    @DisplayName("sse 连接期 401 → 同样产出伪工具（sse/http 均支持 OAuth）")
    void sse401_producesAuthenticatePseudoTool() {
        McpToolPool pool = new McpToolPool(new AuthFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry>[] captured = new List[1];
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", sseConfig("http://svc"))),
            (name, config, tools, commands, resources) -> captured[0] = tools).join();

        assertThat(captured[0]).hasSize(1);
        assertThat(captured[0].get(0).mcpToolName()).isEqualTo("mcp__svc__authenticate");
    }

    // ═════════════ 单 server 启动路径（R2-2：assembleToolPool 连接期 401 → 伪工具）═════════════

    /**
     * WHY（规则九）：McpServerService.start() 走 {@link McpToolPool#assembleToolPool}（单 server
     * 显式装配，对齐 CC reconnectMcpServerImpl）。R1 S3 已接线的 batch 路径（processBatchServer）
     * 覆盖不了 start()：start() 若遇连接期 401 曾直接抛 RuntimeException → status=error，模型
     * 看不到 {@code mcp__<server>__authenticate}，无法发起 OAuth。本测试锁定单 server 装配
     * 路径同样产出伪工具（对齐 CC connectToServer → type='needs-auth' → 调用方产伪工具，
     * client.ts:1105-1107/:1121-1123/:2331）。
     */
    @Test
    @DisplayName("单 server 装配（start() 路径）连接期 401 → 返回 authenticate 伪工具注册项")
    void assembleToolPool_connect401_returnsAuthPseudoTool() {
        McpToolPool pool = new McpToolPool(new AuthFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry> entries = pool.assembleToolPool("svc", httpConfig("http://svc"));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).mcpToolName()).isEqualTo("mcp__svc__authenticate");
        assertThat(entries.get(0).tool()).isInstanceOf(McpAuthTool.class);
        // 伪工具 isEnabled → 每轮 turn assembleToolPool 会登记进 ToolRegistry → dispatch 可触发 OAuth
        assertThat(entries.get(0).tool().isEnabled()).isTrue();
    }

    /**
     * WHY：单 server 装配非认证失败（start() 抛 IllegalStateException）必须保持既有契约——
     * 抛 RuntimeException（McpServerService.start() catch → status=error）。仅 401/needs-auth
     * 走伪工具，普通连接失败不得伪装（对齐 CC 仅 UnauthorizedError 走 needs-auth）。
     */
    @Test
    @DisplayName("单 server 装配非认证连接失败 → 仍抛 RuntimeException（契约保持）")
    void assembleToolPool_nonAuthFailure_stillThrows() {
        McpToolPool pool = new McpToolPool(new PlainFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        assertThatThrownBy(() -> pool.assembleToolPool("svc", httpConfig("http://svc")))
            .isInstanceOf(RuntimeException.class);
    }

    // ═══════════ fakes ═══════════

    /** initialize 返回 401 → McpAuthError（模拟 server 需认证）。 */
    static class AuthFailFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new AuthFailTransport(config == null ? null : config.serverName());
        }
    }

    static class AuthFailTransport implements McpTransport {
        private final String serverName;

        AuthFailTransport(String serverName) { this.serverName = serverName; }

        @Override
        public void start(McpTransport.TransportConfig config) {
            CONNECT_ATTEMPTS.incrementAndGet();
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            if ("initialize".equals(method)) {
                f.completeExceptionally(new McpAuthError(serverName, "requires re-authorization (token expired)"));
            } else {
                f.complete(MAPPER.createObjectNode());
            }
            return f;
        }

        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }

    /** 普通连接失败（start 抛错，非 401）。 */
    static class PlainFailFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new PlainFailTransport();
        }
    }

    static class PlainFailTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            return CompletableFuture.completedFuture(result);
        }

        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }
}
