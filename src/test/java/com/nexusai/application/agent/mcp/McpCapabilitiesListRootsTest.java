package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [S02 X-9] initialize capabilities 声明 {roots:{},elicitation:{}} + roots/list handler ·
 * 对齐 CC client.ts:994-1001（空对象声明 capability——{form:{},url:{}} 会破坏 Java MCP SDK
 * server）+ client.ts:1009-1018（ListRootsRequestSchema → {roots:[{uri:file://cwd}]}）。
 *
 * <p><b>WHY（规则九）</b>：旧 initialize 送 {@code capabilities: Map.of()}（X-9/D-8 偏离），
 * server→client roots/list 请求无 handler 回 -32601（server 声明 roots 能力时客户端不响应
 * 属协议违约）。本测试锁定：
 * <ol>
 *   <li>initialize 请求体 capabilities = {roots:{},elicitation:{}}（CC 声明语义）</li>
 *   <li>server→client roots/list 请求 → {roots:[{uri:"file://"+cwd}]} 响应（stdio 路径，
 *       cwd = config.cwd() ?? CwdResolution.getOriginalCwdLayer()，对齐 CC client.ts:1014 getOriginalCwd）</li>
 *   <li>未知 server→client 请求维持 -32601（不悬挂）</li>
 * </ol>
 */
@DisplayName("[S02 X-9] initialize capabilities + roots/list handler")
class McpCapabilitiesListRootsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "caps-srv";

    /**
     * 隔离 CwdResolution 依赖的会话级 cwd 载体（SessionProjectRoot / RequestContext MDC）。
     * <p>WHY：roots/list 兜底现经统一入口 CwdResolution.getOriginalCwdLayer()（读 SessionProjectRoot.getForSession
     * + RequestContext.sessionId()），若上一测试残留会话绑定/MDC 会污染本类其他 roots/list 测试
     * （如 {@link #rootsListRequest_respondsWithFileUri} 期望回落 user.dir）。每方法前后清空保证确定性。
     */
    @BeforeEach
    void isolateCwdState() {
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    @AfterEach
    void clearCwdState() {
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    // ═══════════════ 1. initialize capabilities 声明 ═══════════════

    @Test
    @DisplayName("initialize 请求体 capabilities = {roots:{},elicitation:{}}（CC client.ts:994-1001）")
    void initialize_declaresRootsAndElicitationCapabilities() throws Exception {
        AtomicReference<Object> capturedParams = new AtomicReference<>();
        McpToolPool pool = new McpToolPool(new McpTransportFactory() {
            @Override
            public McpTransport create(McpTransport.TransportConfig config) {
                return new CaptureTransport(capturedParams);
            }
        }, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool(SERVER,
            new McpTransport.TransportConfig("http://caps-svc:3000", List.of(), Map.of(), null, SERVER, "http"));

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) capturedParams.get();
        assertThat(params).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) params.get("capabilities");
        assertThat(capabilities).as("initialize 必须声明 capabilities（X-9/D-8 旧 Map.of() 偏离修复）")
            .containsKeys("roots", "elicitation");
        assertThat(capabilities.get("roots")).as("roots 为空对象声明（CC 语义，非 null）").isEqualTo(Map.of());
        assertThat(capabilities.get("elicitation")).as("elicitation 为空对象声明（Spring AI 兼容，"
                + "CC 注释明言 {form:{},url:{}} 会破坏 Java MCP SDK server）")
            .isEqualTo(Map.of());
    }

    // ═══════════════ 2. roots/list server→client 请求响应（stdio 路径） ═══════════════

    @Test
    @DisplayName("roots/list 请求 → {roots:[{uri:file://user.dir}]} 响应（config.cwd 缺省 → user.dir）")
    void rootsListRequest_respondsWithFileUri() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = stdioTransportWith(out);

        transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"roots/list\",\"params\":{}}");

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("id").asInt()).isEqualTo(7);
        JsonNode roots = frame.path("result").path("roots");
        assertThat(roots).as("roots/list 必须回传 roots 数组").hasSize(1);
        String uri = roots.get(0).path("uri").asText();
        assertThat(uri).as("root uri = file:// + cwd（CC getOriginalCwd 语义）")
            .isEqualTo("file://" + System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("roots/list 请求 → config.cwd 优先（server 可配沙箱 cwd 时更合理，concerns 登记）")
    void rootsListRequest_prefersConfigCwd() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String cwd = System.getProperty("java.io.tmpdir");
        // start() 保存 config（含 cwd）→ 换假 Process 捕获回传帧（绕过真实子进程）
        StdioMcpTransport transport = new StdioMcpTransport();
        java.nio.file.Path javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java");
        transport.start(new McpTransport.TransportConfig(
            javaBin.toString(),
            List.of("-cp", System.getProperty("java.class.path"),
                StdioTestChildMain.class.getName(), "sleep"),
            Map.of(), cwd, "srv", "stdio"));
        Process fake = mock(Process.class);
        when(fake.getOutputStream()).thenReturn(out);
        transport.attachProcessForTesting(fake);

        transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"roots/list\",\"params\":{}}");

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.path("result").path("roots").get(0).path("uri").asText())
            .as("config.cwd() 优先于 user.dir（受控语义，concerns 登记）")
            .isEqualTo("file://" + cwd);
        transport.close();
    }

    @Test
    @DisplayName("roots/list 兜底经 CwdResolution.getOriginalCwdLayer——会话绑定项目根优先于 user.dir（DEL-07，CC client.ts:1014 getOriginalCwd）")
    void rootsListRequest_fallbackRoutesThroughCwdResolution() throws Exception {
        // WHY（规则九）：旧实现 roots/list 兜底直读 System.getProperty("user.dir")，同一 JVM 内所有会话
        // roots 恒指向进程启动目录，与会话绑定的项目根脱钩——CC roots/list handler（client.ts:1009-1018）
        // 用 STATE.originalCwd（会话项目根，非进程 cwd）。本测试锁定兜底走统一入口
        // CwdResolution.getOriginalCwdLayer()：config.cwd() 缺省且会话已绑定 projectRoot 时，roots 返回
        // 该绑定项目根（而非 user.dir）。若有人把兜底改回直读 user.dir，本测试即红。
        String sid = "mcp-roots-cwd-" + System.nanoTime();
        java.nio.file.Path boundRoot = Files.createTempDirectory("mcp-roots-bound");
        SessionProjectRoot.setForSession(sid, boundRoot.toString());
        RequestContext.setSession(sid);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StdioMcpTransport transport = stdioTransportWith(out); // config=null → 走兜底

            transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"roots/list\",\"params\":{}}");

            JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
            String uri = frame.path("result").path("roots").get(0).path("uri").asText();
            String expected = "file://" + CwdResolution.normalizeCwd(boundRoot.toString());
            assertThat(uri)
                .as("兜底经 CwdResolution.getOriginalCwdLayer：返回会话绑定 projectRoot，非直读 user.dir")
                .isEqualTo(expected);
            assertThat(uri).as("不得直读 user.dir（DEL-07 移除直读）")
                .isNotEqualTo("file://" + System.getProperty("user.dir"));
        } finally {
            SessionProjectRoot.clearSession(sid);
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("未知 server→client 请求维持 -32601（不悬挂）")
    void unknownRequest_returnsMethodNotFound() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdioMcpTransport transport = stdioTransportWith(out);

        transport.handleLine("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"sampling/createMessage\",\"params\":{}}");

        JsonNode frame = MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(frame.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    /** 挂接假 Process 的 stdio transport（绕过 start，config 为 null）。 */
    private static StdioMcpTransport stdioTransportWith(OutputStream out) {
        Process fake = mock(Process.class);
        when(fake.getOutputStream()).thenReturn(out);
        StdioMcpTransport transport = new StdioMcpTransport();
        transport.attachProcessForTesting(fake);
        return transport;
    }

    // ═══════════════ fake ═══════════════

    /** 捕获 initialize params 的假 transport（其余请求空能力）。 */
    static class CaptureTransport implements McpTransport {
        private final AtomicReference<Object> capturedParams;
        private final java.util.concurrent.atomic.AtomicReference<State> state =
            new java.util.concurrent.atomic.AtomicReference<>(State.NOT_CONNECTED);

        CaptureTransport(AtomicReference<Object> capturedParams) {
            this.capturedParams = capturedParams;
        }

        @Override
        public void start(TransportConfig config) {
            state.set(State.CONNECTED);
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                capturedParams.set(params);
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake");
                r.putObject("capabilities");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putArray("tools");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("unexpected " + method));
        }

        @Override
        public void sendNotification(String method, Object params) {
        }

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {
        }

        @Override
        public void close() {
            state.set(State.CLOSED);
        }

        @Override
        public State getState() {
            return state.get();
        }
    }
}
