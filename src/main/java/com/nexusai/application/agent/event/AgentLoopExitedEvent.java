package com.nexusai.application.agent.event;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.AgentState.ExitReason;

/**
 * AgentLoop 退出事件。{@link com.nexusai.application.agent.LlmAgentLoop#run} 结束时
 * 必发布一次（无论何种 exit reason）。
 *
 * <p>典型用途：
 * <ul>
 *   <li>总耗时统计（listener 自己记录 start 时间）</li>
 *   <li>失败原因分布（按 exitReason 打点）</li>
 *   <li>trace span 结束</li>
 *   <li>如果需要：stream 截断（MAX_OUTPUT_TOKENS）时上报重试</li>
 * </ul>
 *
 * <p><b>状态引用语义</b>：{@link #state()} 是 loop 退出时的最终 state（含 exitReason /
 * messages / turnCount / lastError）。listener 应只读。
 *
 * @see AgentLoopStartedEvent
 */
public record AgentLoopExitedEvent(AgentState state,
                                   ExitReason exitReason,
                                   int totalTurns) {

    public AgentLoopExitedEvent {
        if (state == null) throw new IllegalArgumentException("state is null");
        if (exitReason == null) throw new IllegalArgumentException("exitReason is null");
        if (totalTurns < 0) throw new IllegalArgumentException("totalTurns must be >= 0");
    }
}
