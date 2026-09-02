package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.tool.impl.SyntheticOutputTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class R32B14_StructuredOutputTest {

    @Test
    void defaultToolResultHasNoStructuredOutput() {
        assertTrue(ToolResult.presentationMeta(ToolResult.success("toolu-1", "ok")).isEmpty());
    }

    @Test
    void successWithStructuredOutputCarriesPayload() {
        // WHY: [IMP-C2] structuredOutput 折入 data (对齐 CC mapToolResultToToolResultBlockParam
        //   content=data, 呈现字段在 data 内). successWithStructuredOutput 替代
        //   withStructuredOutput; 呈现载荷经 ToolResult.presentationMeta 从 data(Map) 读取.
        Map<String, Object> output = Map.of("answer", "yes", "score", 3);
        ToolResult<Map<String, Object>> result = ToolResult.successWithStructuredOutput("toolu-2", "ack", output);
        assertEquals("yes", ToolResult.presentationMeta(result).get("answer"));
        assertEquals(3, ToolResult.presentationMeta(result).get("score"));
        assertEquals("ack", result.data().get("summary"));
    }

    @Test
    void successWithStructuredOutputNullIsEmpty() {
        // WHY: null structuredOutput → 折入 data 仅含 summary 兜底键 (无呈现字段).
        ToolResult<Map<String, Object>> result = ToolResult.successWithStructuredOutput("toolu-3", "ack", null);
        assertTrue(ToolResult.presentationMeta(result).keySet().contains("summary"));
        assertEquals(1, ToolResult.presentationMeta(result).keySet().size(),
            "null 载荷 → data 不含额外呈现字段");
    }

    @Test
    void successWithStructuredOutputCombinedWithNewMessages() {
        // WHY: CC ToolResult<T> 同时承载 newMessages + data (Tool.ts:323/331).
        // 退役 ExtendedToolResult.withNewMessagesAndStructuredOutput 后, canonical 4 参 ctor 直接构造.
        ChatMessageDto extra = new ChatMessageDto("m", null, Role.user, "user", "extra",
            null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("ok", true);
        ToolResult<Map<String, Object>> result = new ToolResult<>(data, List.of(extra), null, null);
        assertEquals(1, result.newMessages().size());
        assertEquals(true, ToolResult.presentationMeta(result).get("ok"));
    }

    @Test
    void chatMessageSeventeenArgCompatibilityDefaultsStructuredOutputToNull() {
        ChatMessageDto message = new ChatMessageDto("m", null, Role.tool, "tool", "x",
            null, null, null, null, null, null, null, "call", null, null, List.of(), List.of());
        assertNull(message.structuredOutput());
    }

    @Test
    void chatMessageEighteenArgRetainsStructuredOutput() {
        ChatMessageDto message = new ChatMessageDto("m", null, Role.tool, "tool", "x",
            null, null, null, null, null, null, null, "call", null, null, List.of(), List.of(),
            Map.of("nested", Map.of("value", 1)));
        assertEquals(1, ((Map<?, ?>) message.structuredOutput().get("nested")).get("value"));
    }

    @Test
    void applierStoresStructuredOutputByToolUseId() {
        // WHY: ToolResultApplier (替代 ExtendedToolResultApplier) 把 structuredOutput 暂存到
        // AgentState.recordStructuredOutput (b14 本地暂存通道, 后续 provider 序列化).
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        ToolResult<Map<String, Object>> result = ToolResult.successWithStructuredOutput("toolu-5", "ack", Map.of("value", "kept"));
        ToolResult<?> applied = ToolResultApplier.apply(result, state.messages(), state, "toolu-5");
        assertEquals("ack", applied.data() instanceof Map<?, ?> m ? m.get("summary") : applied.data());
        assertEquals("kept", state.takeStructuredOutput("toolu-5").get("value"));
        assertTrue(state.takeStructuredOutput("toolu-5").isEmpty());
    }

    @Test
    void applierAppendsStructuredOutputAttachment() {
        // IT-6: CC toolExecution.ts:1272-1279 — 工具结果含 structured_output → executor 产出
        // createAttachmentMessage({type:'structured_output', data}) 独立附件消息进 transcript;
        // 该附件不进 LLM (CC normalizeAttachmentForAPI structured_output→[], messages.ts:4258-4261)。
        // 双通道并存: attachment 记录 + AgentState 载体暂存 (takeStructuredOutput 仍可取回)。
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        Map<String, Object> output = Map.of("answer", "yes", "score", 3);
        ToolResult<Map<String, Object>> result = ToolResult.successWithStructuredOutput("toolu-8", "ack", output);
        ToolResultApplier.apply(result, state.messages(), state, "toolu-8");

        List<AttachmentMessageDto> attachments = state.attachments();
        assertEquals(1, attachments.size());
        AttachmentMessageDto att = attachments.get(0);
        assertEquals("attachment", att.messageType());
        assertEquals("structured_output", att.type());
        // [IMP-C2] structuredOutput 折入 data → presentationMeta 含 summary 键 + 呈现字段
        Map<String, Object> attData = att.structuredData();
        assertEquals("ack", attData.get("summary"));
        assertEquals("yes", attData.get("answer"));
        assertEquals(3, attData.get("score"));
        assertEquals("Structured output provided", att.content());
        // 工厂归一: null/空载荷 → 空 Map
        assertTrue(AttachmentMessageDto.structuredOutput(null).structuredData().isEmpty());
        // 载体暂存仍可取回 (ExecAgentHook / LlmAgentLoop toolResultMessage 消费路径不变)
        assertEquals("yes", state.takeStructuredOutput("toolu-8").get("answer"));
    }

    @Test
    void queryConfigDefaultsToFiveRetries() {
        QueryConfig config = new QueryConfig("s", new QueryConfig.Gates(true, false, false, true));
        assertEquals(5, config.maxStructuredOutputRetries());
    }

    @Test
    void queryConfigParsesEnvironmentBoundaries() {
        assertEquals(5, QueryConfig.parseMaxStructuredOutputRetries(null));
        assertEquals(5, QueryConfig.parseMaxStructuredOutputRetries("not-a-number"));
        assertEquals(7, QueryConfig.parseMaxStructuredOutputRetries(" 7 "));
        assertEquals(0, QueryConfig.parseMaxStructuredOutputRetries("0"));
    }

    @Test
    void structuredOutputToolReturnsIndependentPayload() {
        SyntheticOutputTool tool = new SyntheticOutputTool();
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("answer", "yes");
        ToolResult<Map<String, Object>> result =
            (ToolResult<Map<String, Object>>) tool.execute(new ToolUseBlock("toolu-6", tool.name(), input));
        // A1 退役 ExtendedToolResult 后, SyntheticOutputTool 返回 ToolResult.successWithStructuredOutput
        assertInstanceOf(ToolResult.class, result);
        assertEquals("yes", ToolResult.presentationMeta(result).get("answer"));
        assertEquals("Structured output provided successfully", result.data().get("summary"));
    }
    @Test
    void structuredOutputToolRejectsSchemaMismatch() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putArray("required").add("answer");
        SyntheticOutputTool tool = new SyntheticOutputTool(schema);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        ToolResult<String> result = (ToolResult<String>) tool.execute(new ToolUseBlock("toolu-7", tool.name(), input));
        assertTrue(LlmAgentLoop.isToolErrorData(result.data()));
        assertTrue(result.data().contains("required"));
    }

    @Test
    void structuredOutputToolIsGatedForInteractiveContext() {
        SyntheticOutputTool tool = new SyntheticOutputTool();
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        ToolUseContext context = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(tool), "", AbortController.NOOP, List.of(), null,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT, Map.of(), false);
        ToolResult<String> result = (ToolResult<String>) tool.execute(new ToolUseBlock("toolu-8", tool.name(), input), context);
        assertTrue(LlmAgentLoop.isToolErrorData(result.data()));
        assertTrue(result.data().contains("non-interactive"));
    }

    @Test
    void structuredOutputToolNameMatchesCcProtocol() {
        assertEquals("StructuredOutput", SyntheticOutputTool.NAME);
        assertEquals("StructuredOutput", new SyntheticOutputTool().name());
    }
}
