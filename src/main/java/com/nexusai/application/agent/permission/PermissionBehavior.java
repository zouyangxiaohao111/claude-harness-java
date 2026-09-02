package com.nexusai.application.agent.permission;

/**
 * 规则行为 · 对齐 CC {@code types/permissions.ts:44}
 *
 * <p>每条规则只能是 3 种行为之一：
 * <ul>
 *   <li>{@link #ALLOW} — 允许</li>
 *   <li>{@link #DENY} — 拒绝</li>
 *   <li>{@link #ASK} — 询问</li>
 * </ul>
 *
 * <p>注意：与 {@link PermissionResult} 不同——{@link PermissionResult} 有 4 种 behavior
 * （含 {@code passthrough}），但 {@code PermissionRule} 只有 3 种（passthrough
 * 不是规则的 behavior）。
 *
 * @see PermissionRule
 * @see PermissionResult
 */
public enum PermissionBehavior {
    ALLOW,
    DENY,
    ASK;
}
