package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S05 A6/A5] AgentMcpServers memoize 共享连接 + capabilities.tools 门控 + headers 透传 ·
 * 对齐 CC client.ts:595 connectToServer=memoize + :1748-1750 fetchToolsForClient 门控
 * + runAgent.ts:163-169 inline config。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 旧路径每次 initialize 无条件
 * create+start+initialize+tools/list（无 memoize 共享、无 capabilities 门控）——
 * 同 server 重复建 transport、无 tools 能力的 server 也白拉 tools/list。本测试锁：
 * <ol>
 *   <li>同 name+config 两次 initialize → factory.create 恰 1 次 + 连接对象同一引用
 *       （A6 memoize）；cleanup 后缓存条目移除 → 再 initialize 重建</li>
 *   <li>initialize 响应无 capabilities.tools（或 false）→ 不发 tools/list、tools 空；
 *       capabilities.tools={}（空对象 truthy）→ 发 tools/list（门控）</li>
 *   <li>fromConfig 把 cfg.headers 并入 env（A5 透传通道，对齐生产轨 headers→env 契约）</li>
 *   <li>wrapSharedPoolClient：cleanup no-op、getTools 快照（CC runAgent.ts:196-210 共享不清）</li>
 * </ol>
 */
@DisplayName("[S05 A6/A5] AgentMcpServers memoize + capabilities 门控 + headers 透传")
class AgentMcpServersMemoizeGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetMemoizeCache() {
        AgentMcpServers.clearConnectionCache();
    }

    /** fake transportFactory：计数 create + 每 transport 独立 capabilities 供给。 */
    static class CountingFactory extends McpTransportFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final AtomicInteger toolsListCount = new AtomicInteger();
        final Supplier<JsonNode> capabilitiesSupplier;

        CountingFactory(Supplier<JsonNode> capabilitiesSupplier) {
            this.capabilitiesSupplier = capabilitiesSupplier;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            createCount.incrementAndGet();
            return new GateTransport(toolsListCount, capabilitiesSupplier);
        }
    }

    static class GateTransport implements McpTransport {
        private final AtomicInteger toolsListCount;
        private final Supplier<JsonNode> capabilitiesSupplier;

        GateTransport(AtomicInteger toolsListCount, Supplier<JsonNode> capabilitiesSupplier) {
            this.toolsListCount = toolsListCount;
            this.capabilitiesSupplier = capabilitiesSupplier;
        }

        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-server");
                JsonNode caps = capabilitiesSupplier.get();
                if (caps != null) {
                    r.set("capabilities", caps);
                }
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                toolsListCount.incrementAndGet();
                ObjectNode r = MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                tools.addObject().put("name", "read_file").put("description", "Read a file");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }

    private static Supplier<Map<String, Object>> noLock() { return Map::of; }

    private static AgentMcpServers.InitResult init(String name, CountingFactory factory) {
        return AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec(name, "cmd", List.of(), Map.of()))),
            List.of(),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);
    }

    // ── ① A6 memoize ──

    @Test
    @DisplayName("同 name+config 两次 initialize → create 恰 1 次 + 同一引用；cleanup 移除缓存后重建")
    void memoize_reusesConnection_andCleanupRemovesCacheEntry() {
        CountingFactory factory = new CountingFactory(() -> {
            ObjectNode caps = MAPPER.createObjectNode();
            caps.putObject("tools");
            return caps;
        });
        AgentMcpServers.InitResult r1 = init("memo-server", factory);
        AgentMcpServers.InitResult r2 = init("memo-server", factory);

        assertThat(factory.createCount.get())
            .as("memoize 命中必须复用连接，不重建 transport（CC client.ts:595）")
            .isEqualTo(1);
        assertThat(r2.clients().get(0)).isSameAs(r1.clients().get(0));

        // memoize 命中的连接不入 newlyCreated → r2.cleanup 不清
        r2.cleanup().run();
        assertThat(factory.createCount.get()).isEqualTo(1);

        // 首个（新建者）cleanup → close + 移除缓存条目
        r1.cleanup().run();
        AgentMcpServers.InitResult r3 = init("memo-server", factory);
        assertThat(factory.createCount.get())
            .as("cleanup 必须移除缓存条目，防闭后复用（跨 agent 复用被首个 cleanup 关闭场景）")
            .isEqualTo(2);
        assertThat(r3.clients().get(0)).isNotSameAs(r1.clients().get(0));
    }

    // ── ② capabilities 门控 ──

    @Test
    @DisplayName("无 capabilities.tools → 不发 tools/list、tools 空（CC :1748-1750）")
    void gate_missingCapabilities_skipsToolsList() {
        CountingFactory factory = new CountingFactory(() -> null);   // initialize 无 capabilities
        AgentMcpServers.InitResult result = init("no-caps-server", factory);

        assertThat(factory.toolsListCount.get()).as("无 capabilities.tools 不得发 tools/list").isZero();
        assertThat(result.tools()).isEmpty();
        assertThat(result.clients()).hasSize(1);
    }

    @Test
    @DisplayName("capabilities.tools=false → 不发 tools/list、tools 空")
    void gate_falseCapabilities_skipsToolsList() {
        CountingFactory factory = new CountingFactory(() -> {
            ObjectNode caps = MAPPER.createObjectNode();
            caps.put("tools", false);
            return caps;
        });
        AgentMcpServers.InitResult result = init("false-caps-server", factory);

        assertThat(factory.toolsListCount.get()).isZero();
        assertThat(result.tools()).isEmpty();
    }

    @Test
    @DisplayName("capabilities.tools={}（空对象 truthy）→ 发 tools/list、tools 非空")
    void gate_emptyObjectCapabilities_fetchesTools() {
        CountingFactory factory = new CountingFactory(() -> {
            ObjectNode caps = MAPPER.createObjectNode();
            caps.putObject("tools");
            return caps;
        });
        AgentMcpServers.InitResult result = init("empty-caps-server", factory);

        assertThat(factory.toolsListCount.get()).as("capabilities.tools={} 为 truthy，必须拉 tools/list").isEqualTo(1);
        assertThat(result.tools()).isNotEmpty();
    }

    // ── ③ A5 headers 透传 ──

    @Test
    @DisplayName("fromConfig 把 cfg.headers 并入 env（A5 透传通道，对齐生产轨 headers→env）")
    void fromConfig_mergesHeadersIntoEnv() {
        Map<String, Object> cfg = Map.of(
            "type", "http",
            "url", "http://localhost:9999/mcp",
            "headers", Map.of("Authorization", "Bearer tok-1", "X-Custom", "v"));
        AgentMcpServers.McpServerSpec spec = AgentMcpServers.fromConfig("hdr-server", cfg);

        assertThat(spec.type()).isEqualTo("http");
        assertThat(spec.command()).isEqualTo("http://localhost:9999/mcp");
        assertThat(spec.env())
            .as("headers 必须并入 env 载体（生产轨 McpServerService 远程契约）")
            .containsEntry("Authorization", "Bearer tok-1")
            .containsEntry("X-Custom", "v");
    }

    @Test
    @DisplayName("fromConfig：无 headers → env 保持原样（不污染）")
    void fromConfig_noHeaders_envUntouched() {
        Map<String, Object> cfg = Map.of(
            "type", "stdio",
            "command", "cmd",
            "env", Map.of("K", "V"));
        AgentMcpServers.McpServerSpec spec = AgentMcpServers.fromConfig("plain-server", cfg);
        assertThat(spec.env()).containsExactly(Map.entry("K", "V"));
    }

    // ── ④ wrapSharedPoolClient ──

    @Test
    @DisplayName("wrapSharedPoolClient：getTools 快照 + cleanup no-op（共享池连接不清）")
    void wrapSharedPoolClient_snapshotAndNoopCleanup() {
        Tool t1 = new DummyTool("mcp__fs__read_file");
        Tool t2 = new DummyTool("mcp__fs__write_file");
        List<Tool> source = new ArrayList<>(List.of(t1, t2));
        AgentMcpServers.McpServerConnection conn = AgentMcpServers.wrapSharedPoolClient("fs", source);

        assertThat(conn.name()).isEqualTo("fs");
        assertThat(conn.getTools()).containsExactly(t1, t2);

        // 快照：源列表变更不影响已包装连接
        source.remove(1);
        assertThat(conn.getTools()).containsExactly(t1, t2);

        // cleanup no-op：共享池连接不被 agent cleanup 清（CC runAgent.ts:196-210）
        conn.cleanup();
        conn.cleanup();
        assertThat(conn.getTools()).containsExactly(t1, t2);
    }

    /** 最小 Tool 桩（name 可定制）。 */
    static class DummyTool implements Tool {
        private final String name;
        DummyTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return MAPPER.createObjectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return null;
        }
    }
}
