package com.nexusai.application.agent.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bash 安全校验器主链 · 逐字移植 CC {@code tools/BashTool/bashSecurity.ts:2257-2413}
 * {@code bashCommandIsSafe_DEPRECATED}（legacy regex/shell-quote 门禁）。
 *
 * <p>Java 无 tree-sitter，legacy 校验器链恒为主门禁（非缺口——所有命令都走 23 项链）。
 * 23 项校验器 = 4 早期（validateEmpty / validateIncompleteCommands /
 * validateSafeCommandSubstitution / validateGitCommit）+ 19 主校验器。主链顺序：
 * CONTROL_CHAR → shell-quote 单引号 bug → extractHeredocs(quotedOnly) →
 * extractQuotedContent → 4 早期（allow→passthrough）→ 19 主校验器（non-misparsing 延后：
 * validateNewlines / validateRedirections 的 ask 不设 misparsing flag，延至末尾才返回）。
 *
 * <p><b>BASH_SECURITY_CHECK_IDS 23 项清单</b>（CC original: bashSecurity.ts:77-101，
 * 逐项 1:1 映射；G3-3 核验通过）：
 * <ol>
 *   <li>1  INCOMPLETE_COMMANDS</li>
 *   <li>2  JQ_SYSTEM_FUNCTION</li>
 *   <li>3  JQ_FILE_ARGUMENTS</li>
 *   <li>4  OBFUSCATED_FLAGS</li>
 *   <li>5  SHELL_METACHARACTERS</li>
 *   <li>6  DANGEROUS_VARIABLES</li>
 *   <li>7  NEWLINES</li>
 *   <li>8  DANGEROUS_PATTERNS_COMMAND_SUBSTITUTION</li>
 *   <li>9  DANGEROUS_PATTERNS_INPUT_REDIRECTION</li>
 *   <li>10 DANGEROUS_PATTERNS_OUTPUT_REDIRECTION</li>
 *   <li>11 IFS_INJECTION</li>
 *   <li>12 GIT_COMMIT_SUBSTITUTION</li>
 *   <li>13 PROC_ENVIRON_ACCESS</li>
 *   <li>14 MALFORMED_TOKEN_INJECTION</li>
 *   <li>15 BACKSLASH_ESCAPED_WHITESPACE</li>
 *   <li>16 BRACE_EXPANSION</li>
 *   <li>17 CONTROL_CHARACTERS</li>
 *   <li>18 UNICODE_WHITESPACE</li>
 *   <li>19 MID_WORD_HASH</li>
 *   <li>20 ZSH_DANGEROUS_COMMANDS</li>
 *   <li>21 BACKSLASH_ESCAPED_OPERATORS</li>
 *   <li>22 COMMENT_QUOTE_DESYNC</li>
 *   <li>23 QUOTED_NEWLINE</li>
 * </ol>
 *
 * <p>仅 misparsing ask 阻断（isBashSecurityCheckForMisparsing=true），对应 CC
 * bashPermissions.ts:2085-2142 legacy misparsing gate 消费点。
 */
public final class BashSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(BashSecurityValidator.class);

    private BashSecurityValidator() {
    }

    /** 校验结果三态 · 对齐 CC PermissionResult behavior（allow/passthrough/ask）。 */
    public enum Behavior { ALLOW, PASSTHROUGH, ASK }

    /**
     * 校验结果 · CC {@code bashCommandIsSafe_DEPRECATED} 返回值简化（allow 由主链转 passthrough）。
     *
     * @param behavior   allow/passthrough/ask
     * @param misparsing 是否 bash 安全检查误解析（isBashSecurityCheckForMisparsing）
     * @param message    结果消息（对齐 CC message 原文）
     * @param checkId    BASH_SECURITY_CHECK_IDS 编号（无则 0）
     * @param subId      子编号（无则 0）
     */
    public record Result(Behavior behavior, boolean misparsing, String message, int checkId, int subId) {
        public boolean ask() {
            return behavior == Behavior.ASK;
        }
        public boolean passthrough() {
            return behavior == Behavior.PASSTHROUGH;
        }
        static Result passthrough(String m) {
            return new Result(Behavior.PASSTHROUGH, false, m, 0, 0);
        }
        static Result allow(String m) {
            return new Result(Behavior.ALLOW, false, m, 0, 0);
        }
        static Result askMisparsing(String m, int id, int sub) {
            return new Result(Behavior.ASK, true, m, id, sub);
        }
        static Result askNonMisparsing(String m, int id, int sub) {
            return new Result(Behavior.ASK, false, m, id, sub);
        }
    }

    // ── BASH_SECURITY_CHECK_IDS（CC bashSecurity.ts:77-101）──────────────────
    public static final int INCOMPLETE_COMMANDS = 1;
    public static final int JQ_SYSTEM_FUNCTION = 2;
    public static final int JQ_FILE_ARGUMENTS = 3;
    public static final int OBFUSCATED_FLAGS = 4;
    public static final int SHELL_METACHARACTERS = 5;
    public static final int DANGEROUS_VARIABLES = 6;
    public static final int NEWLINES = 7;
    public static final int DANGEROUS_PATTERNS_COMMAND_SUBSTITUTION = 8;
    public static final int DANGEROUS_PATTERNS_INPUT_REDIRECTION = 9;
    public static final int DANGEROUS_PATTERNS_OUTPUT_REDIRECTION = 10;
    public static final int IFS_INJECTION = 11;
    public static final int GIT_COMMIT_SUBSTITUTION = 12;
    public static final int PROC_ENVIRON_ACCESS = 13;
    public static final int MALFORMED_TOKEN_INJECTION = 14;
    public static final int BACKSLASH_ESCAPED_WHITESPACE = 15;
    public static final int BRACE_EXPANSION = 16;
    public static final int CONTROL_CHARACTERS = 17;
    public static final int UNICODE_WHITESPACE = 18;
    public static final int MID_WORD_HASH = 19;
    public static final int ZSH_DANGEROUS_COMMANDS = 20;
    public static final int BACKSLASH_ESCAPED_OPERATORS = 21;
    public static final int COMMENT_QUOTE_DESYNC = 22;
    public static final int QUOTED_NEWLINE = 23;

    // ── ValidationContext（CC bashSecurity.ts:103-126）──────────────────────
    private record Context(
            String originalCommand,
            String baseCommand,
            String unquotedContent,
            String fullyUnquotedContent,
            String fullyUnquotedPreStrip,
            String unquotedKeepQuoteChars) {
    }

    // ── 静态正则（CC 顶部常量）──────────────────────────────────────────────
    private static final Pattern CONTROL_CHAR_RE =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /**
     * JS {@code \s} 的 Unicode 空白段（不含方括号）· 委托 {@link BashWhitespace#UNICODE_WS_CHARS}
     * （单一事实源，G3-2；CC original: bashSecurity.ts:1899-1901 UNICODE_WS_RE）。
     */
    private static final String UNICODE_WS_CHARS = BashWhitespace.UNICODE_WS_CHARS;
    private static final Pattern UNICODE_WS_RE = Pattern.compile("[" + BashWhitespace.UNICODE_WS_CHARS + "]");

    /**
     * JS {@code \s} 精确字符集（ASCII 空白 + Unicode 空白）· 委托 {@link BashWhitespace#WS_CLASS_CHARS}
     * （单一事实源，G3-2；CC original: bashSecurity.ts 各 {@code /\s/} 字面量值域）。
     *
     * <p>GAP-4（RETAIN-gap 闭环）：原内联正则用 Java {@code \s}/{@code \S}（仅 ASCII）或
     * {@link Pattern#UNICODE_CHARACTER_CLASS}（值域 ≈ {@code \p{javaWhitespace}}，多含 U+001C–U+001F、
     * 缺含 NBSP(U+00A0)/图空格(U+2007)/窄不换行空格(U+202F)/U+FEFF，与 JS {@code \s} 双向不等价），
     * 导致正则路径 checkId 归属漂移（本应命中 OBFUSCATED_FLAGS/SHELL_METACHARACTERS 却落到 M15
     * UNICODE_WHITESPACE 兜底）。统一改为手写精确字符类，与 {@link BashWhitespace#isBashWhitespace(char)}
     * 同源同值域。
     */
    private static final String WS_CLASS_CHARS = BashWhitespace.WS_CLASS_CHARS;
    /** JS {@code \s} 精确字符类（含方括号）· 委托 {@link BashWhitespace#WS_CLASS}。 */
    private static final String WS_CLASS = BashWhitespace.WS_CLASS;
    /** JS {@code \S} 精确补集字符类（含方括号）· 委托 {@link BashWhitespace#NOT_WS_CLASS}。 */
    private static final String NOT_WS_CLASS = BashWhitespace.NOT_WS_CLASS;

    private record SubPattern(Pattern pattern, String message) {}

    // CC bashSecurity.ts:16-41 COMMAND_SUBSTITUTION_PATTERNS
    private static final List<SubPattern> COMMAND_SUBSTITUTION_PATTERNS = List.of(
            new SubPattern(Pattern.compile("<\\("), "process substitution <()"),
            new SubPattern(Pattern.compile(">\\("), "process substitution >()"),
            new SubPattern(Pattern.compile("=\\("), "Zsh process substitution =()"),
            new SubPattern(Pattern.compile("(?:^|[" + WS_CLASS_CHARS + ";&|])=[a-zA-Z_]"), "Zsh equals expansion (=cmd)"),
            new SubPattern(Pattern.compile("\\$\\("), "$() command substitution"),
            new SubPattern(Pattern.compile("\\$\\{"), "${} parameter substitution"),
            new SubPattern(Pattern.compile("\\$\\["), "$[] legacy arithmetic expansion"),
            new SubPattern(Pattern.compile("~\\["), "Zsh-style parameter expansion"),
            new SubPattern(Pattern.compile("\\(e:"), "Zsh-style glob qualifiers"),
            new SubPattern(Pattern.compile("\\(\\+"), "Zsh glob qualifier with command execution"),
            new SubPattern(Pattern.compile("\\}" + WS_CLASS + "*always" + WS_CLASS + "*\\{"), "Zsh always block (try/always construct)"),
            new SubPattern(Pattern.compile("<#"), "PowerShell comment syntax"));

    // CC bashSecurity.ts:45-74 ZSH_DANGEROUS_COMMANDS
    private static final Set<String> ZSH_DANGEROUS_COMMANDS_SET = Set.of(
            "zmodload", "emulate", "sysopen", "sysread", "syswrite", "sysseek",
            "zpty", "ztcp", "zsocket", "mapfile", "zf_rm", "zf_mv", "zf_ln",
            "zf_chmod", "zf_chown", "zf_mkdir", "zf_rmdir", "zf_chgrp");

    private static final Set<String> ZSH_PRECOMMAND_MODIFIERS =
            Set.of("command", "builtin", "noglob", "nocorrect");

    // CC bashSecurity.ts:1629 SHELL_OPERATORS
    private static final Set<Character> SHELL_OPERATORS = Set.of(';', '|', '&', '<', '>');

    // ── 校验器函数接口 + 早期/主链编排 ──────────────────────────────────────
    @FunctionalInterface
    private interface Validator {
        Result validate(Context ctx);
    }

    private record ValidatorEntry(Validator validator, boolean nonMisparsing) {}

    // ── 日志（中文 slf4j，isDebug 包裹）─────────────────────────────────────
    private static void logAsk(int checkId, int subId, String command) {
        if (log.isDebugEnabled()) {
            log.debug("BashSecurity 校验器命中 checkId={} subId={} command={}",
                    checkId, subId, abbreviate(command, 120));
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ── 主链（CC bashSecurity.ts:2257-2413）─────────────────────────────────
    public static Result check(String command) {
        if (command == null) {
            command = "";
        }

        // SECURITY: 控制字符先于一切处理阻断（CC :2260-2273）
        if (CONTROL_CHAR_RE.matcher(command).find()) {
            logAsk(CONTROL_CHARACTERS, 0, command);
            return Result.askMisparsing(
                    "Command contains non-printable control characters that could be used to bypass security checks",
                    CONTROL_CHARACTERS, 0);
        }

        // SECURITY: shell-quote 单引号反斜杠 bug，先于 shell-quote 解析（CC :2275-2284）
        if (BashShellQuote.hasShellQuoteSingleQuoteBug(command)) {
            logAsk(0, 0, command);
            return Result.askMisparsing(
                    "Command contains single-quoted backslash pattern that could bypass security checks",
                    0, 0);
        }

        // 剥 quoted heredoc body（CC :2286-2293）
        String processed = BashHeredocExtractor.stripQuotedHeredocBodies(command);

        String baseCommand = baseCommandOf(command);
        BashQuoteExtractor.QuoteExtraction qe =
                BashQuoteExtractor.extract(processed, "jq".equals(baseCommand));

        Context ctx = new Context(
                command,
                baseCommand,
                qe.withDoubleQuotes(),
                BashQuoteExtractor.stripSafeRedirections(qe.fullyUnquoted()),
                qe.fullyUnquoted(),
                qe.unquotedKeepQuoteChars());

        // 早期校验器（CC :2308-2332）：allow → passthrough；ask → misparsing
        for (Validator v : EARLY_VALIDATORS) {
            Result r = v.validate(ctx);
            if (r.behavior == Behavior.ALLOW) {
                return Result.passthrough(r.message);
            }
            if (r.behavior == Behavior.ASK) {
                logAsk(r.checkId, r.subId, command);
                return Result.askMisparsing(r.message, r.checkId, r.subId);
            }
        }

        // 主校验器（CC :2348-2407）：先收集 misparsing ask 立即返回，末尾才返回延后非 misparsing ask
        Result deferred = null;
        for (ValidatorEntry e : MAIN_VALIDATORS) {
            Result r = e.validator.validate(ctx);
            if (r.behavior == Behavior.ASK) {
                if (e.nonMisparsing) {
                    if (deferred == null) {
                        deferred = r;
                    }
                    continue;
                }
                logAsk(r.checkId, r.subId, command);
                return Result.askMisparsing(r.message, r.checkId, r.subId);
            }
        }
        if (deferred != null) {
            return deferred;
        }
        return Result.passthrough("Command passed all security checks");
    }

    private static final List<Validator> EARLY_VALIDATORS = List.of(
            BashSecurityValidator::validateEmpty,
            BashSecurityValidator::validateIncompleteCommands,
            BashSecurityValidator::validateSafeCommandSubstitution,
            BashSecurityValidator::validateGitCommit);

    private static final List<ValidatorEntry> MAIN_VALIDATORS = List.of(
            new ValidatorEntry(BashSecurityValidator::validateJqCommand, false),
            new ValidatorEntry(BashSecurityValidator::validateObfuscatedFlags, false),
            new ValidatorEntry(BashSecurityValidator::validateShellMetacharacters, false),
            new ValidatorEntry(BashSecurityValidator::validateDangerousVariables, false),
            new ValidatorEntry(BashSecurityValidator::validateCommentQuoteDesync, false),
            new ValidatorEntry(BashSecurityValidator::validateQuotedNewline, false),
            new ValidatorEntry(BashSecurityValidator::validateCarriageReturn, false),
            new ValidatorEntry(BashSecurityValidator::validateNewlines, true),
            new ValidatorEntry(BashSecurityValidator::validateIFSInjection, false),
            new ValidatorEntry(BashSecurityValidator::validateProcEnvironAccess, false),
            new ValidatorEntry(BashSecurityValidator::validateDangerousPatterns, false),
            new ValidatorEntry(BashSecurityValidator::validateRedirections, true),
            new ValidatorEntry(BashSecurityValidator::validateBackslashEscapedWhitespace, false),
            new ValidatorEntry(BashSecurityValidator::validateBackslashEscapedOperators, false),
            new ValidatorEntry(BashSecurityValidator::validateUnicodeWhitespace, false),
            new ValidatorEntry(BashSecurityValidator::validateMidWordHash, false),
            new ValidatorEntry(BashSecurityValidator::validateBraceExpansion, false),
            new ValidatorEntry(BashSecurityValidator::validateZshDangerousCommands, false),
            new ValidatorEntry(BashSecurityValidator::validateMalformedTokenInjection, false));

    /** CC {@code command.split(' ')[0]}（bashSecurity.ts:2295）——单空格切分取首段。 */
    private static String baseCommandOf(String command) {
        String[] parts = command.split(" ", -1);
        return parts.length > 0 ? parts[0] : "";
    }

    // ════════════════════════════════════════════════════════════════════
    // 早期校验器（CC bashSecurity.ts:233-740）
    // ════════════════════════════════════════════════════════════════════

    private static Result validateEmpty(Context ctx) {
        if (ctx.originalCommand.trim().isEmpty()) {
            return Result.allow("Empty command is safe");
        }
        return Result.passthrough("Command is not empty");
    }

    private static final Pattern TAB_PREFIX = Pattern.compile("^" + WS_CLASS + "*\\t");
    private static final Pattern OPERATOR_PREFIX = Pattern.compile("^" + WS_CLASS + "*(&&|\\|\\||;|>>?|<)");

    private static Result validateIncompleteCommands(Context ctx) {
        String original = ctx.originalCommand;
        String trimmed = original.trim();

        if (TAB_PREFIX.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command appears to be an incomplete fragment (starts with tab)",
                    INCOMPLETE_COMMANDS, 1);
        }
        if (trimmed.startsWith("-")) {
            return Result.askMisparsing(
                    "Command appears to be an incomplete fragment (starts with flags)",
                    INCOMPLETE_COMMANDS, 2);
        }
        if (OPERATOR_PREFIX.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command appears to be a continuation line (starts with operator)",
                    INCOMPLETE_COMMANDS, 3);
        }
        return Result.passthrough("Command appears complete");
    }

    private static Result validateSafeCommandSubstitution(Context ctx) {
        String original = ctx.originalCommand;
        if (!Pattern.compile("\\$\\(.*<<").matcher(original).find()) {
            return Result.passthrough("No heredoc in substitution");
        }
        if (BashHeredocExtractor.isSafeHeredoc(original, r -> !check(r).ask())) {
            return Result.allow("Safe command substitution: cat with quoted/escaped heredoc delimiter");
        }
        return Result.passthrough("Command substitution needs validation");
    }

    // CC :644-646 git commit 消息匹配
    private static final Pattern GIT_COMMIT_PREFIX = Pattern.compile("^git" + WS_CLASS + "+commit" + WS_CLASS + "+");
    // [\s\S] 是「任意字符（含换行）」dot-all 惯用法，非空白值域测试——其值域与 \s 具体集合无关，无需替换为 JS \s。
    private static final Pattern GIT_COMMIT_MSG = Pattern.compile(
            "^git[ \\t]+commit[ \\t]+[^;&|`$<>()\\n\\r]*?-m[ \\t]+([\"'])([\\s\\S]*?)\\1(.*)$");
    private static final Pattern SUBSTITUTION_IN_MSG = Pattern.compile("\\$\\(|`|\\$\\{");
    private static final Pattern REMAINDER_META = Pattern.compile("[;|&()`]|\\$\\(|\\$\\{");

    private static Result validateGitCommit(Context ctx) {
        String original = ctx.originalCommand;
        String base = ctx.baseCommand;

        if (!"git".equals(base) || !GIT_COMMIT_PREFIX.matcher(original).find()) {
            return Result.passthrough("Not a git commit");
        }

        if (original.indexOf('\\') >= 0) {
            return Result.passthrough("Git commit contains backslash, needs full validation");
        }

        Matcher m = GIT_COMMIT_MSG.matcher(original);
        if (m.find()) {
            String quote = m.group(1);
            String messageContent = m.group(2);
            String remainder = m.group(3);

            if ("\"".equals(quote) && messageContent != null && !messageContent.isEmpty()
                    && SUBSTITUTION_IN_MSG.matcher(messageContent).find()) {
                return Result.askMisparsing(
                        "Git commit message contains command substitution patterns",
                        GIT_COMMIT_SUBSTITUTION, 1);
            }

            if (remainder != null && !remainder.isEmpty()
                    && REMAINDER_META.matcher(remainder).find()) {
                return Result.passthrough("Git commit remainder contains shell metacharacters");
            }
            if (remainder != null && !remainder.isEmpty()) {
                StringBuilder unquoted = new StringBuilder();
                boolean inSQ = false;
                boolean inDQ = false;
                for (int i = 0; i < remainder.length(); i++) {
                    char c = remainder.charAt(i);
                    if (c == '\'' && !inDQ) {
                        inSQ = !inSQ;
                        continue;
                    }
                    if (c == '"' && !inSQ) {
                        inDQ = !inDQ;
                        continue;
                    }
                    if (!inSQ && !inDQ) unquoted.append(c);
                }
                if (unquoted.indexOf("<") >= 0 || unquoted.indexOf(">") >= 0) {
                    return Result.passthrough("Git commit remainder contains unquoted redirect operator");
                }
            }

            if (messageContent != null && messageContent.startsWith("-")) {
                return Result.askMisparsing(
                        "Command contains quoted characters in flag names",
                        OBFUSCATED_FLAGS, 5);
            }

            return Result.allow("Git commit with simple quoted message is allowed");
        }
        return Result.passthrough("Git commit needs validation");
    }

    // ════════════════════════════════════════════════════════════════════
    // 主校验器（CC bashSecurity.ts:742-2426）
    // ════════════════════════════════════════════════════════════════════

    private static final Pattern JQ_SYSTEM = Pattern.compile("\\bsystem" + WS_CLASS + "*\\(");
    private static final Pattern JQ_FILE_FLAGS = Pattern.compile(
            "(?:^|" + WS_CLASS + ")(?:-f\\b|--from-file|--rawfile|--slurpfile|-L\\b|--library-path)");

    private static Result validateJqCommand(Context ctx) {
        if (!"jq".equals(ctx.baseCommand)) {
            return Result.passthrough("Not jq");
        }
        String original = ctx.originalCommand;
        if (JQ_SYSTEM.matcher(original).find()) {
            return Result.askMisparsing(
                    "jq command contains system() function which executes arbitrary commands",
                    JQ_SYSTEM_FUNCTION, 1);
        }
        String afterJq = original.length() > 3 ? original.substring(3).trim() : "";
        if (JQ_FILE_FLAGS.matcher(afterJq).find()) {
            return Result.askMisparsing(
                    "jq command contains dangerous flags that could execute code or read arbitrary files",
                    JQ_FILE_ARGUMENTS, 1);
        }
        return Result.passthrough("jq command is safe");
    }

    // ── validateObfuscatedFlags 相关静态正则/辅助（CC :1130-1537）───────────
    private static final Pattern ANSI_C_QUOTE = Pattern.compile("\\$'[^']*'");
    private static final Pattern LOCALE_QUOTE = Pattern.compile("\\$\"[^\"]*\"");
    private static final Pattern EMPTY_SPECIAL_QUOTE_DASH = Pattern.compile("\\$['\"]{2}" + WS_CLASS + "*-");
    private static final Pattern EMPTY_QUOTES_DASH = Pattern.compile("(?:^|" + WS_CLASS + ")(?:''|\"\")+" + WS_CLASS + "*-");
    private static final Pattern EMPTY_PAIR_QUOTE_DASH = Pattern.compile("(?:\"\"|'')+['\"]-");
    private static final Pattern WORD_START_3_QUOTES = Pattern.compile("(?:^|" + WS_CLASS + ")['\"]{3,}");
    private static final Pattern WS_QUOTE_DASH = Pattern.compile(WS_CLASS + "['\"`]-");
    private static final Pattern QUOTE2_DASH = Pattern.compile("['\"`]{2}-");
    private static final Pattern FLAG_INSIDE = Pattern.compile("^-+[a-zA-Z0-9$`]");
    private static final Pattern DASHES_ONLY = Pattern.compile("^-+$");
    private static final Pattern FLAG_CHAR = Pattern.compile("[a-zA-Z0-9$`]");

    private static boolean isQuoteChar(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    private static boolean isFlagContinuationChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '\\' || c == '$' || c == '{' || c == '`' || c == '-';
    }

    /**
     * JS {@code \s}（legacy，无 u 标志）值域判定 · 委托 {@link BashWhitespace#isBashWhitespace(char)}
     * （单一事实源，G3-2；CC original: bashSecurity.ts 各 {@code /\s/} 单字符测试）。
     *
     * <p>值域与 {@link #UNICODE_WS_RE}（M15）同源：ASCII 空白（' ' '\t' '\n' '\r' '\f' 0x0B）
     * + Unicode 空白（NBSP(U+00A0)/U+1680/U+2000–U+200A/U+2028/U+2029/U+202F/U+205F/U+3000/U+FEFF）。
     * 不使用 {@code Character.isWhitespace}——与 JS {@code \s} 值域双向不符：
     * Java 多含 FS/GS/RS/US（U+001C–U+001F），且缺含 NBSP(U+00A0)/图空格(U+2007)/
     * 窄不换行空格(U+202F)/U+FEFF。
     */
    private static boolean isWhitespace(char c) {
        return BashWhitespace.isBashWhitespace(c);
    }

    private static Result validateObfuscatedFlags(Context ctx) {
        String original = ctx.originalCommand;
        String base = ctx.baseCommand;

        boolean hasShellOperators = original.indexOf('|') >= 0 || original.indexOf('&') >= 0
                || original.indexOf(';') >= 0;
        if ("echo".equals(base) && !hasShellOperators) {
            return Result.passthrough("echo command is safe and has no dangerous flags");
        }

        if (ANSI_C_QUOTE.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains ANSI-C quoting which can hide characters",
                    OBFUSCATED_FLAGS, 5);
        }
        if (LOCALE_QUOTE.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains locale quoting which can hide characters",
                    OBFUSCATED_FLAGS, 6);
        }
        if (EMPTY_SPECIAL_QUOTE_DASH.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains empty special quotes before dash (potential bypass)",
                    OBFUSCATED_FLAGS, 9);
        }
        if (EMPTY_QUOTES_DASH.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains empty quotes before dash (potential bypass)",
                    OBFUSCATED_FLAGS, 7);
        }
        if (EMPTY_PAIR_QUOTE_DASH.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains empty quote pair adjacent to quoted dash (potential flag obfuscation)",
                    OBFUSCATED_FLAGS, 10);
        }
        if (WORD_START_3_QUOTES.matcher(original).find()) {
            return Result.askMisparsing(
                    "Command contains consecutive quote characters at word start (potential obfuscation)",
                    OBFUSCATED_FLAGS, 11);
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < original.length() - 1; i++) {
            char current = original.charAt(i);
            char next = original.charAt(i + 1);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (current == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (current == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (isWhitespace(current) && isQuoteChar(next)) {
                char quoteChar = next;
                int j = i + 2;
                StringBuilder insideQuote = new StringBuilder();
                while (j < original.length() && original.charAt(j) != quoteChar) {
                    insideQuote.append(original.charAt(j));
                    j++;
                }
                char charAfterQuote = j + 1 < original.length() ? original.charAt(j + 1) : 0;
                String inside = insideQuote.toString();
                boolean hasFlagCharsInside = FLAG_INSIDE.matcher(inside).find();
                boolean hasFlagCharsContinuing = DASHES_ONLY.matcher(inside).matches()
                        && j + 1 < original.length()
                        && isFlagContinuationChar(charAfterQuote);
                boolean hasFlagCharsInNextQuote =
                        hasFlagCharsInNextQuote(original, j, inside);

                if (j < original.length() && original.charAt(j) == quoteChar
                        && (hasFlagCharsInside || hasFlagCharsContinuing || hasFlagCharsInNextQuote)) {
                    return Result.askMisparsing(
                            "Command contains quoted characters in flag names",
                            OBFUSCATED_FLAGS, 4);
                }
            }

            if (isWhitespace(current) && next == '-') {
                int j = i + 1;
                StringBuilder flagContent = new StringBuilder();
                while (j < original.length()) {
                    char flagChar = original.charAt(j);
                    if (isWhitespace(flagChar) || flagChar == '=') {
                        break;
                    }
                    if (isQuoteChar(flagChar)) {
                        if ("cut".equals(base) && "-d".contentEquals(flagContent) && isQuoteChar(flagChar)) {
                            break;
                        }
                        if (j + 1 < original.length()) {
                            char nextFlagChar = original.charAt(j + 1);
                            if (!((nextFlagChar >= 'a' && nextFlagChar <= 'z')
                                    || (nextFlagChar >= 'A' && nextFlagChar <= 'Z')
                                    || (nextFlagChar >= '0' && nextFlagChar <= '9')
                                    || nextFlagChar == '_' || nextFlagChar == '\''
                                    || nextFlagChar == '"' || nextFlagChar == '-')) {
                                break;
                            }
                        }
                    }
                    flagContent.append(flagChar);
                    j++;
                }
                if (flagContent.indexOf("\"") >= 0 || flagContent.indexOf("'") >= 0) {
                    return Result.askMisparsing(
                            "Command contains quoted characters in flag names",
                            OBFUSCATED_FLAGS, 1);
                }
            }
        }

        if (WS_QUOTE_DASH.matcher(ctx.fullyUnquotedContent).find()) {
            return Result.askMisparsing(
                    "Command contains quoted characters in flag names",
                    OBFUSCATED_FLAGS, 2);
        }
        if (QUOTE2_DASH.matcher(ctx.fullyUnquotedContent).find()) {
            return Result.askMisparsing(
                    "Command contains quoted characters in flag names",
                    OBFUSCATED_FLAGS, 3);
        }
        return Result.passthrough("No obfuscated flags detected");
    }

    /** CC :1367-1433 hasFlagCharsInNextQuote IIFE 的逐字移植。 */
    private static boolean hasFlagCharsInNextQuote(String cmd, int j, String insideQuote) {
        if (!(insideQuote.isEmpty() || DASHES_ONLY.matcher(insideQuote).matches())) {
            return false;
        }
        int charAfterQuoteIdx = j + 1;
        if (charAfterQuoteIdx >= cmd.length()) return false;
        char charAfterQuote = cmd.charAt(charAfterQuoteIdx);
        if (!isQuoteChar(charAfterQuote)) return false;

        int pos = j + 1;
        StringBuilder combined = new StringBuilder(insideQuote);
        while (pos < cmd.length() && isQuoteChar(cmd.charAt(pos))) {
            char segQuote = cmd.charAt(pos);
            int end = pos + 1;
            while (end < cmd.length() && cmd.charAt(end) != segQuote) {
                end++;
            }
            String segment = cmd.substring(pos + 1, end);
            combined.append(segment);

            if (FLAG_INSIDE.matcher(combined.toString()).find()) return true;

            String priorContent = segment.length() > 0
                    ? combined.substring(0, combined.length() - segment.length())
                    : combined.toString();
            if (DASHES_ONLY.matcher(priorContent).matches()) {
                if (FLAG_CHAR.matcher(segment).find()) return true;
            }

            if (end >= cmd.length()) break;
            pos = end + 1;
        }
        if (pos < cmd.length() && isFlagContinuationChar(cmd.charAt(pos))) {
            String combinedStr = combined.toString();
            if (DASHES_ONLY.matcher(combinedStr).matches() || combinedStr.isEmpty()) {
                char nextChar = cmd.charAt(pos);
                if (nextChar == '-') return true;
                if (isFlagContinuationChar(nextChar) && !combinedStr.isEmpty()) return true;
            }
            if (combinedStr.startsWith("-")) return true;
        }
        return false;
    }

    private static final Pattern METACHAR_QUOTED = Pattern.compile(
            "(?:^|" + WS_CLASS + ")[\"'][^\"']*[;&][^\"']*[\"'](?:" + WS_CLASS + "|$)");
    private static final Pattern GLOB_NAME = Pattern.compile("-name" + WS_CLASS + "+[\"'][^\"']*[;|&][^\"']*[\"']");
    private static final Pattern GLOB_PATH = Pattern.compile("-path" + WS_CLASS + "+[\"'][^\"']*[;|&][^\"']*[\"']");
    private static final Pattern GLOB_INAME = Pattern.compile("-iname" + WS_CLASS + "+[\"'][^\"']*[;|&][^\"']*[\"']");
    private static final Pattern GLOB_REGEX = Pattern.compile("-regex" + WS_CLASS + "+[\"'][^\"']*[;&][^\"']*[\"']");

    private static Result validateShellMetacharacters(Context ctx) {
        String unquoted = ctx.unquotedContent;
        String message = "Command contains shell metacharacters (;, |, or &) in arguments";

        if (METACHAR_QUOTED.matcher(unquoted).find()) {
            return Result.askMisparsing(message, SHELL_METACHARACTERS, 1);
        }
        if (GLOB_NAME.matcher(unquoted).find() || GLOB_PATH.matcher(unquoted).find()
                || GLOB_INAME.matcher(unquoted).find()) {
            return Result.askMisparsing(message, SHELL_METACHARACTERS, 2);
        }
        if (GLOB_REGEX.matcher(unquoted).find()) {
            return Result.askMisparsing(message, SHELL_METACHARACTERS, 3);
        }
        return Result.passthrough("No metacharacters");
    }

    private static final Pattern VAR_AFTER_OP = Pattern.compile("[<>|]" + WS_CLASS + "*\\$[A-Za-z_]");
    private static final Pattern VAR_BEFORE_OP = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*" + WS_CLASS + "*[|<>]");

    private static Result validateDangerousVariables(Context ctx) {
        String fully = ctx.fullyUnquotedContent;
        if (VAR_AFTER_OP.matcher(fully).find() || VAR_BEFORE_OP.matcher(fully).find()) {
            return Result.askMisparsing(
                    "Command contains variables in dangerous contexts (redirections or pipes)",
                    DANGEROUS_VARIABLES, 1);
        }
        return Result.passthrough("No dangerous variables");
    }

    private static Result validateDangerousPatterns(Context ctx) {
        String unquoted = ctx.unquotedContent;

        if (BashQuoteExtractor.hasUnescapedChar(unquoted, '`')) {
            return Result.askMisparsing(
                    "Command contains backticks (`) for command substitution", 0, 0);
        }
        for (SubPattern sp : COMMAND_SUBSTITUTION_PATTERNS) {
            if (sp.pattern.matcher(unquoted).find()) {
                return Result.askMisparsing(
                        "Command contains " + sp.message,
                        DANGEROUS_PATTERNS_COMMAND_SUBSTITUTION, 1);
            }
        }
        return Result.passthrough("No dangerous patterns");
    }

    private static Result validateRedirections(Context ctx) {
        String fully = ctx.fullyUnquotedContent;
        if (fully.indexOf('<') >= 0) {
            return Result.askNonMisparsing(
                    "Command contains input redirection (<) which could read sensitive files",
                    DANGEROUS_PATTERNS_INPUT_REDIRECTION, 1);
        }
        if (fully.indexOf('>') >= 0) {
            return Result.askNonMisparsing(
                    "Command contains output redirection (>) which could write to arbitrary files",
                    DANGEROUS_PATTERNS_OUTPUT_REDIRECTION, 1);
        }
        return Result.passthrough("No redirections");
    }

    private static final Pattern NEWLINE_THEN_CMD = Pattern.compile("(?<![" + WS_CLASS_CHARS + "]\\\\)[\\n\\r]" + WS_CLASS + "*" + NOT_WS_CLASS);

    private static Result validateNewlines(Context ctx) {
        String preStrip = ctx.fullyUnquotedPreStrip;
        if (preStrip.indexOf('\n') < 0 && preStrip.indexOf('\r') < 0) {
            return Result.passthrough("No newlines");
        }
        if (NEWLINE_THEN_CMD.matcher(preStrip).find()) {
            return Result.askNonMisparsing(
                    "Command contains newlines that could separate multiple commands",
                    NEWLINES, 1);
        }
        return Result.passthrough("Newlines appear to be within data");
    }

    private static Result validateCarriageReturn(Context ctx) {
        String original = ctx.originalCommand;
        if (original.indexOf('\r') < 0) {
            return Result.passthrough("No carriage return");
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (c == '\r' && !inDoubleQuote) {
                return Result.askMisparsing(
                        "Command contains carriage return (\\r) which shell-quote and bash tokenize differently",
                        NEWLINES, 2);
            }
        }
        return Result.passthrough("CR only inside double quotes");
    }

    private static final Pattern IFS_INJECTION_RE = Pattern.compile("\\$IFS|\\$\\{[^}]*IFS");

    private static Result validateIFSInjection(Context ctx) {
        if (IFS_INJECTION_RE.matcher(ctx.originalCommand).find()) {
            return Result.askMisparsing(
                    "Command contains IFS variable usage which could bypass security validation",
                    IFS_INJECTION, 1);
        }
        return Result.passthrough("No IFS injection detected");
    }

    private static final Pattern PROC_ENVIRON = Pattern.compile("/proc/.*/environ");

    private static Result validateProcEnvironAccess(Context ctx) {
        if (PROC_ENVIRON.matcher(ctx.originalCommand).find()) {
            return Result.askMisparsing(
                    "Command accesses /proc/*/environ which could expose sensitive environment variables",
                    PROC_ENVIRON_ACCESS, 1);
        }
        return Result.passthrough("No /proc/environ access detected");
    }

    private static Result validateMalformedTokenInjection(Context ctx) {
        String original = ctx.originalCommand;

        // CC bashSecurity.ts:1082-1128。CC 1087-1094 的 tryParseShellCommand 失败分支
        // （shell-quote 对 ${var+expr} 类抛 "Bad substitution"，commands.ts:153 注释确认）
        // → passthrough「handled elsewhere」。
        //
        // RETAIN-gap：该 parse-failure 分支**仅覆盖 shell-quote 抛错场景**，未配平引号不在此列
        // ——shell-quote 对未配平引号静默丢弃、解析成功（shellQuote.ts:107-111），由
        // hasMalformedTokens 的原文引号奇偶兜住 → ask(misparsing)。Java 无 shell-quote 库、
        // BashParser.tokenize 是状态机、从不抛 "Bad substitution"，故 ${var+expr} 类无等价触发面。
        // 该分支不影响安全：passthrough 后命令仍走 bashToolHasPermission 权限检查，不构成放行。
        // Java 侧省略 parse-failure 短路，直接 tokenize → hasCommandSeparator → hasMalformedTokens，
        // 未配平引号 + 分隔符恒 ask，绝不 passthrough。

        List<BashParser.Token> tokens = BashParser.tokenize(original);

        if (!hasCommandSeparator(original)) {
            return Result.passthrough("No command separators");
        }
        if (BashShellQuote.hasMalformedTokens(original, tokens)) {
            return Result.askMisparsing(
                    "Command contains ambiguous syntax with command separators that could be misinterpreted",
                    MALFORMED_TOKEN_INJECTION, 1);
        }
        return Result.passthrough("No malformed token injection detected");
    }

    /**
     * 命令分隔符检测（; &amp;&amp; ||）· 对齐 CC bashSecurity.ts:1099-1105（shell-quote op 条目判定）。
     *
     * <p>CC 在 shell-quote 解析结果上检测 op 条目。shell-quote 对未配平引号会静默丢弃、
     * 把其余按非引号解析（shellQuote.ts:107-111），故未配平引号"内"的 ; 仍浮出为 operator；
     * Java {@link BashParser#tokenize} 保留引号上下文（未配平引号内容吞成单个 word token、
     * 其中的 ; 不浮出），故本方法按 bash 引号语义扫描原文：仅【已配平】引号对屏蔽分隔符，
     * 未配平引号（无匹配闭合）视为被丢弃、不屏蔽。; 的判定对齐 CC {@code op === ';'}
     * （仅排除 ;;：shell-quote 产 {@code {op:';;'}} 不匹配 ';'；而 ;&amp; 由 shell-quote
     * 逐字符贪心匹配解析为 {@code {op:';'}} + {@code {op:'&'}} 两个 op，其中 ; 匹配
     * CC {@code op === ';'}，应计为分隔符）。
     */
    private static boolean hasCommandSeparator(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\') {
                i++; // bash：单引号外反斜杠转义下一字符
                continue;
            }
            if (c == '\'') {
                int close = indexOfSingleQuoteClose(command, i + 1);
                if (close >= 0) {
                    i = close; // 已配平单引号：整段为字面量，跳过
                }
                // 未配平（close < 0）：shell-quote 丢弃引号 → 不跳过，继续扫描后续
                continue;
            }
            if (c == '"') {
                int close = indexOfDoubleQuoteClose(command, i + 1);
                if (close >= 0) {
                    i = close; // 已配平双引号：整段为字面量，跳过
                }
                continue;
            }
            if (c == ';') {
                char next = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                if (next != ';') {
                    return true;
                }
                i++; // 跳过 ;; 的第二个字符（对齐 CC op === ';' 不匹配 ;;；;& 已被上面 return true 计为分隔符）
            } else if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                return true;
            } else if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                return true;
            }
        }
        return false;
    }

    /** 单引号闭合定位：单引号内无反斜杠转义，返回下一 {@code '} 的下标（无则 -1）。 */
    private static int indexOfSingleQuoteClose(String s, int from) {
        return s.indexOf('\'', from);
    }

    /** 双引号闭合定位：双引号内 {@code \} 转义下一字符（含 {@code \"}），返回未转义 {@code "} 的下标（无则 -1）。 */
    private static int indexOfDoubleQuoteClose(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++; // 双引号内 \ 转义下一字符
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static Result validateBackslashEscapedWhitespace(Context ctx) {
        if (hasBackslashEscapedWhitespace(ctx.originalCommand)) {
            return Result.askMisparsing(
                    "Command contains backslash-escaped whitespace that could alter command parsing",
                    BACKSLASH_ESCAPED_WHITESPACE, 0);
        }
        return Result.passthrough("No backslash-escaped whitespace");
    }

    private static boolean hasBackslashEscapedWhitespace(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\\' && !inSingleQuote) {
                if (!inDoubleQuote) {
                    char nextChar = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                    if (nextChar == ' ' || nextChar == '\t') {
                        return true;
                    }
                }
                i++;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
        }
        return false;
    }

    private static Result validateBackslashEscapedOperators(Context ctx) {
        if (hasBackslashEscapedOperator(ctx.originalCommand)) {
            return Result.askMisparsing(
                    "Command contains a backslash before a shell operator (;, |, &, <, >) which can hide command structure",
                    BACKSLASH_ESCAPED_OPERATORS, 0);
        }
        return Result.passthrough("No backslash-escaped operators");
    }

    private static boolean hasBackslashEscapedOperator(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\\' && !inSingleQuote) {
                if (!inDoubleQuote) {
                    char nextChar = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                    if (nextChar != 0 && SHELL_OPERATORS.contains(nextChar)) {
                        return true;
                    }
                }
                i++;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
        }
        return false;
    }

    private static Result validateUnicodeWhitespace(Context ctx) {
        if (UNICODE_WS_RE.matcher(ctx.originalCommand).find()) {
            return Result.askMisparsing(
                    "Command contains Unicode whitespace characters that could cause parsing inconsistencies",
                    UNICODE_WHITESPACE, 0);
        }
        return Result.passthrough("No Unicode whitespace");
    }

    private static final Pattern MID_WORD_HASH_RE = Pattern.compile(NOT_WS_CLASS + "(?<!\\$\\{)#");
    private static final Pattern CONTINUATION_JOIN = Pattern.compile("\\\\+\\n");

    private static Result validateMidWordHash(Context ctx) {
        String keep = ctx.unquotedKeepQuoteChars;
        String joined = joinContinuations(keep);
        if (MID_WORD_HASH_RE.matcher(keep).find() || MID_WORD_HASH_RE.matcher(joined).find()) {
            return Result.askMisparsing(
                    "Command contains mid-word # which is parsed differently by shell-quote vs bash",
                    MID_WORD_HASH, 0);
        }
        return Result.passthrough("No mid-word hash");
    }

    private static String joinContinuations(String s) {
        Matcher m = CONTINUATION_JOIN.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String match = m.group();
            int backslashCount = match.length() - 1;
            if (backslashCount % 2 == 1) {
                sb.append("\\".repeat(Math.max(0, backslashCount - 1)));
            } else {
                sb.append(match);
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
        return sb.toString();
    }

    private static final Pattern QUOTED_SINGLE_BRACE = Pattern.compile("['\"][{}]['\"]");

    private static Result validateBraceExpansion(Context ctx) {
        String content = ctx.fullyUnquotedPreStrip;

        int unescapedOpen = 0;
        int unescapedClose = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{' && !BashQuoteExtractor.isEscapedAtPosition(content, i)) {
                unescapedOpen++;
            } else if (c == '}' && !BashQuoteExtractor.isEscapedAtPosition(content, i)) {
                unescapedClose++;
            }
        }
        if (unescapedOpen > 0 && unescapedClose > unescapedOpen) {
            return Result.askMisparsing(
                    "Command has excess closing braces after quote stripping, indicating possible brace expansion obfuscation",
                    BRACE_EXPANSION, 2);
        }
        if (unescapedOpen > 0 && QUOTED_SINGLE_BRACE.matcher(ctx.originalCommand).find()) {
            return Result.askMisparsing(
                    "Command contains quoted brace character inside brace context (potential brace expansion obfuscation)",
                    BRACE_EXPANSION, 3);
        }

        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) != '{') continue;
            if (BashQuoteExtractor.isEscapedAtPosition(content, i)) continue;

            int depth = 1;
            int matchingClose = -1;
            for (int j = i + 1; j < content.length(); j++) {
                char ch = content.charAt(j);
                if (ch == '{' && !BashQuoteExtractor.isEscapedAtPosition(content, j)) {
                    depth++;
                } else if (ch == '}' && !BashQuoteExtractor.isEscapedAtPosition(content, j)) {
                    depth--;
                    if (depth == 0) {
                        matchingClose = j;
                        break;
                    }
                }
            }
            if (matchingClose == -1) continue;

            int innerDepth = 0;
            for (int k = i + 1; k < matchingClose; k++) {
                char ch = content.charAt(k);
                if (ch == '{' && !BashQuoteExtractor.isEscapedAtPosition(content, k)) {
                    innerDepth++;
                } else if (ch == '}' && !BashQuoteExtractor.isEscapedAtPosition(content, k)) {
                    innerDepth--;
                } else if (innerDepth == 0) {
                    if (ch == ',' || (ch == '.' && k + 1 < matchingClose && content.charAt(k + 1) == '.')) {
                        return Result.askMisparsing(
                                "Command contains brace expansion that could alter command parsing",
                                BRACE_EXPANSION, 1);
                    }
                }
            }
        }
        return Result.passthrough("No brace expansion detected");
    }

    private static final Pattern ENV_ASSIGN = Pattern.compile("^[A-Za-z_]\\w*=");
    private static final Pattern FC_MINUS_E = Pattern.compile(WS_CLASS + "-" + NOT_WS_CLASS + "*e");

    private static Result validateZshDangerousCommands(Context ctx) {
        String original = ctx.originalCommand;
        String trimmed = original.trim();
        String[] tokens = trimmed.split(WS_CLASS + "+");
        String baseCmd = "";
        for (String token : tokens) {
            if (ENV_ASSIGN.matcher(token).find()) continue;
            if (ZSH_PRECOMMAND_MODIFIERS.contains(token)) continue;
            baseCmd = token;
            break;
        }

        if (ZSH_DANGEROUS_COMMANDS_SET.contains(baseCmd)) {
            return Result.askMisparsing(
                    "Command uses Zsh-specific '" + baseCmd + "' which can bypass security checks",
                    ZSH_DANGEROUS_COMMANDS, 1);
        }
        if ("fc".equals(baseCmd) && FC_MINUS_E.matcher(trimmed).find()) {
            return Result.askMisparsing(
                    "Command uses 'fc -e' which can execute arbitrary commands via editor",
                    ZSH_DANGEROUS_COMMANDS, 2);
        }
        return Result.passthrough("No Zsh dangerous commands");
    }

    private static Result validateCommentQuoteDesync(Context ctx) {
        String original = ctx.originalCommand;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < original.length(); i++) {
            char ch = original.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inSingleQuote) {
                if (ch == '\'') inSingleQuote = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (inDoubleQuote) {
                if (ch == '"') inDoubleQuote = false;
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (ch == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (ch == '#') {
                int lineEnd = original.indexOf('\n', i);
                String commentText = original.substring(i + 1,
                        lineEnd == -1 ? original.length() : lineEnd);
                if (commentText.indexOf('\'') >= 0 || commentText.indexOf('"') >= 0) {
                    return Result.askMisparsing(
                            "Command contains quote characters inside a # comment which can desync quote tracking",
                            COMMENT_QUOTE_DESYNC, 0);
                }
                if (lineEnd == -1) break;
                i = lineEnd;
            }
        }
        return Result.passthrough("No comment quote desync");
    }

    private static Result validateQuotedNewline(Context ctx) {
        String original = ctx.originalCommand;
        if (original.indexOf('\n') < 0 || original.indexOf('#') < 0) {
            return Result.passthrough("No newline or no hash");
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < original.length(); i++) {
            char ch = original.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (ch == '\n' && (inSingleQuote || inDoubleQuote)) {
                int lineStart = i + 1;
                int nextNewline = original.indexOf('\n', lineStart);
                int lineEnd = nextNewline == -1 ? original.length() : nextNewline;
                String nextLine = original.substring(lineStart, lineEnd);
                if (nextLine.trim().startsWith("#")) {
                    return Result.askMisparsing(
                            "Command contains a quoted newline followed by a #-prefixed line, which can hide arguments from line-based permission checks",
                            QUOTED_NEWLINE, 0);
                }
            }
        }
        return Result.passthrough("No quoted newline-hash pattern");
    }
}
