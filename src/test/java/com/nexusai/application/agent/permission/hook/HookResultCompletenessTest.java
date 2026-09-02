package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.hook.PermissionBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session I · P3-1 HookResult 字段完整性测试 · 对齐 CC Open-ClaudeCode/src/utils/hooks.ts:338-357
 * {@code interface HookResult}.
 *
 * <p><b>[Session M P1-2] M.2.2 改造 (Pattern #4)</b>: HookResult 从 21 字段缩减到 16 字段
 * (撤回 5 个 awaiting 字段, 由 ParsedHookJSONOutput 承载, I-1 对齐 CC). 本测试覆盖仍然保留
 * 在 HookResult 的 3 个 CC 字段 (outcome / stopReason / permissionBehavior), 已撤回的
 * 5 个字段测试已随 HookOutcomeRecordSplitTest 删除 (I-1, 数据流由 ParsedHookJSONOutput 等价覆盖).
 * [Session S07] permissionRequestResult 恢复顶层回填 (CC hooks.ts:2882-2886 yield), 不影响本测试.
 *
 * <h2>测试用例 (3 项, 每个保留字段 1 测试)</h2>
 * <ol>
 *   <li>{@link #outcomeFieldExistsAndReturnsValue()} — outcome 字段读写</li>
 *   <li>{@link #stopReasonFieldExistsAndReturnsValue()} — stopReason 字段读写</li>
 *   <li>{@link #permissionBehaviorFieldReusesEnum()} — permissionBehavior 复用 PermissionBehavior</li>
 * </ol>
 */
class HookResultCompletenessTest {

    // ─────────── 1. outcome 字段 (CC hooks.ts:342) ───────────

    @Test
    @DisplayName("I-1 outcome 字段读写 · CC original: outcome (Open-ClaudeCode/src/utils/hooks.ts:342)")
    void outcomeFieldExistsAndReturnsValue() {
        // [H4+H3+S07] HookResult 14 字段 (移除 hookPermissionResult/hookUpdatedInput/hookSource;
        //   H3 加 hook; S07 加 permissionRequestResult)
        GenericHook.HookResult result = new GenericHook.HookResult(false, null, null, null, null, null, null,
        null, null,
        GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);

        assertThat(result.outcome())
            .as("outcome 字段必须等于 BLOCKING (CC original: 'blocking')")
            .isEqualTo(GenericHook.HookOutcome.BLOCKING);
    }

    // ─────────── 2. stopReason 字段 (CC hooks.ts:344) ───────────

    @Test
    @DisplayName("I-2 stopReason 字段读写 · CC original: stopReason (Open-ClaudeCode/src/utils/hooks.ts:344)")
    void stopReasonFieldExistsAndReturnsValue() {
        // [H4+H3+S07] HookResult 14 字段
        GenericHook.HookResult result = new GenericHook.HookResult(false, null, null, null, null, null, null,
        null, null,
        null, "cc_stop_text", null, null, null, null, null, null, null);

        assertThat(result.stopReason())
            .as("stopReason 字段必须等于 'cc_stop_text'")
            .isEqualTo("cc_stop_text");
    }

    // ─────────── 3. permissionBehavior 字段 (CC hooks.ts:345) ───────────

    @Test
    @DisplayName("I-3 permissionBehavior 字段复用 PermissionBehavior · CC original: permissionBehavior (Open-ClaudeCode/src/utils/hooks.ts:345)")
    void permissionBehaviorFieldReusesEnum() {
        // [H4+H3+S07] HookResult 14 字段
        GenericHook.HookResult result = new GenericHook.HookResult(false, null, null, null, null, null, null,
        null, null,
        null, null, PermissionBehavior.ALLOW, null, null, null, null, null, null);

        assertThat(result.permissionBehavior())
            .as("permissionBehavior 字段必须等于 ALLOW, 且类型为 PermissionBehavior (CC hooks.ts:349 union 对齐)")
            .isEqualTo(PermissionBehavior.ALLOW)
            .isInstanceOf(PermissionBehavior.class);
    }

    // ─────────── 4. awaiting 4 字段顶层承载 (2026-08-12 探查 △-01) ───────────

    @Test
    @DisplayName("△-01 initialUserMessage/watchPaths/elicitationResponse/elicitationResultResponse 顶层承载 · CC hooks.ts:348/352-355")
    void awaitingFieldsCarriedOnHookResult() {
        // [2026-08-12 △-01] HookResult 从 15 字段扩展到 19 字段 (追加 4 个 awaiting 字段)。
        //   WHY: CC HookResult 18 字段含 initialUserMessage/watchPaths/elicitationResponse/
        //   elicitationResultResponse (utils/hooks.ts:338-357), 旧实现只存 ParsedHookJSONOutput,
        //   executeEvent 折叠链上静默丢失 (探查报告 △-01)。消费方 (ElicitationHandler /
        //   SessionStart) 从 HookResult 顶层读取, 不再丢决策。
        GenericHook.HookResult result = new GenericHook.HookResult(false, null, null, null, null, null, null,
        null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null,
        "initial-user-msg", java.util.List.of("/watch/path"),
        new ElicitationResponse("accept", null), new ElicitationResponse("cancel", null));

        assertThat(result.initialUserMessage())
            .as("initialUserMessage 必须顶层承载 (CC hooks.ts:348)")
            .isEqualTo("initial-user-msg");
        assertThat(result.watchPaths())
            .as("watchPaths 必须顶层承载 (CC hooks.ts:352)")
            .containsExactly("/watch/path");
        assertThat(result.elicitationResponse())
            .as("elicitationResponse 必须顶层承载 (CC hooks.ts:353)")
            .extracting(ElicitationResponse::action)
            .isEqualTo("accept");
        assertThat(result.elicitationResultResponse())
            .as("elicitationResultResponse 必须顶层承载 (CC hooks.ts:355)")
            .extracting(ElicitationResponse::action)
            .isEqualTo("cancel");
    }
}
