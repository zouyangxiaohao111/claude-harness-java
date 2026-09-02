package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpAuthError;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H P2-5] McpAuthError 客户端降级测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1601-1629}
 * （McpAuthError catch → appState mcp.clients needs-auth 降级）+ CC
 * {@code mcp/client.ts:152-159}（McpAuthError 类）+ {@code :3194-3208}（401 抛射条件）。
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: H 为 {@code StreamingToolExecutor.executeAsync}
 * catch(Throwable) 新增 McpAuthError 分支，按 CC 三条件更新 appState：
 * <ol>
 *   <li>prev.mcp.clients 按 name 查不到 → 返回 prev（no-op）</li>
 *   <li>找到但 type !== 'connected' → 返回 prev（no-op）</li>
 *   <li>否则替换为 {name, type:'needs-auth', config: existing.config}（config 保留）</li>
 * </ol>
 * 且分支<b>不阻断</b>既有 ToolResult.error 流程。
 *
 * <p><b>行为验证路径</b>: 注册 execute 抛 McpAuthError 的 stub tool → add + getRemainingResults
 * → 断言 appState 更新 + 错误结果产出。appState 是 Java 端 {@code Map<String,Object>} 函数式
 * 更新（LlmAgentLoop.setAppState 同款语义）。
 *
 * @see StreamingToolExecutor
 * @see McpAuthError
 * @since Session H P2-5
 */
class McpAuthDegradationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** execute 抛 McpAuthError 的 stub tool. */
    static final class AuthFailTool implements Tool {
        @Override public String name() { return "mcp__my-server__do"; }
        @Override public String description() { return "stub mcp tool"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            throw new McpAuthError("my-server",
                "MCP server \"my-server\" requires re-authorization (token expired)");
        }
    }

    /** execute 抛普通异常的 stub tool (对照: 非 McpAuthError 不得降级). */
    static final class PlainFailTool implements Tool {
        @Override public String name() { return "plain-fail"; }
        @Override public String description() { return "stub plain tool"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            throw new IllegalStateException("boom");
        }
    }

    private record Driver(ToolRegistry registry, ToolUseContext ctx,
                          AtomicReference<Map<String, Object>> appState,
                          StreamingToolExecutor exec) {}

    /** 组装 executor + 记录 appState 更新的 ctx (setAppState 函数式更新, LlmAgentLoop 同款). */
    private static Driver driver(Tool tool, Map<String, Object> initialState) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AtomicReference<Map<String, Object>> appState = new AtomicReference<>(initialState);
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null,
            ev -> {},  // onCompactProgress (canonical ctor 位置 17)
            s -> Map.copyOf(appState.get()),  // getAppState
            updater -> appState.set(updater.apply(appState.get())),  // setAppState
            null, null, null, null, null, null, null, null, null, null, null,
            null, false, null, null, null, null, null, false, false, null, null, null, null, null, null);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        return new Driver(registry, ctx, appState, exec);
    }

    private static Map<String, Object> connectedClientsState(String serverName, String type, Map<String, Object> config) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", serverName);
        client.put("type", type);
        client.put("config", config);
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("clients", new ArrayList<>(List.of(client)));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mcp", mcp);
        return state;
    }

    private static List<ToolResult> runAndCollect(Driver d, String toolName) {
        d.exec().add(new ToolUseBlock("call-1", toolName, JSON.createObjectNode()));
        return d.exec().getRemainingResults();
    }

    // ─────────────────────── 1. 主路径: 降级 needs-auth + config 保留 ───────────────────────

    @Test
    @DisplayName("McpAuthError → appState mcp.clients 对应 server 变 needs-auth 且 config 保留")
    void mcpAuthError_degradesToNeedsAuth_configPreserved() {
        Map<String, Object> config = Map.of("url", "http://localhost:9999/mcp");
        Driver d = driver(new AuthFailTool(), connectedClientsState("my-server", "connected", config));

        List<ToolResult> results = runAndCollect(d, "mcp__my-server__do");

        // 1. 错误结果仍产出 (分支不阻断既有 ToolResult.error 流程)
        assertThat(results).hasSize(1);
        assertThat(d.exec().getResultErrorFlags().get("call-1"))
            .as("McpAuthError 必须仍产出 error result（IMP-C2 后 isError 由执行器推导）")
            .isTrue();

        // 2. appState 更新: my-server → needs-auth, config 保留
        Object mcpObj = d.appState().get().get("mcp");
        assertThat(mcpObj).as("appState 必须含 mcp key").isInstanceOf(Map.class);
        Object clientsObj = ((Map<?, ?>) mcpObj).get("clients");
        assertThat(clientsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clients = (List<Map<String, Object>>) (List<?>) clientsObj;
        assertThat(clients).hasSize(1);
        Map<String, Object> updated = clients.get(0);
        assertThat(updated.get("name")).isEqualTo("my-server");
        assertThat(updated.get("type")).as("connected → needs-auth 降级").isEqualTo("needs-auth");
        assertThat(updated.get("config")).as("config 必须保留 (CC toolExecution.ts:1619)").isEqualTo(config);
    }

    @Test
    @DisplayName("条件3: 按 CC 重建 {name, type, config} 三字段, 丢弃 client 其余字段")
    void mcpAuthError_degradationRebuildsThreeFields_dropsExtras() {
        // CC toolExecution.ts:1616-1620 是显式重建对象 {name, type:'needs-auth', config},
        // 不是"保留全部字段再覆盖" — client 携带的额外字段 (如 status/custom) 必须被丢弃.
        Map<String, Object> config = Map.of("url", "http://localhost:9999/mcp");
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "my-server");
        client.put("type", "connected");
        client.put("config", config);
        client.put("status", "ready");
        client.put("custom", "extra-field");
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("clients", new ArrayList<>(List.of(client)));
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("mcp", mcp);
        Driver d = driver(new AuthFailTool(), initialState);

        runAndCollect(d, "mcp__my-server__do");

        @SuppressWarnings("unchecked")
        Map<String, Object> updated = ((List<Map<String, Object>>)
            ((Map<?, ?>) d.appState().get().get("mcp")).get("clients")).get(0);
        assertThat(updated).as("重建对象仅含 name/type/config (CC toolExecution.ts:1616-1620)")
            .containsOnlyKeys("name", "type", "config");
        assertThat(updated.get("name")).isEqualTo("my-server");
        assertThat(updated.get("type")).isEqualTo("needs-auth");
        assertThat(updated.get("config")).isEqualTo(config);
    }

    // ─────────────────────── 2. 未注册 server → no-op ───────────────────────

    @Test
    @DisplayName("appState 无对应 server → 返回 prev (no-op, CC toolExecution.ts:1607-1609)")
    void mcpAuthError_unregisteredServer_noOp() {
        Map<String, Object> initialState = connectedClientsState("other-server", "connected", Map.of());
        Driver d = driver(new AuthFailTool(), initialState);

        List<ToolResult> results = runAndCollect(d, "mcp__my-server__do");

        assertThat(results).hasSize(1);
        assertThat(d.exec().getResultErrorFlags().get("call-1")).isTrue();
        // appState 未被改写 (my-server 不在 clients 中)
        assertThat(d.appState().get()).isEqualTo(initialState);
    }

    // ─────────────────────── 3. type != connected → no-op ───────────────────────

    @Test
    @DisplayName("找到但 type!=connected → 返回 prev (no-op, CC toolExecution.ts:1611-1614)")
    void mcpAuthError_notConnectedType_noOp() {
        Map<String, Object> initialState = connectedClientsState("my-server", "failed", Map.of());
        Driver d = driver(new AuthFailTool(), initialState);

        List<ToolResult> results = runAndCollect(d, "mcp__my-server__do");

        assertThat(results).hasSize(1);
        assertThat(d.exec().getResultErrorFlags().get("call-1")).isTrue();
        // failed 状态不得被覆盖 (CC: only update if client was connected)
        assertThat(d.appState().get()).isEqualTo(initialState);
    }

    // ─────────────────────── 4. 非 McpAuthError → 不降级 ───────────────────────

    @Test
    @DisplayName("普通异常 → 不触发降级 (对照, instanceof McpAuthError 是唯一入口)")
    void plainError_noDegradation() {
        Map<String, Object> initialState = connectedClientsState("my-server", "connected", Map.of());
        Driver d = driver(new PlainFailTool(), initialState);

        List<ToolResult> results = runAndCollect(d, "plain-fail");

        assertThat(results).hasSize(1);
        assertThat(d.exec().getResultErrorFlags().get("call-1")).isTrue();
        assertThat(d.appState().get()).isEqualTo(initialState);
    }

    // ─────────────────────── 5. appState 无 mcp key → 惰性创建 ───────────────────────

    @Test
    @DisplayName("appState 无 'mcp' key → 惰性创建空 clients (CC 结构), 三条件后仍 no-op")
    void mcpAuthError_noMcpKey_lazyCreates() {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("todos", Map.of());
        Driver d = driver(new AuthFailTool(), initialState);

        List<ToolResult> results = runAndCollect(d, "mcp__my-server__do");

        assertThat(results).hasSize(1);
        assertThat(d.exec().getResultErrorFlags().get("call-1")).isTrue();
        // 无 mcp key → 惰性按 CC 结构读取空 clients → findIndex=-1 → 返回 prev (CC 三条件 no-op)
        assertThat(d.appState().get().get("mcp"))
            .as("无 mcp key 时三条件后仍返回 prev (CC toolExecution.ts:1607-1609)")
            .isNull();
        // 其他 key (todos) 不受影响
        assertThat(d.appState().get().get("todos")).isEqualTo(Map.of());
    }
}
