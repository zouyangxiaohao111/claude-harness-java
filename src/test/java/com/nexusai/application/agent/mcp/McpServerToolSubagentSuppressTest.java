package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-31 返工 R5] McpServerTool（生产轨 MCP 工具池包装器）subagent 上下文抑制 mcpMeta ·
 * 对齐 CC toolExecution.ts:1464/1727
 * ({@code mcpMeta: toolUseContext.agentId ? undefined : mcpMeta}).
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: McpServerTool 是生产 MCP 工具池包装器
 * （McpToolPool:1009 {@code new McpServerTool(...)}），ToolRegistry 2 参派发
 * {@code tool.execute(call, ctx)} 在 subagent loop 内对生产 MCP 工具同样生效 —
 * 该抑制在生产子代理路径是活跃的。CC subagent 上下文（agentId 非空）不透传 mcpMeta 到
 * user message（SDK 消费者才读）；Java agentId 恒非空（compact ctor 默认 UUID）不可用，
 * 判别改用 {@code ctx.agentType() != null}（主链 base TUC agentType=null / 子代理 TUC 恒设置），
 * 与 {@code AgentMcpTool}（frontmatter 轨）同基准。本测试锁生产轨抑制（镜像
 * {@code AgentMcpToolSubagentSuppressTest}，同构三断言）：
 * <ol>
 *   <li>ctx.agentType()='builder'（subagent）→ execute(call,ctx) 返回的 ToolResult.mcpMeta()=null
 *       （即使 result._meta 非空）</li>
 *   <li>ctx.agentType()=null（主链）→ mcpMeta()=非 null（透传保留）</li>
 *   <li>ctx=null → 走单参 execute（委托 2 参 + ctx=null），mcpMeta 透传（主链/非 ctx 调用方保留）</li>
 * </ol>
 *
 * <p>生产可达性：{@code McpToolPool.assembleToolPool} 建连注册 transport → {@code pool.callTool}
 * 走 activeTransports → 本测试用 {@link #MetaTransport} + {@link #FakeFactory} 走真实
 * assembleToolPool 路径构造 McpServerTool（非反射桩）。
 */
@DisplayName("[MCP-I-9 Q-31 R5] McpServerTool subagent mcpMeta 抑制（agentType 判别，生产轨镜像）")
class McpServerToolSubagentSuppressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * fake transport：initialize/tools/list 支撑 assembleToolPool 建连；tools/call 返回带 _meta
     * 的 result（SDK 消费者读 _meta）。镜像 AgentMcpToolSubagentSuppressTest.MetaTransport。
     */
    static class MetaTransport implements McpTransport {
        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode r = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                r.putObject("serverInfo").put("name", "meta-test-server");
            } else if ("tools/list".equals(method)) {
                r.putArray("tools");
            } else if ("tools/call".equals(method)) {
                r.putArray("content").addObject().put("type", "text").put("text", "result text");
                r.put("isError", false);
                // _meta 非空 — subagent 上下文必须抑制；主链保留
                r.putObject("_meta").put("sessionId", "s-123");
            }
            return CompletableFuture.completedFuture(r);
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() {}
        @Override public State getState() { return State.CONNECTED; }
    }

    /** fake factory: 每次 create 返回指定 fake transport（镜像 McpResourcesListTest.FakeFactory）. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;
        FakeFactory(McpTransport transport) { this.transport = transport; }
        @Override
        public McpTransport create(McpTransport.TransportConfig config) { return transport; }
    }

    /** 经真实 assembleToolPool 路径构造生产轨 McpServerTool（McpToolPool:1009 包装同构）. */
    private static McpServerTool tool() {
        MetaTransport transport = new MetaTransport();
        McpToolPool pool = new McpToolPool(new FakeFactory(transport), new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool("server", null);   // 注册 transport 进 activeTransports → pool.callTool 可达
        return new McpServerTool("server", "tool", "mcp__server__tool", null, null, null, null, null, pool);
    }

    private static ToolUseBlock call() {
        return new ToolUseBlock("t1", "mcp__server__tool", MAPPER.createObjectNode());
    }

    /** 构造一个带 agentType 的 TUC · 复用 ToolUseContext.of 便捷工厂（最小字段）. */
    private static ToolUseContext ctxWithAgentType(String agentType) {
        ToolUseContext base = ToolUseContext.of(java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        // of() 便捷工厂无 agentType 参数 — 用 SubagentContextOverrides 经 with() 注入
        return base.with(new ToolUseContext.SubagentContextOverrides(
            null, agentType, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("subagent ctx (agentType='builder') → mcpMeta 抑制为 null（即使 _meta 非空）")
    void subagentContext_suppressesMcpMeta() {
        // WHY: CC toolExecution.ts:1464 subagent 上下文 mcpMeta=undefined — _meta 只能给 SDK 消费者,
        // 不能落到 user message（否则 subagent 结果污染父上下文）。生产轨包装器与 frontmatter 轨同基准。
        McpServerTool t = tool();
        AgentToolResult<?> raw = t.execute(call(), ctxWithAgentType("builder"));

        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(result.mcpMeta())
            .as("subagent 上下文必须抑制 mcpMeta（_meta 不落到 user message）")
            .isNull();
    }

    @Test
    @DisplayName("主链 ctx (agentType=null) → mcpMeta 透传保留")
    void mainChainContext_passesThroughMcpMeta() {
        // 反向: 主链 base TUC agentType=null → 全量透传（CC toolExecution.ts:1464 主链保留 mcpMeta）
        McpServerTool t = tool();
        AgentToolResult<?> raw = t.execute(call(), ctxWithAgentType(null));

        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(result.mcpMeta())
            .as("主链（agentType=null）必须透传 mcpMeta（SDK 消费者读 _meta）")
            .isNotNull();
        assertThat(result.mcpMeta().meta())
            .containsKey("sessionId");
    }

    @Test
    @DisplayName("ctx=null → 走单参 execute，mcpMeta 透传")
    void nullContext_delegatesToSingleArg_passesMcpMeta() {
        // ctx=null 是非 ctx 调用方（如 ToolRegistry 2 参派发兜底）→ 单参 execute 保持透传
        // （McpServerTool.execute(call) 委托 execute(call, null)，suppress 判定恒 false）
        McpServerTool t = tool();
        AgentToolResult<?> raw = t.execute(call(), null);

        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(result.mcpMeta())
            .as("ctx=null 走单参 execute，mcpMeta 必须透传（非 ctx 调用方保留）")
            .isNotNull();
        assertThat(result.mcpMeta().meta())
            .containsKey("sessionId");
    }
}
