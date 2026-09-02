package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CRON-A3 · CronDeleteTool 对齐 CC {@code CronDeleteTool.ts} 契约测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC 的 3 个不变量 ——
 * <ul>
 *   <li>validateInput 预查 id 存在性 → 不存在 errorCode 1（精确消息，不再透传
 *       NotFoundException 原文）；</li>
 *   <li>teammate 上下文存在且任务属另一 agent → errorCode 2（CC CronDeleteTool.ts:71-79
 *       "Teammates may only delete their own crons"）；</li>
 *   <li>成功输出仅人类文本 {@code Cancelled job {id}.}（CC :90 tool_result content），
 *       输出中不得再出现 status/cancelled 字段（C18 删除回归保护）。</li>
 * </ul>
 *
 * <p><b>风险登记</b>: {@code ScheduleDto.agentId} 由 WF-B 填充（当前生产恒 null），
 * 且 TeammateContext 生产端 0 设定 → errorCode 2 分支生产不可达；本测试用
 * {@code TeammateContext.runWithTeammateContext} 注入 teammate 上下文直接驱动分支，
 * 验证结构对齐（CRON-A3 concerns 登记）。
 */
class CronDeleteToolCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 构造一条 schedule 任务（agentId 为 CC CronDeleteTool.ts:73 task.agentId 的接线缝）。 */
    private static ScheduleDto dto(String id, String agentId) {
        return new ScheduleDto(
            id, "job " + id, ScheduleKind.cron, "0 */5 * * * *",
            null, null, "echo hi", null, null, null,
            ScheduleScope.SESSION, null, agentId, null);
    }

    private static ToolUseBlock call(String callId, String jobId) {
        ObjectNode input = JSON.createObjectNode();
        input.put("id", jobId);
        return new ToolUseBlock(callId, "CronDelete", input);
    }

    @Test
    @DisplayName("validateInput: id 存在 → pass")
    void validateInput_passesWhenIdExists() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of(dto("job-1", null)));
        CronDeleteTool tool = new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", "job-1").input(), null);

        assertThat(r.ok())
            .as("存在性预查通过").isTrue();
    }

    @Test
    @DisplayName("validateInput: id 不存在 → errorCode 1 + CC 精确消息")
    void validateInput_errorCode1_whenIdNotFound() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of());
        CronDeleteTool tool = new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", "ghost-9").input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("1");
        assertThat(r.message())
            .as("CC CronDeleteTool.ts:67 精确消息")
            .isEqualTo("No scheduled job with id 'ghost-9'");
    }

    @Test
    @DisplayName("validateInput: teammate 上下文 + 任务属另一 agent → errorCode 2")
    void validateInput_errorCode2_whenTaskOwnedByAnotherAgent() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of(dto("job-1", "agent-B")));
        CronDeleteTool tool = new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);
        TeammateContext teammate = new TeammateContext(
            "agent-A", "peer", "team", null, false, null,
            AbortControllerFactory.create());

        Tool.ValidationResult r = TeammateContext.runWithTeammateContext(
            teammate,
            () -> tool.validateInput(call("c1", "job-1").input(), null));

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("2");
        assertThat(r.message())
            .as("CC CronDeleteTool.ts:76 精确消息")
            .isEqualTo("Cannot delete cron job 'job-1': owned by another agent");
    }

    @Test
    @DisplayName("execute: 成功 → data 为 'Cancelled job {id}.' 人类文本 (CC :90)")
    void execute_success_dataIsCancelledJobText() {
        ScheduleService svc = mock(ScheduleService.class);
        CronDeleteTool tool = new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(call("c1", "job-1"));

        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(r.data())
            .as("CC CronDeleteTool.ts:90 content 语义（tool_result 文本）")
            .isEqualTo("Cancelled job job-1.");
    }

    @Test
    @DisplayName("execute: 成功输出不含 status/cancelled 字段 (C18 删除回归保护)")
    void execute_success_outputHasNoStatusField() {
        ScheduleService svc = mock(ScheduleService.class);
        CronDeleteTool tool = new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(call("c1", "job-1"));

        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        String text = (String) r.data();
        assertThat(text).isNotBlank();
        assertThat(text).doesNotContain("status");
        assertThat(text).doesNotContain("cancelled");
    }

    @Test
    @DisplayName("isEnabled: cronGates null → fail-open true（决策#11 CRON-F2，对齐 CC isKairosCronEnabled 无 null 态）")
    void isEnabled_nullCronGates_failsOpen() {
        // CC CronDeleteTool.ts:46-48 isEnabled() = isKairosCronEnabled() 恒返回 boolean 无 null 态；
        // Java 生产 CronEnabledGates 恒非 null，直接 new + null 仅防护测试/构造路径 → null 视为门开。
        CronDeleteTool tool = new CronDeleteTool(mock(ScheduleService.class), null);

        assertThat(tool.isEnabled())
            .as("门控 null → 门开（fail-open，决策#11 CRON-F2）")
            .isTrue();
    }

    @Test
    @DisplayName("isEnabled: 对照组锁全真值表 —— DEFAULTS→true / 关闸(false,true)→false")
    void isEnabled_truthTable() {
        ScheduleService svc = mock(ScheduleService.class);
        assertThat(new CronDeleteTool(svc, CronEnabledGates.DEFAULTS).isEnabled())
            .as("默认门(true,true) → 开").isTrue();
        // agentTriggerCron=false 短路 → 恒 false（BundledSkillEnabledGates.isKairosCronEnabled:63-65，
        // 不 consult env，对照组确定）
        assertThat(new CronDeleteTool(svc, new CronEnabledGates(false, true)).isEnabled())
            .as("关闸(false,true) → 关").isFalse();
    }

    @Test
    @DisplayName("inputSchema: additionalProperties=false（CC :21 z.strictObject，拒绝多余字段）+ 运行时 unrecognized_keys")
    void inputSchema_strictObject() {
        CronDeleteTool tool = new CronDeleteTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").isBoolean())
            .as("CC CronDeleteTool.ts:21 z.strictObject 显式声明 additionalProperties").isTrue();
        assertThat(schema.path("additionalProperties").asBoolean())
            .as("CC CronDeleteTool.ts:21 z.strictObject → additionalProperties:false").isFalse();
        assertThat(schema.path("properties").path("id").path("type").asText())
            .isEqualTo("string");
        assertThat(schema.path("properties").path("id").path("description").asText())
            .isEqualTo("Job ID returned by CronCreate.");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).containsExactly("id");

        // 运行时拒绝未知键：ToolInputValidator unknownKeysPolicy() 默认 UNSPECIFIED 跟随广告层
        // additionalProperties=false → 顶层未知键逐键 unrecognized_keys（对齐 CC toolErrors.ts:114）
        ToolInputValidator validator = new ToolInputValidator();
        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool,
            JSON.createObjectNode().put("id", "x").put("extra", 1));
        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
        assertThat(result.issues().get(0).message())
            .isEqualTo("An unexpected parameter `extra` was provided");
    }
}
