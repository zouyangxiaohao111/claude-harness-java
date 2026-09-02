package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TokenBudgetParser · 对齐 CC utils/tokenBudget.ts.
 *
 * <p>L1 语义: 解析 prompt token budget 表达 — shorthand (+500k) + verbose (use 2M tokens)。
 * 静态方法: parseTokenBudget(text)→Long|null;findTokenBudgetPositions(text)→List<{start,end}>;getBudgetContinuationMessage。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 静态方法 + 2 Pattern (shorthand start + verbose) + multipliers (k/m/b)</li>
 *   <li><b>A2 Golden Trace</b>: "+500k"→500_000;"use 2M tokens"→2_000_000;"spend 1b tokens"→1_000_000_000;无 match→null</li>
 *   <li><b>A3 纯函数</b>: stateless;Pattern compile 一次 (singleton)</li>
 *   <li><b>A4 边界</b>: null/empty text→null;invalid suffix→null;不匹配→null;positions 返 []</li>
 *   <li><b>A5 业务场景</b>: 用户 prompt "+500k" → 抽 500K token budget → 不摘要 (CC 注释: keep working do not summarize)</li>
 * </ul>
 *
 * <p>L3 升级: TS regex literal → Java Pattern.compile;
 * TS VERBOSE_RE_G global flag → Java Pattern.matcher.find (multiple matches);
 * TS parseFloat → Java Long.parseLong (避免浮点).
 */
public final class TokenBudgetParser {

    // Shorthand anchored to start (^\s*\+...) — capture group 1 = number, 2 = suffix (k|m|b)
    private static final Pattern SHORTHAND_START = Pattern.compile(
        "^\\s*\\+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\b", Pattern.CASE_INSENSITIVE);
    // Shorthand anchored to end (\s\+...) — leading \s captured for offset
    private static final Pattern SHORTHAND_END = Pattern.compile(
        "\\s\\+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\s*[.!?]?\\s*$", Pattern.CASE_INSENSITIVE);
    // Verbose anywhere — \buse\s+NUM\s+SUFFIX\s+tokens?\b
    private static final Pattern VERBOSE = Pattern.compile(
        "\\b(?:use|spend)\\s+(\\d+(?:\\.\\d+)?)\\s*(k|m|b)\\s*tokens?\\b",
        Pattern.CASE_INSENSITIVE);

    private TokenBudgetParser() {}

    /**
     * Parse a single token budget value from the text.
     * Returns null if no match.
     */
    public static Long parseTokenBudget(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher start = SHORTHAND_START.matcher(text);
        if (start.find()) return parseMatch(start.group(1), start.group(2));
        Matcher end = SHORTHAND_END.matcher(text);
        if (end.find()) return parseMatch(end.group(1), end.group(2));
        Matcher verbose = VERBOSE.matcher(text);
        if (verbose.find()) return parseMatch(verbose.group(1), verbose.group(2));
        return null;
    }

    /**
     * Find all token budget positions in the text. Returns empty list if none.
     */
    public static List<int[]> findTokenBudgetPositions(String text) {
        List<int[]> positions = new ArrayList<>();
        if (text == null || text.isEmpty()) return positions;
        Matcher start = SHORTHAND_START.matcher(text);
        if (start.find()) {
            int trimOffset = start.group().length() - start.group().replaceAll("^\\s*", "").length();
            positions.add(new int[]{
                start.start() + trimOffset,
                start.end()
            });
        }
        Matcher end = SHORTHAND_END.matcher(text);
        if (end.find()) {
            int endStart = end.start() + 1; // +1 to skip leading \s
            boolean alreadyCovered = positions.stream()
                .anyMatch(p -> endStart >= p[0] && endStart < p[1]);
            if (!alreadyCovered) {
                positions.add(new int[]{
                    endStart,
                    end.end()
                });
            }
        }
        // Verbose matches (CC uses global flag; we iterate manually)
        Matcher verbose = VERBOSE.matcher(text);
        while (verbose.find()) {
            positions.add(new int[]{verbose.start(), verbose.end()});
        }
        return positions;
    }

    /**
     * Format the budget continuation message · 对齐 CC utils/tokenBudget.ts:66-73
     * {@code getBudgetContinuationMessage(pct, turnTokens, budget)}。
     *
     * <p>CC 原文: {@code `Stopped at ${pct}% of token target (${fmt(turnTokens)} / ${fmt(budget)}). Keep working — do not summarize.`}
     * 其中 {@code fmt = (n) => new Intl.NumberFormat('en-US').format(n)} → Java 用
     * {@code String.format("%,d", ...)} 千分位（en-US 等价），破折号 U+2014 em-dash。
     */
    public static String getBudgetContinuationMessage(long pct, long turnTokens, long budget) {
        // CC utils/tokenBudget.ts:71 fmt = Intl.NumberFormat('en-US').format → %,d 千分位
        return "Stopped at " + pct + "% of token target ("
            + String.format("%,d", turnTokens) + " / "
            + String.format("%,d", budget) + "). Keep working — do not summarize.";
    }

    private static long parseMatch(String value, String suffix) {
        if (value == null || suffix == null) return 0L;
        double num;
        try {
            num = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
        return switch (suffix.toLowerCase()) {
            case "k" -> (long) (num * 1_000);
            case "m" -> (long) (num * 1_000_000);
            case "b" -> (long) (num * 1_000_000_000);
            default -> 0L;
        };
    }
}
