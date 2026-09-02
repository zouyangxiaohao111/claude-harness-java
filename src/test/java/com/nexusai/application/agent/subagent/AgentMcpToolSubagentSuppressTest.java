package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-31] AgentMcpTool subagent 上下文抑制 mcpMeta · 对齐 CC toolExecution.ts:1464/1727
 * ({@code mcpMeta: toolUseContext.agentId ? undefined : mcpMeta}).
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: CC subagent 上下文（agentId 非空）不透传
 * mcpMeta 到 user message（SDK 消费者才读）。Java agentId 恒非空（compact ctor 默认 UUID）不可用，
 * 判别改用 {@code ctx.agentType() != null}（主链 base TUC agentType=null / 子代理 TUC 恒设置）。
 * 旧实现全量透传（residual concern AgentMcpTool.java:181-183）→ subagent 的 mcpMeta 泄漏到
 * user message，违反 CC 抑制语义。本测试锁：
 * <ol>
 *   <li>ctx.agentType()='builder'（subagent）→ execute(call,ctx) 返回的 ToolResult.mcpMeta()=null
 *       （即使 result._meta 非空）</li>
 *   <li>ctx.agentType()=null（主链）→ mcpMeta()=非 null（透传保留）</li>
 *   <li>ctx=null → 走单参 execute，mcpMeta 透传（主链/非 ctx 调用方保留）</li>
 * </ol>
 */
@DisplayName("[MCP-I-9 Q-31] AgentMcpTool subagent mcpMeta 抑制（agentType 判别）")
class AgentMcpToolSubagentSuppressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** fake transport: tools/call 返回带 _meta 的 result（SDK 消费者读 _meta）. */
    static class MetaTransport implements McpTransport {
        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode r = MAPPER.createObjectNode();
            if ("tools/call".equals(method)) {
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

    private static AgentMcpTool tool() {
        return new AgentMcpTool("server", "tool", "mcp__server__tool",
            null, null, null, "desc",
            new AgentMcpServers.McpToolChannel() {
                @Override
                public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
                    return new MetaTransport().sendRequest("tools/call", Map.of());
                }
                @Override
                public void resetSession() {}
            },
            60_000, null);
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
        // 不能落到 user message（否则 subagent 结果污染父上下文）。
        AgentMcpTool t = tool();
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
        AgentMcpTool t = tool();
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
        AgentMcpTool t = tool();
        AgentToolResult<?> raw = t.execute(call(), null);

        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(result.mcpMeta()).isNotNull();
    }
}
