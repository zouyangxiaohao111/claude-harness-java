package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [hooks_v3 5-W3-6 / 交叉核验 WF3-X1] 弹窗「为什么问」文案对齐 CC
 * {@code PermissionRuleExplanation.stringsForDecisionReason} (PermissionRuleExplanation.tsx:21-67).
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: Java ask 弹窗可见文案 (reason.detail) 原为
 * {@code PermissionDecisionReason.toString()} record 调试格式 (如
 * {@code Rule[rule=PermissionRule[...]]})，前端 PermissionBubble.tsx 原样渲染 —
 * 用户看到的是调试格式而非 CC 人类句。本测试锁死 CC 人类句，防止回归到 toString.
 */
class PermissionRuleExplanationTest {

    private static PermissionDecisionReason rule(String toolName, String ruleContent) {
        return new PermissionDecisionReason.Rule(new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK,
            ruleContent == null ? PermissionRuleValue.wholeTool(toolName)
                                : PermissionRuleValue.withContent(toolName, ruleContent)));
    }

    @Test
    @DisplayName("whole-tool rule → 'Permission rule Bash requires confirmation for this tool.' (CC :41)")
    void ruleWholeTool_ccCopy() {
        assertThat(PermissionRuleExplanation.renderDetail(rule("Bash", null)))
            .isEqualTo("Permission rule Bash requires confirmation for this tool.");
    }

    @Test
    @DisplayName("content rule → ruleValue 复用 toRuleString (CC permissionRuleValueToString)")
    void ruleWithContent_ccCopy() {
        assertThat(PermissionRuleExplanation.renderDetail(rule("Bash", "npm install")))
            .isEqualTo("Permission rule Bash(npm install) requires confirmation for this tool.");
    }

    @Test
    @DisplayName("hook with reason+source → 'Hook X requires confirmation for this tool:\\n<reason> [<source>]' (CC :44-52)")
    void hookWithReasonAndSource_ccCopy() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Hook(
            "PermissionRequest", "userSettings", "policy blocked");
        assertThat(PermissionRuleExplanation.renderDetail(reason))
            .isEqualTo("Hook PermissionRequest requires confirmation for this tool:\npolicy blocked [userSettings]");
    }

    @Test
    @DisplayName("hook no reason/source → 句尾 '.' (CC :46)")
    void hookWithoutReason_ccCopy() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Hook("MyHook", null, null);
        assertThat(PermissionRuleExplanation.renderDetail(reason))
            .isEqualTo("Hook MyHook requires confirmation for this tool.");
    }

    @Test
    @DisplayName("classifier auto-mode → 'Auto mode classifier requires confirmation for this tool.\\n<reason>' (CC :26-32)")
    void classifierAutoMode_ccCopy() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Classifier(
            "auto-mode", "Allowed by prompt rule: \"Bash\"");
        assertThat(PermissionRuleExplanation.renderDetail(reason))
            .isEqualTo("Auto mode classifier requires confirmation for this tool.\nAllowed by prompt rule: \"Bash\"");
    }

    @Test
    @DisplayName("classifier 非 auto-mode → 'Classifier <name> requires confirmation for this tool.\\n<reason>' (CC :33-36)")
    void classifierNonAutoMode_ccCopy() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Classifier(
            "bash_allow", "uncertain");
        assertThat(PermissionRuleExplanation.renderDetail(reason))
            .isEqualTo("Classifier bash_allow requires confirmation for this tool.\nuncertain");
    }

    @Test
    @DisplayName("safetyCheck / other / workingDir → 原 reason 文本 (CC :53-63)")
    void reasonCarryingTypes_useRawReason() {
        assertThat(PermissionRuleExplanation.renderDetail(
                new PermissionDecisionReason.SafetyCheck("writing to ~/.claude/settings.json", false)))
            .isEqualTo("writing to ~/.claude/settings.json");
        assertThat(PermissionRuleExplanation.renderDetail(
                new PermissionDecisionReason.Other("timeout")))
            .isEqualTo("timeout");
        assertThat(PermissionRuleExplanation.renderDetail(
                new PermissionDecisionReason.WorkingDir("outside working directory")))
            .isEqualTo("outside working directory");
    }

    @Test
    @DisplayName("CC default 类型 (mode) → null (弹窗不显示文案, 对齐 CC default case)")
    void ccDefaultType_returnsNull() {
        // RED→GREEN：删除前 renderDetail 对 CC 无文案类型兜底 reason.toString() → 返回 "Mode[...]"
        // → 本断言 null 失败（RED）。删除后 → 对齐 CC PermissionRuleExplanation.tsx:64-67
        // default 返回 null（弹窗不显示文案）→ 通过（GREEN）。
        // WHY（规则九 · 验证意图）：DEL-WF7-EX-02 拍板删除 toString 兜底——CC 对 mode 等类型
        // 返回 null 表示"无文案"，Java 不应回退到 record 调试格式（前端消费面已核对无依赖）。
        assertThat(PermissionRuleExplanation.renderDetail(
                new PermissionDecisionReason.Mode(PermissionMode.DEFAULT)))
            .isNull();
    }

    @Test
    @DisplayName("null reason → null (调用方按无文案处理)")
    void nullReason_returnsNull() {
        assertThat(PermissionRuleExplanation.renderDetail(null)).isNull();
    }
}
