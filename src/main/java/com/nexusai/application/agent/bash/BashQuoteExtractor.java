package com.nexusai.application.agent.bash;

import java.util.regex.Pattern;

/**
 * Bash 引号内容提取工具 · 逐字移植 CC {@code tools/BashTool/bashSecurity.ts:128-231}。
 *
 * <p>三份不同的引号派生语义（供 {@link BashSecurityValidator} 各校验器使用）：
 * <ul>
 *   <li>{@link #extract} → {@link QuoteExtraction}（withDoubleQuotes / fullyUnquoted /
 *       unquotedKeepQuoteChars）——CC {@code extractQuotedContent}（:128-174）</li>
 *   <li>{@link #stripSafeRedirections} ——CC :176-188（三条尾界模式，含 /dev/null/2&gt;&amp;1/&lt;）</li>
 *   <li>{@link #hasUnescapedChar} ——CC :209-231（反斜杠转义感知单字符检测）</li>
 *   <li>{@link #isEscapedAtPosition} ——CC :1727-1735（连续反斜杠奇偶判定）</li>
 * </ul>
 *
 * <p>Java 无 tree-sitter，本类恒为 legacy 正则主门禁的引号提取层。
 */
public final class BashQuoteExtractor {

    private BashQuoteExtractor() {
    }

    /**
     * 引号内容提取结果 · CC {@code QuoteExtraction}（bashSecurity.ts:119-126）。
     *
     * @param withDoubleQuotes      剥单引号、保留双引号内容（CC withDoubleQuotes）
     * @param fullyUnquoted         剥单引号与双引号内容（CC fullyUnquoted）
     * @param unquotedKeepQuoteChars 剥引号内容但保留引号定界符（CC unquotedKeepQuoteChars，
     *                               供 validateMidWordHash 检测引号相邻 #）
     */
    public record QuoteExtraction(
            String withDoubleQuotes,
            String fullyUnquoted,
            String unquotedKeepQuoteChars) {
    }

    /**
     * 逐字移植 CC {@code extractQuotedContent}（bashSecurity.ts:128-174）。
     *
     * <p>语义：反斜杠在单引号外是转义（跳过下一字符）；单引号在双引号外 toggle；
     * 双引号在单引号外 toggle；{@code withDoubleQuotes} 保留双引号内容（剥单引号内容）、
     * {@code fullyUnquoted} 剥两种引号内容、{@code unquotedKeepQuoteChars} 剥内容但保留定界符。
     *
     * @param command 命令原文
     * @param isJq    是否 jq 命令（jq 保留双引号内容以正确分析 jq 表达式）
     */
    public static QuoteExtraction extract(String command, boolean isJq) {
        StringBuilder withDoubleQuotes = new StringBuilder();
        StringBuilder fullyUnquoted = new StringBuilder();
        StringBuilder unquotedKeepQuoteChars = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (escaped) {
                escaped = false;
                if (!inSingleQuote) withDoubleQuotes.append(ch);
                if (!inSingleQuote && !inDoubleQuote) fullyUnquoted.append(ch);
                if (!inSingleQuote && !inDoubleQuote) unquotedKeepQuoteChars.append(ch);
                continue;
            }

            if (ch == '\\' && !inSingleQuote) {
                escaped = true;
                if (!inSingleQuote) withDoubleQuotes.append(ch);
                if (!inSingleQuote && !inDoubleQuote) fullyUnquoted.append(ch);
                if (!inSingleQuote && !inDoubleQuote) unquotedKeepQuoteChars.append(ch);
                continue;
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                unquotedKeepQuoteChars.append(ch);
                continue;
            }

            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                unquotedKeepQuoteChars.append(ch);
                // jq 保留引号内容以确保 jq 表达式被正确分析
                if (!isJq) continue;
            }

            if (!inSingleQuote) withDoubleQuotes.append(ch);
            if (!inSingleQuote && !inDoubleQuote) fullyUnquoted.append(ch);
            if (!inSingleQuote && !inDoubleQuote) unquotedKeepQuoteChars.append(ch);
        }

        return new QuoteExtraction(
                withDoubleQuotes.toString(),
                fullyUnquoted.toString(),
                unquotedKeepQuoteChars.toString());
    }

    private static final Pattern REDIR_2AND1 = Pattern.compile("\\s+2\\s*>&\\s*1(?=\\s|$)");
    private static final Pattern REDIR_OUT_DEVNULL = Pattern.compile("[012]?\\s*>\\s*/dev/null(?=\\s|$)");
    private static final Pattern REDIR_IN_DEVNULL = Pattern.compile("\\s*<\\s*/dev/null(?=\\s|$)");

    /**
     * 逐字移植 CC {@code stripSafeRedirections}（bashSecurity.ts:176-188）。
     *
     * <p>三条尾界模式必须带 {@code (?=\s|$)} 边界，否则 {@code > /dev/nullo} 会误把
     * {@code /dev/null} 当前缀剥掉，使 validateRedirections 漏检 {@code >} 而放行
     * {@code /dev/nullo} 文件写入（speculation.ts 仅走 checkReadOnlyConstraints 时的攻击面）。
     */
    public static String stripSafeRedirections(String content) {
        String r = REDIR_2AND1.matcher(content).replaceAll("");
        r = REDIR_OUT_DEVNULL.matcher(r).replaceAll("");
        r = REDIR_IN_DEVNULL.matcher(r).replaceAll("");
        return r;
    }

    /**
     * 逐字移植 CC {@code hasUnescapedChar}（bashSecurity.ts:209-231）。
     *
     * <p>仅支持单字符。反斜杠跳过其后一字符（转义序列）。用于 validateDangerousPatterns
     * 区分已转义反引号（安全）与未转义反引号（命令替换）。
     */
    public static boolean hasUnescapedChar(String content, char target) {
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                i += 2;
                continue;
            }
            if (content.charAt(i) == target) {
                return true;
            }
            i++;
        }
        return false;
    }

    /**
     * 逐字移植 CC {@code isEscapedAtPosition}（bashSecurity.ts:1727-1735）。
     *
     * <p>统计 {@code pos} 前连续反斜杠个数，奇数为已转义（bash 中 {@code \{}/{\}} 是字面量）。
     * 供 validateBraceExpansion 判定未转义花括号。
     */
    public static boolean isEscapedAtPosition(String content, int pos) {
        int backslashCount = 0;
        int i = pos - 1;
        while (i >= 0 && content.charAt(i) == '\\') {
            backslashCount++;
            i--;
        }
        return backslashCount % 2 == 1;
    }
}
