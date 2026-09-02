package com.nexusai.application.agent.permission;

import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S11] 拒绝消息文本契约测试 · 逐字锁定 CC 模板
 * ({@code Open-ClaudeCode/src/utils/messages.ts:176-219}) 与 cancelAndAbort 组装语义
 * ({@code hooks/toolPermission/PermissionContext.ts:154-173})。
 *
 * <p><b>WHY</b>: 拒绝消息注入 LLM 上下文后模型据此理解"用户不想继续" (EVID-PERM-T03
 * #27/#43, E-CC-MSG; EVID-PERM-T10 G6/G7, E2-10)。本测试把 CC 原文硬编码进断言,
 * CC 侧任何文本变更或 Java 侧回归都会在这里红灯 —— 防回归。
 *
 * <p>CC 行号自验 (基线 e7598af2):
 * <ul>
 *   <li>MEMORY_CORRECTION_HINT: messages.ts:176-177</li>
 *   <li>CANCEL_MESSAGE: messages.ts:210-211</li>
 *   <li>REJECT_MESSAGE: messages.ts:212-213</li>
 *   <li>REJECT_MESSAGE_WITH_REASON_PREFIX: messages.ts:214-215</li>
 *   <li>SUBAGENT_REJECT_MESSAGE: messages.ts:216-217</li>
 *   <li>SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX: messages.ts:218-219</li>
 *   <li>cancelAndAbort 组装: PermissionContext.ts:159-165</li>
 *   <li>retry 消息: services/tools/toolExecution.ts:1093-1098</li>
 * </ul>
 */
class PermissionRejectMessagesTest {

    // ─────────────────────── CC messages.ts 原文 (grep 自验) ───────────────────────

    private static final String CC_CANCEL_MESSAGE =
        "The user doesn't want to take this action right now. STOP what you are doing and wait for the user to tell you how to proceed.";

    private static final String CC_REJECT_MESSAGE =
        "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed.";

    private static final String CC_REJECT_MESSAGE_WITH_REASON_PREFIX =
        "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n";

    private static final String CC_SUBAGENT_REJECT_MESSAGE =
        "Permission for this tool use was denied. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). Try a different approach or report the limitation to complete your task.";

    private static final String CC_SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX =
        "Permission for this tool use was denied. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). The user said:\n";

    private static final String CC_MEMORY_CORRECTION_HINT =
        "\n\nNote: The user's next message may contain a correction or preference. Pay close attention — if they explain what went wrong or how they'd prefer you to work, consider saving that to memory for future sessions.";

    /** CC toolExecution.ts:1096 createUserMessage({content, isMeta:true}) 原文。 */
    private static final String CC_RETRY_MESSAGE =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";

    // ─────────────────────────── 模板常量逐字契约 ───────────────────────────

    @Test
    @DisplayName("6 个拒绝/取消模板常量逐字等于 CC messages.ts:176-219")
    void constants_matchCcVerbatim() {
        assertThat(PermissionRejectMessages.CANCEL_MESSAGE).isEqualTo(CC_CANCEL_MESSAGE);
        assertThat(PermissionRejectMessages.REJECT_MESSAGE).isEqualTo(CC_REJECT_MESSAGE);
        assertThat(PermissionRejectMessages.REJECT_MESSAGE_WITH_REASON_PREFIX)
            .isEqualTo(CC_REJECT_MESSAGE_WITH_REASON_PREFIX);
        assertThat(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE)
            .isEqualTo(CC_SUBAGENT_REJECT_MESSAGE);
        assertThat(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX)
            .isEqualTo(CC_SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX);
        assertThat(PermissionRejectMessages.MEMORY_CORRECTION_HINT)
            .isEqualTo(CC_MEMORY_CORRECTION_HINT);
    }

    // ─────────────────────── cancelAndAbort 组装语义 ───────────────────────

    @Test
    @DisplayName("无反馈: 主 agent → REJECT_MESSAGE; 子 agent → SUBAGENT_REJECT_MESSAGE")
    void noFeedback_usesPlainTemplates() {
        assertThat(PermissionRejectMessages.buildRejectMessage(false, null))
            .isEqualTo(PermissionRejectMessages.REJECT_MESSAGE);
        assertThat(PermissionRejectMessages.buildRejectMessage(true, null))
            .isEqualTo(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE);
    }

    @Test
    @DisplayName("有反馈: PREFIX+反馈 原文拼接 (PermissionContext.ts:160-164)")
    void withFeedback_appendsPrefixAndFeedback() {
        String feedback = "I don't want you to edit this file.";
        assertThat(PermissionRejectMessages.buildRejectMessage(false, feedback))
            .isEqualTo(PermissionRejectMessages.REJECT_MESSAGE_WITH_REASON_PREFIX + feedback);
        assertThat(PermissionRejectMessages.buildRejectMessage(true, feedback))
            .isEqualTo(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX + feedback);
    }

    @Test
    @DisplayName("空串反馈 → 纯模板; 空白串反馈 → PREFIX+空白 (CC truthiness)")
    void emptyString_isFalsy_whitespaceIsTruthy() {
        // CC: feedback ? PREFIX+feedback : 纯模板 — 仅 null/undefined/"" falsy
        // (PermissionContext.ts:160); 空白串在 JS 中 truthy → PREFIX + 原文拼接.
        assertThat(PermissionRejectMessages.buildRejectMessage(false, ""))
            .isEqualTo(PermissionRejectMessages.REJECT_MESSAGE);
        assertThat(PermissionRejectMessages.buildRejectMessage(true, ""))
            .isEqualTo(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE);
        assertThat(PermissionRejectMessages.buildRejectMessage(false, " "))
            .isEqualTo(PermissionRejectMessages.REJECT_MESSAGE_WITH_REASON_PREFIX + " ");
    }

    @Test
    @DisplayName("主 agent 走 withMemoryCorrectionHint; Java 恒 identity 对齐 CC flag 默认关闭")
    void nonSubagent_appliesMemoryCorrectionHint_asIdentity() {
        // CC messages.ts:185-193: flag 关闭时返回原消息; Java 无 auto-memory/flag → 恒 identity.
        String base = PermissionRejectMessages.buildRejectMessage(false, "no");
        assertThat(base)
            .isEqualTo(PermissionRejectMessages.REJECT_MESSAGE_WITH_REASON_PREFIX + "no");
        assertThat(base).doesNotContain("Note: The user's next message may contain a correction");
        // 子 agent 不经过 hint (CC: sub ? baseMessage : withMemoryCorrectionHint(baseMessage))
        String sub = PermissionRejectMessages.buildRejectMessage(true, "no");
        assertThat(sub)
            .isEqualTo(PermissionRejectMessages.SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX + "no");
    }

    // ─────────────────────────── retry 消息契约 ───────────────────────────

    @Test
    @DisplayName("retry 消息: content 逐字 CC toolExecution.ts:1096 + isMeta=true + role=user")
    void retryMessage_matchesCcVerbatim_andIsMeta() {
        var retry = RetryMessageFactory.createRetryMessage("s11-test");
        assertThat(retry.content()).isEqualTo(CC_RETRY_MESSAGE);
        assertThat(retry.isMeta()).as("对齐 CC createUserMessage({isMeta:true})").isTrue();
        assertThat(retry.role()).isEqualTo(Role.user);
    }
}
