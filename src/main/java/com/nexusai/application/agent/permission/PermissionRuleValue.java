package com.nexusai.application.agent.permission;

/**
 * 规则值（工具名 + 可选内容限定符） · 对齐 CC {@code types/permissions.ts:67-71}
 *
 * <p>序列化字符串格式：
 * <ul>
 *   <li>{@code "Bash"} → 整个工具</li>
 *   <li>{@code "Bash(npm publish:*)"} → 工具 + 内容（括号内可转义）</li>
 * </ul>
 *
 * <p>转义规则（{@code permissionRuleParser.ts}）：
 * <ol>
 *   <li>先转义反斜杠 {@code \} → {@code \\}</li>
 *   <li>再转义括号 {@code (} → {@code \(}，{@code )} → {@code \)}</li>
 * </ol>
 *
 * <p>反序列化在 PR 3 由 {@code permissionRuleParser} 实现。
 *
 * @see PermissionRule
 */
public record PermissionRuleValue(
        String toolName,
        String ruleContent
) {
    public PermissionRuleValue {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("PermissionRuleValue.toolName is blank");
        }
        // ruleContent 可以为 null —— 表示整个工具的规则（无内容限定符）
    }

    /**
     * 整个工具的规则（无内容限定符）。
     *
     * @param toolName 工具名（非空）
     */
    public static PermissionRuleValue wholeTool(String toolName) {
        return new PermissionRuleValue(toolName, null);
    }

    /**
     * 带内容限定符的规则。
     *
     * @param toolName    工具名（非空）
     * @param ruleContent 内容限定符（如 {@code "npm publish:*"}）
     */
    public static PermissionRuleValue withContent(String toolName, String ruleContent) {
        return new PermissionRuleValue(toolName, ruleContent);
    }

    /**
     * 转义为字符串（对齐 {@code permissionRuleValueToString in permissionRuleParser.ts:144-152}）。
     * PR 3 会有完整实现（含反序列化）。
     */
    public String toRuleString() {
        if (ruleContent == null) {
            return toolName;
        }
        String escaped = ruleContent
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
        return toolName + "(" + escaped + ")";
    }
}
