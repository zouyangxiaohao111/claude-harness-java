package com.nexusai.application.agent;

/**
 * taskBudget · 对齐 CC query.ts:197 {@code { total: number }}。
 *
 * <p>{@code total} 是整段 agentic turn 的 API 预算（output_config.task_budget）；
 * {@code remaining} <b>不是本输入契约的一部分</b>——CC 中 remaining 是 query loop 局部量
 * （query.ts:291 {@code let taskBudgetRemaining: number | undefined = undefined}，初始 undefined），
 * 每次压缩成功后结转更新（query.ts:508-515 + 1138-1146），仅在跨压缩边界时注入 provider。
 *
 * <p>[IMP-16 REWORK] 本 record 收敛为 CC {@code {total}} 输入契约：不再暴露 remaining 可注入输入，
 * 消除「注入 remaining≠total 时首次结转基准漂移」（OD-13 偏差消解，对齐 query.ts:513
 * {@code (taskBudgetRemaining ?? params.taskBudget.total)}——基准恒以 total 起算）。
 * loop 内部以 {@code Integer[] taskBudgetRemaining} holder 维护结转值（初始 null = CC undefined）。
 *
 * <p><b>线格式</b>：provider 请求体 {@code output_config.task_budget={type:'tokens',total,remaining?}}
 * 使用独立 wire record {@link com.nexusai.infra.llm.TaskBudgetParam}（镜像 CC claude.ts:474-478
 * TaskBudgetParam），本类只管 CC 输入契约。
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 完整参数（CC query.ts:197 仅 total）
 * TaskBudget budget = new TaskBudget(200_000);
 * </pre>
 *
 * @see RunRequest#taskBudget
 * @see com.nexusai.infra.llm.TaskBudgetParam
 * @see LlmAgentLoop#run(RunRequest)
 */
public record TaskBudget(int total) {

    /**
     * 紧凑构造器：校验 total 必须为正数（对齐 CC 运行时校验语义）。
     *
     * @throws IllegalArgumentException if total &lt;= 0
     */
    public TaskBudget {
        if (total <= 0) {
            throw new IllegalArgumentException("taskBudget.total must be positive, got " + total);
        }
    }
}
