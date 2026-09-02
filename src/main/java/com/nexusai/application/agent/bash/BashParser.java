package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bash Parser · 对齐 CC utils/bash/bashParser.ts + ast.ts + heredoc.ts.
 *
 * <p>FIX-R8 (s23 P3-1 修正): 完整 Bash tokenizer/state machine + tree-sitter 等价安全 walker.
 *
 * <p>G3-2 空白值域统一：本类全部空白判定（tokenize:531 / splitForSecurity heredoc 定界扫描 /
 * extractHeredocs / TokenizerState.skipWhitespace·tryHeredoc·isWordBoundary / isCommandStartContext /
 * restOfLineHasContent）由 {@link Character#isWhitespace(char)} 改为 {@link BashWhitespace#isBashWhitespace(char)}
 * （JS {@code \s} legacy 值域单一事实源，CC original: bashSecurity.ts 各 {@code /\s/}）。二者对 ASCII 空白
 * 判定一致，仅 Unicode 空白值域不同（JS {@code \s} 含 NBSP 等、不含 FS/GS/RS/US）。
 *
 * <h2>L1 行为等价（CC ast.ts 2679 行）</h2>
 * <ul>
 *   <li>三 allowlist（{@link #STRUCTURAL_TYPES} / {@link #SEPARATOR_TYPES} /
 *       {@link #TOO_COMPLEX_REASON_TYPES}）— fail-closed 设计</li>
 *   <li>{@link #parseForSecurity(String)} 返回三态 {@link ParseForSecurityResult}
 *       （Simple / TooComplex / ParseUnavailable），产出 argv/envVars/redirects
 *       结构化命令 + varScope 变量追踪（G4-2），并含解析预算（G4-6）、预检查链（G4-8）、
 *       NN# 算术基检测（G4-7）。fail-closed：无法静态建模的结构 → too-complex。</li>
 *   <li>{@link #checkSemantics(List)} 完整 11 项 post-argv 语义检查（CC ast.ts:2213-2679），
 *       EVAL_LIKE_BUILTINS 20 项（CC ast.ts:2086-2134）+ carve-out。</li>
 *   <li>{@link #extractCommandArguments(String)} / {@link #extractEnvVars(String)}
 *       对齐 parser.ts 230 行 extractEnvVars + extractCommandArguments</li>
 * </ul>
 *
 * <h2>L2 契约</h2>
 * <ul>
 *   <li>TOO_COMPLEX_REASON_TYPES 19 项 node type（CC ast.ts:186-205），仅用于 tooComplex
 *       文案（ast.ts:2033-2041）+ nodeTypeId 分析（:213-218）</li>
 *   <li>tooComplex reason 文案：{@code 'Contains '+nodeType} / {@code 'Unhandled node type: '+nodeType} /
 *       {@code 'Parse error'}</li>
 * </ul>
 *
 * <h2>原 tokenizer 保留</h2>
 * Single/double/ANSI-C quote + heredoc + variable + command sub + arithmetic + operator +
 * comment + subshell + function def + alias/keywords 等 state machine 一字不动.
 * 新增方法只增加能力, 不替换旧方法.
 *
 * <p>LIMIT: 不实现完整 AST + tree-sitter NAPI (TS 13K 行), 但 tokenizer + 三 allowlist
 * 安全 walker 覆盖 ast.ts parseForSecurity 的全部 fail-closed 语义.
 */
public final class BashParser {

    private static final Logger log = LoggerFactory.getLogger(BashParser.class);

    private BashParser() {}

    // ════════════════════════════════════════════════════════════════════════
    // R8-2 三 allowlist（对齐 TS ast.ts 2679 行 STRUCTURAL_TYPES/SEPARATOR_TYPES/DANGEROUS_TYPES）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * STRUCTURAL_TYPES — 程序结构节点, 在 tokenize 输出中允许出现的 node_type.
     * 对应 TS ast.ts 中的 program / function_definition / if_statement / for_statement
     * / while_statement / case_statement 等.
     * <p>本实现是 tokenizer（非 AST）, 用 token kind 反向表达: 任何 token kind ∈ 此集合即视为结构性.
     */
    public static final Set<String> STRUCTURAL_TYPES;
    static {
        Set<String> s = new LinkedHashSet<>();
        s.add("program");
        s.add("function_definition");
        s.add("if_statement");
        s.add("for_statement");
        s.add("while_statement");
        s.add("case_statement");
        s.add("word");
        s.add("string");
        s.add("raw_string");
        s.add("ansi_c_string");
        s.add("variable");
        s.add("arithmetic_expansion");
        s.add("redirect");
        s.add("heredoc_tag");
        s.add("heredoc_body");
        s.add("comment");
        s.add("newline");
        s.add("operator");
        s.add("eof");
        STRUCTURAL_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * SEPARATOR_TYPES — 命令分隔符节点. 在 tokenize 输出中, 这些 string 是允许出现的
     * 命令分隔符. 对应 TS ast.ts SEPARATOR_TYPES = [";", "&&", "||", "|"].
     */
    public static final Set<String> SEPARATOR_TYPES;
    static {
        Set<String> s = new LinkedHashSet<>();
        s.add(";");
        s.add("&&");
        s.add("||");
        s.add("|");
        SEPARATOR_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * TOO_COMPLEX_REASON_TYPES — 无法静态分析的 node type（19 项）。
     * 对齐 CC ast.ts:186-205 {@code DANGEROUS_TYPES}。仅用于 tooComplex 文案生成
     * （ast.ts:2033-2041）：{@code nodeType==='ERROR' ? 'Parse error' :
     * DANGEROUS_TYPES.has(nodeType) ? 'Contains '+nodeType : 'Unhandled node type: '+nodeType}，
     * 以及 nodeTypeId 分析（ast.ts:213-218）。注意：真正的 fail-closed 安全属性来自
     * walkArgument/walkCommand 的 allowlist（任何未显式处理类型 → too-complex）。
     * <p>Java 端 tokenizer 语境无 tree-sitter node type 概念，walker 遇到未识别结构时
     * 用本集合的 nodeType 串填充 reason 文案。
     */
    public static final Set<String> TOO_COMPLEX_REASON_TYPES;
    static {
        Set<String> s = new LinkedHashSet<>();
        s.add("command_substitution");
        s.add("process_substitution");
        s.add("expansion");
        s.add("simple_expansion");
        s.add("brace_expression");
        s.add("subshell");
        s.add("compound_statement");
        s.add("for_statement");
        s.add("while_statement");
        s.add("until_statement");
        s.add("if_statement");
        s.add("case_statement");
        s.add("function_definition");
        s.add("test_command");
        s.add("ansi_c_string");
        s.add("translated_string");
        s.add("herestring_redirect");
        s.add("heredoc_redirect");
        TOO_COMPLEX_REASON_TYPES = Collections.unmodifiableSet(s);
    }

    /**
     * nodeTypeId 分析 · 对齐 CC ast.ts:213-218 {@code nodeTypeId}：
     * 0 = unknown/other，-1 = ERROR（解析失败），-2 = 预检查，其余为
     * TOO_COMPLEX_REASON_TYPES 中索引 +1（1-based，append 时 ID 稳定）。
     */
    public static int nodeTypeId(String nodeType) {
        if (nodeType == null) return -2;
        if ("ERROR".equals(nodeType)) return -1;
        int i = new ArrayList<>(TOO_COMPLEX_REASON_TYPES).indexOf(nodeType);
        return i >= 0 ? i + 1 : 0;
    }

    /**
     * EVAL_LIKE_BUILTINS — 求值参数为代码的内建命令（20 项）。
     * 对齐 CC ast.ts:2086-2134（EVAL_LIKE_BUILTINS 全集）。
     * <p>carve-out 在 {@link #checkSemantics} 内实现（CC :2630-2656）：
     * {@code command -v/-V} 放行（:2631）、{@code fc} 无 -e/-s 放行（fc -l 安全, :2633-2635）、
     * {@code compgen} 无 -C/-F/-W 放行（:2642-2644）。
     * <p>declare/typeset/local/readonly/export 属 declaration_command（CC ast.ts:579-676），
     * 不在 EVAL_LIKE 中。
     */
    private static final Set<String> EVAL_LIKE_BUILTINS;
    static {
        Set<String> s = new LinkedHashSet<>();
        s.add("eval");
        s.add("source");
        s.add(".");      // POSIX 等价 source
        s.add("exec");
        s.add("command");
        s.add("builtin");
        s.add("fc");
        s.add("coproc");
        s.add("noglob");     // zsh precommand modifier
        s.add("nocorrect");  // zsh precommand modifier
        s.add("trap");
        s.add("enable");
        s.add("mapfile");
        s.add("readarray");
        s.add("hash");
        s.add("bind");
        s.add("complete");
        s.add("compgen");
        s.add("alias");
        s.add("let");
        EVAL_LIKE_BUILTINS = Collections.unmodifiableSet(s);
    }

    /**
     * 声明关键字 — 后随 {@code VAR=$(...)} 仍属赋值上下文 (CC declaration_command
     * walkVariableAssignment ast.ts:1793-1820 提取内层命令), 与命令名区分.
     */
    private static final Set<String> DECLARATION_KEYWORDS =
            Set.of("export", "declare", "local", "readonly", "typeset");

    // ════════════════════════════════════════════════════════════════════════
    // G4-2 varScope 变量追踪常量 · 对齐 CC ast.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 外层 argv 中 $() 被递归提取后的占位串 · CC original: CMDSUB_PLACEHOLDER
     * （Open-ClaudeCode/src/utils/bash/ast.ts:74）{@code '__CMDSUB_OUTPUT__'}。
     * 运行时输出不可静态确定，内层命令已独立提取并逐条匹配权限规则。
     */
    public static final String CMDSUB_PLACEHOLDER = "__CMDSUB_OUTPUT__";

    /**
     * 已追踪变量（值未知，如循环变量/read 变量/$() 输出）的占位串 ·
     * CC original: VAR_PLACEHOLDER（ast.ts:82）{@code '__TRACKED_VAR__'}。
     */
    public static final String VAR_PLACEHOLDER = "__TRACKED_VAR__";

    /** 值是否含任意占位符（exact/embedded）· CC original: containsAnyPlaceholder（ast.ts:94-96）。 */
    private static boolean containsAnyPlaceholder(String value) {
        return value != null && (value.contains(CMDSUB_PLACEHOLDER) || value.contains(VAR_PLACEHOLDER));
    }

    /**
     * 裸 $VAR 含 IFS/glob 元字符 → 词拆分/路径展开不可静态建模 ·
     * CC original: BARE_VAR_UNSAFE_RE（ast.ts:110）{@code /[ \t\n*?[]/}。
     */
    private static final Pattern BARE_VAR_UNSAFE_RE = Pattern.compile("[ \\t\\n*?\\[]");

    /**
     * 已知安全环境变量（bash 自动设置，值受 shell/OS 控制）· CC original:
     * SAFE_ENV_VARS（ast.ts:125-149，20 项）。仅 insideString 放行。
     */
    private static final Set<String> SAFE_ENV_VARS = Set.of(
            "HOME", "PWD", "OLDPWD", "USER", "LOGNAME", "SHELL", "PATH", "HOSTNAME",
            "UID", "EUID", "PPID", "RANDOM", "SECONDS", "LINENO", "TMPDIR",
            "BASH_VERSION", "BASHPID", "SHLVL", "HISTFILE", "IFS");

    /**
     * 特殊 shell 变量（$?/$/$!/$#/$0-$9）· CC original: SPECIAL_VAR_NAMES（ast.ts:167-174）。
     * 仅 insideString 放行；@ 与 * 不在集合（fresh BashTool shell 中位参为空，占位会撒谎）。
     */
    private static final Set<String> SPECIAL_VAR_NAMES = Set.of("?", "$", "!", "#", "0", "-");

    /** 花括号展开语法 · CC original: BRACE_EXPANSION_RE（ast.ts:245）{@code /\{[^{}\s]*(,|\.\.)[^{}\s]*\}/}。 */
    private static final Pattern BRACE_EXPANSION_RE =
            Pattern.compile("\\{[^{}\\s]*(,|\\.\\.)[^{}\\s]*\\}");

    /** 控制字符（tree-sitter 与 bash 词边界分歧）· CC original: CONTROL_CHAR_RE（ast.ts:254）。 */
    private static final Pattern CONTROL_CHAR_RE = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");

    /** Unicode 空白（终端不可见）· CC original: UNICODE_WHITESPACE_RE（ast.ts:262-263）。 */
    private static final Pattern UNICODE_WHITESPACE_RE =
            Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200B\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]");

    /** 空白前反斜杠 / 行续接 · CC original: BACKSLASH_WHITESPACE_RE（ast.ts:279）。 */
    private static final Pattern BACKSLASH_WHITESPACE_RE = Pattern.compile("\\\\[ \\t]|[^ \\t\\n\\\\]\\\\\\n");

    /** zsh 动态命名目录 ~[name] · CC original: ZSH_TILDE_BRACKET_RE（ast.ts:287）。 */
    private static final Pattern ZSH_TILDE_BRACKET_RE = Pattern.compile("~\\[");

    /** zsh EQUALS 展开 =cmd · CC original: ZSH_EQUALS_EXPANSION_RE（ast.ts:297）。 */
    private static final Pattern ZSH_EQUALS_EXPANSION_RE = Pattern.compile("(?:^|[\\s;&|])=[a-zA-Z_]");

    /** 花括号引号混淆 · CC original: BRACE_WITH_QUOTE_RE（ast.ts:314）{@code /\{[^}]*['"]/}。 */
    private static final Pattern BRACE_WITH_QUOTE_RE = Pattern.compile("\\{[^}]*['\"]");

    /** 换行 + #（下游 stripSafeWrappers 按行重解析时隐藏参数）· CC original: NEWLINE_HASH_RE（ast.ts:2204）。 */
    private static final Pattern NEWLINE_HASH_RE = Pattern.compile("\\n[ \\t]*#");

    /** 泄密路径 /proc/&lt;pid&gt;/environ · CC original: PROC_ENVIRON_RE（ast.ts:2197）{@code /proc/.+/environ}。 */
    private static final Pattern PROC_ENVIRON_RE = Pattern.compile("/proc/.*/environ");

    /** 算术基前缀 NN# · G4-7 检测 {@code 10#$(cmd)} 藏命令替换（ast.ts:1428-1442）。 */
    private static final Pattern NN_NUMBER_PREFIX_RE = Pattern.compile("-?(?:0x)?[0-9]+#");

    /**
     * shell 保留字 · CC original: SHELL_KEYWORDS（bashParser.ts:87-103）。
     * 作命令名 = 解析误判 → 拒绝。
     */
    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "if", "then", "elif", "else", "fi", "while", "until", "for", "in",
            "do", "done", "case", "esac", "function", "select");

    /**
     * G4 block 结构关键字（仅作为<b>段首命令词</b>时识别）· CC ast.ts:693-880
     * {@code for_statement}/{@code if_statement}/{@code while_statement}。
     * 命中 → 进入 block 作用域处理（分支 scope 拷贝 / for 循环变量占位），不产出命令。
     * {@code case}/{@code esac} 不在集合 —— case 结构 Java 线性 walker 不建模，
     * 作普通命令产出后由 {@link #checkSemantics} SHELL_KEYWORDS 拒（fail-closed）。
     */
    private static final Set<String> BLOCK_KEYWORDS = Set.of(
            "if", "then", "elif", "else", "fi",
            "while", "until", "for", "select", "do", "done");

    /**
     * 内建 NAME 重解析 + 算术求值下标的内建 flag 映射 ·
     * CC original: SUBSCRIPT_EVAL_FLAGS（ast.ts:2143-2155）。
     */
    private static final Map<String, Set<String>> SUBSCRIPT_EVAL_FLAGS = Map.of(
            "test", Set.of("-v", "-R"),
            "[", Set.of("-v", "-R"),
            "[[", Set.of("-v", "-R"),
            "printf", Set.of("-v"),
            "read", Set.of("-a"),
            "unset", Set.of("-v"),
            "wait", Set.of("-p"));

    /** [[ 算术比较操作符 · CC original: TEST_ARITH_CMP_OPS（ast.ts:2169-2170）。 */
    private static final Set<String> TEST_ARITH_CMP_OPS =
            Set.of("-eq", "-ne", "-lt", "-le", "-gt", "-ge");

    /** 每个非 flag 位置参数都是 NAME 的内建 · CC original: BARE_SUBSCRIPT_NAME_BUILTINS（ast.ts:2182）。 */
    private static final Set<String> BARE_SUBSCRIPT_NAME_BUILTINS = Set.of("read", "unset");

    /** read 数据旗标（下一参数是数据非 NAME）· CC original: READ_DATA_FLAGS（ast.ts:2189）。 */
    private static final Set<String> READ_DATA_FLAGS = Set.of("-p", "-d", "-n", "-N", "-t", "-u", "-i");

    /** zsh 模块内建 · CC original: ZSH_DANGEROUS_BUILTINS（ast.ts:2060-2078，18 项）。 */
    private static final Set<String> ZSH_DANGEROUS_BUILTINS = Set.of(
            "zmodload", "emulate", "sysopen", "sysread", "syswrite", "sysseek",
            "zpty", "ztcp", "zsocket", "zf_rm", "zf_mv", "zf_ln", "zf_chmod",
            "zf_chown", "zf_mkdir", "zf_rmdir", "zf_chgrp");

    // ════════════════════════════════════════════════════════════════════════
    // G4-6 解析预算（fail-closed）· 对齐 CC ast.ts:444-457 + bashParser.ts PARSE_TIMEOUT_MS
    // ════════════════════════════════════════════════════════════════════════

    /** 输入长度上限 10K 字符 · CC ast.ts:446 注释（{@code (( a[0][0]... ))} ~2800 下标可触发超时）。 */
    public static final int MAX_PARSE_INPUT_LENGTH = 10_000;

    /** 解析超时 50ms · CC bashParser.ts:29 PARSE_TIMEOUT_MS = 50。 */
    public static final long PARSE_TIMEOUT_MILLIS = 50;

    /** 节点/嵌套预算 · CC bashParser.ts:32 MAX_NODES = 50_000（Java 用命令替换递归深度近似）。 */
    public static final int MAX_PARSE_DEPTH = 256;


    // ════════════════════════════════════════════════════════════════════════
    // R8-4 parseForReadOnly — tokenizer walker + 只读 allowlist (fail-closed)
    // 对齐 CC BashTool.isReadOnly (BashTool.tsx:437-442) + readOnlyValidation.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 写命令 — 命中即非只读。对齐旧 BashTool WRITE_PATTERNS 的命令名语义。
     * CC 完整判定在 bashSecurity/bashPermissions (P2 引入)。
     */
    private static final Set<String> WRITE_COMMANDS = Set.of(
        "rm", "mv", "cp", "mkdir", "touch", "chmod", "chown", "tee"
    );

    /**
     * 判断 bash 命令是否只读 — 对齐 CC {@code BashTool.isReadOnly}
     * (BashTool.tsx:437-442): {@code checkReadOnlyConstraints(input) === 'allow'}。
     *
     * <p>CC 语义 (readOnlyValidation.ts checkReadOnlyConstraints:1876+): 命令可解析 +
     * 安全检测通过 + <b>所有子命令</b>只读 → allow; 否则 passthrough →
     * {@code isReadOnly=false}。
     *
     * <p>P1 简化 (7 helper pathValidation/sedValidation/bashPermissions 等移 P2):
     * tokenizer walker + 共享表命令名层 (ReadOnlyCommandTable.lookupBashCommandName,
     * A7b 搬迁自旧私有 READONLY_COMMANDS), 不做 CC 40+ 命令 flag 级校验。
     * <b>fail-closed (Pattern #11)</b> — 任何不确定 → 非只读:
     * <ul>
     *   <li>process/command substitution (本质执行任意命令) → false</li>
     *   <li>tokenize 失败 → false (CC tryParseShellCommand fail → passthrough)</li>
     *   <li>未引号变量/glob 展开 ({@code $VAR} / {@code *} / {@code ?}) → false
     *       (CC containsUnquotedExpansion: 运行时展开不可静态验证)</li>
     *   <li>重定向 ({@code >} {@code >>} {@code 2>} {@code 2>&1} {@code &>}) → false
     *       (tokenizer 产 OPERATOR → 重置 command start, 后续目标 word 非 allowlist 拦截)</li>
     *   <li>首词 eval-like builtin / 写命令 → false</li>
     *   <li>首词非共享表命令名层 → false (CC: 非只读 → passthrough;
     *       ReadOnlyCommandTable.lookupBashCommandName, A7b 搬迁后单源化)</li>
     *   <li>find 含 -delete/-exec/-ok → false (CC flag 校验, P2 精确化)</li>
     *   <li>全部通过 → true</li>
     * </ul>
     *
     * <p>已知 P1 保守偏离 (记 J.md E 节):
     * <ul>
     *   <li>{@code cmd 2>&1} 结尾 stderr 重定向 CC 允许 (isCommandReadOnly 剥离) — Java 判非只读</li>
     *   <li>{@code VAR=val cmd} 前缀赋值 CC 剥离 — Java firstWordOf 取 "VAR" 非 allowlist → false</li>
     *   <li>git 只读子命令 (status/log/diff) CC GIT_READ_ONLY_COMMANDS flag 校验 — Java 一律非只读</li>
     *   <li>{@code ls *.java} glob CC 同 fail-closed false — Java 一致</li>
     * </ul>
     *
     * @param input bash 命令字符串
     * @return true = 只读 (可并发安全执行); false = 非只读 (fail-closed 保守)
     */
    public static boolean parseForReadOnly(String input) {
        if (input == null || input.isBlank()) return true;  // 空命令无写操作

        // fail-closed: substitution 本质执行任意命令 → 非只读
        if (containsProcessSubstitution(input) || containsCommandSubstitution(input)) {
            log.debug("BashParser.parseForReadOnly: substitution detected, not read-only: {}",
                abbreviate(input, 100));
            return false;
        }

        // fail-closed: 未引号变量/glob 展开运行时无法静态验证 (CC containsUnquotedExpansion)
        if (containsUnquotedExpansion(input)) {
            log.debug("BashParser.parseForReadOnly: unquoted expansion, not read-only: {}",
                abbreviate(input, 100));
            return false;
        }

        // fail-closed: tokenize 失败 → 非只读 (CC tryParseShellCommand fail → passthrough)
        List<Token> tokens;
        try {
            tokens = tokenize(input);
        } catch (Exception e) {
            log.warn("BashParser.parseForReadOnly: tokenizer failed, fail-closed: {}", e.toString());
            return false;
        }

        // 扫 token: 每个子命令的首词都必须 ∈ 只读 allowlist
        boolean atCommandStart = true;
        for (Token tok : tokens) {
            TokenKind k = tok.kind();
            switch (k) {
                case REDIRECT:
                    // 防御: tokenizer 当前全产 OPERATOR, 若未来产出 REDIRECT 同样拦截
                    log.debug("BashParser.parseForReadOnly: redirect detected, not read-only: {}",
                        abbreviate(input, 100));
                    return false;
                case OPERATOR:
                case NEWLINE:
                case EOF:
                    atCommandStart = true;
                    break;
                case WORD:
                case STRING:
                case RAW_STRING:
                case ANSI_C_STRING:
                    if (atCommandStart) {
                        String first = firstWordOf(tok.text());
                        if (first != null) {
                            if (EVAL_LIKE_BUILTINS.contains(first)
                                || WRITE_COMMANDS.contains(first)) {
                                log.debug("BashParser.parseForReadOnly: write/eval command '{}': {}",
                                    first, abbreviate(input, 100));
                                return false;
                            }
                            // find 的 -delete/-exec/-ok 执行删除/任意命令 (CC flag 校验, P2 精确化)
                            if ("find".equals(first) && containsFindWriteFlag(input)) {
                                log.debug("BashParser.parseForReadOnly: find write flag, not read-only: {}",
                                    abbreviate(input, 100));
                                return false;
                            }
                            if (!ReadOnlyCommandTable.lookupBashCommandName(first)) {
                                log.debug("BashParser.parseForReadOnly: non-readonly command '{}': {}",
                                    first, abbreviate(input, 100));
                                return false;
                            }
                        }
                        atCommandStart = false;
                    }
                    break;
                default:
                    // VARIABLE / HEREDOC_TAG / HEREDOC_BODY / COMMENT / WHITESPACE / ARITH_EXPANSION
                    break;
            }
        }
        return true;
    }

    /** find 写操作 flag — -delete/-exec/-ok 执行删除或任意命令 (CC flag allowlist P2)。 */
    private static boolean containsFindWriteFlag(String input) {
        return input.contains("-delete") || input.contains("-exec") || input.contains("-ok");
    }

    /**
     * 检测未引号变量/glob 展开 — 对齐 CC readOnlyValidation.ts containsUnquotedExpansion。
     * {@code $VAR} / {@code $(...)} / {@code *} / {@code ?} 未引号出现时运行时展开结果
     * 不可静态验证 → fail-closed 非只读 (Pattern #11)。双引号内 {@code $} 仍展开
     * (bash 语义), 同样拦截; 单引号内是字面量, 放行。
     */
    private static boolean containsUnquotedExpansion(String input) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && !inSingle) { i++; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (inSingle) continue;
            if (c == '$' || c == '*' || c == '?' || c == '[') return true;
        }
        return false;
    }

    /**
     * Detect process substitution {@code <(cmd)} or {@code >(cmd)} at top level.
     *
     * <p>public: 供 {@link com.nexusai.application.agent.permission.hook.HookMatcherEngine}
     * Bash if 内容匹配判定"命令是否可静态分析" (CC preparePermissionMatcher
     * parse 非 simple → fail-safe 运行 hook, hooks.ts:452-455).
     */
    public static boolean containsProcessSubstitution(String input) {
        boolean inSingle = false, inDouble = false;
        int depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && !inSingle) { i++; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (inSingle || inDouble) continue;
            if (c == '(' ) {
                // check prior non-space char: < or >
                int j = i - 1;
                while (j >= 0 && input.charAt(j) == ' ') j--;
                if (j >= 0 && (input.charAt(j) == '<' || input.charAt(j) == '>')) {
                    return true;
                }
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return false;
    }

    /**
     * Detect command substitution {@code $(cmd)} or backtick {@code `cmd`}
     * at top level (outside single quotes — double quotes still count since
     * bash evaluates them). Excludes {@code $((expr))} arithmetic.
     *
     * <p>public: 供 {@link com.nexusai.application.agent.permission.hook.HookMatcherEngine}
     * Bash if 内容匹配判定"命令是否可静态分析" (CC preparePermissionMatcher
     * parse 非 simple → fail-safe 运行 hook, hooks.ts:452-455).
     */
    public static boolean containsCommandSubstitution(String input) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && !inSingle) { i++; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (inSingle) continue;
            // $( ... ) — exclude $(( arithmetic
            if (c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '(') {
                // $(( is arithmetic — skip
                if (i + 2 < input.length() && input.charAt(i + 2) == '(') {
                    i += 2;
                    continue;
                }
                return true;
            }
            // backtick `cmd`
            if (c == '`') {
                return true;
            }
        }
        return false;
    }

    /** Extract first word (command name) from token text. */
    private static String firstWordOf(String text) {
        if (text == null || text.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+' || c == '.'
                || c == '/' || c == ':' || c == '@' || c == '%') {
                sb.append(c);
            } else {
                break;
            }
        }
        String w = sb.toString();
        return w.isEmpty() ? null : w;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ════════════════════════════════════════════════════════════════════════
    // R8-3 extractCommandArguments + extractEnvVars (对齐 TS parser.ts 230 行)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Extract command arguments from first command in input.
     * Returns arguments after the first command word, preserving quote context.
     *
     * @param input bash command string
     * @return list of argument strings (may be empty), in order
     */
    public static List<String> extractCommandArguments(String input) {
        if (input == null) return List.of();
        List<Token> tokens = tokenize(input);
        List<String> args = new ArrayList<>();
        boolean pastCommand = false;
        for (Token tok : tokens) {
            TokenKind k = tok.kind();
            if (!pastCommand) {
                if (k == TokenKind.WORD || k == TokenKind.STRING || k == TokenKind.RAW_STRING
                    || k == TokenKind.ANSI_C_STRING) {
                    pastCommand = true;  // 跳过第一个 word (command name)
                    continue;
                }
                if (k == TokenKind.VARIABLE) continue;  // 前缀 VAR=val 里的变量
                continue;
            }
            switch (k) {
                case WORD:
                case STRING:
                case RAW_STRING:
                case ANSI_C_STRING:
                case VARIABLE:
                case COMMAND_SUBST:
                case ARITH_EXPANSION:
                    args.add(tok.text());
                    break;
                case OPERATOR:
                case NEWLINE:
                case EOF:
                    return args;
                default:
                    break;
            }
        }
        return args;
    }

    /**
     * Extract environment variable assignments from VAR=value prefixes.
     * Returns env vars like {@code FOO=bar BAZ=qux command} before first command.
     *
     * @param input bash command string
     * @return list of VAR=value strings (may be empty)
     */
    public static List<String> extractEnvVars(String input) {
        if (input == null) return List.of();
        List<Token> tokens = tokenize(input);
        List<String> envVars = new ArrayList<>();
        for (Token tok : tokens) {
            TokenKind k = tok.kind();
            if (k == TokenKind.WORD || k == TokenKind.STRING || k == TokenKind.RAW_STRING) {
                String text = tok.text();
                int eq = indexOfUnquotedEquals(text);
                if (eq > 0) {
                    String name = text.substring(0, eq);
                    if (isValidEnvVarName(name)) {
                        envVars.add(text);
                        continue;
                    }
                }
                return envVars;  // 第一个非 env 词即终止
            }
            if (k == TokenKind.VARIABLE) continue;  // skip
            if (k == TokenKind.OPERATOR || k == TokenKind.NEWLINE || k == TokenKind.EOF) {
                return envVars;
            }
        }
        return envVars;
    }

    /** Find first '=' that is not inside a quote. */
    private static int indexOfUnquotedEquals(String text) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (c == '\\' && i + 1 < text.length()) { i++; continue; }
            if (c == '=' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static boolean isValidEnvVarName(String name) {
        if (name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Token types
    // ════════════════════════════════════════════════════════════════════════

    public enum TokenKind {
        WORD,            // 普通 token (含 variable expansion)
        STRING,          // "..." (含 escape)
        RAW_STRING,      // '...' (literal)
        ANSI_C_STRING,   // $'...' (含 escape \n \t \xHH)
        VARIABLE,        // $VAR 或 ${VAR...}
        COMMAND_SUBST,   // $(cmd) 或 `cmd`
        ARITH_EXPANSION, // $((expr))
        OPERATOR,        // | || & && ; ;; ;& > < >> << 2>
        REDIRECT,        // > < >> << 2> 2>&1 &>
        HEREDOC_TAG,     // <<EOF 或 <<-EOF 或 <<'EOF'
        HEREDOC_BODY,    // heredoc 内容
        COMMENT,         // #...
        NEWLINE,         // 行分隔
        WHITESPACE,
        EOF
    }

    public record Token(TokenKind kind, String text, int line, int col, int startOffset) {
        @Override public String toString() { return kind + "(" + text + ")@" + line + ":" + col; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // State machine: character-by-character Bash tokenizer
    // ════════════════════════════════════════════════════════════════════════

    /** Tokenize 整个 Bash 脚本. 返回完整 token 流. */
    public static List<Token> tokenize(String input) {
        if (input == null) return List.of();
        List<Token> tokens = new ArrayList<>();
        TokenizerState s = new TokenizerState(input);
        while (s.pos < s.src.length()) {
            char c = s.src.charAt(s.pos);
            if (c == '\n') { s.emitNewline(tokens); continue; }
            if (BashWhitespace.isBashWhitespace(c)) { s.skipWhitespace(); continue; }
            if (c == '#' && !s.inQuote()) { s.skipComment(tokens); continue; }
            if (s.tryOperator(tokens, c)) continue;
            if (s.tryHeredoc(tokens, c)) continue;
            s.readWord(tokens);
        }
        tokens.add(new Token(TokenKind.EOF, "", s.line, s.col, s.pos));
        return tokens;
    }

    /**
     * 对齐 CC {@code parseForSecurity} (ast.ts:379-392) → {@code BashTool.preparePermissionMatcher}
     * (BashTool.tsx:445-468) 的静态分析拆分结果.
     *
     * <p>三字段对应 CC 三态结果:
     * <ul>
     *   <li>{@code commands}: 外层子命令 (CC SimpleCommand.argv.join(' '); heredoc body 不含;
     *       双引号/赋值内命令替换以占位符 {@code $(...)} 保留原位置, 对齐 CC CMDSUB_PLACEHOLDER)</li>
     *   <li>{@code innerCommands}: 双引号字符串 / {@code VAR=} 赋值内命令替换提取的内层命令
     *       (CC collectCommandSubstitution ast.ts:1374-1397 → 独立 subcommand 逐条匹配;
     *       递归拆分, 内层 too-complex 向上传播 failSafe)</li>
     *   <li>{@code failSafe}: CC kind != 'simple' → preparePermissionMatcher 返回 {@code () => true}
     *       (BashTool.tsx:451-455). 触发条件 (DIV-1..4 对齐):
     *       <ul>
     *         <li>非引号定界 heredoc {@code <<EOF} — body 会 shell 展开 (DIV-2,
     *             ast.ts:1176-1182 walkHeredocRedirect)</li>
     *         <li>定界符同行后跟任何结构 ({@code &&}/pipeline/word) — heredoc_redirect
     *             子节点非结构 token fail-closed (DIV-3, ast.ts:1158-1168)</li>
     *         <li>裸 {@code $(...)} / {@code `...`} 参数位 / 进程替换 — walkCommand/walkArgument
     *             无对应 case → default tooComplex (DIV-4, ast.ts:1282-1290/1481-1490)</li>
     *         <li>内层命令替换递归传播的 too-complex (嵌套 {@code $(echo $(date))} 等)</li>
     *       </ul></li>
     * </ul>
     */
    public record SplitForSecurity(List<String> commands, List<String> innerCommands, boolean failSafe) {}

    /** Split commands at top-level (忽略 quoted/heredoc/subshell 内容). 兼容既有调用方. */
    public static List<String> splitCommands(String input) {
        return splitForSecurity(input).commands();
    }

    /**
     * 静态分析拆分 · 对齐 CC parseForSecurity (ast.ts:379-392).
     *
     * <p>FIX-EX-A (DIV-1..4): heredoc body 行不得作为独立子命令参与匹配 (DIV-1, body 是 stdin
     * 字面量); 非引号定界 heredoc → failSafe (DIV-2); 定界符同行后跟结构 → failSafe (DIV-3);
     * 裸命令替换参数位 → failSafe, 双引号/赋值内 → 内层提取 (DIV-4).
     */
    public static SplitForSecurity splitForSecurity(String input) {
        if (input == null || input.isBlank()) return new SplitForSecurity(List.of(), List.of(), false);
        List<String> result = new ArrayList<>();
        List<String> innerCommands = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0; // subshell / brace nesting
        boolean inSingle = false, inDouble = false;
        // [REF-A] 双引号段字面跟踪 · 对齐 CC walkString solo-placeholder 拒绝
        //   (ast.ts:1633-1637): `"$(cmd)"` / `"$VAR"` 段内只有动态占位无字面内容 →
        //   tooComplex → fail-safe（占位符会绕过下游路径校验）；`"prefix: $(cmd)"`
        //   有字面 → 允许内层提取。段开始重置, 段内普通字符置 hasLiteral,
        //   $() 展开置 sawDynamic, 段结束判定。
        boolean dqHasLiteral = false, dqSawDynamic = false;
        boolean failSafe = false;
        String heredocTag = null;            // 非 null → 正在 heredoc body 扫描
        StringBuilder heredocLine = new StringBuilder(); // 当前 body 行 (不含换行)
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            // heredoc body: 不参与匹配 (DIV-1), 持续到终结符行 (独立行恰为 tag)
            if (heredocTag != null) {
                if (c == '\n') {
                    if (heredocLine.toString().trim().equals(heredocTag)) {
                        result.add(buf.toString().trim());
                        buf.setLength(0);
                        heredocTag = null;
                    }
                    heredocLine.setLength(0);
                } else {
                    heredocLine.append(c);
                }
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < input.length() && !inSingle) {
                buf.append(c).append(input.charAt(++i));
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; buf.append(c); i++; continue; }
            if (c == '"' && !inSingle) {
                if (inDouble) {
                    // [REF-A] 段结束: 只有动态占位无字面 → too-complex → fail-safe (CC ast.ts:1633-1637)
                    if (dqSawDynamic && !dqHasLiteral) {
                        failSafe = true;
                    }
                    dqHasLiteral = false;
                    dqSawDynamic = false;
                }
                inDouble = !inDouble;
                buf.append(c);
                i++;
                continue;
            }
            if (c == '(' && !inSingle) { depth++; buf.append(c); i++; continue; }
            if (c == ')' && !inSingle && depth > 0) { depth--; buf.append(c); i++; continue; }
            if (c == '{' && !inSingle) { depth++; buf.append(c); i++; continue; }
            if (c == '}' && !inSingle && depth > 0) { depth--; buf.append(c); i++; continue; }
            // 行尾 & 触发后台
            if (c == '&' && i + 1 < input.length() && input.charAt(i + 1) != '&') {
                buf.append(c);
                result.add(buf.toString().trim());
                buf.setLength(0);
                i++;
                continue;
            }
            // 分隔符 ; | &
            if ((c == ';' || c == '|' || c == '&') && depth == 0 && !inSingle && !inDouble) {
                buf.append(c);
                result.add(buf.toString().trim());
                buf.setLength(0);
                i++;
                continue;
            }
            // 命令替换 $(...) — CC: 裸参数位 → too-complex fail-safe; 双引号/赋值内 → 内层提取
            if (c == '$' && !inSingle && i + 1 < input.length() && input.charAt(i + 1) == '(') {
                if (i + 2 < input.length() && input.charAt(i + 2) == '(') {
                    // $(( 算术展开 — 非命令替换
                    buf.append(c);
                    i++;
                    continue;
                }
                int end = findMatchingParenQuoted(input, i + 1);
                if (end > 0) {
                    String inner = input.substring(i + 2, end);
                    if (inDouble || isAssignmentContext(input, i)) {
                        // 双引号/赋值内 $() → 内层提取 (CC walkString ast.ts:1561-1578 /
                        // walkVariableAssignment ast.ts:1796-1804), 外层 argv 占位
                        if (inDouble) {
                            dqSawDynamic = true;
                        }
                        SplitForSecurity innerSplit = splitForSecurity(inner);
                        if (innerSplit.failSafe) failSafe = true;
                        innerCommands.addAll(innerSplit.commands);
                        innerCommands.addAll(innerSplit.innerCommands);
                        buf.append("$(...)");
                    } else {
                        // 裸 $() 参数位: CC walkCommand default → tooComplex (ast.ts:1282-1290)
                        failSafe = true;
                    }
                    i = end + 1;
                    continue;
                }
                // 未闭合 $( — CC tree-sitter ERROR → too-complex → fail-safe
                failSafe = true;
                i++;
                continue;
            }
            // 反引号 `...` — 同 $(): 裸 → fail-safe; 双引号内 → 内层提取
            if (c == '`' && !inSingle) {
                int close = findClosingBacktick(input, i + 1);
                if (close > 0) {
                    String inner = input.substring(i + 1, close);
                    if (inDouble) {
                        SplitForSecurity innerSplit = splitForSecurity(inner);
                        if (innerSplit.failSafe) failSafe = true;
                        innerCommands.addAll(innerSplit.commands);
                        innerCommands.addAll(innerSplit.innerCommands);
                        buf.append("`...`");
                    } else {
                        failSafe = true;
                    }
                    i = close + 1;
                    continue;
                }
                failSafe = true;
                i++;
                continue;
            }
            // 进程替换 <(...)/>(...) — CC walkArgument 无 case → default tooComplex → fail-safe
            if ((c == '<' || c == '>') && !inSingle && !inDouble
                    && i + 1 < input.length() && input.charAt(i + 1) == '(') {
                failSafe = true;
                i += 2;
                continue;
            }
            // heredoc 检测
            if (c == '<' && i + 1 < input.length() && input.charAt(i + 1) == '<') {
                int j = i + 2;
                boolean stripIndent = false;
                if (j < input.length() && input.charAt(j) == '-') { stripIndent = true; j++; }
                // <<< herestring — 内容同行非 heredoc (CC herestring_redirect), 不进 body 模式
                if (j < input.length() && input.charAt(j) == '<') {
                    buf.append("<<<");
                    i = j + 1;
                    continue;
                }
                while (j < input.length() && BashWhitespace.isBashWhitespace(input.charAt(j))) j++;
                StringBuilder delim = new StringBuilder();
                while (j < input.length() && !BashWhitespace.isBashWhitespace(input.charAt(j)) && input.charAt(j) != '\n') {
                    delim.append(input.charAt(j++));
                }
                if (delim.length() > 0) {
                    String d = delim.toString();
                    // 引号定界判定 (CC walkHeredocRedirect ast.ts:1174-1182): <<'EOF' / <<"EOF" / <<\EOF
                    boolean quoted = (d.length() >= 2 && d.startsWith("'") && d.endsWith("'"))
                            || (d.length() >= 2 && d.startsWith("\"") && d.endsWith("\""))
                            || d.startsWith("\\");
                    if (!quoted) failSafe = true; // DIV-2: 非引号定界 body 展开
                    if (restOfLineHasContent(input, j)) failSafe = true; // DIV-3: 定界同行结构 fail-closed
                    buf.append("<<").append(stripIndent ? "-" : "").append(d);
                    heredocTag = stripHeredocDelim(d);
                    heredocLine.setLength(0);
                    i = j;
                    continue;
                }
            }
            // [REF-A] 双引号段内普通字符 → 字面内容 (CC walkString sawLiteralContent)
            if (inDouble && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                dqHasLiteral = true;
            }
            buf.append(c);
            i++;
        }
        if (heredocTag != null) {
            // 未终结 heredoc: body 吞至 EOF (CC 引号定界 body 至 EOF 仍 simple), 命令段 flush
            if (buf.length() > 0) result.add(buf.toString().trim());
        } else if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }
        return new SplitForSecurity(
                result.stream().filter(s -> !s.isEmpty()).toList(),
                innerCommands.stream().filter(s -> !s.isEmpty()).toList(),
                failSafe);
    }

    /**
     * checkSemantics 等价 · 对齐 CC ast.ts:2213-2679 {@code checkSemantics}（post-argv 语义检查）。
     *
     * <p>对每个子命令 .text span（含双引号/赋值内命令替换提取的内层命令）做 "dangerous by name"
     * 检查：先剥安全 wrapper（nohup/time/timeout/nice/env/stdbuf，CC :2215-2384）再取命令名，
     * 命中空命令名（CC :2395-2400）/ 片段（CC :2415-2420，{@code -}/{@code |}/{@code &} 开头）/
     * EVAL_LIKE_BUILTINS（CC :2626-2656，求值参数为代码）→ 返回失败原因；全部通过返回 null。
     *
     * <p>覆盖 CC 语义子集（完整下标/算术注入检查由 {@link BashSecurityValidator} legacy 链
     * 3.3 misparsing gate 承接，ast.ts:2048-2051 定位 "与解析无关的按名危险"）。Java 无
     * tree-sitter，subcommand span 由 {@link #splitCommandDeprecated}（干净顶层切分）+
     * {@link #splitForSecurity} innerCommands（内层命令替换）提供。
     *
     * <p>IMP-4 消费点：BashTool.checkPermissions AST 决策链 simple 分支——语义失败 →
     * checkSemanticsDeny（逐子命令 prefix deny）→ Ask(Other)。
     *
     * @param subcommands 子命令 .text span 列表（含内层命令替换提取的命令）
     * @return 首个失败原因（对齐 CC checkSemantics reason 原文）；null = 全部通过
     */
    public static String checkSemanticsFailureReason(List<String> subcommands) {
        if (subcommands == null) {
            return null;
        }
        List<ParseForSecurityResult.BashSimpleCommand> cmds = new ArrayList<>();
        for (String rawCmd : subcommands) {
            if (rawCmd == null || rawCmd.isBlank()) {
                continue;
            }
            ParseForSecurityResult.BashSimpleCommand c = cmdOfSpan(rawCmd);
            if (c.argv().isEmpty()) {
                continue;
            }
            cmds.add(c);
        }
        return checkSemantics(cmds);
    }

    /**
     * 从子命令 span 提取 argv + redirects（引号剥除、前置 env 赋值剥离；$VAR 保留字面）。
     * 供薄壳 {@link #checkSemanticsFailureReason} 转 BashSimpleCommand，使完整
     * checkSemantics 的重定向检查（NEWLINE_HASH / PROC_ENVIRON）在薄壳路径也生效。
     */
    private static ParseForSecurityResult.BashSimpleCommand cmdOfSpan(String raw) {
        List<String> argv = new ArrayList<>();
        List<ParseForSecurityResult.BashRedirect> redirects = new ArrayList<>();
        try {
            List<Token> toks = tokenize(raw);
            for (int i = 0; i < toks.size(); i++) {
                Token t = toks.get(i);
                if (t.kind() == TokenKind.WORD) {
                    argv.add(resolveWordLenient(t.text()));
                } else if (t.kind() == TokenKind.OPERATOR) {
                    String op = t.text();
                    if (op.equals(">") || op.equals(">>") || op.equals(">&")
                            || op.equals("<") || op.equals("<&") || op.equals("&>")
                            || op.equals("&>>") || op.equals(">|") || op.equals("<<<")) {
                        // 捕获重定向目标（CC checkSemantics 检查 redirects）
                        if (i + 1 < toks.size() && toks.get(i + 1).kind() == TokenKind.WORD) {
                            redirects.add(new ParseForSecurityResult.BashRedirect(
                                op, resolveWordLenient(toks.get(i + 1).text()), null));
                            i++;
                        }
                    } else {
                        break;
                    }
                } else if (t.kind() == TokenKind.NEWLINE || t.kind() == TokenKind.EOF) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            return new ParseForSecurityResult.BashSimpleCommand(List.of(), List.of(), List.of(), raw);
        }
        // 前置 env 赋值剥离（对齐 CC envVars 分离语义，BashTool.tsx:456-458）
        while (argv.size() > 1 && isEnvAssignmentWord(argv.get(0))) {
            argv.remove(0);
        }
        if (argv.size() == 1 && isEnvAssignmentWord(argv.get(0))) {
            argv.clear();
        }
        return new ParseForSecurityResult.BashSimpleCommand(
            argv, List.of(), redirects, raw);
    }

    /**
     * 宽松引号解析（不解析 $VAR/$()/${}，仅剥引号 + ANSI-C 转义）。
     * 供 {@link #cmdOfSpan} 把子命令 span 转 argv/redirects 用（checkSemantics 只需按名/内容检查）。
     */
    private static String resolveWordLenient(String text) {
        if (text.startsWith("$'") && text.length() >= 3 && text.endsWith("'")) {
            return unescapeAnsiC(text.substring(2, text.length() - 1));
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                int end = findMatchingQuote(text, i + 1, '\'');
                if (end < 0) break;
                out.append(text, i + 1, end);
                i = end + 1;
                continue;
            }
            if (c == '"') {
                int end = findMatchingQuote(text, i + 1, '"');
                if (end < 0) break;
                out.append(unescapeDoubleQuoted(text.substring(i + 1, end)));
                i = end + 1;
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isEnvAssignmentWord(String w) {
        int eq = indexOfUnquotedEquals(w);
        if (eq <= 0) return false;
        String name = w.substring(0, eq);
        if (name.endsWith("+")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    // ════════════════════════════════════════════════════════════════════════
    // G4-1/2/5/6/7/8 parseForSecurity 完整实现 · 对齐 CC ast.ts:379-2679
    // ════════════════════════════════════════════════════════════════════════

    private static final int MODE_ARG = 0;      // 裸参数位
    private static final int MODE_ASSIGN = 1;   // 赋值 RHS（$VAR 按 insideString 解析）
    private static final int MODE_REDIRECT = 2; // 重定向目标（裸 $VAR/$() 一律拒绝）

    /** 算术展开内注入字符（变量/替换/下标）· CC walkArithmetic ast.ts:1659-1701 保守近似。 */
    private static final Pattern ARITH_INJECT_RE = Pattern.compile("[_$`\\[\\]\\\\]");

    /** 纯十六进制字面 0x… · CC ARITH_LEAF_RE 的 {@code 0[xX][0-9a-fA-F]+}。 */
    private static final Pattern ARITH_HEX_RE = Pattern.compile("^0[xX][0-9a-fA-F]+$");

    /** 基编码字面 NN#… · CC ARITH_LEAF_RE 的 {@code [0-9]+#[0-9a-zA-Z]+}。 */
    private static final Pattern ARITH_BASE_RE = Pattern.compile("^[0-9]+#[0-9a-zA-Z]+$");

    /** ${VAR} 引用（PS4 allowlist 剥离用）· CC ast.ts:1895。 */
    private static final Pattern PS4_REF_RE = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}");

    /** 需要引号包裹的 shell 元字符（.text 重建用）· CC ast.ts:1353。 */
    private static final Pattern SHELL_META_RE = Pattern.compile("[\"'\\\\ \\t\\n$`;|&<>(){}\\[\\]~#]");

    private static final Set<String> REDIRECT_OPS = Set.of(
            ">", ">>", ">&", ">|", "<", "<&", "&>", "&>>", "<<<", "<&-", ">&-");

    /** 解析中止（超时/深度预算）异常 · CC parser.ts PARSE_ABORTED 等价。 */
    private static final class ParseAbortedException extends RuntimeException {
        ParseAbortedException() { super("parse aborted"); }
    }

    private record PendingAssign(String name, String value, boolean isAppend) {}

    /**
     * 静态分析 bash 命令 · 对齐 CC ast.ts:379-392 {@code parseForSecurity}。
     * 三态返回：{@code Simple}（argv/envVars/redirects 结构化命令）/ {@code TooComplex} /
     * {@code ParseUnavailable}。Fail-closed：无法静态建模的结构一律 too-complex。
     */
    public static ParseForSecurityResult parseForSecurity(String cmd) {
        if (cmd == null) {
            return new ParseForSecurityResult.ParseUnavailable();
        }
        // G4-6 长度上限（CC ast.ts:446 注释 10K limit）
        if (cmd.length() > MAX_PARSE_INPUT_LENGTH) {
            return new ParseForSecurityResult.TooComplex(
                "Parser aborted (timeout or resource limit) — possible adversarial input", "PARSE_ABORT");
        }
        // G4-8 预检查链（CC ast.ts:408-437）——先于 trim 运行（trim 会剥 Unicode 空白）
        String pre = precheck(cmd);
        if (pre != null) {
            return new ParseForSecurityResult.TooComplex(pre, null);
        }
        if (cmd.isBlank()) {
            return new ParseForSecurityResult.Simple(List.of());
        }
        long deadlineNanos = System.nanoTime() + PARSE_TIMEOUT_MILLIS * 1_000_000L;
        try {
            AstWalker walker = new AstWalker(cmd, new LinkedHashMap<>(), deadlineNanos);
            walker.walk();
            if (walker.aborted) {
                return new ParseForSecurityResult.TooComplex(
                    "Parser aborted (timeout or resource limit) — possible adversarial input", "PARSE_ABORT");
            }
            if (walker.failReason != null) {
                return new ParseForSecurityResult.TooComplex(walker.failReason, walker.failNodeType);
            }
            return new ParseForSecurityResult.Simple(walker.commands);
        } catch (ParseAbortedException e) {
            return new ParseForSecurityResult.TooComplex(
                "Parser aborted (timeout or resource limit) — possible adversarial input", "PARSE_ABORT");
        } catch (RuntimeException e) {
            // tokenizer/解析异常 → parse-unavailable（CC :388-391）
            if (log.isDebugEnabled()) {
                log.debug("BashParser.parseForSecurity: 解析异常 → parse-unavailable: {}", e.toString());
            }
            return new ParseForSecurityResult.ParseUnavailable();
        }
    }

    /** G4-8 预检查链 · 对齐 CC ast.ts:408-437。命中返回 reason，否则 null。 */
    private static String precheck(String cmd) {
        if (CONTROL_CHAR_RE.matcher(cmd).find()) {
            return "Contains control characters";
        }
        if (UNICODE_WHITESPACE_RE.matcher(cmd).find()) {
            return "Contains Unicode whitespace";
        }
        if (BACKSLASH_WHITESPACE_RE.matcher(cmd).find()) {
            return "Contains backslash-escaped whitespace";
        }
        if (ZSH_TILDE_BRACKET_RE.matcher(cmd).find()) {
            return "Contains zsh ~[ dynamic directory syntax";
        }
        if (ZSH_EQUALS_EXPANSION_RE.matcher(cmd).find()) {
            return "Contains zsh =cmd equals expansion";
        }
        if (BRACE_WITH_QUOTE_RE.matcher(maskBracesInQuotedContexts(cmd)).find()) {
            return "Contains brace with quote character (expansion obfuscation)";
        }
        return null;
    }

    /** 掩掉单/双引号上下文内的 {@code {} · CC ast.ts:331-371 {@code maskBracesInQuotedContexts}。 */
    private static String maskBracesInQuotedContexts(String cmd) {
        if (!cmd.contains("{")) return cmd;
        StringBuilder out = new StringBuilder(cmd.length());
        boolean inSingle = false, inDouble = false;
        int i = 0;
        while (i < cmd.length()) {
            char c = cmd.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                out.append(c == '{' ? ' ' : c);
                i++;
            } else if (inDouble) {
                if (c == '\\' && i + 1 < cmd.length()
                        && (cmd.charAt(i + 1) == '"' || cmd.charAt(i + 1) == '\\')) {
                    out.append(c).append(cmd.charAt(i + 1));
                    i += 2;
                } else {
                    if (c == '"') inDouble = false;
                    out.append(c == '{' ? ' ' : c);
                    i++;
                }
            } else {
                if (c == '\\' && i + 1 < cmd.length()) {
                    out.append(c).append(cmd.charAt(i + 1));
                    i += 2;
                } else {
                    if (c == '\'') inSingle = true;
                    else if (c == '"') inDouble = true;
                    out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }

    /**
     * tooComplex 文案统一生成 · 对齐 CC ast.ts:2033-2041。
     * {@code nodeType==='ERROR' ? 'Parse error' : DANGEROUS_TYPES.has(nodeType) ?
     * 'Contains '+nodeType : 'Unhandled node type: '+nodeType}。
     */
    private static String tooComplexReason(String nodeType) {
        if ("ERROR".equals(nodeType)) return "Parse error";
        if (nodeType != null && TOO_COMPLEX_REASON_TYPES.contains(nodeType)) {
            return "Contains " + nodeType;
        }
        return "Unhandled node type: " + nodeType;
    }

    /**
     * 安全 walker · 对齐 CC collectCommands/walkCommand/walkArgument 的线性等价。
     * 产出 {@code SimpleCommand} 列表 + varScope 变量追踪（G4-2）。
     *
     * <p>scope 控制流（CC ast.ts:505-563）：{@code &&}/{@code ;} 顺序共享 scope；
     * {@code ||}/{@code |}/{@code |&}/{@code &} 之后进入 reset region，赋值写入
     * {@code baseScope} 的拷贝，遇下一个 {@code &&}/{@code ;} 丢弃拷贝回退 base——
     * 防 {@code true || FLAG=--dry-run && cmd $FLAG} 旗标省略攻击。
     */
    /** G4 block 类型（P0-3）· CC ast.ts:693-880 for/if/while 分支 scope 拷贝。 */
    private enum BlockType { IF, FOR, WHILE }

    /**
     * G4 block 作用域帧（P0-3）· 对齐 CC for_statement/if_statement 分支 scope 拷贝
     * （ast.ts:693-880）。
     *
     * <p>{@code realScope} = 块结束恢复的真实 scope（含 condition 内赋值）；
     * {@code branchScope} = 当前分支工作拷贝（then/do/elif/else body 内赋值写入，
     * 不泄漏到块后 —— CC SECURITY: {@code if false; then T=safe; fi && rm $T} 必须 reject $T）。
     */
    private static final class BlockFrame {
        final BlockType type;
        Map<String, String> realScope;
        Map<String, String> branchScope;
        boolean inBody;
        String loopVar;        // FOR: 循环变量名
        boolean loopVarSet;    // FOR: 是否已捕获循环变量

        BlockFrame(BlockType type) {
            this.type = type;
        }
    }

    private static final class AstWalker {
        final String src;
        final List<Token> tokens;
        final List<ParseForSecurityResult.BashSimpleCommand> commands = new ArrayList<>();
        final Map<String, String> baseScope = new LinkedHashMap<>();
        final long deadlineNanos;
        Map<String, String> activeScope;
        Map<String, String> resetAnchor;
        final Deque<BlockFrame> blockStack = new ArrayDeque<>();
        boolean inResetRegion;
        int depth;
        String failReason;
        String failNodeType;
        boolean aborted;

        AstWalker(String src, Map<String, String> inherited, long deadlineNanos) {
            this.src = src;
            this.tokens = tokenize(src);
            this.baseScope.putAll(inherited);
            this.activeScope = this.baseScope;
            this.resetAnchor = this.baseScope;
            this.deadlineNanos = deadlineNanos;
        }

        void checkBudget() {
            if (System.nanoTime() > deadlineNanos) {
                aborted = true;
                throw new ParseAbortedException();
            }
        }

        void fail(String reason, String nodeType) {
            if (failReason == null) {
                failReason = reason;
                failNodeType = nodeType;
            }
        }

        /**
         * 设置当前"永久"scope（activeScope + resetAnchor）· P0-3。
         * resetAnchor 供 reset-region（|| / | 后）作为回退基（对齐 CC 控制流 scope 拷贝
         * ast.ts:505-563 的 baseScope，但以当前 block 上下文为准 —— 分支体内 reset 不回退顶层）。
         */
        void setPermanentScope(Map<String, String> scope) {
            this.activeScope = scope;
            this.resetAnchor = scope;
        }

        /**
         * G4 block 关键字处理 · 对齐 CC ast.ts:693-880 for/if/while 分支 scope 拷贝。
         *
         * <p>仅当关键字是<b>段首命令词</b>时被调用（walker WORD case 先于 seenCommandWord 判定）：
         * <ul>
         *   <li>{@code if}/{@code while}/{@code until} → push IF 帧（realScope = 当前 scope 拷贝）；</li>
         *   <li>{@code for}/{@code select} → push FOR 帧（循环变量下一词捕获）；</li>
         *   <li>{@code then}/{@code do} → 进入 body：branchScope = realScope 拷贝，body 内赋值写拷贝；</li>
         *   <li>{@code elif}/{@code else} → 分支切换：activeScope = realScope 拷贝（不泄漏上分支）；</li>
         *   <li>{@code fi}/{@code done} → 恢复 realScope + 弹出帧。</li>
         * </ul>
         */
        void handleBlockKeyword(String kw) {
            BlockFrame top = blockStack.peek();
            switch (kw) {
                case "if", "until" -> {
                    BlockFrame f = new BlockFrame(BlockType.IF);
                    f.realScope = new LinkedHashMap<>(activeScope);
                    blockStack.push(f);
                }
                case "while" -> {
                    // G4-WHILE-READ（遗留项2）：while 与 if 分开建模（BlockType.WHILE），
                    // 以便条件阶段 flush 出 `read VAR` 命令时做循环变量 scope 化追踪
                    // （CC ast.ts:764-880 while_statement 处理）。until 保持 IF（CC 无
                    // until_statement 处理 → too-complex，fail-closed 一致）。
                    BlockFrame f = new BlockFrame(BlockType.WHILE);
                    f.realScope = new LinkedHashMap<>(activeScope);
                    blockStack.push(f);
                }
                case "for", "select" -> {
                    BlockFrame f = new BlockFrame(BlockType.FOR);
                    f.realScope = new LinkedHashMap<>(activeScope);
                    blockStack.push(f);
                }
                case "then" -> {
                    if (top != null && top.type == BlockType.IF && !top.inBody) {
                        top.inBody = true;
                        top.realScope = activeScope;   // condition 用真实 scope（赋值无条件持久）
                        top.branchScope = new LinkedHashMap<>(activeScope);
                        setPermanentScope(top.branchScope);
                    }
                }
                case "do" -> {
                    if (top != null && !top.inBody) {
                        top.inBody = true;
                        top.realScope = activeScope;
                        top.branchScope = new LinkedHashMap<>(activeScope);
                        setPermanentScope(top.branchScope);
                    }
                }
                case "elif" -> {
                    if (top != null && top.type == BlockType.IF && top.inBody) {
                        top.inBody = false;
                        // CC elif_clause：branchScope = new Map(varScope)（条件+body 同一拷贝）
                        Map<String, String> elifScope = new LinkedHashMap<>(top.realScope);
                        top.branchScope = elifScope;
                        setPermanentScope(elifScope);
                    }
                }
                case "else" -> {
                    if (top != null && top.type == BlockType.IF && top.inBody) {
                        // CC else_clause：branchScope = new Map(varScope)
                        Map<String, String> elseScope = new LinkedHashMap<>(top.realScope);
                        top.branchScope = elseScope;
                        setPermanentScope(elseScope);
                    }
                }
                case "fi", "done" -> {
                    if (top != null) {
                        setPermanentScope(top.realScope);
                        blockStack.pop();
                    }
                }
                default -> { /* 不可达（BLOCK_KEYWORDS 白名单） */ }
            }
        }

        void walk() {
            List<String> argv = new ArrayList<>();
            List<ParseForSecurityResult.BashEnvVar> envVars = new ArrayList<>();
            List<ParseForSecurityResult.BashRedirect> redirects = new ArrayList<>();
            List<PendingAssign> pending = new ArrayList<>();
            List<String> segRaw = new ArrayList<>();
            boolean seenCommandWord = false;
            String declMode = null;
            String fdWord = null;

            int n = tokens.size();
            for (int i = 0; i < n && failReason == null; i++) {
                checkBudget();
                Token t = tokens.get(i);
                switch (t.kind()) {
                    case WORD -> {
                        String text = t.text();
                        // fd 前缀：纯数字 + 紧邻重定向操作符（2>file / 2>&1）
                        if (text.matches("[0-9]+") && isAdjacentRedirect(t, i)) {
                            fdWord = text;
                            break;
                        }
                        if (declMode != null) {
                            handleDeclarationWord(text, argv);
                            if (failReason != null) return;
                            segRaw.add(text);
                            break;
                        }
                        if (!seenCommandWord && isAssignmentWord(text)) {
                            PendingAssign pa = parseAssignmentWord(text);
                            if (pa == null) return;
                            pending.add(pa);
                            segRaw.add(text);
                            break;
                        }
                        // P0-3: negated_command `! cmd` —— 递归剥前导 `!`（仅段首独立 `!` token，
                        //   对齐 CC ast.ts:567-577）。不产出 argv 项也不进 segRaw（.text = 内层命令 span，
                        //   与 CC SimpleCommand.text 一致），裸 `!` 命令无实际命令 → 视为空段。
                        if (!seenCommandWord && argv.isEmpty() && text.equals("!")) {
                            break;
                        }
                        // P0-3: block 结构关键字（仅段首命令词）→ 分支 scope 拷贝处理（CC ast.ts:693-880）
                        if (!seenCommandWord && argv.isEmpty() && BLOCK_KEYWORDS.contains(text)) {
                            handleBlockKeyword(text);
                            if (failReason != null) return;
                            break;
                        }
                        // P0-3: for/select 循环变量捕获（段首 `for` 后的下一词）· CC ast.ts:693-753
                        BlockFrame topFrame = blockStack.peek();
                        if (topFrame != null && topFrame.type == BlockType.FOR
                                && !topFrame.loopVarSet && !seenCommandWord && argv.isEmpty()) {
                            if (!text.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                                fail("for loop variable must be a bare identifier: " + text, "for_statement");
                                return;
                            }
                            if (text.equals("PS4") || text.equals("IFS")) {
                                fail(text + " as loop variable bypasses assignment validation", "for_statement");
                                return;
                            }
                            // 循环变量恒 VAR_PLACEHOLDER（值运行时未知，裸 $i → too-complex，
                            //   字符串嵌入放行）· CC ast.ts:697-753
                            topFrame.loopVar = text;
                            topFrame.loopVarSet = true;
                            activeScope.put(text, VAR_PLACEHOLDER);
                            segRaw.add(text);
                            break;
                        }
                        seenCommandWord = true;
                        if (argv.isEmpty() && DECLARATION_KEYWORDS.contains(text)) {
                            declMode = text;
                            argv.add(text);
                        } else if (argv.isEmpty() && (text.startsWith("(") || text.equals("{"))) {
                            // subshell / 组命令出现在命令位 → fail-closed（CC walkCommand 无此 case）
                            fail(tooComplexReason("subshell"), "subshell");
                            return;
                        } else {
                            String resolved = resolveWord(text, MODE_ARG);
                            if (resolved == null) return;
                            argv.add(resolved);
                        }
                        // P0-3: unset 删除 varScope（CC ast.ts:937-938 unset_command varScope.delete）——
                        //   `VAR=safe && unset VAR && rm $VAR` 必须拒绝解析 $VAR（后续引用 too-complex）。
                        //   对齐 CC 实际行为（遗留项2，主 agent 裁决）：bashParser.ts:3674-3678 parseUnset
                        //   对每个非 `-` 开头操作数统一包成 variable_name（不论 -f/-v/无旗标），
                        //   ast.ts:937-938 对 variable_name 一律 varScope.delete —— 即 `unset -f func` 也删
                        //   varScope（CC 比 bash 运行语义更严，但以 CC 真源为准）。
                        if (argv.size() >= 2 && "unset".equals(argv.get(0))
                                && text.matches("[A-Za-z_][A-Za-z0-9_]*")
                                && !text.startsWith("-") && !text.contains("=")) {
                            activeScope.remove(text);
                            if (log.isDebugEnabled()) {
                                log.debug("BashParser.walk: unset 删除 varScope 变量 {}", text);
                            }
                        }
                        segRaw.add(text);
                    }
                    case OPERATOR -> {
                        String op = t.text();
                        // `&>`/`&>>` 由 tokenizer 拆成 `&` + `>`，此处合并（CC REDIRECT_OPS ast.ts:224-234）
                        if (op.equals("&") && i + 1 < n && isAdjacent(t, tokens.get(i + 1))
                                && (tokens.get(i + 1).text().equals(">") || tokens.get(i + 1).text().equals(">>"))) {
                            String combined = "&" + tokens.get(i + 1).text();
                            int consumed = handleRedirect(combined, fdWord, redirects, i + 1);
                            if (failReason != null) return;
                            if (consumed > 0) i = consumed;
                            segRaw.add(combined);
                            fdWord = null;
                            break;
                        }
                        if (isRedirectOp(op)) {
                            int consumed = handleRedirect(op, fdWord, redirects, i);
                            if (failReason != null) return;
                            if (consumed > 0) i = consumed;
                            segRaw.add(op);
                            fdWord = null;
                            break;
                        }
                        if (op.equals("&&") || op.equals("||") || op.equals("|") || op.equals("|&")
                                || op.equals("&") || op.equals(";") || op.equals(";;")
                                || op.equals(";&") || op.equals(";;&")) {
                            flushSegment(argv, envVars, redirects, pending, segRaw, seenCommandWord);
                            if (failReason != null) return;
                            if (op.equals("||") || op.equals("|") || op.equals("|&") || op.equals("&")) {
                                // P0-3: reset 回退基 = 当前 block 上下文的永久 scope（非顶层 baseScope），
                                //   分支体内 || 不把 scope 重置回顶层（CC ast.ts:505-563 同语义）
                                activeScope = new LinkedHashMap<>(resetAnchor);
                                inResetRegion = true;
                            } else if (inResetRegion) {
                                // && / ; 结束 reset region：丢弃 reset copy（CC ast.ts:505-563）
                                activeScope = resetAnchor;
                                inResetRegion = false;
                            }
                            argv = new ArrayList<>();
                            envVars = new ArrayList<>();
                            redirects = new ArrayList<>();
                            pending = new ArrayList<>();
                            segRaw = new ArrayList<>();
                            seenCommandWord = false;
                            declMode = null;
                            fdWord = null;
                        } else {
                            fail("Unhandled node type: " + op, op);
                            return;
                        }
                    }
                    case HEREDOC_TAG -> {
                        if (!handleHeredoc(t)) return;
                    }
                    case HEREDOC_BODY, COMMENT -> {
                        // heredoc body 由 handleHeredoc 校验（引号定界）；注释跳过
                    }
                    case NEWLINE, EOF -> {
                        flushSegment(argv, envVars, redirects, pending, segRaw, seenCommandWord);
                        if (failReason != null) return;
                        if (inResetRegion) {
                            activeScope = resetAnchor;
                            inResetRegion = false;
                        }
                        argv = new ArrayList<>();
                        envVars = new ArrayList<>();
                        redirects = new ArrayList<>();
                        pending = new ArrayList<>();
                        segRaw = new ArrayList<>();
                        seenCommandWord = false;
                        declMode = null;
                        fdWord = null;
                    }
                    default -> {
                        // WHITESPACE 等
                    }
                }
            }
            flushSegment(argv, envVars, redirects, pending, segRaw, seenCommandWord);
        }

        boolean isAdjacent(Token a, Token b) {
            return a.startOffset() + a.text().length() == b.startOffset();
        }

        boolean isRedirectOp(String op) {
            return REDIRECT_OPS.contains(op);
        }

        boolean isAdjacentRedirect(Token wordTok, int idx) {
            if (idx + 1 >= tokens.size()) return false;
            Token next = tokens.get(idx + 1);
            return next.kind() == TokenKind.OPERATOR
                    && isAdjacent(wordTok, next)
                    && isRedirectOp(next.text());
        }

        void flushSegment(List<String> argv, List<ParseForSecurityResult.BashEnvVar> envVars,
                          List<ParseForSecurityResult.BashRedirect> redirects,
                          List<PendingAssign> pending, List<String> segRaw,
                          boolean seenCommandWord) {
            if (failReason != null) return;
            // P0-3: for/select 头部（for i in a b）不产出命令（CC for_statement 不 push header
            //   command，ast.ts:693-753）；头部词已逐词校验（resolveWord 失败 → failReason）。
            BlockFrame topFrame = blockStack.peek();
            boolean inForHeader = topFrame != null && topFrame.type == BlockType.FOR
                    && !topFrame.inBody;
            if (inForHeader) {
                return;
            }
            if (!seenCommandWord) {
                // 纯赋值段（无命令）：按 bare variable_assignment 应用 scope（CC ast.ts:678-691）
                for (PendingAssign pa : pending) {
                    applyVarToScope(pa.name(), pa.value(), pa.isAppend());
                }
                return;
            }
            // 前置赋值归 envVars（命令局部，不进 varScope —— CC walkCommand ast.ts:1251-1259）
            for (PendingAssign pa : pending) {
                envVars.add(new ParseForSecurityResult.BashEnvVar(pa.name(), pa.value()));
            }
            String text = buildText(argv, segRaw);
            commands.add(new ParseForSecurityResult.BashSimpleCommand(
                new ArrayList<>(argv), new ArrayList<>(envVars), new ArrayList<>(redirects), text));
            // G4-WHILE-READ（遗留项2）· 对齐 CC ast.ts:839-877：while 条件中的 `read VAR`
            //   把循环变量以 VAR_PLACEHOLDER 追踪到 realScope（do 处 body 分支拷贝继承；
            //   每迭代 read 重赋值 → 循环体内 $VAR 按循环变量处理，不误判 too-complex）。
            //   fail-closed：变量已有字面值时（read 可能未执行，如 `true || read VAR`）→ too-complex。
            if (topFrame != null && topFrame.type == BlockType.WHILE
                    && !topFrame.inBody && argv.size() >= 2
                    && "read".equals(argv.get(0))) {
                trackWhileReadVars(argv);
                if (failReason != null) return;
            }
        }

        /**
         * G4-WHILE-READ · 对齐 CC ast.ts:839-877（while_statement 条件 read 变量追踪）。
         *
         * <p>{@code while read line; do echo "$line"; done} —— read 每迭代从 stdin 重赋值
         * line，body 内 {@code $line} 按循环变量（VAR_PLACEHOLDER，值运行时不详）处理：
         * 字符串嵌入放行、裸参数 fail-closed too-complex。
         *
         * <p>fail-closed 守卫（CC ast.ts:856-871）：变量已有被追踪字面值时（read 可能未执行，
         * 如 {@code true || read VAR} / 管道 / 子壳），覆盖成占位符会隐藏路径穿越 →
         * 返回 too-complex（nodeType='if_statement'，对齐 CC ast.ts:869）。
         *
         * @param argv 刚 flush 的 read 命令 argv（argv[0]="read"）
         */
        void trackWhileReadVars(List<String> argv) {
            for (int i = 1; i < argv.size(); i++) {
                String a = argv.get(i);
                // 跳过旗标（-r / -d 等）；CC ast.ts:847 同语义
                if (a.startsWith("-")) continue;
                if (!a.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                String existing = activeScope.get(a);
                if (existing != null && !containsAnyPlaceholder(existing)) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashParser.trackWhileReadVars: while 条件 read 覆盖已追踪字面值 {} → fail-closed too-complex", a);
                    }
                    fail("'read " + a + "' in condition may not execute (||/pipeline/subshell); "
                            + "cannot prove it overwrites tracked literal '" + existing + "'", "if_statement");
                    return;
                }
                activeScope.put(a, VAR_PLACEHOLDER);
                if (log.isDebugEnabled()) {
                    log.debug("BashParser.trackWhileReadVars: while 条件 read 循环变量 {} 追踪为 VAR_PLACEHOLDER", a);
                }
            }
        }

        String buildText(List<String> argv, List<String> segRaw) {
            String joined = String.join(" ", segRaw);
            // $VAR 已解析或含换行 → 从 argv 重建（CC ast.ts:1349-1358）
            if (joined.indexOf('$') >= 0 || joined.indexOf('\n') >= 0) {
                List<String> quoted = new ArrayList<>();
                for (String a : argv) {
                    if (a.isEmpty() || SHELL_META_RE.matcher(a).find()) {
                        quoted.add("'" + a.replace("'", "'\\''") + "'");
                    } else {
                        quoted.add(a);
                    }
                }
                return String.join(" ", quoted);
            }
            return joined;
        }

        int handleRedirect(String op, String fdWord, List<ParseForSecurityResult.BashRedirect> redirects, int idx) {
            Integer fd = fdWord != null ? Integer.valueOf(fdWord) : null;
            if (op.equals("<&-") || op.equals(">&-")) {
                return -1; // 关闭 fd 无目标
            }
            int j = idx + 1;
            while (j < tokens.size() && tokens.get(j).kind() == TokenKind.COMMENT) j++;
            if (j >= tokens.size() || tokens.get(j).kind() != TokenKind.WORD) {
                fail("Unhandled node type: redirect", "redirect");
                return -1;
            }
            Token targetTok = tokens.get(j);
            String targetText = targetTok.text();
            // 进程替换 <(cmd) / >(cmd)（CC walkArgument 无 case → tooComplex, ast.ts:1481-1486）
            if ((op.equals("<") || op.equals(">")) && targetText.startsWith("(")) {
                fail(tooComplexReason("process_substitution"), "process_substitution");
                return -1;
            }
            // G4-7 NN# 算术基：10#$(cmd) 藏命令替换（CC ast.ts:1428-1442 / 1087-1091）
            if (NN_NUMBER_PREFIX_RE.matcher(targetText).find()
                    && (targetText.contains("$") || targetText.contains("`"))) {
                fail("Number node contains expansion (NN# arithmetic base syntax)", "number");
                return -1;
            }
            if (op.equals("<<<")) {
                // herestring 内容（CC walkHerestringRedirect ast.ts:1211-1230）：必须字面，不入 redirects
                String content = resolveWord(targetText, MODE_REDIRECT);
                if (content == null) return -1;
                return j;
            }
            String target = resolveWord(targetText, MODE_REDIRECT);
            if (target == null) return -1;
            redirects.add(new ParseForSecurityResult.BashRedirect(op, target, fd));
            return j;
        }

        boolean handleHeredoc(Token tagTok) {
            String tag = tagTok.text();
            int tagOffset = tagTok.startOffset();
            // [P0-1 修复] tokenizer 把定界符引号剥掉且 HEREDOC_TAG.startOffset 指向 `<<` —— 旧
            //   isQuoteBefore（查 << 前字符）对 `cat <<'EOF'` 恒 false → 引号定界 heredoc 被误判为
            //   非引号定界 → too-complex → preparePermissionMatcher fail-safe over-fire（body 命中误报）。
            //   改为从 `<<` 后扫描定界符本身判定引号（'EOF' / "EOF" / \EOF），对齐 CC ast.ts:1176-1182。
            boolean quoted = isHeredocDelimiterQuoted(src, tagOffset);
            if (!quoted) {
                // 非引号定界 heredoc body 会 shell 展开（CC ast.ts:1176-1182）
                fail("Heredoc with unquoted delimiter undergoes shell expansion", "heredoc_redirect");
                return false;
            }
            // 定界符同行后跟任何结构 fail-closed（CC ast.ts:1158-1168）
            int restStart = heredocDelimiterEnd(src, tagOffset);
            if (restOfLineHasContent(src, restStart)) {
                fail("Unhandled node type: heredoc_redirect", "heredoc_redirect");
                return false;
            }
            return true;
        }

        void handleDeclarationWord(String text, List<String> argv) {
            // CC declaration_command ast.ts:579-676
            if (isAssignmentWord(text)) {
                PendingAssign pa = parseAssignmentWord(text);
                if (pa == null) return;
                applyVarToScope(pa.name(), pa.value(), pa.isAppend());
                argv.add(pa.name() + "=" + pa.value());
                return;
            }
            String resolved = resolveWord(text, MODE_ARG);
            if (resolved == null) return;
            String head = argv.isEmpty() ? "" : argv.get(0);
            // declare/typeset/local flag 改变赋值语义（CC ast.ts:623-654）
            if (head.equals("declare") || head.equals("typeset") || head.equals("local")) {
                if (resolved.matches("^-[a-zA-Z]*[niaA].*")) {
                    fail("declare flag " + resolved
                            + " changes assignment semantics (nameref/integer/array)", "declaration_command");
                    return;
                }
                if (!resolved.startsWith("-") && resolved.matches("^[^=]*\\[.*")) {
                    fail("declare positional '" + resolved
                            + "' contains array subscript — bash evaluates $(cmd) in subscripts", "declaration_command");
                    return;
                }
            }
            argv.add(resolved);
        }

        PendingAssign parseAssignmentWord(String text) {
            int eq = findAssignmentEquals(text);
            if (eq < 0) return null;
            String name = text.substring(0, eq);
            boolean isAppend = false;
            if (name.endsWith("+")) {
                name = name.substring(0, name.length() - 1);
                isAppend = true;
            }
            // 无效名（bash 当命令执行）· CC ast.ts:1835-1841
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                fail("Invalid variable name (bash treats as command): " + name, "variable_assignment");
                return null;
            }
            // IFS 赋值改变词拆分 · CC ast.ts:1846-1852
            if (name.equals("IFS")) {
                fail("IFS assignment changes word-splitting — cannot model statically", "variable_assignment");
                return null;
            }
            String valuePart = text.substring(eq + 1);
            String value = resolveWord(valuePart, MODE_ASSIGN);
            if (value == null) return null;
            // PS4 allowlist · CC ast.ts:1878-1906
            if (name.equals("PS4")) {
                if (isAppend) {
                    fail("PS4 += cannot be statically verified — combine into a single PS4= assignment", "variable_assignment");
                    return null;
                }
                if (containsAnyPlaceholder(value)) {
                    fail("PS4 value derived from cmdsub/variable — runtime unknowable", "variable_assignment");
                    return null;
                }
                String stripped = PS4_REF_RE.matcher(value).replaceAll("");
                if (!stripped.matches("[A-Za-z0-9 _+:./=[\\]-]*")) {
                    fail("PS4 value outside safe charset — only ${VAR} refs and [A-Za-z0-9 _+:.=/[]-] allowed", "variable_assignment");
                    return null;
                }
            }
            // tilde 在赋值 RHS 会展开 · CC ast.ts:1914-1920
            if (value.contains("~")) {
                fail("Tilde in assignment value — bash may expand at assignment time", "variable_assignment");
                return null;
            }
            return new PendingAssign(name, value, isAppend);
        }

        int findAssignmentEquals(String text) {
            boolean s = false, d = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'' && !d) { s = !s; continue; }
                if (c == '"' && !s) { d = !d; continue; }
                if (c == '\\' && i + 1 < text.length()) { i++; continue; }
                if (c == '=' && !s && !d) {
                    // `A==B` 不是赋值（CC tryParseAssignment c1 !== '='）
                    if (i + 1 < text.length() && text.charAt(i + 1) == '=') continue;
                    return i;
                }
                if (c == '+' && i + 1 < text.length() && text.charAt(i + 1) == '=' && !s && !d) {
                    return i + 1;
                }
            }
            return -1;
        }

        boolean isAssignmentWord(String text) {
            if (text.indexOf('=') < 0) return false;
            int eq = findAssignmentEquals(text);
            if (eq < 0) return false;
            String name = text.substring(0, eq);
            if (name.endsWith("+")) {
                name = name.substring(0, name.length() - 1);
            }
            return name.matches("[A-Za-z_][A-Za-z0-9_]*");
        }

        void applyVarToScope(String name, String value, boolean isAppend) {
            // CC applyVarToScope ast.ts:2017-2027：任一侧含占位 → 存 VAR_PLACEHOLDER
            String existing = activeScope.get(name);
            String combined = isAppend ? (existing == null ? "" : existing) + value : value;
            activeScope.put(name, containsAnyPlaceholder(combined) ? VAR_PLACEHOLDER : combined);
        }

        String resolveSimpleExpansion(String varName, boolean isSpecial, boolean insideString, int mode) {
            // 重定向目标裸 $VAR 一律拒绝（CC walkFileRedirect 无 simple_expansion case, ast.ts:1086-1117）
            if (mode == MODE_REDIRECT && !insideString) {
                fail(tooComplexReason("simple_expansion"), "simple_expansion");
                return null;
            }
            String tracked = activeScope.get(varName);
            if (tracked != null) {
                // 非字面（占位）→ 裸参数拒、insideString 返回 VAR_PLACEHOLDER（CC ast.ts:1967-1973）
                if (containsAnyPlaceholder(tracked)) {
                    if (!insideString) {
                        fail(tooComplexReason("simple_expansion"), "simple_expansion");
                        return null;
                    }
                    return VAR_PLACEHOLDER;
                }
                // 裸参：空串 / IFS/glob 元字符 → 拒（CC ast.ts:1982-1992）
                if (!insideString) {
                    if (tracked.isEmpty()) {
                        fail(tooComplexReason("simple_expansion"), "simple_expansion");
                        return null;
                    }
                    if (BARE_VAR_UNSAFE_RE.matcher(tracked).find()) {
                        fail(tooComplexReason("simple_expansion"), "simple_expansion");
                        return null;
                    }
                }
                return tracked;
            }
            // SAFE_ENV_VARS + special vars 仅 insideString 放行（CC ast.ts:1995-2006）
            if (insideString) {
                if (SAFE_ENV_VARS.contains(varName)) return VAR_PLACEHOLDER;
                if (isSpecial && (SPECIAL_VAR_NAMES.contains(varName) || varName.matches("[0-9]+"))) {
                    return VAR_PLACEHOLDER;
                }
            }
            fail(tooComplexReason("simple_expansion"), "simple_expansion");
            return null;
        }

        String resolveWord(String text, int mode) {
            if (BRACE_EXPANSION_RE.matcher(text).find()) {
                fail("Word contains brace expansion syntax", "word");
                return null;
            }
            StringBuilder out = new StringBuilder();
            boolean inSingle = false, inDouble = false;
            boolean segDynamic = false, segLiteral = false;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                // 单引号（无转义）
                if (c == '\'' && !inDouble) {
                    int end = findMatchingQuote(text, i + 1, '\'');
                    if (end < 0) { fail("Unhandled node type: string", "string"); return null; }
                    out.append(text, i + 1, end);
                    segLiteral = true;
                    i = end + 1;
                    continue;
                }
                // 双引号
                if (c == '"' && !inSingle) {
                    if (inDouble) {
                        inDouble = false;
                        // solo-placeholder 字符串拒（CC ast.ts:1631-1638）
                        if (segDynamic && !segLiteral) {
                            fail("Unhandled node type: string", "string");
                            return null;
                        }
                        segDynamic = false;
                        segLiteral = false;
                    } else {
                        inDouble = true;
                        segDynamic = false;
                        segLiteral = false;
                    }
                    i++;
                    continue;
                }
                // ANSI-C $'...'
                if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    int end = findMatchingQuote(text, i + 2, '\'');
                    if (end < 0) { fail("Unhandled node type: ansi_c_string", "ansi_c_string"); return null; }
                    out.append(unescapeAnsiC(text.substring(i + 2, end)));
                    segLiteral = true;
                    i = end + 2;
                    continue;
                }
                // 反斜杠
                if (c == '\\' && i + 1 < text.length()) {
                    char nx = text.charAt(i + 1);
                    if (inDouble) {
                        if (nx == '$' || nx == '`' || nx == '"' || nx == '\\' || nx == '\n') {
                            out.append(nx);
                            segLiteral = true;
                            i += 2;
                            continue;
                        }
                        out.append(c);
                        i++;
                        continue;
                    }
                    out.append(nx);
                    segLiteral = true;
                    i += 2;
                    continue;
                }
                if (c == '$') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '(') {
                        if (i + 2 < text.length() && text.charAt(i + 2) == '(') {
                            // $(( )) 算术展开
                            int end = findMatchingParen(text, i + 1);
                            if (end < 0) { fail("Unhandled node type: arithmetic_expansion", "arithmetic_expansion"); return null; }
                            if (!walkArithmeticLenient(text, i, end)) return null;
                            out.append(text, i, end + 1);
                            segLiteral = true;
                            i = end + 1;
                            continue;
                        }
                        // $( 命令替换
                        int end = findMatchingParenQuoted(text, i + 1);
                        if (end < 0) { fail(tooComplexReason("command_substitution"), "command_substitution"); return null; }
                        String inner = text.substring(i + 2, end);
                        if (inDouble || mode == MODE_ASSIGN) {
                            if (mode == MODE_REDIRECT) {
                                fail(tooComplexReason("command_substitution"), "command_substitution");
                                return null;
                            }
                            if (!extractInner(inner)) return null;
                            out.append(CMDSUB_PLACEHOLDER);
                            segDynamic = true;
                        } else {
                            fail(tooComplexReason("command_substitution"), "command_substitution");
                            return null;
                        }
                        i = end + 1;
                        continue;
                    }
                    if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                        int end = text.indexOf('}', i + 2);
                        if (end < 0) { fail(tooComplexReason("expansion"), "expansion"); return null; }
                        String body = text.substring(i + 2, end);
                        String varName = braceVarName(body);
                        if (varName != null) {
                            String r = resolveSimpleExpansion(varName, false, inDouble, mode);
                            if (r == null) return null;
                            out.append(r);
                            if (VAR_PLACEHOLDER.equals(r)) segDynamic = true;
                            else segLiteral = true;
                        } else {
                            fail(tooComplexReason("expansion"), "expansion");
                            return null;
                        }
                        i = end + 1;
                        continue;
                    }
                    if (i + 1 < text.length() && (Character.isLetter(text.charAt(i + 1))
                            || text.charAt(i + 1) == '_')) {
                        int j = i + 2;
                        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j))
                                || text.charAt(j) == '_')) {
                            j++;
                        }
                        String varName = text.substring(i + 1, j);
                        String r = resolveSimpleExpansion(varName, false, inDouble, mode);
                        if (r == null) return null;
                        out.append(r);
                        if (VAR_PLACEHOLDER.equals(r)) segDynamic = true;
                        else segLiteral = true;
                        i = j;
                        continue;
                    }
                    if (i + 1 < text.length()) {
                        char sp = text.charAt(i + 1);
                        if ("?$!#@*-".indexOf(sp) >= 0 || Character.isDigit(sp)) {
                            String r = resolveSimpleExpansion(String.valueOf(sp), true, inDouble, mode);
                            if (r == null) return null;
                            out.append(r);
                            if (VAR_PLACEHOLDER.equals(r)) segDynamic = true;
                            else segLiteral = true;
                            i += 2;
                            continue;
                        }
                    }
                    out.append('$');
                    segLiteral = true;
                    i++;
                    continue;
                }
                if (c == '`') {
                    int close = findClosingBacktick(text, i + 1);
                    if (close < 0) { fail(tooComplexReason("command_substitution"), "command_substitution"); return null; }
                    String inner = text.substring(i + 1, close);
                    if (inDouble || mode == MODE_ASSIGN) {
                        if (mode == MODE_REDIRECT) {
                            fail(tooComplexReason("command_substitution"), "command_substitution");
                            return null;
                        }
                        if (!extractInner(inner)) return null;
                        out.append(CMDSUB_PLACEHOLDER);
                        segDynamic = true;
                    } else {
                        fail(tooComplexReason("command_substitution"), "command_substitution");
                        return null;
                    }
                    i = close + 1;
                    continue;
                }
                out.append(c);
                if (inDouble && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    segLiteral = true;
                }
                i++;
            }
            if (inSingle || inDouble) {
                fail("Unhandled node type: string", "string");
                return null;
            }
            return out.toString();
        }

        /** ${VAR} 提取变量名；复杂展开 ${x:-y} 等返回 null（CC walkArgument expansion → tooComplex）。 */
        String braceVarName(String body) {
            if (body.isEmpty()) return null;
            if (body.matches("[A-Za-z_][A-Za-z0-9_]*")) return body;
            if (body.matches("[?$!#0-9@*\\-]")) return body;
            return null;
        }

        /** $((...)) 算术校验（CC walkArithmetic ast.ts:1675-1701 保守近似：仅字面数值）。 */
        boolean walkArithmeticLenient(String text, int start, int end) {
            // end = 最外层 `)`（CC 配对）；内容在 $(( 之后、最后两个 ) 之前
            String inner = text.substring(start + 3, end - 1);
            if (ARITH_INJECT_RE.matcher(inner).find()) {
                fail("Arithmetic expansion references variable or non-literal: " + inner, "arithmetic_expansion");
                return false;
            }
            // 含字母：仅允许纯十六进制 0x… / 基编码 NN#…（否则是变量，bash 算术递归求值）
            String t = inner.trim();
            if (t.matches(".*[a-zA-Z].*")
                    && !ARITH_HEX_RE.matcher(t).matches()
                    && !ARITH_BASE_RE.matcher(t).matches()) {
                fail("Arithmetic expansion references variable or non-literal: " + inner, "arithmetic_expansion");
                return false;
            }
            return true;
        }

        /** 递归提取 $() 内层命令（CC collectCommandSubstitution ast.ts:1374-1393）。 */
        boolean extractInner(String inner) {
            depth++;
            if (depth > MAX_PARSE_DEPTH) {
                fail("Parser aborted (timeout or resource limit) — possible adversarial input", "PARSE_ABORT");
                depth--;
                return false;
            }
            try {
                Map<String, String> inherited = new LinkedHashMap<>(activeScope);
                AstWalker innerWalker = new AstWalker(inner, inherited, deadlineNanos);
                innerWalker.walk();
                if (innerWalker.aborted) {
                    fail("Parser aborted (timeout or resource limit) — possible adversarial input", "PARSE_ABORT");
                    return false;
                }
                if (innerWalker.failReason != null) {
                    fail(innerWalker.failReason, innerWalker.failNodeType);
                    return false;
                }
                commands.addAll(innerWalker.commands);
            } finally {
                depth--;
            }
            return true;
        }
    }

    /**
     * checkSemantics 完整 11 项 · 对齐 CC ast.ts:2213-2679。
     * 在 parseForSecurity 返回 simple 后运行，捕获"能 tokenize 但按名/内容危险"的命令。
     *
     * @param commands SimpleCommand 列表（parseForSecurity 产出）
     * @return 首个失败 reason；null = 全部通过
     */
    public static String checkSemantics(List<ParseForSecurityResult.BashSimpleCommand> commands) {
        if (commands == null) {
            return null;
        }
        for (ParseForSecurityResult.BashSimpleCommand cmd : commands) {
            WrapperStrip ws = stripWrappers(cmd.argv());
            if (ws.failReason() != null) {
                return ws.failReason();
            }
            List<String> a = ws.wrapped();
            String name = a.isEmpty() ? null : a.get(0);
            if (name == null) {
                continue;
            }
            // 空命令名（CC :2395-2400）
            if (name.isEmpty()) {
                return "Empty command name — argv[0] may not reflect what bash runs";
            }
            // placeholder 命令名（CC :2406-2411）
            if (name.contains(CMDSUB_PLACEHOLDER) || name.contains(VAR_PLACEHOLDER)) {
                return "Command name is runtime-determined (placeholder argv[0])";
            }
            // 片段（CC :2415-2420）
            if (name.startsWith("-") || name.startsWith("|") || name.startsWith("&")) {
                return "Command appears to be an incomplete fragment";
            }
            // SUBSCRIPT_EVAL_FLAGS 数组下标算术（CC :2428-2475）
            Set<String> dangerFlags = SUBSCRIPT_EVAL_FLAGS.get(name);
            if (dangerFlags != null) {
                for (int i = 1; i < a.size(); i++) {
                    String arg = a.get(i);
                    if (dangerFlags.contains(arg) && i + 1 < a.size() && a.get(i + 1).contains("[")) {
                        return "'" + name + " " + arg
                                + "' operand contains array subscript — bash evaluates $(cmd) in subscripts";
                    }
                    // 组合短 flag（-ra ≡ -r -a）
                    if (arg.length() > 2 && arg.charAt(0) == '-' && arg.charAt(1) != '-'
                            && !arg.contains("[")) {
                        for (String flag : dangerFlags) {
                            if (flag.length() == 2 && arg.indexOf(flag.charAt(1)) >= 0
                                    && i + 1 < a.size() && a.get(i + 1).contains("[")) {
                                return "'" + name + " " + flag + "' (combined in '" + arg
                                        + "') operand contains array subscript — bash evaluates $(cmd) in subscripts";
                            }
                        }
                    }
                    // 融合 form（-vNAME）
                    for (String flag : dangerFlags) {
                        if (flag.length() == 2 && arg.startsWith(flag) && arg.length() > 2
                                && arg.contains("[")) {
                            return "'" + name + " " + flag
                                    + "' (fused) operand contains array subscript — bash evaluates $(cmd) in subscripts";
                        }
                    }
                }
            }
            // [[ ARG OP ARG ]] 算术比较（CC :2484-2496）
            if (name.equals("[[")) {
                for (int i = 2; i < a.size(); i++) {
                    if (!TEST_ARITH_CMP_OPS.contains(a.get(i))) continue;
                    if (a.get(i - 1).contains("[")
                            || (i + 1 < a.size() && a.get(i + 1).contains("["))) {
                        return "'[[ ... " + a.get(i)
                                + " ... ]]' operand contains array subscript — bash arithmetically evaluates $(cmd) in subscripts";
                    }
                }
            }
            // read/unset 裸 NAME 数组下标（CC :2504-2540）
            if (BARE_SUBSCRIPT_NAME_BUILTINS.contains(name)) {
                boolean skipNext = false;
                for (int i = 1; i < a.size(); i++) {
                    String arg = a.get(i);
                    if (skipNext) { skipNext = false; continue; }
                    if (arg.startsWith("-")) {
                        if (name.equals("read")) {
                            if (READ_DATA_FLAGS.contains(arg)) {
                                skipNext = true;
                            } else if (arg.length() > 2 && arg.charAt(1) != '-') {
                                for (int j = 1; j < arg.length(); j++) {
                                    if (READ_DATA_FLAGS.contains("-" + arg.charAt(j))) {
                                        if (j == arg.length() - 1) skipNext = true;
                                        break;
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    if (arg.contains("[")) {
                        return "'" + name + "' positional NAME '" + arg
                                + "' contains array subscript — bash evaluates $(cmd) in subscripts";
                    }
                }
            }
            // SHELL_KEYWORDS 保留字当命令名（CC :2547-2552）
            if (SHELL_KEYWORDS.contains(name)) {
                return "Shell keyword '" + name + "' as command name — tree-sitter mis-parse";
            }
            // NEWLINE_HASH（CC :2554-2586）
            for (String arg : cmd.argv()) {
                if (arg.contains("\n") && NEWLINE_HASH_RE.matcher(arg).find()) {
                    return "Newline followed by # inside a quoted argument can hide arguments from path validation";
                }
            }
            for (ParseForSecurityResult.BashEnvVar ev : cmd.envVars()) {
                if (ev.value().contains("\n") && NEWLINE_HASH_RE.matcher(ev.value()).find()) {
                    return "Newline followed by # inside an env var value can hide arguments from path validation";
                }
            }
            for (ParseForSecurityResult.BashRedirect r : cmd.redirects()) {
                if (r.target() != null && r.target().contains("\n")
                        && NEWLINE_HASH_RE.matcher(r.target()).find()) {
                    return "Newline followed by # inside a redirect target can hide arguments from path validation";
                }
            }
            // jq system() + dangerous flags（CC :2594-2617）
            if (name.equals("jq")) {
                for (String arg : a) {
                    if (arg.matches(".*\\bsystem\\s*\\(.*")) {
                        return "jq command contains system() function which executes arbitrary commands";
                    }
                }
                for (String arg : a) {
                    if (arg.matches("^(?:-[fL](?:$|[^A-Za-z])|--(?:from-file|rawfile|slurpfile|library-path)(?:$|=)).*")) {
                        return "jq command contains dangerous flags that could execute code or read arbitrary files";
                    }
                }
            }
            // ZSH_DANGEROUS_BUILTINS（CC :2619-2624）
            if (ZSH_DANGEROUS_BUILTINS.contains(name)) {
                return "Zsh builtin '" + name + "' can bypass security checks";
            }
            // EVAL_LIKE + carve-out（CC :2626-2656）
            if (EVAL_LIKE_BUILTINS.contains(name)) {
                if (name.equals("command") && a.size() >= 2
                        && (a.get(1).equals("-v") || a.get(1).equals("-V"))) {
                    // command -v/-V 只打印路径，不执行（CC :2630-2631）
                } else if (name.equals("fc") && !fcHasDangerousOpt(a)) {
                    // fc -l 等安全（CC :2632-2640）
                } else if (name.equals("compgen") && !compgenHasDangerousOpt(a)) {
                    // compgen -c/-f/-v 安全（CC :2641-2649）
                } else {
                    return "'" + name + "' evaluates arguments as shell code";
                }
            }
            // PROC_ENVIRON（CC :2658-2676）
            for (String arg : cmd.argv()) {
                if (arg.contains("/proc/") && PROC_ENVIRON_RE.matcher(arg).find()) {
                    return "Accesses /proc/*/environ which may expose secrets";
                }
            }
            for (ParseForSecurityResult.BashRedirect r : cmd.redirects()) {
                if (r.target() != null && r.target().contains("/proc/")
                        && PROC_ENVIRON_RE.matcher(r.target()).find()) {
                    return "Accesses /proc/*/environ which may expose secrets";
                }
            }
        }
        return null;
    }

    private record WrapperStrip(List<String> wrapped, String failReason) {}

    /** fc 是否含 -e/-s 危险短 flag（CC ast.ts:2632-2640）。 */
    private static boolean fcHasDangerousOpt(List<String> a) {
        for (int i = 1; i < a.size(); i++) {
            if (a.get(i).matches("^-[^-]*[es].*")) return true;
        }
        return false;
    }

    /** compgen 是否含 -C/-F/-W 危险短 flag（CC ast.ts:2641-2649）。 */
    private static boolean compgenHasDangerousOpt(List<String> a) {
        for (int i = 1; i < a.size(); i++) {
            if (a.get(i).matches("^-[^-]*[CFW].*")) return true;
        }
        return false;
    }

    /**
     * 安全 wrapper 逐 flag 剥离 · 对齐 CC checkSemantics 内联 while-loop（ast.ts:2220-2384）。
     * 未知 flag / 非标准 duration → fail-closed（返回 failReason）。
     */
    private static WrapperStrip stripWrappers(List<String> argv) {
        List<String> a = new ArrayList<>(argv);
        for (;;) {
            if (a.isEmpty()) {
                return new WrapperStrip(a, null);
            }
            String head = a.get(0);
            if (head.equals("time") || head.equals("nohup")) {
                a = a.subList(1, a.size());
                continue;
            }
            if (head.equals("timeout")) {
                int i = 1;
                while (i < a.size()) {
                    String arg = a.get(i);
                    if (arg.equals("--foreground") || arg.equals("--preserve-status")
                            || arg.equals("--verbose")) {
                        i++;
                    } else if (arg.matches("^--(?:kill-after|signal)=[A-Za-z0-9_.+-]+$")) {
                        i++;
                    } else if ((arg.equals("--kill-after") || arg.equals("--signal"))
                            && i + 1 < a.size() && a.get(i + 1).matches("^[A-Za-z0-9_.+-]+$")) {
                        i += 2;
                    } else if (arg.startsWith("--")) {
                        return new WrapperStrip(null, "timeout with " + arg + " flag cannot be statically analyzed");
                    } else if (arg.equals("-v")) {
                        i++;
                    } else if ((arg.equals("-k") || arg.equals("-s"))
                            && i + 1 < a.size() && a.get(i + 1).matches("^[A-Za-z0-9_.+-]+$")) {
                        i += 2;
                    } else if (arg.matches("^-[ks][A-Za-z0-9_.+-]+$")) {
                        i++;
                    } else if (arg.startsWith("-")) {
                        return new WrapperStrip(null, "timeout with " + arg + " flag cannot be statically analyzed");
                    } else {
                        break;
                    }
                }
                if (i < a.size() && a.get(i).matches("^\\d+(?:\\.\\d+)?[smhd]?$")) {
                    a = a.subList(i + 1, a.size());
                } else if (i < a.size()) {
                    return new WrapperStrip(null, "timeout duration '" + a.get(i) + "' cannot be statically analyzed");
                } else {
                    break;
                }
                continue;
            }
            if (head.equals("nice")) {
                if (a.size() >= 3 && a.get(1).equals("-n") && a.get(2).matches("^-?\\d+$")) {
                    a = a.subList(3, a.size());
                } else if (a.size() >= 2 && a.get(1).matches("^-\\d+$")) {
                    a = a.subList(2, a.size());
                } else if (a.size() >= 2 && a.get(1).matches(".*[$(`].*")) {
                    return new WrapperStrip(null, "nice argument '" + a.get(1)
                            + "' contains expansion — cannot statically determine wrapped command");
                } else {
                    a = a.subList(1, a.size());
                }
                continue;
            }
            if (head.equals("env")) {
                int i = 1;
                while (i < a.size()) {
                    String arg = a.get(i);
                    if (arg.contains("=") && !arg.startsWith("-")) {
                        i++;
                    } else if (arg.equals("-i") || arg.equals("-0") || arg.equals("-v")) {
                        i++;
                    } else if (arg.equals("-u") && i + 1 < a.size()) {
                        i += 2;
                    } else if (arg.startsWith("-")) {
                        return new WrapperStrip(null, "env with " + arg + " flag cannot be statically analyzed");
                    } else {
                        break;
                    }
                }
                if (i < a.size()) {
                    a = a.subList(i, a.size());
                } else {
                    break;
                }
                continue;
            }
            if (head.equals("stdbuf")) {
                int i = 1;
                while (i < a.size()) {
                    String arg = a.get(i);
                    if (arg.matches("^-[ioe]$") && i + 1 < a.size()) {
                        i += 2;
                    } else if (arg.matches("^-[ioe].")) {
                        i++;
                    } else if (arg.matches("^--(?:input|output|error)=")) {
                        i++;
                    } else if (arg.startsWith("-")) {
                        return new WrapperStrip(null, "stdbuf with " + arg + " flag cannot be statically analyzed");
                    } else {
                        break;
                    }
                }
                if (i > 1 && i < a.size()) {
                    a = a.subList(i, a.size());
                } else {
                    break;
                }
                continue;
            }
            break;
        }
        return new WrapperStrip(a, null);
    }

    /** $( 前是否 {@code VAR=} 赋值上下文 — CC walkVariableAssignment (ast.ts:1796-1804) 提取内层. */
    private static boolean isAssignmentContext(String input, int dollarIdx) {
        if (dollarIdx <= 0 || input.charAt(dollarIdx - 1) != '=') return false;
        int p = dollarIdx - 2;
        while (p >= 0 && isIdentifierChar(input.charAt(p))) p--;
        if (p == dollarIdx - 2) return false; // '=' 前无变量名 (如 $(...) 前是 =)
        // 变量名前必须是命令起始上下文: 命令首 / 分隔符 / 声明关键字 / 连续赋值链
        return isCommandStartContext(input, p);
    }

    /**
     * 命令起始上下文判定: 位置前是字符串开头 / 分隔符 ({@code ; & | ( { }) / 声明关键字
     * ({@code export declare local readonly typeset}) / 连续赋值链 ({@code A=1 B=$(x)}).
     * 反例 {@code echo VAR=$(x)}: VAR= 前是命令名 echo → 参数位 → 裸 $() fail-safe
     * (CC: walkArgument concatenation 内 command_substitution → tooComplex).
     */
    private static boolean isCommandStartContext(String input, int before) {
        int b = before;
        while (b >= 0 && BashWhitespace.isBashWhitespace(input.charAt(b))) b--;
        if (b < 0) return true;
        char c = input.charAt(b);
        if (c == ';' || c == '&' || c == '|' || c == '(' || c == '{' || c == '}') return true;
        if (isTokenChar(c)) {
            int tokEnd = b;
            while (b >= 0 && isTokenChar(input.charAt(b))) b--;
            String token = input.substring(b + 1, tokEnd + 1);
            if (DECLARATION_KEYWORDS.contains(token)) return true;
            if (isAssignmentPrefix(token)) return isCommandStartContext(input, b);
            return false;
        }
        return false;
    }

    /** token 是否为 {@code VAR=...} 赋值形 (首字符标识符 + 含 =). */
    private static boolean isAssignmentPrefix(String token) {
        return !token.isEmpty() && isIdentifierChar(token.charAt(0)) && token.indexOf('=') > 0;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 回溯 token 字符集: 标识符 + 赋值值常见字符 ({@code VAR=/a/b VAR2=$(x)}). */
    private static boolean isTokenChar(char c) {
        return isIdentifierChar(c) || c == '=' || c == '/' || c == '.' || c == '~'
                || c == '-' || c == '+' || c == ':';
    }

    /**
     * 定界符同行余下内容判定 (CC walkHeredocRedirect fail-closed, ast.ts:1158-1168):
     * heredoc_redirect 子节点含非结构 token ({@code &&}/pipeline/word 等) → too-complex →
     * fail-safe. 引号内内容不算 (引号内 operator 是字面量).
     */
    private static boolean restOfLineHasContent(String input, int from) {
        boolean s = false, d = false;
        for (int k = from; k < input.length(); k++) {
            char c = input.charAt(k);
            if (c == '\n') return false;
            if (c == '\\' && !s && k + 1 < input.length()) { k++; continue; }
            if (c == '\'' && !d) { s = !s; continue; }
            if (c == '"' && !s) { d = !d; continue; }
            if (!BashWhitespace.isBashWhitespace(c)) return true;
        }
        return false;
    }

    /**
     * P0-1: heredoc 定界符是否引号定界（{@code 'EOF'} / {@code "EOF"} / {@code \EOF}）·
     * 从 {@code <<} 起始偏移扫描（tokenizer 剥了引号且 HEREDOC_TAG.startOffset 指向 {@code <<}，
     * 不能靠 isQuoteBefore 查前字符）。对齐 CC ast.ts:1176-1182（非引号定界 → shell 展开 → too-complex）。
     *
     * @param src          命令源串
     * @param heredocStart HEREDOC_TAG token 的 startOffset（{@code <<} 位置）
     * @return true = 引号/反斜杠定界（body 不做 shell 展开）
     */
    static boolean isHeredocDelimiterQuoted(String src, int heredocStart) {
        int p = heredocStart;
        if (p < 0 || p + 1 >= src.length() || src.charAt(p) != '<' || src.charAt(p + 1) != '<') {
            return false;
        }
        p += 2;
        if (p < src.length() && src.charAt(p) == '-') p++;   // <<-
        while (p < src.length() && BashWhitespace.isBashWhitespace(src.charAt(p))
                && src.charAt(p) != '\n') p++;
        if (p >= src.length()) return false;
        char c = src.charAt(p);
        return c == '\'' || c == '"' || c == '\\';
    }

    /**
     * P0-1: heredoc 定界符结束偏移（含闭合引号）· 供 {@code restOfLineHasContent} 从定界符后
     * 扫描同行结构（CC ast.ts:1158-1168 fail-closed）。
     */
    static int heredocDelimiterEnd(String src, int heredocStart) {
        int p = heredocStart + 2;
        if (p < src.length() && src.charAt(p) == '-') p++;
        while (p < src.length() && BashWhitespace.isBashWhitespace(src.charAt(p))
                && src.charAt(p) != '\n') p++;
        if (p >= src.length()) return p;
        char c = src.charAt(p);
        if (c == '\'' || c == '"') {
            char q = c;
            p++;
            while (p < src.length() && src.charAt(p) != q) p++;
            if (p < src.length()) p++;   // 闭合引号
        } else if (c == '\\') {
            p++;
            while (p < src.length() && !BashWhitespace.isBashWhitespace(src.charAt(p))
                    && src.charAt(p) != '\n') p++;
        } else {
            while (p < src.length() && !BashWhitespace.isBashWhitespace(src.charAt(p))
                    && src.charAt(p) != '\n') p++;
        }
        return p;
    }

    /** 引号感知的匹配括号查找 ($(...) 内层文本提取; 单/双引号内括号不算嵌套). */
    private static int findMatchingParenQuoted(String s, int openIdx) {
        int depth = 0;
        boolean inS = false, inD = false;
        for (int k = openIdx; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && !inS && k + 1 < s.length()) { k++; continue; }
            if (c == '\'' && !inD) { inS = !inS; continue; }
            if (c == '"' && !inS) { inD = !inD; continue; }
            if (inS || inD) continue;
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return k; }
        }
        return -1;
    }

    /** 找闭合反引号 (转义 {@code \`} 跳过). */
    private static int findClosingBacktick(String s, int start) {
        for (int k = start; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length()) { k++; continue; }
            if (c == '`') return k;
        }
        return -1;
    }

    /** 剥 heredoc 定界符引号/反斜杠 → 终结符词 (CC heredoc_start text: {@code 'EOF'} → {@code EOF}). */
    private static String stripHeredocDelim(String d) {
        if (d.length() >= 2 && d.startsWith("'") && d.endsWith("'")) return d.substring(1, d.length() - 1);
        if (d.length() >= 2 && d.startsWith("\"") && d.endsWith("\"")) return d.substring(1, d.length() - 1);
        if (d.startsWith("\\")) return d.substring(1);
        return d;
    }

    /** Extract quoted content (single + double + ansi-c). */
    public static List<String> extractQuoted(String input) {
        List<String> result = new ArrayList<>();
        if (input == null) return result;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\'') {
                int end = input.indexOf('\'', i + 1);
                if (end < 0) break;
                result.add(input.substring(i + 1, end));
                i = end + 1;
            } else if (c == '"') {
                int end = findMatchingQuote(input, i + 1, '"');
                if (end < 0) break;
                result.add(unescapeDoubleQuoted(input.substring(i + 1, end)));
                i = end + 1;
            } else if (c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                int end = findMatchingQuote(input, i + 2, '\'');
                if (end < 0) break;
                result.add(unescapeAnsiC(input.substring(i + 2, end)));
                i = end + 2;
            } else {
                i++;
            }
        }
        return result;
    }

    /** Extract variable expansions. */
    public static List<String> extractVariables(String input) {
        List<String> result = new ArrayList<>();
        if (input == null) return result;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '$' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '{') {
                    int end = input.indexOf('}', i + 2);
                    if (end > 0) {
                        result.add(input.substring(i + 1, end + 1));
                        i = end + 1;
                        continue;
                    }
                } else if (Character.isLetter(next) || next == '_') {
                    int j = i + 2;
                    while (j < input.length() && (Character.isLetterOrDigit(input.charAt(j)) || input.charAt(j) == '_')) j++;
                    result.add(input.substring(i + 1, j));
                    i = j;
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    /** Extract command substitutions ($(cmd) and `cmd`). */
    public static List<String> extractCommandSubstitutions(String input) {
        List<String> result = new ArrayList<>();
        if (input == null) return result;
        int i = 0;
        while (i < input.length()) {
            if (i + 1 < input.length() && input.charAt(i) == '$' && input.charAt(i + 1) == '(') {
                int end = findMatchingParen(input, i + 1);
                if (end > 0) {
                    result.add(input.substring(i + 2, end));
                    i = end + 1;
                    continue;
                }
            }
            if (input.charAt(i) == '`' && i + 1 < input.length()) {
                int j = i + 1;
                StringBuilder buf = new StringBuilder();
                while (j < input.length() && input.charAt(j) != '`') {
                    if (input.charAt(j) == '\\' && j + 1 < input.length()) {
                        buf.append(input.charAt(j + 1));
                        j += 2;
                    } else {
                        buf.append(input.charAt(j++));
                    }
                }
                result.add(buf.toString());
                i = j + 1;
                continue;
            }
            i++;
        }
        return result;
    }

    /**
     * Extract heredoc bodies (返回 {tag, body} 列表).
     * R8-4 升级: 增量 quote/comment scanner, nested-heredoc dedup.
     * 对齐 TS heredoc.ts 733 行 extractHeredocs.
     */
    public static List<Heredoc> extractHeredocs(String input) {
        List<Heredoc> result = new ArrayList<>();
        if (input == null) return result;
        Set<String> seenTags = new HashSet<>();  // nested-heredoc dedup
        int i = 0;
        while (i < input.length()) {
            // 查找 <<TAG 模式
            if (i + 1 < input.length() && input.charAt(i) == '<' && input.charAt(i + 1) == '<') {
                int j = i + 2;
                boolean stripIndent = false;
                boolean literal = false;
                if (j < input.length() && input.charAt(j) == '-') { stripIndent = true; j++; }
                while (j < input.length() && BashWhitespace.isBashWhitespace(input.charAt(j))) j++;
                if (j < input.length() && input.charAt(j) == '\'') {
                    literal = true;
                    j++;
                    int tagEnd = input.indexOf('\'', j);
                    if (tagEnd > 0) j = tagEnd + 1;
                } else {
                    while (j < input.length() && !BashWhitespace.isBashWhitespace(input.charAt(j))
                           && input.charAt(j) != '\n' && input.charAt(j) != '<') {
                        j++;
                    }
                }
                int tagStart = stripIndent ? i + 3 : i + 2;
                String tag = input.substring(tagStart, j).trim();
                if (tag.isEmpty()) { i++; continue; }

                // nested-heredoc dedup: same tag already seen → skip (avoid double-counting)
                if (!seenTags.add(tag)) { i = j; continue; }

                // 找到下一个 \nTAG 行 (TAG at line start)
                int lineEnd = input.indexOf('\n', j);
                if (lineEnd < 0) lineEnd = input.length();
                int bodyEnd = findHeredocEnd(input, lineEnd + 1, tag, literal, stripIndent);
                String body = input.substring(lineEnd + 1, bodyEnd);
                result.add(new Heredoc(tag, body, literal, stripIndent));
                i = bodyEnd;
                continue;
            }
            i++;
        }
        return result;
    }


    public record Heredoc(String tag, String body, boolean literal, boolean stripIndent) {}

    /** 第一条命令名. */
    public static String firstCommandName(String input) {
        List<String> cmds = splitCommands(input);
        if (cmds.isEmpty()) return null;
        return firstCommandNameOfSingle(cmds.get(0));
    }

    private static String firstCommandNameOfSingle(String cmd) {
        String first = cmd.trim().split("\\s+")[0];
        int slash = first.lastIndexOf('/');
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    /** Detect whether string contains any unsafe construct (subshell / redirect / write). */
    public static boolean isSafeCommand(String input) {
        if (input == null) return true;
        // 检查 redirect / subshell / write command
        List<String> cmds = splitCommands(input);
        for (String cmd : cmds) {
            String trimmed = cmd.trim();
            // redirect → not safe
            if (trimmed.matches(".*[<>].*")) return false;
            // write command → not safe
            String first = firstCommandNameOfSingle(trimmed);
            if (isWriteCommand(first)) return false;
        }
        return true;
    }

    private static boolean isWriteCommand(String name) {
        if (name == null) return false;
        return switch (name) {
            case "rm", "mv", "cp", "touch", "mkdir", "rmdir",
                 "chmod", "chown", "ln", "tee", "truncate",
                 "dd", "shred", "install", "rsync" -> true;
            default -> false;
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // 路径校验子命令切分 + 输出重定向目标提取 · 对齐 CC commands.ts
    // splitCommand_DEPRECATED(:265) + extractOutputRedirections(:634) +
    // astRedirectsToOutputRedirections(pathValidation.ts:1116)
    // ════════════════════════════════════════════════════════════════════════

    /** 输出重定向目标 · 对齐 CC astRedirectsToOutputRedirections 返回的 {target, operator}。 */
    public record RedirectTarget(String operator, String target) {}

    /** 输出重定向提取结果 · 对齐 CC extractOutputRedirections 返回结构。 */
    public record OutputRedirections(List<RedirectTarget> redirections, boolean hasDangerousRedirection) {}

    /**
     * 顶层干净切分子命令 · 对齐 CC {@code splitCommand_DEPRECATED}（commands.ts:265-369）。
     *
     * <p>供 {@code BashPathValidator} 路径校验用（CC checkPathConstraints 对子命令逐条校验）。
     * 与 {@link #splitForSecurity} 的区别（后者不可用于路径校验，见 CC 真源）：
     * <ul>
     *   <li>顶层 {@code &&}/{@code ||}/{@code ;}/{@code ;;}/{@code ;&}/{@code |}/单 {@code &}/
     *       换行 切分子命令，<b>不把操作符 token 并入段</b>、不产出孤立 {@code &} 段</li>
     *   <li>剥输出重定向操作符及其静态 target（{@code >}/{@code >>}/{@code >&}/{@code &>}/
     *       {@code <}/{@code <<}/{@code <&}/{@code <<<}），避免 redirect target 被误当命令参数
     *       （{@code ls > /etc/passwd} 不得把 {@code /etc/passwd} 当 ls 的路径参数）</li>
     * </ul>
     *
     * @param command 原始 bash 命令
     * @return 干净子命令段（trim 后非空）
     */
    public static List<String> splitCommandDeprecated(String command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<Token> tokens;
        try {
            tokens = tokenize(command);
        } catch (Exception e) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean skipRedirectTarget = false;
        for (Token t : tokens) {
            TokenKind k = t.kind();
            if (skipRedirectTarget) {
                skipRedirectTarget = false;
                continue;
            }
            if (k == TokenKind.OPERATOR) {
                String op = t.text();
                if (op.equals(">") || op.equals(">>") || op.equals(">&") || op.equals("&>")
                        || op.equals("<") || op.equals("<<") || op.equals("<&") || op.equals("<<<")) {
                    skipRedirectTarget = true; // 剥重定向操作符 + 下一 token（target）
                    continue;
                }
                // 命令分隔符（单 & 含后台分隔，不产孤立 '&' 段）
                if (op.equals("&&") || op.equals("||") || op.equals(";") || op.equals(";;")
                        || op.equals(";&") || op.equals("|") || op.equals("&")) {
                    flushCommandSegment(segments, cur);
                    continue;
                }
                continue;
            }
            if (k == TokenKind.NEWLINE || k == TokenKind.EOF) {
                flushCommandSegment(segments, cur);
                continue;
            }
            if (k == TokenKind.WORD || k == TokenKind.STRING || k == TokenKind.RAW_STRING
                    || k == TokenKind.ANSI_C_STRING || k == TokenKind.VARIABLE
                    || k == TokenKind.COMMAND_SUBST || k == TokenKind.ARITH_EXPANSION) {
                if (cur.length() > 0) {
                    cur.append(' ');
                }
                cur.append(t.text());
            }
            // HEREDOC_TAG / HEREDOC_BODY / COMMENT → 跳过（非命令词）
        }
        return segments;
    }

    private static void flushCommandSegment(List<String> segments, StringBuilder cur) {
        String seg = cur.toString().trim();
        cur.setLength(0);
        if (!seg.isEmpty()) {
            segments.add(seg);
        }
    }

    /**
     * 提取输出重定向目标 · 对齐 CC {@code extractOutputRedirections}（commands.ts:634-790）+
     * {@code astRedirectsToOutputRedirections}（pathValidation.ts:1116-1150）。
     *
     * <p>语义：
     * <ul>
     *   <li>{@code >}/{@code >>} → target=下一词（含 {@code 2>file} 的 fd 前缀形式，fd 数字本身
     *       是独立 WORD，不影响 target 提取）</li>
     *   <li>{@code >&N}（数字 fd）→ fd 复制，跳过（对齐 CC astRedirectsToOutputRedirections
     *       {@code >&} + 纯数字 → 非文件写）；{@code >&file} → 文件写（deprecated {@code &>} 形式）</li>
     *   <li>{@code <}/{@code <<}/{@code <&}/{@code <<<} → 输入重定向，跳过</li>
     *   <li>target 含 shell 展开语法（{@code $}/{@code %}/{@code `}/{@code *}/{@code ?}/
     *       {@code [}/{@code \{} 或前导 {@code !}/{@code =}/{@code ~} 或空串）或未闭合引号 →
     *       {@code hasDangerousRedirection=true}（对齐 CC hasDangerousExpansion）</li>
     * </ul>
     *
     * @param command 原始 bash 命令
     * @return 重定向目标列表 + 危险标记
     */
    public static OutputRedirections extractOutputRedirectTargets(String command) {
        if (command == null || command.isEmpty()) {
            return new OutputRedirections(List.of(), false);
        }
        boolean hasDangerous = !hasBalancedQuotesForRedirect(command);
        List<RedirectTarget> redirections = new ArrayList<>();
        List<Token> tokens;
        try {
            tokens = tokenize(command);
        } catch (Exception e) {
            return new OutputRedirections(List.of(), true);
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() != TokenKind.OPERATOR) {
                continue;
            }
            String op = t.text();
            if (op.equals(">") || op.equals(">>")) {
                int j = nextWordTokenIndex(tokens, i + 1);
                if (j < 0) {
                    hasDangerous = true;
                    continue;
                }
                String target = tokens.get(j).text();
                // zsh/posix force overwrite（>! file / >| file）→ 真实 target 在下一词
                if (target.equals("!") || target.equals("|")) {
                    int k = nextWordTokenIndex(tokens, j + 1);
                    if (k < 0) {
                        hasDangerous = true;
                        continue;
                    }
                    target = tokens.get(k).text();
                    i = k;
                } else {
                    i = j;
                }
                if (isDangerousRedirectTarget(target)) {
                    hasDangerous = true;
                } else {
                    redirections.add(new RedirectTarget(op, stripSurroundingQuotes(target)));
                }
            } else if (op.equals(">&")) {
                int j = nextWordTokenIndex(tokens, i + 1);
                if (j < 0) {
                    hasDangerous = true;
                    continue;
                }
                String target = tokens.get(j).text();
                i = j;
                if (target.matches("\\d+")) {
                    continue; // fd 复制（2>&1 / >&10）
                }
                if (isDangerousRedirectTarget(target)) {
                    hasDangerous = true;
                } else {
                    redirections.add(new RedirectTarget(">", stripSurroundingQuotes(target)));
                }
            }
            // < / << / <& / <<< → 输入重定向，跳过
        }
        return new OutputRedirections(redirections, hasDangerous);
    }

    /** 找下一个 WORD/STRING 类 token（跳过操作符间可能穿插的 COMMENT 等）。 */
    private static int nextWordTokenIndex(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            TokenKind k = tokens.get(i).kind();
            if (k == TokenKind.WORD || k == TokenKind.STRING || k == TokenKind.RAW_STRING
                    || k == TokenKind.ANSI_C_STRING || k == TokenKind.VARIABLE) {
                return i;
            }
            if (k == TokenKind.OPERATOR || k == TokenKind.NEWLINE || k == TokenKind.EOF) {
                return -1;
            }
        }
        return -1;
    }

    /** 危险重定向 target 判定 · 对齐 CC hasDangerousExpansion（commands.ts:830-858）。 */
    private static boolean isDangerousRedirectTarget(String target) {
        if (target == null || target.isEmpty()) {
            return true;
        }
        return target.startsWith("!") || target.startsWith("=") || target.startsWith("~")
            || target.contains("$") || target.contains("%") || target.contains("`")
            || target.contains("*") || target.contains("?") || target.contains("[")
            || target.contains("{");
    }

    private static String stripSurroundingQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'"))
                || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 引号是否闭合（单/双引号配平，忽略转义）· 未闭合 → 重定向危险。 */
    private static boolean hasBalancedQuotesForRedirect(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\' && i + 1 < command.length()) {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            }
        }
        return !inSingle && !inDouble;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static int findMatchingQuote(String s, int start, char quote) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i++; continue; }
            if (c == quote && depth == 0) return i;
        }
        return -1;
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i++; continue; }
            if (c == '(' && (i == 0 || s.charAt(i - 1) != '\\')) depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findHeredocEnd(String s, int start, String tag, boolean literal, boolean stripIndent) {
        int i = start;
        while (i < s.length()) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = s.length();
            String line = s.substring(lineStart, lineEnd);
            String compare = stripIndent ? line.replaceAll("^\\s+", "") : line;
            if (compare.equals(tag)) return lineStart;
            i = lineEnd + 1;
        }
        return s.length();
    }

    /** 解码 double-quoted escape: \$ \" \\ \` \n \t. */
    private static String unescapeDoubleQuoted(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; continue; }
                    case 't' -> { sb.append('\t'); i++; continue; }
                    case 'r' -> { sb.append('\r'); i++; continue; }
                    case '\\' -> { sb.append('\\'); i++; continue; }
                    case '"' -> { sb.append('"'); i++; continue; }
                    case '$' -> { sb.append('$'); i++; continue; }
                    case '`' -> { sb.append('`'); i++; continue; }
                    default -> { sb.append(c); }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 解码 ANSI-C quote ($'...'): \\n \\t \\r \\\\ \\\" \\xHH \\uHHHH. */
    private static String unescapeAnsiC(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (i + 1 >= s.length()) break;
            char next = s.charAt(i + 1);
            i++;
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '\\' -> sb.append('\\');
                case '\'' -> sb.append('\'');
                case '"' -> sb.append('"');
                case '0' -> sb.append('\0');
                case 'a' -> sb.append('\007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\013');
                case 'x' -> {
                    if (i + 2 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    }
                }
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tokenizer state (character-by-character scanner)
    // ════════════════════════════════════════════════════════════════════════

    private static final class TokenizerState {
        final String src;
        int pos, line, col;
        int singleQuoteDepth, doubleQuoteDepth, subshellDepth;
        String heredocTag;
        boolean heredocLiteral, heredocStripIndent, heredocActive;

        TokenizerState(String src) {
            this.src = src;
            this.pos = 0; this.line = 1; this.col = 1;
        }

        boolean inQuote() { return singleQuoteDepth > 0 || doubleQuoteDepth > 0 || heredocActive; }

        void advance() { pos++; col++; }
        void advance(int n) { pos += n; col += n; }

        void emitNewline(List<Token> tokens) {
            tokens.add(new Token(TokenKind.NEWLINE, "\n", line, col, pos));
            advance();
            line++; col = 1;
        }

        void skipWhitespace() {
            while (pos < src.length() && BashWhitespace.isBashWhitespace(src.charAt(pos))
                   && src.charAt(pos) != '\n') {
                advance();
            }
        }

        void skipComment(List<Token> tokens) {
            int start = pos;
            int startCol = col;
            while (pos < src.length() && src.charAt(pos) != '\n') advance();
            tokens.add(new Token(TokenKind.COMMENT, src.substring(start, pos), line, startCol, start));
        }

        boolean tryOperator(List<Token> tokens, char c) {
            if (c != '|' && c != '&' && c != ';' && c != '>' && c != '<') return false;
            int startCol = col;
            int startPos = pos;
            String op;
            if (c == '|' && pos + 1 < src.length() && src.charAt(pos + 1) == '|') {
                op = "||"; advance(2);
            } else if (c == '&' && pos + 1 < src.length() && src.charAt(pos + 1) == '&') {
                op = "&&"; advance(2);
            } else if (c == ';' && pos + 1 < src.length() && src.charAt(pos + 1) == ';') {
                op = ";;"; advance(2);
            } else if (c == ';' && pos + 1 < src.length() && src.charAt(pos + 1) == '&') {
                op = ";&"; advance(2);
            } else if (c == '>' && pos + 1 < src.length() && src.charAt(pos + 1) == '>') {
                op = ">>"; advance(2);
            } else if (c == '<' && pos + 1 < src.length() && src.charAt(pos + 1) == '<') {
                // heredoc 由 tryHeredoc 处理
                return false;
            } else if (c == '>' && pos + 1 < src.length() && src.charAt(pos + 1) == '&') {
                op = ">&"; advance(2);
            } else if (c == '<' && pos + 1 < src.length() && src.charAt(pos + 1) == '&') {
                op = "<&"; advance(2);
            } else {
                op = String.valueOf(c); advance();
            }
            tokens.add(new Token(TokenKind.OPERATOR, op, line, startCol, startPos));
            return true;
        }

        boolean tryHeredoc(List<Token> tokens, char c) {
            if (c != '<') return false;
            if (pos + 1 >= src.length() || src.charAt(pos + 1) != '<') return false;
            int startCol = col;
            int startPos = pos;
            advance(2);
            // 检测 <<- (strip indent)
            boolean stripIndent = false;
            if (pos < src.length() && src.charAt(pos) == '-') { stripIndent = true; advance(); }
            // 检测 <<< (here-string)
            if (pos < src.length() && src.charAt(pos) == '<') {
                advance();
                tokens.add(new Token(TokenKind.OPERATOR, "<<<", line, startCol, startPos));
                return true;
            }
            // 跳过空白
            while (pos < src.length() && BashWhitespace.isBashWhitespace(src.charAt(pos))
                   && src.charAt(pos) != '\n') advance();
            // 读取 tag (可选 quote)
            boolean literal = false;
            int tagStart = pos;
            if (pos < src.length() && src.charAt(pos) == '\'') {
                literal = true; advance();
                tagStart = pos;
                while (pos < src.length() && src.charAt(pos) != '\'') advance();
            } else {
                while (pos < src.length() && !BashWhitespace.isBashWhitespace(src.charAt(pos))
                       && src.charAt(pos) != '\n' && src.charAt(pos) != '<') advance();
            }
            String tag = src.substring(tagStart, pos).trim();
            if (literal && pos < src.length() && src.charAt(pos) == '\'') advance();
            tokens.add(new Token(TokenKind.HEREDOC_TAG, tag, line, startCol, startPos));
            // 跳到行尾
            while (pos < src.length() && src.charAt(pos) != '\n') advance();
            if (pos < src.length()) { advance(); line++; col = 1; }
            // 读取 heredoc body
            int bodyStart = pos;
            while (pos < src.length()) {
                int lineStart = pos;
                int lineEnd = src.indexOf('\n', pos);
                if (lineEnd < 0) lineEnd = src.length();
                String lineText = src.substring(lineStart, lineEnd);
                String compare = stripIndent ? lineText.replaceAll("^\\s+", "") : lineText;
                if (compare.equals(tag)) { pos = lineStart; break; }
                pos = lineEnd + 1;
                this.line++;
            }
            tokens.add(new Token(TokenKind.HEREDOC_BODY, src.substring(bodyStart, pos), line, startCol, bodyStart));
            return true;
        }

        void readWord(List<Token> tokens) {
            int start = pos;
            int startCol = col;
            while (pos < src.length() && !isWordBoundary(src.charAt(pos))) {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) { advance(2); continue; }
                if (c == '\'') {
                    singleQuoteDepth++;
                    advance();
                    while (pos < src.length() && src.charAt(pos) != '\'') advance();
                    if (pos < src.length()) advance();
                    singleQuoteDepth--;
                    continue;
                }
                if (c == '"') {
                    doubleQuoteDepth++;
                    advance();
                    while (pos < src.length() && src.charAt(pos) != '"') {
                        if (src.charAt(pos) == '\\' && pos + 1 < src.length()) advance(2);
                        else advance();
                    }
                    if (pos < src.length()) advance();
                    doubleQuoteDepth--;
                    continue;
                }
                if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '(') {
                    subshellDepth++;
                    advance(2);
                    while (pos < src.length() && subshellDepth > 0) {
                        if (src.charAt(pos) == '(' && (pos == 0 || src.charAt(pos - 1) != '\\')) subshellDepth++;
                        else if (src.charAt(pos) == ')') subshellDepth--;
                        if (subshellDepth > 0) advance();
                    }
                    if (pos < src.length()) advance();
                    continue;
                }
                advance();
            }
            String word = src.substring(start, pos);
            if (!word.isEmpty()) {
                tokens.add(new Token(TokenKind.WORD, word, line, startCol, start));
            }
        }

        boolean isWordBoundary(char c) {
            return BashWhitespace.isBashWhitespace(c) || c == '|' || c == '&' || c == ';'
                || c == '>' || c == '<';
        }
    }
}