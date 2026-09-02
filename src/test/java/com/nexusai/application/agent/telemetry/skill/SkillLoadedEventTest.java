package com.nexusai.application.agent.telemetry.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [P2-11] SkillLoadedEvent 遥测测试 · 等价 CC utils/telemetry/skillLoadedEvent.ts:13-39
 * {@code logSkillsLoaded}（tengu_skill_loaded 5 字段）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>每个 prompt 技能必须在会话启动发射 tengu_skill_loaded</b> —— CC main.tsx:281
 *       logSessionTelemetry 在会话启动对 getSkillToolCommands 每个技能发射一条，BQ 侧据此分析
 *       跨会话可用技能分布。若字段缺失（_PROTO_skill_name 路由特权 BQ 列），分析侧拿不到未脱敏
 *       技能名。</li>
 *   <li><b>非 prompt 技能跳过</b> —— CC :26 {@code if (skill.type !== 'prompt') continue}，
 *       local-jsx 等非 prompt 命令不应污染技能加载统计。</li>
 *   <li><b>skill_kind 仅 truthy 发</b> —— CC :34-35 spread 条件，null kind 不发字段。</li>
 *   <li><b>枚举 → CC snake_case 字符串</b> —— skill_source=user/bundled（CommandSource 小写）、
 *       skill_loaded_from=skills/bundled（CommandLoadedFrom 小写），与 CC 直接值一致。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 实施前 SkillLoadedEvent 类不存在（P2-11 未执行，grep tengu_skill_loaded 0 命中）
 * —— 本测试编译即 RED；类落地后全绿。
 */
@DisplayName("[P2-11] SkillLoadedEvent: tengu_skill_loaded 5 字段发射")
class SkillLoadedEventTest {

    @Test
    @DisplayName("每个 prompt 技能发射一条 tengu_skill_loaded（5 字段，CC skillLoadedEvent.ts:28-36）")
    void promptSkills_emitLoadedEventPerSkill() {
        Telemetry telemetry = mock(Telemetry.class);

        Command c1 = new Command();
        c1.setName("web-search");
        c1.setSource(CommandSource.USER);
        c1.setLoadedFrom(CommandLoadedFrom.SKILLS);
        c1.setKind("workflow");

        Command c2 = new Command();
        c2.setName("commit");
        c2.setSource(CommandSource.BUNDLED);
        c2.setLoadedFrom(CommandLoadedFrom.BUNDLED);
        c2.setKind(null);

        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(c1, c2), 8000);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry, times(2)).recordEvent(eq("tengu_skill_loaded"), captor.capture());
        List<Map<String, Object>> all = captor.getAllValues();

        // c1：5 字段全发（kind=workflow truthy）
        Map<String, Object> first = all.get(0);
        assertThat(first).containsEntry("_PROTO_skill_name", "web-search");   // CC :30 特权 BQ 列
        assertThat(first).containsEntry("skill_source", "userSettings");       // CC :31 — SettingSource camelCase（SU-△-2）
        assertThat(first).containsEntry("skill_loaded_from", "skills");        // CC :32
        assertThat(first).containsEntry("skill_budget", 8000);                 // CC :33
        assertThat(first).containsEntry("skill_kind", "workflow");             // CC :34-35

        // c2：kind=null → 不发 skill_kind（CC :34-35 spread 条件）
        Map<String, Object> second = all.get(1);
        assertThat(second).containsEntry("_PROTO_skill_name", "commit");
        assertThat(second).containsEntry("skill_source", "bundled");
        assertThat(second).containsEntry("skill_loaded_from", "bundled");
        assertThat(second).containsEntry("skill_budget", 8000);
        assertThat(second).doesNotContainKey("skill_kind");
    }

    @Test
    @DisplayName("SU-△-2: skill_loaded_from=commands_DEPRECATED 混合大小写 + skill_source=policySettings（CC 精确字符串）")
    void loadedFromAndSource_useCcExactStrings() {
        Telemetry telemetry = mock(Telemetry.class);

        Command legacy = new Command();
        legacy.setName("legacy-cmd");
        legacy.setSource(CommandSource.POLICY_SETTINGS);
        legacy.setLoadedFrom(CommandLoadedFrom.COMMANDS_DEPRECATED);

        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(legacy), 8000);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry, times(1)).recordEvent(eq("tengu_skill_loaded"), captor.capture());
        Map<String, Object> attrs = captor.getValue();
        // CC :31 skill_source = SettingSource camelCase（policySettings，非小写 policy_settings）
        assertThat(attrs).containsEntry("skill_source", "policySettings");
        // CC :32 skill_loaded_from = 'commands_DEPRECATED'（混合大小写，非全小写）
        assertThat(attrs).containsEntry("skill_loaded_from", "commands_DEPRECATED");
    }

    @Test
    @DisplayName("P2-19: SettingSource 细分 skill_source（projectSettings/localSettings/flagSettings 不再塌缩 userSettings）")
    void settingSourceSplit_emitsCcExactStrings() {
        // WHY（P2-19 · 规则九）：CC skillLoadedEvent.ts:28-29 直发 skill.source（SettingSource 5 值），
        //   Java 旧模型把 userSettings/projectSettings/localSettings/flagSettings 全折叠为 USER →
        //   遥测桶恒发 "userSettings"，项目/附加目录技能（CC source='projectSettings'）遥测桶塌缩
        //   （EV-WF7-TU-015/016/017）。P2-19 拆分后按 CommandSource 细分值输出 CC 精确字符串，
        //   项目技能 skill_source 必须为 "projectSettings" 而非 "userSettings"。
        Telemetry telemetry = mock(Telemetry.class);

        Command project = new Command();
        project.setName("proj-skill");
        project.setSource(CommandSource.PROJECT_SETTINGS);
        project.setLoadedFrom(CommandLoadedFrom.SKILLS);

        Command local = new Command();
        local.setName("local-skill");
        local.setSource(CommandSource.LOCAL_SETTINGS);
        local.setLoadedFrom(CommandLoadedFrom.SKILLS);

        Command flag = new Command();
        flag.setName("flag-skill");
        flag.setSource(CommandSource.FLAG_SETTINGS);
        flag.setLoadedFrom(CommandLoadedFrom.SKILLS);

        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(project, local, flag), 8000);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry, times(3)).recordEvent(eq("tengu_skill_loaded"), captor.capture());
        List<Map<String, Object>> all = captor.getAllValues();
        assertThat(all.get(0)).containsEntry("skill_source", "projectSettings"); // CC constants.ts:12
        assertThat(all.get(1)).containsEntry("skill_source", "localSettings");   // CC constants.ts:15
        assertThat(all.get(2)).containsEntry("skill_source", "flagSettings");     // CC constants.ts:18
    }

    @Test
    @DisplayName("P3-29: skill_kind JS truthy 边界 —— 空白串 \" \" 发，空串 \"\" 与 null 不发（CC :34-35）")
    void skillKind_whitespaceTruthyBoundary() {
        // WHY（P3-29 · 规则九）：CC skillLoadedEvent.ts:34-35 `...(skill.kind && {...})` 为 JS truthy 判断 ——
        //   空白串 " " 是 truthy（仍发射），空串 "" 与 null/undefined 是 falsy（不发射）。Java 原 `!isBlank()`
        //   会把 " " 排除，导致遥测桶与 CC 分裂（EV-WF7-TU-003，OPD-WF7-2）。
        Telemetry telemetry = mock(Telemetry.class);

        Command spaceKind = new Command();
        spaceKind.setName("space-skill");
        spaceKind.setKind(" ");                       // JS truthy → 应发射 skill_kind

        Command emptyKind = new Command();
        emptyKind.setName("empty-skill");
        emptyKind.setKind("");                        // JS falsy（""）→ 不应发射

        Command nullKind = new Command();
        nullKind.setName("null-skill");
        nullKind.setKind(null);                       // JS falsy → 不应发射

        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(spaceKind, emptyKind, nullKind), 8000);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry, times(3)).recordEvent(eq("tengu_skill_loaded"), captor.capture());
        List<Map<String, Object>> all = captor.getAllValues();
        assertThat(all.get(0)).containsEntry("skill_kind", " ");   // 空白串 truthy 仍发
        assertThat(all.get(1)).doesNotContainKey("skill_kind");    // "" falsy 不发
        assertThat(all.get(2)).doesNotContainKey("skill_kind");    // null falsy 不发
    }

    @Test
    @DisplayName("非 prompt 技能跳过 / null telemetry no-op / 空列表 no-op（CC :26 + best-effort）")
    void nonPromptAndNullSafety() {
        Telemetry telemetry = mock(Telemetry.class);

        Command nonPrompt = new Command();
        nonPrompt.setName("local-jsx");
        nonPrompt.setType("local-jsx");
        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(nonPrompt), 8000);
        verify(telemetry, never()).recordEvent(anyString(), any());

        // null telemetry → no-op（对齐 CC logEvent best-effort，不破坏执行链）
        assertThatCode(() -> SkillLoadedEvent.logSkillsLoaded(null, List.of(nonPrompt), 8000))
            .doesNotThrowAnyException();

        // 空列表 → no-op
        SkillLoadedEvent.logSkillsLoaded(telemetry, List.of(), 8000);
        verify(telemetry, never()).recordEvent(anyString(), any());
    }
}
