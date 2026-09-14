package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [S1-T14 · 缺值策略 (a)] CronDeleteTool 的 cron 所有权判据在**身份缺值**时必须 fail loud。
 *
 * <p><b>WHY（规则九 · 意图）</b>：CC {@code CronDeleteTool.ts:71-79} 的所有权判据是
 * {@code if (ctx && task.agentId !== ctx.agentId) reject}。本仓对应的两个「缺值」形态必须区分：
 * <ul>
 *   <li><b>identity == null</b>（调用方本就不是 teammate，例如 leader 清理）⇒ 按 CC 的
 *       {@code ctx &&} 短路<b>放行</b>——这是 CC 语义，不是缺陷；</li>
 *   <li><b>identity 存在但 {@code agentId} 缺失</b> ⇒ CC 的 {@code TeammateIdentity.agentId} 是
 *       <b>非可选</b>字段（types.ts:14 {@code agentId: string}）⇒ 这是**载体缺陷**：所有权
 *       <b>不可判定</b>。此时若静默落进 {@code Objects.equals(null, ownerAgentId)}，当任务 owner
 *       也为 null 时两端"相等" ⇒ <b>放行</b>，等于让一个身份残缺的 teammate 删掉任意 cron
 *       （静默失效）。本仓铁律：本该有却没有 ⇒ 拒绝并留痕（禁静默降级）。</li>
 * </ul>
 *
 * <p>本测试锁 3 件事：
 * <ol>
 *   <li><b>缺 agentId + 有 owner</b> ⇒ 拒绝（errorCode 2）；</li>
 *   <li><b>缺 agentId + owner 也 null</b>（最容易被静默"相等"放行的组合）⇒ <b>仍拒绝</b>；</li>
 *   <li>缺 agentId 被拒时<b>留下 ≥WARN 日志</b>（可观测；禁只 DEBUG）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：把新增的 agentId 缺失分支改成 {@code return ValidationResult.pass()}
 * ⇒ 断言 1/2 红；把 warn 降到 debug ⇒ 断言 3 红。
 */
class CronDeleteToolOwnershipFailLoudTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ScheduleDto dto(String id, String agentId) {
        return new ScheduleDto(
            id, "job " + id, ScheduleKind.cron, "0 */5 * * * *",
            null, null, "echo hi", null, null, null,
            ScheduleScope.SESSION, null, agentId, null);
    }

    private static ToolUseBlock call(String jobId) {
        ObjectNode input = JSON.createObjectNode();
        input.put("id", jobId);
        return new ToolUseBlock("c1", "CronDelete", input);
    }

    /** 带「残缺 teammate 身份」（agentId == null）的 TUC。 */
    private static ToolUseContext incompleteIdentityTuc() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-s1-t14")
            .withTeammateIdentity(
                new TeammateIdentity(null, "researcher", "team-a", null, false, "leader-sess"));
    }

    private static CronDeleteTool toolWith(ScheduleDto... tasks) {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.listAll()).thenReturn(List.of(tasks));
        return new CronDeleteTool(svc, CronEnabledGates.DEFAULTS);
    }

    private static ListAppender<ILoggingEvent> captureWarn() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CronDeleteTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        return appender;
    }

    private static void stopCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CronDeleteTool.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言1: teammate 身份缺 agentId + 任务有 owner ⇒ fail loud 拒绝（不静默放行）")
    void missingAgentId_withOwner_isRejected() {
        CronDeleteTool tool = toolWith(dto("job-1", "agent-B"));

        Tool.ValidationResult r = tool.validateInput(call("job-1").input(), incompleteIdentityTuc());

        assertThat(r.ok())
            .as("⭐ 所有权不可判定 ⇒ 必须拒绝（不得静默落到 Objects.equals 后放行）")
            .isFalse();
        assertThat(r.errorCode()).isEqualTo("2");
        assertThat(r.message()).contains("job-1");
    }

    @Test
    @DisplayName("断言2: teammate 身份缺 agentId + 任务 owner 也为 null ⇒ 仍拒绝（堵死「null==null 相等」静默放行）")
    void missingAgentId_withNullOwner_isStillRejected() {
        CronDeleteTool tool = toolWith(dto("job-2", null));

        Tool.ValidationResult r = tool.validateInput(call("job-2").input(), incompleteIdentityTuc());

        assertThat(r.ok())
            .as("⭐ owner 也为 null 时 Objects.equals(null,null)==true 会放行——必须在该点之前拦住")
            .isFalse();
        assertThat(r.errorCode()).isEqualTo("2");
    }

    @Test
    @DisplayName("断言3: 缺值拒绝时必须留下 ≥WARN 日志（禁只 DEBUG）")
    void missingAgentId_leavesWarnLog() {
        CronDeleteTool tool = toolWith(dto("job-3", "agent-B"));

        ListAppender<ILoggingEvent> logs = captureWarn();
        try {
            tool.validateInput(call("job-3").input(), incompleteIdentityTuc());
        } finally {
            stopCapture(logs);
        }

        assertThat(logs.list)
            .as("缺值拒绝必须 ≥WARN 可观测")
            .anySatisfy(e -> {
                assertThat(e.getLevel().toInt())
                    .as("日志级别必须 ≥WARN")
                    .isGreaterThanOrEqualTo(Level.WARN.toInt());
                assertThat(e.getFormattedMessage()).contains("缺值策略(a)");
            });
    }

    // ── 对照：CC 原样放行方向（identity == null = 主会话/leader）必须保持 ──

    @Test
    @DisplayName("对照: identity == null（非 teammate，如 leader 清理）⇒ 按 CC `if (ctx && ...)` 短路放行")
    void noTeammateIdentity_followsCcShortCircuit() {
        CronDeleteTool tool = toolWith(dto("job-4", "agent-B"));

        Tool.ValidationResult r = tool.validateInput(
            call("job-4").input(), ToolUseContext.of(UUID.randomUUID(), "sess-s1-t14"));

        assertThat(r.ok())
            .as("CC CronDeleteTool.ts:72 `if (ctx && ...)` ⇒ 非 teammate 不受所有权限制")
            .isTrue();
    }

    @Test
    @DisplayName("对照: teammate 身份完整 + 任务属自己 ⇒ 放行（正方向，证明守卫不是恒拒）")
    void completeIdentity_ownTask_passes() {
        CronDeleteTool tool = toolWith(dto("job-5", "agent-A"));
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-s1-t14")
            .withTeammateIdentity(
                new TeammateIdentity("agent-A", "peer", "team-a", null, false, "leader-sess"));

        assertThat(tool.validateInput(call("job-5").input(), tuc).ok()).isTrue();
    }
}
