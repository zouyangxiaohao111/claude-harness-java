package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;

import java.util.List;
import java.util.Map;

/**
 * 通用 Hook 接口 · 对齐 CC 27 种事件的统一处理
 *
 * <p>取代 PreToolUseHook/PostToolUseHook 的分散接口,
 * 用单一接口 + HookEventType 过滤实现.
 */
@FunctionalInterface
public interface GenericHook {

    /**
     * 处理 hook 事件
     *
     * @param event hook 事件
     * @return 处理结果（null = 不干预）
     */
    HookResult onEvent(HookEvent event);

    // [IMPL-10] DEL-L01-01: onEventWithContinuation 已删除（HookContinuationResult 为
    //   0 生产调用的控制 record；CC toolHooks.ts:117-130 的 PostToolUse 阻止语义由
    //   attachment 通道落地 — IMPL-03/07 已实施）。

    /**
     * Hook 处理结果 · [Session H4] 全量对齐 CC {@code HookResult} 真源
     * {@code Open-ClaudeCode/src/utils/hooks.ts:338-357} (grep 自验 2026-07-30) +
     * {@code Open-ClaudeCode/src/types/hooks.ts:260-275}.
     *
     * <p><b>[Session H4] 字段重构 (破约, 不留兼容壳)</b>:
     * <ul>
     *   <li>删除 3 个非 CC 顶层字段: {@code hookPermissionResult} / {@code hookUpdatedInput} / {@code hookSource}
     *       (CC permissionDecision 在 hookSpecificOutput 内, 不在 HookResult 顶层;
     *       hookSource 在 AggregatedHookResult 顶层 / decisionReason 内, 不在 HookResult 顶层;
     *       hookUpdatedInput 与 updatedInput 语义重叠, 合并到 updatedInput)</li>
     *   <li>{@code blockingError} 类型 String → {@link HookBlockingError} (CC types/hooks.ts:263
     *       blockingError 是结构化 record {blockingError, command}, 非 String)</li>
     *   <li>[Session H3] {@code additionalContext} List&lt;String&gt; → String (CC types/hooks.ts:269
     *       单值 string, 非数组)</li>
     *   <li>[Session H3] 新增第 13 字段 {@code hook} (CC utils/hooks.ts:356, 放最后)</li>
     *   <li>[Session S07] 新增第 14 字段 {@code permissionRequestResult} (CC utils/hooks.ts:351
     *       顶层字段 + hooks.ts:2882-2886 yield; 旧注释"不回填顶层"系误读, 破约纠正 —
     *       PermissionRequest 决策在 HookResult 顶层承载, 不再只存 ParsedHookJSONOutput)</li>
     *   <li>字段集对齐 CC HookResult 14 字段 + hook (保留 outcome, permissionBehavior, stopReason,
     *       retry, hookPermissionDecisionReason, additionalContext, message, systemMessage,
     *       updatedInput, updatedMCPToolOutput, preventContinuation, permissionRequestResult;
     *       initialUserMessage 不回填顶层, 由 ParsedHookJSONOutput 承载 (I-1 对齐 CC))</li>
     * </ul>
     *
     * <p><b>字段语义对照表 (CC ↔ Java) — 14 字段</b>:
     * <table>
     *   <tr><th>CC 字段</th><th>Java 字段</th><th>用途</th></tr>
     *   <tr><td>{@code message}</td><td>{@link #message}</td><td>user-visible message</td></tr>
     *   <tr><td>{@code systemMessage}</td><td>{@link #systemMessage}</td><td>系统级消息</td></tr>
     *   <tr><td>{@code blockingError}</td><td>{@link #blockingError} ({@link HookBlockingError})</td>
     *     <td>结构化阻塞错误 (CC types/hooks.ts:243-246)</td></tr>
     *   <tr><td>{@code outcome}</td><td>{@link #outcome} (enum)</td><td>success/blocking/...</td></tr>
     *   <tr><td>{@code preventContinuation}</td><td>{@link #preventContinuation}</td><td>阻止后续</td></tr>
     *   <tr><td>{@code stopReason}</td><td>{@link #stopReason}</td><td>阻止原因</td></tr>
     *   <tr><td>{@code permissionBehavior}</td><td>{@link #permissionBehavior}</td><td>权限决议 (ask/deny/...)</td></tr>
     *   <tr><td>{@code hookPermissionDecisionReason}</td><td>{@link #hookPermissionDecisionReason}</td><td>hook 决策原因</td></tr>
     *   <tr><td>{@code additionalContext}</td><td>{@link #additionalContext} (String, H3 改单值)</td><td>附加上下文</td></tr>
     *   <tr><td>{@code updatedInput}</td><td>{@link #updatedInput}</td><td>修改后 input</td></tr>
     *   <tr><td>{@code updatedMCPToolOutput}</td><td>{@link #updatedMCPToolOutput}</td><td>MCP output 替换</td></tr>
     *   <tr><td>{@code retry}</td><td>{@link #retry}</td><td>允许重试</td></tr>
     *   <tr><td>{@code hook}</td><td>{@link #hook} (H3 新增)</td><td>触发本 result 的 HookCommand (hooks.ts:356)</td></tr>
     *   <tr><td>{@code permissionRequestResult}</td><td>{@link #permissionRequestResult} (S07 新增)</td>
     *     <td>PermissionRequest hook 决策 (types/hooks.ts:248-258, utils/hooks.ts:351)</td></tr>
     * </table>
     *
     * <p><b>awaiting 字段处理</b>: initialUserMessage / elicitationResponse /
     * watchPaths / elicitationResultResponse 由 caller 经 {@link HookOutputParser#processHookJSONOutput}
     * 装入 {@link ParsedHookJSONOutput} (CC processHookJSONOutput awaiting 字段),
     * 然后由 {@link HookRegistry} 聚合并填入 {@link AggregatedHookResult}.
     * permissionRequestResult 自 S07 起在 HookResult 顶层回填 (CC hooks.ts:2882-2886),
     * ParsedHookJSONOutput 仍保留该字段供 AHR 聚合.
     * @param preventContinuation       CC original: {@code preventContinuation} (utils/hooks.ts:343);
     *                                  是否阻止继续执行（true = 中断流程）
     * @param blockingError             CC original: {@code blockingError} (utils/hooks.ts:341);
     *                                  结构化阻塞错误 record, null = 无阻塞
     * @param systemMessage             CC original: {@code systemMessage} (utils/hooks.ts:340);
     *                                  系统级消息
     * @param additionalContext          CC original: {@code additionalContext} (utils/hooks.ts:347);
     *                                  附加上下文 (H3 改单值 String, 对齐 types/hooks.ts:269)
     * @param message                   CC original: {@code message} (utils/hooks.ts:339);
     *                                  用户可见消息
     * @param updatedInput              CC original: {@code updatedInput} (utils/hooks.ts:351);
     *                                  修改后的工具输入（PreToolUse hook 可修改入参）
     * @param updatedMCPToolOutput      CC original: {@code updatedMCPToolOutput} (utils/hooks.ts:352);
     *                                  修改后的 MCP 工具输出
     * @param retry                     CC original: {@code retry} (utils/hooks.ts:355);
     *                                  允许重试标记 (null/true/false)
     * @param hookPermissionDecisionReason CC original: {@code hookPermissionDecisionReason} (utils/hooks.ts:346);
     *                                  hook 决策原因
     * @param outcome                   CC original: {@code outcome} (utils/hooks.ts:342);
     *                                  hook outcome 枚举 (success/blocking/non_blocking_error/cancelled)
     * @param stopReason                CC original: {@code stopReason} (utils/hooks.ts:344);
     *                                  阻止继续执行的语义化原因文本
     * @param permissionBehavior        CC original: {@code permissionBehavior} (utils/hooks.ts:345);
     *                                  hook 内的权限决议 (ask/deny/allow/passthrough)
     * @param hook                      CC original: {@code hook} (utils/hooks.ts:356);
     *                                  触发本 result 的 HookCommand (H3 新增, 放最后)
     * @param permissionRequestResult   CC original: {@code permissionRequestResult} (utils/hooks.ts:351);
     *                                  PermissionRequest hook 决策 (allow/deny), null = 无决策
     *                                  (S07 顶层回填, CC hooks.ts:2882-2886)
     */
    record HookResult(
        boolean preventContinuation,
        HookBlockingError blockingError,
        // [H-WF5a-02 折叠链项3] systemMessage 单值 String → List<String> systemMessages ·
        //   CC executeHooks 逐结果 yield systemMessage → N hook_system_message 附件
        //   (hooks.ts:2769-2780, 消费端 toolHooks.ts 逐条注入). Java 聚合层折叠 N 结果
        //   → List 全保留 (旧 first-non-null 只留第 1 条). 通用事件路径 (executeEvent)
        //   仍可承载单元素 List (1 个 result 折叠), 与 CC 逐结果 yield 语义等价.
        java.util.List<String> systemMessages,
        // [H-WF5a-02 折叠链项2] additionalContext 单值 String → List<String> additionalContexts ·
        //   CC 逐结果 yield { additionalContexts: [result.additionalContext] } (hooks.ts:2782-2790)
        //   → 消费端 N hook_additional_context 附件 (toolHooks.ts:132-143). Java 聚合层
        //   List 全保留 (旧 first-non-null 只留第 1 条).
        java.util.List<String> additionalContexts,
        Object message,
        Map<String, Object> updatedInput,
        Object updatedMCPToolOutput,
        // [R32-b13 B9] retry 标记 · 对齐 CC utils/hooks.ts:355 yield {retry}.
        //   null = 不消费 retry, true = 允许重试, false = 显式拒绝.
        Boolean retry,
        // [P0-3] hookPermissionDecisionReason · 对齐 CC utils/hooks.ts:346
        //   CC toolHooks.ts:518 用作 decisionReason.reason.
        String hookPermissionDecisionReason,
        // ─── [Session I P3-1] 3 字段扩展 · 对齐 CC HookResult 真源 ───
        HookOutcome outcome,
        String stopReason,
        PermissionBehavior permissionBehavior,
        // ─── [Session S07] permissionRequestResult 顶层回填 · 对齐 CC utils/hooks.ts:351 + hooks.ts:2882-2886 ───
        //   CC executeHooks 对 PermissionRequest hook 的决策按
        //   `if (result.permissionRequestResult) yield { permissionRequestResult }` 顶层产出
        //   (hooks.ts:2882-2886), 且 HookResult 类型含该可选字段 (utils/hooks.ts:351).
        //   旧 Java 注释称"不回填顶层, 由 ParsedHookJSONOutput 承载"系误读 —— S07 破约纠正:
        //   HookOutputParser.processHookJSONOutput 构造时直接回填本字段 (CommandHookExecutor
        //   parseStdoutJson 的 parsed.result() 即携带), coordinator/interactive 消费链
        //   (runHooks 等价) 从 HookResult 顶层读取, 不再丢决策.
        PermissionRequestResult permissionRequestResult,
        // [Session H3] hook 字段 (放最后) · 对齐 CC hooks.ts:356 {@code hook}
        //   (注意: 真源是 utils/hooks.ts:338-357, 不是 types/hooks.ts:260-275 的 15 字段 SDK 版无 hook)
        //   承载触发本 result 的 HookCommand, 供审计/UI 追溯. 由 toHookResult(CommandHook) 重载填充.
        HookCommand hook,
        // ─── [2026-08-12 探查 △-01] 4 字段补齐 · 对齐 CC utils/hooks.ts:338-357 18 字段 HookResult ───
        //   CC HookResult 含 initialUserMessage/watchPaths/elicitationResponse/elicitationResultResponse
        //   (hooks.ts:348/352-355), 旧 Java 15 字段在 executeEvent 折叠链上静默丢失
        //   (awaiting 字段仅存 ParsedHookJSONOutput, parseStdoutJson 只返回 result 丢弃).
        //   探查报告: 探查/hooks/探查-hooks-整合版.md §3 △-01. 字段追加在 record 尾部 (hook 之后),
        //   现有 15 参构造点仅追加 4 个参数即兼容; 语义上对应 CC HookResult 可选字段.
        String initialUserMessage,
        java.util.List<String> watchPaths,
        ElicitationResponse elicitationResponse,
        ElicitationResponse elicitationResultResponse
        // ─── [IMP-DA-01 TY-01] 第 19 字段 allBlockingErrors 已删除 · 对齐 CC HookResult 无此字段 ───
        //   CC executeHooks 逐结果 yield blockingError (hooks.ts:2759-2763) →
        //   runPostToolUseHooks 逐 result.blockingError 产独立 hook_blocking_error 附件
        //   (toolHooks.ts:105-115, N 个 blocking → N 附件). 删除后由 HookRegistry 折叠层把
        //   每 blocking result 的 hook_blocking_error 附件并入 message 列表 (无 message 时合成),
        //   StreamingToolExecutor.injectPostToolUseHookAttachments 逐条注入 (不再读聚合字段).
    ) {
        /** Compact 构造器: 不可变 Map/List 拷贝 + null-safe (List 字段 List.copyOf 防外部修改). */
        public HookResult {
            if (updatedInput != null) {
                updatedInput = Map.copyOf(updatedInput);
            }
            if (systemMessages != null) {
                systemMessages = List.copyOf(systemMessages);
            }
            if (additionalContexts != null) {
                additionalContexts = List.copyOf(additionalContexts);
            }
        }

        /**
         * [Session S07] wither: 回填 permissionRequestResult · 对齐 CC hooks.ts:2882-2886
         * {@code yield { permissionRequestResult }} 顶层产出.
         *
         * <p>WHY: {@link HookRegistry#resolveEventResult} 折叠多 hook 结果时, 返回的
         * 结果对象若未携带首个 permissionRequestResult (如 firstStop 分支), 用本方法
         * 补填而不丢决策 (record 14 参全传).
         *
         * @param prr PermissionRequest hook 决策 (allow/deny), null = 不覆盖
         * @return 相同字段 + permissionRequestResult 的新 record
         */
        public HookResult withPermissionRequestResult(PermissionRequestResult prr) {
            if (prr == null) {
                return this;
            }
            return new HookResult(preventContinuation, blockingError, systemMessages, additionalContexts,
                message, updatedInput, updatedMCPToolOutput, retry, hookPermissionDecisionReason,
                outcome, stopReason, permissionBehavior, prr, hook,
                initialUserMessage, watchPaths, elicitationResponse, elicitationResultResponse);
        }

        /**
         * [Session H3] wither: 返回携带 hook 字段的新 record · 对齐 CC HookResult.hook (hooks.ts:356).
         *
         * <p>WHY: {@link CommandHookExecutor#toHookResult} 只有 String hookCommand 时 hook=null,
         * 调用方拿到 CommandHook 后可用本方法补填 hook 字段 (record 14 参全传).
         *
         * @param hook 触发本 result 的 CommandHook
         * @return 相同 13 字段 + hook 的新 record
         */
        public HookResult withHook(HookCommand hook) {
            return new HookResult(preventContinuation, blockingError, systemMessages, additionalContexts,
                message, updatedInput, updatedMCPToolOutput, retry, hookPermissionDecisionReason,
                outcome, stopReason, permissionBehavior, permissionRequestResult, hook,
                initialUserMessage, watchPaths, elicitationResponse, elicitationResultResponse);
        }

        /**
         * 返回"继续执行"结果（无干预）。
         */
        public static HookResult proceed() {
            return new HookResult(false, null, null, null, null, null, null,
                null, null, HookOutcome.SUCCESS, null, null, null, null,
                null, null, null, null);
        }

        /**
         * 返回"阻止继续"结果。
         *
         * @param stopReason 阻止原因
         */
        public static HookResult stop(String stopReason) {
            return new HookResult(true, null, null, null, null, null, null,
                null, null, HookOutcome.BLOCKING, stopReason, null, null, null,
                null, null, null, null);
        }

        /**
         * [P1-4] 返回"阻止继续 + blockingError"结果。
         *
         * <p>对齐 CC Stop hook 的 blockingError 通道：
         * exit 2 stderr 文本注入 LLM 作为反馈，重入 loop。
         *
         * @param stopReason    阻止原因
         * @param blockingError 阻塞错误文本（注入 LLM）
         */
        public static HookResult stop(String stopReason, String blockingError) {
            HookBlockingError err = blockingError != null
                ? new HookBlockingError(blockingError, null) : null;
            return new HookResult(true, err, null, null, null, null, null,
                null, null, HookOutcome.BLOCKING, stopReason, null, null, null,
                null, null, null, null);
        }

        /**
         * [R32-b13 B9] 返回"允许重试"结果 · 对齐 CC
         * Open-ClaudeCode/src/utils/hooks.ts:2887-2892 yield {@code {retry: true}}.
         *
         * <p>PermissionDenied hook 可调此工厂返回 HookResult.withRetry(), LlmAgentLoop 在
         * {@code applyPermissionFilter} Deny 分支消费 retry=true 时, 注入 isMeta user
         * message 告诉 LLM 可以重试（对齐 CC toolExecution.ts:1092-1099）。
         *
         * <p>WHY 命名 {@code withRetry()} 而非 {@code retry()}:
         * Java record 自动生成 {@code retry()} 字段 accessor, 静态工厂同名会冲突.
         * 用 {@code withRetry()} 保留 retry 字段语义, 不影响 record accessor.
         *
         * <p>retry 字段为 nullable Boolean, 仅 true 时触发 retry 行为; false/null 不触发.
         */
        public static HookResult withRetry() {
            return new HookResult(false, null, null, null, null, null, null,
                true, null, HookOutcome.SUCCESS, null, null, null, null,
                null, null, null, null);
        }
    }

    /**
     * [Session I P3-1] Hook 结果 outcome 枚举 · 对齐 CC Open-ClaudeCode/src/utils/hooks.ts:342
     * {@code outcome: 'success' | 'blocking' | 'non_blocking_error' | 'cancelled'}.
     *
     * <p>CC 真源 4 值 union, Java 端落地为 enum, snake_case 转 SCREAMING_SNAKE_CASE.
     *
     * <ul>
     *   <li>{@link #SUCCESS} — CC original: 'success'</li>
     *   <li>{@link #BLOCKING} — CC original: 'blocking'</li>
     *   <li>{@link #NON_BLOCKING_ERROR} — CC original: 'non_blocking_error'</li>
     *   <li>{@link #CANCELLED} — CC original: 'cancelled'</li>
     * </ul>
     */
    enum HookOutcome {
        SUCCESS,
        BLOCKING,
        NON_BLOCKING_ERROR,
        CANCELLED
    }
}
