package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-9 · E2E 测试 · tool_reference 从 producer 到 provider wire 全链路闭环（X-1 收口）。
 *
 * <p><b>WHY (意图验证)</b>: 真实 ToolSearchTool 命中后，producer
 * {@link LlmAgentLoop#toolResultMessage} 曾把 {@code block.content()} 为非 String 的块数组
 * 回退到 {@link ToolResult#renderToolResultPayloadText}，返回 record 的 {@code toString()}
 * （如 {@code ToolSearchOutput[matches=[Read],...]}），tool_reference 被<b>静默丢弃</b>。
 * 本测试锁定整条链路——真实 {@code ToolSearchTool.execute} → per-tool
 * {@code mapToToolResultBlockParam} → {@code LlmAgentLoop.toolResultMessage} 注入 contentBlocks
 * → {@code AnthropicSdkProvider.buildMessageParams} 序列化 wire——任何一环回退到旧文本渲染器，
 * tool_result.content 数组内就不会出现 {@code {type:"tool_reference", tool_name:"Read"}} 块，
 * 对应用例即 RED。
 *
 * <p>对齐 CC 真源：
 * <ul>
 *   <li>{@code ToolSearchTool.ts:462-469} matches 非空 → {@code content = matches.map(name =>
 *       ({type:'tool_reference', tool_name: name}))}（块数组，非文本）</li>
 *   <li>{@code toolExecution.ts:1292-1301} mappedContent 允许 String 或块数组两形态</li>
 *   <li>{@code toolExecution.ts:1418-1456} tool_result 块嵌套进 user 消息 content 数组</li>
 * </ul>
 */
class LlmAgentLoopToolResultBlockE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ───────────────────────── 主 E2E ─────────────────────────

    @Test
    @DisplayName("ToolSearch 命中 select:Read → producer 注入 tool_reference → provider wire tool_result.content 数组含 tool_reference")
    void toolSearchHit_toolReferenceFlowsProducerToProviderWire() throws Exception {
        ToolSearchTool tool = new ToolSearchTool();
        AgentToolResult<?> result = execute(tool, "select:Read", List.of(deferredTool("Read")));

        // 真实 producer 链：mapToToolResultBlockParam（内部）→ toolResultMessage 注入 contentBlocks
        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
                (ToolResult<?>) result, "toolu_1", false, tool, null, null, List.of(), List.of(), Map.of());

        // ── producer 断言：块数组注入 contentBlocks，payload 不再落 record toString ──
        assertThat(msg.contentBlocks())
                .as("producer 应把 tool_reference 块数组注入 contentBlocks，而非回退文本渲染器")
                .isNotEmpty();
        assertThat(msg.content())
                .as("块数组路径 payload 应置空，避免 provider 前置空文本块 / 丢弃块语义")
                .isEmpty();

        JsonNode producerBlock = (JsonNode) msg.contentBlocks().get(0);
        assertThat(producerBlock.get("type").asText()).isEqualTo("tool_reference");
        assertThat(producerBlock.get("tool_name").asText()).isEqualTo("Read");

        // ── provider wire 断言：tool_result.content 为块数组，含 tool_reference ──
        JsonNode body = buildWire(msg);
        JsonNode toolResultBlock = body.get("messages").get(0).get("content").get(0);
        assertThat(toolResultBlock.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResultBlock.get("tool_use_id").asText()).isEqualTo("toolu_1");

        JsonNode toolContent = toolResultBlock.get("content");
        assertThat(toolContent.isArray())
                .as("tool_result.content 应为嵌套块数组（CC ToolSearchTool.ts:462-469），非文本字符串")
                .isTrue();
        assertThat(toolContent.size()).isEqualTo(1);
        assertThat(toolContent.get(0).get("type").asText()).isEqualTo("tool_reference");
        assertThat(toolContent.get(0).get("tool_name").asText()).isEqualTo("Read");
    }

    @Test
    @DisplayName("ToolSearch 多命中 select:Read,Edit → 两个 tool_reference 块顺序注入")
    void toolSearchMultiHit_multipleToolReferenceBlocks() throws Exception {
        ToolSearchTool tool = new ToolSearchTool();
        AgentToolResult<?> result = execute(tool, "select:Read,Edit",
                List.of(deferredTool("Read"), deferredTool("Edit")));

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
                (ToolResult<?>) result, "toolu_1", false, tool, null, null, List.of(), List.of(), Map.of());

        JsonNode body = buildWire(msg);
        JsonNode toolContent = body.get("messages").get(0).get("content").get(0).get("content");

        assertThat(toolContent.isArray()).isTrue();
        assertThat(toolContent.size()).isEqualTo(2);
        assertThat(toolContent.get(0).get("tool_name").asText()).isEqualTo("Read");
        assertThat(toolContent.get(1).get("tool_name").asText()).isEqualTo("Edit");
        assertThat(toolContent.get(0).get("type").asText()).isEqualTo("tool_reference");
        assertThat(toolContent.get(1).get("type").asText()).isEqualTo("tool_reference");
    }

    // ───────────────────────── 负向守卫：空结果仍走文本回退 ─────────────────────────

    @Test
    @DisplayName("ToolSearch 空结果 → producer 走文本回退，tool_result.content 保持纯文本（块数组分支不误伤）")
    void toolSearchMiss_textFallbackStillWorks() throws Exception {
        ToolSearchTool tool = new ToolSearchTool();
        AgentToolResult<?> result = execute(tool, "select:Zzz", List.of(deferredTool("Read")));

        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
                (ToolResult<?>) result, "toolu_1", false, tool, null, null, List.of(), List.of(), Map.of());

        assertThat(msg.contentBlocks()).as("空结果路径不注入块").isEmpty();
        assertThat(msg.content()).as("空结果走文本回退").startsWith("No matching deferred tools found");

        JsonNode body = buildWire(msg);
        JsonNode toolResultBlock = body.get("messages").get(0).get("content").get(0);
        assertThat(toolResultBlock.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResultBlock.get("content").isTextual()).isTrue();
        assertThat(toolResultBlock.get("content").asText())
                .startsWith("No matching deferred tools found");
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 真实执行 ToolSearchTool（含 ToolUseContext，复用 ToolSearchToolRetrievalTest 模式）。 */
    private AgentToolResult<?> execute(ToolSearchTool tool, String query, List<Tool> tools) {
        JsonNode input = JSON.createObjectNode().put("query", query).put("max_results", 5);
        ToolUseBlock call = new ToolUseBlock("toolu_1", "ToolSearch", input);
        ToolUseContext ctx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                tools, "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "");
        return tool.execute(call, ctx);
    }

    /** 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode（复用 R32B9 测试模式）。 */
    private JsonNode buildWire(ChatMessageDto msg) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
                "claude-opus-4", (java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock>) null,
                List.of(msg), null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
                .writeValueAsString(params._body()));
    }

    /** 最小 deferred 匿名工具：shouldDefer=true 使 ToolSearchTool 将其纳入 deferred 全集。 */
    private Tool deferredTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " description"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
        };
    }
}
