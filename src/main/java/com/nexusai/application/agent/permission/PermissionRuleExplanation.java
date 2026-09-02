package com.nexusai.application.agent.permission;

/**
 * 权限弹窗「为什么问」文案渲染器 · 对齐 CC
 * {@code components/permissions/PermissionRuleExplanation.tsx} {@code stringsForDecisionReason}
 * （PermissionRuleExplanation.tsx:21-67）。
 *
 * <p><b>WHY（hooks_v3 决策 5-W3-6 / 交叉核验 WF3-X1）</b>：Java ask 弹窗可见「为什么问」
 * 即 {@link com.nexusai.eventbus.ws.MessagePermissionRequestEvent} 的 {@code reason.detail}，
 * 原实现为 {@code PermissionDecisionReason.toString()} 的 record 自动调试格式（如
 * {@code Rule[rule=PermissionRule[source=USER_SETTINGS, ...]]}）。CC 弹窗渲染的是
 * {@code stringsForDecisionReason} 派生的<b>人类句</b>（如 {@code Permission rule Bash
 * requires confirmation for this tool.}，PermissionRuleExplanation.tsx:41）。本类把该映射
 * 移植为纯 Java 静态方法，供弹窗 detail 生成。
 *
 * <p><b>口径（交叉核验 X-WF7-03 §9）</b>：按「弹窗可见文案」对齐 CC 弹窗渲染（I3），
 * 而非 {@code createPermissionRequestMessage} 的 message 字段句（两套文本本就不同）。
 * {@code toolType} 恒为 {@code "tool"} —— CC FallbackPermissionRequest.tsx:295 对工具使用
 * 权限弹窗固定传 {@code toolType="tool"}，Java ask 弹窗全部经工具使用权限流程。
 *
 * <p>映射（对应 CC 真源）：
 * <ul>
 *   <li>{@link PermissionDecisionReason.Classifier} → classifier 分支（Java transcript
 *       classifier 默认启用，无条件映射，对齐 PermissionMessageGenerator 同款约定）</li>
 *   <li>{@link PermissionDecisionReason.Rule} → rule 分支（ruleValue 复用
 *       {@link PermissionRuleValue#toRuleString()} = CC permissionRuleValueToString）</li>
 *   <li>{@link PermissionDecisionReason.Hook} → hook 分支</li>
 *   <li>{@link PermissionDecisionReason.SafetyCheck} / {@link PermissionDecisionReason.Other} /
 *       {@link PermissionDecisionReason.WorkingDir} → 原 reason 文本</li>
 *   <li>{@link PermissionDecisionReason.Mode} / SubcommandResults / PermissionPromptTool /
 *       AsyncAgent / SandboxOverride → CC default case 返回 null（弹窗不显示文案）→
 *       {@link #renderDetail} 返回 null（对齐 CC）【DEL-WF7-EX-02：旧 toString 兜底已删，
 *       CC 对无文案类型恒 null，前端消费面已核对无依赖】</li>
 * </ul>
 *
 * @see PermissionDecisionReason
 * @see com.nexusai.eventbus.ws.MessagePermissionRequestEvent
 */
public final class PermissionRuleExplanation {

    private PermissionRuleExplanation() {
        // 静态工具类，不实例化
    }

    /**
     * 渲染弹窗 reason.detail · CC {@code stringsForDecisionReason} 人类句。
     *
     * <p>【DEL-WF7-EX-02】旧 {@code reason.toString()} 兜底已删——CC 对无文案的类型
     * （mode 等）default case 返回 null（PermissionRuleExplanation.tsx:64-67），
     * Java 不应回退到 record 调试格式；renderDetail 无生产调用方（仅测试引用），
     * MessagePermissionRequestEvent 走 serializeDecisionReason 不消费本方法。
     *
     * @param reason 决策归因（可为 null → null，由调用方按「无文案」处理）
     * @return CC 对齐人类句；CC 映射为 null 的类型 → null（对齐 CC default case）
     */
    public static String renderDetail(PermissionDecisionReason reason) {
        if (reason == null) {
            return null;
        }
        return stringsForDecisionReason(reason);
    }

    /**
     * CC {@code stringsForDecisionReason}（PermissionRuleExplanation.tsx:21-67）逐分支移植，
     * {@code toolType="tool"} 固定。返回 CC 人类句；CC default case → null。
     */
    private static String stringsForDecisionReason(PermissionDecisionReason reason) {
        // CC :25-37 classifier 分支 — Java transcript classifier 默认启用（对齐
        // PermissionMessageGenerator.java:52-55 无条件处理 classifier 的约定）
        if (reason instanceof PermissionDecisionReason.Classifier c) {
            if ("auto-mode".equals(c.classifier())) {
                return "Auto mode classifier requires confirmation for this tool.\n" + c.reason();
            }
            return "Classifier " + c.classifier() + " requires confirmation for this tool.\n" + c.reason();
        }
        // CC :39-43 rule 分支 — permissionRuleValueToString(ruleValue) = toRuleString()
        if (reason instanceof PermissionDecisionReason.Rule r) {
            return "Permission rule " + r.rule().ruleValue().toRuleString()
                + " requires confirmation for this tool.";
        }
        // CC :44-52 hook 分支
        if (reason instanceof PermissionDecisionReason.Hook h) {
            String hookReasonString = h.reason() != null && !h.reason().isBlank()
                ? ":\n" + h.reason() : ".";
            String sourceLabel = h.hookSource() != null && !h.hookSource().isBlank()
                ? " [" + h.hookSource() + "]" : "";
            return "Hook " + h.hookName() + " requires confirmation for this tool"
                + hookReasonString + sourceLabel;
        }
        // CC :53-58 safetyCheck / other → reason.reason
        if (reason instanceof PermissionDecisionReason.SafetyCheck sc) {
            return sc.reason();
        }
        if (reason instanceof PermissionDecisionReason.Other o) {
            return o.reason();
        }
        // CC :59-63 workingDir → reason.reason
        if (reason instanceof PermissionDecisionReason.WorkingDir wd) {
            return wd.reason();
        }
        // CC default → null（mode / subcommandResults / permissionPromptTool /
        // asyncAgent / sandboxOverride 弹窗不显示文案）
        return null;
    }
}
