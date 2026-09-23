package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * deferred_tools_delta 生产门控（delta 专用门已删 · 对齐 2.1.278）。
 *
 * <p><b>本类的前身</b>：原名即 {@code DeferredToolsDeltaUnifiedGateTest}，锁定「DB
 * settings.deferred_tools_delta_enabled 覆盖 → 回落 env USER_TYPE=ant」这条统一判定在
 * 主循环 / 压缩内圈 / prepend 三处取值一致。该判据（CC 2.1.88 toolSearch.ts:629-633
 * {@code isDeferredToolsDeltaEnabled}）与其互补的 prepend 通道在 2.1.278 发行产物
 * （cc_bundle.js + claude.exe）双产物 0 命中 ⇒ 本批整条删除。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）现在锁定什么</b>：门删掉后，deferred_tools_delta 的产出只受
 * <b>capability 门</b>约束（isToolSearchEnabledOptimistic / toolReferenceUsable /
 * isToolSearchToolAvailable / 工具池非空），不再有任何 delta 专用开关 ⇒ 「env 空且无 DB 配置」
 * 与「env USER_TYPE=ant」两种形态都必须产出 —— 这正是删除的可观测后果。
 *
 * <p><b>变异自证</b>：把任意一道 delta 专用门（DB 列或 env USER_TYPE）加回
 * {@code deferredToolsDeltaAttachment} 的 gate 链 ⇒
 * {@link #noDeltaGate_envEmptyAndNoDb_stillProduces()} 转红（该形态下旧门会返回 null）；
 * 放宽任意 capability 门 ⇒ {@link #capabilityGate_providerOpenAi_blocksDelta()} 或
 * {@link #capabilityGate_noToolSearchTool_blocksDelta()} 转红。
 */
class DeferredToolsDeltaUnifiedGateTest {

    private static final String MODEL = "claude-sonnet-4-5";
    private static final String ANTHROPIC = "anthropic";
    private static final String OPENAI_COMPAT = "openai_compatible";

    @AfterEach
    void resetSeams() {
        // env seam 全局单例 → 逐测复位，杜绝串扰。
        ToolSearchService.envOverride = null;
    }

    // ════════════════════════════════════════════════════════════════════
    // (a) delta 专用门已删 → env 空 / 无 DB 配置也必须产出
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(a) env 空（USER_TYPE≠ant）+ 无 DB 覆盖 → 仍产出 deferred_tools_delta（旧门会返回 null）")
    void noDeltaGate_envEmptyAndNoDb_stillProduces() {
        ToolSearchService.envOverride = Map.of(); // 旧 env 层判 false —— 旧实现会在此返回 null → 红

        List<Tool> tools = List.of(
            tool("mcp__docs-server__search", true),  // MCP → 恒 deferred（added 非空）
            tool("ToolSearch", false));

        ChatMessageDto dtd = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            tools, MODEL, ANTHROPIC, List.of());

        assertThat(dtd)
            .as("delta 专用门已删 → 无 DB/env 开关，capability 门通过即产出")
            .isNotNull();
        assertThat(dtd.subtype()).isEqualTo(PostCompactAttachmentRestorer.DELTA_TYPE_DEFERRED_TOOLS);
        assertThat(dtd.content()).contains("mcp__docs-server__search");
    }

    @Test
    @DisplayName("(a') env USER_TYPE=ant（旧门判 true）→ 同一形态，产出不变（门删后 env 不再有意义）")
    void noDeltaGate_envAnt_stillProduces() {
        ToolSearchService.envOverride = Map.of("USER_TYPE", "ant");

        List<Tool> tools = List.of(
            tool("mcp__docs-server__search", true),
            tool("ToolSearch", false));

        assertThat(PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
                tools, MODEL, ANTHROPIC, List.of()))
            .as("env 两态产出相同 ⇒ 证明 env 开关已不在链上")
            .isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // (b) capability 门仍在（红线：不得为让测试过而放宽）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(b) capability 门：openai_compatible provider（无 tool_reference 语义）→ 不产出")
    void capabilityGate_providerOpenAi_blocksDelta() {
        ToolSearchService.envOverride = Map.of();

        List<Tool> tools = List.of(
            tool("mcp__docs-server__search", true),
            tool("ToolSearch", false));

        assertThat(PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
                tools, MODEL, OPENAI_COMPAT, List.of()))
            .as("toolReferenceUsable(openai_compatible,*) = false → gate 拦截（不得放宽）")
            .isNull();
    }

    @Test
    @DisplayName("(b') capability 门：工具池无 ToolSearch → 不产出")
    void capabilityGate_noToolSearchTool_blocksDelta() {
        ToolSearchService.envOverride = Map.of();

        List<Tool> tools = List.of(tool("mcp__docs-server__search", true));

        assertThat(PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
                tools, MODEL, ANTHROPIC, List.of()))
            .as("isToolSearchToolAvailable = false → gate 拦截（不得放宽）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 假 Tool（isMcp → 恒 deferred，CC isDeferredTool MCP 分支）。 */
    private static Tool tool(String name, boolean mcp) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return name + " desc"; }
            @Override
            public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
            @Override
            public boolean isMcp() { return mcp; }
            @Override
            public boolean shouldDefer(JsonNode input) { return mcp; }
        };
    }
}
