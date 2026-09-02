package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2: ReadMcpResourceTool 真实现（RED→GREEN）· 对齐 CC {@code ReadMcpResourceTool.ts:75-158}。
 *
 * <p>对齐目标（grep 自验 CC 真源，不信注释）：
 * <ul>
 *   <li>name = 'ReadMcpResourceTool'（:60）；inputSchema {server,uri} 必填（:22-27）；outputSchema contents（:30-44）</li>
 *   <li>isConcurrencySafe/isReadOnly=true（:50-55）；toAutoClassifierInput = `${server} ${uri}`（:56-58）</li>
 *   <li>shouldDefer=true（:59）；maxResultSizeChars=100_000（:62）；searchHint（:61）</li>
 *   <li>3 throw（:78-92）：server not found / not connected / no resources capability</li>
 *   <li>resources/read（:95-101）→ text 直传 / blob base64 解码落盘 → blobSavedTo（:106-138）；persist 失败 → text 错误（:120-126）</li>
 *   <li>返回 {contents:[...]}（:141-143；IMP-E1 △-4 移除 data 包装）</li>
 * </ul>
 */
@DisplayName("D2 ReadMcpResourceTool 真实现")
class ReadMcpResourceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** [F2 rework] 落盘介质：@TempDir 目录（JUnit 自动清理）。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("name() = 'ReadMcpResourceTool'（CC 真名）")
    void name_matchesCC() {
        assertThat(tool().name()).isEqualTo("ReadMcpResourceTool");
    }

    @Test
    @DisplayName("inputSchema 必填 server+uri（ReadMcpResourceTool.ts:22-27）")
    void inputSchema_requiresServerAndUri() {
        JsonNode schema = tool().inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("server")).isTrue();
        assertThat(schema.path("properties").has("uri")).isTrue();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("server");
        assertThat(schema.path("required").get(1).asText()).isEqualTo("uri");
    }

    @Test
    @DisplayName("toAutoClassifierInput = `${server} ${uri}`（:56-58）")
    void toAutoClassifierInput_serverSpaceUri() {
        ReadMcpResourceTool tool = tool();
        assertThat(tool.toAutoClassifierInput(
            MAPPER.createObjectNode().put("server", "s1").put("uri", "u1"))).isEqualTo("s1 u1");
        assertThat(tool.toAutoClassifierInput(MAPPER.createObjectNode())).isEqualTo(" ");
    }

    @Test
    @DisplayName("静态标志：isConcurrencySafe/isReadOnly/shouldDefer/maxResultSizeChars/searchHint")
    void staticFlags_alignCC() {
        ReadMcpResourceTool tool = tool();
        assertThat(tool.isConcurrencySafe(null)).isTrue();
        assertThat(tool.isReadOnly(null)).isTrue();
        assertThat(tool.shouldDefer(null)).isTrue();
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);
        assertThat(tool.searchHint()).isEqualTo("read a specific MCP resource by URI");
    }

    @Test
    @DisplayName("server not found → error（:80-84）")
    void execute_serverNotFound_returnsError() throws Exception {
        ReadMcpResourceTool tool = tool();
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"ghost\",\"uri\":\"u\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat((String) result.data()).startsWith("Server \"ghost\" not found.");
    }

    @Test
    @DisplayName("缺 server / 缺 uri → error（inputSchema 必填校验）")
    void execute_missingFields_returnsError() throws Exception {
        // WHY（mcp-align impl-I-4 T7 场景）: inputSchema {server,uri} 均必填 —— 缺失任一直接
        // error（ReadMcpResourceTool: missing 'server' / 'uri'），不进入 resources/read
        // （对齐 CC z.object({server, uri}) 必填校验）
        ReadMcpResourceTool tool = tool();
        ToolResult<?> noServer = (ToolResult<?>) tool.execute(call("t1", "{\"uri\":\"u\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(noServer.data())).isTrue();
        assertThat((String) noServer.data()).startsWith("ReadMcpResourceTool: missing 'server'");
        ToolResult<?> noUri = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"s\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(noUri.data())).isTrue();
        assertThat((String) noUri.data()).startsWith("ReadMcpResourceTool: missing 'uri'");
    }

    @Test
    @DisplayName("无 resources 能力 → error（:90-92）")
    void execute_noResourceCapability_returnsError() throws Exception {
        ReadMcpResourceTool tool = toolWith(new NoResourceCapabilityTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"gate\",\"uri\":\"u\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat((String) result.data()).isEqualTo("Server \"gate\" does not support resources");
    }

    @Test
    @DisplayName("server 存在但未连接（isServerConnected=false）→ error（:86-88 client.type !== 'connected'）")
    void execute_serverNotConnected_returnsError() throws Exception {
        ReadMcpResourceTool tool = toolWith(new NotConnectedTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"gate\",\"uri\":\"u\"}"));
        // 前置门控 3/3：装配成功（activeServers 含 gate，过 server-not-found）→
        // isServerConnected(gate)=false（transport.getState()==NOT_CONNECTED）→ not-connected 错误
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat((String) result.data()).isEqualTo("Server \"gate\" is not connected");
    }

    @Test
    @DisplayName("resources/read 协议失败 → error（CC :95-101 无 try/catch → 抛错，非空结果）")
    void execute_readProtocolFailure_returnsError() throws Exception {
        ReadMcpResourceTool tool = toolWith(new ReadFailureTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"gate\",\"uri\":\"mock://x\"}"));
        // 对比 fetchResources（client.ts:2021-2027 catch → [] fail-soft）：resources/read 无 fail-soft，
        // 协议失败抛错 → Java 端 Tool.execute catch → ToolResult.error（行为等价）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat((String) result.data()).startsWith("ReadMcpResourceTool:")
            .contains("read failed");
    }

    @Test
    @DisplayName("text 内容直传 → {contents:[{uri,mimeType,text}]}（:108-110；IMP-E1 △-4 移除 data 包装）")
    void execute_textContent_returnsContents() throws Exception {
        ReadMcpResourceTool tool = toolWith(new TextResourceTransport());
        ToolResult<?> result = (ToolResult<?>) tool.execute(call("t1", "{\"server\":\"gate\",\"uri\":\"mock://docs/readme\"}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = MAPPER.readTree((String) result.data());
        JsonNode first = out.path("contents").get(0);
        assertThat(first.path("uri").asText()).isEqualTo("mock://docs/readme");
        assertThat(first.path("mimeType").asText()).isEqualTo("text/plain");
        assertThat(first.path("text").asText()).isEqualTo("hello resource");
        assertThat(first.has("blobSavedTo")).isFalse();
    }

    @Test
    @DisplayName("blob base64 解码落盘 → blobSavedTo + 保存提示（:114-137）；文件真实落盘")
    void execute_blobContent_persistsToDisk() throws Exception {
        byte[] png = new byte[]{1, 2, 3, 4, 5};
        String base64 = Base64.getEncoder().encodeToString(png);
        ReadMcpResourceTool tool = toolWith(new BlobResourceTransport(base64, "image/png"));
        // [F2 rework] G30⑮ 删除 tmpdir 回退后，execute(call) → ctx=null → resolveToolResultsDir(null)
        // 返回 null → 'missing persistence context' 不落盘。生产 StreamingToolExecutor 恒传 ctx
        // （effectiveCwd+sessionId）→ 测试对齐生产轨传递含落盘上下文的 ToolUseContext（指向临时目录）。
        ToolUseContext ctx = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), java.util.List.of(), "", new AbortController(), java.util.List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", tempDir, null, null, null);
        ToolResult<?> result = (ToolResult<?>) tool.execute(
            call("t1", "{\"server\":\"gate\",\"uri\":\"mock://img/1\"}"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = MAPPER.readTree((String) result.data());
        JsonNode first = out.path("contents").get(0);
        String savedTo = first.path("blobSavedTo").asText();
        assertThat(savedTo).isNotBlank();
        assertThat(savedTo).endsWith(".png");
        assertThat(first.path("text").asText()).contains("Binary content (image/png,")
            .contains("saved to " + savedTo);
        // 真实落盘验证（临时目录介质）
        Path saved = Path.of(savedTo);
        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.readAllBytes(saved)).containsExactly(png);
    }

    // ── helpers ──

    private static ReadMcpResourceTool tool() {
        return toolWith(new TextResourceTransport());
    }

    private static ReadMcpResourceTool toolWith(McpTransport transport) {
        McpToolPool pool = new McpToolPool(new FakeFactory(transport), new ToolRegistry(), new JsonRpcMcpClient());
        // 装配 "gate" server → activeServers() 含 gate（否则 server-not-found 命中在前）
        pool.assembleToolPool("gate", null);
        return new ReadMcpResourceTool(pool);
    }

    private static ToolUseBlock call(String id, String inputJson) throws Exception {
        return new ToolUseBlock(id, "ReadMcpResourceTool", MAPPER.readTree(inputJson));
    }

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

    /** 未连接 transport：装配成功（initialize/tools/list 正常）但 getState()=NOT_CONNECTED
     *  → isServerConnected(gate)=false，命中 not-connected 前置门控（CC :86-88）. */
    static class NotConnectedTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities"); // 空 capabilities 足够装配成功
            } else if ("tools/list".equals(method)) {
                result.putArray("tools");
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
        public State getState() { return State.NOT_CONNECTED; }
    }

    /** 协议失败 transport：resources/read 请求失败（CC :95-101 无 try/catch → 抛错，非 fail-soft）. */
    static class ReadFailureTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("resources");
            } else if ("resources/read".equals(method)) {
                return CompletableFuture.failedFuture(new RuntimeException("read failed"));
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

    /** 无 resources 能力 transport：initialize 返回空 capabilities（server 仍装配成功）. */
    static class NoResourceCapabilityTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities"); // 空 capabilities → resources()=false
            } else if ("tools/list".equals(method)) {
                result.putArray("tools");
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

    /** text 资源 transport：capabilities.resources 开启，resources/read 返回 text 内容. */
    static class TextResourceTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("resources");
            } else if ("resources/read".equals(method)) {
                result.putArray("contents").addObject()
                    .put("uri", "mock://docs/readme")
                    .put("mimeType", "text/plain")
                    .put("text", "hello resource");
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

    /** blob 资源 transport：resources/read 返回 blob（base64 string）. */
    static class BlobResourceTransport implements McpTransport {
        private final String base64;
        private final String mimeType;

        BlobResourceTransport(String base64, String mimeType) {
            this.base64 = base64;
            this.mimeType = mimeType;
        }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities").putObject("resources");
            } else if ("resources/read".equals(method)) {
                result.putArray("contents").addObject()
                    .put("uri", "mock://img/1")
                    .put("mimeType", mimeType)
                    .put("blob", base64);
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
