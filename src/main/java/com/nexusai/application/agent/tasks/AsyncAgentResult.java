package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AgentUsage;

/**
 * 异步 Agent 终态结果封装 · 对齐 CC LocalAgentTask lifecycle 终态化.
 *
 * <p>由 SubagentExecutor.execute(...) 完成后包装为 {@code AsyncAgentResult} 交给
 * {@link AsyncAgentFinalizer} → {@link BackgroundTaskRunner#completeAsyncAgent(String, AsyncAgentResult)}
 * 写入 task 终态 + 通知 + evict.
 *
 * <p>6 字段:
 * <ul>
 *   <li>{@code summary} — agent 结论文本 / killed 路径的部分结果 (CC extractPartialResult)</li>
 *   <li>{@code totalToolUseCount} — 整个 query 循环累计 tool_call 次数</li>
 *   <li>{@code totalDurationMs} — execute() 起 → 完 总耗时 (ms)</li>
 *   <li>{@code agentId} — sub-agent UUID (= taskId)</li>
 *   <li>{@code totalTokens} — 总 token 数 (CC agentToolUtils.ts:237/319)</li>
 *   <li>{@code usage} — usage 对象 (CC agentToolUtils.ts:238-256, 7 子字段)</li>
 * </ul>
 *
 * <p>[S4 P1 差异项 6] runAsyncAgentLifecycle 三态 (CC agentToolUtils.ts:624/659/673):
 * <ul>
 *   <li>{@link #success(String, int, long, String, long, AgentUsage)} — completed, 写 COMPLETED</li>
 *   <li>{@link #killed(String, AgentUsage, long, long, String)} — killed, 写 KILLED (部分结果保留)</li>
 *   <li>{@link #failure(String, String)} — failed, 写 FAILED (经 {@link AsyncAgentFinalizer} 路由)</li>
 * </ul>
 */
public record AsyncAgentResult(
        String summary,
        int totalToolUseCount,
        long totalDurationMs,
        String agentId,
        long totalTokens,
        AgentUsage usage
) {
    /**
     * 构造正常完成结果 (兼容 4 参, usage/totalTokens 兜底 EMPTY/0).
     *
     * <p>本工厂不做防御性校验: caller 应保证 totalToolUseCount / totalDurationMs ≥ 0;
     * 若传入负值, 将原样保留 (不静默截断) 以暴露 caller 的计数 bug.
     *
     * @param summary agent 结论文本 (可空 → 写空字符串)
     * @param totalToolUseCount tool_call 总次数 (caller 应保证 ≥ 0; 负值原样保留)
     * @param totalDurationMs 总耗时 ms (caller 应保证 ≥ 0; 负值原样保留)
     * @param agentId sub-agent UUID 字符串 (= taskId, 可空)
     */
    public static AsyncAgentResult success(String summary, int totalToolUseCount,
                                          long totalDurationMs, String agentId) {
        return success(summary, totalToolUseCount, totalDurationMs, agentId, 0L, AgentUsage.EMPTY);
    }

    /**
     * 构造正常完成结果 · 对齐 CC runAsyncAgentLifecycle completed 路径 (agentToolUtils.ts:624-637).
     *
     * @param summary agent 结论文本 (可空 → 写空字符串)
     * @param totalToolUseCount tool_call 总次数
     * @param totalDurationMs 总耗时 ms
     * @param agentId sub-agent UUID 字符串 (= taskId)
     * @param totalTokens 总 token 数 (CC agentToolUtils.ts:237)
     * @param usage usage 对象 (CC agentToolUtils.ts:238-256)
     */
    public static AsyncAgentResult success(String summary, int totalToolUseCount,
                                          long totalDurationMs, String agentId,
                                          long totalTokens, AgentUsage usage) {
        return new AsyncAgentResult(
            summary != null ? summary : "",
            totalToolUseCount,
            totalDurationMs,
            agentId,
            totalTokens,
            usage != null ? usage : AgentUsage.EMPTY
        );
    }

    /**
     * 构造 killed 结果 · 对齐 CC runAsyncAgentLifecycle AbortError → killAsyncAgent +
     * extractPartialResult (agentToolUtils.ts:640-668). summary 承载部分结果 (CC partialResult,
     * extractPartialResult :658 逆序找首个有 text 的 assistant message).
     *
     * @param partialResult 被 kill 时已产出的部分结果文本 (CC extractPartialResult)
     * @param usage         usage 对象
     * @param totalTokens   总 token 数
     * @param totalDurationMs 已消耗耗时 ms
     * @param agentId       sub-agent UUID 字符串 (= taskId)
     */
    public static AsyncAgentResult killed(String partialResult, AgentUsage usage,
                                          long totalTokens, long totalDurationMs, String agentId) {
        return new AsyncAgentResult(
            partialResult != null ? partialResult : "",
            0,
            totalDurationMs,
            agentId,
            totalTokens,
            usage != null ? usage : AgentUsage.EMPTY
        );
    }

    /**
     * 构造失败结果 · 调用方通常经 {@link AsyncAgentFinalizer#finalize(String, AsyncAgentResult)}
     * 失败处理路径转入 FAILED 状态. summary 字段作为错误描述写入 outputFile.
     */
    public static AsyncAgentResult failure(String error, String agentId) {
        return new AsyncAgentResult(
            error != null ? error : "unknown error",
            0,
            0L,
            agentId,
            0L,
            AgentUsage.EMPTY
        );
    }
}