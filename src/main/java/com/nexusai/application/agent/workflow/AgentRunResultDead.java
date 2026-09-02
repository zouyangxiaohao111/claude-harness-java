package com.nexusai.application.agent.workflow;

/**
 * {@code dead} 变体 · CC original: {@code {kind:'dead', reason?, detail?}} (types.ts:53-70)。
 *
 * @param reason CC original: {@code reason?} — 5 枚举（见 {@link AgentRunResult.DeadReason}）
 * @param detail CC original: {@code detail?} — 错误信息/文本预览，仅供日志，不给终端用户
 */
public record AgentRunResultDead(AgentRunResult.DeadReason reason, String detail)
        implements AgentRunResult {
}
