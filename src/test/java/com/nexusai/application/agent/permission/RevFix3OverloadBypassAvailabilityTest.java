package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [REV-FIX-3 + F1-BY] 重载 isBypassPermissionsModeAvailable 语义测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：REV-FIX-3 复验发现旧便捷重载委托
 * {@link InitialPermissionModeResolver.Input#empty()}，把可用性交给空输入重算 → 恒 false →
 * 经 {@code CheckLayer2a_BypassMode:80} 守卫使 bypass 静默失效（{@code --dangerously-skip-permissions}
 * 被禁用）。CC 中该 flag 是 {@code initializeToolPermissionContext}（permissionSetup.ts:872-886）
 * 启动时<b>一次性</b>计算的字段（:939-943），per-turn 重建保留原值、不重算。R1 删除 1/2/3/4 参
 * 便捷重载后，剩余 5/6 参重载钉死语义：
 * <ol>
 *   <li><b>[F1-BY] 5 参重载（携带 base）→ 保留 base 值</b>：base=true → true、base=false → false
 *       （对齐 CC applyPermissionUpdate setMode {@code {...context, mode}} spread 保留语义，
 *       PermissionUpdate.ts:60-67）；</li>
 *   <li>5 参 mode=PLAN + base=true → true（CheckLayer2a plan 分支可达性，回归锁定）；</li>
 *   <li>对照 6 参 Input.empty()+defaults → false（锁定 CC 公式仍在 6 参真实输入路径生效）。</li>
 * </ol>
 */
class RevFix3OverloadBypassAvailabilityTest {

    private static final AgentState STATE = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);

    private final PermissionContextBuilder builder = new PermissionContextBuilder();

    // ═══════════════ ① 5 参 mode 回归锁定（R1 删 1/2/3/4 参便捷重载后保留） ═══════════════

    @Test
    @DisplayName("[5 参] mode=PLAN + base=true → 可用（CheckLayer2a plan+available 分支可达性，P2 #5）")
    void fiveParam_planMode_availableTrue() {
        ToolPermissionContext ctx =
            builder.buildPermissionContext(STATE, false, PermissionMode.PLAN, false, true);
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("PLAN mode + 可用 → CheckLayer2a_BypassMode:80 的 plan 分支生产可达（CC permissions.ts:1268-1281 plan && isAvailable）")
            .isTrue();
        assertThat(ctx.mode()).isEqualTo(PermissionMode.PLAN);
    }

    // ═══════════════ ② [F1-BY] 5 参重载：保留 base 值（CC per-turn 保留语义） ═══════════════

    @Test
    @DisplayName("[5 参] base=true → bypass 可用（per-turn 保留启动值，CC spread 语义）")
    void fiveParam_baseTrue_availableTrue() {
        ToolPermissionContext ctx = builder.buildPermissionContext(
            STATE, false, PermissionMode.BYPASS_PERMISSIONS, false, true);
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("5 参重载携带 base=true → per-turn 保留 true（启动时可用，per-turn 不得翻 false）")
            .isTrue();
    }

    @Test
    @DisplayName("[5 参] base=false → bypass 不可用（per-turn 保留启动值，org/settings 禁用不失效）")
    void fiveParam_baseFalse_availableFalse() {
        ToolPermissionContext ctx = builder.buildPermissionContext(
            STATE, false, PermissionMode.BYPASS_PERMISSIONS, false, false);
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("5 参重载携带 base=false → per-turn 保留 false（org/settings 启动禁用 bypass 不被翻回 true）")
            .isFalse();
        assertThat(ctx.mode())
            .as("mode 透传仍生效（BYPASS_PERMISSIONS，与可用性解耦）")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    // ═══════════════ ③ 对照：6 参真实输入路径保留 CC 公式 ═══════════════

    @Test
    @DisplayName("[6 参对照] Input.empty()+defaults → 可用性 false（锁定 CC 公式未被破坏）")
    void sixParam_emptyInput_availableFalse_locksCcFormula() {
        ToolPermissionContext ctx = builder.buildPermissionContext(
            STATE, false, PermissionMode.DEFAULT, false,
            InitialPermissionModeResolver.Input.empty(),
            InitialPermissionModeResolver.Config.defaults());
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("6 参真实输入路径（override=null）保留 CC 公式：无 dangerouslySkip 且未以 bypass 启动 → false（permissionSetup.ts:939-944）")
            .isFalse();
        assertThat(ctx.mode())
            .as("6 参显式 mode=DEFAULT 透传")
            .isEqualTo(PermissionMode.DEFAULT);
    }
}
