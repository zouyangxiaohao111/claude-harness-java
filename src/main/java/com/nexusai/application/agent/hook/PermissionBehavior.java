package com.nexusai.application.agent.hook;

/**
 * Hook 结果的权限行为枚举 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks.ts:349}
 * {@code HookResult.permissionBehavior?: 'ask' | 'deny' | 'allow' | 'passthrough'}.
 *
 * <p>[D session] 从退役的 {@code ToolHooks.java} (R32-D 死代码, 全文件 0 生产调用)
 * 中独立出的唯一 live 成员 — 被 {@link com.nexusai.application.agent.permission.hook.GenericHook.HookResult#permissionBehavior()}
 * 引用 (H series Session I 选用本枚举对齐 CC hooks.ts:345-349). ToolHooks 其余部分
 * (HookResult/HookDecision/HookEvent/runPostToolUseHooks/PostToolUseHookEvent)
 * 全部 0 调用, 已删除. {@code resolveHookPermissionDecision} 已于 [D P1-2] 以
 * {@link com.nexusai.application.agent.LlmAgentLoop#resolveHookPermissionDecision}
 * 7 参静态入口形式恢复并接线 (对齐 CC toolExecution.ts:921-929 调用点, 委托
 * {@link com.nexusai.application.agent.permission.hook.HookPermissionResolver}).
 *
 * <p><b>PASSTHROUGH 保留说明</b>: D.md/V2 声称 "CC 无 PASSTHROUGH" 已实证为过时
 * (Pattern #9) — CC hooks.ts:349 union 明确含 {@code 'passthrough'}; CC
 * types/permissions.ts:251-266 的 {@code PermissionResult} 也有 passthrough 态.
 * 故枚举保留 4 值完整对齐 CC.
 */
public enum PermissionBehavior {
    ALLOW,
    DENY,
    ASK,
    PASSTHROUGH
}
