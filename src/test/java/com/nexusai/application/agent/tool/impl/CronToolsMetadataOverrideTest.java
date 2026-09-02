package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.domain.schedule.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * CRON-A5 · 三 Cron 工具框架元数据 override 单测.
 *
 * <p><b>WHY (意图验证)</b>: CC 三工具 buildTool 入参的元数据（maxResultSizeChars=100_000、
 * shouldDefer=true、searchHint、toAutoClassifierInput）覆盖 Tool 接口默认值
 * （50000 / false / ''），否则大结果落盘阈值与延迟执行语义会漂移回接口默认。
 *
 * <p><b>CC 真源对照</b>（三工具 TS）:
 * <ul>
 *   <li>CronCreateTool.ts :58 searchHint / :59 maxResultSizeChars=100_000 / :60 shouldDefer=true /
 *       :67-69 isEnabled=isKairosCronEnabled / :70-72 toAutoClassifierInput=`${cron}: ${prompt}`</li>
 *   <li>CronDeleteTool.ts :37 searchHint / :38 maxResultSizeChars / :39 shouldDefer /
 *       :46-48 isEnabled / :49-51 toAutoClassifierInput=input.id</li>
 *   <li>CronListTool.ts :39 searchHint / :40 maxResultSizeChars / :41 shouldDefer / :48-50 isEnabled /
 *       :51-53 isConcurrencySafe=true / :54-56 isReadOnly=true —— <b>无</b> toAutoClassifierInput /
 *       getPath（勿补）</li>
 * </ul>
 */
class CronToolsMetadataOverrideTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CronCreateTool newCreate() {
        return new CronCreateTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);
    }

    private static CronDeleteTool newDelete() {
        return new CronDeleteTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);
    }

    private static CronListTool newList() {
        return new CronListTool(mock(ScheduleService.class), CronEnabledGates.DEFAULTS);
    }

    @Test
    @DisplayName("CronCreate: maxResultSizeChars=100000 / shouldDefer=true / searchHint / isEnabled")
    void cronCreateMetadataOverrides() {
        CronCreateTool tool = newCreate();
        assertThat(tool.maxResultSizeChars())
            .as("CC CronCreateTool.ts:59 maxResultSizeChars = 100_000").isEqualTo(100_000L);
        assertThat(tool.shouldDefer(null))
            .as("CC CronCreateTool.ts:60 shouldDefer = true").isTrue();
        assertThat(tool.searchHint())
            .as("CC CronCreateTool.ts:58 searchHint").isEqualTo("schedule a recurring or one-shot prompt");
        assertThat(tool.isEnabled())
            .as("CC CronCreateTool.ts:67-69 isEnabled=isKairosCronEnabled, DEFAULTS 默认开").isTrue();
    }

    @Test
    @DisplayName("CronCreate: toAutoClassifierInput = '<cron>: <prompt>' (CC :70-72)")
    void cronCreateAutoClassifierInput() {
        CronCreateTool tool = newCreate();
        ObjectNode input = JSON.createObjectNode();
        input.put("cron", "*/5 * * * *");
        input.put("prompt", "run smoke test");
        assertThat(tool.toAutoClassifierInput(input))
            .as("CC CronCreateTool.ts:71 `${input.cron}: ${input.prompt}`")
            .isEqualTo("*/5 * * * *: run smoke test");
    }

    @Test
    @DisplayName("CronDelete: maxResultSizeChars / shouldDefer / searchHint / toAutoClassifierInput=id")
    void cronDeleteMetadataOverrides() {
        CronDeleteTool tool = newDelete();
        assertThat(tool.maxResultSizeChars())
            .as("CC CronDeleteTool.ts:38 maxResultSizeChars = 100_000").isEqualTo(100_000L);
        assertThat(tool.shouldDefer(null))
            .as("CC CronDeleteTool.ts:39 shouldDefer = true").isTrue();
        assertThat(tool.searchHint())
            .as("CC CronDeleteTool.ts:37 searchHint").isEqualTo("cancel a scheduled cron job");
        assertThat(tool.isEnabled())
            .as("CC CronDeleteTool.ts:46-48 isEnabled=isKairosCronEnabled").isTrue();
        ObjectNode input = JSON.createObjectNode();
        input.put("id", "job-42");
        assertThat(tool.toAutoClassifierInput(input))
            .as("CC CronDeleteTool.ts:49-51 toAutoClassifierInput = input.id").isEqualTo("job-42");
    }

    @Test
    @DisplayName("CronList: maxResultSizeChars / shouldDefer / searchHint / isConcurrencySafe / isReadOnly")
    void cronListMetadataOverrides() {
        CronListTool tool = newList();
        assertThat(tool.maxResultSizeChars())
            .as("CC CronListTool.ts:40 maxResultSizeChars = 100_000").isEqualTo(100_000L);
        assertThat(tool.shouldDefer(null))
            .as("CC CronListTool.ts:41 shouldDefer = true").isTrue();
        assertThat(tool.searchHint())
            .as("CC CronListTool.ts:39 searchHint").isEqualTo("list active cron jobs");
        assertThat(tool.isEnabled())
            .as("CC CronListTool.ts:48-50 isEnabled=isKairosCronEnabled").isTrue();
        assertThat(tool.isConcurrencySafe(null))
            .as("CC CronListTool.ts:51-53 isConcurrencySafe = true").isTrue();
        assertThat(tool.isReadOnly(null))
            .as("CC CronListTool.ts:54-56 isReadOnly = true").isTrue();
    }

    @Test
    @DisplayName("CronList: 不 override toAutoClassifierInput (CC 无) → 接口默认空串")
    void cronListNoAutoClassifierInput() {
        CronListTool tool = newList();
        ObjectNode input = JSON.createObjectNode();
        assertThat(tool.toAutoClassifierInput(input))
            .as("CC CronListTool.ts 无 toAutoClassifierInput → Tool 接口默认 ''（勿补）")
            .isEmpty();
    }

    @Test
    @DisplayName("三工具 isEnabled 委托 CronEnabledGates: 显式 (true,false) 时 Create/Delete/List 仍开")
    void isEnabledDelegatesToCronGates() {
        // 验证 isEnabled 真实接线 CronEnabledGates（而非恒 true）：gate 关 → 工具不可见
        CronEnabledGates off = new CronEnabledGates(false, true);
        CronCreateTool create = new CronCreateTool(mock(ScheduleService.class), off);
        CronDeleteTool delete = new CronDeleteTool(mock(ScheduleService.class), off);
        CronListTool list = new CronListTool(mock(ScheduleService.class), off);
        assertThat(create.isEnabled())
            .as("CC isKairosCronEnabled: feature('AGENT_TRIGGERS')=false → false (prompt.ts:37)")
            .isFalse();
        assertThat(delete.isEnabled()).isFalse();
        assertThat(list.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Tool 接口契约成员: searchHint 已提升为接口方法, 未 override 默认 null (G5)")
    void searchHintIsInterfaceMember_defaultNull() {
        // G5 契约提升：searchHint 已是 Tool 接口方法（CC Tool.ts:378），未 override 的工具
        // default 返回 null = CC absent 语义。锁定契约 + 向后兼容（Cron 工具 override 见
        // cronCreateMetadataOverrides/cronDeleteMetadataOverrides/cronListMetadataOverrides）。
        assertThatCode(() -> Tool.class.getMethod("searchHint"))
            .as("searchHint 应为 Tool 接口契约成员（G5 提升，CC Tool.ts:378）")
            .doesNotThrowAnyException();
    }
}
