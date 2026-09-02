package com.nexusai.application.agent.permission.explainer;

/**
 * 权限解释结果 · 对齐 CC {@code type PermissionExplanation}
 * (Open-ClaudeCode/src/utils/permissions/permissionExplainer.ts:28-33) 四字段全必填：
 * <pre>
 *   { riskLevel: RiskLevel, explanation: string, reasoning: string, risk: string }
 * </pre>
 *
 * <p>由 {@link PermissionExplainer} 调用 LLM 生成，供权限弹窗展示。
 * CC 经 {@code RiskAssessmentSchema}（zod 严格，permissionExplainer.ts:77-84）校验：
 * 四字段全部必填且类型匹配，任一缺失/类型错 → {@code safeParse} 失败 → 返回 null。
 * Java 以 compact constructor 表达同一不变量：四字段任一 null 即抛异常。
 *
 * @param riskLevel   风险等级（必填）· CC original: riskLevel
 * @param explanation 一句话解释（必填）· CC original: explanation
 * @param reasoning   推理过程（必填）· CC original: reasoning
 * @param risk        风险描述（必填）· CC original: risk
 *
 * @see RiskLevel
 * @see PermissionExplainer
 */
public record PermissionExplanation(
        RiskLevel riskLevel,
        String explanation,
        String reasoning,
        String risk
) {

    /**
     * 紧凑构造器 · 对齐 CC zod 严格四字段必填（permissionExplainer.ts:77-84）。
     *
     * <p>WHY：CC {@code RiskAssessmentSchema} 的 {@code z.object({...})} 默认要求
     * 四字段全部存在且类型匹配，缺一即解析失败；Java 以 null 检查表达同一契约，
     * 下游 {@code riskLevel} 开关与弹窗渲染依赖四字段非空。
     */
    public PermissionExplanation {
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel is null");
        }
        if (explanation == null) {
            throw new IllegalArgumentException("explanation is null");
        }
        if (reasoning == null) {
            throw new IllegalArgumentException("reasoning is null");
        }
        if (risk == null) {
            throw new IllegalArgumentException("risk is null");
        }
    }
}
