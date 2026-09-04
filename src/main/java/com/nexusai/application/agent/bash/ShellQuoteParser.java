package com.nexusai.application.agent.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * shell-quote 库等价 + CC {@code bashPipeCommand.ts rearrangePipeCommand} 移植。
 *
 * <p>CC 对「含管道且需 stdin 重定向」的命令调用 {@code rearrangePipeCommand}
 * （Open-ClaudeCode/src/utils/bash/bashPipeCommand.ts），把 {@code < /dev/null} 插到第一个
 * 管道命令之后（{@code cmd1 < /dev/null | cmd2}），修复 {@code eval 'cmd1 | cmd2' < /dev/null}
 * 时 stdin 重定向落到管道末命令、而第一命令（如 {@code cat}）继承父 stdin 挂起的 bug。
 * 无法安全 parse（{@code $()/反引号/控制结构/单引号 bug/malformed} 等）时 fallback
 * {@code quoteWithEvalStdinRedirect}（{@code 'cmd' < /dev/null}）。
 *
 * <p><b>CC 真源</b>（grep/curl 自验，不信注释）：
 * <ul>
 *   <li>{@code rearrangePipeCommand}（bashPipeCommand.ts:11-78）：回退判断（:13-45）→
 *       {@code joinContinuationLines}（:242-256）→ {@code tryParseShellCommand}（shell-quote parse，
 *       shellQuote.ts:24-45）→ {@code hasMalformedTokens}（shellQuote.ts:117-176）→
 *       {@code findFirstPipeOperator}（:85-96）→ {@code buildCommandParts}（:104-197）→
 *       {@code singleQuoteForEval}（:224-229）；</li>
 *   <li>shell-quote parse 核心为正则 chunker（parse.js）：{@code CONTROL} 操作符
 *       {@code || && ;; |& <( <<< >> >& <& [&;()|<>]} + {@code BAREWORD|DOUBLE_QUOTE|SINGLE_QUOTE}
 *       词段，手写扫描器处理引号/转义；</li>
 * </ul>
 *
 * <p>Java 简化边界：CC 回退判断已排除含 {@code $} / {@code $()} / 反引号 / 控制结构的命令，
 * 故 parse 无需变量展开/命令替换；glob 在 shell-quote 中为普通词（{@code *} 非 META），
 * quote 后原样保留（与 CC 的 glob 特判结果一致）。
 */
public final class ShellQuoteParser {

    /** shell-quote CONTROL 操作符（parse.js CONTROL，逐字移植）。 */
    private static final Pattern CONTROL =
        Pattern.compile("\\|\\||&&|;;|\\|&|<\\(|<<<|>>|>&|<&|[&;()|<>]");
    /** BAREWORD：反斜杠转义 META/引号 或 非空白/引号/META 字符（parse.js BAREWORD）。 */
    private static final Pattern BAREWORD =
        Pattern.compile("(?:\\\\['\"|&;()<> \\t]|[^\\s'\"|&;()<> \\t])+");
    /** 双引号段（parse.js DOUBLE_QUOTE）。 */
    private static final Pattern DOUBLE_QUOTE = Pattern.compile("\"(?:\\\\\"|[^\"])*?\"");
    /** 单引号段（parse.js SINGLE_QUOTE）。 */
    private static final Pattern SINGLE_QUOTE = Pattern.compile("'[^']*?'");
    /** 词段组合：(BAREWORD|DQ|SQ)+。 */
    private static final Pattern WORD_OR_QUOTE =
        Pattern.compile("(?:(?:" + BAREWORD.pattern() + "|" + DOUBLE_QUOTE.pattern() + "|" + SINGLE_QUOTE.pattern() + ")+)");
    /** chunker：(CONTROL)|(词段)（parse.js chunker 正则）。 */
    private static final Pattern CHUNKER =
        Pattern.compile("(" + CONTROL.pattern() + ")|(" + WORD_OR_QUOTE.pattern() + ")");
    /** bash 控制结构（bashPipeCommand.ts:198-203 containsControlStructure）。 */
    private static final Pattern CONTROL_STRUCTURE = Pattern.compile("\\b(for|while|until|if|case|select)\\s");
    /** 行连续（bashPipeCommand.ts:242-256 joinContinuationLines）。 */
    private static final Pattern CONTINUATION = Pattern.compile("\\\\+\\n");
    /** 变量引用（bashPipeCommand.ts:24-27，命中 → fallback）。 */
    private static final Pattern VAR_REF = Pattern.compile("\\$[A-Za-z_{]");

    /** 操作符 token。 */
    public record Op(String op) {
    }

    private ShellQuoteParser() {
    }

    /**
     * 单引号字面量 + {@code '"'"'} escape（eval 参数用）· 对齐 CC bashPipeCommand.ts singleQuoteForEval（:224-229）。
     */
    public static String singleQuoteForEval(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /**
     * shell-quote quote 等价 · 对齐 npm shell-quote（shellQuote.ts:267 委托）+ CC quote 语义。
     * 数组项以空格 join；含 {@code "} / 空白 / shell 元字符（{@code |&;()<>} 等，见
     * {@link #containsShellMeta}）且不含 {@code '} → 单引号；含 {@code "} 或 {@code '} 或空白
     * 或 shell 元字符 → 双引号；否则原样。
     */
    public static String quote(List<String> args) {
        return args.stream().map(ShellQuoteParser::quoteOne).collect(Collectors.joining(" "));
    }

    private static String quoteOne(String arg) {
        // [元字符保护 2026-09-04 方案B] 除引号/空白外，词内含 eval 后会被 bash 当语法操作符的
        //   字符（| & ; ( ) < > 换行）也必须引号保护。WHY：rearrangePipeCommand 经 shell-quote
        //   parse 把双引号模式串（grep -oE "a|b|c"）剥离引号成单个词，重建时若无此保护竖线裸返回
        //   → eval 'grep -oE a|b|c' 被拆成管道 → 裸 grep 读 stdin 永久挂起（Java 服务 stdin 非 EOF）。
        //   只保护语法控制符，不含 glob（* ? [ ]）——glob 需保持原样交给 shell 展开（CC buildCommandParts
        //   glob 特判不引号 语义一致，ShellQuoteParserTest/ShellExecutorTest 回归覆盖）。
        boolean needProtect = arg.contains("\"") || arg.contains("'") || containsWhitespace(arg)
            || containsShellMeta(arg);
        if (needProtect && !arg.contains("'")) {
            return "'" + arg.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (needProtect) {
            return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`").replace("!", "\\!") + "\"";
        }
        return arg;
    }

    /** 词内含 bash 语法操作符字符（eval 后改变解析结构）→ 必须引号保护。 */
    private static boolean containsShellMeta(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '|' || c == '&' || c == ';' || c == '(' || c == ')'
                    || c == '<' || c == '>' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 管道命令 stdin 重定向重组 · 对齐 CC {@code rearrangePipeCommand}（bashPipeCommand.ts:11-78）。
     * 返回重建命令串：{@code 'cmd1 < /dev/null | cmd2 ...'}（singleQuoteForEval 整体包裹）；
     * 无法安全 parse → fallback {@code 'cmd' < /dev/null}（quoteWithEvalStdinRedirect :211-215）。
     *
     * @param command 已 {@code rewriteWindowsNullRedirect} 后的命令（含管道）
     * @return 重组后的 quoted 命令串（供 {@code eval <...>}）
     */
    public static String rearrangePipeCommand(String command) {
        // 回退判断 · 对齐 bashPipeCommand.ts:13-38
        if (command.contains("`")) {
            return fallback(command);
        }
        if (command.contains("$(")) {
            return fallback(command);
        }
        if (VAR_REF.matcher(command).find()) {
            return fallback(command);
        }
        if (CONTROL_STRUCTURE.matcher(command).find()) {
            return fallback(command);
        }
        // 行连续 join · 对齐 :41-44
        String joined = joinContinuationLines(command);
        if (joined.contains("\n")) {
            return fallback(command);
        }
        // 单引号 bug · 对齐 :46-50（BashShellQuote 已移植 shellQuote.ts:190-265）
        if (BashShellQuote.hasShellQuoteSingleQuoteBug(joined)) {
            return fallback(command);
        }
        // shell-quote parse · 对齐 :52-57 tryParseShellCommand
        List<Object> parsed;
        try {
            parsed = parseShell(joined);
        } catch (RuntimeException e) {
            return fallback(command);
        }
        // malformed tokens · 对齐 :59-70 hasMalformedTokens（shellQuote.ts:117-176）
        if (hasMalformedTokens(joined, parsed)) {
            return fallback(command);
        }
        // 找第一个管道操作符 · 对齐 :72-76 findFirstPipeOperator
        int firstPipe = findFirstPipeOperator(parsed);
        if (firstPipe <= 0) {
            return fallback(command);
        }
        // 重建：first_command < /dev/null | rest · 对齐 :78-84
        List<String> parts = new ArrayList<>();
        parts.addAll(buildCommandParts(parsed, 0, firstPipe));
        parts.add("< /dev/null");
        parts.addAll(buildCommandParts(parsed, firstPipe, parsed.size()));
        return singleQuoteForEval(String.join(" ", parts));
    }

    /** CC quoteWithEvalStdinRedirect（bashPipeCommand.ts:211-215）。 */
    private static String fallback(String command) {
        return singleQuoteForEval(command) + " < /dev/null";
    }

    /** shell-quote parse 等价（正则 chunker + 引号扫描）· parse.js。 */
    static List<Object> parseShell(String s) {
        List<Object> tokens = new ArrayList<>();
        Matcher m = CHUNKER.matcher(s);
        while (m.find()) {
            if (m.group(1) != null) {
                tokens.add(new Op(m.group(1)));
            } else if (m.group(2) != null) {
                tokens.add(scanWord(m.group(2)));
            }
        }
        return tokens;
    }

    /**
     * 引号/转义扫描 · 对齐 shell-quote parse.js 手写扫描器：
     * 引号内先判闭合（退出引号）；双引号内仅 {@code \"} 与 {@code \\} 转义，单引号内全字面量；
     * 引号外 {@code \} 转义下一字符；引号可 mid-token 切换（无空白）。
     */
    private static String scanWord(String s) {
        StringBuilder out = new StringBuilder();
        char quote = 0; // 0 = 无引号, '\'' , '"'
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == '\'' || quote == '"') {
                // 引号内：先看闭合引号
                if (c == quote) {
                    quote = 0;
                    continue;
                }
                // 双引号内仅转义 " 和 \；单引号内全字面量
                if (quote == '"' && c == '\\') {
                    if (i + 1 < s.length() && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
                        out.append(s.charAt(i + 1));
                        i++;
                    } else {
                        out.append(c);
                    }
                    continue;
                }
                out.append(c);
                continue;
            }
            // 引号外
            if (c == '\\') {
                if (i + 1 < s.length()) {
                    out.append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                quote = '\'';
                continue;
            }
            if (c == '"') {
                quote = '"';
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 引号平衡 + token 内括号/引号平衡检测 · 对齐 shellQuote.ts:117-176 hasMalformedTokens。 */
    static boolean hasMalformedTokens(String command, List<Object> parsed) {
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
        if (doubleCount % 2 != 0 || singleCount % 2 != 0) {
            return true;
        }
        for (Object entry : parsed) {
            if (!(entry instanceof String str)) {
                continue;
            }
            if (countChar(str, '{') != countChar(str, '}')) return true;
            if (countChar(str, '(') != countChar(str, ')')) return true;
            if (countChar(str, '[') != countChar(str, ']')) return true;
            if (countUnescaped(str, '"') % 2 != 0) return true;
            if (countUnescaped(str, '\'') % 2 != 0) return true;
        }
        return false;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 非反斜杠转义字符计数 · 等价 CC {@code (?<!\\)c}（shellQuote.ts:147-154）。 */
    private static int countUnescaped(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                boolean escaped = i > 0 && s.charAt(i - 1) == '\\';
                // lookbehind (?<!\\) 语义：前一字符非 \；连续反斜杠时奇数个前导 \ 才算转义
                if (escaped) {
                    int bs = 0;
                    int j = i - 1;
                    while (j >= 0 && s.charAt(j) == '\\') {
                        bs++;
                        j--;
                    }
                    escaped = bs % 2 == 1;
                }
                if (!escaped) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 找第一个 {@code |} 操作符索引 · 对齐 bashPipeCommand.ts:85-96。 */
    private static int findFirstPipeOperator(List<Object> parsed) {
        for (int i = 0; i < parsed.size(); i++) {
            if (parsed.get(i) instanceof Op op && op.op.equals("|")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 重建命令部分 · 对齐 bashPipeCommand.ts:104-197 buildCommandParts：
     * fd 重定向（2>&1 / 2>/dev/null / 2> &1）合并为单单元；env 赋值（VAR=value）只 quote 值；
     * 普通词 quote 恢复；操作符原样 + 命令分隔符重置 env 上下文。
     */
    private static List<String> buildCommandParts(List<Object> parsed, int start, int end) {
        List<String> parts = new ArrayList<>();
        boolean seenNonEnvVar = false;
        for (int i = start; i < end; i++) {
            Object entry = parsed.get(i);
            // fd 重定向
            if (entry instanceof String str && str.length() == 1 && "012".indexOf(str.charAt(0)) >= 0
                    && i + 2 < end && parsed.get(i + 1) instanceof Op op) {
                Object target = parsed.get(i + 2);
                if (op.op.equals(">&") && target instanceof String t
                        && t.length() == 1 && "012".indexOf(t.charAt(0)) >= 0) {
                    parts.add(str + ">&" + t);
                    i += 2;
                    continue;
                }
                if (op.op.equals(">") && "/dev/null".equals(target)) {
                    parts.add(str + ">/dev/null");
                    i += 2;
                    continue;
                }
                if (op.op.equals(">") && target instanceof String t && t.startsWith("&")) {
                    String fd = t.substring(1);
                    if (fd.length() == 1 && "012".indexOf(fd.charAt(0)) >= 0) {
                        parts.add(str + ">&" + fd);
                        i += 2;
                        continue;
                    }
                }
            }
            if (entry instanceof String str) {
                boolean isEnvVar = !seenNonEnvVar && isEnvironmentVariableAssignment(str);
                if (isEnvVar) {
                    int eq = str.indexOf('=');
                    String name = str.substring(0, eq);
                    String value = str.substring(eq + 1);
                    parts.add(name + "=" + quoteOne(value));
                } else {
                    seenNonEnvVar = true;
                    parts.add(quoteOne(str));
                }
            } else if (entry instanceof Op op) {
                parts.add(op.op);
                if (isCommandSeparator(op.op)) {
                    seenNonEnvVar = false;
                }
            }
        }
        return parts;
    }

    /** env 赋值检测 · 对齐 bashPipeCommand.ts:169-172。 */
    private static boolean isEnvironmentVariableAssignment(String str) {
        return Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=").matcher(str).find();
    }

    /** 命令分隔符 · 对齐 bashPipeCommand.ts:175-179。 */
    private static boolean isCommandSeparator(String op) {
        return op.equals("&&") || op.equals("||") || op.equals(";");
    }

    /** 行连续 join · 对齐 bashPipeCommand.ts:242-256（奇数反斜杠 → 续行）。 */
    private static String joinContinuationLines(String command) {
        Matcher m = CONTINUATION.matcher(command);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String match = m.group();
            int bs = match.length() - 1; // -1 换行
            if (bs % 2 == 1) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\\".repeat(bs - 1)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
