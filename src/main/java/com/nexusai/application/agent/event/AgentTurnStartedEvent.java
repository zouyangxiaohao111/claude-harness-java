package com.nexusai.application.agent.event;

import com.nexusai.application.agent.AgentState;

/**
 * Agent Turn 启动事件。每个 turn 进入时发布一次（在 state.incrementTurn + maxTurns/cancel
 * 检查通过之后，stream 调用之前）。
 *
 * <p>典型用途：每 turn 计时器、turn-level metric（per-turn latency / tokens）。
 *
 * <p><b>不会发布的情况</b>：MAX_TURNS 触发 / 循环顶部检测到 cancel —— 此时 turn 实际未启动。
 * 详见 {@link com.nexusai.application.agent.LlmAgentLoop#loop} 的循环顶部逻辑。
 *
 * @see AgentTurnCompletedEvent
 * @see AgentLoopStartedEvent
 */
public record AgentTurnStartedEvent(AgentState state,
                                    int turnCount,
                                    String modelName) {

    public AgentTurnStartedEvent {
        if (state == null) throw new IllegalArgumentException("state is null");
        if (turnCount < 1) throw new IllegalArgumentException("turnCount must be >= 1");
        if (modelName == null) throw new IllegalArgumentException("modelName is null");
    }
}
