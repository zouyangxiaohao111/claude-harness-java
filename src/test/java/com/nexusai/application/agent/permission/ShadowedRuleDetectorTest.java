package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.ShadowedRuleDetector.ShadowType;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShadowedRuleDetector 遮蔽模型测试 · 对齐 CC {@code shadowedRuleDetection.ts}
 * （S15：T02 R3 遮蔽判定模型修复）。
 *
 * <p><b>CC 模型（与旧 Java 模型的三处根本差异）</b>：
 * <ol>
 *   <li><b>仅 tool-wide 遮蔽带 content</b>：同 tool 的 tool-wide deny/ask（ruleContent 为空）
 *       遮蔽带 content 的 allow；带 content 的 deny/ask <b>不</b>遮蔽带 content 的 allow
 *       （旧模型是 ruleContent 全等匹配）</li>
 *   <li><b>源无关</b>：不比较 source 优先级——任何 source 的 tool-wide deny/ask
 *       遮蔽任何 source 的带 content allow（旧模型仅高优先级 source 遮蔽）</li>
 *   <li><b>tool-wide allow 不遮蔽</b>：ruleContent 为空的 allow 不被判遮蔽；
 *       ask 遮蔽带 sandbox 例外（Bash + sandbox 自动放行 + ask 来自个人 source → 不遮蔽）</li>
 * </ol>
 *
 * @see ShadowedRuleDetector
 * @since S15
 */
class ShadowedRuleDetectorTest {

    private final ShadowedRuleDetector detector = new ShadowedRuleDetector();

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
                                       String toolName, String content) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(toolName, content));
    }

    private static ToolPermissionContext ctx(
            Set<PermissionRule> allow, Set<PermissionRule> deny, Set<PermissionRule> ask) {
        return ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.USER_SETTINGS, allow),
            Map.of(PermissionRuleSource.USER_SETTINGS, deny),
            Map.of(PermissionRuleSource.USER_SETTINGS, ask),
            Map.of());
    }

    // ─────────────────────── 验收 1: tool-wide deny/ask 遮蔽带 content allow ───────────────────────

    @Test
    @DisplayName("tool-wide deny 遮蔽同 tool 带 content 的 allow → 判定遮蔽 (deny)")
    void toolWideDeny_shadowsContentAllow() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", null)),
            Set.of()));

        assertThat(result).hasSize(1);
        ShadowedRuleDetector.ShadowedRule sr = result.get(0);
        assertThat(sr.shadowType()).isEqualTo(ShadowType.DENY);
        assertThat(sr.rule().ruleValue().toolName()).isEqualTo("Bash");
        assertThat(sr.rule().ruleValue().ruleContent()).isEqualTo("ls:*");
        assertThat(sr.shadowedBy().ruleValue().ruleContent()).isNull();
        assertThat(sr.reason()).contains("Blocked by \"Bash\" deny rule (from user settings)");
        assertThat(sr.fix()).contains("Remove the \"Bash\" deny rule from user settings");
    }

    @Test
    @DisplayName("tool-wide ask 遮蔽同 tool 带 content 的 allow → 判定遮蔽 (ask)")
    void toolWideAsk_shadowsContentAllow() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", "src/**")),
            Set.of(),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Edit", null))));

        assertThat(result).hasSize(1);
        ShadowedRuleDetector.ShadowedRule sr = result.get(0);
        assertThat(sr.shadowType()).isEqualTo(ShadowType.ASK);
        assertThat(sr.reason()).contains("Shadowed by \"Edit\" ask rule (from user settings)");
        assertThat(sr.fix()).contains("Remove the \"Edit\" ask rule from user settings");
    }

    // ─────────────────────── 验收 2: 源无关（跨 source 仍判定） ───────────────────────

    @Test
    @DisplayName("跨 source 遮蔽: 低优先级 source 的 tool-wide deny 也遮蔽高优先级 source 的 allow (源无关)")
    void shadowAcrossSources_sourceAgnostic() {
        // 旧模型: 仅高优先级 source (session > user) 遮蔽 → 反方向不遮蔽
        // CC 模型: 任何 source 的 tool-wide deny 遮蔽任何 source 的 allow
        ToolPermissionContext multiSource = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, "Bash", "ls:*"))),
            Map.of(PermissionRuleSource.USER_SETTINGS,
                Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", null))),
            Map.of(),
            Map.of());

        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(multiSource);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).rule().source()).isEqualTo(PermissionRuleSource.SESSION);
        // DEL-WF2-RL-01：CC UnreachableRule 无顶层 source 字段，source 经 rule.source() 读取
        assertThat(result.get(0).shadowedBy().source()).isEqualTo(PermissionRuleSource.USER_SETTINGS);
    }

    // ─────────────────────── 验收 3: sandbox 例外 ───────────────────────

    @Test
    @DisplayName("sandbox 例外: Bash + sandbox 自动放行 + ask 来自个人 source → 不遮蔽")
    void sandboxException_personalAskNotShadowBash() {
        detector.setSandboxManager(new SandboxManager(true, true));

        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Bash", null))));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sandbox 例外不适用: ask 来自 shared source (projectSettings) → 仍遮蔽")
    void sandboxException_sharedAskStillShadows() {
        detector.setSandboxManager(new SandboxManager(true, true));

        ToolPermissionContext multiSource = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.USER_SETTINGS,
                Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*"))),
            Map.of(),
            Map.of(PermissionRuleSource.PROJECT_SETTINGS,
                Set.of(rule(PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.ASK, "Bash", null))),
            Map.of());

        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(multiSource);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).shadowType()).isEqualTo(ShadowType.ASK);
    }

    @Test
    @DisplayName("sandbox 例外仅限 Bash: 非 Bash tool 带 sandbox → 仍遮蔽")
    void sandboxException_nonBashToolStillShadows() {
        detector.setSandboxManager(new SandboxManager(true, true));

        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", "src/**")),
            Set.of(),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Edit", null))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shadowType()).isEqualTo(ShadowType.ASK);
    }

    @Test
    @DisplayName("sandbox 未启用 (默认 null) → ask 遮蔽生效, 无例外")
    void sandboxDisabled_askShadows() {
        // detector.sandboxManager 默认 null → sandboxAutoAllowEnabled=false → 无例外
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Bash", null))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shadowType()).isEqualTo(ShadowType.ASK);
    }

    // ─────────────────────── 验收 4: tool-wide allow 不遮蔽 ───────────────────────

    @Test
    @DisplayName("tool-wide allow 不遮蔽任何规则 (同 tool deny/ask 并存也不遮蔽)")
    void toolWideAllow_notShadowed() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", null)),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", null)),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Bash", null))));

        assertThat(result).isEmpty();
    }

    // ─────────────────────── 旧模型残留行为必须删除 ───────────────────────

    @Test
    @DisplayName("带 content 的 deny 不遮蔽带 content 的 allow (仅 tool-wide 遮蔽; 旧全等匹配已删除)")
    void contentDeny_doesNotShadowContentAllow() {
        // 旧模型: ruleContent 全等匹配 → Bash(ls:*) vs Bash(ls:*) 判定遮蔽 (错误)
        // CC 模型: 遮蔽只来自 tool-wide deny (ruleContent 为空) → 不遮蔽
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", "ls:*")),
            Set.of()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("不同 tool 不遮蔽")
    void differentTool_notShadowed() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Edit", null)),
            Set.of()));

        assertThat(result).isEmpty();
    }

    // ─────────────────────── deny 优先 ───────────────────────

    @Test
    @DisplayName("deny 遮蔽优先: deny+ask 同时命中 → 仅报 deny 一条 (CC continue 语义)")
    void denyShadowing_takesPriorityOverAsk() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*")),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", null)),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK, "Bash", null))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shadowType()).isEqualTo(ShadowType.DENY);
        assertThat(result.get(0).reason()).contains("deny rule");
    }

    @Test
    @DisplayName("多条 allow 规则各自独立判定")
    void multipleAllowRules_eachEvaluated() {
        List<ShadowedRuleDetector.ShadowedRule> result = detector.detectShadowedRules(ctx(
            Set.of(
                rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Bash", "ls:*"),
                rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, "Edit", "src/**")),
            Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY, "Bash", null)),
            Set.of()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rule().ruleValue().toolName()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("空规则集 → 空结果")
    void emptyRules_noShadowed() {
        assertThat(detector.detectShadowedRules(ctx(Set.of(), Set.of(), Set.of()))).isEmpty();
    }
}
