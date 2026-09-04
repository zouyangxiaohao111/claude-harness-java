package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolParent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-toolcalls-400 C] 工具 newMessages 先于 tool_result 写入 state.messages 的回归测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: Read pdf pages 等工具返回 {@code newMessages}
 * （isMeta image user 消息，20 image block）时，若 {@code ToolResultApplier.apply} 在工具执行
 * dispatch 期就 {@code state.messages().addAll(tr.newMessages())}（早于 handleToolCallsTurn step 3
 * 才 append 的 tool_result），state.messages 顺序变成
 * {@code [assistant(tool_calls), user(isMeta 页图), tool(tool_result)]} → provider 原序透传 →
 * assistant tool_calls 后夹 image user 消息 → Anthropic 400 "assistant message with tool_calls must
 * be followed by tool messages"。
 *
 * <p><b>对齐 CC</b>: toolExecution.ts:1478 addToolResult（产出含 tool_result 的 user 消息）先 /
 * :1566-1570 push result.newMessages（页图消息）后 —— 顺序必须为
 * {@code assistant(tool_calls) → tool(tool_result) → user(newMessages)}。
 *
 * <p><b>变异点</b>: 删掉 handleToolCallsTurn step 3 的 {@code flushNewMessagesAfterToolResult} 恢复
 * 旧「dispatch 期 addAll」→ 本测试红（isMeta 消息出现在 tool_result 之前）。
 *
 * <p><b>包选择</b>: 放 {@code com.nexusai.application.agent}（与 AgentState 同包）以便调用包私有
 * {@code AgentState.incrementTurn()} 启动 turn（handleToolCallsTurn 末尾构造
 * {@code AgentTurnCompletedEvent(state, turnCount,...)} 要求 turnCount &gt;= 1）。
 */
@DisplayName("[fix-toolcalls-400 C] tool_result 先于该工具 newMessages 落盘（CC toolExecution.ts:1478/1566 顺序）")
class ToolResultNewMessagesOrderingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 携带 isMeta image user 消息的工具（模拟 Read pdf pages → newMessages 页图）。 */
    private static Tool imageReturningTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "reads pdf pages and returns page images as newMessages"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                ObjectNode img = JSON.createObjectNode();
                img.put("type", "image");
                img.put("media_type", "image/png");
                img.put("data", "iVBORw0KGgo="); // 示意 base64
                ChatMessageDto pageImages = new ChatMessageDto(
                    UUID.randomUUID().toString(), null, Role.user, "system",
                    null, null, null, null, null, null,
                    "刚刚", OffsetDateTime.now(), null, null, null,
                    List.of(img), List.of(), null,
                    true, false, null, null, false, null, null, null);
                return ToolResult.successWithNewMessages(call.id(), "Read 2 pages", List.of(pageImages));
            }
            @Override public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };
    }

    @Test
    @DisplayName("单工具带 newMessages：state.messages = assistant(tool_calls) → tool(tool_result) → user(isMeta 页图)")
    void newMessages_flushAfterSameToolToolResult() {
        // ── 1. 上下文：ctx + per-turn TUC（availableTools 非空 → buildStreamingExecutor 真实构建）──
        Tool pdfTool = imageReturningTool("Read");
        ToolRegistry registry = new ToolRegistry();
        registry.register(pdfTool);
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);
        AgentState state = new AgentState("sys", "sess-ord-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.incrementTurn();
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), "sess-ord")
            .withAvailableTools(List.of(pdfTool));
        String turnAssistantId = UUID.randomUUID().toString();

        // ── 2. msg：1 个 tool_call（Read）──
        ObjectNode callInput = JSON.createObjectNode().put("file_path", "/tmp/sample.pdf").put("pages", "1-2");
        ToolUseBlock call = new ToolUseBlock("call_pdf_1", "Read", callInput);
        AssistantMessage msg = new AssistantMessage("I'll read the pdf pages", "tool_calls", List.of(call));

        // ── 3. 真实 executor：add call（dispatch 期 ToolResultApplier.apply 会把 newMessages 暂存，不立即 addAll）──
        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId, null, true, null, null);
        assertThat(exec).as("availableTools 非空 → 必须构建出真实 executor").isNotNull();
        exec.add(call, ToolParent.of(turnAssistantId), null);

        // ── 4. 调 handleToolCallsTurn（streaming path）──
        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll read the pdf pages", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).as("工具轮返回 continue 让外层 loop 继续").isEqualTo("continue");

        // ── 5. 断言顺序：assistant(tool_calls) → tool(tool_result) → user(isMeta newMessages) ──
        List<ChatMessageDto> msgs = state.messages();
        // 意图锚 5a：倒数第 3 条 = 含 toolCalls 的 assistant（Read）
        ChatMessageDto assistant = msgs.get(msgs.size() - 3);
        assertThat(assistant.role()).as("倒数第 3 条必须是 assistant").isEqualTo(Role.assistant);
        assertThat(assistant.toolCalls()).as("assistant 携带 Read tool_call").hasSize(1);
        assertThat(assistant.toolCalls().get(0).id()).isEqualTo("call_pdf_1");
        // 意图锚 5b：倒数第 2 条 = role=tool 的 tool_result（tool_result 必须先于 newMessages 落盘）
        ChatMessageDto toolResult = msgs.get(msgs.size() - 2);
        assertThat(toolResult.role()).as("倒数第 2 条必须是 tool(tool_result)").isEqualTo(Role.tool);
        assertThat(toolResult.toolCallId()).as("tool_result 配对 Read tool_use").isEqualTo("call_pdf_1");
        // 意图锚 5c：末条 = user isMeta 页图 newMessages（flush 在 tool_result 之后）
        ChatMessageDto pageImages = msgs.get(msgs.size() - 1);
        assertThat(pageImages.role()).as("末条必须是 user（isMeta 页图 newMessages）").isEqualTo(Role.user);
        assertThat(pageImages.isMeta()).as("页图 newMessages 是 isMeta user 消息").isTrue();

        // 意图锚 5d（变异点锁定）：从 assistant(tool_calls) 到第一条 tool_result 之间不得夹任何 user 消息。
        // 若在 dispatch 期提前 addAll → 页图 user 消息出现在 tool_result 之前 → 本断言红。
        int assistantIdx = -1;
        int firstToolResultIdx = -1;
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessageDto m = msgs.get(i);
            if (m.role() == Role.assistant && m.toolCalls() != null && !m.toolCalls().isEmpty() && assistantIdx < 0) {
                assistantIdx = i;
            }
            if (m.role() == Role.tool && m.toolCallId() != null && firstToolResultIdx < 0) {
                firstToolResultIdx = i;
            }
        }
        assertThat(assistantIdx).as("必须找到含 tool_calls 的 assistant").isGreaterThanOrEqualTo(0);
        assertThat(firstToolResultIdx).as("必须找到 tool_result").isGreaterThanOrEqualTo(0);
        for (int i = assistantIdx + 1; i < firstToolResultIdx; i++) {
            assertThat(msgs.get(i).role())
                .as("assistant(tool_calls) 与首个 tool_result 之间不得夹非 tool 消息（否则 Anthropic 400）")
                .isEqualTo(Role.tool);
        }
    }

    @Test
    @DisplayName("无 newMessages 的工具：行为不变，只有 assistant + tool_result，无多余 user 消息")
    void toolWithoutNewMessages_unaffected() {
        Tool plainTool = TestContexts.dummyTool("Grep");
        ToolRegistry registry = new ToolRegistry();
        registry.register(plainTool);
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);
        AgentState state = new AgentState("sys", "sess-ord2", null);
        state.incrementTurn();
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), "sess-ord2")
            .withAvailableTools(List.of(plainTool));
        String turnAssistantId = UUID.randomUUID().toString();

        ObjectNode callInput = JSON.createObjectNode().put("pattern", "TODO");
        ToolUseBlock call = new ToolUseBlock("call_grep_1", "Grep", callInput);
        AssistantMessage msg = new AssistantMessage("I'll grep", "tool_calls", List.of(call));

        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId, null, true, null, null);
        assertThat(exec).isNotNull();
        exec.add(call, ToolParent.of(turnAssistantId), null);

        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll grep", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).isEqualTo("continue");

        List<ChatMessageDto> msgs = state.messages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).role()).isEqualTo(Role.assistant);
        assertThat(msgs.get(1).role()).isEqualTo(Role.tool);
        assertThat(msgs.get(1).toolCallId()).isEqualTo("call_grep_1");
    }
}
