package com.nexusai.application.agent.permission.hook;

/**
 * [Session H4] SessionEnd 退出原因枚举 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:747-754} {@code EXIT_REASONS}
 * + {@code coreTypes.ts:55-62} (grep 自验 2026-07-30).
 *
 * <p>CC 真源 (coreSchemas.ts:747-754):
 * <pre>
 * export const EXIT_REASONS = [
 *   'clear', 'resume', 'logout', 'prompt_input_exit', 'other', 'bypass_permissions_disabled',
 * ] as const
 * export const ExitReasonSchema = lazySchema(() => z.enum(EXIT_REASONS))
 * </pre>
 *
 * <p>WHY (规则三): 之前 Java HookEvent.sessionEnd(reason) 用 String 承载, 丢失 CC enum
 * 约束. H4 补全为 enum, SessionEnd 工厂签名改用此 enum (破约改签名). snake_case 转
 * SCREAMING_SNAKE_CASE, 每个常量 JavaDoc 标注 CC 原名.
 *
 * @since Session H4
 */
public enum ExitReasons {
    /** CC original: {@code clear} (coreSchemas.ts:748). 会话清空退出. */
    CLEAR,
    /** CC original: {@code resume} (coreSchemas.ts:749). 会话恢复退出. */
    RESUME,
    /** CC original: {@code logout} (coreSchemas.ts:750). 用户登出退出. */
    LOGOUT,
    /** CC original: {@code prompt_input_exit} (coreSchemas.ts:751). prompt 输入退出. */
    PROMPT_INPUT_EXIT,
    /** CC original: {@code other} (coreSchemas.ts:752). 其他退出原因. */
    OTHER,
    /** CC original: {@code bypass_permissions_disabled} (coreSchemas.ts:753). 绕过权限被禁用退出. */
    BYPASS_PERMISSIONS_DISABLED;
}