package com.nexusai.application.agent.subagent;

/**
 * AgentSummaryService 启动返回的句柄 · 对齐 CC startAgentSummarization() 返回的 {stop}.
 *
 * <p>调用方持有 handle, 任务结束 (subagent 完成 / 取消 / 错误恢复) 时调 {@link #stop()}.
 * stop 后不可重启 — CC 同语义 (stopped=true 后 scheduleNext 不再触发).
 */
public class AgentSummaryHandle {

    private final AgentSummaryService.AgentSummaryState state;

    AgentSummaryHandle(AgentSummaryService.AgentSummaryState state) {
        this.state = state;
    }

    /** 取消定时器 + 终止 in-flight summary; 幂等. */
    public void stop() {
        state.stop();
    }
}