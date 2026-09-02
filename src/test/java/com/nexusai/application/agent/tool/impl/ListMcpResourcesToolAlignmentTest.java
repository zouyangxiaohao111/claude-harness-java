package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2: ListMcpResourcesTool 对齐 CC 真名/schema/空结果/toAutoClassifierInput（RED→GREEN）。
 *
 * <p>对齐目标（grep 自验 CC 真源，不信注释）：
 * <ul>
 *   <li>name = 'ListMcpResourcesTool'（ListMcpResourcesTool/prompt.ts:1 LIST_MCP_RESOURCES_TOOL_NAME）</li>
 *   <li>toAutoClassifierInput = input.server ?? ''（ListMcpResourcesTool.ts:47-49）</li>
 *   <li>shouldDefer=true（:50）；maxResultSizeChars=100_000（:53）；isReadOnly/isConcurrencySafe=true（:41-46）</li>
 *   <li>空结果 → 'No resources found. MCP servers may still provide tools even if they have no resources.'
 *       （mapToolResultToToolResultBlockParam :108-116；Java mapToToolResultBlockParam 生产 dead → execute 注入）</li>
 *   <li>outputSchema 数组 {uri,name,mimeType?,description?,server}（:25-35）</li>
 * </ul>
 */
@DisplayName("D2 ListMcpResourcesTool 对齐 CC")
class ListMcpResourcesToolAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("name() = 'ListMcpResourcesTool'（CC LIST_MCP_RESOURCES_TOOL_NAME）")
    void name_matchesCCRealName() {
        assertThat(toolWith(new EmptyResourcesTransport()).name())
            .isEqualTo("ListMcpResourcesTool");
    }

    @Test
    @DisplayName("toAutoClassifierInput = server ?? ''（ListMcpResourcesTool.ts:47-49）")
    void toAutoClassifierInput_serverOrEmpty() {
        ListMcpResourcesTool tool = toolWith(new EmptyResourcesTransport());
        assertThat(tool.toAutoClassifierInput(MAPPER.createObjectNode().put("server", "filesystem")))
            .isEqualTo("filesystem");
        assertThat(tool.toAutoClassifierInput(MAPPER.createObjectNode())).isEmpty();
        assertThat(tool.toAutoClassifierInput(null)).isEmpty();
    }

    @Test
    @DisplayName("description() 对齐 CC DESCRIPTION 原文（含 usage examples，逐字）")
    void description_alignsCcDescription() {
        String desc = toolWith(new EmptyResourcesTransport()).description();
        // CC ListMcpResourcesTool/prompt.ts:2-9 DESCRIPTION
        assertThat(desc).startsWith("Lists available resources from configured MCP servers.\n")
            .contains("Each resource object includes a 'server' field indicating which server it's from.\n")
            .contains("Usage examples:\n")
            .contains("- List all resources from all servers: `listMcpResources`")
            .contains("- List resources from a specific server: `listMcpResources({ server: \"myserver\" })`");
    }

    @Test
    @DisplayName("shouldDefer=true / maxResultSizeChars=100_000 / isReadOnly / isConcurrencySafe")
    void staticFlags_alignCC() {
        ListMcpResourcesTool tool = toolWith(new EmptyResourcesTransport());
        assertThat(tool.shouldDefer(null)).isTrue();
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);
        assertThat(tool.isReadOnly(null)).isTrue();
        assertThat(tool.isConcurrencySafe(null)).isTrue();
        assertThat(tool.searchHint()).isEqualTo("list resources from connected MCP servers");
    }

    @Test
    @DisplayName("outputSchema 数组 {uri,name,mimeType?,description?,server}（:25-35）")
    void outputSchema_alignsCC() {
        JsonNode schema = toolWith(new EmptyResourcesTransport()).outputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("array");
        JsonNode itemProps = schema.path("items").path("properties");
        assertThat(itemProps.has("uri")).isTrue();
        assertThat(itemProps.has("name")).isTrue();
        assertThat(itemProps.has("mimeType")).isTrue();
        assertThat(itemProps.has("description")).isTrue();
        assertThat(itemProps.has("server")).isTrue();
    }

    @Test
    @DisplayName("空结果 → CC 提示语（mapToolResultToToolResultBlockParam :108-116）")
    void execute_emptyResults_returnsCcHintText() throws Exception {
        ListMcpResourcesTool tool = toolWith(new EmptyResourcesTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat((String) result.data()).isEqualTo(
            "No resources found. MCP servers may still provide tools even if they have no resources.");
    }

    @Test
    @DisplayName("非空结果 → 裸数组（:98-100 results.flat；[IMP-E1 △-3] 移除 data 包装）")
    void execute_nonEmpty_returnsBareArray() throws Exception {
        ListMcpResourcesTool tool = toolWith(new OneResourceTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // [IMP-E1 组 2-7 △-3] CC mapToolResultToToolResultBlockParam content = jsonStringify(content)
        //   → 模型看到裸数组 [{uri:...}]；旧实现 {"data":[...]} 与声明 array outputSchema 矛盾。
        JsonNode out = MAPPER.readTree((String) result.data());
        JsonNode arr = out; // 顶层即裸数组（无 data 包装）
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.get(0).path("uri").asText()).isEqualTo("mock://docs/readme");
        assertThat(arr.get(0).path("server").asText()).isEqualTo("gate");
    }

    @Test
    @DisplayName("指定不存在 server → error（ListMcpResourcesTool.ts:73-77）")
    void execute_targetServerNotFound_returnsError() throws Exception {
        ListMcpResourcesTool tool = toolWith(new EmptyResourcesTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"nope\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat((String) result.data()).contains("Server \"nope\" not found.");
    }

    // ── helpers ──

    private static ListMcpResourcesTool toolWith(McpTransport transport) {
        McpToolPool pool = new McpToolPool(new FakeFactory(transport), new ToolRegistry(), new JsonRpcMcpClient());
        // 装配 "gate" server → activeServers() 含 gate（否则所有调用都命中 server-not-found）
        pool.assembleToolPool("gate", null);
        return new ListMcpResourcesTool(pool);
    }

    private static ToolUseBlock call(String id, String inputJson) throws Exception {
        return new ToolUseBlock(id, "ListMcpResourcesTool", MAPPER.readTree(inputJson));
    }

    /** fake factory: 每次 create 返回指定 fake transport. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeFactory(McpTransport transport) {
            this.transport = transport;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /** 空资源 transport：capabilities.resources 开启，resources/list 返回空数组. */
    static class EmptyResourcesTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("resources");
            } else if ("resources/list".equals(method)) {
                result.putArray("resources");
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

    /** 单资源 transport：resources/list 返回 1 条（server 字段由 listResourcesFromJson 追加）. */
    static class OneResourceTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("resources");
            } else if ("resources/list".equals(method)) {
                result.putArray("resources").addObject()
                    .put("uri", "mock://docs/readme")
                    .put("name", "Mock Readme")
                    .put("mimeType", "text/plain");
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
