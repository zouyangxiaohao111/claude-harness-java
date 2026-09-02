package com.nexusai.application.agent.permission.hook;

/**
 * [Session H4] Hook 阻塞错误结构化 record · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/types/hooks.ts:243-246} {@code HookBlockingError}.
 *
 * <p>CC 真源 (types/hooks.ts:243-246, grep 自验 2026-07-30):
 * <pre>
 * export type HookBlockingError = {
 *   blockingError: string
 *   command: string
 * }
 * </pre>
 *
 * <p>WHY (规则三): 之前 Java GenericHook.HookResult.blockingError 是 String,
 * 承载 CC {@code blockingError} (exit 2 stderr 文本) 但丢失了 {@code command} 字段.
 * H4 补全为结构化 record, 对齐 CC 顶层契约.
 *
 * @param blockingError CC original: {@code blockingError} (types/hooks.ts:244);
 *                     exit 2 stderr 文本, 注入 LLM 作为反馈
 * @param command      CC original: {@code command} (types/hooks.ts:245);
 *                     触发阻塞的 hook 命令串 (审计/UI 展示用)
 * @since Session H4
 */
public record HookBlockingError(
    String blockingError,
    String command
) {
}