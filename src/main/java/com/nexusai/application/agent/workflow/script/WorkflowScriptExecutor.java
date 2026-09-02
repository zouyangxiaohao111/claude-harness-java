package com.nexusai.application.agent.workflow.script;

import java.util.concurrent.CompletableFuture;

/**
 * 已编译脚本的执行器（对齐 CC engine/script.ts:157 ParsedScript.execute 签名）。
 *
 * <p>CC execute 实际调用（script.ts:214-227）：</p>
 * <pre>{@code
 * fn(hooks.agent, hooks.parallel, hooks.pipeline, hooks.phase,
 *    hooks.log, hooks.workflow, args, budget, sandboxedDate, sandboxedMath)
 * }</pre>
 *
 * <p><b>10 个位置参数 = 8 业务注入 + 2 沙箱替身</b>（script-doc §3.1）：</p>
 * <ol>
 *   <li>agent / parallel / pipeline / phase / log / workflow：{@link WorkflowHooks} 的 6 个 hook（位置 1-6）</li>
 *   <li>args：execute 的调用参数（Workflow tool input 透传，位置 7）</li>
 *   <li>budget：预算对象（CC ctx.resources.budget，位置 8）</li>
 *   <li>Date / Math：{@link DateMathSandbox} 确定性沙箱替身（位置 9-10）</li>
 * </ol>
 *
 * <p>接线现状（G-2）：{@link WorkflowScriptParser#parse} 在未注入 executor 时默认编译
 * {@link RestrictedScriptExecutor}（受限 DSL 解释器，部分对齐 CC，见其类 Javadoc）——生产
 * WorkflowServiceImpl → new WorkflowRunEngine() 已由此接通真实执行，不再命中 {@link #NOT_WIRED}。
 * {@link #NOT_WIRED} 仅保留为<b>显式</b>的「未接线」兜底（API 稳定性，fail loud），不再作默认。</p>
 */
@FunctionalInterface
public interface WorkflowScriptExecutor {

    /**
     * 执行脚本函数体。
     *
     * @param hooks  6 个 hook 能力（agent/parallel/pipeline/phase/log/workflow）
     * @param args   调用参数（位置 7）
     * @param budget 预算对象（位置 8）
     * @return 脚本返回结果
     */
    CompletableFuture<Object> execute(WorkflowHooks hooks, Object args, Object budget);

    /**
     * 显式「未接线」兜底 executor：返回 failedFuture，提示调用方注入（G-2 后不再作 parser 默认；
     * 仅当调用方显式传入本常量时才命中，fail loud）。
     */
    WorkflowScriptExecutor NOT_WIRED = (hooks, args, budget) ->
            CompletableFuture.failedFuture(new IllegalStateException(
                    "WorkflowScriptExecutor 显式 NOT_WIRED：未接线执行引擎。"
                            + " 生产默认已由 RestrictedScriptExecutor 接通（G-2）；若此处命中，说明调用方显式注入了 NOT_WIRED，请改为不注入或注入真实 executor。"));
}
