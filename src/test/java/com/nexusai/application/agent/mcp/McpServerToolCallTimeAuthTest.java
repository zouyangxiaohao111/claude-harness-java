package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S03 R2-03 △-7] 调用期 401 恢复测试 · 对齐 CC client.ts:3194-3208（callMCPTool catch
 * errorCode===401 → throw new McpAuthError）+ toolExecution.ts:1601-1629（McpAuthError →
 * appState mcp.clients needs-auth 降级）。
 *
 * <p><b>WHY（意图验证）</b>: 旧路径（D-S03-1）把 transport 401 经 {@code catch(Exception)}
 * 吞成 {@code 'MCP call failed: '} 普通 error 文本——不标记 needs-auth 缓存、不更新连接状态
 * 注册表、不产伪工具，token 过期后模型永远看不到 {@code mcp__&lt;server&gt;__authenticate}
 * 触发重授权（I-4 违反）。本测试用真装配 {@link McpToolPool}（fake transport 调用期抛
 * {@link McpAuthError}）断言三件事：
 * <ol>
 *   <li>execute 返回 isError 结果且消息 = CC 原文（`MCP server "srv" requires re-authorization
 *       (token expired)`，client.ts:3206）——不再静默吞为普通 error</li>
 *   <li>{@link McpNeedsAuthCache#isCached} = true（15min TTL 标记，client.ts:293-309）</li>
 *   <li>auth 工具替换回调收到 {@code mcp__srv__authenticate} 伪工具条目（与连接期同一收敛
 *       路径/同一 handler，client.ts:2318）+ 连接状态注册表 needs-auth（toolExecution.ts:1616-1620）</li>
 * </ol>
 */
class McpServerToolCallTimeAuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 调用期 401 fake transport：initialize/tools/list 正常，tools/call 抛 McpAuthError。 */
    static class CallTimeAuthTransport implements McpTransport {
        private final String serverName;

        CallTimeAuthTransport(String serverName) {
            this.serverName = serverName == null ? "srv" : serverName;
        }

        @Override
        public void start(McpTransport.TransportConfig config) {
            // no-op：已连接
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            switch (method) {
                case "initialize" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.putObject("serverInfo").put("name", serverName).put("version", "1.0.0");
                    result.putObject("capabilities");
                    result.put("protocolVersion", "2024-11-05");
                    f.complete(result);
                }
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.putArray("tools").addObject()
                        .put("name", "do")
                        .put("description", "stub tool")
                        .set("inputSchema", MAPPER.createObjectNode());
                    f.complete(result);
                }
                case "tools/call" -> {
                    // CC client.ts:3194-3208：401 → throw new McpAuthError(name,
                    // `MCP server "${name}" requires re-authorization (token expired)`)
                    f.completeExceptionally(new McpAuthError(serverName,
                        "MCP server \"" + serverName + "\" requires re-authorization (token expired)"));
                }
                default -> f.complete(MAPPER.createObjectNode());
            }
            return f;
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() {
            return State.CONNECTED;
        }
    }

    static class CallTimeAuthFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new CallTimeAuthTransport(config == null ? null : config.serverName());
        }
    }

    @Test
    @DisplayName("[S03 R2-03] 调用期 401 → execute 返回 CC 原文错误 + needs-auth 缓存标记 + 伪工具替换（与连接期同一收敛路径）")
    void callTime401_marksNeedsAuth_andSwapsAuthTool() throws Exception {
        // 真装配 McpToolPool（非 stub tool）：fake transport 调用期抛 McpAuthError
        McpToolPool pool = new McpToolPool(new CallTimeAuthFactory(), new ToolRegistry(),
            new JsonRpcMcpClient());
        // 注入独立 needs-auth 缓存实例供断言（Spring 未注入 → pool 惰性自建）
        McpNeedsAuthCache cache = new McpNeedsAuthCache();
        org.springframework.test.util.ReflectionTestUtils.setField(pool, "needsAuthCache", cache);
        // 记录伪工具替换回调（生产 = McpServerService.replaceServerToolsAfterAuth）
        AtomicReference<List<McpToolPool.McpToolEntry>> swapped = new AtomicReference<>(List.of());
        AtomicReference<String> swappedServer = new AtomicReference<>();
        pool.setMcpAuthToolSwapHandler((name, entries) -> {
            swappedServer.set(name);
            swapped.set(entries);
        });
        // 装配（connect + initialize + tools/list）→ activeTransports/serverConfigs 就绪
        List<McpToolPool.McpToolEntry> assembled = pool.assembleToolPool("srv",
            new McpTransport.TransportConfig("http://srv.example", List.of(), Map.of(),
                null, "srv", "http"));
        assertThat(assembled).extracting(McpToolPool.McpToolEntry::mcpToolName)
            .contains("mcp__srv__do");

        // 工具执行层等价物：wrapMcpTool 产物（McpServerTool），走真实 execute
        McpServerTool tool = (McpServerTool) assembled.stream()
            .filter(e -> "mcp__srv__do".equals(e.mcpToolName()))
            .findFirst().orElseThrow().tool();
        AgentToolResult<?> result = tool.execute(new ToolUseBlock("use-401",
            "mcp__srv__do", MAPPER.createObjectNode()), null);

        // ① 错误结果 + CC 原文消息（旧路径「MCP call failed: 」吞 401 已删除）。
        // [IMP-C2] ToolResult 已删 isError 字段；401 错误文本「requires re-authorization」不被
        // isToolErrorData 前缀表识别（非注册错误前缀），错误结果由 CC 原文消息内容唯一证明
        // （正常路径不会产出该文本），故删除 isError 断言、保留数据内容断言承载意图。
        assertThat(result.data()).asString()
            .contains("requires re-authorization (token expired)");
        assertThat(result.data()).asString().contains("srv");

        // ② needs-auth 缓存标记（15min TTL，CC setMcpAuthCacheEntry client.ts:293-309）
        assertThat(cache.isCached("srv"))
            .as("调用期 401 必须置 needs-auth 缓存（批连接跳过重试窗口）").isTrue();

        // ③ 伪工具替换（CC toolExecution.ts:1601-1629 needs-auth 降级 + client.ts:2318 伪工具）
        assertThat(swappedServer.get()).as("swap handler 必须收到 server 名").isEqualTo("srv");
        assertThat(swapped.get()).isNotEmpty();
        assertThat(swapped.get()).extracting(McpToolPool.McpToolEntry::mcpToolName)
            .as("伪工具 = mcp__srv__authenticate（模型可见可触发 OAuth）")
            .contains("mcp__srv__authenticate");

        // ④ 连接状态注册表 needs-auth（CC appState mcp.clients type='needs-auth' 等价物）
        assertThat(pool.connectivityStatusRegistry().typeOf("srv"))
            .as("连接状态注册表必须降级 needs-auth").isEqualTo("needs-auth");
    }

    @Test
    @DisplayName("[S03 R2-03] 非认证异常仍走 generic error 文本（不标记 needs-auth、不产伪工具）")
    void nonAuthFailure_keepsGenericErrorText() throws Exception {
        McpToolPool pool = new McpToolPool(new McpTransportFactory() {
            @Override
            public McpTransport create(McpTransport.TransportConfig config) {
                return new CallTimeAuthTransport(config == null ? null : config.serverName()) {
                    @Override
                    public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
                        if ("tools/call".equals(method)) {
                            CompletableFuture<JsonNode> f = new CompletableFuture<>();
                            f.completeExceptionally(new IllegalStateException("boom"));
                            return f;
                        }
                        return super.sendRequest(method, params);
                    }
                };
            }
        }, new ToolRegistry(), new JsonRpcMcpClient());
        McpNeedsAuthCache cache = new McpNeedsAuthCache();
        org.springframework.test.util.ReflectionTestUtils.setField(pool, "needsAuthCache", cache);
        AtomicReference<List<McpToolPool.McpToolEntry>> swapped = new AtomicReference<>(List.of());
        pool.setMcpAuthToolSwapHandler((name, entries) -> swapped.set(entries));

        List<McpToolPool.McpToolEntry> assembled = pool.assembleToolPool("srv",
            new McpTransport.TransportConfig("http://srv.example", List.of(), Map.of(),
                null, "srv", "http"));
        McpServerTool tool = (McpServerTool) assembled.stream()
            .filter(e -> "mcp__srv__do".equals(e.mcpToolName()))
            .findFirst().orElseThrow().tool();

        AgentToolResult<?> result = tool.execute(new ToolUseBlock("use-500",
            "mcp__srv__do", MAPPER.createObjectNode()), null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        // IMP-C2 后 isError 由执行器推导，不存于 ToolResult；data 承载 "MCP call failed: " 前缀
        // 即该结果落在非认证 generic error 路径（401 专属消息不含此前缀，二者可判别）。
        assertThat(result.data()).asString().contains("MCP call failed");
        assertThat(cache.isCached("srv")).as("非认证错误不得标记 needs-auth").isFalse();
        assertThat(swapped.get()).as("非认证错误不得产伪工具").isEmpty();
        assertThat(pool.connectivityStatusRegistry().typeOf("srv"))
            .as("非认证错误不降级 needs-auth").isNotEqualTo("needs-auth");
    }

}
