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

    /**
     * CC {@code permissionBehaviorSchema} 字面量（{@code PermissionRule.ts:25-27}，
     * {@code z.enum(['allow','deny','ask'])} 小写、大小写敏感）。
     *
     * <p>[批 A1] {@link PermissionUpdate.WireSerializer} 唯一的 behavior → CC 串映射点。
     * 注意本枚举的 {@code name()}（{@code ALLOW}）不是线格式 —— CC 形状是小写。
     */
    public String ccLiteral() {
        return switch (this) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
        };
    }
}
