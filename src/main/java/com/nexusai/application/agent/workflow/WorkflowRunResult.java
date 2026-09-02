package com.nexusai.application.agent.workflow;

/**
 * 引擎 run 结果 · CC original: {@code WorkflowRunResult}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:128-132)。
 *
 * <p><b>W-1c 协调声明</b>：{@code status} 返回顶层 {@link RunStatus}（W-1e WorkflowServiceImpl.routeTerminal
 * 比较 {@code result.status() == RunStatus.COMPLETED} 需要顶层枚举）。progress 事件侧使用
 * {@link ProgressEvent.RunStatus}（嵌套），引擎 emit run_done 时转换。</p>
 *
 * @param status      CC original: {@code status} (types.ts:129) — completed | failed | killed
 * @param returnValue CC original: {@code returnValue?} (types.ts:130) — completed 时脚本返回值
 * @param error       CC original: {@code error?} (types.ts:131) — failed 时错误消息
 */
public record WorkflowRunResult(RunStatus status, Object returnValue, String error) {

    /** 便捷构造 completed。 */
    public static WorkflowRunResult completed(Object returnValue) {
        return new WorkflowRunResult(RunStatus.COMPLETED, returnValue, null);
    }

    /** 便捷构造 failed。 */
    public static WorkflowRunResult failed(String error) {
        return new WorkflowRunResult(RunStatus.FAILED, null, error);
    }

    /** 便捷构造 killed。 */
    public static WorkflowRunResult killed() {
        return new WorkflowRunResult(RunStatus.KILLED, null, null);
    }
}
