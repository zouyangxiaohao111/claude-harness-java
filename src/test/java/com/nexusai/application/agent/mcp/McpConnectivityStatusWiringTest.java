package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpConnectivityStatus.McpClient;
import com.nexusai.application.agent.mcp.McpConnectivityStatus.Notification;
import com.nexusai.application.agent.mcp.McpTypesRegistry.FailedMCPServer;
import com.nexusai.application.agent.mcp.McpTypesRegistry.NeedsAuthMCPServer;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ScopedMcpServerConfig;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4 Q-08] McpConnectivityStatus 生产接线测试。
 *
 * <p>WHY（规则九）：连接状态通知是「MCP server 连接成功/失败/认证失败时，用户应得到
 * mcp-failed / mcp-needs-auth 通知」的意图落地（CC useMcpConnectivityStatus 对 mcpClients
 * 做 4 组 filter → addNotification）。仅断言 classify 纯函数本身（原有 McpConnectivityStatus 测试）
 * 不够——必须验证 ① classify 被生产消费方调用（McpToolPool 连接生命周期驱动注册表 → classify），
 * ② 真实 CC record（NeedsAuthMCPServer / FailedMCPServer）被消费，③ 状态可供查询
 * （queryStatus / queryClients / typeOf）。
 */
@DisplayName("[S4] McpConnectivityStatus 生产接线")
class McpConnectivityStatusWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger CONNECT_ATTEMPTS = new AtomicInteger();

    private McpTransport.TransportConfig httpConfig(String url) {
        return new McpTransport.TransportConfig(null, null, Map.of(), null, "svc", "http");
    }

    private static McpTypesRegistry.McpServerConfig configOfType(String type) {
        return new McpTypesRegistry.McpServerConfig() {
            @Override public String type() { return type; }
        };
    }

    private static ScopedMcpServerConfig scoped(String type) {
        return new ScopedMcpServerConfig(configOfType(type), McpTypesRegistry.ConfigScope.LOCAL, null);
    }

    // ───────────── ① classify 被生产消费（真实 MCPServerConnection record 被消费）─────────────

    /**
     * WHY：classify 必须消费真实 NeedsAuthMCPServer/FailedMCPServer 记录（而非仅精简 McpClient），
     * 这是「生产连接记录 → 通知」链路的核心；NeedsAuthMCPServer 是 Q-08 点名的被消费 record。
     */
    @Test
    @DisplayName("classify 消费 NeedsAuthMCPServer → mcp-needs-auth 通知")
    void classify_consumesNeedsAuthRecord() {
        List<Notification> out = McpConnectivityStatus.classify(
            McpConnectivityStatus.toClients(List.of(new NeedsAuthMCPServer("svc", scoped("http")))),
            name -> false);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).key()).isEqualTo("mcp-needs-auth");
        assertThat(out.get(0).text()).isEqualTo("1 MCP server needs auth");
        assertThat(out.get(0).priority()).isEqualTo("medium");
    }

    @Test
    @DisplayName("classify 消费 FailedMCPServer → mcp-failed 通知")
    void classify_consumesFailedRecord() {
        List<Notification> out = McpConnectivityStatus.classify(
            McpConnectivityStatus.toClients(List.of(new FailedMCPServer("svc", scoped("http"), "conn refused"))),
            name -> false);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).key()).isEqualTo("mcp-failed");
        assertThat(out.get(0).text()).isEqualTo("1 MCP server failed");
    }

    @Test
    @DisplayName("claudeai-proxy needs-auth + everConnected → mcp-claudeai-needs-auth")
    void classify_claudeAiNeedsAuth_requiresEverConnected() {
        // 从未连接 → 不触发（CC hasClaudeAiMcpEverConnected 门控）
        List<Notification> never = McpConnectivityStatus.classify(
            McpConnectivityStatus.toClients(List.of(new NeedsAuthMCPServer("svc", scoped("claudeai-proxy")))),
            name -> false);
        assertThat(never).isEmpty();
        // 曾连接 → 触发
        List<Notification> ever = McpConnectivityStatus.classify(
            McpConnectivityStatus.toClients(List.of(new NeedsAuthMCPServer("svc", scoped("claudeai-proxy")))),
            name -> name.equals("svc"));
        assertThat(ever).hasSize(1);
        assertThat(ever.get(0).key()).isEqualTo("mcp-claudeai-needs-auth");
    }

    // ───────────── ② 注册表状态生产 + 查询接口 ─────────────

    @Test
    @DisplayName("注册表 updateNeedsAuth → snapshot 含真实 record + queryStatus 通知 + typeOf")
    void registry_needsAuth_queryable() {
        McpConnectivityStatusRegistry reg = new McpConnectivityStatusRegistry();
        reg.updateNeedsAuth("svc", "http");

        assertThat(reg.snapshot()).hasSize(1);
        assertThat(reg.snapshot().get(0)).isInstanceOf(NeedsAuthMCPServer.class);
        assertThat(reg.typeOf("svc")).isEqualTo("needs-auth");
        assertThat(reg.queryStatus()).hasSize(1);
        assertThat(reg.queryStatus().get(0).key()).isEqualTo("mcp-needs-auth");
        assertThat(reg.queryClients()).anyMatch(c -> c.name().equals("svc") && "needs-auth".equals(c.type()));
    }

    @Test
    @DisplayName("注册表 updateConnected 清除降级态 → queryClients 投影 connected")
    void registry_connected_clearsDegraded() {
        McpConnectivityStatusRegistry reg = new McpConnectivityStatusRegistry();
        reg.updateNeedsAuth("svc", "http");
        reg.updateConnected("svc", "http");

        assertThat(reg.snapshot()).isEmpty();                 // 降级态已清除
        assertThat(reg.queryStatus()).isEmpty();              // 无通知
        assertThat(reg.typeOf("svc")).isEqualTo("connected");
        assertThat(reg.queryClients()).hasSize(1);
        assertThat(reg.queryClients().get(0)).isEqualTo(new McpClient("connected", "http", "svc"));
    }

    @Test
    @DisplayName("注册表 updateFailed → FailedMCPServer + mcp-failed 通知")
    void registry_failed_queryable() {
        McpConnectivityStatusRegistry reg = new McpConnectivityStatusRegistry();
        reg.updateFailed("svc", "http", "conn refused");

        assertThat(reg.snapshot()).hasSize(1);
        assertThat(reg.snapshot().get(0)).isInstanceOf(FailedMCPServer.class);
        assertThat(reg.typeOf("svc")).isEqualTo("failed");
        assertThat(reg.queryStatus()).hasSize(1);
        assertThat(reg.queryStatus().get(0).key()).isEqualTo("mcp-failed");
    }

    @Test
    @DisplayName("注册表 remove 移除 → 不再产生通知")
    void registry_remove_clears() {
        McpConnectivityStatusRegistry reg = new McpConnectivityStatusRegistry();
        reg.updateFailed("svc", "http", "boom");
        reg.remove("svc");

        assertThat(reg.snapshot()).isEmpty();
        assertThat(reg.queryStatus()).isEmpty();
        assertThat(reg.typeOf("svc")).isNull();
    }

    // ───────────── ③ McpToolPool 批连接 → 注册表（生产接线端到端）─────────────

    /**
     * WHY：连接期 401 不仅产出伪工具（S3 已验证），还必须驱动连接状态注册表 → classify 通知
     * （对齐 CC client.ts:1105-1107 → handleRemoteAuthFailure → mcpClients needs-auth）。
     */
    @Test
    @DisplayName("批连接 401 → 注册表 needs-auth（classify 生产调用）")
    void batchConnect401_updatesRegistryNeedsAuth() {
        CONNECT_ATTEMPTS.set(0);
        McpToolPool pool = new McpToolPool(new AuthFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> {}).join();

        McpConnectivityStatusRegistry reg = pool.connectivityStatusRegistry();
        assertThat(reg.typeOf("svc")).isEqualTo("needs-auth");
        assertThat(reg.snapshot()).hasSize(1);
        assertThat(reg.snapshot().get(0)).isInstanceOf(NeedsAuthMCPServer.class);
        assertThat(reg.queryStatus()).hasSize(1);
        assertThat(reg.queryStatus().get(0).key()).isEqualTo("mcp-needs-auth");
    }

    /**
     * WHY：非认证连接失败 → 注册表 failed（对齐 CC catch → type='failed' :2388-2396），
     * 而非 needs-auth——认证失败与连接失败必须区分，通知文案不同。
     */
    @Test
    @DisplayName("批连接非认证失败 → 注册表 failed")
    void batchConnectNonAuthFail_updatesRegistryFailed() {
        McpToolPool pool = new McpToolPool(new PlainFailFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> {}).join();

        McpConnectivityStatusRegistry reg = pool.connectivityStatusRegistry();
        assertThat(reg.typeOf("svc")).isEqualTo("failed");
        assertThat(reg.queryStatus()).hasSize(1);
        assertThat(reg.queryStatus().get(0).key()).isEqualTo("mcp-failed");
    }

    /** WHY：连接成功 → 注册表 connected + 无通知（对齐 CC client.type='connected'，connected 不产生通知）。 */
    @Test
    @DisplayName("批连接成功 → 注册表 connected（无通知）")
    void batchConnectSuccess_updatesRegistryConnected() {
        McpToolPool pool = new McpToolPool(new SuccessFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        List<McpToolPool.McpToolEntry>[] captured = new List[1];
        pool.getMcpToolsCommandsAndResources(List.of(
            new McpToolPool.McpServerConfigEntry("svc", httpConfig("http://svc"))),
            (name, config, tools, commands, resources) -> captured[0] = tools).join();

        McpConnectivityStatusRegistry reg = pool.connectivityStatusRegistry();
        assertThat(reg.typeOf("svc")).isEqualTo("connected");
        assertThat(reg.queryStatus()).isEmpty();
        // connected 已注册进 LLM 池（成功路径工具非空）
        assertThat(captured[0]).isNotEmpty();
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

    /** 连接 + tools/list 均成功（返回一个真实工具）。 */
    static class SuccessFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new SuccessTransport();
        }
    }

    static class SuccessTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                // capabilities.tools 为 object → Capabilities.toolsList=true → fetchTools 放行
                result.putObject("capabilities").putObject("tools");
            } else if ("tools/list".equals(method)) {
                ObjectNode tools = result.putArray("tools").addObject();
                tools.put("name", "my_tool");
                tools.put("description", "test tool");
                tools.putObject("inputSchema");
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }
}
