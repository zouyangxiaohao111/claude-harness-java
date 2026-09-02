package com.nexusai.application.agent.workflow;

/**
 * 引擎进度事件判别联合（8 变体）· CC original: {@code ProgressEvent}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:85-125)。
 *
 * <p><b>所有变体都带 {@code runId}</b>，adapter 借此把事件路由到对应 task（支持多 workflow 并发）。
 * {@link #runId()} 统一暴露。
 *
 * <p><b>8 类不是 7 类</b>：{@code log}（{@link WorkflowLog}）变体也要建（引擎层确实发射 log 事件，
 * store 层才显式 early-exit 忽略，types-doc §0 修正）。
 */
public sealed interface ProgressEvent
        permits ProgressEvent.RunStarted, ProgressEvent.PhaseStarted, ProgressEvent.PhaseDone,
                ProgressEvent.AgentStarted, ProgressEvent.AgentDone, ProgressEvent.AgentProgress,
                ProgressEvent.WorkflowLog, ProgressEvent.RunDone {

    /** 所有变体必带 runId · CC original: types.ts:86 注释「全部变体都带 runId」 */
    String runId();

    /**
     * 运行开始 · CC original: {@code {type:'run_started', runId, workflowName, meta}} (types.ts:87)。
     * store 设 status='running'、declaredPhases、description。
     *
     * @param runId        CC original: {@code runId}
     * @param workflowName CC original: {@code workflowName}
     * @param meta         CC original: {@code meta | null} — 声明期阶段列表 + 描述
     */
    record RunStarted(String runId, String workflowName, WorkflowMeta meta) implements ProgressEvent {
    }

    /**
     * 阶段开始 · CC original: {@code {type:'phase_started', runId, phase}} (types.ts:88)。
     * store 无则 push {title,'running'} 并设 currentPhase。
     */
    record PhaseStarted(String runId, String phase) implements ProgressEvent {
    }

    /**
     * 阶段结束 · CC original: {@code {type:'phase_done', runId, phase}} (types.ts:89)。
     * store 该 phase.status='done'，currentPhase===phase 则清 null。
     */
    record PhaseDone(String runId, String phase) implements ProgressEvent {
    }

    /**
     * 子 agent 启动 · CC original: {@code {type:'agent_started', runId, agentId, label?, phase?}} (types.ts:90)。
     * agentId 为引擎盖章数字唯一 id（agentIdSeq 自增，精确关联 started/done，修掉旧 LIFO 竞态）。
     */
    record AgentStarted(String runId, int agentId, String label, String phase) implements ProgressEvent {
    }

    /**
     * 子 agent 结束 · CC original: {@code {type:'agent_done', runId, agentId, label?, phase?, result}} (types.ts:91)。
     * 含 journal 命中重放路径 / skip 路径。
     */
    record AgentDone(String runId, int agentId, String label, String phase, AgentRunResult result)
            implements ProgressEvent {
    }

    /**
     * 高频实时 token/工具数 · CC original:
     * {@code {type:'agent_progress', runId, agentId, label?, phase?, tokenCount, toolCount}} (types.ts:92)。
     */
    record AgentProgress(String runId, int agentId, String label, String phase,
                         int tokenCount, int toolCount) implements ProgressEvent {
    }

    /**
     * 日志事件 · CC original: {@code {type:'log', runId, message}} (types.ts:93)。
     * 引擎层发射；store 显式 early-exit 忽略，但 bus 上仍广播给其他订阅者（telemetry 等）。
     * 命名用 WorkflowLog 避免与 Java 内置 Log 冲突（P0-plan §2 对齐要点 5）。
     */
    record WorkflowLog(String runId, String message) implements ProgressEvent {
    }

    /**
     * 终态 · CC original: {@code {type:'run_done', runId, status, returnValue?, error?}} (types.ts:94)。
     * status: 'completed' | 'failed' | 'killed'；shutdown-kill 也路由到 KILLED。
     *
     * @param runId       CC original: {@code runId}
     * @param status      CC original: {@code status} — COMPLETED/FAILED/KILLED
     * @param returnValue CC original: {@code returnValue?} — completed 的返回值
     * @param error       CC original: {@code error?} — failed 的错误信息
     */
    record RunDone(String runId, RunStatus status, Object returnValue, String error) implements ProgressEvent {
    }

    /**
     * {@code run_done.status} 三值 · CC original: types.ts:128-132 {@code 'completed'|'failed'|'killed'}。
     * 枚举序 COMPLETED/FAILED/KILLED → ordinal 0/1/2 对齐 telemetry status 映射（ports.ts:84-86）。
     */
    enum RunStatus {
        COMPLETED,
        FAILED,
        KILLED
    }
}
