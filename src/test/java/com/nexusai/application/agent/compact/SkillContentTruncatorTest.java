package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [P1-6-READ-3] SkillContentTruncator 静态工具类测试 ·
 * 对齐 CC services/compact/compact.ts:1657-1672 truncateToTokens + SKILL_TRUNCATION_MARKER
 * + services/tokenEstimation.ts:203-208 roughTokenCountEstimation。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: skill 文件可大到 20KB，压缩后重注入
 * 必须截断到 POST_COMPACT_MAX_TOKENS_PER_SKILL=5000 token 且保留文件头部。测试验证
 * <b>忠实 Math.round 语义</b>（非整数除法 floor）、超预算截断恒 {@code <=} 预算、
 * 不超预算零改动、{@code <=} 边界（CC 用 <= 不用 <）与 marker 精确文本 —— 任一条
 * 语义偏移都会破坏"保留头部 + 预算约束 + 可 Read 全文"的重注入契约。
 */
class SkillContentTruncatorTest {

    @Test
    @DisplayName("Math.round 语义: len=6→2 (整数除法得 1 即失败), len=8→2, len=22000→5500")
    void roughTokenCountEstimationUsesMathRoundNotFloor() {
        // WHY: CC tokenEstimation.ts:207 是 Math.round(len/4)，len=6 → round(1.5)=2；
        // 若误用整数除法 len/4 → floor(1.5)=1，0.5 边界偏移会让 token 估算偏低，
        // 导致超预算内容逃过截断，重注入时冲击 POST_COMPACT_SKILLS_TOKEN_BUDGET。
        assertThat(SkillContentTruncator.roughTokenCountEstimation("abcdef")).isEqualTo(2);
        assertThat(SkillContentTruncator.roughTokenCountEstimation("abcdefgh")).isEqualTo(2);
        assertThat(SkillContentTruncator.roughTokenCountEstimation("a".repeat(22_000))).isEqualTo(5_500);
    }

    @Test
    @DisplayName("超预算截断: 22000 字符 + maxTokens=5000 → 保留头部 + marker, 恒 <= 预算")
    void truncateOverBudgetKeepsHeadAndMarker() {
        String content = "a".repeat(22_000);   // 5500 tokens > 5000 → 触发截断
        String truncated = SkillContentTruncator.truncateToTokens(content, 5_000);

        // charBudget = 5000*4 - 100(marker) = 19900; 结果 len = 19900 + 100 = 20000
        assertThat(truncated).hasSize(20_000);
        // 保留头部（setup/usage 指引在文件最前）
        assertThat(truncated).startsWith("a");
        // 尾部为截断 marker
        assertThat(truncated).endsWith(SkillContentTruncator.SKILL_TRUNCATION_MARKER);
        // 截断后仍 <= 预算（CC compact.ts:1661-1663 注释：减去 marker 使结果恒在预算内）
        assertThat(SkillContentTruncator.roughTokenCountEstimation(truncated)).isLessThanOrEqualTo(5_000);
        // 头部内容就是原文前 19900 字符（保头不保尾）
        assertThat(truncated.substring(0, 19_900)).isEqualTo(content.substring(0, 19_900));
    }

    @Test
    @DisplayName("不超预算: 短文原样返回（等值）")
    void underBudgetReturnsContentUnchanged() {
        String content = "short content preserved";
        assertThat(SkillContentTruncator.truncateToTokens(content, 5_000)).isSameAs(content);
    }

    @Test
    @DisplayName("边界: rough(content) == maxTokens 不截断（CC 用 <= 不是 <）")
    void exactBudgetIsNotTruncated() {
        // len=20 → round(20/4.0)=5 tokens == maxTokens=5 → CC:1667 <= 成立 → 原样返回
        String content = "a".repeat(20);
        assertThat(SkillContentTruncator.roughTokenCountEstimation(content)).isEqualTo(5);
        assertThat(SkillContentTruncator.truncateToTokens(content, 5)).isSameAs(content);
    }

    @Test
    @DisplayName("SKILL_TRUNCATION_MARKER 精确文本: len=100 含双换行前导")
    void markerExactText() {
        assertThat(SkillContentTruncator.SKILL_TRUNCATION_MARKER)
            .isEqualTo("\n\n[... skill content truncated for compaction; use Read on the skill path if you need the full text]");
        assertThat(SkillContentTruncator.SKILL_TRUNCATION_MARKER).hasSize(100);
        assertThat(SkillContentTruncator.SKILL_TRUNCATION_MARKER).startsWith("\n\n");
    }

    @Test
    @DisplayName("null 输入抛 NPE（对齐 CC roughTokenCountEstimation content.length 遇 null 抛异常，P3-37 删除 Java null 防御）")
    void nullInputThrowsLikeCc() {
        // WHY: CC compact.ts:1667 roughTokenCountEstimation(content) 直接访问 content.length，
        // null 输入会抛异常（Token 估算无 null 场景——调用方 addInvokedSkill/truncateToTokens
        // 均保证非 null）。P3-37 删除 Java 的 null→0/null→null 防御扩展，忠实 CC 遇 null 抛异常。
        assertThatThrownBy(() -> SkillContentTruncator.roughTokenCountEstimation(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SkillContentTruncator.truncateToTokens(null, 5_000))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("钳制忠实 JS slice: charBudget<0 从尾部计数不抛异常（slice(0,-80)=slice(0,20)）")
    void negativeCharBudgetFollowsJsSliceTailSemantics() {
        // WHY: CC compact.ts:1671 是 content.slice(0, charBudget)。JS slice(0, -80) 并非钳 0，
        // 而是 max(length+charBudget, 0)=20（负数从尾部计数）。maxTokens=5 → charBudget=20-100=-80
        // → Java substring(0, 20)。若误钳为 0 或直接 substring(0,-80) 抛异常，均偏离 JS 语义。
        String content = "a".repeat(100);
        String result = SkillContentTruncator.truncateToTokens(content, 5);
        assertThat(result).hasSize(20 + SkillContentTruncator.SKILL_TRUNCATION_MARKER.length());
        assertThat(result).startsWith("a".repeat(20));
        assertThat(result).endsWith(SkillContentTruncator.SKILL_TRUNCATION_MARKER);
    }
}
