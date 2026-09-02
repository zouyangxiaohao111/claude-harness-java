package com.nexusai.application.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-29 R1] ToolRegistrationConfig.subagentExecutor @Bean 生产接线 plugin-only 权限闸.
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: CC runAgent.ts:117-127 在
 * strictPluginOnlyCustomization 锁 MCP 时跳过 USER-CONTROLLED agent 的 frontmatter MCP。
 * Java 端 {@code SubagentExecutor.setPluginOnlySettingsSupplier} 已存在但 0 生产调用方 →
 * 闸生产惰性（EV-15/EV-21）。本测试锁：
 * <ol>
 *   <li>@Bean 构建后 pluginOnly supplier 已注入（非默认 {@code Map::of}）——否则闸永不生效</li>
 *   <li>注入的 supplier 读到 policy 的 strictPluginOnlyCustomization=true + source=userSettings
 *       → {@code AgentMcpServers.initialize} 返 parentClients + 空 tools（跳过闸生效）</li>
 *   <li>source=plugin（admin-trusted）→ 不跳过</li>
 * </ol>
 */
@DisplayName("[MCP-I-9 Q-29 R1] ToolRegistrationConfig.subagentExecutor 接线 plugin-only 权限闸")
class ToolRegistrationConfigSubagentSupplierTest {

    @BeforeEach
    void resetMemoizeCache() {
        // [S05 A6] 静态 memoize 缓存跨测试污染（复用 spec 名 filesystem）→ 每用例复位
        AgentMcpServers.clearConnectionCache();
    }

    @Test
    @DisplayName("@Bean 构建后 pluginOnlySettingsSupplier 已注入（非默认 Map::of）")
    void beanWiresPluginOnlySettingsSupplier() throws Exception {
        // GIVEN: ManagedPolicySettingsSupplier 指向含 strictPluginOnlyCustomization=true 的 policy 文件
        ManagedPolicySettingsSupplier supplier = new ManagedPolicySettingsSupplier(
            new ObjectMapper(), "classpath-does-not-matter");

        // WHEN: 调用 @Bean（无 Spring 容器，null-safe 路径）
        // [RF-2 返工] bean 已加第 10 参 backgroundTaskRunner（null-safe）
        // [R31-03] bean 已加第 11 参 sdkAgentProgressSummariesEnabled（null-safe 布尔）
        // [D-3] bean 已加第 11 参 sdkEventQueue（null-safe，插到布尔前）
        // [冲突裁决·并集] 第 13/14 参 analyticsTracker+agentNameRegistry（IMP-G4）+ 第 15 参 yoloClassifier（IMP-SUB-25）
        // [循环依赖修复] 第 16 参 agentMemoryDirectory（null-safe 参数注入；null → 回落 productionDefault()）
        // [prompt-align UP-01] 第 17 参 promptAlignSettingsResolver（null → setter 回落 coordinatorMode.isCoordinatorMode()）
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        SubagentExecutor executor = config.subagentExecutor(null, null, null, null, null, supplier, null, null, null, null, null, false, null, null, null, null, null);

        // THEN: executor 的 pluginOnlySettingsSupplier 字段必须指向注入的 supplier（非默认 Map::of）
        Field f = SubagentExecutor.class.getDeclaredField("pluginOnlySettingsSupplier");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Supplier<Map<String, Object>> wired = (Supplier<Map<String, Object>>) f.get(executor);
        assertThat(wired)
            .as("@Bean 必须注入 ManagedPolicySettingsSupplier::all（否则权限闸生产惰性）")
            .isNotNull()
            .isNotSameAs((Supplier<Map<String, Object>>) Map::of);
    }

    @Test
    @DisplayName("注入 supplier 读到 policy 锁 MCP → userSettings agent 跳过 frontmatter MCP")
    void injectedSupplier_enablesPermissionGate_forUserControlledAgent() {
        // GIVEN: policy 文件 strictPluginOnlyCustomization=true
        ManagedPolicySettingsSupplier supplier = new ManagedPolicySettingsSupplier(
            new ObjectMapper(),
            tempPolicyFile(Map.of("strictPluginOnlyCustomization", true)));

        // WHEN: userSettings agent（USER-CONTROLLED）声明 frontmatter MCP
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("filesystem", "cmd", List.of(), Map.of()))),
            List.of(),
            new StubFactory(),
            "userSettings",
            supplier::all,
            60_000,
            null);  // [S05] elicitation 状态机（测试 null = 不接 elicitation）

        // THEN: 跳过闸生效 → tools 空（不连接 frontmatter MCP）
        assertThat(result.tools()).as("userSettings agent 在 MCP 锁 plugin-only 时必须跳过").isEmpty();
    }

    @Test
    @DisplayName("plugin source（admin-trusted）在 MCP 锁 plugin-only 时不跳过")
    void injectedSupplier_adminTrustedPlugin_stillLoads() {
        ManagedPolicySettingsSupplier supplier = new ManagedPolicySettingsSupplier(
            new ObjectMapper(),
            tempPolicyFile(Map.of("strictPluginOnlyCustomization", true)));

        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("filesystem", "cmd", List.of(), Map.of()))),
            List.of(),
            new StubFactory(),
            "plugin",
            supplier::all,
            60_000,
            null);

        // plugin 是 admin-approved surface → 不跳过（tools 非空）
        assertThat(result.tools()).as("plugin source 是 admin-trusted，不得跳过").isNotEmpty();
    }

    private static String tempPolicyFile(Map<String, Object> content) {
        try {
            java.nio.file.Path p = java.nio.file.Files.createTempFile("mcp-i9-policy", ".json");
            java.nio.file.Files.writeString(p, new ObjectMapper().writeValueAsString(content));
            return p.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** stub transportFactory: 返回 fake transport（与 AgentMcpServersPermissionGateTest 同构）。 */
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
                com.fasterxml.jackson.databind.node.ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-server");
                // [S05 A6] capabilities.tools 门控（truthy {}）→ tools/list 才发生
                r.putObject("capabilities").putObject("tools");
                return java.util.concurrent.CompletableFuture.completedFuture(r);
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
