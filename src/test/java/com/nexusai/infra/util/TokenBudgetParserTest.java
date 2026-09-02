package com.nexusai.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ER-IMP-2026-04 P-34] TokenBudgetParser 直接单测 · 对齐 CC utils/tokenBudget.ts:1-64
 * （真源逐行复核，非计划转述）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 三正则（SHORTHAND_START/END + VERBOSE）
 * 是 +500k 预算解析与位置提取的唯一入口（LlmAgentLoop:1748 doRun 预算源 + checkTokenBudget
 * 第三参）。正则漂移（错配/漏配/重叠）会直接改变预算门控行为。RED tooth：改正则或解析
 * 顺序（start→end→verbose）→ 本测试 fail。
 *
 * <p>用例映射 CC 真源：
 * <ul>
 *   <li>{@code parseTokenBudget} 顺序 start→end→verbose→null（utils/tokenBudget.ts:21-29）</li>
 *   <li>{@code findTokenBudgetPositions} 重叠去重（:31-64，'"+500k"' 单输入 start/end 双命中去重）</li>
 *   <li>parseBudgetMatch = parseFloat × multiplier（:17-19）</li>
 * </ul>
 */
class TokenBudgetParserTest {

    // ── parseTokenBudget：三正则全形态（CC utils/tokenBudget.ts:21-29）──

    @Test
    @DisplayName("shorthand start：'+500k' → 500_000（CC SHORTHAND_START_RE）")
    void parse_shorthandStart() {
        assertThat(TokenBudgetParser.parseTokenBudget("+500k")).isEqualTo(500_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("  +500k")).as("前导空白允许（^\\s*）").isEqualTo(500_000L);
    }

    @Test
    @DisplayName("shorthand end：句子尾部 '+2m' → 2_000_000（CC SHORTHAND_END_RE）")
    void parse_shorthandEnd() {
        assertThat(TokenBudgetParser.parseTokenBudget("please use +2m")).isEqualTo(2_000_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("keep it +1.5m.")).as("尾部标点允许（[.!?]?）").isEqualTo(1_500_000L);
    }

    @Test
    @DisplayName("verbose：'use 2M tokens' / 'spend 1b tokens' → 对应值（CC VERBOSE_RE，大小写不敏感）")
    void parse_verboseUseSpend() {
        assertThat(TokenBudgetParser.parseTokenBudget("use 2M tokens")).isEqualTo(2_000_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("spend 1b tokens")).isEqualTo(1_000_000_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("USE 500K TOKENS")).as("大小写不敏感（/i）").isEqualTo(500_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("use 5 token")).as("无后缀 → 不匹配").isNull();
    }
    @Test
    @DisplayName("解析顺序 start → end → verbose → null（CC :21-28；'+500k use 2m tokens' 命中 start 优先）")
    void parse_priorityOrder() {
        assertThat(TokenBudgetParser.parseTokenBudget("+500k use 2m tokens"))
            .as("shorthand start 优先于 verbose")
            .isEqualTo(500_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("please spend 1b tokens +2m"))
            .as("shorthand end（尾部）优先于 verbose")
            .isEqualTo(2_000_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("no budget here")).as("无匹配 → null").isNull();
        assertThat(TokenBudgetParser.parseTokenBudget(null)).as("null → null").isNull();
        assertThat(TokenBudgetParser.parseTokenBudget("")).as("空串 → null").isNull();
    }

    @Test
    @DisplayName("小数：'+0.5b' → 500_000_000（parseFloat × multiplier · CC :17-19）")
    void parse_decimal() {
        assertThat(TokenBudgetParser.parseTokenBudget("+0.5b")).isEqualTo(500_000_000L);
        assertThat(TokenBudgetParser.parseTokenBudget("use 1.25m tokens")).isEqualTo(1_250_000L);
    }

    // ── findTokenBudgetPositions（CC utils/tokenBudget.ts:31-64）──

    @Test
    @DisplayName("'+500k' 单独输入：start/end 双正则命中 → 去重为 1 个 position（CC :48-58 alreadyCovered）")
    void positions_singleShorthandDeduplicated() {
        List<int[]> positions = TokenBudgetParser.findTokenBudgetPositions("+500k");
        assertThat(positions).as("'+500k' 单独输入去重后仅 1 个 position").hasSize(1);
        assertThat(positions.get(0)[0]).as("start=0（无前导空白）").isZero();
        assertThat(positions.get(0)[1]).isEqualTo("+500k".length());
    }

    @Test
    @DisplayName("positions：前导空白 trim + start/end 双命中区间（CC :37-44 trimStart 偏移）")
    void positions_leadingWhitespaceTrimmed() {
        List<int[]> positions = TokenBudgetParser.findTokenBudgetPositions("  +500k");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0)[0]).as("前导空白被 trim（start=2）").isEqualTo(2);
        assertThat(positions.get(0)[1]).isEqualTo("  +500k".length());
    }

    @Test
    @DisplayName("positions：verbose 全局多命中（CC :60-62 matchAll）")
    void positions_multipleVerboseMatches() {
        List<int[]> positions = TokenBudgetParser.findTokenBudgetPositions("use 2m tokens and spend 1b tokens");
        assertThat(positions).hasSize(2);
        assertThat(positions.get(0)[1]).isLessThanOrEqualTo(positions.get(1)[0]);
    }

    @Test
    @DisplayName("positions：start + verbose 混合命中均返回（CC :35-62）")
    void positions_mixedStartAndVerbose() {
        List<int[]> positions = TokenBudgetParser.findTokenBudgetPositions("+500k then use 2m tokens");
        assertThat(positions).hasSize(2);
    }

    @Test
    @DisplayName("positions：无匹配 → 空列表（CC :34 初值）")
    void positions_noMatchEmpty() {
        assertThat(TokenBudgetParser.findTokenBudgetPositions("plain text")).isEmpty();
        assertThat(TokenBudgetParser.findTokenBudgetPositions(null)).isEmpty();
    }
}
