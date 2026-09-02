package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-4 T9] 工具名残留裸拼接统一测试（McpToolPool :393/:934 + RuleQuery :143/:145 → McpStringUtils）。
 *
 * <p>WHY（规则九）：server 名含 {@code .} / 空格 / 大写（如 {@code my server.tools}）时，旧裸拼接
 * {@code "mcp__" + serverName + "__" + toolName} 与 CC 规范化消费方（skill 命令 buildMcpToolName、
 * mcpInfoFromString 按 {@code __} 解析、权限规则）失配——工具注册了但权限/过滤查不到，静默失效。
 */
@DisplayName("[impl-I-4 T9] 工具名裸拼接统一 McpStringUtils")
class McpToolNameUnifyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    @Test
    @DisplayName("McpStringUtils.buildMcpToolName: server 名含 . / 空格 → 规范化（mcpStringUtils.ts:50-52）")
    void buildMcpToolName_normalizesServerAndTool() {
        assertThat(McpStringUtils.buildMcpToolName("my server.tools", "echo"))
            .isEqualTo("mcp__my_server_tools__echo");
        assertThat(McpStringUtils.buildMcpToolName("GitHub", "create-issue"))
            .isEqualTo("mcp__GitHub__create-issue");  // 保留大小写（normalization.ts:17-23）
    }

    @Test
    @DisplayName("RuleQuery.getToolNameForPermissionCheck: MCP 工具全名规范化（mcpStringUtils.ts:60-67）")
    void ruleQuery_permissionCheckName_isNormalized() {
        Tool mcpTool = new McpServerTool("my server.tools", "echo", "mcp__my_server_tools__echo", null, null, null, null, null, null);
        // McpServerTool.mcpInfo() 返回原始 serverName（未规范化），RuleQuery 构建时规范化 → 与注册名一致
        assertThat(RuleQuery.getToolNameForPermissionCheck(mcpTool))
            .isEqualTo("mcp__my_server_tools__echo");
    }

    @Test
    @DisplayName("McpToolPool.fetchTools: mcpToolName 规范化注册（旧裸拼接 mcp__my server.tools__x = RED）")
    void fetchTools_registersNormalizedName() {
        ToolReturningTransport fake = new ToolReturningTransport();
        McpToolPool pool = new McpToolPool(new McpResourcesListTest.FakeFactory(fake),
            new ToolRegistry(), client);
        pool.assembleToolPool("my server.tools",
            new McpTransport.TransportConfig("python", List.of("x"), Map.of(), null, null, "stdio"));

        List<McpToolPool.McpToolEntry> entries = pool.fetchTools("my server.tools");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).mcpToolName()).isEqualTo("mcp__my_server_tools__echo");
        assertThat(entries.get(0).tool().name()).isEqualTo("mcp__my_server_tools__echo");
    }

    @Test
    @DisplayName("mcpInfoFromString 逆解析：规范化名 → 还原 server/tool")
    void roundTrip_parseNormalized() {
        String name = McpStringUtils.buildMcpToolName("my server.tools", "echo");
        McpStringUtils.McpInfo info = McpStringUtils.mcpInfoFromString(name);
        assertThat(info.serverName()).isEqualTo("my_server_tools");
        assertThat(info.toolName()).isEqualTo("echo");
    }

    /**
     * WHY（规则九 + 反射 F4）：McpServerService.stop / AgentMcpServers 原 {@code "mcp__"+rawName+"__"}
     * 原始名裸拼，对含空格/点/大写 server 名与注册的规范化名失配（工具删不掉/权限前缀匹配不到）。
     * 统一到 {@link McpStringUtils#getMcpPrefix} 后，stop 前缀过滤 / agent 工具注册名全链一致。
     */
    @Test
    @DisplayName("[F4] getMcpPrefix 规范化前缀：含空格/点 server 名可匹配注册名（stop/agent 统一）")
    void getMcpPrefix_normalizedForSpecialServerNames() {
        String normalizedPrefix = McpStringUtils.getMcpPrefix("my server.tools");
        assertThat(normalizedPrefix).isEqualTo("mcp__my_server_tools__");
        // 规范化前缀可匹配 buildMcpToolName 产出的完整工具名（McpServerService.stop 前缀过滤语义）
        assertThat(McpStringUtils.buildMcpToolName("my server.tools", "echo").startsWith(normalizedPrefix)).isTrue();
        // 旧裸拼前缀对含空格名失配（RED 证据：原实现 stop() 过滤不到注册名）
        assertThat("mcp__my server.tools__").isNotEqualTo(normalizedPrefix);
        // McpServerService.refreshMcpSkillCommands 已用 getMcpPrefix（:765），stop 统一后一致
        assertThat(McpStringUtils.getMcpPrefix("GitHub")).isEqualTo("mcp__GitHub__");
    }

    /** tools/list 返回 echo 工具（server 名含点/空格场景）。 */
    static class ToolReturningTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("tools");
            } else if ("tools/list".equals(method)) {
                result.putArray("tools").addObject()
                    .put("name", "echo")
                    .put("description", "echo")
                    .putObject("inputSchema").put("type", "object");
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
        public State getState() { return State.CONNECTED; }
    }
}
