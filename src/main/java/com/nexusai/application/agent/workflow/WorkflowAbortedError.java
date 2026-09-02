package com.nexusai.application.agent.workflow;

/**
 * workflow 被 abort（kill）· 对齐 CC {@code engine/errors.ts:10-15 WorkflowAbortedError}。
 *
 * <p>runWorkflow 识别它为 {@code killed} 终态，不伪装成 dead 正常完成。</p>
 */
public class WorkflowAbortedError extends RuntimeException {

    public WorkflowAbortedError() {
        super("workflow has been aborted");
    }
}
