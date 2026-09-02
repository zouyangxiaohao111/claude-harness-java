package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HR-08 R1/R2] 结构化输出 enablement 前置阻断闭环测试。
 *
 * <p>WHY (规则九 · 测试验证意图): IMP-HR-08 §8.1 的启用前置阻断 R1（schema 专用 SyntheticOutputTool
 * 未暴露主循环 LLM → enforcement 恒不可满足）+ R2（MAX_STRUCTURED_OUTPUT_RETRIES 安全阀未接线，
 * maxTurns 默认 null=无限 → STOP 全 blocking 重入挂起/无限循环）在本 settle 中解决。本测试锁定：
 * <ul>
 *   <li><b>R1</b>: {@link LlmAgentLoop#appendStructuredOutputToolToSchema} 把 schema 专用
 *       SyntheticOutputTool（含 jsonSchema）追加到主循环 LLM tools schema（对齐 CC
 *       main.tsx:1885-1891 过滤后追加）；无效 schema → 不追加（对齐 CC main.tsx:1897-1901
 *       tengu_structured_output_failure）。</li>
 *   <li><b>R2</b>: {@link LlmAgentLoop#countStructuredOutputCalls} 逐 assistant message 计
 *       StructuredOutput 调用（镜像 CC messages.ts:4691-4707 countToolCalls）；
 *       {@code callsThisQuery = currentCalls - initialStructuredOutputCalls}，超过
 *       {@link LlmAgentLoop#maxStructuredOutputRetries} 上限 → 终止（CC QueryEngine.ts:1005-1035）。</li>
 * </ul>
 */
@DisplayName("[IMP-HR-08 R1/R2] 结构化输出 enablement 前置阻断")
class StructuredOutputSafetyValveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SO = ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME;

    // ════════════════════════════════════════════════════════════════════════
    // R1 · schema 专用 SyntheticOutputTool 暴露给主循环 LLM
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R1: jsonSchema → LLM tools schema 追加 StructuredOutput 工具（含 jsonSchema 参数，CC main.tsx:1885-1891）")
    void appendStructuredOutputToolToSchema_exposesToolWithSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("answer").put("type", "string");
        ArrayNode tools = JSON.createArrayNode();

        LlmAgentLoop.appendStructuredOutputToolToSchema(tools, schema);

        // 追加后 tools 数组含一个 function 条目，name=StructuredOutput，parameters 携带 jsonSchema。
        assertThat(tools.size()).isEqualTo(1);
        String name = tools.get(0).path("function").path("name").asText();
        assertThat(name).as("R1 暴露 StructuredOutput 给主循环 LLM（CC main.tsx:1886）").isEqualTo(SO);
        assertThat(tools.get(0).path("function").path("parameters").path("properties").path("answer").path("type").asText())
            .as("schema 专用实例 inputJSONSchema 透传到 parameters（CC buildSyntheticOutputTool inputJSONSchema）")
            .isEqualTo("string");
    }

    @Test
    @DisplayName("R1: 无效 jsonSchema → 不追加（CC main.tsx:1897-1901 tengu_structured_output_failure）")
    void appendStructuredOutputToolToSchema_invalidSchema_noAppend() {
        // 非 object schema（数组）→ SyntheticOutputTool 构造抛 IllegalArgumentException → 内部 catch，不追加。
        ArrayNode tools = JSON.createArrayNode();
        ArrayNode badSchema = JSON.createArrayNode().add(1);

        LlmAgentLoop.appendStructuredOutputToolToSchema(tools, badSchema);

        assertThat(tools).as("无效 jsonSchema 不暴露 StructuredOutput（对齐 CC failure 路径）").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // R2 · MAX_STRUCTURED_OUTPUT_RETRIES 安全阀
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R2: countStructuredOutputCalls 逐 assistant message 计 StructuredOutput（CC countToolCalls）")
    void countStructuredOutputCalls_countsAssistantToolCalls() {
        // 2 条 assistant 各含一次 SO 调用 + 1 条 assistant 只调 Read + 1 条 tool 消息 → 计 2
        List<ChatMessageDto> msgs = List.of(
            assistant("tu-so-1", SO),
            assistant("tu-so-2", SO),
            assistant("tu-read", "Read"),
            tool("tu-so-1", "content"));

        assertThat(LlmAgentLoop.countStructuredOutputCalls(msgs))
            .as("只计含 StructuredOutput tool_use 的 assistant 消息（CC messages.ts:4691-4707）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("R2: countStructuredOutputCalls null/空 → 0")
    void countStructuredOutputCalls_nullOrEmpty_returnsZero() {
        assertThat(LlmAgentLoop.countStructuredOutputCalls(null)).isZero();
        assertThat(LlmAgentLoop.countStructuredOutputCalls(List.of())).isZero();
    }

    @Test
    @DisplayName("R2: callsThisQuery 超过上限 → 安全阀终止（CC QueryEngine.ts:1013-1025）")
    void safetyValve_exceedsRetries_terminates() {
        // 模拟本 query 内 StructuredOutput 调用 ≥ maxRetries：baseline 0，当前 5 次调用。
        int initialStructuredOutputCalls = 0;
        int currentCalls = LlmAgentLoop.countStructuredOutputCalls(List.of(
            assistant("tu-1", SO), assistant("tu-2", SO), assistant("tu-3", SO),
            assistant("tu-4", SO), assistant("tu-5", SO)));
        int callsThisQuery = currentCalls - initialStructuredOutputCalls;

        assertThat(callsThisQuery).as("本 query 内 StructuredOutput 调用数（CC initialStructuredOutputCalls 相减）").isEqualTo(5);
        assertThat(callsThisQuery >= LlmAgentLoop.maxStructuredOutputRetries())
            .as("callsThisQuery >= MAX_STRUCTURED_OUTPUT_RETRIES → error_max_structured_output_retries 终止（CC :1025-1035）")
            .isTrue();
    }

    @Test
    @DisplayName("R2: 未达上限（1 次成功调用）→ 安全阀不触发（放行）")
    void safetyValve_belowLimit_doesNotTrigger() {
        int initialStructuredOutputCalls = 0;
        int currentCalls = LlmAgentLoop.countStructuredOutputCalls(List.of(assistant("tu-1", SO)));
        int callsThisQuery = currentCalls - initialStructuredOutputCalls;

        assertThat(callsThisQuery < LlmAgentLoop.maxStructuredOutputRetries())
            .as("1 次合法调用不触发安全阀")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 测试夹具
    // ════════════════════════════════════════════════════════════════════════

    private static ChatMessageDto assistant(String toolUseId, String toolName) {
        ToolCallDto tc = new ToolCallDto(toolUseId, toolName, "{}", null, null);
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, null,
            "", null, List.of(tc), null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto tool(String toolUseId, String content) {
        return new ChatMessageDto(
            null, null, Role.tool, toolUseId, content, null, null, null, null,
            null, null, OffsetDateTime.now(), null, null, null, List.of(), List.of());
    }
}
