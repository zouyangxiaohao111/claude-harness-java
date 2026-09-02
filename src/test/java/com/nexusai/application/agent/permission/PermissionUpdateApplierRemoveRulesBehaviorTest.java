package com.nexusai.application.agent.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RemoveRules 单桶删除语义测试 · 对齐 CC {@code applyPermissionUpdate} case 'removeRules'
 * （{@code PermissionUpdate.ts:139-169}）。
 *
 * <p>WHY 该行为重要：CC 的 removeRules 按 {@code update.behavior} 选单桶（{@code ruleKind}）
 * 后仅 {@code filter context[ruleKind][destination]}，<b>不跨桶删除</b>。旧 Java 实现跨 3 桶
 * （allow/deny/ask）一并删除同一 toolName+ruleContent 的规则——若用户只想移除一条 allow 规则
 * （如 {@code Bash(npm publish)}），却连带删掉了 deny/ask 桶里同值规则，会<b>削弱安全</b>
 * （deny 保护被误删）或<b>破坏弹窗意图</b>（ask 弹窗规则被误删）。本测试断言：
 * behavior=ALLOW 只删 allow 桶，deny/ask 桶原样保留。
 */
@DisplayName("RemoveRules 单桶删除（CC PermissionUpdate.ts:139-169）")
class PermissionUpdateApplierRemoveRulesBehaviorTest {

    private final PermissionUpdateApplier applier = new PermissionUpdateApplier();

    private static PermissionRule rule(
            PermissionRuleSource source, PermissionBehavior behavior, String tool, String content) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(tool, content));
    }

    /** 构造 allow/deny/ask 三桶各含一条同 toolName+ruleContent 规则的 ctx。 */
    private static ToolPermissionContext ctxWithAllThreeBuckets(
            PermissionRuleSource source, String tool, String content) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> ask = new EnumMap<>(PermissionRuleSource.class);
        allow.put(source, new LinkedHashSet<>(List.of(rule(source, PermissionBehavior.ALLOW, tool, content))));
        deny.put(source, new LinkedHashSet<>(List.of(rule(source, PermissionBehavior.DENY, tool, content))));
        ask.put(source, new LinkedHashSet<>(List.of(rule(source, PermissionBehavior.ASK, tool, content))));
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, deny, ask, Map.of());
    }

    @Test
    @DisplayName("behavior=ALLOW 只删 allow 桶，deny/ask 桶不变")
    void removeRules_allow_onlyRemovesAllowBucket() {
        PermissionRuleSource source = PermissionRuleSource.USER_SETTINGS;
        ToolPermissionContext ctx = ctxWithAllThreeBuckets(source, "Bash", "npm publish");

        ToolPermissionContext result = applier.apply(
                new PermissionUpdate.RemoveRules(
                        PermissionUpdate.Destination.USER_SETTINGS,
                        List.of(rule(source, PermissionBehavior.ALLOW, "Bash", "npm publish")),
                        PermissionBehavior.ALLOW),
                ctx);

        assertThat(result.alwaysAllowRules().get(source))
                .as("allow 桶同值规则必须被删除（behavior=ALLOW 选 allow 桶）")
                .isEmpty();
        assertThat(result.alwaysDenyRules().get(source))
                .as("deny 桶不受 behavior=ALLOW 删除影响（CC 单桶语义，不跨桶）")
                .hasSize(1);
        assertThat(result.alwaysAskRules().get(source))
                .as("ask 桶不受 behavior=ALLOW 删除影响（CC 单桶语义，不跨桶）")
                .hasSize(1);
    }

    @Test
    @DisplayName("behavior=DENY 只删 deny 桶，allow/ask 桶不变")
    void removeRules_deny_onlyRemovesDenyBucket() {
        PermissionRuleSource source = PermissionRuleSource.USER_SETTINGS;
        ToolPermissionContext ctx = ctxWithAllThreeBuckets(source, "Bash", "rm -rf");

        ToolPermissionContext result = applier.apply(
                new PermissionUpdate.RemoveRules(
                        PermissionUpdate.Destination.USER_SETTINGS,
                        List.of(rule(source, PermissionBehavior.DENY, "Bash", "rm -rf")),
                        PermissionBehavior.DENY),
                ctx);

        assertThat(result.alwaysDenyRules().get(source)).isEmpty();
        assertThat(result.alwaysAllowRules().get(source)).hasSize(1);
        assertThat(result.alwaysAskRules().get(source)).hasSize(1);
    }

    @Test
    @DisplayName("behavior=ASK 只删 ask 桶，allow/deny 桶不变")
    void removeRules_ask_onlyRemovesAskBucket() {
        PermissionRuleSource source = PermissionRuleSource.USER_SETTINGS;
        ToolPermissionContext ctx = ctxWithAllThreeBuckets(source, "Bash", "git push");

        ToolPermissionContext result = applier.apply(
                new PermissionUpdate.RemoveRules(
                        PermissionUpdate.Destination.USER_SETTINGS,
                        List.of(rule(source, PermissionBehavior.ASK, "Bash", "git push")),
                        PermissionBehavior.ASK),
                ctx);

        assertThat(result.alwaysAskRules().get(source)).isEmpty();
        assertThat(result.alwaysAllowRules().get(source)).hasSize(1);
        assertThat(result.alwaysDenyRules().get(source)).hasSize(1);
    }

    // ── AddDirectories destination→source 映射（OPD-WF1-01-Q1）────────────────

    @Test
    @DisplayName("AddDirectories source = mapDestination(destination)（CC PermissionUpdate.ts:130-131 source=update.destination）")
    void addDirectories_mapsDestinationToSource() {
        ToolPermissionContext ctx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());

        ToolPermissionContext result = applier.apply(
                new PermissionUpdate.AddDirectories(
                        PermissionUpdate.Destination.PROJECT_SETTINGS,
                        List.of("/workspace/proj")),
                ctx);

        AdditionalWorkingDirectory dir = result.additionalWorkingDirectories().get("/workspace/proj");
        assertThat(dir)
                .as("addDirectories 必须把路径加入 additionalWorkingDirectories（CC PermissionUpdate.ts:126-132）")
                .isNotNull();
        assertThat(dir.source())
                .as("CC PermissionUpdate.ts:130-131 —— addDirectories source=update.destination"
                        + "（Java 经 mapDestination 映射，非恒 SESSION）；"
                        + "WORKING_DIRECTORY_SOURCE 语义（types/permissions.ts:432）决定该目录归属")
                .isEqualTo(PermissionRuleSource.PROJECT_SETTINGS);
    }

    @Test
    @DisplayName("AddDirectories 各 destination 均映射到对应 source（USER_SETTINGS/CLI_ARG/SESSION）")
    void addDirectories_mapsEveryDestination() {
        ToolPermissionContext base = ToolPermissionContext.of(
                PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());

        AdditionalWorkingDirectory userDir = applier.apply(
                        new PermissionUpdate.AddDirectories(
                                PermissionUpdate.Destination.USER_SETTINGS, List.of("/u/a")),
                        base)
                .additionalWorkingDirectories().get("/u/a");
        assertThat(userDir.source()).isEqualTo(PermissionRuleSource.USER_SETTINGS);

        AdditionalWorkingDirectory cliDir = applier.apply(
                        new PermissionUpdate.AddDirectories(
                                PermissionUpdate.Destination.CLI_ARG, List.of("/u/b")),
                        base)
                .additionalWorkingDirectories().get("/u/b");
        assertThat(cliDir.source()).isEqualTo(PermissionRuleSource.CLI_ARG);

        AdditionalWorkingDirectory sessionDir = applier.apply(
                        new PermissionUpdate.AddDirectories(
                                PermissionUpdate.Destination.SESSION, List.of("/u/c")),
                        base)
                .additionalWorkingDirectories().get("/u/c");
        assertThat(sessionDir.source()).isEqualTo(PermissionRuleSource.SESSION);
    }
}
