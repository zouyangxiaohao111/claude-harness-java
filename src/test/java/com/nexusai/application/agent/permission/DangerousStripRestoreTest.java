package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S04] 危险剥离 stash/restore 恢复路径 · 对齐 CC
 * {@code stripDangerousPermissionsForAutoMode} / {@code restoreDangerousPermissions}
 * （{@code permissionSetup.ts:510-553 / :561-579}）。
 *
 * <p>覆盖 Session S04 验收标准：
 * <ol>
 *   <li>auto 模式构建上下文时剥离并 stash（Detector 级 + 两个 ContextBuilder 级）</li>
 *   <li>退出 auto（restore）后剥离规则完整恢复</li>
 *   <li>二次 restore no-op（幂等）</li>
 *   <li>非 auto 模式不剥离（builder 级 mode 守卫）</li>
 *   <li>CC 源过滤语义：非可持久化 source（policy/flag/command）不剥离、不进 stash</li>
 * </ol>
 *
 * <p>装配：Detector 级用真实 {@link PermissionUpdateApplier}（CC applyPermissionUpdate 等价）；
 * builder 级用 {@link ApplicationContextRunner} 真 bean 装配（非纯 mock）。
 */
class DangerousStripRestoreTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** 测试可控: loader 返回的规则集 (每次 build 重新读取). */
    static volatile List<PermissionRule> loaderRules = List.of();

    /** 测试可控: auto mode 开关. */
    static volatile boolean autoModeOn = false;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(BuilderBeansConfig.class);

    @Configuration
    static class BuilderBeansConfig {
        @Bean
        DangerousPatternDetector dangerousPatternDetector() {
            return new DangerousPatternDetector(new PermissionUpdateApplier());
        }

        @Bean
        AutoModeGate autoModeGate() {
            return new AutoModeGate(autoModeOn);
        }

        @Bean
        PermissionContextBuilder permissionContextBuilder() {
            return new PermissionContextBuilder(List.of(new StubLoader()));
        }
    }

    /** 真 loader 桩: 返回测试可控规则 (source = USER_SETTINGS). */
    static final class StubLoader implements PermissionSourceLoader {
        @Override public PermissionRuleSource source() { return PermissionRuleSource.USER_SETTINGS; }
        @Override public List<PermissionRule> load() { return loaderRules; }
    }

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
                                       String toolName, String content) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(toolName, content));
    }

    private static ToolPermissionContext ctxWith(PermissionRule... rules) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        for (PermissionRule r : rules) {
            allow.computeIfAbsent(r.source(), k -> new LinkedHashSet<>()).add(r);
        }
        return new ToolPermissionContext(
            PermissionMode.AUTO, allow, Map.of(), Map.of(), Map.of(),
            true, true, Map.of(), false, false, null);
    }

    private static ToolPermissionContext ctxWithStash(Map<PermissionRuleSource, Set<PermissionRule>> stash,
                                                      PermissionRule... rules) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        for (PermissionRule r : rules) {
            allow.computeIfAbsent(r.source(), k -> new LinkedHashSet<>()).add(r);
        }
        return new ToolPermissionContext(
            PermissionMode.AUTO, allow, Map.of(), Map.of(), Map.of(),
            true, true, stash, false, false, null);
    }

    /** 上下文标志位可控的构造助手（S04 返工：CC spread 标志位保真验证）。 */
    private static ToolPermissionContext ctxWithFlags(boolean avoid, boolean await,
                                                      PermissionMode prePlan,
                                                      PermissionRule... rules) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        for (PermissionRule r : rules) {
            allow.computeIfAbsent(r.source(), k -> new LinkedHashSet<>()).add(r);
        }
        return new ToolPermissionContext(
            PermissionMode.AUTO, allow, Map.of(), Map.of(), Map.of(),
            true, true, Map.of(), avoid, await, prePlan);
    }

    /** S09 对齐 CC isDangerousBashPermission（permissionSetup.ts:94-147）的精确形态 + 全小写判定。 */
    private static boolean isDangerousBash(PermissionRule r) {
        if (!"Bash".equals(r.ruleValue().toolName())) {
            return false;
        }
        String content = r.ruleValue().ruleContent();
        if (content == null) {
            return true;
        }
        String c = content.trim().toLowerCase();
        return c.equals("python") || c.equals("python:*") || c.equals("python*")
            || c.equals("python *") || (c.startsWith("python -") && c.endsWith("*"));
    }

    // ─────────────────────── 1. 剥离 + stash（验收 1/CC-PERM-25） ───────────────────────

    @Test
    @DisplayName("strip: 危险规则从 allow 桶剥离并进入 stash，安全规则保留")
    void strip_removesDangerousAndStashes() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext ctx = ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));

        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(ctx);

        Set<PermissionRule> allow = stripped.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS);
        assertThat(allow)
            .as("allow 桶剥离后必须保留安全规则 (ls)")
            .anyMatch(r -> "ls".equals(r.ruleValue().ruleContent()));
        assertThat(allow)
            .as("危险规则 (python -c ...) 必须从 allow 桶剥离")
            .noneMatch(DangerousStripRestoreTest::isDangerousBash);

        assertThat(stripped.strippedDangerousRules())
            .as("被剥离规则必须 stash 进 ctx.strippedDangerousRules（CC-PERM-25）")
            .containsKey(PermissionRuleSource.USER_SETTINGS);
        assertThat(stripped.strippedDangerousRules().get(PermissionRuleSource.USER_SETTINGS))
            .anyMatch(DangerousStripRestoreTest::isDangerousBash);
    }

    @Test
    @DisplayName("strip: 无危险规则 → 上下文原样返回，既有 stash 不被覆盖 (CC :530-535)")
    void strip_noDangerous_keepsContextAndStash() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        Map<PermissionRuleSource, Set<PermissionRule>> preStash = new EnumMap<>(PermissionRuleSource.class);
        preStash.put(PermissionRuleSource.USER_SETTINGS, new LinkedHashSet<>(List.of(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python x"))));
        ToolPermissionContext ctx = ctxWithStash(preStash,
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"));

        ToolPermissionContext result = detector.stripDangerousPermissionsForAutoMode(ctx);

        assertThat(result).isSameAs(ctx);
        assertThat(result.strippedDangerousRules())
            .as("无危险规则时 stash 保持原值（CC strippedDangerousRules ?? {}）")
            .isEqualTo(preStash);
    }

    @Test
    @DisplayName("strip: 非可持久化 source (policy) 危险规则不剥离、不进 stash (CC isPermissionUpdateDestination)")
    void strip_nonPersistableSource_notStrippedNotStashed() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext ctx = ctxWith(
            rule(PermissionRuleSource.POLICY_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));

        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(ctx);

        assertThat(stripped.alwaysAllowRules().get(PermissionRuleSource.POLICY_SETTINGS))
            .as("policySettings 不可持久化 → 危险规则保留（CC removeDangerousPermissions 跳过）")
            .anyMatch(DangerousStripRestoreTest::isDangerousBash);
        assertThat(stripped.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("userSettings 可持久化 → 危险规则剥离")
            .noneMatch(DangerousStripRestoreTest::isDangerousBash);
        assertThat(stripped.strippedDangerousRules())
            .as("stash 只含可持久化源（stash == 实际被剥离的规则）")
            .containsOnlyKeys(PermissionRuleSource.USER_SETTINGS);
    }

    // ─────────────────────── S09：危险规则匹配算法（精确形态 + 全小写，OD-WF1-CFG-02） ───────────────────────

    @Test
    @DisplayName("isDangerousRule: 精确形态 + 全小写判定（对齐 CC isDangerousBashPermission permissionSetup.ts:94-147）")
    void isDangerousRule_exactShapeAndLowercase() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        // 精确 5 形态危险（CC :117-144）：python / python:* / python* / python * / python -*
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python:*"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python*"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python *"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"))).isTrue();
        // 全小写归一（CC :108）—— 大写规则同样命中
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "Python:*"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "PYTHON -c *"))).isTrue();
        // '*' 显式判危险（CC :111-113）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "*"))).isTrue();
        // wholeTool（content null）= 最危险（CC :104-106）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", null))).isTrue();

        // 非危险：具体命令不带通配符 / 前缀不匹配（S09 精确形态——旧正则 find 会误判，此处验证新语义）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c 'x'"))).isFalse();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python x"))).isFalse();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"))).isFalse();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "pip install"))).isFalse();
        // 非 Bash 工具不判危险
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", "python"))).isFalse();
        // 非 ALLOW 行为不判危险
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", "python:*"))).isFalse();
    }

    // ─────────────────────── OPD-WF4-BC-03：Task/Agent 危险判定（CC isDangerousTaskPermission permissionSetup.ts:240-245） ───────────────────────

    @Test
    @DisplayName("isDangerousTaskPermission: normalizeLegacyToolName===AGENT_TOOL_NAME（Agent 及其 legacy Task 恒危险，与 ruleContent 无关）")
    void isDangerousTaskPermission_agentAndLegacyTask_areDangerous() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        // CC :240-245 —— normalizeLegacyToolName(toolName) === AGENT_TOOL_NAME('Agent')
        assertThat(detector.isDangerousTaskPermission("Agent", null)).isTrue();
        assertThat(detector.isDangerousTaskPermission("Task", null)).isTrue(); // legacy wire 名归一
        assertThat(detector.isDangerousTaskPermission("Agent", "coding-agent")).isTrue(); // 与 ruleContent 无关
        assertThat(detector.isDangerousTaskPermission("Task", "coding-agent")).isTrue();
        assertThat(detector.isDangerousTaskPermission("Bash", null)).isFalse();
        assertThat(detector.isDangerousTaskPermission("Edit", null)).isFalse();
    }

    @Test
    @DisplayName("isDangerousRule: Agent/Task allow 规则判危险（auto 模式防分类器绕过），非 ALLOW 不判")
    void isDangerousRule_agentAllow_isDangerous() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        // WHY: auto mode 下 Agent(*) allow 会绕过分类器对 sub-agent 提示词的安全评估（delegation attack 防御）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Agent", null))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Agent", "code-reviewer"))).isTrue();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Task", null))).isTrue();
        // 非 Agent/Bash/PowerShell 工具不判危险（保持 CC 工具白名单语义）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", null))).isFalse();
        // 非 ALLOW 行为不判危险（CC findDangerousClassifierPermissions ruleBehavior==='allow'）
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Agent", null))).isFalse();
        assertThat(detector.isDangerousRule(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Task", null))).isFalse();
    }

    @Test
    @DisplayName("strip: auto 模式剥离 Agent(*) 危险 allow 规则并 stash（防 delegation attack 绕过分类器）")
    void strip_removesDangerousAgentRuleAndStashes() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext ctx = ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Agent", null),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", null));

        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(ctx);

        Set<PermissionRule> allow = stripped.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS);
        assertThat(allow)
            .as("Agent 危险规则剥离后，Edit 规则保留")
            .anyMatch(r -> "Edit".equals(r.ruleValue().toolName()));
        assertThat(allow)
            .as("Agent(*) 危险 allow 必须从 allow 桶剥离")
            .noneMatch(r -> "Agent".equals(r.ruleValue().toolName()));
        assertThat(stripped.strippedDangerousRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("Agent 规则必须 stash 于上下文（CC-PERM-25）")
            .anyMatch(r -> "Agent".equals(r.ruleValue().toolName()));
    }

    // ─────────────────────── 2. 恢复（验收 2） + 幂等（验收 3） ───────────────────────

    @Test
    @DisplayName("restore: 剥离 → 恢复后 allow 桶完整还原，stash 清空")
    void restore_afterStrip_fullyRestoresAndClearsStash() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext original = ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));
        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(original);
        assertThat(stripped.strippedDangerousRules()).isNotEmpty();

        ToolPermissionContext restored = detector.restoreDangerousPermissions(stripped);

        Set<PermissionRule> allow = restored.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS);
        assertThat(allow)
            .as("恢复后安全规则 (ls) 仍在")
            .anyMatch(r -> "ls".equals(r.ruleValue().ruleContent()));
        assertThat(allow)
            .as("恢复后被剥离的危险规则 (python) 完整回来")
            .anyMatch(DangerousStripRestoreTest::isDangerousBash);
        assertThat(allow).hasSize(2);
        assertThat(restored.strippedDangerousRules())
            .as("恢复后 stash 清空（CC :578 strippedDangerousRules: undefined）")
            .isEmpty();
    }

    @Test
    @DisplayName("restore: 二次调用 no-op（幂等，规则不重复）")
    void restore_secondCall_isNoOp() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext original = ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));
        ToolPermissionContext restoredOnce = detector.restoreDangerousPermissions(
            detector.stripDangerousPermissionsForAutoMode(original));

        ToolPermissionContext restoredTwice = detector.restoreDangerousPermissions(restoredOnce);

        assertThat(restoredTwice)
            .as("二次恢复 stash 已空 → 原样返回（幂等 no-op）")
            .isSameAs(restoredOnce);
        assertThat(restoredTwice.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("二次恢复不得重复添加规则")
            .hasSize(1);
        assertThat(restoredTwice.strippedDangerousRules()).isEmpty();
    }

    @Test
    @DisplayName("restore: 无 stash 上下文 → 原样返回 no-op")
    void restore_noStash_returnsSameContext() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext ctx = ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"));

        assertThat(detector.restoreDangerousPermissions(ctx)).isSameAs(ctx);
    }

    // ─────────────────────── 4. 上下文标志位保真（S04 返工：CC spread 语义） ───────────────────────

    @Test
    @DisplayName("strip: 剥离后 shouldAvoidPermissionPrompts/awaitAutomatedChecksBeforeDialog/prePlanMode 从原 ctx 保真")
    void strip_preservesContextFlags() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext ctx = ctxWithFlags(
            true, true, PermissionMode.DEFAULT,
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));

        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(ctx);

        assertThat(stripped.shouldAvoidPermissionPrompts())
            .as("shouldAvoidPermissionPrompts 不得被 Applier 重建重置（CC :549-552 spread 保留原 ctx 字段）")
            .isTrue();
        assertThat(stripped.awaitAutomatedChecksBeforeDialog())
            .as("awaitAutomatedChecksBeforeDialog 不得被 Applier 重建重置")
            .isTrue();
        assertThat(stripped.prePlanMode())
            .as("prePlanMode 不得被 Applier 重建重置")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(stripped.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("危险规则已剥离")
            .noneMatch(DangerousStripRestoreTest::isDangerousBash);
        assertThat(stripped.strippedDangerousRules())
            .as("stash 已填入（CC-PERM-25）")
            .containsKey(PermissionRuleSource.USER_SETTINGS);
    }

    @Test
    @DisplayName("restore: 剥离→恢复后 3 个上下文标志位仍保真，规则完整回写，stash 清空")
    void restore_preservesContextFlags() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        ToolPermissionContext original = ctxWithFlags(
            true, true, PermissionMode.DEFAULT,
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));

        ToolPermissionContext stripped = detector.stripDangerousPermissionsForAutoMode(original);
        ToolPermissionContext restored = detector.restoreDangerousPermissions(stripped);

        assertThat(restored.shouldAvoidPermissionPrompts())
            .as("恢复后 shouldAvoidPermissionPrompts 仍保真（CC :578 spread）")
            .isTrue();
        assertThat(restored.awaitAutomatedChecksBeforeDialog())
            .as("恢复后 awaitAutomatedChecksBeforeDialog 仍保真")
            .isTrue();
        assertThat(restored.prePlanMode())
            .as("恢复后 prePlanMode 仍保真")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(restored.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("规则完整回写（无重复）")
            .hasSize(1)
            .anyMatch(DangerousStripRestoreTest::isDangerousBash);
        assertThat(restored.strippedDangerousRules())
            .as("stash 清空（CC :578 strippedDangerousRules: undefined）")
            .isEmpty();
    }

    // ─────────────────────── 3. builder 级：auto 剥离+stash / 非 auto 不剥离（验收 1/4） ───────────────────────

    @Test
    @DisplayName("PermissionContextBuilder: auto 模式 + gate 开 → 剥离并 stash")
    void permissionContextBuilder_autoMode_stripsAndStashes() {
        loaderRules = List.of(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));
        autoModeOn = true;
        try {
            runner.run(springCtx -> {
                PermissionContextBuilder builder = springCtx.getBean(PermissionContextBuilder.class);
                AgentState state = new AgentState("system", SESSION_ID, AGENT_ID);
                ToolPermissionContext permCtx =
                    builder.buildPermissionContext(state, false, PermissionMode.AUTO, false, true);

                Set<PermissionRule> allow = permCtx.alwaysAllowRules()
                    .get(PermissionRuleSource.USER_SETTINGS);
                assertThat(allow)
                    .as("auto 模式剥离：安全规则保留")
                    .anyMatch(r -> "ls".equals(r.ruleValue().ruleContent()));
                assertThat(allow)
                    .as("auto 模式剥离：危险规则移除")
                    .noneMatch(DangerousStripRestoreTest::isDangerousBash);
                assertThat(permCtx.strippedDangerousRules())
                    .as("auto 模式剥离：危险规则 stash 于上下文")
                    .containsKey(PermissionRuleSource.USER_SETTINGS);
            });
        } finally {
            autoModeOn = false;
            loaderRules = List.of();
        }
    }

    @Test
    @DisplayName("PermissionContextBuilder: 非 auto 模式（gate 开）→ 不剥离（验收 4）")
    void permissionContextBuilder_nonAutoMode_notStripped() {
        loaderRules = List.of(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "python -c *"));
        autoModeOn = true;
        try {
            runner.run(springCtx -> {
                PermissionContextBuilder builder = springCtx.getBean(PermissionContextBuilder.class);
                AgentState state = new AgentState("system", SESSION_ID, AGENT_ID);
                ToolPermissionContext permCtx =
                    builder.buildPermissionContext(state, false, PermissionMode.DEFAULT, false, true);

                assertThat(permCtx.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
                    .as("非 auto 模式不得剥离危险规则（CC-PERM-09）")
                    .hasSize(1);
                assertThat(permCtx.strippedDangerousRules()).isEmpty();
            });
        } finally {
            autoModeOn = false;
            loaderRules = List.of();
        }
    }

    @AfterEach
    void cleanup() {
        loaderRules = List.of();
        autoModeOn = false;
    }
}
