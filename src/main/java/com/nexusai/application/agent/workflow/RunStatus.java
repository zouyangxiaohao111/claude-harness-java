package com.nexusai.application.agent.workflow;

/**
 * workflow run 终态 · CC original: {@code 'completed' | 'failed' | 'killed'}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:128-132 WorkflowRunResult.status)。
 *
 * <p><b>W-1c 协调声明</b>：并发 subagent 对终态枚举存在两处定义——本顶层 {@link RunStatus}（W-1e
 * WorkflowServiceImpl.routeTerminal 比较用）与 {@link ProgressEvent.RunStatus}（嵌套，progress 事件用）。
 * W-1c 采用顶层 {@link RunStatus} 作为 {@link WorkflowRunResult#status()} 返回类型，引擎在 emit
 * run_done 时转换为 {@link ProgressEvent.RunStatus}（见 WorkflowRunEngine.emitTerminalAndDone）。
 * 主 agent 合入时以 DEC-P0-01 reconcile（双枚举保留 or 归一）。</p>
 */
public enum RunStatus {
    /** CC original: 'completed' */
    COMPLETED,
    /** CC original: 'failed' */
    FAILED,
    /** CC original: 'killed' — workflow 被 abort（WorkflowAbortedError），不伪装成正常完成。 */
    KILLED
}
