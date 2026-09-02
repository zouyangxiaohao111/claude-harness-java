package com.nexusai.application.agent.workflow.script;

/**
 * 非确定性调用错误（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:107-114）。
 *
 * <p>CC message 模板：{@code "{fn} is not available in workflow scripts (would break resume determinism). Pass timestamps/random seeds via args."}</p>
 *
 * <p><b>Why</b>：journal resume 必须可复现——agentCallKey(prompt, params) 指纹匹配依赖同样的输入产生同样的 agent 调用序列；
 * 若脚本能取到当前时间/随机数，resume 时结果会漂移（script-doc §4.1）。</p>
 *
 * <p><b>归属</b>：运行期错误，由执行引擎（W-1c WorkflowRunEngine）捕获并路由到 run_done status=failed；
 * {@link WorkflowScriptParser} 只抛编译期 {@link ScriptError}，不处理本错误（script-doc §6 错误分类）。</p>
 */
public class NonDeterministicError extends RuntimeException {

    public NonDeterministicError(String fn) {
        super(fn + " is not available in workflow scripts (would break resume determinism). "
                + "Pass timestamps/random seeds via args.");
    }
}
