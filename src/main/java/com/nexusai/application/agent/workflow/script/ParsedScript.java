package com.nexusai.application.agent.workflow.script;

import java.util.concurrent.CompletableFuture;

/**
 * 已解析的脚本（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:150-157 ParsedScript）。
 *
 * <pre>{@code
 * type ParsedScript = {
 *   meta: WorkflowMeta | null
 *   execute: (hooks: WorkflowHooks, args: unknown, budget: unknown) => Promise<unknown>
 * }
 * }</pre>
 *
 * <p><b>注入参数语义</b>（script-doc §3.1「8 注入」+ §8 红线 6）：10 形参 = 8 业务注入 + 2 沙箱替身。</p>
 * <ul>
 *   <li><b>meta</b>：extractMeta 剥离出的 {@link WorkflowMeta}（无 meta 时为 null），供面板展示/校验。</li>
 *   <li><b>body</b>：剥离 `export const meta = {...};` 后的脚本函数体（含 meta 声明前源码），保留供执行引擎（W-1c）编译。</li>
 *   <li><b>executor</b>：注入参数模型载体——{@link #execute} 把 8 个业务注入参数（6 hook + args + budget）
 *       与 2 个 Date/Math 沙箱替身按 CC 位置序绑定后执行脚本（位置序 agent/parallel/pipeline/phase/log/workflow/args/budget/Date/Math）。</li>
 * </ul>
 *
 * <p>沙箱构造口径（DocReflect G 项修正）：CC 在 parseScript 返回闭包内、execute 之外构造一次
 * sandboxedDate/sandboxedMath（script.ts:210-211），execute 闭包捕获复用，并非每次 execute 重建；
 * 沙箱无状态，重建/复用行为等价。Java 端对齐 CC 结构，parse 时构造一次、executor 复用。</p>
 *
 * @param meta     工作流元数据（可为 null）
 * @param body     剥离 meta 后的脚本函数体
 * @param executor 已编译脚本执行器（parser 未注入时默认 {@link RestrictedScriptExecutor}，G-2 接线）
 */
public record ParsedScript(WorkflowMeta meta, String body, WorkflowScriptExecutor executor) {

    /**
     * 执行脚本（对齐 CC ParsedScript.execute，script.ts:157；调用序见 script.ts:214-227）。
     *
     * @param hooks  6 个 hook 能力（agent/parallel/pipeline/phase/log/workflow，位置 1-6）
     * @param args   调用参数（位置 7，Workflow tool input 透传）
     * @param budget 预算对象（位置 8，CC ctx.resources.budget）
     * @return 脚本返回结果
     */
    public CompletableFuture<Object> execute(WorkflowHooks hooks, Object args, Object budget) {
        return executor.execute(hooks, args, budget);
    }
}
