package com.nexusai.application.agent.permission.hook;

/**
 * AbortException · Hook 中止信号 · 对齐 CC AbortError (errors.ts:12-17) + outcome 'cancelled'
 * (hooks.ts:4812-4818).
 *
 * <p>L1 语义: 当 hook 检测到用户中止意图（CC AbortController.abort() / AbortSignal.aborted）
 * 时抛出此异常。{@link HookRegistry} 13 处 AbortException 处理点（grep 自验 2026-08-14:
 * instanceof AbortException × 10 + catch (AbortException) × 3, 行号随实施漂移以 grep 为准）
 * 必须先于通用 Exception 之前识别并 rethrow，让用户中止意图透传至 caller，不被静默吞掉。
 *
 * <p>WHY: CC AbortError 定义于 {@code src/utils/errors.ts:12-17}; hook 执行链 catch 后
 * 返回 {@code outcome: 'cancelled'} (utils/hooks.ts:4812-4818)，中止的 hook 不再产出
 * 业务事件；否则用户在工具执行前点击 abort 会导致工具继续执行（资源悬挂风险）。
 * （旧注释误引 utils/hooks.ts:2045-2051 — 该处为 PostToolUse callback fast-path 的
 * context 对象，与 AbortError 无关，DIF-13 修正。）
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1 不可变消息</b>: 单构造方法携带 message；message 透传给 caller</li>
 *   <li><b>A2 透传语义</b>: 任意 caller catch AbortException 后应停止后续 hook 链 + 退出当前流程</li>
 *   <li><b>A3 不干扰普通异常流</b>: 业务异常（如 NullPointerException）仍走 catch (Exception e) 路径</li>
 *   <li><b>A4 RuntimeException 兼容</b>: 任何 RuntimeException 透传规则仍生效</li>
 *   <li><b>A5 业务场景</b>: 用户点击 abort → ctx.abortController().isCancelled()=true → hook 抛 AbortException → HookRegistry rethrow → LlmAgentLoop 停止</li>
 * </ul>
 */
public class AbortException extends RuntimeException {

    public AbortException(String message) {
        super(message);
    }
}