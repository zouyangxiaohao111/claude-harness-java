package com.nexusai.application.agent.workflow;

/**
 * 预算耗尽 · 对齐 CC {@code engine/budget.ts:1-6 BudgetExhaustedError}。
 *
 * <p>注意：真源在 budget.ts 而非 errors.ts（勿误放错误层级）。</p>
 */
public class BudgetExhaustedError extends WorkflowError {

    public BudgetExhaustedError() {
        super("workflow token budget exhausted (budget.total reached the cap)");
    }
}
