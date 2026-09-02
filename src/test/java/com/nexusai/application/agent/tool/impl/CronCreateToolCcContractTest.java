package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.chat.ChatService;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-A2b · CronCreateTool 对齐 CC {@code CronCreateTool.ts} 契约测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC 的 4 个不变量 ——
 * <ul>
 *   <li>validateInput 四错误码（CC :82-116 顺序 1→2→3→4）：1 非法 cron / 2 一年无匹配 /
 *       3 超 MAX_JOBS / 4 durable+teammate 冲突；errorCode2-4 消息逐字对齐 CC，
 *       errorCode1 消息按决策#5 为 6 字段契约文本（'Expected 6 fields: S M H DoM Mon DoW.'
 *       + IMPL-03 追加 '?' 占位说明，dom/dow 互斥）；errorCode2 自建 366 天上限
 *       （IMPL-03/NEW-1：CronExpressionConverter.hasMatchWithinYear，CC cron.ts:138 maxIter 等价）；</li>
 *   <li>effectiveDurable kill-switch（CC :120）：durable=true 但 {@code cron-durable} 门 false →
 *       effectiveDurable=false → SESSION-only（tool_result where 子句 "Session-only..." 可观测）；</li>
 *   <li>tool_result 人类文本（CC :143-153 mapToolResultToToolResultBlockParam）：recurring 含
 *       "Scheduled recurring job {id} ({humanSchedule}). Auto-expires after 7 days..."，
 *       one-shot 含 "Scheduled one-shot task ... It will fire once then auto-delete."；</li>
 *   <li>inputSchema strictObject（CC :28 z.strictObject）：additionalProperties:false。</li>
 * </ul>
 *
 * <p><b>风险登记 (WF-A-OD-11)</b>: errorCode 4 的 teammate predicate 用
 * {@code TeammateContext.getTeammateContext()}（对齐 CC 真源 :107 + CronDeleteTool:195 既有约定）；
 * Java 当前无 teammate agentId 字段（WF-B/D 补），本测试用
 * {@code TeammateContext.runWithTeammateContext} 注入 teammate 上下文直接驱动分支。
 */
class CronCreateToolCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 构造 mock 创建的 schedule 任务（execute 断言用，renderResult 只读 id）。 */
    private static ScheduleDto created(String id, ScheduleScope scope) {
        return new ScheduleDto(
            id, "job " + id, ScheduleKind.cron, "0 */5 * * * *",
            null, null, "run smoke test", "run smoke test", null, null,
            scope, null, null, null);
    }

    private static ObjectNode input(String cron, String prompt, Boolean recurring, Boolean durable) {
        ObjectNode in = JSON.createObjectNode();
        if (cron != null) in.put("cron", cron);
        if (prompt != null) in.put("prompt", prompt);
        if (recurring != null) in.put("recurring", recurring);
        if (durable != null) in.put("durable", durable);
        return in;
    }

    private static ToolUseBlock call(String callId, ObjectNode in) {
        return new ToolUseBlock(callId, "CronCreate", in);
    }

    /**
     * IMPL-06: 无碰撞 stub —— nextAvailableName 原样返回 base。碰撞 → -2/-3 递增后缀的
     * 计算属 ScheduleService 域（ScheduleServiceCreateStorageTest#nextAvailableName_suffixAndTruncation
     * 覆盖）；工具层仅负责派生 base + 透传返回值，故本类在服务边界 stub，而非 listAll 占用集
     * （mock 不执行真实方法体，unstubbed nextAvailableName 返回 null）。
     */
    private static void stubNoNameCollision(ScheduleService svc) {
        when(svc.nextAvailableName(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 清理 SessionProjectRoot 静态登记（批次X R2 测试 setForSession 注入，防跨测试污染）。 */
    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
    }

    // ═════════════ 批次X Q2 R2 · DURABLE 创建侧 boundProject 提取链（F2 键反解）═════════════

    @Test
    @DisplayName("批次X Q2: DURABLE + ctx(派生UUID) → req.boundProject() = 创建会话绑定项目（F2 反解命中）")
    void executePersistentWithBoundSessionStoresBoundProject() {
        // WHY（规则九 · 主特性创建侧意图）：CronCreateTool DURABLE 分支的项目锚 = 创建会话的
        // 绑定项目（对齐 CC STATE.projectRoot 启动锚 state.ts:511-513/523-525，非 sessionCwd ——
        // sessionCwd 会随会话内 cd 漂移）。工具路径 ctx.sessionId() 是派生 UUID，SessionProjectRoot
        //（boundProject 层）以原始键 "sess-xxx" 为键 → 经 SessionKeys.originalKey 反解回原始键
        // 再查（CRON-D5 F2 双键）。若反解失败或键错，boundProject 恒 null → fire 兜底 user.dir
        // （跨会话 cwd 错位）。本条是批次X 返工 R2 补的创建侧直接单测（旧测试全走 execute(call)
        // 单参重载 ctx=null → boundProject=null 分支，主特性提取链零覆盖）。
        String originalKey = "sess-b0d9f2a1";
        String derivedUuid = originalKey;
        String boundProject = "D:/repo/nexusai-backend";
        SessionProjectRoot.setForSession(originalKey, boundProject);
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-bp", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);
        // durable=true（DURABLE 分支）+ ctx.sessionId=派生 UUID（工具路径落库形态）
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), derivedUuid);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("0 9 * * *", "run smoke test", true, true)), ctx);

        assertThat(r.data()).as("execute 成功路径（data 非空）").isNotNull();
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().boundProject())
            .as("DURABLE 创建会话绑定项目须经 F2 反解命中（ctx 派生 UUID → originalKey → getForSession）")
            .isEqualTo(boundProject);
        assertThat(cap.getValue().sessionId())
            .as("[cron-durable-session-fire] DURABLE 存创建会话 sessionId（归属对话/注入目标，"
                + "fire 存活时 transcript 归创建会话）——对齐 CC fire 注入活跃会话 useScheduledTasks.ts:71-82")
            .isEqualTo(derivedUuid);
    }

    @Test
    @DisplayName("批次X Q2: DURABLE + ctx(派生UUID) 未绑定项目 → req.boundProject() = null（负例）")
    void executePersistentWithoutBoundSessionStoresNullBoundProject() {
        // WHY（负例）：创建会话未绑定项目（getForSession 未命中）→ boundProject 恒 null，
        // fire 兜底 user.dir（已知差异：CC 所有 durable 任务都在会话里创建，B 探查 §7.3）。
        // 不因反解失败/NPE 而错误填值，也不得抛异常。
        String originalKey = "sess-f00dcafe";
        String derivedUuid = originalKey;
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-null-bp", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), derivedUuid);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("0 9 * * *", "run smoke test", true, true)), ctx);

        assertThat(r.data()).as("execute 成功路径（data 非空）").isNotNull();
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().boundProject())
            .as("未绑定项目会话 → boundProject=null（fire 兜底 user.dir，已知差异）")
            .isNull();
    }

    // ═════════════ validateInput 四错误码（CC :82-116，顺序 1→2→3→4 固定）═════════════

    @Test
    @DisplayName("validateInput: 非法 cron → errorCode 1 + CC 精确消息")
    void validateInput_errorCode1_invalidCron() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("bad cron", "p", null, null)).input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message())
            .as("决策#5: errorCode1 消息 6 字段契约文本（S M H DoM Mon DoW，CC 为 5 字段被决策覆写）"
                + " + IMPL-03 追加 '?' 占位说明（dom/dow 互斥，LLM 可改写）")
            .isEqualTo("Invalid cron expression 'bad cron'. Expected 6 fields: S M H DoM Mon DoW. "
                + "Use '?' for the unused day-of-month or day-of-week field.");
    }

    @Test
    @DisplayName("validateInput: 一年内无匹配 → errorCode 2 + CC 精确消息")
    void validateInput_errorCode2_noMatchInYear() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        // 2月30日不存在 → 5 段经 toQuartz6Field 语法合法，但 Quartz getNextValidTimeAfter 无匹配（CC :90-96）
        Tool.ValidationResult r = tool.validateInput(call("c1", input("0 0 30 2 *", "p", null, null)).input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("2");
        assertThat(r.message())
            .as("CC CronCreateTool.ts:93 精确消息")
            .isEqualTo("Cron expression '0 0 30 2 *' does not match any calendar date in the next year.");

        // B5-4 补：6 段等价输入 '0 0 0 30 2 ?'（= 5 字段 '0 0 30 2 *' 的 toQuartz6Field 产物
        // sec=0 min=0 hour=0 dom=30 mon=2 dow=?；注意 plan 原字面 '0 0 30 2 * ?' 字段错位
        // hour=30 越界 → errorCode1，经实测纠正为正确 6 段形）。Quartz 原生 Feb30 永不匹配
        // → 同 errorCode2（印证 B5-1 §5「Quartz 无 366 天上限，Feb29 远排/Feb30 永不匹配」）
        Tool.ValidationResult r6 = tool.validateInput(call("c1", input("0 0 0 30 2 ?", "p", null, null)).input(), null);
        assertThat(r6.ok()).isFalse();
        assertThat(r6.errorCode()).isEqualTo("2");
        assertThat(r6.message())
            .isEqualTo("Cron expression '0 0 0 30 2 ?' does not match any calendar date in the next year.");
    }

    @Test
    @DisplayName("IMPL-03: 6 段 dom+dow 双具体（无 ?）→ errorCode 1 + 消息含 ? 占位说明（✗-G）")
    void validateInput_errorCode1_6fieldDualSpecific_placeholderHint() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        // '0 0 9 * * 1-5'：dom='*' + dow='1-5' 双具体 → Quartz 互斥规则拒绝（isValidExpression=false）
        // → errorCode1；IMPL-03 追加 '?' 占位说明（dom/dow 互斥：一方具体另一方须 '?'，LLM 可改写）。
        Tool.ValidationResult r = tool.validateInput(
            call("c1", input("0 0 9 * * 1-5", "p", null, null)).input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("1");
        assertThat(r.message())
            .as("IMPL-03/✗-G: errorCode1 消息须含 '?' 占位说明（dom/dow 互斥），且保留决策#5 6 字段文本主体")
            .contains("Expected 6 fields: S M H DoM Mon DoW")
            .contains("Use '?' for the unused day-of-month or day-of-week field");
    }

    @Test
    @DisplayName("IMPL-03: Feb-29 类 cron → errorCode2 判定与 hasMatchWithinYear 同源一致（NEW-1 366 上限）")
    void validateInput_errorCode2_feb29_beyond366d() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);
        long now = System.currentTimeMillis();

        // Feb-29 类稀疏 cron：Quartz 无 366 天上限（实测 2026-08-15 → 2028-02-29 ≈ 563 天），
        // CC maxIter=366*24*60 分钟超限 null → errorCode2 拒（09 NEW-1 已定方向，IMPL-03 自建
        // 上限 hasMatchWithinYear）。validateInput 硬编码 System.currentTimeMillis() 无法注入时钟，
        // Feb-29 边界跨年翻转（2027-03-01..2028-02-28 窗口内 next≤366 天会 pass）→ 用同源判定
        // 断言接线而非绝对值防 flaky；确定性边界覆盖在 CronExpressionConverterCoreTest（固定 localMs）。
        for (String feb29 : List.of("0 0 9 29 2 ?", "0 9 29 2 *")) {
            Tool.ValidationResult r = tool.validateInput(call("c1", input(feb29, "p", null, null)).input(), null);
            boolean withinYear = CronExpressionConverter.hasMatchWithinYear(feb29, now);
            assertThat(r.ok())
                .as("IMPL-03: Feb-29 结果必须与 hasMatchWithinYear(now) 同源一致（= %s），cron=[%s]",
                    withinYear, feb29)
                .isEqualTo(withinYear);
            if (!withinYear) {
                assertThat(r.errorCode()).isEqualTo("2");
            }
        }
    }

    @Test
    @DisplayName("validateInput: 超 MAX_JOBS → errorCode 3 + CC 精确消息")
    void validateInput_errorCode3_tooManyJobs() {
        ScheduleService svc = mock(ScheduleService.class);
        List<ScheduleDto> many = new ArrayList<>();
        for (int i = 0; i < ScheduleService.MAX_JOBS; i++) {
            many.add(created("job-" + i, ScheduleScope.SESSION));
        }
        when(svc.listAll()).thenReturn(many);
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("*/5 * * * *", "p", null, null)).input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("3");
        assertThat(r.message())
            .as("CC CronCreateTool.ts:101 精确消息")
            .isEqualTo("Too many scheduled jobs (max " + ScheduleService.MAX_JOBS + "). Cancel one first.");
    }

    @Test
    @DisplayName("validateInput: durable+teammate → errorCode 4 + CC 精确消息")
    void validateInput_errorCode4_durableTeammate() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of());
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);
        TeammateContext teammate = new TeammateContext(
            "agent-A", "peer", "team", null, false, null,
            AbortControllerFactory.create());

        Tool.ValidationResult r = TeammateContext.runWithTeammateContext(
            teammate,
            () -> tool.validateInput(call("c1", input("*/5 * * * *", "p", null, true)).input(), null));

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("4");
        assertThat(r.message())
            .as("CC CronCreateTool.ts:111 精确消息")
            .isEqualTo("durable crons are not supported for teammates (teammates do not persist across sessions)");
    }

    @Test
    @DisplayName("validateInput: 合法输入且非 teammate → pass")
    void validateInput_passesWhenValid() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of());
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("*/5 * * * *", "p", null, null)).input(), null);

        assertThat(r.ok()).as("合法 cron + 空任务 + 非 teammate → pass").isTrue();
    }

    // ═════════════ CRON-B1-C1 · errorCode1 闸门 5/6 段兼容（决策#5 OPD-Cron-T1-01）═════════════

    @Test
    @DisplayName("validateInput: 6 字段合法 cron '0 0 9 * * ?' → pass（决策#5 兼容 5/6 段）")
    void validateInput_6fieldValid_passes() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("0 0 9 * * ?", "p", null, null)).input(), null);

        assertThat(r.ok())
            .as("OPD-Cron-T1-01「写工具兼容5段 并也支持6段」：6 段 '0 0 9 * * ?' 经 toQuartz6Field 透传"
                + " + Quartz isValidExpression 校验合法 → 必须通过（errorCode1 文案已 6 字段，闸门不得再拒 6 段）")
            .isTrue();
    }

    @Test
    @DisplayName("validateInput: 6 字段内字段非法 '0 99 9 * * ?' → errorCode 1（归一后内字段仍校验）")
    void validateInput_6fieldInvalidInner_errorCode1() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("0 99 9 * * ?", "p", null, null)).input(), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode())
            .as("6 段 '0 99 9 * * ?' 分钟 99 超 0-59 → 6 段透传经数值越界/Quartz 校验拒绝 → errorCode1")
            .isEqualTo("1");
    }

    @Test
    @DisplayName("validateInput: 5 段 dow 区间 '0 9 * * 1-5' → pass（B5-1 improvement，toQuartzDow 1-5→2,3,4,5,6）")
    void validateInput_5fieldDowRange_passes() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        Tool.ValidationResult r = tool.validateInput(call("c1", input("0 9 * * 1-5", "p", null, null)).input(), null);

        assertThat(r.ok())
            .as("B5-1 §5 improvement：5 段 dow 区间 '1-5' 经 toQuartzDow 重编号 '2,3,4,5,6' → "
                + "toQuartz6Field '0 0 9 ? * 2,3,4,5,6' Quartz 合法 → errorCode1 由拒变过（对齐全 6 字段方向，勿修回）")
            .isTrue();
    }

    // ═════════════ effectiveDurable kill-switch + tool_result 文本（CC :117-153）═════════════

    @Test
    @DisplayName("execute: durable=true 且门开 → Persisted where（effectiveDurable=true，CC :151）")
    void execute_durableGateOn_persistsWhere() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-1", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("*/5 * * * *", "run smoke test", true, true)));

        assertThat(r.data())
            .as("CC :151 recurring 文本 + Persisted where")
            .isEqualTo("Scheduled recurring job job-1 (Every 5 minutes). "
                + "Persisted to the scheduled task store. "
                + "Auto-expires after 7 days. Use CronDelete to cancel sooner.");
    }

    @Test
    @DisplayName("execute recurring 双约束 '0 9 1 * 1' → req.cron() = OR 变体 join 串（OPD-Cron-T1-05）")
    void execute_recurringDoubleConstraint_joinVariants() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-or", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("0 9 1 * 1", "run smoke test", true, true)));

        assertThat(r.data()).as("execute 成功路径（data 非空）").isNotNull();
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().cron())
            .as("双约束 recurring 必须透传 OR 变体 join 串（dom 侧 || dow 侧），而非 dom-only 单变体")
            .isEqualTo("0 0 9 1 * ?||0 0 9 ? * 2");
    }

    @Test
    @DisplayName("execute recurring 单约束 '0 9 1 * *' → req.cron() = 单变体（无分隔符）")
    void execute_recurringSingleConstraint_singleVariant() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-1c", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("0 9 1 * *", "run smoke test", true, true)));

        assertThat(r.data()).as("execute 成功路径（data 非空）").isNotNull();
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().cron()).isEqualTo("0 0 9 1 * ?");
    }

    @Test
    @DisplayName("execute: one-shot → 'Scheduled one-shot task ... It will fire once then auto-delete.'（CC :152）")
    void execute_oneShotText() {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-2", ScheduleScope.DURABLE));
        stubNoNameCollision(svc);
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("30 14 28 2 *", "remind me", false, true)));

        assertThat(r.data())
            .as("CC :152 one-shot 文本（humanSchedule 兜底原串）")
            .isEqualTo("Scheduled one-shot task job-2 (30 14 28 2 *). "
                + "Persisted to the scheduled task store. "
                + "It will fire once then auto-delete.");
    }

    @Test
    @DisplayName("execute once → req.cron() 落库用户原始 cron（IMP-F2 / DEL-F2-02 once 改存 cron，非 null）")
    void execute_onceStoresCron() {
        ScheduleService svc = mock(ScheduleService.class);
        // 派生名展示无碰撞 stub（mock 不执行真实方法体，unstubbed nextAvailableName 返回 null →
        //   CronCreateTool.execute:482 !name.equals(baseName) 在 debug 日志下 NPE）
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-once", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("30 14 28 2 *", "remind me", false, true)));

        assertThat(r.data()).isNotNull();
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().cron())
            .as("once 任务 cron 列改存用户原始 cron（CC once 任务亦存 cron 无 null 态；CronList humanSchedule 恒 cronToHuman）")
            .isEqualTo("30 14 28 2 *");
    }

    @Test
    @DisplayName("execute: durable=true 但 cron-durable 门 false → Session-only（kill-switch 可观测）")
    void execute_killSwitchForcesSessionOnly() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-3", ScheduleScope.SESSION));
        // 门 cron-durable=false（agent-trigger-cron=true 保持工具可用）
        CronCreateTool tool = new CronCreateTool(svc, new CronEnabledGates(true, false));
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("*/5 * * * *", "run smoke test", true, true)), ctx);

        assertThat(r.data()).as("SESSION 需 ctx.sessionId，已提供").isNotNull();
        assertThat(r.data())
            .as("CC :120 effectiveDurable=false → where=Session-only（门 false → durable:false 可观测）")
            .isEqualTo("Scheduled recurring job job-3 (Every 5 minutes). "
                + "Session-only (not written to disk, dies when Claude exits). "
                + "Auto-expires after 7 days. Use CronDelete to cancel sooner.");
    }

    @Test
    @DisplayName("execute: create 抛异常 → 异常传播框架层而非 isError ToolResult（CAND-3：对齐 CC call() 无 catch）")
    void execute_createThrows_propagatesToFramework() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class)))
            .thenThrow(new RuntimeException("db down"));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        // CAND-3: 工具层零 catch（对齐 CC CronCreateTool.ts:117-142 call() 无 try/catch），
        // 异常直接抛框架层 StreamingToolExecutor 统一错误面 —— 直连 execute 断言传播而非
        // isError ToolResult；泄漏形态 "CronCreateTool: create failed" 0 命中回归锁。
        assertThatThrownBy(() -> tool.execute(
            call("c1", input("*/5 * * * *", "run smoke test", true, true))))
            .as("CAND-3: create 异常必须传播，不返回 error ToolResult")
            .isInstanceOf(RuntimeException.class)
            .hasMessage("db down")
            .hasMessageNotContaining("CronCreateTool: create failed");
    }

    // ═════════════ 决策#11 · 门控 null 统一 fail-open（CRON-B4-2）═════════════

    @Test
    @DisplayName("isEnabled: cronGates 为 null → true（决策#11 门控 null→开）")
    void isEnabled_gateNull_failOpen() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), null);

        assertThat(tool.isEnabled())
            .as("决策#11: 门 null→开。对齐 CC 无 null 态布尔链（isKairosCronEnabled 恒返回 boolean 且默认 true，"
                + "CronCreateTool.ts:67-69）+ TestJob.java:99 / CronIdleExecutor.java:131 已 fail-open（null→放行），"
                + "CronCreateTool 同化统一。fail-closed 下 null→false 会让工具静默禁用（生产 @ConfigurationProperties "
                + "恒注入非 null，此分支仅防护直接 new 构造/测试路径）")
            .isTrue();
    }

    @Test
    @DisplayName("execute: cronGates 为 null + durable=true → Persisted where（决策#11 durable 门 null fail-open）")
    void execute_durableGateNull_failOpen() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class))).thenReturn(created("job-null", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, null);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("*/5 * * * *", "run smoke test", true, true)));

        assertThat(r.data())
            .as("决策#11: null gates+durable=true → effectiveDurable=true → Persisted where（对齐 CC "
                + "isDurableCronEnabled 默认 true 的 fail-open 语义，非先前 fail-closed 的 Session-only）")
            .isEqualTo("Scheduled recurring job job-null (Every 5 minutes). "
                + "Persisted to the scheduled task store. "
                + "Auto-expires after 7 days. Use CronDelete to cancel sooner.");
    }

    // ═════════════ inputSchema strictObject（CC :28 z.strictObject）═════════════

    @Test
    @DisplayName("inputSchema: additionalProperties=false（拒绝多余字段）")
    void inputSchema_strictObject() {
        CronCreateTool tool = new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);

        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").isBoolean())
            .as("CC CronCreateTool.ts:28 z.strictObject 显式声明 additionalProperties").isTrue();
        // JSON Schema 语义：additionalProperties=false = 拒绝多余字段（strictObject 等价）
        assertThat(schema.path("additionalProperties").asBoolean())
            .as("CC CronCreateTool.ts:28 z.strictObject → additionalProperties:false").isFalse();
        assertThat(schema.path("properties").has("cron")).isTrue();
        assertThat(schema.path("properties").has("prompt")).isTrue();
        assertThat(schema.path("properties").has("recurring")).isTrue();
        assertThat(schema.path("properties").has("durable")).isTrue();
    }

    // ═════════════ IMPL-06/NEW-5 · name 派生碰撞后缀（CC 无 name 字段，同 cron 二次创建均 fire）═════════════

    @Test
    @DisplayName("execute: 派生 name 展示碰撞 → 请求 name = base-2（NEW-5 递增后缀，对齐 CC 无去重）")
    void execute_collidingDerivedName_appendsSuffix() {
        ScheduleService svc = mock(ScheduleService.class);
        // 碰撞在 ScheduleService.nextAvailableName 边界模拟（精确 base stub）：已占用
        // "cron:0 9 * * *" → 返回 -2 后缀。后缀计算本体由 CreateStorageTest 覆盖；
        // 此处钉死工具把派生 base 传给 nextAvailableName 且透传返回值进 create 请求。
        when(svc.nextAvailableName("cron:0 9 * * *")).thenReturn("cron:0 9 * * *-2");
        when(svc.create(any(ScheduleCreateRequest.class)))
            .thenReturn(created("job-sfx", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("0 9 * * *", "run smoke test", true, true)));

        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        verify(svc).nextAvailableName("cron:0 9 * * *");
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().name())
            .as("NEW-5: 展示碰撞 → 递增后缀 -2（展示稳定可读；同 cron 二次创建仍成功，对齐 CC 无去重）")
            .isEqualTo("cron:0 9 * * *-2");
    }

    @Test
    @DisplayName("execute: 派生 name 无碰撞 → 请求 name = base 原样（无后缀）")
    void execute_freeDerivedName_keepsBase() {
        ScheduleService svc = mock(ScheduleService.class);
        stubNoNameCollision(svc);
        when(svc.create(any(ScheduleCreateRequest.class)))
            .thenReturn(created("job-free", ScheduleScope.DURABLE));
        CronCreateTool tool = new CronCreateTool(svc, CronEnabledGates.DEFAULTS);

        ToolResult<?> r = (ToolResult<?>) tool.execute(
            call("c1", input("*/5 * * * *", "run smoke test", true, true)));

        // [REWORK-7] ToolResult.isError() 已删 (IMP-C2) → isError 由 data 文案推导。
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        verify(svc).nextAvailableName("cron:*/5 * * * *");
        ArgumentCaptor<ScheduleCreateRequest> cap = ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc).create(cap.capture());
        assertThat(cap.getValue().name())
            .as("无碰撞 → base 原样（既有 execute 用例同此路径）")
            .isEqualTo("cron:*/5 * * * *");
    }
}
