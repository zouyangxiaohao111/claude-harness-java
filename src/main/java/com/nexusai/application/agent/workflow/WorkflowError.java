package com.nexusai.application.agent.workflow;

/**
 * 引擎级预期错误（脚本错误 / 上限 / 嵌套）· 对齐 CC {@code engine/errors.ts:2-7 WorkflowError}。
 */
public class WorkflowError extends RuntimeException {

    public WorkflowError(String message) {
        super(message);
    }

    public WorkflowError(String message, Throwable cause) {
        super(message, cause);
    }
}
