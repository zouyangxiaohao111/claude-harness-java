package com.nexusai.application.agent.workflow.script;

/**
 * Date/Math 确定性沙箱（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:116-143 sandboxDate / sandboxMath）。
 *
 * <p><b>Why 确定性</b>：journal resume 必须可复现——agentCallKey(prompt, params) 指纹匹配依赖同样的输入产生
 * 同样的 agent 调用序列；若脚本能取到当前时间/随机数，resume 时结果会漂移（script-doc §4.1）。</p>
 *
 * <table border="1">
 *   <caption>沙箱成员行为（CC script.ts:116-143）</caption>
 *   <tr><th>CC 成员</th><th>行为</th><th>本守卫方法</th></tr>
 *   <tr><td>Date.now()</td><td>抛 NonDeterministicError('Date.now()')</td><td>{@link #rejectDateNow()}</td></tr>
 *   <tr><td>new Date() 无参</td><td>抛 NonDeterministicError('Date.now()/new Date()')</td><td>{@link #rejectNewDateNoArg()}</td></tr>
 *   <tr><td>new Date(...args) 有参</td><td>透传真实 Date（确定性构造允许）</td><td>引擎保留原生</td></tr>
 *   <tr><td>Date.parse</td><td>透传真实实现（确定性纯函数）</td><td>引擎保留原生</td></tr>
 *   <tr><td>Date.UTC</td><td>透传真实实现（确定性纯函数）</td><td>引擎保留原生</td></tr>
 *   <tr><td>Math.random()</td><td>抛 NonDeterministicError('Math.random()')</td><td>{@link #rejectMathRandom()}</td></tr>
 *   <tr><td>其余 Math.*</td><td>透传（max/min/abs/floor/ceil/round/pow/sqrt/log 等确定性函数）</td><td>引擎保留原生</td></tr>
 * </table>
 *
 * <p><b>边界诚实声明（对齐 CC script.ts:172-179）</b>：确定性沙箱 ≠ 安全沙箱。沙箱只保 resume 可复现，
 * 不保证安全（与 LLM 同级信任），拦不住 crypto / process / 文件 IO 等熵源；import(...)/动态逃逸被
 * {@link WorkflowScriptParser#assertScriptBody} 规则 2 显式禁止。</p>
 *
 * <p>沙箱构造口径（DocReflect G 项修正）：CC 在 parseScript 返回闭包内、execute 之外构造一次
 * sandboxedDate/sandboxedMath（script.ts:210-211），execute 闭包捕获复用，并非每次 execute 重建；
 * 沙箱无状态，重建/复用行为等价。</p>
 *
 * <p>P0 落地形态：本类提供<b>执行时校验</b>守卫——引擎（W-1c，GraalJS）把 Date.now() / new Date() / Math.random()
 * 绑定为调用这些守卫的 JS 函数，脚本触发即抛 {@link NonDeterministicError}。</p>
 */
public final class DateMathSandbox {

    private DateMathSandbox() {
        // 纯工具类，禁止实例化
    }

    /**
     * Date.now() → 抛 {@link NonDeterministicError}，message 含 'Date.now()'（CC script.ts:124-126）。
     */
    public static void rejectDateNow() {
        throw new NonDeterministicError("Date.now()");
    }

    /**
     * 无参 new Date() → 抛 {@link NonDeterministicError}，message 含 'new Date'（CC script.ts:118-120）。
     */
    public static void rejectNewDateNoArg() {
        throw new NonDeterministicError("Date.now()/new Date()");
    }

    /**
     * Math.random() → 抛 {@link NonDeterministicError}，message 含 'Math.random()'（CC script.ts:135-138）。
     */
    public static void rejectMathRandom() {
        throw new NonDeterministicError("Math.random()");
    }
}
