package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * PostToolUse Hook 接口 · 对齐 CC §3.10 PostToolUse Hooks。
 *
 * <p>在工具执行<b>后</b>运行，可修改 result / 审计。
 * 注册到 {@link HookRegistry} 后，由 {@code LlmAgentLoop} 在 {@code tool.execute()} 之后调用。
 *
 * <h2>用途</h2>
 * <ul>
 *   <li><b>审计日志</b> — 记录每次工具调用的输入、输出、耗时</li>
 *   <li><b>结果后处理</b> — 过滤敏感信息、截断过大结果</li>
 *   <li><b>指标收集</b> — 统计工具使用频率、错误率</li>
 * </ul>
 *
 * <h2>多个 hook 执行顺序</h2>
 * <p>当注册了多个 PostToolUse hook 时，<b>全部执行</b>（非短路）。
 * 结果按注册顺序聚合：首个非 null 的 blockingError / systemMessage /
 * updatedMCPToolOutput 胜出（见 {@link HookRegistry#executePostToolUse}）。
 *
 * <h2>异常策略</h2>
 * <p>hook 抛异常 → warn 日志 + 继续执行下一个 hook（best-effort，不阻塞主流程）。
 *
 * @see HookRegistry
 * @see PreToolUseHook
 */
@FunctionalInterface
public interface PostToolUseHook {

    /**
     * 工具执行后调用。
     *
     * <p>[P2-6] 对齐 CC hooks.ts:2810-2818: PostToolUse hook 可产出
     * {@code blockingError}（作为 automated feedback 注入模型）、
     * {@code updatedMCPToolOutput}（替换工具输出）、{@code systemMessage}。
     *
     * <p>[R27] 对齐 CC 真源风格 — 新增 {@code stopHookActive} 参数。
     * Java 端当前无 PostToolUse 嵌套调用（hook 不调 hook），故实参永远为 {@code false}。
     * 参数保留是为对齐 CC {@code hooks.ts:3634-3643 executeStopHooks} 的 boolean 透传模式 —
     * 当未来 PostToolUse hook 嵌套调用场景出现时，调用方负责透传正确的 boolean 值。
     *
     * @param toolName       工具名（对应 {@link com.nexusai.application.agent.tool.Tool#name()}）
     * @param input          工具输入（已解析的 JSON 对象，由 LLM 生成）
     * @param result         工具执行结果（含 content / isError / toolUseId）
     * @param ctx            工具调用上下文（含 agentId / sessionId / mode 等）
     * @param stopHookActive 是否在另一 PostToolUse hook 内被调用（CC 嵌套守卫）
     * @return hook 结果；{@code null} 或 {@link GenericHook.HookResult#proceed()} = 纯旁路观察（不影响对话流）
     */
    GenericHook.HookResult onPostToolUse(String toolName, JsonNode input, ToolResult result,
                                          ToolUseContext ctx, boolean stopHookActive);

    /**
     * [R28.6] 工具执行失败后调用 · 对齐 CC toolExecution.ts:1700-1711 PostToolUseFailureHooks。
     *
     * <p>对齐 CC 真源: CC 在工具异常 (含 AbortError) 路径独立触发 PostToolUseFailure hooks,
     * 独立于成功路径的 PostToolUse hooks. Java 端 default 实现返回 proceed() (纯旁路),
     * R26 6 hook 零破坏. 新 hook 可 override 实现失败注入/审计/补救.
     *
     * <p>L2 不可触碰: default method 兼容 @FunctionalInterface 契约 (3 参主方法).
     *
     * @param toolName       工具名
     * @param input          工具输入
     * @param errorResult    错误 ToolResult (isError=true, content 含错误信息)
     * @param ctx            工具调用上下文
     * @param stopHookActive 是否在另一 PostToolUseFailure hook 内被调用
     * @return hook 结果; null = 纯旁路观察
     */
    default GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode input,
                                                       ToolResult errorResult, ToolUseContext ctx,
                                                       boolean stopHookActive) {
        // default 返回 proceed() — 兼容 R26 6 hook 零破坏
        return GenericHook.HookResult.proceed();
    }
}
