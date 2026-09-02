package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-2] SkillTool.mapToToolResultBlockParam 测试 · 对齐 CC {@code SkillTool.ts:843-862}
 * {@code mapToolResultToToolResultBlockParam}（inline/forked 两分支文案 → ToolResultBlockParam 3 键）.
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：CC {@code toolExecution.ts:1292-1295} 用
 * {@code tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)} 组装 LLM 可见的
 * {@code tool_result} content —— Java 默认空 Map（Tool.java:655 default）使技能调用
 * {@code tool_result} 无内容，LLM 看不到 "Launching skill: X" / fork 完成文案。P2-2 override
 * 使 SkillTool 的 mapper 行为对齐 CC 契约并被测试锁定。
 */
@DisplayName("P2-2 · SkillTool.mapToToolResultBlockParam (CC SkillTool.ts:843-862)")
class SkillToolMapResultTest {

    @Test
    @DisplayName("T1 inline: 无 status → 'Launching skill: X' + tool_use_id/type/content 3 键 (CC :857-861)")
    void inlineResult_rendersLaunchingSkill() {
        // GIVEN: mapToToolResultBlockParam 不依赖 registry，null 构造即可
        SkillToolImpl tool = new SkillToolImpl(null);
        // inline 分支数据（CC :303-312 inlineOutputSchema：success/commandName，无 status）
        ToolResult<String> inline = ToolResult.success(
                "toolu_1", "{\"success\":true,\"commandName\":\"commit\"}");

        // WHEN
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(inline, "toolu_1", false);

        // THEN: tool_use_id/type/content（CC :849-861）
        assertThat(block.toolUseId()).isEqualTo("toolu_1");   // CC :859 tool_use_id: toolUseID
        assertThat(block.type()).isEqualTo("tool_result");     // CC :858 type: 'tool_result'
        assertThat(block.content()).isEqualTo("Launching skill: commit");  // CC :860
    }

    @Test
    @DisplayName("T2 forked: status='forked' → 'Skill \"X\" completed (forked execution).\\n\\nResult:\\nY' (CC :848-854)")
    void forkedResult_rendersForkedCompleted() {
        SkillToolImpl tool = new SkillToolImpl(null);
        // forked 分支数据（CC :315-323 forkedOutputSchema：status:'forked' 必填）
        ToolResult<String> forked = ToolResult.success(
                "toolu_2",
                "{\"success\":true,\"commandName\":\"review-pr\",\"status\":\"forked\",\"agentId\":\"abc\",\"result\":\"Done\"}");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(forked, "toolu_2", false);

        assertThat(block.toolUseId()).isEqualTo("toolu_2");
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.content())
                .isEqualTo("Skill \"review-pr\" completed (forked execution).\n\nResult:\nDone");  // CC :852
    }

    @Test
    @DisplayName("T3 inline 带 allowedTools/model 无 status → 仍 inline 文案 (CC :303-312 status 可选)")
    void inlineWithExtras_rendersLaunchingSkill() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ToolResult<String> inline = ToolResult.success(
                "toolu_3",
                "{\"success\":true,\"commandName\":\"commit\",\"allowedTools\":[\"Edit\",\"Write\"],\"model\":\"claude-sonnet\"}");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(inline, "toolu_3", false);

        assertThat(block.content()).isEqualTo("Launching skill: commit");  // CC :860
        assertThat(block.toolUseId()).isEqualTo("toolu_3");
    }

    @Test
    @DisplayName("T4 isError=true → Map.of()（CC mapper 仅成功路径被调 toolExecution.ts:1292-1295）")
    void errorResult_returnsEmptyMap() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ToolResult<String> error = ToolResult.error("toolu_4", "Unknown skill: xyz");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(error, "toolu_4", true);

        assertThat(block).isNull();
    }

    @Test
    @DisplayName("T5 data 不可解析为 JSON → Map.of()（防御：非 outputSchema 数据不渲染文案）")
    void unparseableData_returnsEmptyMap() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ToolResult<String> bad = ToolResult.success("toolu_5", "not-json");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(bad, "toolu_5", false);

        assertThat(block).isNull();
    }

    @Test
    @DisplayName("T6 data 缺 commandName → Map.of()（防御：zod union 保证必填，Java 端不模拟 undefined 文本）")
    void dataWithoutCommandName_returnsEmptyMap() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ToolResult<String> noCmd = ToolResult.success("toolu_6", "{\"success\":true}");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(noCmd, "toolu_6", false);

        assertThat(block).isNull();
    }
}
