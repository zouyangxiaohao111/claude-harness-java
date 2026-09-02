package com.nexusai.application.agent.bash;

import java.util.ArrayList;
import java.util.List;

/**
 * shell-quote 库行为差异防护工具 · 逐字移植 CC {@code utils/bash/shellQuote.ts:117-265}。
 *
 * <p>两处 shell-quote 与 bash 的解析差异导致权限绕过，须在主校验器之前/之中检测：
 * <ul>
 *   <li>{@link #hasShellQuoteSingleQuoteBug} ——shellQuote.ts:190-265（单引号内尾反斜杠奇偶 + 偶奇后随 '）</li>
 *   <li>{@link #hasMalformedTokens} ——shellQuote.ts:117-176（原始命令引号奇偶 + 每 token 花/圆/方括号与未转义引号平衡）</li>
 * </ul>
 *
 * <p>Java 无 shell-quote 库，tokenizer 用 {@link BashParser#tokenize} 产 Token 流适配
 * （OPERATOR token 的 text 即分隔符 op ∈ {;, &amp;&amp;, ||}）。
 */
public final class BashShellQuote {

    private BashShellQuote() {
    }

    /**
     * 逐字移植 CC {@code hasShellQuoteSingleQuoteBug}（shellQuote.ts:190-265）。
     *
     * <p>bash 单引号内反斜杠是字面量，shell-quote 却视作转义，导致
     * {@code '\' &lt;payload&gt; '\'} 把 payload 藏进"单一单引号串"而跳过安全检查。
     * 单引号闭合时统计尾随反斜杠：奇数为恒 bug；偶数仅在命令后续还存在 {@code '}
     * 时（chunker 正则可用其作假闭合）才 bug。
     */
    public static boolean hasShellQuoteSingleQuoteBug(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (ch == '\\' && !inSingleQuote) {
                i++;
                continue;
            }

            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;

                if (!inSingleQuote) {
                    int backslashCount = 0;
                    int j = i - 1;
                    while (j >= 0 && command.charAt(j) == '\\') {
                        backslashCount++;
                        j--;
                    }
                    if (backslashCount > 0 && backslashCount % 2 == 1) {
                        return true;
                    }
                    if (backslashCount > 0 && backslashCount % 2 == 0
                            && command.indexOf('\'', i + 1) != -1) {
                        return true;
                    }
                }
                continue;
            }
        }
        return false;
    }

    /**
     * 原文引号奇偶判定 · 等价 CC {@code hasMalformedTokens}（shellQuote.ts:121-143）开头的未配平引号检测。
     *
     * <p>shell-quote {@code parse()} 对未配平引号<b>不抛错</b>——它静默丢弃未配平的 {@code "}/{@code '}、
     * 把其余按非引号解析成功（shellQuote.ts:107-111；bashPipeCommand.ts:74-79 同源：未配平引号被
     * 解析成 tokens 且 {@code ;} 浮出为 operator），token 层无从察觉。故须在原文按 bash 引号语义
     * 统计奇偶：反斜杠在单引号外转义下一字符；单引号内无反斜杠转义。奇偶为奇（未配平）即歧义语法，
     * 由 {@link #hasMalformedTokens} 兜住 → ask(misparsing)。
     *
     * <p>仅被 {@link #hasMalformedTokens} 使用，降级为私有辅助（不对外暴露为 parse-failure 语义——
     * shell-quote 对未配平引号并不抛错）。
     */
    private static boolean hasUnterminatedQuote(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        int doubleCount = 0;
        int singleCount = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\' && !inSingle) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                doubleCount++;
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                singleCount++;
                inSingle = !inSingle;
            }
        }
        return doubleCount % 2 != 0 || singleCount % 2 != 0;
    }

    /**
     * 逐字移植 CC {@code hasMalformedTokens}（shellQuote.ts:117-176），tokenizer 适配。
     *
     * <p>1) 原始命令引号奇偶（bash 语义：反斜杠在单引号外转义下一字符；单引号内无反斜杠转义）——
     * shell-quote 会静默丢弃未配平引号，token 层无法察觉，须走原文奇偶。2) 每个 word 类
     * token（WORD/STRING/RAW_STRING）的花/圆/方括号与未转义引号奇偶平衡。任何不平衡 → 歧义语法。
     *
     * @param command 命令原文
     * @param tokens  tokenizer 产出的 token 流（等价 shell-quote ParseEntry[]）
     */
    public static boolean hasMalformedTokens(String command, List<BashParser.Token> tokens) {
        if (hasUnterminatedQuote(command)) {
            return true;
        }

        for (BashParser.Token token : tokens) {
            if (!isWordLike(token.kind())) {
                continue;
            }
            String text = token.text();
            if (countOf(text, '{') != countOf(text, '}')) return true;
            if (countOf(text, '(') != countOf(text, ')')) return true;
            if (countOf(text, '[') != countOf(text, ']')) return true;
            if (unescapedQuoteCount(text, '"') % 2 != 0) return true;
            if (unescapedQuoteCount(text, '\'') % 2 != 0) return true;
        }
        return false;
    }

    private static boolean isWordLike(BashParser.TokenKind kind) {
        return kind == BashParser.TokenKind.WORD
                || kind == BashParser.TokenKind.STRING
                || kind == BashParser.TokenKind.RAW_STRING
                || kind == BashParser.TokenKind.ANSI_C_STRING;
    }

    private static int countOf(String s, char target) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) count++;
        }
        return count;
    }

    /** 统计未转义引号个数（前有反斜杠则不计）——CC {@code (?!<\\)["']} 的逐字等价。 */
    private static int unescapedQuoteCount(String s, char quote) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == quote) {
                if (i > 0 && s.charAt(i - 1) == '\\') {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    /**
     * 解析命令为【剥引号后的】词序列 · 等价 CC {@code tryParseShellCommand}（shellQuote.ts:24-45）
     * 成功分支的 tokens。
     *
     * <p>shell-quote {@code parse(cmd, env)} 成功时 tokens 是【剥引号后】的词 + 操作符条目
     * （{@code 'git'} 解析后 tokens[0]==='git'，引号被剥离）；抛错时返回 {@code {success:false}}。
     * 本方法只保留 word-like token（WORD/STRING/RAW_STRING/ANSI_C_STRING），跳过
     * OPERATOR/NEWLINE/EOF/COMMENT/HEREDOC/VARIABLE/COMMAND_SUBST/ARITH_EXPANSION——
     * 操作符过滤不影响调用方（isNormalizedGitCommand / isNormalizedCdCommand）只比
     * {@code tokens[0]} 与 {@code contains('git')} 的首词判定。
     *
     * <p>每个 word token 经 {@link #unquoteWord} 剥引号（bash 语义：单引号字面量、双引号仅
     * {@code \\} {@code \"} {@code \$} {@code `} 转义、单引号外反斜杠转义下一字符）。
     *
     * <p>tokenize 异常 → 返回空列表（等价 CC tryParseShellCommand 抛错返回
     * {@code {success:false}}，调用方回退正则兜底）。
     *
     * @param command 命令字符串
     * @return 剥引号后的词序列（null/空命令/tokenize 失败时为空列表）
     */
    public static List<String> parseUnquotedTokens(String command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<BashParser.Token> tokens;
        try {
            tokens = BashParser.tokenize(command);
        } catch (Exception e) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (BashParser.Token token : tokens) {
            if (isWordLike(token.kind())) {
                words.add(unquoteWord(token.text()));
            }
        }
        return words;
    }

    /**
     * bash 语义逐词剥引号 · 对应 shell-quote parse 的逐词 unquote
     * （{@code 'git'} → git、{@code "git"} → git、{@code g'it'} → git、{@code g\i\t} → git）。
     *
     * <p>与 {@link BashQuoteExtractor#extract} 的 {@code fullyUnquoted} 语义相反：后者是
     * 【剥引号内容、只留引号外字符】，会把 {@code "git"} 剥成空串；本方法是【剥引号定界符、
     * 保留引号内容】（shell-quote parse 的 tokens 语义），二者不得混用。
     *
     * <p>bash 引号语义：单引号内全字面量（含反斜杠）；双引号内仅 {@code \\} {@code \"}
     * {@code \$} {@code `} 转义（其余反斜杠为字面量）；单引号外反斜杠转义下一字符。
     */
    private static String unquoteWord(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        StringBuilder sb = new StringBuilder(word.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    sb.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < word.length()) {
                    char next = word.charAt(i + 1);
                    if (next == '\\' || next == '"' || next == '$' || next == '`') {
                        sb.append(next);
                        i++;
                    } else {
                        sb.append(c); // 双引号内其余反斜杠为字面量（bash 不转义 \t 等）
                    }
                } else {
                    sb.append(c);
                }
                continue;
            }
            // 引号外
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\\' && i + 1 < word.length()) {
                sb.append(word.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
