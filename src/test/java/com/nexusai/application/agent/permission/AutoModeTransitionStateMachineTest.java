package com.nexusai.application.agent.permission;

import com.nexusai.infra.util.AutoModeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S10] auto 状态机 focused 测试 · 对齐 CC permissionSetup.ts:597-646
 * {@code transitionPermissionMode} + getNextPermissionMode.ts:88-101 {@code cyclePermissionMode}
 * （探查 M-6：Java 全仓零命中 → S10 补齐）。
 *
 * <p>覆盖（验收标准 §5-1/§5-7）：
 * <ol>
 *   <li>同模式 no-op（CC :602-603，plan→plan SDK set_permission_mode 防误入 leave 分支）</li>
 *   <li>进入 auto：门开 → setAutoModeActive(true) + 剥离危险规则（stash 于上下文）</li>
 *   <li>进入 auto 门关 → throw IllegalStateException（CC :628-630）</li>
 *   <li>离开 auto：setAutoModeActive(false) + restore（S04 API 消费）</li>
 *   <li>二次离开 no-op（restore 幂等，CC :564-567）</li>
 *   <li>plan+active → plan→auto 无副作用（fromUses=true 且 toUses=true，CC :621-625）</li>
 *   <li>plan+active → 离开 plan → restore + 清激活</li>
 *   <li>feature 关闭 → 无 strip/restore 副作用（CC :612 块整体跳过）</li>
 *   <li>plan 进入：纯 plan 记 prePlanMode（CC :1492）</li>
 *   <li>plan 进入（来自 auto，planAutoMode=false）：restore + prePlanMode=auto（CC :1473-1478）</li>
 *   <li>plan 进入（planAutoMode=true）：激活 auto + strip + prePlanMode=fromMode（CC :1480-1486）</li>
 *   <li>plan 退出：prePlanMode 清理（CC :640-643）</li>
 *   <li>cyclePermissionMode：golden path（default→acceptEdits / plan→auto / auto→default /
 *       bypass→auto）+ 转换副作用随 cycle 生效（CC getNextPermissionMode.ts:88-101）</li>
 * </ol>
 *
 * <p>使用真实 {@link DangerousPatternDetector} + {@link PermissionUpdateApplier}（S04 交付），
 * 规则构造模式与 DangerousStripRestoreTest 一致。
 */
@DisplayName("[S10] auto 状态机 transitionPermissionMode/cyclePermissionMode")
class AutoModeTransitionStateMachineTest {

    private static final java.util.function.BooleanSupplier GATE_ON = () -> true;
    private static final java.util.function.BooleanSupplier GATE_OFF = () -> false;

    private DangerousPatternDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DangerousPatternDetector(new PermissionUpdateApplier());
        AutoModeState.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        AutoModeState.resetForTesting();
    }

    // ─────────────────── 1. 同模式 no-op ───────────────────

    @Test
    @DisplayName("同模式转换原样返回（CC :602-603 plan→plan 防误入 leave 分支）")
    void sameMode_noOp() {
        ToolPermissionContext ctx = ctxWithSafeRule();

        ToolPermissionContext result = transition(
            PermissionMode.PLAN, PermissionMode.PLAN, ctx, GATE_ON);

        assertThat(result).isSameAs(ctx);
        assertThat(AutoModeState.isAutoModeActive()).isFalse();
    }

    // ─────────────────── 2/3. 进入 auto ───────────────────

    @Test
    @DisplayName("进入 auto：门开 → setAutoModeActive(true) + 剥离危险规则并 stash")
    void enterAuto_stripsAndActivates() {
        ToolPermissionContext ctx = ctxWithSafeAndDangerousRules();

        ToolPermissionContext result = transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, ctx, GATE_ON);

        assertThat(AutoModeState.isAutoModeActive())
            .as("CC :631 setAutoModeActive(true)")
            .isTrue();
        assertThat(result.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("进入 auto 剥离危险规则（CC :632 stripDangerousPermissionsForAutoMode）")
            .noneMatch(r -> r.ruleValue().ruleContent().contains("python"));
        assertThat(result.strippedDangerousRules())
            .as("被剥离规则 stash 于上下文（CC-PERM-25）")
            .containsKey(PermissionRuleSource.USER_SETTINGS);
    }

    @Test
    @DisplayName("进入 auto：门关 → throw IllegalStateException（CC :628-630）")
    void enterAuto_gateOff_throws() {
        ToolPermissionContext ctx = ctxWithSafeRule();

        assertThatThrownBy(() -> transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, ctx, GATE_OFF))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Cannot transition to auto mode: gate is not enabled");
        assertThat(AutoModeState.isAutoModeActive())
            .as("抛错前不得激活 auto")
            .isFalse();
    }

    @Test
    @DisplayName("feature 关闭 → 无 strip/restore/激活副作用（CC :612 块整体跳过）")
    void featureOff_noSideEffects() {
        ToolPermissionContext ctx = ctxWithSafeAndDangerousRules();

        ToolPermissionContext result = transitionFeatureOff(
            PermissionMode.DEFAULT, PermissionMode.AUTO, ctx);

        assertThat(AutoModeState.isAutoModeActive()).isFalse();
        assertThat(result).isSameAs(ctx);
        assertThat(result.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("feature 关 → 危险规则不剥离（外部构建语义）")
            .anyMatch(r -> r.ruleValue().ruleContent().contains("python"));
    }

    // ─────────────────── 4/5. 离开 auto ───────────────────

    @Test
    @DisplayName("离开 auto：setAutoModeActive(false) + restore 完整恢复（S04 API 消费）")
    void leaveAuto_restoresRules() {
        ToolPermissionContext original = ctxWithSafeAndDangerousRules();
        ToolPermissionContext stripped = transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, original, GATE_ON);
        assertThat(AutoModeState.isAutoModeActive()).isTrue();

        ToolPermissionContext restored = transition(
            PermissionMode.AUTO, PermissionMode.DEFAULT, stripped, GATE_ON);

        assertThat(AutoModeState.isAutoModeActive())
            .as("CC :634 setAutoModeActive(false)")
            .isFalse();
        assertThat(restored.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("离开 auto 恢复被剥离规则（CC :636 restoreDangerousPermissions）")
            .hasSize(2)
            .anyMatch(r -> r.ruleValue().ruleContent().contains("python"));
        assertThat(restored.strippedDangerousRules())
            .as("恢复后 stash 清空（CC :578）")
            .isEmpty();
    }

    @Test
    @DisplayName("二次离开 auto no-op（restore 幂等，CC :564-567）")
    void leaveAutoTwice_idempotent() {
        ToolPermissionContext original = ctxWithSafeAndDangerousRules();
        ToolPermissionContext stripped = transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, original, GATE_ON);
        ToolPermissionContext restored = transition(
            PermissionMode.AUTO, PermissionMode.DEFAULT, stripped, GATE_ON);
        ToolPermissionContext restoredTwice = transition(
            PermissionMode.DEFAULT, PermissionMode.DEFAULT, restored, GATE_ON);

        assertThat(restoredTwice)
            .as("已离开 auto（active=false）后再次转换无副作用")
            .isSameAs(restored);
        assertThat(restoredTwice.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("规则不重复添加")
            .hasSize(2);
    }

    // ─────────────────── 6/7. plan+active 语义 ───────────────────

    @Test
    @DisplayName("plan+auto-active → plan→auto 无副作用（fromUses=true 且 toUses=true，CC :621-625）")
    void planActive_toAuto_noSideEffects() {
        AutoModeState.setAutoModeActive(true);
        ToolPermissionContext ctx = ctxWithSafeRule();

        ToolPermissionContext result = transition(
            PermissionMode.PLAN, PermissionMode.AUTO, ctx, GATE_ON);

        assertThat(AutoModeState.isAutoModeActive())
            .as("plan 期间 auto 已激活，plan→auto 不重复激活")
            .isTrue();
        assertThat(result).isSameAs(ctx);
    }

    @Test
    @DisplayName("plan+auto-active → 离开 plan 触发 restore + 清激活（CC :633-637）")
    void planActive_leavePlan_restores() {
        ToolPermissionContext original = ctxWithSafeAndDangerousRules();
        ToolPermissionContext stripped = transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, original, GATE_ON);
        // plan 期间 auto 保持激活（CC isAutoModeActive 权威信号）
        assertThat(AutoModeState.isAutoModeActive()).isTrue();

        ToolPermissionContext restored = transition(
            PermissionMode.PLAN, PermissionMode.DEFAULT, stripped, GATE_ON);

        assertThat(AutoModeState.isAutoModeActive()).isFalse();
        assertThat(restored.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("plan+active 离开 → 危险规则恢复")
            .hasSize(2);
    }

    // ─────────────────── 9-12. plan 进入/退出 ───────────────────

    @Test
    @DisplayName("纯 plan 进入：prePlanMode=fromMode（CC :1492）")
    void enterPlan_plain_setsPrePlanMode() {
        ToolPermissionContext ctx = ctxWithSafeRule();

        ToolPermissionContext result = transition(
            PermissionMode.DEFAULT, PermissionMode.PLAN, ctx, GATE_ON);

        assertThat(result.prePlanMode())
            .as("CC :1492 {...ctx, prePlanMode: currentMode}")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(AutoModeState.isAutoModeActive()).isFalse();
    }

    @Test
    @DisplayName("来自 auto 进入 plan（planAutoMode=false）：restore + prePlanMode=auto（CC :1473-1478）")
    void enterPlan_fromAuto_withoutPlanAuto_restores() {
        ToolPermissionContext original = ctxWithSafeAndDangerousRules();
        ToolPermissionContext stripped = transition(
            PermissionMode.DEFAULT, PermissionMode.AUTO, original, GATE_ON);
        assertThat(AutoModeState.isAutoModeActive()).isTrue();

        ToolPermissionContext result = transitionPlanEntry(
            PermissionMode.AUTO, stripped, GATE_ON, GATE_OFF);

        assertThat(AutoModeState.isAutoModeActive())
            .as("CC :1473 auto 在 plan 期间不保持激活")
            .isFalse();
        assertThat(result.prePlanMode()).isEqualTo(PermissionMode.AUTO);
        assertThat(result.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("CC :1476 restoreDangerousPermissions")
            .hasSize(2);
    }

    @Test
    @DisplayName("planAutoMode=true 进入 plan：激活 auto + strip + prePlanMode=fromMode（CC :1480-1486）")
    void enterPlan_withPlanAuto_activatesAndStrips() {
        ToolPermissionContext ctx = ctxWithSafeAndDangerousRules();

        ToolPermissionContext result = transitionPlanEntry(
            PermissionMode.DEFAULT, ctx, GATE_ON, GATE_ON);

        assertThat(AutoModeState.isAutoModeActive())
            .as("CC :1481 setAutoModeActive(true)")
            .isTrue();
        assertThat(result.prePlanMode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(result.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS))
            .as("CC :1483 stripDangerousPermissionsForAutoMode")
            .noneMatch(r -> r.ruleValue().ruleContent().contains("python"));
    }

    @Test
    @DisplayName("plan 退出：prePlanMode 清理（CC :640-643）")
    void leavePlan_clearsPrePlanMode() {
        ToolPermissionContext entered = transition(
            PermissionMode.DEFAULT, PermissionMode.PLAN, ctxWithSafeRule(), GATE_ON);
        assertThat(entered.prePlanMode()).isEqualTo(PermissionMode.DEFAULT);

        ToolPermissionContext result = transition(
            PermissionMode.PLAN, PermissionMode.DEFAULT, entered, GATE_ON);

        assertThat(result.prePlanMode())
            .as("CC :641-643 {...ctx, prePlanMode: undefined}")
            .isNull();
        assertThat(AutoModeState.isAutoModeActive()).isFalse();
    }
    @Test
    @DisplayName("cycle: default+external → acceptEdits（CC getNextPermissionMode.ts:50）")
    void cycle_defaultToAcceptEdits() {
        ToolPermissionContext ctx = ctxWithSafeRule();
        GetNextPermissionMode.CycleResult r = cycle(
            ctx, false, false, false);

        assertThat(r.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.acceptEdits);
        assertThat(r.context())
            .as("default→acceptEdits 无转换副作用，上下文不变")
            .isSameAs(ctx);
    }

    @Test
    @DisplayName("cycle: default+ant+bypass → bypassPermissions（CC :42-44）")
    void cycle_antDefaultToBypass() {
        GetNextPermissionMode.CycleResult r = cycle(
            ctxWithSafeRule(), true, true, false);

        assertThat(r.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.bypassPermissions);
    }

    @Test
    @DisplayName("cycle: plan + canCycleToAuto → auto，且转换副作用生效（进入 auto 剥离）")
    void cycle_planToAuto_appliesTransition() {
        ToolPermissionContext ctx = ctxWithMode(PermissionMode.PLAN,
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash",
                "python -c 'import os; os.system(x)'"));

        GetNextPermissionMode.CycleResult r = cycle(ctx, false, false, true);

        assertThat(r.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.auto);
        assertThat(AutoModeState.isAutoModeActive())
            .as("cycle 应用 transitionPermissionMode 副作用")
            .isTrue();
        assertThat(r.context().strippedDangerousRules())
            .as("进入 auto 剥离 stash 随 cycle 生效")
            .containsKey(PermissionRuleSource.USER_SETTINGS);
    }

    @Test
    @DisplayName("cycle: bypassPermissions → auto（CC :64-68）；auto → default（CC :75-77）")
    void cycle_bypassToAuto_thenAutoToDefault() {
        ToolPermissionContext bypassCtx = ctxWithMode(PermissionMode.BYPASS_PERMISSIONS);

        GetNextPermissionMode.CycleResult toAuto = cycle(bypassCtx, false, true, true);
        assertThat(toAuto.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.auto);
        assertThat(AutoModeState.isAutoModeActive())
            .as("bypass→auto 进入 classifier 侧 → 激活")
            .isTrue();

        // 模拟调用方把 mode 应用到返回 ctx 后再 cycling（CC :590-591 调用方职责）
        ToolPermissionContext autoCtx = ctxWithMode(PermissionMode.AUTO);
        GetNextPermissionMode.CycleResult fromAuto = cycle(autoCtx, false, true, true);
        assertThat(fromAuto.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.defaultMode);
        assertThat(AutoModeState.isAutoModeActive())
            .as("auto→default 离开 classifier 侧 → 清激活")
            .isFalse();
    }

    @Test
    @DisplayName("cycle: default+ant+无 bypass 无 auto → 停留 default（CC :45-48）")
    void cycle_antDefaultStays() {
        GetNextPermissionMode.CycleResult r = cycle(
            ctxWithSafeRule(), true, false, false);

        assertThat(r.nextMode()).isEqualTo(GetNextPermissionMode.ModeCycle.defaultMode);
    }

    // ─────────────────── 工具 ───────────────────

    private ToolPermissionContext transition(PermissionMode from, PermissionMode to,
                                             ToolPermissionContext ctx,
                                             java.util.function.BooleanSupplier gate) {
        return GetNextPermissionMode.transitionPermissionMode(from, to, ctx,
            new GetNextPermissionMode.TransitionConfig(GATE_ON, gate, GATE_OFF, detector));
    }

    /** feature 关闭（transcript feature supplier = null / false）场景。 */
    private ToolPermissionContext transitionFeatureOff(PermissionMode from, PermissionMode to,
                                                       ToolPermissionContext ctx) {
        return GetNextPermissionMode.transitionPermissionMode(from, to, ctx,
            new GetNextPermissionMode.TransitionConfig(null, GATE_ON, GATE_OFF, detector));
    }

    /** plan 进入（planUsesAutoMode 可控）。 */
    private ToolPermissionContext transitionPlanEntry(PermissionMode from,
                                                      ToolPermissionContext ctx,
                                                      java.util.function.BooleanSupplier gate,
                                                      java.util.function.BooleanSupplier planAuto) {
        return GetNextPermissionMode.transitionPermissionMode(from, PermissionMode.PLAN, ctx,
            new GetNextPermissionMode.TransitionConfig(GATE_ON, gate, planAuto, detector));
    }
    private static ToolPermissionContext ctxWith(PermissionRule... rules) {
        return ctxWithMode(PermissionMode.DEFAULT, rules);
    }

    /** 指定起始 mode 的上下文（cycle 测试需要模拟调用方已应用模式后的 ctx）。 */
    private static ToolPermissionContext ctxWithMode(PermissionMode mode, PermissionRule... rules) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        Set<PermissionRule> set = new LinkedHashSet<>();
        for (PermissionRule r : rules) {
            set.add(r);
        }
        allow.put(PermissionRuleSource.USER_SETTINGS, set);
        return ToolPermissionContext.of(
            mode, allow, Map.of(), Map.of(), Map.of());
    }

    private GetNextPermissionMode.CycleResult cycle(ToolPermissionContext ctx,
                                                    boolean isAnt, boolean isBypass,
                                                    boolean canCycleToAuto) {
        return GetNextPermissionMode.cyclePermissionMode(ctx, isAnt, isBypass, canCycleToAuto,
            new GetNextPermissionMode.TransitionConfig(GATE_ON, GATE_ON, GATE_OFF, detector));
    }

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
                                       String toolName, String content) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(toolName, content));
    }

    private static ToolPermissionContext ctxWithSafeRule() {
        return ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"));
    }

    private static ToolPermissionContext ctxWithSafeAndDangerousRules() {
        return ctxWith(
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls"),
            rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash",
                "python -c 'import os; os.system(x)'"));
    }
}
