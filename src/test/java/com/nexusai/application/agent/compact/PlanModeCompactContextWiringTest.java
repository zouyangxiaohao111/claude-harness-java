package com.nexusai.application.agent.compact;

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
 * [RV-E-01 GAP-03] 压缩上下文 plan mode 读侧接线守卫。
 *
 * <p><b>意图 (WHY · CLAUDE.md 规则 9)</b>: {@code populatePlanModeAttachment}（compactConversation
 * step 10）经 {@code ctx.isInPlanMode()} 判定是否重注入 plan_mode 附件（对齐 CC compact.ts:552-555
 * {@code createPlanModeAttachmentIfNeeded}），而 {@code isInPlanMode()} 读
 * {@code ctx.getToolUseContext().getAppState()}（CompactConversationContext.java:169-180）。若
 * auto/manual 路径从不把 ToolUseContext 接线进 ctx，则 isInPlanMode() 恒 false → plan_mode 附件
 * 压缩后从不重注入（死链）。本测试把「isInPlanMode 依赖 toolUseContext 接线」显式契约化：
 * 删 {@code setToolUseContext} 即 RED。
 */
class PlanModeCompactContextWiringTest {

    /** 构造 PLAN 模式 TUC：getAppState 返回含 toolPermissionContext.mode=PLAN 的 appState（对齐 EnterPlanModeTool 写侧）。 */
    private static ToolUseContext planModeTuc(PermissionMode mode) {
        UUID sid = UUID.randomUUID();
        Map<String, Object> appState = Map.of(
            "toolPermissionContext", ToolPermissionContext.strict(mode));
        // [session-id-short] of(agentId, sessionId)：agentId=UUID sid，sessionId=short
        return ToolUseContext.of(
            sid, "sess-" + sid.toString().substring(0, 8), mode,
            List.of(), "", AbortController.NOOP, List.of(),
            ToolPermissionContext.strict(mode), mode,
            Map.of(), false, "", null,
            null, null, e -> {},
            s -> appState, f -> {}, sm -> {}, sdk -> {});
    }

    @Test
    @DisplayName("auto 主路径: buildAutoContext 把 ToolUseContext 接线进 ctx → isInPlanMode()==true")
    void buildAutoContext_wiresToolUseContext() {
        ToolUseContext tuc = planModeTuc(PermissionMode.PLAN);

        CompactConversationContext ctx = CompactConversation.buildAutoContext(tuc, "model", "compact", null);

        assertThat(ctx.getToolUseContext())
            .as("auto 主路径必须把 ToolUseContext 接线进 ctx（否则 isInPlanMode 恒 false）")
            .isNotNull();
        assertThat(ctx.isInPlanMode())
            .as("plan 模式 TUC → isInPlanMode 必须读真实 plan mode")
            .isTrue();
    }

    @Test
    @DisplayName("[T9 改锚] auto 回落 ctx **不**携带 toolUseContext（AutoCompactor.toolUseContext 字段已删）—— plan mode 读侧唯一来源 = buildAutoContext")
    void autoCompactorFallback_doesNotCarryToolUseContext() {
        // ── 改锚说明（S1 轨 III · T9 2026-09-14）──────────────────────────────
        //   原用例断言「auto.setToolUseContext(tuc) → 回落 ctx 的 isInPlanMode()==true」。
        //   AutoCompactor.toolUseContext 字段与 setToolUseContext 已**删除**（生产 0 写入方；
        //   唯一调用点就是本用例）⇒ 该行为**已不存在**。新契约（本用例锁的）：
        //   ①回落 ctx 的 toolUseContext 恒 null（⇒ isInPlanMode 恒 false，不会误注入 plan_mode 附件）；
        //   ②plan mode 读侧的唯一合法来源是主路径 buildAutoContext（见本文件上一个用例）。
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult("<summary>x</summary>", null));

        CompactConversationContext ctx = auto.buildDefaultCompactConversationContext(null, "user");

        assertThat(ctx.getToolUseContext())
            .as("回落 ctx 不得携带 toolUseContext（字段已删；携带 ⇒ 说明有人把身份/上下文塞回了单例）")
            .isNull();
        assertThat(ctx.isInPlanMode())
            .as("无 toolUseContext ⇒ isInPlanMode 必须 false（守卫：不误注入 plan_mode 附件）")
            .isFalse();
        assertThat(auto.getClass().getDeclaredMethods())
            .as("AutoCompactor.setToolUseContext 必须已不存在（T9 删除终态）")
            .noneMatch(m -> "setToolUseContext".equals(m.getName()));
    }

    @Test
    @DisplayName("非 plan 模式 TUC → isInPlanMode()==false（守卫：不误注入 plan_mode）")
    void defaultMode_isInPlanModeFalse() {
        ToolUseContext tuc = planModeTuc(PermissionMode.DEFAULT);

        CompactConversationContext ctx = CompactConversation.buildAutoContext(tuc, "model", "compact", null);

        assertThat(ctx.getToolUseContext()).isNotNull();
        assertThat(ctx.isInPlanMode())
            .as("DEFAULT 模式 TUC → isInPlanMode 必须 false（避免误注入 plan_mode）")
            .isFalse();
    }
}
