package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator-preserveMode 2026-11-26] worker 弹窗授权写回 leader 时保留 leader 模式 ·
 * 对齐 CC {@code inProcessRunner.ts:283-288}（worker onAllow 无条件 {@code preserveMode: true}）
 * + {@code REPL.tsx:2858}（{@code mode: preserveMode ? prev.toolPermissionContext.mode : context.mode}）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：sess-908ea539 实证——协调者主会话 bypass，子代理以 DEFAULT
 * 运行（技能占位 bug，已另修）触发目录外 WorkingDir 弹窗，用户点「本次会话允许编辑」带
 * setMode(acceptEdits) 写回主会话覆盖层 → 主会话整体降级、连续弹窗。CC 的 worker 写回路径
 * <b>无条件</b>保留 leader mode（即使 updates 含 setMode），本仓两个等价写回单点
 * （WebSocketPermissionPrompter / ToolPermissionGate）此前整 ctx 写回、无守卫。
 * 本测试锁死两个 helper 的语义：SetMode 过滤 + appState 写回 mode 保留。
 *
 * <p>变异自检：{@code withPreservedMode} 改回恒返回 applied → 用例 3 红；
 * {@code withoutSetMode} 改回恒返回原列表 → 用例 1 红。
 */
@DisplayName("[coordinator-preserveMode] worker 写回保留 leader mode（CC inProcessRunner preserveMode）")
class SessionPermissionOverlayPreserveModeTest {

    private static ToolPermissionContext ctxWithMode(PermissionMode mode) {
        return new ToolPermissionContext(mode, Map.of(), Map.of(), Map.of(),
                Map.of(), true, true, Map.of(), false, false, null);
    }

    private static Map<String, Object> appStateWith(ToolPermissionContext tpc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("toolPermissionContext", tpc);
        return snapshot;
    }

    @Test
    @DisplayName("withoutSetMode：过滤 SetMode、保留 AddRules/AddDirectories（规则类更新照常生效）")
    void withoutSetMode_filtersSetModeKeepsRuleAndDirectoryUpdates() {
        PermissionUpdate addRules = new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.SESSION,
                List.of(new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                        PermissionRuleValue.wholeTool("Read"))),
                PermissionBehavior.ALLOW);
        PermissionUpdate setMode = new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS);
        PermissionUpdate addDirs = new PermissionUpdate.AddDirectories(
                PermissionUpdate.Destination.SESSION, List.of("D:/tmp"));

        List<PermissionUpdate> filtered = SessionPermissionOverlay.withoutSetMode(
                List.of(addRules, setMode, addDirs));

        assertThat(filtered)
                .as("[coordinator-preserveMode] worker 写回：SetMode 被过滤（leader mode 不变），规则/目录保留")
                .containsExactly(addRules, addDirs);
    }

    @Test
    @DisplayName("withoutSetMode：null/空 → 空列表；全 SetMode → 空列表")
    void withoutSetMode_nullEmptyAndAllSetMode() {
        assertThat(SessionPermissionOverlay.withoutSetMode(null)).isEmpty();
        assertThat(SessionPermissionOverlay.withoutSetMode(List.of())).isEmpty();
        assertThat(SessionPermissionOverlay.withoutSetMode(List.of(
                new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.DEFAULT))))
                .isEmpty();
    }

    @Test
    @DisplayName("withPreservedMode：prev=leader BYPASS + applied=worker ACCEPT_EDITS → mode 保留 BYPASS、规则取 applied")
    void withPreservedMode_prevBypassAppliedAcceptEdits_preservesLeaderMode() {
        // WHY: CC REPL.tsx:2858 preserveMode ? prev.toolPermissionContext.mode : context.mode。
        //   worker 的变换 mode（acceptEdits/DEFAULT）不得经 mergeAppStatePermissionRules:1776
        //   「appState mode 胜出」压掉 leader 的 bypass。规则/目录是本次授权的真实产出，取 applied。
        ToolPermissionContext leader = ctxWithMode(PermissionMode.BYPASS_PERMISSIONS);
        Map<PermissionRuleSource, java.util.Set<PermissionRule>> allow =
                new java.util.EnumMap<>(PermissionRuleSource.class);
        allow.put(PermissionRuleSource.SESSION, java.util.Set.of(
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                        PermissionRuleValue.wholeTool("Read"))));
        ToolPermissionContext applied = new ToolPermissionContext(
                PermissionMode.ACCEPT_EDITS, allow, Map.of(), Map.of(),
                Map.of(), true, true, Map.of(), false, false, null);

        ToolPermissionContext result = SessionPermissionOverlay.withPreservedMode(
                applied, appStateWith(leader));

        assertThat(result.mode())
                .as("[coordinator-preserveMode] leader mode=BYPASS 必须保留，不被 worker 的 ACCEPT_EDITS 覆盖")
                .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(result.alwaysAllowRules())
                .as("规则桶取 applied（本次授权的真实产出）")
                .isEqualTo(applied.alwaysAllowRules());
    }

    @Test
    @DisplayName("withPreservedMode：prev 无 toolPermissionContext → 原样返回（无 leader mode 可保，不凭空造）")
    void withPreservedMode_prevWithoutTpc_returnsAppliedUnchanged() {
        ToolPermissionContext applied = ctxWithMode(PermissionMode.ACCEPT_EDITS);
        assertThat(SessionPermissionOverlay.withPreservedMode(applied, new LinkedHashMap<>()))
                .isSameAs(applied);
        assertThat(SessionPermissionOverlay.withPreservedMode(applied, null))
                .isSameAs(applied);
    }

    @Test
    @DisplayName("withPreservedMode：同 mode → 原引用（零新实例）；applied=null → null")
    void withPreservedMode_sameModeReturnsSameReference() {
        ToolPermissionContext applied = ctxWithMode(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(SessionPermissionOverlay.withPreservedMode(
                applied, appStateWith(ctxWithMode(PermissionMode.BYPASS_PERMISSIONS))))
                .isSameAs(applied);
        assertThat(SessionPermissionOverlay.withPreservedMode(null, appStateWith(applied))).isNull();
    }
}