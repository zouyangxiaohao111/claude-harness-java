package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-32] 子代理 mcpServers 按名解析（string-ref → DB lookup）· 对齐 CC runAgent.ts:140-151
 * + services/mcp/config.ts:1033 getMcpConfigByName。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: frontmatter {@code mcpServers: ["already-configured"]}
 * 是名字引用，应按名查 DB（Q-09=C DB 唯一运行时源）复用已配置 server，而非当作空 command 连接。
 * 旧实现把 string-ref 当 inline spec（command="", args=[], env={}）→ 连空命令失败。
 * 本测试锁：
 * <ol>
 *   <li>string-ref 命中 DB → 构 stdio spec（command+args）→ initialize 成功建连 + 工具拉取</li>
 *   <li>DB 无该名 → warn + 跳过（InitResult 空 tools 无异常，对齐 CC :145-151）</li>
 *   <li>inline spec 不受名字解析影响（stdio 缺省保持旧行为）</li>
 * </ol>
 */
@DisplayName("[MCP-I-9 Q-32] 子代理 mcpServers 按名解析（string-ref → DB lookup）")
class SubagentMcpNameResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetMemoizeCache() {
        // [S05 A6] 静态 memoize 缓存跨测试污染 → 每用例复位（RecordingFactory 计数断言依赖）
        AgentMcpServers.clearConnectionCache();
    }

    /** fake transportFactory: 记录 TransportConfig.type（验证 stdio/remote 判别）+ tools/list. */
    static class RecordingFactory extends McpTransportFactory {
        final List<String> types = new ArrayList<>();
        @Override public McpTransport create(McpTransport.TransportConfig config) {
            types.add(config.type());
            return new FakeTransport();
        }
    }

    static class FakeTransport implements McpTransport {
        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake");
                // [S05 A6] capabilities.tools 门控（truthy {}）→ tools/list 才发生
                r.putObject("capabilities").putObject("tools");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                tools.addObject().put("name", "read_file").put("description", "Read");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }

    /** 构建一个带 string-ref mcpServers 的 AgentDefinition（mcpServers=[{name}]）. */
    private static AgentDefinition defWithStringRef(String name) {
        return AgentDefinition.CustomAgentDefinition.builder("test", "test-agent", "userSettings", "prompt")
            .mcpServers(List.of(Map.of("name", name)))
            .build();
    }

    /** 构建一个带 inline spec 的 AgentDefinition（mcpServers=[{name, command}]）. */
    private static AgentDefinition defWithInline(String name, String command) {
        return AgentDefinition.CustomAgentDefinition.builder("test", "test-agent", "userSettings", "prompt")
            .mcpServers(List.of(Map.of("name", name, "command", command)))
            .build();
    }

    @Test
    @DisplayName("string-ref 命中 DB（stdio command+args）→ 建连成功 + 工具拉取")
    void stringRef_hit_connectsAndFetchesTools() {
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        RecordingFactory factory = new RecordingFactory();
        executor.setMcpTransportFactory(factory);
        // resolver: name → stdio spec（command+args，来自 DB config）
        executor.setMcpServerNameResolver(name -> Optional.of(
            new AgentMcpServers.McpServerSpec(name, "cmd", List.of("--flag"), Map.of("K", "V"), "stdio")));

        AgentMcpServers.InitResult result = executor.initializeAgentMcp(
            defWithStringRef("already-configured-server"), List.of());

        // 命中 → 真实连接 + 工具拉取（非空 command，stdio transport）
        assertThat(result.tools()).isNotEmpty();
        assertThat(factory.types).containsExactly("stdio");
    }

    @Test
    @DisplayName("DB 无该名 → warn + 跳过（InitResult 空 tools 无异常，对齐 CC :145-151）")
    void stringRef_miss_warnAndSkip_noThrow() {
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        RecordingFactory factory = new RecordingFactory();
        executor.setMcpTransportFactory(factory);
        executor.setMcpServerNameResolver(name -> Optional.empty());   // DB 未命中

        AgentMcpServers.InitResult result = executor.initializeAgentMcp(
            defWithStringRef("not-in-db"), List.of());

        // 跳过 → 空 tools、无异常（CC runAgent.ts:145-151 continue）
        assertThat(result.tools()).isEmpty();
        assertThat(factory.types).isEmpty();   // 未建任何连接
    }

    @Test
    @DisplayName("inline spec 不受名字解析影响（stdio 缺省保持旧行为）")
    void inlineSpec_unaffectedByResolver() {
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        RecordingFactory factory = new RecordingFactory();
        executor.setMcpTransportFactory(factory);
        executor.setMcpServerNameResolver(name -> Optional.of(
            new AgentMcpServers.McpServerSpec(name, "db-cmd", List.of(), Map.of(), "stdio")));

        AgentMcpServers.InitResult result = executor.initializeAgentMcp(
            defWithInline("inline-server", "inline-cmd"), List.of());

        // inline spec 走 command 建连（不触发 resolver）；stdio 缺省
        assertThat(result.tools()).isNotEmpty();
        assertThat(factory.types).containsExactly("stdio");
    }

    /**
     * [MCP-I-9 返工 R3] CC 真源 keyed inline 判别 · 对齐 loadAgentsDir.ts:66
     * {@code z.record(z.string(), McpServerConfigSchema())} + runAgent.ts:152-170
     * （inline 是 {@code {[serverName]: config}}，顶层取 entries[0] 键为 name）。
     *
     * <p><b>WHY</b>: 首轮判别器 {@code stringRef = command.isBlank() && !has("type") && !has("command")}
     * 对 keyed 形式 {@code {foo: {command: ...}}} 顶层无 command/type → 误判为 string-ref
     * → name=「unnamed」查 DB 大概率 miss → warn+skip 静默丢弃（CC runAgent.ts:152-170 应取 key 为 name）。
     */
    @Test
    @DisplayName("R3: keyed inline {[name]: config} 判别为 inline（取键为 name，非 string-ref）")
    void keyedInline_ccSchema_isTreatedAsInline_notStringRef() {
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        RecordingFactory factory = new RecordingFactory();
        executor.setMcpTransportFactory(factory);
        // resolver 抛异常：若误判为 string-ref 会查 DB（keyed inline 不应查 DB）
        executor.setMcpServerNameResolver(name -> {
            throw new AssertionError("keyed inline 不得触发 string-ref 按名查 DB，被判别为 string-ref: name=" + name);
        });

        // CC keyed 形式：{foo: {command: "cmd", args: ["--flag"]}}
        AgentDefinition def = AgentDefinition.CustomAgentDefinition.builder("test", "test-agent", "userSettings", "prompt")
            .mcpServers(List.of(Map.of("foo", Map.of("command", "cmd", "args", List.of("--flag")))))
            .build();

        AgentMcpServers.InitResult result = executor.initializeAgentMcp(def, List.of());

        // 判别为 inline → 取 key "foo" 为 name → 建连成功 + 工具拉取（stdio）
        assertThat(result.tools()).as("keyed inline 应判别为 inline 并建连，而非 string-ref 静默丢弃").isNotEmpty();
        assertThat(factory.types).containsExactly("stdio");
    }

    @Test
    @DisplayName("R3: keyed inline 多 key（{a: {...}, b: {...}}）非 CC schema → warn+skip 不抛")
    void keyedInline_multiEntry_invalid_skips_noThrow() {
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        RecordingFactory factory = new RecordingFactory();
        executor.setMcpTransportFactory(factory);

        // CC z.record keyed 形式要求单 key（runAgent.ts:155-162 entries.length !== 1 → warn+continue）
        AgentDefinition def = AgentDefinition.CustomAgentDefinition.builder("test", "test-agent", "userSettings", "prompt")
            .mcpServers(List.of(Map.of(
                "a", Map.of("command", "cmdA"),
                "b", Map.of("command", "cmdB"))))
            .build();

        AgentMcpServers.InitResult result = executor.initializeAgentMcp(def, List.of());

        // 无效 spec → skip（空 tools、无异常，对齐 CC runAgent.ts:156-162 warn+continue）
        assertThat(result.tools()).isEmpty();
        assertThat(factory.types).isEmpty();
    }
}
