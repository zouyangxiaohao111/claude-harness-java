package com.nexusai.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ER-IMP-13] TokenBudgetParser.getBudgetContinuationMessage 对齐 CC utils/tokenBudget.ts:66-73。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 文案
 * {@code `Stopped at ${pct}% of token target (${fmt(turnTokens)} / ${fmt(budget)}). Keep working — do not summarize.`}
 * 其中 {@code fmt = Intl.NumberFormat('en-US')}（千分位）。Java 若漏千分位（"500000" 而非 "500,000"）
 * 或文案漂移，LLM 收到的 nudge 与 CC 不一致；TokenBudgetChecker 已单源化委托本方法（双实现漂移删除）。
 * RED tooth：改回无千分位拼接 → "5,000 / 500,000" 断言 fail。
 */
class TokenBudgetParserCcTest {

    @Test
    @DisplayName("getBudgetContinuationMessage: en-US 千分位 + em-dash + 精确文案（CC utils/tokenBudget.ts:66-73）")
    void getBudgetContinuationMessage_matchesCcFormat() {
        String msg = TokenBudgetParser.getBudgetContinuationMessage(5, 5_000, 500_000);
        // CC 原文等价：Stopped at 5% of token target (5,000 / 500,000). Keep working — do not summarize.
        assertThat(msg)
            .as("pct/turnTokens/budget 全量千分位 + em-dash 精确匹配（Intl.NumberFormat en-US）")
            .isEqualTo("Stopped at 5% of token target (5,000 / 500,000). Keep working — do not summarize.");
    }

    @Test
    @DisplayName("getBudgetContinuationMessage: 大数值 en-US 千分位（90% · 1.8M / 2M · CC NumberFormat 语义）")
    void getBudgetContinuationMessage_largeValues_enUsThousands() {
        String msg = TokenBudgetParser.getBudgetContinuationMessage(90, 1_800_000, 2_000_000);
        assertThat(msg)
            .as("90% 分支：turnTokens/budget 均带 en-US 千分位（%,d），禁止无分隔拼接")
            .contains("Stopped at 90% of token target (1,800,000 / 2,000,000)")
            .doesNotContain("1800000")
            .doesNotContain("2000000");
    }
}
