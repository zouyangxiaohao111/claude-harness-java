package com.nexusai.application.agent.recovery;

/**
 * query 循环终止/续传原因全集枚举 · 对齐 CC query.ts 的 {@code Terminal} / {@code Continue}
 * 联合 reason（grep 自验：{@code Open-ClaudeCode/src/query.ts}，共 18 处 / 17 个唯一 reason）。
 *
 * <p><b>类级断链标注（OPD-ER-01）</b>：CC 声明类型来自 {@code ./query/transitions.js}
 * （query.ts:104 {@code import type { Terminal, Continue } from './query/transitions.js'}），
 * 但该文件在 CC 仓库中<b>不存在</b>（grep 自验：{@code ls Open-ClaudeCode/src/query/} 仅
 * config.ts / deps.ts / stopHooks.ts / tokenBudget.ts）——CC 类型源断链，type 定义实际
 * 无法解析。Java 侧依据 query.ts 实际 {@code return { reason: '...' }} / {@code transition:
 * { reason: '...' }} 字面量重建全集；断链只做标注，不修 CC（不新增 Java 侧不存在的行为）。
 *
 * <p><b>Terminal vs Continue 划分（CC query.ts 真源）</b>：
 * <ul>
 *   <li><b>Terminal（10 值）</b>：queryLoop 以 {@code return { reason } } 结束 —— 作为
 *       query() AsyncGenerator 的 return value（query.ts:227/250 泛型 {@code Terminal}），
 *       循环不再继续。含无字段 return 与带载荷 return（model_error.error / max_turns.turnCount）。</li>
 *   <li><b>Continue（7 值）</b>：queryLoop 以 {@code state = next; continue } 进入下一迭代 ——
 *       写 {@code transition} 字段（query.ts:216 {@code transition: Continue | undefined}），
 *       供测试断言恢复路径已触发。含带载荷 transition（collapse_drain_retry.committed /
 *       max_output_tokens_recovery.attempt）。</li>
 * </ul>
 *
 * <p><b>载荷建模（本 session 边界）</b>：CC 4 处带载荷（model_error.error / max_turns.turnCount /
 * collapse_drain_retry.committed / max_output_tokens_recovery.attempt）。本枚举仅承载 reason
 * 全集，载荷语义以 JavaDoc 标注、不建 record 包装 —— 载荷消费留给 ER-IMP-04/05。
 *
 * <p><b>Java ⊕ 偏差</b>：本枚举不包含 Java 旧 Transition 的 withRetry 域动作值
 * （BACKOFF_RETRY / FALLBACK_MODEL / EXHAUSTED / FATAL，DC-09）——这些是 withRetry 层
 * （CC withRetry.ts）内部动作，CC query.ts 无对应 reason，已随 Transition.java 删除。
 *
 * <p><b>Terminal/Continue 接线状态（VRSA-01 偏离标注）</b>：CC Terminal reason 在 Java
 * 生产控制流中由 {@link com.nexusai.application.agent.AgentState.ExitReason} 承载（便利字段，
 * 非 CC State 字段），<b>非</b>由本枚举 Terminal 值构造 --本枚举 10 个 Terminal 值从未作为
 * {@link RecoveryResult#reason} 或 {@link RecoveryState#getLastReason} 构造。Continue 7 值中
 * 6 值已接线（COLLAPSE_DRAIN_RETRY / MAX_OUTPUT_TOKENS_ESCALATE / MAX_OUTPUT_TOKENS_RECOVERY /
 * REACTIVE_COMPACT_RETRY / STOP_HOOK_BLOCKING / TOKEN_BUDGET_CONTINUATION -- 后两者经
 * {@code LlmAgentLoop.loop()} {@code setLastReason} 接线：STOP_HOOK_BLOCKING（stop-hook 重入，
 * CC query.ts:1302）与 TOKEN_BUDGET_CONTINUATION（CC query.ts:1338），grep 自验 2026-08-08 终态），
 * 余 1 值（NEXT_TURN）为 CC transition 语义镜像，由 LlmAgentLoop 直接 continue 隐式兑现。Terminal 值
 * 作为 CC reason 全集的权威引用 + 测试防回归（{@link LoopReasonCompletenessTest}），不直接
 * 参与控制流。Terminal 收敛（ExitReason -> LoopReason Terminal 值接线）待主 agent 拍板。
 */
public enum LoopReason {

    // ════════════════════════════════════════════════════════════════════════
    // Terminal 10 值 · CC queryLoop return { reason }（循环终止）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: {@code blocking_limit}（query.ts:646）。
     *
     * <p>callModel 前 token 估算达 effectiveContextWindow - MANUAL_COMPACT_BUFFER_TOKENS(3000)
     * 上限 → 直接退出（不调 provider），避免超窗请求 413（query.ts:645-648）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    BLOCKING_LIMIT,

    /**
     * CC original: {@code image_error}（query.ts:977 / :1175）。
     *
     * <p>CC 有两个独立返回点产出 image_error（grep 自验）：
     * <ul>
     *   <li>query.ts:977 -- ImageSizeError / ImageResizeError 纯图片错误
     *       （query.ts:970-978），<b>不</b>涉及 prompt_too_long</li>
     *   <li>query.ts:1175 -- media withheld 分支三元
     *       {@code return { reason: isWithheldMedia ? 'image_error' : 'prompt_too_long' }}
     *       （query.ts:1173-1175），与 {@link #PROMPT_TOO_LONG} 共享此 return 点</li>
     * </ul>
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    IMAGE_ERROR,

    /**
     * CC original: {@code model_error}（query.ts:996）。
     *
     * <p>queryModelWithStreaming 抛出异常（含 withRetry 抛出的模型错误）→ 补 tool_result
     * 块后 surface 真实错误退出（query.ts:981-997）。<b>含 {@code error} 载荷</b>。
     *
     * <p><b>Terminal 载荷</b>：{@code error}（底层异常）。
     */
    MODEL_ERROR,

    /**
     * CC original: {@code aborted_streaming}（query.ts:1051）。
     *
     * <p>流式响应中用户中断 → surface 用户中断消息后退出（query.ts:1040-1051）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    ABORTED_STREAMING,

    /**
     * CC original: {@code prompt_too_long}（query.ts:1175 / :1182）。
     *
     * <p>CC 有两个独立返回点产出 prompt_too_long（grep 自验）：
     * <ul>
     *   <li>query.ts:1175 -- media withheld 分支三元
     *       {@code return { reason: isWithheldMedia ? 'image_error' : 'prompt_too_long' }}
     *       （query.ts:1173-1175），与 {@link #IMAGE_ERROR} 共享此 return 点</li>
     *   <li>query.ts:1182 -- contextCollapse withheld 无法恢复
     *       （query.ts:1176-1183），纯 prompt_too_long</li>
     * </ul>
     * 两处均 surface withheld 错误后退出，不走 stop hooks（防死亡螺旋，query.ts:1168-1172）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    PROMPT_TOO_LONG,

    /**
     * CC original: {@code completed}（query.ts:1264 / :1357）。
     *
     * <p>正常完成退出，两个 return 点：
     * <ul>
     *   <li>query.ts:1264 —— lastMessage.isApiErrorMessage 时（rate limit / PTL / auth 等，
     *       走 executeStopFailureHooks 后按 completed 退出，不落 stop hooks）；</li>
     *   <li>query.ts:1357 —— 正常无阻塞、无 tool_use、无 stop 钩子阻断的迭代末尾。</li>
     * </ul>
     *
    * <p><b>[P-6 2026-08-15 对齐修正]</b>：max_tokens 耗尽路径已统一——append
    * createAssistantAPIErrorMessage（isApiErrorMessage=true）后设 skipStopPipeline=true
    * （§14 Stop hooks 跳过）+ 触发 StopFailure hook，exitReason 改为 NORMAL
    * （对齐 CC query.ts:1264 return {reason:'completed'}；旧实现保持 ExitReason.MAX_OUTPUT_TOKENS
    * 的偏离已消除）。本枚举 COMPLETED 值对应 Java ExitReason.NORMAL 语义。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    COMPLETED,

    /**
     * CC original: {@code stop_hook_prevented}（query.ts:1279）。
     *
     * <p>post-sampling stop hook 返回 preventContinuation=true → 优雅终止（query.ts:1276-1279）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    STOP_HOOK_PREVENTED,

    /**
     * CC original: {@code aborted_tools}（query.ts:1515）。
     *
     * <p>工具执行阶段用户中断（tool_use 中断）→ 若达 maxTurns 附带 max_turns 消息后退出
     * （query.ts:1504-1515）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    ABORTED_TOOLS,

    /**
     * CC original: {@code hook_stopped}（query.ts:1520）。
     *
     * <p>工具循环后 shouldPreventContinuation=true（hook 指示停止续行）→ 退出
     * （query.ts:1517-1520）。
     *
     * <p><b>Terminal 载荷</b>：无字段。
     */
    HOOK_STOPPED,

    /**
     * CC original: {@code max_turns}（query.ts:1711）。
     *
     * <p>nextTurnCount &gt; maxTurns → 附 max_turns_reached 消息后退出（query.ts:1709-1712）。
     * <b>含 {@code turnCount} 载荷</b>。
     *
     * <p><b>Terminal 载荷</b>：{@code turnCount}（已达成轮数）。
     */
    MAX_TURNS,

    // ════════════════════════════════════════════════════════════════════════
    // Continue 7 值 · CC queryLoop transition = { reason }（循环继续下一迭代）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: {@code collapse_drain_retry}（query.ts:1110）。
     *
     * <p>contextCollapse drain 成功（committed &gt; 0）→ 以 drained.messages 重试。
     * <b>含 {@code committed} 载荷</b>（query.ts:1106-1117）。
     *
     * <p><b>Continue 载荷</b>：{@code committed}（drain 提交步数）。
     */
    COLLAPSE_DRAIN_RETRY,

    /**
     * CC original: {@code reactive_compact_retry}（query.ts:1162）。
     *
     * <p>reactive compact 成功 → 以 compacted messages 重试，且置
     * {@code hasAttemptedReactiveCompact: true}（单次守卫，query.ts:1152-1165）。
     *
     * <p><b>Continue 载荷</b>：无字段。
     */
    REACTIVE_COMPACT_RETRY,

    /**
     * CC original: {@code max_output_tokens_escalate}（query.ts:1217）。
     *
     * <p>首次 max_tokens 截断 + 升级 gate 开启 → max_tokens 8K→64K 升级，下一请求注入
     * {@code maxOutputTokensOverride: ESCALATED_MAX_TOKENS}（query.ts:1209-1220）。
     *
     * <p><b>Continue 载荷</b>：无字段。
     */
    MAX_OUTPUT_TOKENS_ESCALATE,

    /**
     * CC original: {@code max_output_tokens_recovery}（query.ts:1246）。
     *
     * <p>已升级 / gate 关闭后再截断 → 追加 recoveryMessage 续写提示重试，且
     * {@code maxOutputTokensRecoveryCount + 1}（&lt; MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3）。
     * <b>含 {@code attempt} 载荷</b>（query.ts:1223-1252）。
     *
     * <p><b>Continue 载荷</b>：{@code attempt}（本次续写序号）。
     */
    MAX_OUTPUT_TOKENS_RECOVERY,

    /**
     * CC original: {@code stop_hook_blocking}（query.ts:1302）。
     *
     * <p>stop hook 产生 blockingErrors → 追加 blockingErrors 后重试，且置
     * {@code stopHookActive: true}、保留 {@code hasAttemptedReactiveCompact}（防
     * compact→PTL→hook→compact 死亡螺旋，query.ts:1281-1304）。
     *
     * <p><b>Continue 载荷</b>：无字段。
     */
    STOP_HOOK_BLOCKING,

    /**
     * CC original: {@code token_budget_continuation}（query.ts:1338）。
     *
     * <p>token budget 检查放行 → 重置 {@code maxOutputTokensRecoveryCount: 0} +
     * {@code hasAttemptedReactiveCompact: false} 后继续（query.ts:1325-1340）。
     *
     * <p><b>Continue 载荷</b>：无字段。
     */
    TOKEN_BUDGET_CONTINUATION,

    /**
     * CC original: {@code next_turn}（query.ts:1725）。
     *
     * <p>常规进入下一 turn（assistant 响应 + 工具结果循环）→ 重置
     * {@code maxOutputTokensRecoveryCount: 0}（:1721）+ {@code hasAttemptedReactiveCompact:
     * false}（:1722）+ {@code maxOutputTokensOverride: undefined}（:1723）
     * （query.ts:1715-1726）。
     *
     * <p><b>Continue 载荷</b>：无字段。
     */
    NEXT_TURN;

    /**
     * 是否 query 循环<b>终止</b> reason（CC queryLoop {@code return} 路径）。
     *
     * <p><b>VRSA-03 标注</b>：查询辅助方法（防回归），生产控制流不调用 --
     * Terminal/Continue 分流在生产中由 {@link com.nexusai.application.agent.AgentState.ExitReason}
     * 承载（见类级 VRSA-01 偏离标注）。仅 {@link LoopReasonCompletenessTest} 调用以锁定
     * CC Terminal 分组不漂移。
     */
    public boolean isTerminal() {
        return ordinal() < MAX_TURNS.ordinal() + 1;
    }

    /**
     * 是否 query 循环<b>续传</b> reason（CC queryLoop {@code continue} 路径）。
     *
     * <p>同 {@link #isTerminal()}，查询辅助方法（防回归），生产控制流不调用。
     */
    public boolean isContinue() {
        return !isTerminal();
    }
}
