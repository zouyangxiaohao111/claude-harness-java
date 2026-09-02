package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [B-2] SubagentExecutor shouldAvoidPermissionPrompts 落地验证 · 对齐 CC runAgent.ts:440-451.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: 旧实现 {@code shouldAvoidPermissionPrompts = isAsync}
 * 漏掉 CC 的 bubble 例外 (fork 子 agent 恒可冒泡弹窗 → false), 且计算结果只传参不落地
 * (runSubagentQueryLoop 死参数, 函数体未使用). B-2 修复:
 * <ol>
 *   <li>公式降级: {@code shouldAvoidPrompts = (agentPermissionMode === 'bubble' ? false : isAsync)}
 *       (Java AgentDefinition 无 canShowPermissionPrompts, 完整透传留 P3 H9-GAP-6)</li>
 *   <li>flag 写入子 base TUC permCtx (SubagentExecutor 复用 H9 v3 Gap① 位置)</li>
 *   <li>per-turn 重建保真: AgentLoopContext.toolExecContext → PermissionContextBuilder 5 参
 *       重载透传 flag, 防每轮重建把 flag 覆盖回 false</li>
 * </ol>
 *
 * @since B-2 (P0-2 收尾)
 */
@DisplayName("[B-2] SubagentExecutor shouldAvoidPermissionPrompts 落地")
class SubagentExecutorShouldAvoidPromptsTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String SESSION_ID = "00000000-0000-0000-0000-0000000000b2";

    // ─────────────────────── 公式 (CC runAgent.ts:440-451 降级) ───────────────────────

    @Test
    @DisplayName("bubble (fork) + async → false (bubble 冒泡到父终端恒可弹窗)")
    void bubbleAsync_neverAvoidsPrompts() {
        // WHY: CC :443-445 — agentPermissionMode==='bubble' → false, 与 isAsync 无关.
        //   fork 子 agent 的权限弹窗冒泡到父终端 (gate bubble 分支), 不得自动拒绝.
        assertThat(SubagentExecutor.resolveShouldAvoidPermissionPrompts(PermissionMode.BUBBLE, true))
            .as("bubble + async → false (CC runAgent.ts:443-445 bubble 例外)")
            .isFalse();
        assertThat(SubagentExecutor.resolveShouldAvoidPermissionPrompts(PermissionMode.BUBBLE, false))
            .as("bubble + sync → false")
            .isFalse();
    }

    @Test
    @DisplayName("非 bubble + async → true (异步后台 agent 无 UI → 自动拒绝弹窗)")
    void asyncNonBubble_avoidsPrompts() {
        // WHY: CC :446 — 非 bubble 异步 agent 不能弹 UI → shouldAvoidPrompts=true.
        //   旧实现漏掉 bubble 例外; 本断言防回归 (若公式退化为恒 true, fork 弹窗路径全毁).
        assertThat(SubagentExecutor.resolveShouldAvoidPermissionPrompts(PermissionMode.DEFAULT, true))
            .as("DEFAULT + async → true (CC runAgent.ts:440-451)")
            .isTrue();
    }

    @Test
    @DisplayName("非 bubble + sync → false (同步子 agent 可弹窗)")
    void syncNonBubble_showsPrompts() {
        assertThat(SubagentExecutor.resolveShouldAvoidPermissionPrompts(PermissionMode.DEFAULT, false))
            .as("DEFAULT + sync → false")
            .isFalse();
        assertThat(SubagentExecutor.resolveShouldAvoidPermissionPrompts(PermissionMode.ACCEPT_EDITS, false))
            .as("ACCEPT_EDITS + sync → false")
            .isFalse();
    }

    // ─────────────────────── PermissionContextBuilder 5 参 (每轮重建保真) ───────────────────────

    @Test
    @DisplayName("buildPermissionContext 5 参: shouldAvoidPermissionPrompts 透传 (防每轮重建覆盖回 false)")
    void builder_overloadPropagatesAvoidFlag() {
        // WHY: 旧便捷重载（已删除）硬编码 false — 即使 SubagentExecutor 把 flag 写入 base TUC,
        //   每轮重建也会覆盖回 false. 5 参重载是 per-turn 保真的唯一来源.
        PermissionContextBuilder builder = new PermissionContextBuilder();
        AgentState state = new AgentState("sys", SESSION_ID, AGENT_ID);

        ToolPermissionContext withFlag = builder.buildPermissionContext(state, false, PermissionMode.DEFAULT, true, true);
        assertThat(withFlag.shouldAvoidPermissionPrompts())
            .as("5 参显式 true → flag 必须保真")
            .isTrue();
        ToolPermissionContext noFlag = builder.buildPermissionContext(state, false, PermissionMode.DEFAULT, false, true);
        assertThat(noFlag.shouldAvoidPermissionPrompts())
            .as("5 参显式 false → flag false")
            .isFalse();
        ToolPermissionContext defaultCtx = builder.buildPermissionContext(state, false, null, false, true);
        assertThat(defaultCtx.shouldAvoidPermissionPrompts())
            .as("默认路径 (shouldAvoid=false) 保持 false (主线程/同步子 agent)")
            .isFalse();
    }

    // ─────────────────────── per-turn 保真 (toolExecContext) ───────────────────────

    @Test
    @DisplayName("toolExecContext: base TUC permCtx flag=true → per-turn permCtx flag=true (每轮保真)")
    void toolExecContext_preservesAvoidFlagFromBase() {
        // WHY: SubagentExecutor 把 flag 写入子 base TUC permCtx; 每轮重建必须保真 —
        //   若 wiring 丢失 (builder 硬编码 false), 异步子 agent 的自动拒绝语义丢失.
        ToolPermissionContext basePermCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            true, true, Map.of(), true, false, null);
        ToolUseContext baseTuc = ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), basePermCtx, PermissionMode.DEFAULT);

        AgentLoopContext ctx = ctxWithPermissionContextBuilder(new PermissionContextBuilder());
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, baseTuc, new AgentState("sys", SESSION_ID, AGENT_ID), Map.of());

        assertThat(perTurn.permissionContext()).isNotNull();
        assertThat(perTurn.permissionContext().shouldAvoidPermissionPrompts())
            .as("per-turn 重建必须保真 base 的 flag=true (CC runAgent.ts:440-451 → builder 5 参)")
            .isTrue();
    }

    @Test
    @DisplayName("toolExecContext: base TUC permCtx flag=false → per-turn flag=false (不误置)")
    void toolExecContext_keepsFalseWhenBaseFalse() {
        // WHY: 主线程 / 同步子 agent base flag=false → per-turn 不得误置 true
        //   (否则所有权限检查走自动拒绝, 弹窗路径全毁).
        ToolPermissionContext basePermCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            true, true, Map.of(), false, false, null);
        ToolUseContext baseTuc = ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), basePermCtx, PermissionMode.DEFAULT);

        AgentLoopContext ctx = ctxWithPermissionContextBuilder(new PermissionContextBuilder());
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, baseTuc, new AgentState("sys", SESSION_ID, AGENT_ID), Map.of());

        assertThat(perTurn.permissionContext()).isNotNull();
        assertThat(perTurn.permissionContext().shouldAvoidPermissionPrompts())
            .as("base flag=false → per-turn 保持 false (主线程/同步子 agent 不误置)")
            .isFalse();
    }

    // ─────────────────────── 辅助 ───────────────────────

    /** 最小 AgentLoopContext · 注入真实 PermissionContextBuilder (对齐 H9V2GapFixTest 模式). */
    private static AgentLoopContext ctxWithPermissionContextBuilder(PermissionContextBuilder builder) {
        // [ContextCompact 对齐合并] record 34 组件: 20=featureFlags, 29=permissionContextBuilder
        //   (旧签名含 CompactContext/PromptTooLongHandler 位已被移除, 补 3 个 null 位)
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            builder, null, null, null);
    }
}
