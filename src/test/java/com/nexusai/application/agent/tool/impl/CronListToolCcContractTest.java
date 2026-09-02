package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CRON-A4 · CronListTool 对齐 CC {@code CronListTool.ts} 契约测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC 的 4 个不变量 ——
 * <ul>
 *   <li>jobs 条件键（CC :28-29 recurring/durable optional）：recurring 派生 kind!=once 才含
 *       {@code (recurring)}，durable 派生 scope==SESSION 才含 {@code [session-only]}
 *       （Java 无独立布尔，由 kind/scope 派生，risk R3）；</li>
 *   <li>teammate 过滤（CC :66-69 {@code ctx ? allTasks.filter(t => t.agentId === ctx.agentId) : allTasks}）；
 *       无 ctx → 全部，有 ctx → 只保留 agentId 匹配（结构对齐，ScheduleDto.agentId 由 WF-B 填充，risk R2）；</li>
 *   <li>tool_result 列表文本（CC :80-93 mapToolResultToToolResultBlockParam）：非空逐行
 *       {@code ${id} — ${humanSchedule}${(recurring)|(one-shot)}${[session-only]}: ${truncate(prompt,80,true)}}，
 *       空 → {@code No scheduled jobs.}；</li>
 *   <li>输出无 count / toJson 12 字段残留（C17 删除回归保护）。</li>
 * </ul>
 *
 * <p><b>风险登记</b>: {@code ScheduleDto.agentId} 由 WF-B 填充（当前生产恒 null）、TeammateContext
 * 生产端 0 设定 → 过滤分支生产不可达；本测试用 {@code TeammateContext.runWithTeammateContext}
 * 注入 teammate 上下文直接驱动分支，验证结构对齐（同 CRON-A3 做法）。
 */
class CronListToolCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 构造一条 schedule 任务（command=prompt 语义对齐 CC prompt → Java command，CronCreateTool:41）。
     *  cron 用真实存储形 {@code 0 0 * ? * *}（CronExpressionConverter.toQuartz6Field 产物，6 段）——
     *  Δ2 回归锁：CronToHuman 归一 5 段后仍须翻译 "Every hour"。 */
    private static ScheduleDto dto(String id, ScheduleKind kind, ScheduleScope scope,
                                   String agentId) {
        return new ScheduleDto(
            id, "job " + id, kind, "0 0 * ? * *",
            null, null, "remind me to do the thing", "remind me to do the thing",
            null, null, scope, null, agentId, null);
    }

    /** once 专用工厂：cron=null + runAt（对齐 CronCreateTool:359-361 once 落库形态 cronForSchedule=null）。 */
    private static ScheduleDto onceDto(String id, String runAt) {
        return new ScheduleDto(
            id, "job " + id, ScheduleKind.once, null,
            null, runAt, "remind me to do the thing", "remind me to do the thing",
            null, null, ScheduleScope.DURABLE, null, null, null);
    }

    private static ToolUseBlock call(String callId) {
        ObjectNode input = JSON.createObjectNode();
        return new ToolUseBlock(callId, "CronList", input);
    }

    private static String listText(ScheduleService svc, ScheduleDto... tasks) {
        when(svc.listAll()).thenReturn(List.of(tasks));
        CronListTool tool = new CronListTool(svc, CronEnabledGates.DEFAULTS);
        ToolResult<?> r = (ToolResult<?>) tool.execute(call("c1"));
        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("execute 不报错").isFalse();
        return (String) r.data();
    }

    @Test
    @DisplayName("空任务 → tool_result 为 'No scheduled jobs.' (CC :92)")
    void execute_emptyDataIsNoScheduledJobs() {
        String text = listText(mock(ScheduleService.class));
        assertThat(text).isEqualTo("No scheduled jobs.");
    }

    @Test
    @DisplayName("cron 任务 → 行含 humanSchedule + (recurring)，无 [session-only] (CC :70-77/:89)")
    void execute_cronTask_rendersRecurringLine() {
        ScheduleService svc = mock(ScheduleService.class);
        String text = listText(svc, dto("job-1", ScheduleKind.cron, ScheduleScope.DURABLE, null));

        assertThat(text).contains("job-1 — Every hour (recurring): remind me to do the thing");
        assertThat(text).as("DURABLE 无 [session-only]").doesNotContain("[session-only]");
    }

    @Test
    @DisplayName("once 任务 cron=null（存量/REST 直建）→ humanSchedule 显示 unknown + (one-shot)（主代码 fail-loud，删除 formatRunAt）")
    void execute_onceTask_rendersOneShot() {
        ScheduleService svc = mock(ScheduleService.class);
        // [tool_v3/IMP-F2 DEL-F2-02 对齐] CronCreateTool once 已改存原始 cron（无 runAt null 态）；
        //   cron=null 仅存量行/REST 直建 once → 主代码 fail-loud warn + humanSchedule="unknown"
        //   （CronListTool:266-273，删除 formatRunAt，不再按 runAt 格式化）。
        String text = listText(svc, onceDto("job-2", "2026-08-12T09:00:00+08:00"));

        assertThat(text).contains("job-2 — unknown (one-shot):");
    }

    @Test
    @DisplayName("SESSION 任务 → 行含 [session-only] (CC :89 durable===false)")
    void execute_sessionTask_rendersSessionOnly() {
        ScheduleService svc = mock(ScheduleService.class);
        String text = listText(svc, dto("job-3", ScheduleKind.cron, ScheduleScope.SESSION, null));

        assertThat(text).contains("(recurring) [session-only]:");
    }

    @Test
    @DisplayName("prompt 超 80 列 → truncate(prompt,80,true) 尾 '…' (CC :89/truncate.ts:134-158)")
    void execute_longPrompt_truncated() {
        ScheduleService svc = mock(ScheduleService.class);
        String longPrompt = "A".repeat(120);
        ScheduleDto d = new ScheduleDto(
            "job-4", "job 4", ScheduleKind.cron, "0 * * * *", null, null,
            longPrompt, longPrompt, null, null, ScheduleScope.DURABLE, null, null, null);
        String text = listText(svc, d);

        assertThat(text).as("截断尾缀 … 且保留前缀").contains("AAAA…");
        assertThat(text).as("不超 80+分隔符").hasSizeLessThan(120);
    }

    @Test
    @DisplayName("prompt 含换行 → singleLine 截首行 (truncate.ts:142-151)")
    void execute_promptWithNewline_truncatedAtFirstLine() {
        ScheduleService svc = mock(ScheduleService.class);
        ScheduleDto d = new ScheduleDto(
            "job-5", "job 5", ScheduleKind.cron, "0 * * * *", null, null,
            "line one\nline two", "line one\nline two", null, null,
            ScheduleScope.DURABLE, null, null, null);
        String text = listText(svc, d);

        assertThat(text).contains("job-5 — Every hour (recurring): line one…");
        assertThat(text).as("第二行不进入 tool_result").doesNotContain("line two");
    }

    @Test
    @DisplayName("无 teammate ctx → 全部任务 (CC :67-69)")
    void execute_noTeammate_returnsAll() {
        ScheduleService svc = mock(ScheduleService.class);
        String text = listText(svc,
            dto("job-a", ScheduleKind.cron, ScheduleScope.DURABLE, "agent-A"),
            dto("job-b", ScheduleKind.cron, ScheduleScope.DURABLE, "agent-B"));

        assertThat(text).contains("job-a").contains("job-b");
    }

    @Test
    @DisplayName("有 teammate ctx → 只保留 agentId 匹配任务 (CC :68)")
    void execute_withTeammate_filtersByAgentId() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of(
            dto("job-a", ScheduleKind.cron, ScheduleScope.DURABLE, "agent-A"),
            dto("job-b", ScheduleKind.cron, ScheduleScope.DURABLE, "agent-B")));
        CronListTool tool = new CronListTool(svc, CronEnabledGates.DEFAULTS);
        TeammateContext teammate = new TeammateContext(
            "agent-A", "peer", "team", null, false, null,
            AbortControllerFactory.create());

        ToolResult<?> r = TeammateContext.runWithTeammateContext(
            teammate,
            () -> (ToolResult<?>) tool.execute(call("c1")));

        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        String text = (String) r.data();
        assertThat(text).contains("job-a");
        assertThat(text).as("agentId 不匹配任务被过滤").doesNotContain("job-b");
    }

    @Test
    @DisplayName("输出无 count 字段 (C17 删除回归保护)")
    void execute_outputHasNoCount() {
        ScheduleService svc = mock(ScheduleService.class);
        String text = listText(svc, dto("job-1", ScheduleKind.cron, ScheduleScope.DURABLE, null));

        assertThat(text).as("tool_result 不再含 JSON count 键").doesNotContain("count");
    }

    @Test
    @DisplayName("listAll 抛异常 → 异常传播框架层而非 isError ToolResult（CAND-3：对齐 CC call() 无 catch）")
    void execute_listAllThrows_propagatesToFramework() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenThrow(new RuntimeException("db down"));
        CronListTool tool = new CronListTool(svc, CronEnabledGates.DEFAULTS);

        // CAND-3: 工具层零 catch（对齐 CC CronListTool.ts:63-79 call() 无 try/catch），
        // 异常直接抛框架层 StreamingToolExecutor 统一错误面 —— 直连 execute 断言传播而非
        // isError ToolResult；泄漏形态 "CronListTool: list failed" 0 命中回归锁。
        assertThatThrownBy(() -> tool.execute(call("c1")))
            .as("CAND-3: listAll 异常必须传播，不返回 error ToolResult")
            .isInstanceOf(RuntimeException.class)
            .hasMessage("db down")
            .hasMessageNotContaining("CronListTool: list failed");
    }

    @Test
    @DisplayName("outputSchema() 对齐 CC :20-33 jobs 6 字段结构")
    void outputSchema_documentsJobsStructure() {
        ScheduleService svc = mock(ScheduleService.class);
        CronListTool tool = new CronListTool(svc, CronEnabledGates.DEFAULTS);

        JsonNode schema = tool.outputSchema();
        assertThat(schema).as("outputSchema 非 null").isNotNull();
        JsonNode props = schema.path("properties").path("jobs");
        assertThat(props.path("type").asText()).isEqualTo("array");
        JsonNode itemProps = props.path("items").path("properties");
        assertThat(itemProps.has("id")).isTrue();
        assertThat(itemProps.has("cron")).isTrue();
        assertThat(itemProps.has("humanSchedule")).isTrue();
        assertThat(itemProps.has("prompt")).isTrue();
        assertThat(itemProps.has("recurring")).isTrue();
        assertThat(itemProps.has("durable")).isTrue();
        // CC :24-27 前 4 项必填 (z.string())，:28-29 recurring/durable 可选 (z.boolean().optional())
        // → required 仅含前 4 项，recurring/durable 不入 required
        List<String> requiredFields = new ArrayList<>();
        props.path("items").path("required").forEach(n -> requiredFields.add(n.asText()));
        assertThat(requiredFields).as("required 顺序 = 声明序 (CronListTool.ts:24-27)")
            .containsExactly("id", "cron", "humanSchedule", "prompt");
        assertThat(requiredFields).as("recurring/durable 为可选字段")
            .doesNotContain("recurring").doesNotContain("durable");
    }

    @Test
    @DisplayName("isEnabled: cronGates null → fail-open true（决策#11 CRON-F2，对齐 CC isKairosCronEnabled 无 null 态）")
    void isEnabled_nullCronGates_failsOpen() {
        // CC CronListTool.ts:48-50 isEnabled() = isKairosCronEnabled() 恒返回 boolean 无 null 态；
        // Java 生产 CronEnabledGates 恒非 null，直接 new + null 仅防护测试/构造路径 → null 视为门开。
        CronListTool tool = new CronListTool(mock(ScheduleService.class), null);

        assertThat(tool.isEnabled())
            .as("门控 null → 门开（fail-open，决策#11 CRON-F2）")
            .isTrue();
    }

    @Test
    @DisplayName("isEnabled: 对照组锁全真值表 —— DEFAULTS→true / 关闸(false,true)→false")
    void isEnabled_truthTable() {
        ScheduleService svc = mock(ScheduleService.class);
        assertThat(new CronListTool(svc, CronEnabledGates.DEFAULTS).isEnabled())
            .as("默认门(true,true) → 开").isTrue();
        // agentTriggerCron=false 短路 → 恒 false（BundledSkillEnabledGates.isKairosCronEnabled:63-65，
        // 不 consult env，对照组确定）
        assertThat(new CronListTool(svc, new CronEnabledGates(false, true)).isEnabled())
            .as("关闸(false,true) → 关").isFalse();
    }

    @Test
    @DisplayName("inputSchema: 空 strictObject → additionalProperties=false，拒绝任何多余键（CC :17）+ 运行时 unrecognized_keys")
    void inputSchema_strictObject() {
        CronListTool tool = new CronListTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").isBoolean())
            .as("CC CronListTool.ts:17 z.strictObject 显式声明 additionalProperties").isTrue();
        assertThat(schema.path("additionalProperties").asBoolean())
            .as("CC CronListTool.ts:17 z.strictObject({}) → additionalProperties:false").isFalse();
        assertThat(schema.path("properties").isObject())
            .as("CC strictObject({}) → properties 为空对象").isTrue();
        assertThat(schema.path("properties").isEmpty()).isTrue();
        assertThat(schema.path("required").isMissingNode())
            .as("CC strictObject({}) 无必填字段 → 无 required").isTrue();

        // 运行时拒绝未知键：空 strictObject 亦拒绝任何多余键（CC toolErrors.ts:114 unrecognized_keys）
        ToolInputValidator validator = new ToolInputValidator();
        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool,
            JSON.createObjectNode().put("extra", 1));
        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
        assertThat(result.issues().get(0).message())
            .isEqualTo("An unexpected parameter `extra` was provided");
    }
}
