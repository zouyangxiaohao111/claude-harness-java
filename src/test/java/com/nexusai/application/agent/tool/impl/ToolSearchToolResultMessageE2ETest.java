package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [OPD-TS-09-01] ToolSearch → toolResultMessage → ChatMessageDto 边界的 tool_reference 压平修复 E2E 测试.
 *
 * <p><b>WHY (规则九 · 意图验证)</b>: ToolSearchTool.mapToToolResultBlockParam 已正确产出
 * {@code ToolResultBlockParam(content=List<ToolReferenceBlockParam>)}，但 LlmAgentLoop.toolResultMessage
 * 经 toolResultPayloadText 只认 {@code content instanceof String}；List 内容回退
 * {@code ToolResult.renderToolResultPayloadText} → {@code String.valueOf(ToolSearchOutput)}
 * 把 record 压平成 {@code "ToolSearchOutput[matches=[Read], ...]"} 扁平串，结构化 tool_reference
 * 块丢失 → LLM/API 收不到 tool_reference 块，defer_loading 动态发现闭环死锁。
 *
 * <p>本测试锁定边界：toolResultMessage 命中 List<ContentBlockParam> 时必须结构化透传
 * （content 置空串 + contentBlocks 注入 {@code {type:tool_reference,tool_name}} 块数组
 * JsonNode —— Role.tool 扁平化主形状，tool_use_id 位于消息层 toolCallId，由 provider 端
 * 重组 tool_result 包裹块），不再 String.valueOf(record)。
 *
 * <p><b>变异点</b>: 删除 LlmAgentLoop.toolResultMessage 的 {@code block.content() instanceof List}
 * 结构化透传分支（退化为旧 String.valueOf 压平）→ 本测试的正向用例即变红
 * （{@code msg.content()} 非空、含 "ToolSearchOutput["，contentBlocks 空）。
 */
class ToolSearchToolResultMessageE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolSearchTool tool = new ToolSearchTool();

    @Test
    @DisplayName("Anthropic 命中 tool_reference → toolResultMessage 结构化透传（content 置空串 + contentBlocks 注入纯 tool_reference 块，不压平 record）")
    void toolSearch_selectHit_toolResultMessage_preservesToolReferenceBlocks() {
        List<Tool> tools = List.of(deferredTool("Read", "read a file"));
        // [openai-lazy] Anthropic（Claude，支持 tool_reference）→ 命中纯 tool_reference（CC 原样，无 schema text）
        AgentToolResult<?> result = executeResultWithModel("select:Read", tools, "claude-sonnet-4-5");

        // mapper 已产出 tool_reference 块数组（上游正确侧）
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "toolsearch-1", false);
        assertThat(block.content()).isInstanceOf(List.class);

        // 边界：toolResultMessage 不应再压平为 String.valueOf(record)
        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
                (ToolResult) result, "toolsearch-1", false, tool, "asst_1", null, List.of(), List.of(), Map.of());

        assertThat(msg.role()).isEqualTo(Role.tool);
        assertThat(msg.content())
                .as("content 不再承载 String.valueOf(ToolSearchOutput) 压平串（变异点：删 List 透传分支即非空）")
                .isEmpty();

        // contentBlocks 直接承载 tool_reference 块（SchemaNotSentHint Role.tool 扁平化主形状：
        //   contentBlocks 即 tool_result.content 数组；tool_use_id 位于消息层 toolCallId，
        //   provider 端 appendToolResultContentBlock 读 block.tool_name 重组 tool_result 包裹块）
        List<?> blocks = msg.contentBlocks();
        assertThat(blocks).as("结构化 tool_reference 块注入 contentBlocks").isNotNull().hasSize(1);
        JsonNode toolRefNode = (JsonNode) blocks.get(0);
        assertThat(toolRefNode.path("type").asText()).isEqualTo("tool_reference");
        assertThat(toolRefNode.path("tool_name").asText()).isEqualTo("Read");
        assertThat(msg.toolCallId())
                .as("tool_use_id 位于消息层 toolCallId，而非 contentBlocks 内包裹块")
                .isEqualTo("toolsearch-1");

        // isError 透传仍正确（结构化透传不得污染 isError 尾参）
        assertThat(msg.isError()).isFalse();
    }

    @Test
    @DisplayName("空 matches → 纯文本 tool_result，contentBlocks 无 tool_reference 注入（CC ToolSearchTool.ts:448-461）")
    void toolSearch_selectMiss_toolResultMessage_textPayload_noToolReferenceBlocks() {
        AgentToolResult<?> result = executeResult("select:Zzz", List.of(deferredTool("Read", "read a file")));

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
                (ToolResult) result, "toolsearch-1", false, tool, "asst_1", null, List.of(), List.of(), Map.of());

        assertThat(msg.content())
                .as("空 matches 走 String 载荷（CC ToolSearchTool.ts:448-461 纯文本）")
                .startsWith("No matching deferred tools found");
        assertThat(msg.contentBlocks())
                .as("空 matches 无结构化 tool_reference 块注入")
                .isNotNull()
                .isEmpty();
        assertThat(msg.isError()).isFalse();
    }

    // ───────────────────────── helpers（对齐 ToolSearchToolRetrievalTest） ─────────────────────────

    private AgentToolResult<?> executeResult(String query, List<Tool> tools) {
        return executeResultWithModel(query, tools, null);
    }

    /** [openai-lazy] 带模型名执行（model 非 null 且支持 tool_reference → Anthropic 分流纯 tool_reference）。 */
    private AgentToolResult<?> executeResultWithModel(String query, List<Tool> tools, String model) {
        JsonNode input = MAPPER.createObjectNode()
                .put("query", query)
                .put("max_results", 5);
        ToolUseBlock call = new ToolUseBlock("toolu_1", "ToolSearch", input);
        ToolUseContext ctx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                tools, "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "")
                .withEffectiveModelName(model);
        return tool.execute(call, ctx);
    }

    private static Tool deferredTool(String name, String prompt) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return prompt; }
            @Override public String prompt() { return prompt; }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
        };
    }
}
