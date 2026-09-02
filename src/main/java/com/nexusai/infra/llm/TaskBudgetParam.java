package com.nexusai.infra.llm;

/**
 * [IMP-16 REWORK] task_budget 线格式 · 对齐 CC {@code TaskBudgetParam}
 * （Open-ClaudeCode/src/services/api/claude.ts:474-478）。
 *
 * <pre>{@code
 * type TaskBudgetParam = {
 *   type: 'tokens'
 *   total: number
 *   remaining?: number
 * }
 * }</pre>
 *
 * <p><b>与输入契约 {@link com.nexusai.application.agent.TaskBudget} 的区别</b>：
 * 输入契约（query.ts:197）仅 {@code {total}}；本类镜像 callModel options.taskBudget
 * （query.ts:699-706）{@code {total, remaining?}}——remaining 由 loop 内部维护
 * （query.ts:291 taskBudgetRemaining，初始 undefined），经 ModelCaller →
 * AnthropicSdkProvider → buildMessageParams 写入请求体 {@code output_config.task_budget}。
 *
 * <p>{@code type: 'tokens'} 是常量线值，在 buildRequestBody 序列化时固定输出
 * （claude.ts:492），不体现在本 record 字段中。
 *
 * @param total     API task_budget.total（整段 agentic turn 的 token 预算）
 * @param remaining API task_budget.remaining（跨压缩结转后的剩余预算；null = CC undefined，
 *                  序列化时省略该字段——对齐 claude.ts:494 {@code remaining !== undefined}）
 */
public record TaskBudgetParam(int total, Integer remaining) {

    /**
     * 便捷构造器：仅 total（remaining = null = CC undefined，不注入 remaining 字段）。
     */
    public TaskBudgetParam(int total) {
        this(total, null);
    }
}
