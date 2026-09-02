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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-18] fork skill 工具授权对齐 CC 测试 · CC original:
 * {@code createGetAppStateWithAllowedTools} (forkedAgent.ts:147-171) + SkillTool.ts:227
 * {@code toolUseContext: {...context, getAppState: modifiedGetAppState}}.
 *
 * <p>规则九 (验证意图): fork 技能声明的 allowedTools 必须真实授予 fork 子代理权限上下文 —
 * {@code createForkGetAppStateWithAllowedTools} 包装后的 getAppState 快照的
 * {@code toolPermissionContext.alwaysAllowRules[COMMAND]} 必须含技能 allowedTools 的 whole-tool
 * ALLOW rule, 经 AgentLoopContext.mergeAppStateCommandRules 逐轮并入 fork 子代理 permCtx,
 * 使 fork 子代理内调用技能声明工具不再被权限层阻断 (旧实现 getAllowedTools 在 SubagentExecutor
 * 零命中, 授权完全缺失).
 *
 * <p>RED 依据: 实施前 {@code SubagentExecutor.createForkGetAppStateWithAllowedTools} 不存在
 * (编译失败 = RED); 实施后转 GREEN.
 */
@DisplayName("[P1-18] fork 技能工具授权对齐 CC createGetAppStateWithAllowedTools")
class ForkedSkillToolAuthTest {

    /** 空 COMMAND 桶 + DEFAULT mode 的 ToolPermissionContext · 模拟 fork 子代理 base 快照. */
    private static ToolPermissionContext emptyCommandBucketContext() {
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        allow.put(PermissionRuleSource.COMMAND, new LinkedHashSet<>());
        return new ToolPermissionContext(PermissionMode.DEFAULT, allow, Map.of(), Map.of(),
                Map.of(), false, false, Map.of(), false, false, null);
    }

    /** 含既有 COMMAND 桶 (wholeTool "Bash") 的 ToolPermissionContext · 模拟父会话已授予的 command 规则. */
    private static ToolPermissionContext existingCommandBucketContext() {
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        Set<PermissionRule> commandRules = new LinkedHashSet<>();
        commandRules.add(new PermissionRule(PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Bash")));
        allow.put(PermissionRuleSource.COMMAND, commandRules);
        return new ToolPermissionContext(PermissionMode.DEFAULT, allow, Map.of(), Map.of(),
                Map.of(), false, false, Map.of(), false, false, null);
    }

    private static Map<String, Object> snapshotWith(ToolPermissionContext tpc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("toolPermissionContext", tpc);
        return snapshot;
    }

    private static PermissionRule wholeToolRule(String toolName) {
        return new PermissionRule(PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool(toolName));
    }

    @Test
    @DisplayName("base 快照 COMMAND 桶为空 → 包装后含技能 allowedTools 的 whole-tool ALLOW rule (CC forkedAgent.ts:153-166)")
    void createForkGetAppStateWithAllowedTools_wrapsSkillAllowedToolsIntoCommandBucket() {
        // WHY: CC forkedAgent.ts:153-166 createGetAppStateWithAllowedTools 把 allowedTools 去重合入
        //   appState.toolPermissionContext.alwaysAllowRules.command. fork 子代理 getAppState 被包装后,
        //   AgentLoopContext.mergeAppStateCommandRules 读该快照并入 permCtx → 技能声明工具不再被权限层阻断.
        //   旧实现 SubagentExecutor 无此包装 (getAllowedTools 零命中), fork 授权完全缺失.
        Function<Map<String, Object>, Map<String, Object>> base = prev -> snapshotWith(emptyCommandBucketContext());

        Function<Map<String, Object>, Map<String, Object>> wrapped =
                SubagentExecutor.createForkGetAppStateWithAllowedTools(base, List.of("Bash", "Read"));

        Map<String, Object> snapshot = wrapped.apply(null);
        assertThat(snapshot)
                .as("包装后快照必须保留 toolPermissionContext")
                .containsKey("toolPermissionContext");
        ToolPermissionContext tpc = (ToolPermissionContext) snapshot.get("toolPermissionContext");
        assertThat(tpc.alwaysAllowRules().get(PermissionRuleSource.COMMAND))
                .as("[P1-18] skill allowedTools 必须授予 fork 子代理 COMMAND 桶 (CC forkedAgent.ts:160-166)")
                .containsExactlyInAnyOrder(wholeToolRule("Bash"), wholeToolRule("Read"));
    }

    @Test
    @DisplayName("allowedTools 为空 → 返回原函数 (no-op 守卫, CC forkedAgent.ts:151)")
    void createForkGetAppStateWithAllowedTools_emptyAllowedTools_returnsSameFunction() {
        // WHY: CC forkedAgent.ts:151 no-op 守卫 — allowedTools 空时原样返回 baseGetAppState
        //   (无 allowedTools 技能不引入任何授权包装, 行为与未包装一致).
        Function<Map<String, Object>, Map<String, Object>> base = prev -> snapshotWith(emptyCommandBucketContext());

        Function<Map<String, Object>, Map<String, Object>> wrapped =
                SubagentExecutor.createForkGetAppStateWithAllowedTools(base, List.of());

        assertThat(wrapped)
                .as("[P1-18] allowedTools 空 → 原样返回 base 函数 (CC forkedAgent.ts:151)")
                .isSameAs(base);
    }

    @Test
    @DisplayName("既有 COMMAND 桶与技能 allowedTools 去重合并 (CC forkedAgent.ts:161-165 [...new Set])")
    void createForkGetAppStateWithAllowedTools_mergesDedupWithExistingCommandBucket() {
        // WHY: CC forkedAgent.ts:161-165 [...new Set([...(existing||[]), ...allowedTools])] —
        //   既有 command 规则 (父会话已授) 与技能 allowedTools 必须去重合并, 不能互相覆盖.
        //   否则父会话已授予的 Bash 会被技能授权链冲掉或重复.
        Function<Map<String, Object>, Map<String, Object>> base =
                prev -> snapshotWith(existingCommandBucketContext());

        Function<Map<String, Object>, Map<String, Object>> wrapped =
                SubagentExecutor.createForkGetAppStateWithAllowedTools(base, List.of("Bash", "Read"));

        Map<String, Object> snapshot = wrapped.apply(null);
        ToolPermissionContext tpc = (ToolPermissionContext) snapshot.get("toolPermissionContext");
        Set<PermissionRule> commandRules = tpc.alwaysAllowRules().get(PermissionRuleSource.COMMAND);
        assertThat(commandRules)
                .as("[P1-18] 既有 COMMAND 桶 (Bash) + 技能 allowedTools (Bash, Read) 去重合并, Bash 只出现一次")
                .containsExactlyInAnyOrder(wholeToolRule("Bash"), wholeToolRule("Read"));
        assertThat(commandRules.stream().filter(r -> r.ruleValue().toolName().equals("Bash")).count())
                .as("[P1-18] 去重: Bash whole-tool ALLOW rule 必须唯一 (CC forkedAgent.ts:161-165 [...new Set])")
                .isEqualTo(1);
    }
}
