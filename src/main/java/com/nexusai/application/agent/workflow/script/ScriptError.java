package com.nexusai.application.agent.workflow.script;

/**
 * 编译期脚本错误（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:3-8）。
 *
 * <p>CC 原文：{@code export class ScriptError extends Error { constructor(message: string) { super(message); this.name = 'ScriptError' } }}</p>
 *
 * <p>作用域：{@link WorkflowScriptParser} 编译期（extractMeta / assertScriptBody / meta 求值）抛出的唯一错误类型。
 * 运行期错误（{@link NonDeterministicError} / WorkflowError / WorkflowAbortedError）不归 parser（script-doc §6 错误分类）。
 * 错误路由：service.launch 快速校验直接抛 "Script validation failed" 不进后台；runWorkflow 捕获后回 run_done failed 回给模型。</p>
 */
public class ScriptError extends RuntimeException {

    public ScriptError(String message) {
        super(message);
    }
}
