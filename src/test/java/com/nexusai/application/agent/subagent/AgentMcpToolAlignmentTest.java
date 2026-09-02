package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
// [冲突裁决·并集] 两 import 均被使用：LlmAgentLoop.isToolErrorData（:140/:151，master/HEAD 已有）、
//   McpAuthError（:179 新增 execute_returnsAuthErrorText 测试，subagent_v3 侧新增）→ 并集保留两行
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.mcp.McpAuthError;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5 [差异 1] AgentMcpTool annotations 映射 + execute timeout + 真实 description.
 *
 * <p>规则九 (测试验证意图而非行为): 意图是 —
 * <ul>
 *   <li>MCP server 在 tools/list 声明的 readOnlyHint / destructiveHint / openWorldHint
 *       必须映射到 Tool 的 isConcurrencySafe/isReadOnly/isDestructive/isOpenWorld
 *       (CC client.ts:1795-1808)。不写 = 并发退化: 本可并发的只读 MCP tool 被串行执行。</li>
 *   <li>MCP 调用不能永久阻塞 (CC client.ts:3091 Promise.race + getMcpToolTimeoutMs)。
 *       不写 = 挂死: MCP server 不响应时 agent 永久卡住。</li>
 *   <li>description 必须返真实 tool description (CC client.ts:1786-1788)，LLM 靠它做工具选择。</li>
 * </ul>
 */
@DisplayName("[S5] AgentMcpTool annotations/timeout/description 对齐 CC client.ts")
class AgentMcpToolAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 构造 annotations 对象: {"readOnlyHint":..,"destructiveHint":..,"openWorldHint":..}. */
    private static ObjectNode annotations(boolean readOnly, boolean destructive, boolean openWorld) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("readOnlyHint", readOnly);
        n.put("destructiveHint", destructive);
        n.put("openWorldHint", openWorld);
        return n;
    }

    private static ToolUseBlock call(String id, String inputJson) {
        try {
            JsonNode input = inputJson == null
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(inputJson);
            return new ToolUseBlock(id, "mcp__test__read", input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AgentMcpTool tool(JsonNode annotations, String description,
                                     McpTransport transport, long timeoutMs) {
        return new AgentMcpTool("test", "read", "mcp__test__read",
            MAPPER.createObjectNode(), annotations, null, description,
            channel(transport), timeoutMs, null);
    }

    /** fake channel: tools/call 委托给 fake transport（旧 8 参构造器的 transport 直持迁移）。 */
    private static AgentMcpServers.McpToolChannel channel(McpTransport transport) {
        return new AgentMcpServers.McpToolChannel() {
            @Override
            public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
                return transport.sendRequest("tools/call", Map.of("name", "read", "arguments", args));
            }
            @Override
            public void resetSession() {}
        };
    }

    @Test
    void isConcurrencySafe_mapsReadOnlyHint_whenAnnotationPresent() {
        // WHY: MCP server 声明 readOnlyHint=true 的 tool 是可并发只读 tool (CC client.ts:1795-1796)
        // 不映射 = 并发退化: 本可并发的只读 tool 被串行执行
        // 注: 4 个 annotation 方法忽略 input 参数 (S5-1 决策, CC 无参) → 传 null
        AgentMcpTool t = tool(annotations(true, false, false), "read", new FakeTransport(), 60_000);
        assertThat(t.isConcurrencySafe(null)).isTrue();
        assertThat(t.isReadOnly(null)).isTrue();
    }

    @Test
    void isConcurrencySafe_defaultsFalse_whenAnnotationAbsent() {
        // 反向: annotations 为 null → 默认 false (CC ?? false)
        AgentMcpTool t = tool(null, "read", new FakeTransport(), 60_000);
        assertThat(t.isConcurrencySafe(null)).isFalse();
        assertThat(t.isReadOnly(null)).isFalse();
    }

    @Test
    void isDestructive_mapsDestructiveHint() {
        // 边界: destructiveHint=true → isDestructive()=true (CC client.ts:1804-1805)
        AgentMcpTool t = tool(annotations(false, true, false), "read", new FakeTransport(), 60_000);
        assertThat(t.isDestructive(null)).isTrue();
    }

    @Test
    void isOpenWorld_mapsOpenWorldHint() {
        // 边界: openWorldHint=true → isOpenWorld()=true (CC client.ts:1807-1808)
        AgentMcpTool t = tool(annotations(false, false, true), "read", new FakeTransport(), 60_000);
        assertThat(t.isOpenWorld(null)).isTrue();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // RED-GREEN guard: 回退 orTimeout 时测试不会永久挂死
    void execute_timesOut_whenMcpServerHangs() {
        // WHY: MCP 调用不能永久阻塞 (CC client.ts:3091 Promise.race with timeout)
        // 不写 = 挂死: MCP server 不响应时 agent 永久卡住
        // transport.sendRequest 返永不完成的 future → execute 内部 orTimeout 触发后
        // 捕获 (Tool 契约: 错误不抛) 返 ToolResult.error, 且必须在 timeoutMs 量级返回 (不挂死)
        McpTransport hanging = new McpTransport() {
            @Override public void start(TransportConfig config) {}
            @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                return new CompletableFuture<>(); // 永不完成
            }
            @Override public void sendNotification(String method, Object params) {}
            @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
            @Override public void close() {}
            @Override public State getState() { return State.CONNECTED; }
        };
        AgentMcpTool t = tool(null, "read", hanging, 200);
        long start = System.nanoTime();
        var result = t.execute(call("t1", null));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // 200ms timeout → 必须在秒级返回错误 (不永久阻塞)
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("MCP 调用超时 → 结果 data 必须为错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isTrue();
        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void execute_succeeds_whenMcpServerRespondsInTime() {
        // 反向: 正常响应 → 不超时, 返回内容
        AgentMcpTool t = tool(null, "read", new FakeTransport(), 60_000);
        var result = t.execute(call("t2", null));
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("正常 MCP 响应 data 必须非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        assertThat(String.valueOf(result.data())).contains("hello");
    }

    @Test
    void maxResultSizeChars_returnsMcpLimitOf100k() {
        // WHY: CC MCPTool.ts:35 maxResultSizeChars = 100_000 —— MCP 工具结果可大于
        // Tool.java 接口 default 50_000 (Tool.java:422)。错用 default 会让大 MCP 结果被提前截断
        // 丢数据 (消费方 AgentLoopContext.java:1976 Math.min(maxResultSizeChars, Integer.MAX_VALUE))。
        // 防回退: 本断言锁死 MCP 轨 100_000, 若未来误删 override 会变红。
        AgentMcpTool t = tool(null, "read", new FakeTransport(), 60_000);
        assertThat(t.maxResultSizeChars()).isEqualTo(100_000L);
    }

    @Test
    void execute_returnsAuthErrorText_whenTransportThrowsMcpAuthError() {
        // WHY: transport 层 401 (OAuth token 过期/未授权) 抛 McpAuthError
        // (CC client.ts:3198-3204 'MCP server "${name}" requires re-authorization (token expired)')。
        // agent 路径必须把 authErr.getMessage() 作为 needs-auth 错误文本返给 LLM (对齐
        // McpServerTool:550-557)。不分类 = 认证错误被外层 catch 的 "MCP call failed:" 吞掉 →
        // LLM 无法区分认证失败与其它失败 (安全相关错误路径, 规则九)。
        String authMsg = "MCP server \"test\" requires re-authorization (token expired)";
        McpTransport authFail = new McpTransport() {
            @Override public void start(TransportConfig config) {}
            @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new McpAuthError("test", authMsg));
                return f;
            }
            @Override public void sendNotification(String method, Object params) {}
            @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
            @Override public void close() {}
            @Override public State getState() { return State.CONNECTED; }
        };
        AgentMcpTool t = tool(null, "read", authFail, 60_000);
        var result = t.execute(call("t-auth", null));
        // G16 错误分类: execute 返 ToolResult.error 且文本 == authErr.getMessage()
        // (future.join → CompletionException 解包 → instanceof McpAuthError 分支命中)
        // [冲突裁决·修复] 合入 master 后 ToolResult 已删 isError() 字段（IMP-C2：is_error 由执行器
        //   推导，AgentToolResult 无 isError 访问器）。错误路径证明改用 data 精确匹配 authMsg ——
        //   该文本仅 McpAuthError 分支产出（成功路径返 content JSON、通用 catch 前缀 "MCP call failed:"），
        //   isEqualTo(authMsg) 即等价于「isError=true 且 401 未被吞」。isToolErrorData 前缀表不含
        //   "MCP server"，不可用于本认证错误文本（与 McpServerToolCallTimeAuthTest 同为 baseline 口径）。
        assertThat(result.data()).isInstanceOf(String.class);
        assertThat(String.valueOf(result.data())).isEqualTo(authMsg);
    }

    @Test
    void execute_degradesMcpClientToNeedsAuth_whenTransportThrowsMcpAuthError() {
        // WHY: A-1 决策（WF-A-UN-3 状态面补齐）——agent 轨 MCP 401 只返错误文本、不更新
        // needs-auth 状态面 → /mcp 展示与连接状态通知不反映认证失败。CC toolExecution.ts:1599-1629
        // 在 McpAuthError 时经 toolUseContext.setAppState 把 appState.mcp.clients 中该 server
        // 条目降级为 needs-auth（条件：按 name 查不到 → no-op；type!=='connected' → no-op；
        // 否则重建 {name,type:'needs-auth',config}）。Java 共享执行层面
        // StreamingToolExecutor.degradeMcpClientToNeedsAuth（:3988-4047）仅在异常传播时触发，
        // agent 轨 G16 内部吞错 → 本类必须复用 ctx.setAppState() 同一通道补做降级。
        // 不写 = 子代理 MCP 401 后 /mcp 仍显示 connected，用户看不到"需要重新授权"。
        String authMsg = "MCP server \"test\" requires re-authorization (token expired)";
        McpTransport authFail = new McpTransport() {
            @Override public void start(TransportConfig config) {}
            @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new McpAuthError("test", authMsg));
                return f;
            }
            @Override public void sendNotification(String method, Object params) {}
            @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
            @Override public void close() {}
            @Override public State getState() { return State.CONNECTED; }
        };
        AgentMcpTool t = tool(null, "read", authFail, 60_000);

        // 构造带 setAppState 捕获的 ctx：appState.mcp.clients 含 connected 的 "test" server
        // （对齐 TaskToolsExpandedViewTest 的 setAppState 桥接注入形态）。
        AtomicReference<Map<String, Object>> state = new AtomicReference<>();
        Map<String, Object> mcp = new LinkedHashMap<>();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "test");
        client.put("type", "connected");
        client.put("config", Map.of("type", "http"));
        mcp.put("clients", List.of(client));
        state.set(new LinkedHashMap<>(Map.of("mcp", mcp)));

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
            prev -> Map.copyOf(state.get()),
            updater -> {
                Map<String, Object> next = updater.apply(Map.copyOf(state.get()));
                if (next != null) {
                    state.set(Map.copyOf(next));
                }
            },
            m -> {}, s -> {});

        var result = t.execute(call("t-auth-degrade", null), ctx);
        // 错误文本仍返 LLM（G16 错误面不变）
        assertThat(String.valueOf(result.data())).isEqualTo(authMsg);
        // 状态面已降级：appState.mcp.clients 中 "test" 条目 type 变为 needs-auth（重建 name/type/config）
        Object mcpAfter = state.get().get("mcp");
        assertThat(mcpAfter).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientsAfter =
            (List<Map<String, Object>>) ((Map<String, Object>) mcpAfter).get("clients");
        assertThat(clientsAfter).hasSize(1);
        assertThat(clientsAfter.get(0)).containsEntry("name", "test")
            .containsEntry("type", "needs-auth")
            .containsEntry("config", Map.of("type", "http"));
    }

    @Test
    void execute_keepsNeedsAuthNoop_whenServerNotInClients() {
        // WHY: A-1 降级三条件之一——按 serverName 在 appState.mcp.clients 中查不到 → 返回
        // prevState 不做任何修改（CC toolExecution.ts:1607-1609）。错降级会覆盖其它 server
        // 状态 / 凭空注入不存在的 client。断言：认证失败但 server 不在 clients 列表 → state 不变。
        String authMsg = "MCP server \"ghost\" requires re-authorization (token expired)";
        McpTransport authFail = new McpTransport() {
            @Override public void start(TransportConfig config) {}
            @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new McpAuthError("ghost", authMsg));
                return f;
            }
            @Override public void sendNotification(String method, Object params) {}
            @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
            @Override public void close() {}
            @Override public State getState() { return State.CONNECTED; }
        };
        AgentMcpTool t = tool(null, "read", authFail, 60_000);

        AtomicReference<Map<String, Object>> state = new AtomicReference<>();
        Map<String, Object> mcp = new LinkedHashMap<>();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "test"); // 只有 "test"，没有 "ghost"
        client.put("type", "connected");
        mcp.put("clients", List.of(client));
        state.set(new LinkedHashMap<>(Map.of("mcp", mcp)));

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
            prev -> Map.copyOf(state.get()),
            updater -> {
                Map<String, Object> next = updater.apply(Map.copyOf(state.get()));
                if (next != null) {
                    state.set(Map.copyOf(next));
                }
            },
            m -> {}, s -> {});

        var result = t.execute(call("t-auth-ghost", null), ctx);
        assertThat(String.valueOf(result.data())).isEqualTo(authMsg);
        // no-op：clients 列表仍是原始 1 条 connected "test"，未注入 "ghost"，未改 type
        Object mcpAfter = state.get().get("mcp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientsAfter =
            (List<Map<String, Object>>) ((Map<String, Object>) mcpAfter).get("clients");
        assertThat(clientsAfter).hasSize(1);
        assertThat(clientsAfter.get(0)).containsEntry("name", "test")
            .containsEntry("type", "connected");
    }

    @Test
    void execute_keepsNeedsAuthNoop_whenClientTypeNotConnected() {
        // WHY: A-1 降级三条件之二——已找到但 type!=='connected'（如 failed/pending）→ 返回
        // prevState 不覆盖其它状态（CC toolExecution.ts:1611-1614）。错覆盖会吞掉真实连接失败态。
        String authMsg = "MCP server \"test\" requires re-authorization (token expired)";
        McpTransport authFail = new McpTransport() {
            @Override public void start(TransportConfig config) {}
            @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new McpAuthError("test", authMsg));
                return f;
            }
            @Override public void sendNotification(String method, Object params) {}
            @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
            @Override public void close() {}
            @Override public State getState() { return State.CONNECTED; }
        };
        AgentMcpTool t = tool(null, "read", authFail, 60_000);

        AtomicReference<Map<String, Object>> state = new AtomicReference<>();
        Map<String, Object> mcp = new LinkedHashMap<>();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "test");
        client.put("type", "failed"); // 非 connected → 不覆盖
        client.put("config", Map.of("type", "http"));
        mcp.put("clients", List.of(client));
        state.set(new LinkedHashMap<>(Map.of("mcp", mcp)));

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
            prev -> Map.copyOf(state.get()),
            updater -> {
                Map<String, Object> next = updater.apply(Map.copyOf(state.get()));
                if (next != null) {
                    state.set(Map.copyOf(next));
                }
            },
            m -> {}, s -> {});

        var result = t.execute(call("t-auth-failed", null), ctx);
        assertThat(String.valueOf(result.data())).isEqualTo(authMsg);
        // no-op：type 仍为 failed，未被覆盖为 needs-auth
        Object mcpAfter = state.get().get("mcp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientsAfter =
            (List<Map<String, Object>>) ((Map<String, Object>) mcpAfter).get("clients");
        assertThat(clientsAfter).hasSize(1);
        assertThat(clientsAfter.get(0)).containsEntry("name", "test")
            .containsEntry("type", "failed");
    }

    @Test
    void description_returnsRealToolDescription() {
        // WHY: LLM 需真实 description 做工具选择 (CC client.ts:1786-1788), 非硬编码 "MCP tool X from server Y"
        AgentMcpTool t = tool(null, "Real tool description: read a file", new FakeTransport(), 60_000);
        assertThat(t.description()).isEqualTo("Real tool description: read a file");
        // prompt() 返回同样文本 (CC client.ts:1789-1793, 未超 2048 不截断)
        assertThat(t.prompt()).isEqualTo("Real tool description: read a file");
    }

    @Test
    void checkPermissions_passthroughWithWholeToolAllowSuggestion() {
        // WHY: AgentMcpTool 必须与 McpServerTool 同步自表态 passthrough（对齐 CC client.ts:1814-1829
        //      生产路径 per-tool 覆盖）。默认 Allow 会让 sub-agent 内 MCP 调用绕过第 3 层兜底 Ask，
        //      直接放行任意 MCP 调用——消除两工具 checkPermissions 语义分裂（复验整合版曾点名）。
        AgentMcpTool t = tool(null, "read", new FakeTransport(), 60_000);
        PermissionResult r = t.checkPermissions(null, null);

        assertThat(r).isInstanceOf(PermissionResult.Passthrough.class);
        PermissionResult.Passthrough p = (PermissionResult.Passthrough) r;
        assertThat(p.message()).isEqualTo("MCPTool requires permission.");
        assertThat(p.reason()).isNull(); // CC passthrough 变体无 reason 字段
        assertThat(p.suggestions()).hasSize(1);
        assertThat(p.suggestions().get(0)).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules add = (PermissionUpdate.AddRules) p.suggestions().get(0);
        assertThat(add.destination()).isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(add.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(add.rules()).hasSize(1);
        assertThat(add.rules().get(0).ruleValue())
            .isEqualTo(PermissionRuleValue.wholeTool("mcp__test__read"));
    }

    /** 正常响应的 fake transport: tools/call 返 {content:[{type:text,text:hello}], isError:false}. */
    static class FakeTransport implements McpTransport {
        @Override public void start(TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("isError", false);
            result.putObject("content").put("type", "text").put("text", "hello");
            return CompletableFuture.completedFuture(result);
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }
}
