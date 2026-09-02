package com.nexusai.application.agent.permission.hook;

/**
 * Hook 来源枚举 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts:15-20}
 * {@code HookSource = EditableSettingSource | 'policySettings' | 'pluginHook' | 'sessionHook' | 'builtinHook'}.
 *
 * <p>WHY: CC 用 HookSource 标记每个 hook 的来源, 用于 UI 分组展示 + 优先级排序
 * (sortMatchersByPriority 按 source 优先级). Java 端用 enum 表达, 每个常量
 * JavaDoc 标注 CC 原名, 便于审计.
 *
 * <p><b>CC 真源 (hooksSettings.ts:15-20)</b>:
 * <ul>
 *   <li>EditableSettingSource = userSettings / projectSettings / localSettings
 *       (CC settings/constants.ts)</li>
 *   <li>policySettings — 企业管控策略 (read-only)</li>
 *   <li>pluginHook — 插件注册的 hook</li>
 *   <li>sessionHook — 运行时会话内临时 hook (内存, 不持久化)</li>
 *   <li>builtinHook — Claude Code 内部注册的 hook</li>
 * </ul>
 *
 * <p><b>与 {@link com.nexusai.application.agent.permission.PermissionRuleSource} 的关系</b>:
 * PermissionRuleSource 覆盖 8 个权限规则来源 (含 FLAG_SETTINGS/CLI_ARG/COMMAND/SESSION),
 * HookSource 仅覆盖 7 个 hook 来源 (无 FLAG/CLI_ARG/COMMAND, 但有 PLUGIN_HOOK/SESSION_HOOK/BUILTIN_HOOK).
 * 二者不共用, 因为 hook 与 permission rule 的来源集合不同 (CC 也是分开定义).
 *
 */
public enum HookSource {
    /** CC original: userSettings (~/.claude/settings.json). */
    USER_SETTINGS,
    /** CC original: projectSettings (.claude/settings.json). */
    PROJECT_SETTINGS,
    /** CC original: localSettings (.claude/settings.local.json). */
    LOCAL_SETTINGS,
    /** CC original: policySettings (企业 managed policy, read-only). */
    POLICY_SETTINGS,
    /** CC original: pluginHook (插件注册的 hook). */
    PLUGIN_HOOK,
    /** CC original: sessionHook (运行时会话内临时 hook, 内存). */
    SESSION_HOOK,
    /** CC original: builtinHook (Claude Code 内部注册的 hook). */
    BUILTIN_HOOK;
}
