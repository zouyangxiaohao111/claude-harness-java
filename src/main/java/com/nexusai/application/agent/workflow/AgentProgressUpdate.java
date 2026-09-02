package com.nexusai.application.agent.workflow;

/**
 * agent 运行中的进度快照（onProgress 回调载荷）· 对齐 CC {@code types.ts:31-34 AgentProgressUpdate}。
 *
 * <p>W-1c 支撑集。后端循环累计 token/tool 计数后 emit agent_progress 事件。</p>
 */
public record AgentProgressUpdate(
        /** CC original: tokenCount (types.ts:32) — 已累计 token 数。 */
        int tokenCount,
        /** CC original: toolCount (types.ts:33) — 已累计工具调用数。 */
        int toolCount
) {
}
