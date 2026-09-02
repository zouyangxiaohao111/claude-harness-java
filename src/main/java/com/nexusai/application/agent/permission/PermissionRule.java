package com.nexusai.application.agent.permission;

/**
 * 权限规则 · 对齐 CC {@code types/permissions.ts:75-79}
 *
 * <p>单条规则形如：
 * <pre>
 *   source:        userSettings | projectSettings | ... （8 种）
 *   ruleBehavior:  allow | deny | ask
 *   ruleValue:     { toolName: "Bash", ruleContent: "npm publish:*" }
 * </pre>
 *
 * <p>{@code ruleContent} 是 optional：
 * <ul>
 *   <li>不带 {@code ruleContent} → 整个工具规则（如 {@code "Bash"}）</li>
 *   <li>带 {@code ruleContent} → 内容限定（如 {@code "Bash(npm publish:*)"} 或
 *       {@code "Edit(/Users/foo/**)"}）</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code source} 在 8 种之内（仅用 {@link PermissionRuleSource} 枚举）</li>
 *   <li>{@code toolName} 必须非空</li>
 *   <li>{@code ruleContent} 可空（{@code null} 表示整个工具）</li>
 * </ul>
 *
 * @see PermissionRuleSource
 * @see PermissionBehavior
 * @see PermissionRuleValue
 */
public record PermissionRule(
        PermissionRuleSource source,
        PermissionBehavior ruleBehavior,
        PermissionRuleValue ruleValue
) {
    public PermissionRule {
        if (source == null) {
            throw new IllegalArgumentException("PermissionRule.source is null");
        }
        if (ruleBehavior == null) {
            throw new IllegalArgumentException("PermissionRule.ruleBehavior is null");
        }
        if (ruleValue == null) {
            throw new IllegalArgumentException("PermissionRule.ruleValue is null");
        }
        if (ruleValue.toolName() == null || ruleValue.toolName().isBlank()) {
            throw new IllegalArgumentException("PermissionRule.ruleValue.toolName is blank");
        }
    }
}
