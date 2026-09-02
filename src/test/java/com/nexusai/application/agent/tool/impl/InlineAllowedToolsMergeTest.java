package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-5] inline 侧 allowedTools → alwaysAllowRules.command 合并 · 对齐 CC SkillTool.ts:790-801
 * （{@code [...new Set([...(existing||[]), ...allowedTools])]} 去重合入 command 桶）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>schedule/update-config 的 Command.allowedTools 必须在 inline 调用时真实落进
 *       appState.toolPermissionContext.alwaysAllowRules.command</b>——这是 P2-5 目标验收的后半段
 *       （"inline 调用时 alwaysAllowRules.command 含对应 whole-tool ALLOW rule"）。mergeAllowedToolsIntoAppState
 *       是 inline 侧 contextModifier（SkillToolImpl.buildContextModifier :1368-1383）与 fork 侧
 *       （SubagentExecutor.createForkGetAppStateWithAllowedTools，SkillToolImpl.java:1418-1421 同函数复用）
 *       共用的唯一合并实现；fork 侧已由 ForkedSkillToolAuthTest 经 SubagentExecutor 覆盖，inline 侧
 *       无直接单测。用 schedule/update-config 的精确工具清单直接注入本函数，断言 COMMAND 桶产出
 *       whole-tool ALLOW rule，即锁死 P2-5 验收的运行时侧。</li>
 *   <li><b>去重语义</b>——既有 COMMAND 桶与技能 allowedTools 必须去重合并（CC SkillTool.ts:795-799
 *       {@code new Set}），不得互相覆盖/重复。</li>
 * </ol>
 */
@DisplayName("[P2-5] inline 侧 allowedTools → alwaysAllowRules.command（mergeAllowedToolsIntoAppState）")
class InlineAllowedToolsMergeTest {

    private static Map<String, Object> snapshotWith(ToolPermissionContext tpc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("toolPermissionContext", tpc);
        return snapshot;
    }

    private static ToolPermissionContext emptyCommandBucketContext() {
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        allow.put(PermissionRuleSource.COMMAND, new LinkedHashSet<>());
        return new ToolPermissionContext(PermissionMode.DEFAULT, allow, Map.of(), Map.of(),
                Map.of(), false, false, Map.of(), false, false, null);
    }

    private static PermissionRule wholeToolRule(String toolName) {
        return new PermissionRule(PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool(toolName));
    }

    @Test
    @DisplayName("schedule allowedTools=[RemoteTrigger,AskUserQuestion] → COMMAND 桶含对应 whole-tool ALLOW rule（CC SkillTool.ts:790-801）")
    void scheduleAllowedTools_mergeIntoAppState_commandBucketGainsWholeToolAllowRules() {
        // WHY: scheduleRemoteAgents.ts:335 allowedTools=[REMOTE_TRIGGER_TOOL_NAME, ASK_USER_QUESTION_TOOL_NAME]
        //   → inline contextModifier（SkillTool.ts:775-806）把 allowedTools 去重合入
        //   appState.toolPermissionContext.alwaysAllowRules.command。若透传断链（E8 根因回归），
        //   schedule 技能声明的两个工具在会话权限层零授权，模型调用 RemoteTrigger/AskUserQuestion
        //   会被权限层阻断。断言 COMMAND 桶含两个 whole-tool ALLOW rule 即锁死运行时授权。
        Map<String, Object> merged = SkillToolImpl.mergeAllowedToolsIntoAppState(
                null, List.of("RemoteTrigger", "AskUserQuestion"));

        ToolPermissionContext tpc = (ToolPermissionContext) merged.get("toolPermissionContext");
        assertThat(tpc).as("appState 无既有 toolPermissionContext → 构建最小 ToolPermissionContext").isNotNull();
        assertThat(tpc.alwaysAllowRules().get(PermissionRuleSource.COMMAND))
                .as("[P2-5] schedule 声明工具必须授予 COMMAND 桶 whole-tool ALLOW rule（CC SkillTool.ts:794-799）")
                .containsExactlyInAnyOrder(wholeToolRule("RemoteTrigger"), wholeToolRule("AskUserQuestion"));
    }

    @Test
    @DisplayName("update-config allowedTools=[Read] → COMMAND 桶含 Read whole-tool ALLOW rule（CC updateConfig.ts:450）")
    void updateConfigAllowedTools_mergeIntoAppState_commandBucketGainsReadAllowRule() {
        // WHY: updateConfig.ts:450 allowedTools: ['Read'] → 同上，Read 必须授予 COMMAND 桶。
        //   update-config 只允许 Read（禁止 Edit/Write/Bash 由模型直接调用，用户必须手动确认），
        //   若 allowedTools 丢失，Read 也需权限询问，破坏"先读再写"引导流程。
        Map<String, Object> merged = SkillToolImpl.mergeAllowedToolsIntoAppState(
                null, List.of("Read"));

        ToolPermissionContext tpc = (ToolPermissionContext) merged.get("toolPermissionContext");
        assertThat(tpc.alwaysAllowRules().get(PermissionRuleSource.COMMAND))
                .as("[P2-5] update-config 声明工具 Read 必须授予 COMMAND 桶（CC updateConfig.ts:450）")
                .containsExactly(wholeToolRule("Read"));
    }

    @Test
    @DisplayName("既有 COMMAND 桶与技能 allowedTools 去重合并（CC SkillTool.ts:795-799 [...new Set]）")
    void mergeIntoAppState_dedupsWithExistingCommandBucket() {
        // WHY: 会话已授予的 command 规则（如父链已授 Read）与技能 allowedTools 必须去重合并，
        //   不能互相覆盖或重复。CC SkillTool.ts:795-799 [...new Set([...(existing||[]), ...allowedTools])]。
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        Set<PermissionRule> existing = new LinkedHashSet<>();
        existing.add(wholeToolRule("Read"));
        allow.put(PermissionRuleSource.COMMAND, existing);
        ToolPermissionContext base = new ToolPermissionContext(PermissionMode.DEFAULT, allow, Map.of(), Map.of(),
                Map.of(), false, false, Map.of(), false, false, null);

        Map<String, Object> merged = SkillToolImpl.mergeAllowedToolsIntoAppState(
                snapshotWith(base), List.of("Read", "RemoteTrigger"));

        ToolPermissionContext tpc = (ToolPermissionContext) merged.get("toolPermissionContext");
        Set<PermissionRule> commandRules = tpc.alwaysAllowRules().get(PermissionRuleSource.COMMAND);
        assertThat(commandRules)
                .as("[P2-5] 既有 Read + 技能 [Read, RemoteTrigger] 去重合并，Read 不重复")
                .containsExactlyInAnyOrder(wholeToolRule("Read"), wholeToolRule("RemoteTrigger"));
        assertThat(commandRules.stream().filter(r -> r.ruleValue().toolName().equals("Read")).count())
                .as("[P2-5] 去重：Read whole-tool ALLOW rule 必须唯一（CC SkillTool.ts:795-799 new Set）")
                .isEqualTo(1);
    }
}
