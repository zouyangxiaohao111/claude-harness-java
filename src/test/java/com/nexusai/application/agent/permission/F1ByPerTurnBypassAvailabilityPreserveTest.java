package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [F1-BY] per-turn 重建 isBypassPermissionsModeAvailable 保留 base 值测试（org/settings 禁用门不失效）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：F1 复验发现 {@link AgentLoopContext#toolExecContext}
 * 每轮重建 permCtx 走 5 参 {@link PermissionContextBuilder#buildPermissionContext(AgentState, boolean,
 * PermissionMode, boolean, boolean)}，旧实现内部硬编码 {@code isBypassPermissionsModeAvailable=true}。结果：org/settings
 * 在启动时（{@code initializeToolPermissionContext} 三条件公式，CC permissionSetup.ts:938-944）禁用
 * bypass 后，per-turn 重建又把可用性翻回 true → {@code CheckLayer2a_BypassMode:80} 的禁用门在 per-turn
 * 失效（用户绕过组织策略重新获得 bypass）。
 *
 * <p><b>CC 真源</b>：CC 的 {@code isBypassPermissionsModeAvailable} 启动时<b>一次性</b>计算，per-turn
 * 重建<b>保留原值、不重算</b>——{@code applyPermissionUpdate} setMode 用 {@code {...context, mode}} spread
 * 保留该字段（PermissionUpdate.ts:60-67）。本测试钉死修复后语义：
 * <ol>
 *   <li>base permCtx {@code isBypassPermissionsModeAvailable=false}（模拟 org/settings 启动禁用）
 *       → per-turn 重建后仍为 false（<b>核心断言</b>：旧硬编码 true 会翻回 true）；</li>
 *   <li>base=true → per-turn 仍 true（保留语义回归，启动可用时 per-turn 不得翻 false）；</li>
 *   <li>6 参真实输入（settings.disableBypassPermissionsMode='disable' + dangerouslySkip）→ base=false
 *       → 喂给 per-turn 重建仍 false（settings 侧 disable 驱动的完整链路）。</li>
 * </ol>
 * 任一 per-turn 重建把 base 值覆盖（翻 true / 翻 false），测试即 RED。
 */
class F1ByPerTurnBypassAvailabilityPreserveTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String SESSION_ID = "00000000-0000-0000-0000-0000000000b2";

    private final PermissionContextBuilder builder = new PermissionContextBuilder();

    private static AgentLoopContext ctxWithPermissionContextBuilder(PermissionContextBuilder b) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            b, null, null, null);
    }

    /** base permCtx · isBypassPermissionsModeAvailable 可指定（模拟启动时三条件公式结果）。 */
    private static ToolPermissionContext basePermCtx(boolean bypassAvailable) {
        return new ToolPermissionContext(
            PermissionMode.BYPASS_PERMISSIONS, Map.of(), Map.of(), Map.of(), Map.of(),
            bypassAvailable, false, Map.of(), false, false, null);
    }

    /** 经 toolExecContext 派生 per-turn permCtx（对齐 H9V2GapFixTest 的 production 接缝模式）。 */
    private static ToolPermissionContext perTurnPermCtx(boolean baseBypassAvailable) {
        ToolUseContext baseTuc = ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            basePermCtx(baseBypassAvailable), PermissionMode.BYPASS_PERMISSIONS);
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctxWithPermissionContextBuilder(new PermissionContextBuilder()),
            baseTuc, new AgentState("sys", SESSION_ID, AGENT_ID), Map.of());
        assertThat(perTurn.permissionContext())
            .as("toolExecContext 必须重建 per-turn permCtx（permissionContextBuilder 注入时）")
            .isNotNull();
        return perTurn.permissionContext();
    }

    // ═══════════════ ① 核心断言：base=false → per-turn 保留 false（禁用门不失效） ═══════════════

    @Test
    @DisplayName("base=false（org/settings 启动禁用）→ per-turn 重建仍 false（旧硬编码 true 会翻回）")
    void perTurn_preservesBaseFalse() {
        ToolPermissionContext permCtx = perTurnPermCtx(false);
        assertThat(permCtx.isBypassPermissionsModeAvailable())
            .as("per-turn 重建必须保留 base=false（org/settings 启动禁用 bypass 不得被 per-turn 翻回 true）")
            .isFalse();
        assertThat(permCtx.mode())
            .as("mode 透传（BYPASS_PERMISSIONS）仍生效，与可用性解耦")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    // ═══════════════ ② 保留语义回归：base=true → per-turn 保留 true ═══════════════

    @Test
    @DisplayName("base=true（启动可用）→ per-turn 重建仍 true（保留语义，不翻 false）")
    void perTurn_preservesBaseTrue() {
        ToolPermissionContext permCtx = perTurnPermCtx(true);
        assertThat(permCtx.isBypassPermissionsModeAvailable())
            .as("per-turn 重建必须保留 base=true（启动可用时不得翻 false，静默禁用 bypass）")
            .isTrue();
    }

    // ═══════════════ ③ 6 参真实输入（settings disable）→ base=false → per-turn 保留 false ═══════════════

    @Test
    @DisplayName("6 参 settings.disableBypassPermissionsMode='disable' + dangerouslySkip → base=false → per-turn 仍 false")
    void startup_settingsDisable_thenPerTurnPreservesFalse() {
        // 6 参启动：dangerouslySkip=true + settings.disableBypassPermissionsMode=true → CC 三条件公式
        //   (bypass || dangerouslySkip) && !org门 && !settings.disable = false（settings 侧 disable 驱动）
        ToolPermissionContext base = builder.buildPermissionContext(
            new AgentState("sys", SESSION_ID, AGENT_ID), false, null, false,
            new InitialPermissionModeResolver.Input(null, true, null, true),
            InitialPermissionModeResolver.Config.defaults());
        assertThat(base.isBypassPermissionsModeAvailable())
            .as("6 参启动：settings.disableBypassPermissionsMode='disable' → 三条件公式 false（CC :938-944）")
            .isFalse();

        // 把启动 base 值喂给 per-turn 重建 → 保留 false
        ToolPermissionContext perTurn = builder.buildPermissionContext(
            new AgentState("sys", SESSION_ID, AGENT_ID), false, PermissionMode.BYPASS_PERMISSIONS, false,
            base.isBypassPermissionsModeAvailable());
        assertThat(perTurn.isBypassPermissionsModeAvailable())
            .as("per-turn 5 参携带 base=false → 保留 false（settings 侧禁用门在 per-turn 不失效）")
            .isFalse();
    }
}
