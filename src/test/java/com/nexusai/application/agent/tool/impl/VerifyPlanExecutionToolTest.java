package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VerifyPlanExecutionTool 真行为单测 · 对齐 CC
 * {@code VerifyPlanExecutionTool/VerifyPlanExecutionTool.ts:7-93}。
 *
 * <p><b>WHY（意图验证，规则九）</b>: 本工具语义是<b>模型自证</b>——CC {@code call()}（:85-92）
 * 不做独立核验，{@code verified} 恒等于模型输入的 {@code all_steps_completed}，{@code summary}
 * 逐字透传 {@code plan_summary}（模型在退出 plan mode 前自证计划完成）。若 verified 与
 * all_steps_completed 解耦（如工具自行判定完成度），则模型自证信号丢失，门控（
 * CLAUDE_CODE_VERIFY_PLAN）开启后工具输出误导下游。测试锁定该不变式。
 */
class VerifyPlanExecutionToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 门控开（verifyPlan=true · 对齐 CLAUDE_CODE_VERIFY_PLAN==='true'）以触发真行为。 */
    private static VerifyPlanExecutionTool gatedOnTool() {
        FeatureFlags on = new FeatureFlags(
            false, false, false, false, false, false, false, false, false, false,
            false,  // bgSessions
            false,  // overflowTestTool
            false,  // terminalPanel
            true,   // verifyPlan（CLAUDE_CODE_VERIFY_PLAN）
            false, false, false, false, false, false, false);
        return new VerifyPlanExecutionTool(on);
    }

    @Test
    @DisplayName("verified 恒等于 all_steps_completed=true（模型自证全部完成 → verified=true，CC :88）")
    void verifiedEchoesAllStepsCompletedTrue() {
        // WHY: CC VerifyPlanExecutionTool.ts:88 verified: input.all_steps_completed —— 模型声明
        // 全部步骤完成时工具必须回 verified=true。这是"退出 plan mode 前自证"的核心契约；
        // 若工具把 true 误判为 false，模型无法完成 plan-mode 自证闭环。
        JsonNode data = executeData("Implement auth flow", true);

        Assertions.assertThat(data.get(VerifyPlanExecutionTool.DATA_KEY_VERIFIED).asBoolean())
            .as("verified 透传 all_steps_completed=true").isTrue();
        Assertions.assertThat(data.size()).as("data 仅含 {verified, summary}（CC VerifyOutput :26）").isEqualTo(2);
    }

    @Test
    @DisplayName("verified 恒等于 all_steps_completed=false（模型诚实声明失败 → verified=false，CC :88）")
    void verifiedEchoesAllStepsCompletedFalse() {
        // WHY: 反向边界——模型诚实声明部分步骤失败（all_steps_completed=false）时 verified 必须
        // =false（CC :88 verified: input.all_steps_completed），否则工具把失败误报为成功，
        // verifyStrategy 反演（假验证通过）。
        JsonNode data = executeData("Implement auth flow", false);

        Assertions.assertThat(data.get(VerifyPlanExecutionTool.DATA_KEY_VERIFIED).asBoolean())
            .as("verified 透传 all_steps_completed=false").isFalse();
    }

    @Test
    @DisplayName("summary 逐字透传 plan_summary（CC :89 summary: input.plan_summary）")
    void summaryEchoesPlanSummary() {
        // WHY: CC VerifyPlanExecutionTool.ts:89 summary: input.plan_summary —— 下游用户看到的是
        // "被验证的计划"内容。若工具改写/丢弃 summary，验证反馈失真（用户不知道模型自证的是哪个计划）。
        String planSummary = "Refactor DB layer + add composite indexes";
        JsonNode data = executeData(planSummary, true);

        Assertions.assertThat(data.get(VerifyPlanExecutionTool.DATA_KEY_SUMMARY).asText())
            .as("summary 逐字透传 plan_summary").isEqualTo(planSummary);
    }

    @Test
    @DisplayName("inputSchema 契约：plan_summary/all_steps_completed 必填、verification_notes 可选、strictObject（CC :7-22）")
    void inputSchemaContract() {
        // WHY: 对齐 CC z.strictObject（:8）—— plan_summary + all_steps_completed 是模型自证
        // 两要素必须必填（漏任一 → 模型自证不完整），verification_notes 可选（失败时说明原因），
        // additionalProperties=false 拒绝未知键（strictObject 语义）。漏任一 → 模型拿不到完整 schema。
        JsonNode schema = gatedOnTool().inputSchema();

        Assertions.assertThat(schema.get("type").asText()).isEqualTo("object");
        Assertions.assertThat(schema.get("additionalProperties").asBoolean())
            .as("strictObject → additionalProperties=false").isFalse();

        JsonNode props = schema.get("properties");
        Assertions.assertThat(props.get("plan_summary").get("type").asText()).isEqualTo("string");
        Assertions.assertThat(props.get("verification_notes").get("type").asText()).isEqualTo("string");
        Assertions.assertThat(props.get("all_steps_completed").get("type").asText()).isEqualTo("boolean");

        java.util.List<String> required = new java.util.ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asText()));
        Assertions.assertThat(required)
            .as("required 恰为模型自证两要素")
            .containsExactlyInAnyOrder("plan_summary", "all_steps_completed");
    }

    @Test
    @DisplayName("mapToToolResultBlockParam：verified=true → 'Plan verified: <summary>'（CC :79-82）")
    void mapperVerifiedTrueRendersPlanVerified() {
        // WHY: CC VerifyPlanExecutionTool.ts:79-80 verified 分支 `Plan verified: ${summary}` ——
        // 模型自证通过时给用户的反馈必须是确认语气，否则成功验证被展示为失败。
        ToolResult<?> result = (ToolResult<?>) gatedOnTool().execute(call("Implement", true));
        ToolResultBlockParam block = gatedOnTool().mapToToolResultBlockParam(result, "tu-1", false);

        Assertions.assertThat(block.content()).isEqualTo("Plan verified: Implement");
    }

    @Test
    @DisplayName("mapToToolResultBlockParam：verified=false → 'Plan verification failed: <summary>'（CC :79-82）")
    void mapperVerifiedFalseRendersFailure() {
        // WHY: CC VerifyPlanExecutionTool.ts:81-82 非 verified 分支 `Plan verification failed: ${summary}`
        // —— 模型自证失败时反馈必须显式标红失败，否则用户/下游误以为计划已通过验证。
        ToolResult<?> result = (ToolResult<?>) gatedOnTool().execute(call("Implement", false));
        ToolResultBlockParam block = gatedOnTool().mapToToolResultBlockParam(result, "tu-1", false);

        Assertions.assertThat(block.content()).isEqualTo("Plan verification failed: Implement");
    }

    @Test
    @DisplayName("renderToolUseMessage：all_steps_completed true/false/缺省 三分支（CC :62-70）")
    void renderToolUseMessageBranches() {
        // WHY: CC VerifyPlanExecutionTool.ts:62-70 renderToolUseMessage —— 三分支给用户即时可见
        // 的自证结果（全部完成 / 未完成 / 状态未知）。漏分支 → UI 无法区分自证状态。
        VerifyPlanExecutionTool tool = gatedOnTool();

        ObjectNode completed = input("Implement", true);
        Assertions.assertThat(tool.renderToolUseMessage(completed))
            .as("all_steps_completed=true 分支").isEqualTo("Verify Plan: all steps completed");

        ObjectNode incomplete = input("Implement", false);
        Assertions.assertThat(tool.renderToolUseMessage(incomplete))
            .as("all_steps_completed=false 分支").isEqualTo("Verify Plan: incomplete");

        ObjectNode noFlag = MAPPER.createObjectNode();
        noFlag.put("plan_summary", "Implement");
        Assertions.assertThat(tool.renderToolUseMessage(noFlag))
            .as("缺省分支").isEqualTo("Verify Plan");
    }

    @Test
    @DisplayName("工具元数据对齐 CC：name/description/isConcurrencySafe/isReadOnly/userFacingName/searchHint/maxResultSizeChars/strict")
    void metadataAlignsCc() {
        // WHY: 各元数据对齐 CC 真源（:30-60）—— searchHint 供 ToolSearch、userFacingName='VerifyPlan'
        // 供 UI、isReadOnly/isConcurrencySafe=true 允许并行（无副作用）、strict=true 对应
        // strictObject、maxResultSizeChars=10_000（:31）。任一偏离 → 前端/toolchain 展示失真。
        VerifyPlanExecutionTool tool = gatedOnTool();

        Assertions.assertThat(tool.name()).isEqualTo("VerifyPlanExecution"); // constants.ts:1
        Assertions.assertThat(tool.description())
            .isEqualTo("Verify that a plan was executed correctly before exiting plan mode"); // :39
        Assertions.assertThat(tool.searchHint()).isEqualTo("verify plan execution check completion"); // :30
        Assertions.assertThat(tool.userFacingName()).isEqualTo("VerifyPlan"); // :59
        Assertions.assertThat(tool.isConcurrencySafe(null)).isTrue(); // :52
        Assertions.assertThat(tool.isReadOnly(null)).isTrue(); // :55
        Assertions.assertThat(tool.maxResultSizeChars()).isEqualTo(10_000L); // :31
        Assertions.assertThat(tool.strict()).isTrue(); // :32
    }

    // ── helpers ──

    private static JsonNode executeData(String planSummary, boolean allCompleted) {
        AgentToolResult<?> result = gatedOnTool().execute(call(planSummary, allCompleted));
        Assertions.assertThat(result).as("execute 返回 ToolResult 而非 error").isInstanceOf(ToolResult.class);
        return (JsonNode) ((ToolResult<?>) result).data();
    }

    private static ToolUseBlock call(String planSummary, boolean allCompleted) {
        return new ToolUseBlock("tu-1", VerifyPlanExecutionTool.NAME, input(planSummary, allCompleted));
    }

    private static ObjectNode input(String planSummary, boolean allCompleted) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("plan_summary", planSummary);
        input.put("verification_notes", "ran unit tests, all green"); // 可选字段（CC :12-17）
        input.put("all_steps_completed", allCompleted);
        return input;
    }
}
