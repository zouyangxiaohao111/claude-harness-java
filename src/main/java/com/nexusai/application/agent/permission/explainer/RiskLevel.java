package com.nexusai.application.agent.permission.explainer;

/**
 * 风险等级 · 对齐 CC {@code type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'}
 * (Open-ClaudeCode/src/utils/permissions/permissionExplainer.ts:14)。
 *
 * <p>CC 的 RiskLevel 是纯字符串 union，无方法、无确认判定、无 UI 文案；
 * Java 以枚举表达三态，并附加 {@link #numericValue()} 承载 CC 的
 * {@code RISK_LEVEL_NUMERIC} 数值映射（permissionExplainer.ts:17-21）：
 * <pre>
 *   LOW: 1, MEDIUM: 2, HIGH: 3
 * </pre>
 * 数值映射仅供 telemetry 使用（CC {@code logEvent('tengu_permission_explainer_generated',
 * risk_level: RISK_LEVEL_NUMERIC[...]})，不参与任何确认语义。
 *
 * @see PermissionExplanation
 * @see PermissionExplainer
 */
public enum RiskLevel {

    /** 安全开发工作流（读文件、搜索等）· CC original: 'LOW'。 */
    LOW,

    /** 可恢复的变更（改文件、执行命令等）· CC original: 'MEDIUM'。 */
    MEDIUM,

    /** 危险/不可逆操作（删除、系统命令等）· CC original: 'HIGH'。 */
    HIGH;

    /**
     * telemetry 数值映射 · CC original: {@code RISK_LEVEL_NUMERIC[RiskLevel]}
     * (permissionExplainer.ts:17-21) = {@code {LOW:1, MEDIUM:2, HIGH:3}}。
     *
     * @return 1（LOW）/ 2（MEDIUM）/ 3（HIGH）
     */
    public int numericValue() {
        return switch (this) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }
}
