package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RV-D-01 NG-1] BashSecurityValidator 23 项校验器 RED→GREEN 单元矩阵。
 *
 * <p>WHY（为何重要）：CC bashSecurity.ts 的 bashCommandIsSafe 校验器链在 Java 全缺
 * （EV-RV-D-BP-022 E1），注入向量现为 passthrough → 改后 misparsing ask。每项校验器
 * 附一个能命中该校验器（而非更早校验器）的注入向量，断言 ask && misparsing（除
 * validateNewlines/validateRedirections 两个 non-misparsing 校验器，断言 ask && !misparsing）。
 * 合法命令（ls -la / echo hi / git status / git commit -m 'x'）断言非 ask，守门禁不误伤。
 */
@DisplayName("[RV-D-01] BashSecurityValidator 23 校验器矩阵")
class BashSecurityValidatorTest {

    private static BashSecurityValidator.Result check(String cmd) {
        return BashSecurityValidator.check(cmd);
    }

    private static void assertMisparsingAsk(String cmd, String reasonContains) {
        BashSecurityValidator.Result r = check(cmd);
        assertThat(r.ask()).as("命令 [%s] 应命中 misparsing ask", cmd).isTrue();
        assertThat(r.misparsing()).as("命令 [%s] 应为 misparsing", cmd).isTrue();
        if (reasonContains != null) {
            assertThat(r.message()).contains(reasonContains);
        }
    }

    private static void assertSafe(String cmd) {
        BashSecurityValidator.Result r = check(cmd);
        assertThat(r.ask()).as("合法命令 [%s] 不应 ask", cmd).isFalse();
    }

    @Test
    @DisplayName("17 CONTROL_CHARACTERS: 控制字符（\\x00）→ ask misparsing")
    void controlCharacters() {
        assertMisparsingAsk("echo safe" + (char) 0 + "; rm -rf /", "control characters");
    }

    @Test
    @DisplayName("shell-quote 单引号反斜杠 bug：'\\' → ask misparsing")
    void shellQuoteSingleQuoteBug() {
        assertMisparsingAsk("'\\'", "single-quoted backslash");
    }

    @Test
    @DisplayName("1 INCOMPLETE_COMMANDS：tab 前缀 / dash 前缀 / 操作符前缀 → ask")
    void incompleteCommands() {
        assertMisparsingAsk("\techo hi", "starts with tab");
        assertMisparsingAsk("-la", "starts with flags");
        assertMisparsingAsk("&& echo", "starts with operator");
    }

    @Test
    @DisplayName("2 JQ_SYSTEM_FUNCTION：jq system( → ask")
    void jqSystemFunction() {
        assertMisparsingAsk("jq 'system(\"ls\")'", "system()");
    }

    @Test
    @DisplayName("3 JQ_FILE_ARGUMENTS：jq -f → ask")
    void jqFileArguments() {
        assertMisparsingAsk("jq -f /etc/passwd", "dangerous flags");
    }

    @Test
    @DisplayName("4 OBFUSCATED_FLAGS：$'...' ANSI-C 引号 → ask")
    void obfuscatedFlagsAnsiC() {
        assertMisparsingAsk("ls $'foo'", "ANSI-C");
    }

    @Test
    @DisplayName("5 SHELL_METACHARACTERS：jq 双引号内容含 ; → ask（jq 保留双引号）")
    void shellMetacharacters() {
        assertMisparsingAsk("jq \"a;b\"", "metacharacters");
    }

    @Test
    @DisplayName("6 DANGEROUS_VARIABLES：$VAR 于管道/重定向上下文 → ask")
    void dangerousVariables() {
        assertMisparsingAsk("cat $VAR | evil", "variables in dangerous contexts");
    }

    @Test
    @DisplayName("7 NEWLINES（non-misparsing 延后）：换行分隔命令 → ask 且非 misparsing")
    void newlinesDeferred() {
        BashSecurityValidator.Result r = check("echo hi\necho evil");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).as("LF 换行是 normal pattern，非 misparsing").isFalse();
    }

    @Test
    @DisplayName("7 NEWLINES subId2：\\r 双引号外 → ask misparsing（CR 是 misparsing）")
    void carriageReturn() {
        assertMisparsingAsk("echo a\recho evil", "carriage return");
    }

    @Test
    @DisplayName("11 IFS_INJECTION：$IFS → ask")
    void ifsInjection() {
        assertMisparsingAsk("cat $IFS", "IFS");
    }

    @Test
    @DisplayName("13 PROC_ENVIRON_ACCESS：/proc/self/environ → ask")
    void procEnvironAccess() {
        assertMisparsingAsk("cat /proc/self/environ", "/proc");
    }

    @Test
    @DisplayName("8 DANGEROUS_PATTERNS：反引号 / $() → ask")
    void dangerousPatterns() {
        assertMisparsingAsk("cat `id`", "backticks");
        assertMisparsingAsk("echo $(id)", "$()");
    }

    @Test
    @DisplayName("10 REDIRECTIONS（non-misparsing 延后）：> 重定向 → ask 且非 misparsing")
    void redirectionsDeferred() {
        BashSecurityValidator.Result r = check("cat file > out");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).as("重定向是 normal pattern，非 misparsing").isFalse();
    }

    @Test
    @DisplayName("15 BACKSLASH_ESCAPED_WHITESPACE：echo\\ test → ask（路径遍历）")
    void backslashEscapedWhitespace() {
        assertMisparsingAsk("echo\\ test", "backslash-escaped whitespace");
    }

    @Test
    @DisplayName("21 BACKSLASH_ESCAPED_OPERATORS：\\; → ask（splitCommand 双解析 bug）")
    void backslashEscapedOperators() {
        assertMisparsingAsk("cat safe.txt \\; echo /etc/passwd", "backslash before a shell operator");
    }

    @Test
    @DisplayName("18 UNICODE_WHITESPACE：U+00A0 → ask")
    void unicodeWhitespace() {
        assertMisparsingAsk("echo " + (char) 0x00A0 + "evil", "Unicode whitespace");
    }

    @Test
    @DisplayName("19 MID_WORD_HASH：a#b → ask（shell-quote 视 # 注释 bash 视字面量）")
    void midWordHash() {
        assertMisparsingAsk("echo a#b", "mid-word #");
    }

    @Test
    @DisplayName("16 BRACE_EXPANSION：{a,b} 最外层逗号 → ask")
    void braceExpansion() {
        assertMisparsingAsk("git diff {a,b}", "brace expansion");
    }

    @Test
    @DisplayName("20 ZSH_DANGEROUS_COMMANDS：zmodload → ask")
    void zshDangerousCommands() {
        assertMisparsingAsk("zmodload zsh/system", "zmodload");
    }

    @Test
    @DisplayName("14 MALFORMED_TOKEN_INJECTION：未配平花括号 + ; 分隔符 → ask（HackerOne eval 绕过）")
    void malformedTokenInjection() {
        assertMisparsingAsk("echo {a};echo {b", "ambiguous syntax");
    }

    @Test
    @DisplayName("14 MALFORMED_TOKEN_INJECTION 未配平引号门禁：失衡引号 + 分隔符 → ask misparsing（绝不 passthrough 放行）")
    void malformedTokenInjectionParseFailGate() {
        // CC 净效果（shellQuote.ts:107-111）：shell-quote 静默丢弃未配平引号、把其余按非引号解析，
        // 引号"内"的 ; 仍浮出为 operator；hasMalformedTokens 的原文引号奇偶兜住 → ask(misparsing)。
        // 上一轮返工误把该场景反转为 passthrough「handled elsewhere」——语义反置（放行），此处修正：
        // 未配平引号 + 分隔符必须 ask，验证意图 = 未配平引号绝不 passthrough 放行。
        BashSecurityValidator.Result dq = check("echo \"hi;evil");
        assertThat(dq.ask()).as("失衡双引号 + 分隔符应 ask 而非 passthrough").isTrue();
        assertThat(dq.misparsing()).isTrue();
        assertThat(dq.checkId()).isEqualTo(BashSecurityValidator.MALFORMED_TOKEN_INJECTION);
        assertThat(dq.message()).contains("ambiguous syntax");

        BashSecurityValidator.Result sq = check("echo 'hi;evil");
        assertThat(sq.ask()).as("失衡单引号 + 分隔符应 ask 而非 passthrough").isTrue();
        assertThat(sq.misparsing()).isTrue();
        assertThat(sq.checkId()).isEqualTo(BashSecurityValidator.MALFORMED_TOKEN_INJECTION);
        assertThat(sq.message()).contains("ambiguous syntax");
    }

    @Test
    @DisplayName("4 OBFUSCATED_FLAGS：NBSP（Unicode 空白）后接引号 flag → ask（\\s Unicode 感知，DRIFT-2 修正）")
    void obfuscatedFlagsUnicodeWhitespace() {
        // 修复前 isWhitespace 仅 ASCII：NBSP 不被当空白 → 落到 M15 UNICODE_WHITESPACE；
        // 修复后 NBSP 被识别为空白 → M2 命中 OBFUSCATED_FLAGS（checkId 归属对齐 CC）。
        BashSecurityValidator.Result r = check("ls\u00A0\"-x\"");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.OBFUSCATED_FLAGS);
        assertThat(r.message()).contains("quoted characters in flag names");
    }

    @Test
    @DisplayName("19 MID_WORD_HASH：NBSP（Unicode 空白）前 # 不误判 mid-word（\\S Unicode 感知，DRIFT-2 修正）")
    void midWordHashUnicodeWhitespaceNotMatch() {
        // NBSP 是 Unicode 空白：\\S 不应匹配它 → 不命中 MID_WORD_HASH，由 M15 UNICODE_WHITESPACE 兜底。
        // 回归守卫：NBSP 前 # 恒归 UNICODE_WHITESPACE，绝不归 MID_WORD_HASH。
        BashSecurityValidator.Result r = check("echo foo\u00A0#bar");
        assertThat(r.ask()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.UNICODE_WHITESPACE);
    }

    @Test
    @DisplayName("4 OBFUSCATED_FLAGS：NBSP 词界 + 空引号 dash → 正则路径 EMPTY_QUOTES_DASH 命中 4/7（GAP-4 正则值域对齐）")
    void obfuscatedFlagsRegexUnicodeWhitespace() {
        // EMPTY_QUOTES_DASH /(?:^|\s)(?:''|"")+\s*-/：NBSP 是 JS \s，作词界 → 命中 OBFUSCATED_FLAGS(4/7)。
        // GAP-4 前 Java \s 漏 NBSP → 该正则不命中，落到 M15 UNICODE_WHITESPACE(18)。这是纯正则路径（非 isWhitespace 字符扫描）。
        BashSecurityValidator.Result r = check("ls" + (char) 0x00A0 + "''-x");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.OBFUSCATED_FLAGS);
        assertThat(r.subId()).isEqualTo(7);
        assertThat(r.message()).contains("empty quotes");
    }

    @Test
    @DisplayName("5 SHELL_METACHARACTERS：jq 引号内容含 ; → 正则路径 METACHAR_QUOTED 命中 5/1（GAP-4 正则值域对齐）")
    void shellMetacharactersUnicodeWhitespace() {
        // METACHAR_QUOTED /(?:^|\s)["'][^"']*[;&][^"']*["'](?:\s|$)/ 匹配 withDoubleQuotes。
        // 对 jq 命令（baseCommand=jq），isJq=true 使 withDoubleQuotes 保留引号（jq "a;b" → 含 "a;b"），
        // METACHAR_QUOTED 命中 SHELL_METACHARACTERS(5/1)（GAP-4 后 WS_CLASS 精确 JS \s，空格词界命中）。
        // 注：NBSP 词界场景（jq "a;b"）受 baseCommandOf 的 split(" ") 局限（NBSP 非空格 → baseCommand 识别失败 → isJq=false），
        // 会落到 M15 UNICODE_WHITESPACE(18)——已由 newlinesUnicodeWhitespace 覆盖 NBSP 兜底语义，此处用普通空格验证正则值域。
        BashSecurityValidator.Result r = check("jq \"a;b\"");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.SHELL_METACHARACTERS);
        assertThat(r.subId()).isEqualTo(1);
        assertThat(r.message()).contains("metacharacters");
    }

    @Test
    @DisplayName("7 NEWLINES：换行后 NBSP → 正则 \\s*\\S 命中（GAP-4），M15 UNICODE_WHITESPACE 兜底胜出（checkId=18，对齐 CC 终判）")
    void newlinesUnicodeWhitespace() {
        // NEWLINE_THEN_CMD /(?<![\s]\\)[\n\r]\s*\S/：换行后 NBSP 作 \s*，GAP-4 后正则语义严格对齐 JS。
        // 但 validateNewlines 是 non-misparsing（延后），validateUnicodeWhitespace（misparsing，晚于 Newlines）仍命中 NBSP → 立即 ask。
        // CC 同样：M15 在 Newlines 之后立即返回，终判 checkId=UNICODE_WHITESPACE(18)，非 NEWLINES(7)。回归守卫：不误报 NEWLINES。
        BashSecurityValidator.Result r = check("echo hi\n" + (char) 0x00A0 + "evil");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.UNICODE_WHITESPACE);
    }

    @Test
    @DisplayName("19 MID_WORD_HASH：U+FEFF（BOM）前 # 不误判 mid-word（\\S 精确补集，GAP-4 正则值域对齐）")
    void midWordHashUnicodeWhitespaceFeffNotMatch() {
        // U+FEFF 是 JS \s（含于 UNICODE_WS_RE），\\S（NOT_WS_CLASS）不应匹配它 → 不命中 MID_WORD_HASH。
        // 由 M15 UNICODE_WHITESPACE 兜底（先于 MidWordHash 运行）。与 NBSP 用例互补，覆盖 Java \s 亦漏的非分隔符空白 U+FEFF。
        BashSecurityValidator.Result r = check("echo foo" + (char) 0xFEFF + "#bar");
        assertThat(r.ask()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.UNICODE_WHITESPACE);
    }

    @Test
    @DisplayName("22 COMMENT_QUOTE_DESYNC：# 注释后含引号 → ask")
    void commentQuoteDesync() {
        assertMisparsingAsk("echo x # ' \"\nrm -rf /", "comment");
    }

    @Test
    @DisplayName("23 QUOTED_NEWLINE：引号内换行 + 下一行 # → ask（stripCommentLines 藏路径）")
    void quotedNewline() {
        assertMisparsingAsk("echo 'x\n#secret", "quoted newline");
    }

    @Test
    @DisplayName("合法命令不误伤：ls -la / echo hi / git status / git commit -m 'x' → 非 ask")
    void legalCommandsNotAsk() {
        assertSafe("ls -la");
        assertSafe("echo hi");
        assertSafe("git status");
        assertSafe("git commit -m 'simple message'");
    }

    // ── G3-3 逐项核验补充断言（对齐 CC bashSecurity.ts 实际行为，行号易漂须复验）──

    @Test
    @DisplayName("12 GIT_COMMIT_SUBSTITUTION：git commit -m \"$(whoami)\"（双引号内含 $()）→ ask misparsing")
    void gitCommitDoubleQuoteSubstitutionAsks() {
        // CC validateGitCommit（bashSecurity.ts:644-660）：quote === '\"' 且 messageContent 含 $()/`/${}
        // → ask GIT_COMMIT_SUBSTITUTION(1)。双引号内 $() 会经 bash 展开执行。
        BashSecurityValidator.Result r = check("git commit -m \"$(whoami)\"");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.GIT_COMMIT_SUBSTITUTION);
        assertThat(r.subId()).isEqualTo(1);
        assertThat(r.message()).contains("command substitution");
    }

    @Test
    @DisplayName("12 GIT_COMMIT_SUBSTITUTION：git commit -m '$(whoami)'（单引号内含 $()）→ 不 ask（单引号字面量）")
    void gitCommitSingleQuoteSubstitutionNotAsk() {
        // CC validateGitCommit 仅检查双引号 quote：单引号内 $() 是字面量，bash 不展开 → allow → 早退 passthrough。
        // 回归守卫：单引号消息不得误报 GIT_COMMIT_SUBSTITUTION。
        assertSafe("git commit -m '$(whoami)'");
    }

    @Test
    @DisplayName("延后时序（CC :2380-2391）：cat safe.txt \\; echo /etc/passwd > ./out → misparsing \\; 胜出，非延后重定向 ask")
    void deferredRedirectionDoesNotShortCircuitMisparsing() {
        // WHY（规则九）：validateRedirections 是 non-misparsing（延后），若短路返回，`> ./out` 的 ask
        // 无 misparsing flag → 权限流不阻断；但后续 validateBackslashEscapedOperators（misparsing）命中
        // `\\;` 才应返回带 flag 的 ask（防 `cat safe.txt \\; echo /etc/passwd > ./out` 逃逸）。
        // 该断言唯一验证「non-misparsing ask 不得短路掉更晚的 misparsing ask」这一安全不变量。
        BashSecurityValidator.Result r = check("cat safe.txt \\; echo /etc/passwd > ./out");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).as("\\; 是 misparsing 关注，必须带 flag 返回，而非延后重定向 ask").isTrue();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.BACKSLASH_ESCAPED_OPERATORS);
        assertThat(r.message()).contains("backslash before a shell operator");
    }

    @Test
    @DisplayName("10 REDIRECTIONS：echo hi > out → ask 且 checkId=OUTPUT_REDIRECTION(10)、非 misparsing")
    void redirectionsOutputCheckId() {
        BashSecurityValidator.Result r = check("echo hi > out");
        assertThat(r.ask()).isTrue();
        assertThat(r.misparsing()).isFalse();
        assertThat(r.checkId()).isEqualTo(BashSecurityValidator.DANGEROUS_PATTERNS_OUTPUT_REDIRECTION);
        assertThat(r.message()).contains("output redirection");
    }
}
