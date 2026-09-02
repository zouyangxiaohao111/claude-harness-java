package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * PreToolUse Hook 接口 · 对齐 CC §3.6 PreToolUse Hooks · P0-3 强化版.
 *
 * <p>在工具执行<b>前</b>运行, 可修改 input / 拦截 / 放行 / 注入上下文.
 * 注册到 {@link HookRegistry} 后, 由 {@link StreamingToolExecutor#executeAsync}
 * 在 tool.execute 之前调用.
 *
 * <h2>P0-3 强化 (对齐 CC 16 字段 {@link AggregatedHookResult})</h2>
 * <p>本 hook 主方法返回类型从 {@link PermissionResult} (4 态) 升级为
 * {@link AggregatedHookResult} (16 字段). 完整覆盖 CC 7 类 yield case:
 * <ol>
 *   <li>{@link AggregatedHookResult#message()} — user-visible message</li>
 *   <li>{@link AggregatedHookResult#permissionBehavior()} — hook 内的权限决策</li>
 *   <li>{@link AggregatedHookResult#updatedInput()} — hook 修改后的 input (passthrough 模式)</li>
 *   <li>{@link AggregatedHookResult#preventContinuation()} — 阻止当前工具继续</li>
 *   <li>{@link AggregatedHookResult#stopReason()} — 阻止原因</li>
 *   <li>{@link AggregatedHookResult#additionalContexts()} — 注入到 LLM 的附加上下文</li>
 *   <li>{@link AggregatedHookResult#preventContinuation()}+{@link AggregatedHookResult#stopReason()} —
 *       翻译为"立即停止"语义 (CC toolHooks.ts:530 `yield { type: 'stop' }` 无字段, 由
 *       {@code preventContinuation} & {@code stopReason} 联合构造); AHR 不保留独立 stopSignal 字段</li>
 * </ol>
 *
 * <h2>permissionBehavior 字段语义</h2>
 * <ul>
 *   <li>{@link PermissionResult.Allow} — hook 显式允许, 跳过权限管线</li>
 *   <li>{@link PermissionResult.Deny} — hook 显式拒绝, 工具不执行</li>
 *   <li>{@link PermissionResult.Ask} — hook 要求询问用户</li>
 *   <li>{@code null} (即无 permissionBehavior) — hook 不干预, 交给权限管线 (CC
 *       {@code if (result.permissionBehavior !== undefined)} 真源 toolHooks.ts:510)</li>
 * </ul>
 *
 * <h2>多个 hook 执行顺序</h2>
 * <p>当注册了多个 PreToolUse hook 时, 按注册顺序依次执行. 全部执行后由
 * {@link HookRegistry} 按 deny > ask > allow 聚合 (对齐 CC hooks.ts:2820-2847).
 *
 * <h2>异常策略</h2>
 * <p>hook 抛异常 → warn 日志 + 视为 proceed() (best-effort, 不阻塞主流程).
 * AbortException 透传 (用户中止意图不可吞).
 *
 * @see AggregatedHookResult
 * @see HookRegistry
 * @see PostToolUseHook
 * @see StreamingToolExecutor
 */
@FunctionalInterface
public interface PreToolUseHook {

    /**
     * 工具执行前调用.
     *
     * @param toolName 工具名 (对应 {@link com.nexusai.application.agent.tool.Tool#name()})
     * @param input    工具输入 (已解析的 JSON 对象, 由 LLM 生成)
     * @param ctx      工具调用上下文 (含 agentId / sessionId / mode 等)
     * @return {@link AggregatedHookResult} 16 字段 (null-safe; null = 视为 proceed())
     */
    AggregatedHookResult onPreToolUse(String toolName, JsonNode input, ToolUseContext ctx);

    /**
     * [IMP-HOOKS-S6 ⊕1 + IMP-HOOKS-S9 DEL-02e] 5 参 PreToolUse hook · 对齐 CC
     * executePreToolHooks 9 参签名 (hooks.ts:3394-3405) 中的 hook 入参面:
     * toolName/toolUseID/toolInput/toolUseContext/toolInputSummary —— CC 无
     * userModified/parentMessage/requestId 入参 (旧 Java 8 参/10 参重载是 ⊕ 删除项,
     * T6-⊕1); prompt 回调通道 参数已删 (DEL-02, Java 无 UI 消费端, 删除前 HookRegistry
     * 恒传 null → 可观测行为不变).
     *
     * <p>WHY: CC 把 {@code tool.getToolUseSummary?.(processedInput)} (toolHooks.ts:475)
     * 透传给 hook 链, toolUseSummary 是工具输入的可读摘要.
     *
     * <p>default 实现 delegate 3 参主方法 — 既有 hook 无需改动即可编译,
     * 需要摘要的 hook 显式 override 本方法.
     *
     * @param toolName        工具名
     * @param input           工具输入 (processedInput: strip+backfill 后, E9·CCJ-T6-22)
     * @param ctx             工具调用上下文
     * @param toolUseId       LLM 工具调用 ID (对齐 CC toolUseID)
     * @param toolUseSummary  工具输入摘要 (CC original: toolInputSummary, hooks.ts:3405;
     *                        null = 工具未提供, 对齐 CC getToolUseSummary optional ?.)
     * @return Hook 决策 (CC 16 字段 AHR)
     */
    default AggregatedHookResult onPreToolUse(String toolName, JsonNode input, ToolUseContext ctx,
                                              String toolUseId,
                                              String toolUseSummary) {
        // default delegate 3 参主方法 — 兼容既有 hook 实现 (摘要默认丢弃)
        return onPreToolUse(toolName, input, ctx);
    }
}
