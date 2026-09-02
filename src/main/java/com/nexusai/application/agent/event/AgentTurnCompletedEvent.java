package com.nexusai.application.agent.event;

import com.nexusai.application.agent.AgentState;

/**
 * Agent Turn 完成事件。每个 turn <b>成功完成后</b>发布一次（在 appendMessage 之后，
 * NORMAL 检查之前）。
 *
 * <p>典型用途：
 * <ul>
 *   <li>per-turn latency 统计</li>
 *   <li>token usage aggregation（如果有 token 计数）</li>
 *   <li>streaming 行为监控（chunks 数 / chars 数）</li>
 * </ul>
 *
 * <p><b>不会发布的情况</b>：turn 中 break（STREAM_ERROR / STREAM_TIMEOUT / INTERRUPTED /
 * ABORTED / MAX_OUTPUT_TOKENS / NO_ASSISTANT_TEXT）—— 这些场景由 {@link AgentLoopExitedEvent}
 * 统一告知，{@code exitReason()} 区分。
 *
 * @see AgentTurnStartedEvent
 * @see AgentLoopExitedEvent
 */
public record AgentTurnCompletedEvent(AgentState state,
                                      int turnCount,
                                      int chunkCount,
                                      int textLength,
                                      String finishReason) {

    public AgentTurnCompletedEvent {
        if (state == null) throw new IllegalArgumentException("state is null");
        if (turnCount < 1) throw new IllegalArgumentException("turnCount must be >= 1");
        if (chunkCount < 0) throw new IllegalArgumentException("chunkCount must be >= 0");
        if (textLength < 0) throw new IllegalArgumentException("textLength must be >= 0");
        if (finishReason == null) finishReason = "unknown";
    }
}
