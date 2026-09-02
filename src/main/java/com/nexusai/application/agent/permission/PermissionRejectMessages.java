package com.nexusai.application.agent.permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 权限拒绝消息模板 · 对齐 CC {@code utils/messages.ts:185-218}
 * (REJECT_MESSAGE / SUBAGENT_REJECT_MESSAGE + withMemoryCorrectionHint) +
 * {@code hooks/toolPermission/PermissionContext.ts:154-173} (cancelAndAbort 消息组装).
 *
 * <p><b>WHY 独立成类</b>: 拒绝消息是跨 handler 共享的契约文本 (gate abort 路径 /
 * swarm leader reject / interactive onReject 共用), 必须逐字对齐 CC 模板 —— 消息注入
 * LLM 后模型据此理解"用户不想继续", 拼错会让模型误判严重性.
 *
 * <p><b>Java 特有说明</b>:
 * <ul>
 *   <li>{@code sub = !!toolUseContext.agentId} (PermissionContext.ts:159) — Java 端
 *       ToolUseContext.agentId 恒非 null (compact ctor UUID 兜底), 无法区分主/子 agent,
 *       改用 {@code agentType != null} (仅 fork 出的子 agent 携带, 对齐 CC agentId 语义
 *       的 Java 等价, 见 {@link #buildRejectMessage})</li>
 *   <li>{@code withMemoryCorrectionHint} (messages.ts:185-197) 依赖 auto-memory 能力 +
 *       GrowthBook flag (tengu_amber_prism) — Java 端无等价物, 实现为 identity
 *       (返回原消息), 保留扩展点</li>
 * </ul>
 */
public final class PermissionRejectMessages {

    private PermissionRejectMessages() {}
    private static final Logger log = LoggerFactory.getLogger(PermissionRejectMessages.class);


    /** CC original: REJECT_MESSAGE (Open-ClaudeCode/src/utils/messages.ts:212). */
    public static final String REJECT_MESSAGE =
        "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed.";

    /** CC original: REJECT_MESSAGE_WITH_REASON_PREFIX (Open-ClaudeCode/src/utils/messages.ts:214). */
    public static final String REJECT_MESSAGE_WITH_REASON_PREFIX =
        "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n";

    /** CC original: SUBAGENT_REJECT_MESSAGE (Open-ClaudeCode/src/utils/messages.ts:216). */
    public static final String SUBAGENT_REJECT_MESSAGE =
        "Permission for this tool use was denied. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). Try a different approach or report the limitation to complete your task.";

    /** CC original: SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX (Open-ClaudeCode/src/utils/messages.ts:218). */
    public static final String SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX =
        "Permission for this tool use was denied. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). The user said:\n";

    /** CC original: CANCEL_MESSAGE (Open-ClaudeCode/src/utils/messages.ts:210-211). */
    public static final String CANCEL_MESSAGE =
        "The user doesn't want to take this action right now. STOP what you are doing and wait for the user to tell you how to proceed.";

    /** CC original: MEMORY_CORRECTION_HINT (Open-ClaudeCode/src/utils/messages.ts:176-177). */
    public static final String MEMORY_CORRECTION_HINT =
        "\n\nNote: The user's next message may contain a correction or preference. Pay close attention — if they explain what went wrong or how they'd prefer you to work, consider saving that to memory for future sessions.";

    /**
     * 追加 memory correction hint · 对齐 CC {@code withMemoryCorrectionHint}
     * (Open-ClaudeCode/src/utils/messages.ts:185-197).
     *
     * <p>CC: auto-memory 启用 + GrowthBook flag (tengu_amber_prism) 时追加 hint.
     * Java 端无 auto-memory / feature flag 等价物 → 恒 identity (返回原消息);
     * 未来接入 memory 能力时在此追加 {@link #MEMORY_CORRECTION_HINT}.
     *
     * @param message 拒绝/取消消息
     * @return 原消息 (Java 端 feature 未启用)
     */
    public static String withMemoryCorrectionHint(String message) {
        // Java 端无 auto-memory / GrowthBook flag (tengu_amber_prism) 等价物,
        // 与 CC flag=false 默认分支语义一致 (messages.ts:185-193): 恒 identity.
        if (log.isDebugEnabled()) {
            log.debug("PERMISSION withMemoryCorrectionHint: auto-memory 未启用, 返回原消息 (CC messages.ts:185-193 默认分支)");
        }
        return message;
    }

    /**
     * 组装 cancelAndAbort 拒绝消息 · 对齐 CC {@code cancelAndAbort}
     * (Open-ClaudeCode/src/hooks/toolPermission/PermissionContext.ts:154-173).
     *
     * <p>CC 真源 (PermissionContext.ts:159-165):
     * <pre>{@code
     * const sub = !!toolUseContext.agentId
     * const baseMessage = feedback
     *   ? `${sub ? SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX : REJECT_MESSAGE_WITH_REASON_PREFIX}${feedback}`
     *   : sub ? SUBAGENT_REJECT_MESSAGE : REJECT_MESSAGE
     * const message = sub ? baseMessage : withMemoryCorrectionHint(baseMessage)
     * }</pre>
     *
     * <p>Java 特有: {@code sub} 判定用 {@code agentType != null} (见类 JavaDoc);
     * {@code withMemoryCorrectionHint} 恒 identity.
     *
     * @param isSubagent 是否为子 agent (Java: ctx.agentType() != null)
     * @param feedback   用户反馈 (可为 null = 无反馈, 用纯模板)
     * @return 拒绝消息 (逐字对齐 CC 模板)
     */
    public static String buildRejectMessage(boolean isSubagent, String feedback) {
        // CC truthiness (feedback ? ...) 语义: 仅 null/undefined/空串走纯模板;
        // 空白串在 JS 中 truthy, 与 CC 一致走 PREFIX+反馈 分支 (PermissionContext.ts:160-164).
        boolean hasFeedback = feedback != null && !feedback.isEmpty();
        String template;
        String baseMessage;
        if (hasFeedback) {
            template = isSubagent ? "SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX"
                                  : "REJECT_MESSAGE_WITH_REASON_PREFIX";
            baseMessage = (isSubagent ? SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX
                                     : REJECT_MESSAGE_WITH_REASON_PREFIX) + feedback;
        } else {
            template = isSubagent ? "SUBAGENT_REJECT_MESSAGE" : "REJECT_MESSAGE";
            baseMessage = isSubagent ? SUBAGENT_REJECT_MESSAGE : REJECT_MESSAGE;
        }
        if (log.isDebugEnabled()) {
            log.debug("PERMISSION 拒绝消息组装: isSubagent={} hasFeedback={} 模板={} feedbackLen={}",
                isSubagent, hasFeedback, template, feedback == null ? 0 : feedback.length());
        }
        return isSubagent ? baseMessage : withMemoryCorrectionHint(baseMessage);
    }
}
