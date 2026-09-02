package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 [差异 2] AgentMcpServers 权限闸 + newlyCreated 选择性 cleanup.
 *
 * <p>规则九 (测试验证意图而非行为): 意图是 —
 * <ul>
 *   <li>strictPluginOnlyCustomization 锁 MCP 时, USER-CONTROLLED agent 的 frontmatter MCP
 *       必须跳过 (CC runAgent.ts:117-127)，否则恶意 agent 可声明任意 MCP server (安全漏洞)。</li>
 *   <li>plugin / built-in / policySettings 是 admin-trusted, 其 frontmatter MCP 是
 *       admin-approved surface, 不跳过 (CC runAgent.ts:117)。</li>
 *   <li>cleanup 只清 newly created (inline) clients, 共享 memoized parentClients 不清
 *       (CC runAgent.ts:132/176/198)。</li>
 * </ul>
 */
@DisplayName("[S5] AgentMcpServers 权限闸 + newlyCreated 选择性 cleanup 对齐 CC runAgent.ts")
class AgentMcpServersPermissionGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetMemoizeCache() {
        // [S05 A6] 静态 memoize 缓存跨测试污染（既有测试复用 spec 名 filesystem）→ 每用例复位
        AgentMcpServers.clearConnectionCache();
    }

    /** fake transportFactory: 每次 create 返回一个可追踪 close 的 fake transport. */
    static class FakeFactory extends McpTransportFactory {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new FakeTransport(closeCount);
        }
    }

    /** fake transport: initialize/tools/list 返固定 JSON, close() 计数. */
    static class FakeTransport implements McpTransport {
        private final AtomicInteger closeCount;

        FakeTransport(AtomicInteger closeCount) { this.closeCount = closeCount; }

        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-server");
                // [S05 A6] capabilities.tools 门控（truthy {}）→ tools/list 才发生
                r.putObject("capabilities").putObject("tools");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                ObjectNode tool = tools.addObject();
                tool.put("name", "read_file");
                tool.put("description", "Read a file");
                tool.putObject("annotations").put("readOnlyHint", true);
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() { closeCount.incrementAndGet(); }
        @Override public State getState() { return State.CONNECTED; }
    }

    private static AgentMcpServers.McpServerSpec spec() {
        return new AgentMcpServers.McpServerSpec("filesystem", "cmd", List.of(), Map.of());
    }

    private static Supplier<Map<String, Object>> settings(boolean locked) {
        return locked ? () -> Map.of("strictPluginOnlyCustomization", true) : Map::of;
    }

    /** 可追踪 cleanup 的 parent client. */
    static class TrackedClient implements AgentMcpServers.McpServerConnection {
        final String name;
        final AtomicInteger cleanups = new AtomicInteger();
        TrackedClient(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public List<Tool> getTools() { return List.of(); }
        @Override public void cleanup() { cleanups.incrementAndGet(); }
    }

    @Test
    void initialize_skipsFrontmatterMcp_whenPluginOnlyAndNotAdminTrusted() {
        // WHY: USER-CONTROLLED agent 不能加载任意 frontmatter MCP (安全)
        // 不写 = 安全漏洞: 恶意 agent 可声明任意 MCP server
        TrackedClient parent = new TrackedClient("parent");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(spec())),
            List.of(parent),
            factory,
            "userSettings",
            settings(true),
            60_000,
            null);  // [S05] elicitation 状态机（测试 null = 不接 elicitation）
        // 跳过 → tools 为空, 只有 parentClients
        assertThat(result.tools()).isEmpty();
        assertThat(result.clients()).hasSize(1);
        assertThat(result.clients().get(0).name()).isEqualTo("parent");
        // cleanup 不清任何 (无 newly created)
        result.cleanup().run();
        assertThat(factory.closeCount.get()).isZero();
        assertThat(parent.cleanups.get()).isZero();
    }

    @Test
    void initialize_loadsFrontmatterMcp_whenAdminTrusted() {
        // 反向: source=plugin (admin-trusted) → MCP 不跳过, 真实 tools/list
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(spec())),
            List.of(),
            factory,
            "plugin",
            settings(true),
            60_000,
            null);
        assertThat(result.tools()).isNotEmpty();
        // annotations 透传: read_file 声明 readOnlyHint=true → isReadOnly=true
        Tool agentTool = result.tools().get(0);
        assertThat(agentTool.isReadOnly(null)).isTrue();
        assertThat(agentTool.description()).isEqualTo("Read a file");
    }

    @Test
    void initialize_loadsFrontmatterMcp_whenPluginOnlyDisabled() {
        // 边界: isRestrictedToPluginOnly('mcp')=false → 所有 source 都加载
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(spec())),
            List.of(),
            factory,
            "userSettings",
            settings(false),
            60_000,
            null);
        assertThat(result.tools()).isNotEmpty();
    }

    @Test
    void cleanup_onlyCleansNewlyCreatedClients_notShared() {
        // WHY: 共享 memoized client 不应被 agent cleanup 误清 (CC runAgent.ts:132/176/198)
        // 不写 = 误清: parentClients 被 agent 结束时关闭, 后续 parent 的 MCP 调用全挂
        TrackedClient parent = new TrackedClient("parent");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(spec())),
            List.of(parent),
            factory,
            "userSettings",
            settings(false),
            60_000,
            null);
        assertThat(result.clients()).hasSize(2); // parent + agent client
        result.cleanup().run();
        // agent client (newly created) 被 cleanup, parent client 不清
        assertThat(factory.closeCount.get()).isEqualTo(1);
        assertThat(parent.cleanups.get()).isZero();
    }
}
