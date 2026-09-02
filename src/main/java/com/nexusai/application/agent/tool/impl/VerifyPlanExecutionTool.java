package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VerifyPlanExecutionTool 真实现 · 对齐 CC
 * {@code Open-ClaudeCode/src/tools/VerifyPlanExecutionTool/VerifyPlanExecutionTool.ts:7-93}。
 *
 * <p><b>语义（模型自证，WHY）</b>: CC {@code call()}（:85-92）不做任何独立核验，返回
 * {@code data = { verified: input.all_steps_completed, summary: input.plan_summary }}——
 * 工具是<b>模型在退出 plan mode 前的自证声明通道</b>：模型输入 plan_summary（执行的计划
 * 摘要）+ verification_notes（验证备注，可选）+ all_steps_completed（是否全部完成），
 * 工具把 {@code verified=all_steps_completed} + {@code summary=plan_summary} 原样回传
 * （:88-89）。verified 与 all_steps_completed 恒等，绝不由工具自行判定（否则模型自证
 * 信号丢失）。
 *
 * <p><b>门控（保留）</b>: CC {@code tools.ts:91-94} 用
 * {@code process.env.CLAUDE_CODE_VERIFY_PLAN === 'true'} 条件构建；Java 等价
 * {@code FeatureFlags.verifyPlan()}（{@code CLAUDE_CODE_VERIFY_PLAN} env gate ·
 * FeatureFlags.java:244-246/343-345）。{@link #isEnabled()} = {@code verifyPlan()}，
 * 默认关（对齐 CC env 非 'true' 时工具为 null、不进 getAllBaseTools · tools.ts:231）。
 *
 * <p><b>行为对齐点（CC 真源）</b>：
 * <ul>
 *   <li>{@code description()} — :38-40 'Verify that a plan was executed correctly before exiting plan mode'</li>
 *   <li>{@code prompt()} — :41-49 指南全文</li>
 *   <li>{@code inputSchema()} — :7-22 {@code z.strictObject}（plan_summary 必填 string /
 *       verification_notes 可选 string / all_steps_completed 必填 boolean + additionalProperties=false）</li>
 *   <li>{@code isConcurrencySafe()} — :51-53 true（无副作用）</li>
 *   <li>{@code isReadOnly()} — :54-56 true</li>
 *   <li>{@code userFacingName()} — :58-60 'VerifyPlan'</li>
 *   <li>{@code renderToolUseMessage()} — :62-70 三分支（all_steps_completed true/false/缺省）</li>
 *   <li>{@code mapToToolResultBlockParam()} — :72-83 {@code verified ? 'Plan verified: '+summary
 *       : 'Plan verification failed: '+summary}</li>
 *   <li>{@code maxResultSizeChars()} — :31 10_000；{@code searchHint()} — :30
 *       'verify plan execution check completion'；{@code strict()} — :32 true</li>
 * </ul>
 */
public class VerifyPlanExecutionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VerifyPlanExecutionTool.class);

    /** CC 工具名 · {@code VerifyPlanExecutionTool/constants.ts:1} VERIFY_PLAN_EXECUTION_TOOL_NAME='VerifyPlanExecution'。 */
    public static final String NAME = ToolNameConstants.VERIFY_PLAN_EXECUTION_TOOL_NAME;

    /** CC data key: verified（VerifyPlanExecutionTool.ts:26/88 VerifyOutput.verified）· snake_case 保持 CC 原样。 */
    public static final String DATA_KEY_VERIFIED = "verified";
    /** CC data key: summary（VerifyPlanExecutionTool.ts:26/89 VerifyOutput.summary）。 */
    public static final String DATA_KEY_SUMMARY = "summary";

    private final FeatureFlags featureFlags;

    public VerifyPlanExecutionTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public VerifyPlanExecutionTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("VerifyPlanExecutionTool.name(): 返回 CC 工具名 VERIFY_PLAN_EXECUTION_TOOL_NAME='VerifyPlanExecution'（对齐 VerifyPlanExecutionTool/constants.ts:1）");
        }
        return NAME;
    }

    @Override
    public String description() {
        // CC VerifyPlanExecutionTool.ts:38-40 description()
        return "Verify that a plan was executed correctly before exiting plan mode";
    }

    /**
     * 工具提示词 · 对齐 CC {@code VerifyPlanExecutionTool.ts:41-49 prompt()} 逐字移植。
     */
    @Override
    public String prompt() {
        return """
            Verify that a plan has been executed correctly. Call this tool before exiting plan mode to confirm all steps were completed.

            Guidelines:
            - Summarize the plan that was executed
            - Note whether all steps completed successfully
            - Include any verification notes (tests passed, files created, etc.)
            - If steps were skipped or failed, explain why in verification_notes""";
    }

    /**
     * 搜索提示 · 对齐 CC {@code VerifyPlanExecutionTool.ts:30 searchHint}
     * 'verify plan execution check completion'。
     */
    @Override
    public String searchHint() {
        return "verify plan execution check completion"; // CC VerifyPlanExecutionTool.ts:30
    }

    @Override
    public long maxResultSizeChars() {
        return 10_000L; // CC VerifyPlanExecutionTool.ts:31 maxResultSizeChars: 10_000
    }

    @Override
    public boolean strict() {
        return true; // CC VerifyPlanExecutionTool.ts:32 strict: true
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = this.featureFlags.verifyPlan();
        if (log.isDebugEnabled()) {
            log.debug("VerifyPlanExecutionTool.isEnabled() = {}（CLAUDE_CODE_VERIFY_PLAN==='true' 门控，CC tools.ts:91-94）", enabled);
        }
        return enabled;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true; // CC VerifyPlanExecutionTool.ts:51-53 isConcurrencySafe() → true（无副作用）
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true; // CC VerifyPlanExecutionTool.ts:54-56 isReadOnly() → true
    }

    @Override
    public String userFacingName() {
        return "VerifyPlan"; // CC VerifyPlanExecutionTool.ts:58-60 userFacingName() → 'VerifyPlan'
    }

    /**
     * 输入 schema · 对齐 CC {@code VerifyPlanExecutionTool.ts:7-22}
     * {@code z.strictObject({plan_summary, verification_notes?, all_steps_completed})}。
     *
     * <p>required = [plan_summary, all_steps_completed]（模型自证两要素必填）；
     * verification_notes 可选（:12-17 optional）。strictObject → additionalProperties=false
     * （运行时拒绝未知键，ToolInputValidator 按 schema 执行）。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode planSummary = props.putObject("plan_summary");
        planSummary.put("type", "string");
        planSummary.put("description", "A summary of the plan that was executed."); // CC :9-11

        ObjectNode verificationNotes = props.putObject("verification_notes");
        verificationNotes.put("type", "string");
        verificationNotes.put("description",
            "Notes on what was verified and any issues found during verification."); // CC :12-17

        ObjectNode allStepsCompleted = props.putObject("all_steps_completed");
        allStepsCompleted.put("type", "boolean");
        allStepsCompleted.put("description", "Whether all planned steps were completed successfully."); // CC :18-20

        schema.putArray("required").add("plan_summary").add("all_steps_completed");
        schema.put("additionalProperties", false); // z.strictObject（CC :8）
        return schema;
    }

    /**
     * 工具使用消息 · 对齐 CC {@code VerifyPlanExecutionTool.ts:62-70 renderToolUseMessage}
     * 三分支：all_steps_completed true → 'Verify Plan: all steps completed'；false →
     * 'Verify Plan: incomplete'；缺省 → 'Verify Plan'。
     */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input != null && input.has("all_steps_completed") && input.get("all_steps_completed").isBoolean()) {
            if (input.get("all_steps_completed").asBoolean()) {
                return "Verify Plan: all steps completed"; // CC :63-65
            }
            return "Verify Plan: incomplete"; // CC :66-68
        }
        return "Verify Plan"; // CC :69
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        // 模型自证两要素（CC :88-89）——verified 恒等于 all_steps_completed，summary 逐字透传
        // plan_summary。input 经 ToolInputValidator 按 inputSchema 前置校验（required 两字段
        // 必在），此处仍防御兜底（缺失 → verified=false / summary=""）。
        boolean allStepsCompleted = input != null
            && input.has("all_steps_completed")
            && input.get("all_steps_completed").isBoolean()
            && input.get("all_steps_completed").asBoolean();
        String planSummary = input != null
            && input.has("plan_summary")
            && input.get("plan_summary").isTextual()
            ? input.get("plan_summary").asText() : "";

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put(DATA_KEY_VERIFIED, allStepsCompleted);
        data.put(DATA_KEY_SUMMARY, planSummary);

        log.info("[VerifyPlanExecutionTool] 会话自证计划执行结果 verified={} summaryLen={}（CC VerifyPlanExecutionTool.ts:85-92 call() 返回 {verified, summary}）",
            allStepsCompleted, planSummary.length());
        return ToolResult.success(call.id(), data);
    }

    /**
     * tool_result 块 · 对齐 CC {@code VerifyPlanExecutionTool.ts:72-83
     * mapToolResultToToolResultBlockParam}：{@code content.verified ? 'Plan verified: '+content.summary
     * : 'Plan verification failed: '+content.summary}。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr) || !(tr.data() instanceof JsonNode node)) {
            return null;
        }
        boolean verified = node.has(DATA_KEY_VERIFIED) && node.get(DATA_KEY_VERIFIED).asBoolean(false);
        String summary = node.has(DATA_KEY_SUMMARY) ? node.get(DATA_KEY_SUMMARY).asText() : "";
        String content = verified
            ? "Plan verified: " + summary
            : "Plan verification failed: " + summary; // CC :79-82
        if (log.isDebugEnabled()) {
            log.debug("[VerifyPlanExecutionTool] mapToToolResultBlockParam 生成 tool_result content={}（CC :79-82）", content);
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}
