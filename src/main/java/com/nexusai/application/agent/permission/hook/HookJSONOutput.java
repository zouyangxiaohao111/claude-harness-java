package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * [Session H3] Hook JSON 输出 union · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/types/hooks.ts:169-176} {@code hookJSONOutputSchema} +
 * {@code Open-ClaudeCode/src/types/hooks.ts:182-193} isSyncHookJSONOutput / isAsyncHookJSONOutput.
 *
 * <p>CC 真源 (types/hooks.ts:169-176, grep 自验 2026-08-01):
 * <pre>
 * export const hookJSONOutputSchema = lazySchema(() => {
 *   const asyncHookResponseSchema = z.object({
 *     async: z.literal(true),
 *     asyncTimeout: z.number().optional(),
 *   })
 *   return z.union([asyncHookResponseSchema, syncHookResponseSchema()])
 * })
 * </pre>
 *
 * <p><b>WHY (规则三 + Pattern #8)</b>: CC 的 hook stdout JSON 是 async ∪ sync 两个 discriminated
 * union, async 判别 ({@code 'async' in json && json.async === true}, types/hooks.ts:192) 决定
 * 是否后台化 (backgrounded). Java 端用 sealed interface + 2 record 表达: {@link AsyncHookOutput}
 * 对应 async 分支, {@link SyncHookOutput} 对应 sync 7 顶层字段 + hookSpecificOutput.
 *
 * <p>字段全 nullable (除 async), 对齐 CC Zod optional 语义.
 *
 * @since Session H3
 */
// 无显式 permits 子句: 两个 record 嵌套同文件, javac 自动推断 permitted subclasses
//   (JLS 9.1.4: 同 compilation unit 的直接子类即 permitted)
public sealed interface HookJSONOutput {

    /**
     * 是否 sync 输出 · 对齐 CC {@code isSyncHookJSONOutput} (types/hooks.ts:182-186):
     * {@code !('async' in json && json.async === true)}.
     *
     * <p>Java 端 async 判别 = {@code instanceof AsyncHookOutput}, 非 async 即 sync.
     */
    static boolean isSyncHookJSONOutput(HookJSONOutput json) {
        return !isAsyncHookJSONOutput(json);
    }

    /**
     * 是否 async 输出 · 对齐 CC {@code isAsyncHookJSONOutput} (types/hooks.ts:189-193):
     * {@code 'async' in json && json.async === true}.
     *
     * <p>WHY 判别方式是 {@code instanceof AsyncHookOutput}: Java sealed interface 的运行时
     * 类型即判别结果, 等价 CC 的 {@code 'async' in json && json.async === true}.
     */
    static boolean isAsyncHookJSONOutput(HookJSONOutput json) {
        return json instanceof AsyncHookOutput;
    }

    /**
     * CC syncHookResponseSchema 7 顶层字段 (types/hooks.ts:50-166) + hookSpecificOutput.
     *
     * @param continueExecution  CC original: {@code continue} (types/hooks.ts:52-55);
     *                           false=阻止继续, optional; Java 关键字 {@code continue} 避让
     * @param suppressOutput     CC original: {@code suppressOutput} (types/hooks.ts:56-59);
     *                           true=从 transcript 隐藏 stdout, optional
     * @param stopReason         CC original: {@code stopReason} (types/hooks.ts:60-63);
     *                           continue=false 时展示的消息, optional
     * @param decision           CC original: {@code decision} (types/hooks.ts:64);
     *                           'approve'|'block', optional
     * @param reason             CC original: {@code reason} (types/hooks.ts:65);
     *                           决策原因文本, optional
     * @param systemMessage      CC original: {@code systemMessage} (types/hooks.ts:66-69);
     *                           展示给用户的警告消息, optional
     * @param hookSpecificOutput CC original: {@code hookSpecificOutput} (types/hooks.ts:70-164);
     *                           15 子类型 union, optional
     */
    record SyncHookOutput(
        @JsonProperty("continue") Boolean continueExecution,
        @JsonProperty("suppressOutput") Boolean suppressOutput,
        @JsonProperty("stopReason") String stopReason,
        @JsonProperty("decision") String decision,
        @JsonProperty("reason") String reason,
        @JsonProperty("systemMessage") String systemMessage,
        @JsonProperty("hookSpecificOutput") HookSpecificOutput hookSpecificOutput
    ) implements HookJSONOutput {
    }

    /**
     * CC asyncHookResponseSchema (types/hooks.ts:171-174).
     *
     * @param async        CC original: {@code async} (types/hooks.ts:172); 必传 true (z.literal)
     * @param asyncTimeout CC original: {@code asyncTimeout} (types/hooks.ts:173); optional
     */
    record AsyncHookOutput(
        @JsonProperty("async") boolean async,
        @JsonProperty("asyncTimeout") Long asyncTimeout
    ) implements HookJSONOutput {
    }
}
