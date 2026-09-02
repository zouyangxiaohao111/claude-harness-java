package com.nexusai.application.agent.toolsearch;

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
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H2 闭环] SchemaNotSentHint gate4 走<b>生产交付链</b>的回归测试 · 锁定 OPD-TS-09-01
 * tool_reference 压平修复后, 生产 tool_result 消息历史能使 discovered set 非空、消除误报.
 *
 * <p><b>WHY (规则九 · 意图验证 + §7.2-5 反模式)</b>: {@link SchemaNotSentHintFullCoverageTest}
 * 的 gate4 用例（gate4_scanToolReference_discoveredNotEmpty 等）都是手动 {@code new
 * ChatMessageDto} + 手工塞 {@code Role.user} contentBlocks, 掩盖了生产缺口 —— 生产链上
 * tool_result 被 {@code LlmAgentLoop.toolResultMessage} 扁平化为 {@code Role.tool} 消息
 * （非 Role.user），而旧 {@code extractDiscoveredToolNames} 只扫 Role.user → 生产 discovered
 * 恒空 → gate4 恒放行 → 误报（tool 已发现仍注入 hint）。本测试不走手动构造, 而是复现
 * 「ToolSearchTool.execute → mapToToolResultBlockParam → LlmAgentLoop.toolResultMessage →
 * SchemaNotSentHint.extractDiscoveredToolNames → build」真实交付链, 锁定该闭环。
 *
 * <p><b>变异点</b>: 若把 {@code extractDiscoveredToolNames} 的 role 过滤回退为只扫 Role.user
 * （删除 Role.tool 分支）, 本测试正向用例即变红（{@code discovered} 空、build 返回非 null hint）。
 */
class SchemaNotSentHintGate4ProductionChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolSearchTool searchTool = new ToolSearchTool();

    /** 目标 deferred 工具（schema 失败方）: isMcp + shouldDefer=true 保证过 gate3。 */
    private static Tool deferredTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "deferred tool " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isMcp() { return true; }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
        };
    }

    @Test
    @DisplayName("生产链: ToolSearch select 命中 → toolResultMessage(Role.tool) → gate4 discovered 非空 → build==null（消除误报）")
    void productionChain_discoveredNonEmpty_buildNull() {
        Tool webSearch = deferredTool("WebSearch");

        // ── 1) ToolSearchTool.execute("select:WebSearch") 产 ToolResult（真实生产上游）──
        JsonNode input = JSON.createObjectNode()
                .put("query", "select:WebSearch")
                .put("max_results", 5);
        ToolUseBlock call = new ToolUseBlock("toolu_search_1", "ToolSearch", input);
        ToolUseContext searchCtx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                List.of(searchTool, webSearch));
        AgentToolResult<?> result = searchTool.execute(call, searchCtx);

        // ── 2) tool.mapToToolResultBlockParam 返回 content=List<ToolReferenceBlockParam>（OPD-TS-09-01 上游侧）──
        ToolResultBlockParam block = searchTool.mapToToolResultBlockParam(result, "toolsearch-1", false);
        assertThat(block.content())
                .as("mapper 命中 tool_reference 必须产 List<ContentBlockParam>（CC ToolSearchTool.ts:462-469）")
                .isInstanceOf(List.class);

        // ── 3) LlmAgentLoop.toolResultMessage 产 Role.tool 消息, contentBlocks 注入 tool_result 块 ──
        ChatMessageDto toolResultMsg = LlmAgentLoop.toolResultMessage(
                (ToolResult) result, "toolsearch-1", false, searchTool, "asst_1", null, List.of(), List.of(), Map.of());
        assertThat(toolResultMsg.role()).isEqualTo(Role.tool);
        assertThat(toolResultMsg.contentBlocks()).as("结构化 tool_reference 块注入 contentBlocks").hasSize(1);

        // ── 4) extractDiscoveredToolNames 从生产消息历史（Role.tool）提取到 WebSearch ──
        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(toolResultMsg));
        assertThat(discovered)
                .as("生产 tool_result（Role.tool）的 tool_reference 必须被发现, 否则 gate4 恒放行（H2 闭环根因）")
                .containsExactly("WebSearch");

        // ── 5) build 全链路: 4 道门过到 gate4, discovered 含目标工具 → 返回 null（消除误报）──
        ToolUseContext ctx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                List.of(searchTool, webSearch), "", AbortController.NOOP, List.of(toolResultMsg));
        String hint = SchemaNotSentHint.build(webSearch, ctx, JSON.createObjectNode());
        assertThat(hint)
                .as("discovered 含目标工具 → schema 已发 → 不注入 hint（生产误报消除）")
                .isNull();
    }

    @Test
    @DisplayName("反向: 无 tool_reference 历史 → discovered 空 → build 注入 hint（真正未发送仍提示）")
    void reverse_noToolReference_buildInjectsHint() {
        Tool webSearch = deferredTool("WebSearch");
        ToolUseContext ctx = ToolUseContext.of(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                List.of(searchTool, webSearch), "", AbortController.NOOP, List.of());

        String hint = SchemaNotSentHint.build(webSearch, ctx, JSON.createObjectNode());

        assertThat(hint).as("历史无 tool_reference → schema 未发送 → 注入 hint 引导加载").isNotNull();
        assertThat(hint).contains("select:WebSearch");
    }
}
