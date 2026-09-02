package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.ToolParent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-toolcalls-400 B] handleToolCallsTurn 配对防御测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 根因 1.1 —— 空参工具（arguments:""）在流式回调里
 * {@code isComplete()} 恒 false → 永不 add 进 executor → {@code msg.toolCalls()} 有 N 个而执行器只产
 * S&lt;N 个结果。原实现 :1747 静默 {@code break} → state.messages() 变 [assistant(N calls), tool(S results)]
 * → OpenAI 400 "insufficient tool messages following tool_calls message"。本测试锁定配对防御：执行器
 * 结果数 &lt; 调用数时，为每个未覆盖 tool_call 生成 synthetic error tool_result（对齐 CC
 * yieldMissingToolResultBlocks query.ts:123-149），保证每个 tool_call 都有 tool 响应。
 *
 * <p><b>变异点</b>: 删掉 synthetic 分支回到 {@code break} → call#2 无 tool 响应 → 本测试红
 * （正是 400 根因）。
 *
 * <p><b>包选择</b>: 放 {@code com.nexusai.application.agent}（与 AgentState 同包）以便调用包私有
 * {@code AgentState.incrementTurn()} 启动 turn（handleToolCallsTurn 末尾构造
 * {@code AgentTurnCompletedEvent(state, turnCount,...)} 要求 turnCount &gt;= 1）。
 */
@DisplayName("[fix-toolcalls-400 B] handleToolCallsTurn 配对防御（缺失工具结果 → synthetic error）")
class HandleToolCallsTurnPairingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("混合批 [带参 Bash + 空参 Bash] 只执行 1 个 → 未覆盖 tool_call 补 synthetic error tool_result")
    void missingToolResult_getsSyntheticErrorForEachUncoveredCall() {
        // ── 1. 上下文：ctx + per-turn TUC（availableTools 非空 → buildStreamingExecutor 真实构建）──
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);
        AgentState state = new AgentState("sys", "sess-fixb", null);
        state.incrementTurn(); // LlmAgentLoop.queryLoop:2870 每轮进入前 incrementTurn（turnCount>=1 必需）
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), "sess-fixb")
            .withAvailableTools(List.of(TestContexts.dummyTool("Bash")));
        String turnAssistantId = UUID.randomUUID().toString();

        // ── 2. msg：2 个 tool_calls（call#1 带参 / call#2 空参）──
        ObjectNode call1Input = JSON.createObjectNode().put("command", "ls");
        ToolUseBlock call1 = new ToolUseBlock("call_pair_1", "Bash", call1Input);
        ToolUseBlock call2 = new ToolUseBlock("call_pair_2", "Bash", JSON.createObjectNode()); // 空参工具
        AssistantMessage msg = new AssistantMessage("I'll run tools", "tool_calls",
            List.of(call1, call2));

        // ── 3. 真实 executor：只 add call#1（模拟空参工具未被流式回调 add 的缺失场景，根因 1.1）──
        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId,
            null, true, null, null);
        assertThat(exec).as("availableTools 非空 → 必须构建出真实 executor").isNotNull();
        exec.add(call1, ToolParent.of(turnAssistantId), null);

        // ── 4. 调 handleToolCallsTurn（streaming path：exec.size()==1 > 0 → runTools 只产 1 result）──
        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll run tools", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).as("工具轮返回 continue 让外层 loop 继续").isEqualTo("continue");

        // ── 5. 断言：assistant(2 calls) + call#1 真结果 + call#2 synthetic error ──
        // 5a. assistant 消息保留 2 个 toolCalls
        List<ChatMessageDto> assistantMsgs = state.messages().stream()
            .filter(m -> m.role() == Role.assistant && m.toolCalls() != null)
            .toList();
        assertThat(assistantMsgs).as("必须有一条含 toolCalls 的 assistant").hasSize(1);
        assertThat(assistantMsgs.get(0).toolCalls()).as("assistant 保留全部 N 个 tool_calls").hasSize(2);
        // 5b. call#1 有真实 tool 响应
        List<ChatMessageDto> tr1 = state.messages().stream()
            .filter(m -> "call_pair_1".equals(m.toolCallId()))
            .toList();
        assertThat(tr1).as("call#1 必须有真实 tool 响应").hasSize(1);
        assertThat(tr1.get(0).isError()).as("call#1 成功结果 isError=false").isFalse();
        // 5c. call#2 补 synthetic error
        List<ChatMessageDto> tr2 = state.messages().stream()
            .filter(m -> "call_pair_2".equals(m.toolCallId()))
            .toList();
        assertThat(tr2).as("call#2 必须补 synthetic error tool_result").hasSize(1);
        assertThat(tr2.get(0).content()).isEqualTo("Tool result missing");
        assertThat(tr2.get(0).isError()).as("synthetic error isError=true").isTrue();
        assertThat(tr2.get(0).assistantMessageId())
            .as("synthetic assistantMessageId = CC sourceToolAssistantUUID 等价位")
            .isEqualTo(turnAssistantId);
        // 5d. 意图锚：每个 tool_call id 都有 tool 响应（配对完整性 → 注入历史合法 → 不再 400）
        java.util.Set<String> toolResultIds = state.messages().stream()
            .filter(m -> m.toolCallId() != null)
            .map(ChatMessageDto::toolCallId)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(toolResultIds).as("每个 tool_use 都必须有 tool_result 配对").contains("call_pair_1", "call_pair_2");
    }
}
