package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 返工 R1+R2] SubagentTool 4 个 executor 构造点必须把 MCP name-resolver + 真实
 * plugin-only supplier 注入 executor · 对齐 CC runAgent.ts:140-151（string-ref 按名解析建连）
 * + runAgent.ts:117-127（strictPluginOnlyCustomization 锁 MCP 时跳过 frontmatter MCP）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 首轮实施只在
 * {@code ToolRegistrationConfig.subagentExecutor @Bean} 接线 resolver/supplier（fork-skill 路径），
 * SubagentTool 的 4 个构造点（executeSync/async worker/降级 sync/resume）只注入 supplier：
 * <ul>
 *   <li>模型调用子代理主路径（StreamingToolExecutor:1515 SubagentTool 分发）自建 executor 的
 *       {@code mcpServerNameResolver=null} → string-ref mcpServers fall-through 成空 command inline
 *       → 连接失败（CC runAgent.ts:140-151 要求按名查配置建连）——R1 双轨缺口。</li>
 *   <li>{@code pluginOnlySettingsSupplier} 字段默认 {@code Map::of}，setter 参数 {@code Supplier<Map>}
 *       无匹配 bean 且无外部调用 → 恒空 → {@code isRestrictedToPluginOnly} 恒 false —— R2 权限闸死字段。</li>
 * </ul>
 * 本测试经 {@code applyMcpWiring}（4 构造点共用的装配 helper）锁语义：装配后 executor 必须拿到
 * resolver + 非空 supplier。
 */
@DisplayName("[MCP-I-9 返工 R1+R2] SubagentTool MCP 装配（resolver + plugin-only supplier）注入 executor")
class SubagentToolMcpWiringTest {

    @Test
    @DisplayName("R1: 装配后 executor.mcpServerNameResolver == 注入的 resolver（非 null）")
    void applyMcpWiring_injectsNameResolver() throws Exception {
        SubagentTool tool = new SubagentTool();
        java.util.function.Function<String, Optional<AgentMcpServers.McpServerSpec>> resolver =
            name -> Optional.of(new AgentMcpServers.McpServerSpec(name, "cmd", List.of(), Map.of(), "stdio"));
        tool.setMcpServerNameResolver(resolver);

        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        tool.applyMcpWiring(executor);

        Field f = SubagentExecutor.class.getDeclaredField("mcpServerNameResolver");
        f.setAccessible(true);
        assertThat(f.get(executor))
            .as("4 构造点装配后 executor 必须拿到 resolver（否则 string-ref mcpServers 无法按名建连，CC runAgent.ts:140-151）")
            .isSameAs(resolver);
    }

    @Test
    @DisplayName("R2: setManagedPolicySettingsSupplier 后装配 → executor.pluginOnlySettingsSupplier 非默认 Map::of")
    void setManagedPolicySettingsSupplier_wiresRealSupplier() throws Exception {
        SubagentTool tool = new SubagentTool();
        ManagedPolicySettingsSupplier policy = new ManagedPolicySettingsSupplier(
            new ObjectMapper(), tempPolicyFile(Map.of("strictPluginOnlyCustomization", true)));
        tool.setManagedPolicySettingsSupplier(policy);

        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        tool.applyMcpWiring(executor);

        Field f = SubagentExecutor.class.getDeclaredField("pluginOnlySettingsSupplier");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Supplier<Map<String, Object>> wired = (Supplier<Map<String, Object>>) f.get(executor);
        assertThat(wired)
            .as("生产装配必须注入 ManagedPolicySettingsSupplier::all（否则权限闸死字段恒 Map::of，strictPluginOnlyCustomization 锁 MCP 时 USER-CONTROLLED agent 不跳过）")
            .isNotNull()
            .isNotSameAs((Supplier<Map<String, Object>>) Map::of);
    }

    @Test
    @DisplayName("R2 佐证: 经 tool 装配的 supplier 读到 policy 锁 → userSettings agent frontmatter MCP 被跳过")
    void wiredSupplier_enablesPermissionGate_endToEnd() {
        SubagentTool tool = new SubagentTool();
        ManagedPolicySettingsSupplier policy = new ManagedPolicySettingsSupplier(
            new ObjectMapper(), tempPolicyFile(Map.of("strictPluginOnlyCustomization", true)));
        tool.setManagedPolicySettingsSupplier(policy);
        tool.setMcpTransportFactory(new StubFactory());

        // 经工具构造点相同的装配 helper 构建 executor（Step 15 seam 之后进入 initializeAgentMcp）
        SubagentExecutor executor = new SubagentExecutor(null, null, null, null, null, "gpt-4", "");
        tool.applyMcpWiring(executor);

        com.nexusai.application.agent.subagent.AgentDefinition def =
            defWithInline("filesystem", "cmd");
        AgentMcpServers.InitResult result = executor.initializeAgentMcp(def, List.of());

        assertThat(result.tools()).as("userSettings agent 在 MCP 锁 plugin-only 时必须跳过 frontmatter MCP").isEmpty();
    }

    private static com.nexusai.application.agent.subagent.AgentDefinition defWithInline(String name, String command) {
        return com.nexusai.application.agent.subagent.AgentDefinition.CustomAgentDefinition.builder(
                "test-agent", "test", "userSettings", "prompt")
            .mcpServers(List.of(Map.of("name", name, "command", command)))
            .build();
    }

    private static String tempPolicyFile(Map<String, Object> content) {
        try {
            Path p = Files.createTempFile("mcp-i9-rework-policy", ".json");
            Files.writeString(p, new ObjectMapper().writeValueAsString(content));
            return p.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** stub transportFactory: 返回 fake transport（与 ToolRegistrationConfigSubagentSupplierTest 同构）。 */
    static class StubFactory extends com.nexusai.application.agent.mcp.McpTransportFactory {
        @Override
        public com.nexusai.application.agent.mcp.McpTransport create(
                com.nexusai.application.agent.mcp.McpTransport.TransportConfig config) {
            return new FakeTransport();
        }
    }

    static class FakeTransport implements com.nexusai.application.agent.mcp.McpTransport {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        @Override public void start(com.nexusai.application.agent.mcp.McpTransport.TransportConfig config) {}
        @Override public java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> sendRequest(
                String method, Object params) {
            if ("initialize".equals(method)) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    MAPPER.createObjectNode().putObject("serverInfo").put("name", "fake-server"));
            }
            if ("tools/list".equals(method)) {
                com.fasterxml.jackson.databind.node.ObjectNode r = MAPPER.createObjectNode();
                com.fasterxml.jackson.databind.node.ArrayNode tools = r.putArray("tools");
                tools.addObject().put("name", "read_file").put("description", "Read a file");
                return java.util.concurrent.CompletableFuture.completedFuture(r);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method,
                com.nexusai.application.agent.mcp.McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public com.nexusai.application.agent.mcp.McpTransport.State getState() {
            return com.nexusai.application.agent.mcp.McpTransport.State.CONNECTED;
        }
    }
}
