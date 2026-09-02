package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bash 规则匹配器 · 对齐 CC {@code tools/BashTool/bashPermissions.ts} 的规则匹配部分
 * （filterRulesByContentsMatchingInput :778-935 / stripSafeWrappers :524-615 /
 * stripAllLeadingEnvVars :733-776 / stripCommentLines :508-522 / 建议生成 :161-337）
 * 与 {@code utils/permissions/shellRuleMatching.ts}（parsePermissionRule / matchWildcardPattern /
 * suggestionForPrefix）。
 *
 * <h2>对齐的不变量（CC-PERM-13/24/11）</h2>
 * <ul>
 *   <li><b>env/wrapper 剥离</b>：匹配前对命令做 fixed-point 剥离
 *       （stripSafeWrappers 只剥 SAFE_ENV_VARS 白名单 env + 5 个 wrapper 命令；
 *       deny/ask 桶额外 stripAllLeadingEnvVars 剥任意 env 前缀）——消除
 *       {@code FOO=bar cmd}、{@code timeout 5 rm -rf x} 绕过面。</li>
 *   <li><b>compound guard</b>：prefix/wildcard 规则在 prefix 模式不匹配复合命令
 *       （{@code cd x && evil}），deny/ask 桶跳过该检查（skipCompoundCheck=true）。</li>
 *   <li><b>xargs 前缀</b>：{@code Bash(grep:*)} 同时匹配 {@code xargs grep pattern}。</li>
 *   <li><b>wildcard exact 拒绝</b>：exact 模式下通配规则不匹配
 *       （{@code foo *} 不得匹配 {@code foo arg && curl evil.com}）。</li>
 *   <li><b>重定向剥离</b>：{@code Bash(python:*)} 匹配 {@code python script.py &gt; output.txt}。</li>
 * </ul>
 *
 * <p>Java 简化声明（与 CC 的差异）：
 * <ul>
 *   <li>ANT_ONLY_SAFE_ENV_VARS（CC :447-497，USER_TYPE==='ant' 专属）不启用——Java 无 ant
 *       构建，对齐 CC 外部构建语义（仅 SAFE_ENV_VARS 白名单）。</li>
 *   <li>{@link #extractOutputRedirections} 用引号感知 token 扫描替代 CC shell-quote 解析
 *       （commands.ts:634-699）；解析失败（未闭合引号）fail-closed 返回 hasDangerousRedirection=true
 *       与 CC :693-699 一致。</li>
 *   <li>{@link #isCompoundCommand} 用引号感知分隔符 split 替代 CC splitCommand_DEPRECATED
 *       （commands.ts:265，完整 shell 解析）；语义覆盖 {code ;} / {@code &&} / {@code ||} /
 *       {@code |} / {@code &} / 换行 顶层分隔。</li>
 * </ul>
 *
 * <p>无状态工具类，全部 static，线程安全。
 */
public final class BashRuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(BashRuleMatcher.class);

    /** CC 工具名 BASH_TOOL_NAME（toolName.ts:2）。 */
    public static final String BASH_TOOL_NAME = "Bash";

    /**
     * 子命令扇出上限 · CC bashPermissions.ts:103
     * {@code MAX_SUBCOMMANDS_FOR_SECURITY_CHECK = 50}。CC-643 防 REPL 冻结：
     * splitCommand 切分后子命令数超限 → ask（无法逐个安全检查）。IMP-3 R5 接入主链。
     */
    public static final int MAX_SUBCOMMANDS_FOR_SECURITY_CHECK = 50;

    // ──────────────────────────────────────────────────────────────────────
    // 常量 · CC bashPermissions.ts
    // ──────────────────────────────────────────────────────────────────────

    /** ENV 赋值前缀检测 · CC :93 {@code /^[A-Za-z_]\w*=/}。 */
    private static final Pattern ENV_VAR_ASSIGN_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=");

    /**
     * env 白名单（32 项）· CC :378-430 SAFE_ENV_VARS。
     * 白名单之外的 env 前缀在 stripSafeWrappers 不剥离（防 {@code DOCKER_HOST=evil docker ps}
     * 自动命中 {@code Bash(docker ps:*)}），deny/ask 桶经 stripAllLeadingEnvVars 全剥。
     */
    public static final Set<String> SAFE_ENV_VARS = Set.of(
        // Go — build/runtime settings only
        "GOEXPERIMENT", "GOOS", "GOARCH", "CGO_ENABLED", "GO111MODULE",
        // Rust — logging/debugging only
        "RUST_BACKTRACE", "RUST_LOG",
        // Node — environment name only (not NODE_OPTIONS!)
        "NODE_ENV",
        // Python — behavior flags only (not PYTHONPATH!)
        "PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE",
        // Pytest — test configuration
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD", "PYTEST_DEBUG",
        // API keys and authentication
        "ANTHROPIC_API_KEY",
        // Locale and character encoding
        "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_TIME", "CHARSET",
        // Terminal and display
        "TERM", "COLORTERM", "NO_COLOR", "FORCE_COLOR", "TZ",
        // Color configuration for various tools
        "LS_COLORS", "LSCOLORS", "GREP_COLOR", "GREP_COLORS", "GCC_COLORS",
        // Display formatting
        "TIME_STYLE", "BLOCK_SIZE", "BLOCKSIZE");

    /**
     * BARE_SHELL_PREFIXES 黑名单（21 项）· CC :196-226。
     * getFirstWordPrefix 拒绝为裸 shell/wrapper 生成建议
     * （{@code bash:*}/{@code sudo:*} 类建议 ≈ 放行任意代码）。
     */
    public static final Set<String> BARE_SHELL_PREFIXES = Set.of(
        "sh", "bash", "zsh", "fish", "csh", "tcsh", "ksh", "dash", "cmd",
        "powershell", "pwsh",
        // wrappers that exec their args as a command
        "env", "xargs",
        // SECURITY: 建议 Bash(nice:*) 约等于 Bash(*)
        "nice", "stdbuf", "nohup", "timeout", "time",
        // privilege escalation
        "sudo", "doas", "pkexec");

    /**
     * timeout flag 值白名单 · CC :620 TIMEOUT_FLAG_VALUE_RE
     * （signal 为 TERM/KILL/9，时长为 5/5s/10.5）。拒绝 {@code $(id)} 等注入字符——
     * {@code timeout -k$(id) 10 ls} 不得剥离成 {@code ls} 命中 {@code Bash(ls:*)}。
     */
    private static final Pattern TIMEOUT_FLAG_VALUE_RE = Pattern.compile("^[A-Za-z0-9_.+-]+$");

    /**
     * BINARY_HIJACK_VARS · CC :708 {@code /^(LD_|DYLD_|PATH$)/}。
     * sandbox excludedCommands 匹配的 blocklist；Java 端 excludedCommands 消费在
     * SandboxManager 域（T04 D9），本常量随 CC 保留供该路径使用。
     */
    public static final Pattern BINARY_HIJACK_VARS = Pattern.compile("^(LD_|DYLD_|PATH$)");

    /** stripSafeWrappers 的 env 白名单模式 · CC :575 ENV_VAR_PATTERN。 */
    private static final Pattern SAFE_ENV_VAR_PATTERN = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)=([A-Za-z0-9_./:-]+)[ \\t]+");

    /** stripAllLeadingEnvVars 的全量 env 模式 · CC :759-760。 */
    private static final Pattern ENV_VAR_PATTERN_ALL = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*(?:\\[[^\\]]*\\])?)\\+?=(?:'[^'\\n\\r]*'|\"(?:\\\\.|[^\"$`\\\\\\n\\r])*\"|\\\\.|[^ \\t\\n\\r$`;|&()<>\\\\'\"])*[ \\t]+");

    /**
     * SAFE_WRAPPER_PATTERNS（5 个 wrapper 正则）· CC :532-560。
     * SECURITY：横向空白用 {@code [ \t]+} 而非 {@code \s+}——{@code \s} 匹配换行，
     * 跨行匹配会把换行分隔的命令剥离错位。
     */
    private static final Pattern TIMEOUT_WRAPPER_PATTERN = Pattern.compile(
        "^timeout[ \\t]+(?:(?:--(?:foreground|preserve-status|verbose)"
            + "|--(?:kill-after|signal)=[A-Za-z0-9_.+-]+"
            + "|--(?:kill-after|signal)[ \\t]+[A-Za-z0-9_.+-]+"
            + "|-v|-[ks][ \\t]+[A-Za-z0-9_.+-]+|-[ks][A-Za-z0-9_.+-]+)[ \\t]+)*"
            + "(?:--[ \\t]+)?\\d+(?:\\.\\d+)?[smhd]?[ \\t]+");
    private static final Pattern TIME_WRAPPER_PATTERN = Pattern.compile(
        "^time[ \\t]+(?:--[ \\t]+)?");
    private static final Pattern NICE_WRAPPER_PATTERN = Pattern.compile(
        "^nice(?:[ \\t]+-n[ \\t]+-?\\d+|[ \\t]+-\\d+)?[ \\t]+(?:--[ \\t]+)?");
    private static final Pattern STDBUF_WRAPPER_PATTERN = Pattern.compile(
        "^stdbuf(?:[ \\t]+-[ioe][LN0-9]+)+[ \\t]+(?:--[ \\t]+)?");
    private static final Pattern NOHUP_WRAPPER_PATTERN = Pattern.compile(
        "^nohup[ \\t]+(?:--[ \\t]+)?");

    private static final List<Pattern> SAFE_WRAPPER_PATTERNS = List.of(
        TIMEOUT_WRAPPER_PATTERN, TIME_WRAPPER_PATTERN,
        NICE_WRAPPER_PATTERN, STDBUF_WRAPPER_PATTERN, NOHUP_WRAPPER_PATTERN);

    /** 重定向操作符 · CC extractOutputRedirections（commands.ts:634+，仅输出重定向）。 */
    private static final Pattern REDIRECTION_OPERATOR_RE = Pattern.compile("^[0-9]?&?>+$|^[0-9]?>&[0-9]*$");

    // ──────────────────────────────────────────────────────────────────────
    // 规则解析（对齐 CC shellRuleMatching.ts parsePermissionRule :159-184）
    // ──────────────────────────────────────────────────────────────────────

    /** 解析后的 shell 权限规则三型 · CC ShellPermissionRule（shellRuleMatching.ts:25-37）。 */
    public record ShellPermissionRule(
            /** exact | prefix | wildcard · CC type 判别。 */
            String type,
            /** exact 规则的命令原文（type=exact）。 */
            String command,
            /** prefix 规则的前缀（type=prefix，CC prefix 字段）。 */
            String prefix,
            /** wildcard 规则的模式（type=wildcard，CC pattern 字段）。 */
            String pattern) {
    }

    /** 重定向剥离结果 · CC extractOutputRedirections 返回结构（commands.ts:634-638）。 */
    public record RedirectionResult(
            /** 剥离输出重定向后的命令（匹配用）。 */
            String commandWithoutRedirections,
            /** 解析失败（fail-closed）→ true，调用方须按危险处理 · CC :693-698。 */
            boolean hasDangerousRedirection) {
    }

    /**
     * 解析权限规则字符串为三型结构 · 对齐 CC {@code parsePermissionRule}
     * （shellRuleMatching.ts:159-184）。
     *
     * <p>优先识别 legacy {@code :*} 前缀语法（{@code npm:* → prefix npm}）；
     * 再识别含未转义 {@code *} 的 wildcard（{@code git *}）；否则 exact。
     *
     * @param ruleContent 规则内容（不含工具名前缀，如 {@code "npm publish:*"}）
     * @return 解析后的三型规则
     */
    public static ShellPermissionRule parseRule(String ruleContent) {
        String legacyPrefix = extractLegacyPrefix(ruleContent);
        if (legacyPrefix != null) {
            return new ShellPermissionRule("prefix", null, legacyPrefix, null);
        }
        if (hasUnescapedWildcard(ruleContent)) {
            return new ShellPermissionRule("wildcard", null, null, ruleContent);
        }
        return new ShellPermissionRule("exact", ruleContent, null, null);
    }

    /**
     * 提取 legacy {@code :*} 前缀 · 对齐 CC {@code permissionRuleExtractPrefix}
     * （shellRuleMatching.ts:43-48，正则 {@code /^(.+):\*$/}）。
     *
     * @param ruleContent 规则内容
     * @return 前缀；非 {@code :*} 语法或前缀为空返回 null
     */
    public static String extractLegacyPrefix(String ruleContent) {
        if (ruleContent != null && ruleContent.endsWith(":*") && ruleContent.length() > 2) {
            return ruleContent.substring(0, ruleContent.length() - 2);
        }
        return null;
    }

    /**
     * 是否含未转义 {@code *} · 对齐 CC {@code hasWildcards}（shellRuleMatching.ts:54-78）。
     * 以 {@code :*} 结尾视为 legacy 前缀语法；{@code *} 前为偶数个（含 0 个）反斜杠时为未转义。
     *
     * @param pattern 规则模式
     * @return true = 含未转义通配符
     */
    public static boolean hasUnescapedWildcard(String pattern) {
        if (pattern == null) {
            return false;
        }
        if (pattern.endsWith(":*")) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                int backslashCount = 0;
                for (int j = i - 1; j >= 0 && pattern.charAt(j) == '\\'; j--) {
                    backslashCount++;
                }
                if (backslashCount % 2 == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 通配匹配（对齐 CC shellRuleMatching.ts matchWildcardPattern :90-154，Bash 大小写敏感）
    // ──────────────────────────────────────────────────────────────────────

    /** 通配符转义占位符 · CC shellRuleMatching.ts:14-15 ESCAPED_STAR_PLACEHOLDER。 */
    private static final String ESCAPED_STAR_PLACEHOLDER = "\u0000ESCAPED_STAR\u0000";
    /** 反斜杠转义占位符 · CC shellRuleMatching.ts:14-15 ESCAPED_BACKSLASH_PLACEHOLDER。 */
    private static final String ESCAPED_BACKSLASH_PLACEHOLDER = "\u0000ESCAPED_BACKSLASH\u0000";

    /**
     * 通配模式匹配 · 对齐 CC {@code matchWildcardPattern}（shellRuleMatching.ts:90-154，
     * Bash 大小写敏感，caseInsensitive=false）。
     *
     * <ul>
     *   <li>{@code *} 匹配任意字符序列（含换行，DOTALL）</li>
     *   <li>{@code \*} 匹配字面星号；{@code \\} 匹配字面反斜杠</li>
     *   <li>模式以 {@code ' *'} 结尾且仅含一个未转义 {@code *} → 尾随参数可选
     *       （{@code 'git *'} 同时命中裸 {@code git}，:136-145）</li>
     * </ul>
     *
     * @param pattern 通配模式（如 {@code "git *"}）
     * @param command 实际命令
     * @return true = 匹配
     */
    public static boolean matchWildcardPattern(String pattern, String command) {
        // CC :95-96 先 trim 模式
        String trimmedPattern = pattern.trim();
        // 处理转义序列 \* 与 \\（CC :98-123）
        StringBuilder processed = new StringBuilder();
        int i = 0;
        while (i < trimmedPattern.length()) {
            char ch = trimmedPattern.charAt(i);
            if (ch == '\\' && i + 1 < trimmedPattern.length()) {
                char next = trimmedPattern.charAt(i + 1);
                if (next == '*') {
                    processed.append(ESCAPED_STAR_PLACEHOLDER);
                    i += 2;
                    continue;
                } else if (next == '\\') {
                    processed.append(ESCAPED_BACKSLASH_PLACEHOLDER);
                    i += 2;
                    continue;
                }
            }
            processed.append(ch);
            i++;
        }
        // 转义 regex 特殊字符（CC :126 字符类 [.+?^${}()|[\]\\'"]，不含 *）
        String escaped = escapeRegexSpecials(processed.toString());
        // 未转义 * → .*（CC :129）
        String withWildcards = escaped.replace("*", ".*");
        // 占位符还原为转义字面量（CC :132-134）
        String regexPattern = withWildcards
            .replace(ESCAPED_STAR_PLACEHOLDER, "\\*")
            .replace(ESCAPED_BACKSLASH_PLACEHOLDER, "\\\\");
        // 尾随 ' *' 且是唯一未转义通配符 → 尾随空格+参数整体可选（CC :136-145）
        int unescapedStarCount = countUnescapedStars(processed.toString());
        if (regexPattern.endsWith(" .*") && unescapedStarCount == 1) {
            regexPattern = regexPattern.substring(0, regexPattern.length() - 3) + "( .*)?";
        }
        // ^...$ + s（CC :148-151）：DOTALL 让 . 匹配换行。
        // 用 \z 而非 $ —— Java 的 $ 还匹配末尾换行符之前，JS 的 $（无 m 标志）只匹配字符串末尾。
        Pattern regex = Pattern.compile("^" + regexPattern + "\\z", Pattern.DOTALL);
        return regex.matcher(command).matches();
    }

    private static int countUnescapedStars(String processed) {
        int count = 0;
        for (int k = 0; k < processed.length(); k++) {
            if (processed.charAt(k) == '*') {
                count++;
            }
        }
        return count;
    }

    /** 转义 regex 特殊字符 · CC :126 {@code /[.+?^${}()|[\]\\'"]/g}（不含 {@code *}）。 */
    private static String escapeRegexSpecials(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            switch (ch) {
                case '.', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\', '\'', '"' ->
                    sb.append('\\').append(ch);
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 剥离三件套（对齐 CC bashPermissions.ts）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 剥离整行注释 · 对齐 CC {@code stripCommentLines}（:508-522）。
     *
     * <p>只剥离整行 {@code #} 注释（非行内注释）；全注释/空行时返回原命令。
     *
     * @param command 命令（可为多行）
     * @return 剥离注释行后的命令
     */
    public static String stripCommentLines(String command) {
        if (command == null || command.isEmpty()) {
            return command == null ? "" : command;
        }
        List<String> nonCommentLines = new ArrayList<>();
        for (String line : command.split("\n", -1)) {
            String trimmed = line.trim();
            // 保留非空且不以 # 开头的行
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                nonCommentLines.add(line);
            }
        }
        // 全部是注释/空行 → 返回原命令
        if (nonCommentLines.isEmpty()) {
            return command;
        }
        return String.join("\n", nonCommentLines);
    }

    /**
     * 剥离安全 env 前缀与 wrapper 命令 · 对齐 CC {@code stripSafeWrappers}（:524-615）。
     *
     * <p>两阶段 fixed-point：
     * <ol>
     *   <li>Phase 1：剥离 SAFE_ENV_VARS 白名单 env 前缀 + 注释（CC :580-596）</li>
     *   <li>Phase 2：剥离 5 个 wrapper（timeout/time/nice/stdbuf/nohup）+ 注释
     *       （CC :604-612）；wrapper 后不再剥 env（HackerOne #3543050，
     *       VAR=val 在 wrapper 后是命令而非赋值）</li>
     * </ol>
     *
     * <p>SECURITY：flag 值白名单（TIMEOUT_FLAG_VALUE_RE）拒绝 {@code timeout -k$(id) 10 ls}
     * 剥离成 {@code ls} 绕过 {@code Bash(ls:*)}。
     *
     * @param command 原始命令
     * @return 剥离后的命令
     */
    public static String stripSafeWrappers(String command) {
        if (command == null || command.isEmpty()) {
            return command == null ? "" : command;
        }
        String stripped = command;
        String previous = "";
        // Phase 1: 白名单 env + 注释 fixed-point（CC :580-596）
        while (!stripped.equals(previous)) {
            previous = stripped;
            stripped = stripCommentLines(stripped);
            Matcher envMatcher = SAFE_ENV_VAR_PATTERN.matcher(stripped);
            if (envMatcher.find()) {
                String varName = envMatcher.group(1);
                if (SAFE_ENV_VARS.contains(varName)) {
                    stripped = stripped.substring(envMatcher.end());
                }
            }
        }
        // Phase 2: wrapper + 注释 fixed-point（CC :604-612），不剥 env
        previous = "";
        while (!stripped.equals(previous)) {
            previous = stripped;
            stripped = stripCommentLines(stripped);
            for (Pattern wrapper : SAFE_WRAPPER_PATTERNS) {
                stripped = wrapper.matcher(stripped).replaceFirst("");
            }
        }
        return stripped.trim();
    }

    /**
     * 剥离全部前置 env 前缀（任意变量名）· 对齐 CC {@code stripAllLeadingEnvVars}（:733-776）。
     *
     * <p>deny/ask 规则匹配用：{@code FOO=bar claude} 必须仍命中 {@code Bash(claude:*)} deny
     * （白名单限制仅适用于 allow 规则，deny 规则必须更难绕过）。
     *
     * <p>SECURITY：值模式排除 {@code $} / 反引号 / {@code ;|&()} / 重定向 / 引号 / 反斜杠
     * 等注入字符（:759-760）；横向空白 {@code [ \t]+} 防跨行剥离。
     *
     * @param command  原始命令
     * @param blocklist 可选正则：匹配的变量名不剥离且停止（BINARY_HIJACK_VARS 供
     *                  excludedCommands 用）；deny 规则传 null
     * @return 剥离后的命令
     */
    public static String stripAllLeadingEnvVars(String command, Pattern blocklist) {
        if (command == null || command.isEmpty()) {
            return command == null ? "" : command;
        }
        String stripped = command;
        String previous = "";
        while (!stripped.equals(previous)) {
            previous = stripped;
            stripped = stripCommentLines(stripped);
            Matcher matcher = ENV_VAR_PATTERN_ALL.matcher(stripped);
            if (!matcher.find()) {
                break;
            }
            if (blocklist != null && blocklist.matcher(matcher.group(1)).matches()) {
                break;
            }
            stripped = stripped.substring(matcher.end());
        }
        return stripped.trim();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 重定向剥离（对齐 CC commands.ts extractOutputRedirections :634-699）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 剥离输出重定向（匹配用）· 对齐 CC {@code extractOutputRedirections}
     * （commands.ts:634-699）的匹配用途：{@code Bash(python:*)} 应匹配
     * {@code python script.py &gt; output.txt}。
     *
     * <p>Java 简化（与 CC 的差异见类注释）：引号感知 token 扫描，按 token 剥离
     * {@code >} / {@code >>} / {@code 1>} / {@code 2>} / {@code &>} / {@code >&} /
     * {@code 2>&1} 操作符及其目标 token；未闭合引号（无法可靠解析）fail-closed
     * 返回原命令 + hasDangerousRedirection=true（CC :693-699 同向）。
     *
     * @param command 原始命令
     * @return 剥离结果
     */
    public static RedirectionResult extractOutputRedirections(String command) {
        if (command == null || command.isEmpty()) {
            return new RedirectionResult(command == null ? "" : command, false);
        }
        // fail-closed：未闭合引号无法可靠解析 → 原命令 + dangerous（CC :693-699）
        if (!hasBalancedQuotes(command)) {
            return new RedirectionResult(command, true);
        }
        List<String> tokens = shellSplit(command);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (isRedirectionOperator(token)) {
                // 剥操作符；下一个 token 若非分隔符即重定向目标，一并剥离（CC token 级剥离）
                if (i + 1 < tokens.size() && !isShellSeparator(tokens.get(i + 1))) {
                    i++;
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return new RedirectionResult(sb.toString(), false);
    }

    /** 是否输出重定向操作符 token · CC commands.ts 的 redirections operator 集。 */
    private static boolean isRedirectionOperator(String token) {
        return REDIRECTION_OPERATOR_RE.matcher(token).matches();
    }

    /** 是否 shell 顶层分隔符 token（splitCommandWithOperators 保留的操作符）。 */
    private static boolean isShellSeparator(String token) {
        return switch (token) {
            case ";", "&&", "||", "|", "&", "(", ")" -> true;
            default -> false;
        };
    }

    /** 引号是否闭合（单引号/双引号配平，忽略转义）。 */
    private static boolean hasBalancedQuotes(String command) {
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
                    i++; // 双引号内转义（\" 等）
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

    /**
     * 引号感知 token 分割 · CC shell-quote 的简化版（token 级，保留引号内容）。
     * 分隔符（; & | 及换行）作为独立 token 返回，供顶层剥离/拆分子命令用。
     */
    private static List<String> shellSplit(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                cur.append(c);
            } else if (inDouble) {
                if (c == '\\' && i + 1 < command.length()) {
                    cur.append(c).append(command.charAt(i + 1));
                    i++;
                } else {
                    if (c == '"') {
                        inDouble = false;
                    }
                    cur.append(c);
                }
            } else if (c == '\'') {
                inSingle = true;
                cur.append(c);
            } else if (c == '"') {
                inDouble = true;
                cur.append(c);
            } else if (c == '\\' && i + 1 < command.length()) {
                cur.append(c).append(command.charAt(i + 1));
                i++;
            } else if (isShellSeparator(String.valueOf(c))) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
                // && / || 双字符操作符
                if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    tokens.add("&&");
                    i++;
                } else if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    tokens.add("||");
                    i++;
                } else {
                    tokens.add(String.valueOf(c));
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    // ──────────────────────────────────────────────────────────────────────
    // compound 检测（对齐 CC :855-868 isCompoundCommand 预计算）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 是否复合命令 · 对齐 CC {@code splitCommand(cmd).length > 1}
     * （bashPermissions.ts:865，splitCommand = splitCommand_DEPRECATED）。
     *
     * <p>Java 简化：引号感知 split 后段数 &gt; 1（{@code ;} / {@code &&} / {@code ||} /
     * {@code |} / {@code &} / 换行 顶层分隔；转义/引号内分隔符不算）。
     *
     * @param command 待检命令
     * @return true = 复合命令（含多个子命令）
     */
    public static boolean isCompoundCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return splitCommandSegments(command).size() > 1;
    }

    /**
     * 拆分子命令段 · CC splitCommand_DEPRECATED 的 Java 简化版。
     * 引号感知（单/双引号、反斜杠转义内不分隔）。
     *
     * @param command 命令
     * @return 子命令段列表（顶层分隔符切分，段为 trim 后原文）
     */
    public static List<String> splitCommandSegments(String command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                cur.append(c);
            } else if (inDouble) {
                if (c == '\\' && i + 1 < command.length()) {
                    cur.append(c).append(command.charAt(i + 1));
                    i++;
                } else {
                    if (c == '"') {
                        inDouble = false;
                    }
                    cur.append(c);
                }
            } else if (c == '\'') {
                inSingle = true;
                cur.append(c);
            } else if (c == '"') {
                inDouble = true;
                cur.append(c);
            } else if (c == '\\' && i + 1 < command.length()) {
                cur.append(c).append(command.charAt(i + 1));
                i++;
            } else if (c == ';' || c == '&' || c == '|' || c == '\n') {
                flushSegment(segments, cur);
                if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    i++; // && 双字符
                } else if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    i++; // || 双字符
                }
            } else {
                cur.append(c);
            }
        }
        flushSegment(segments, cur);
        return segments;
    }

    private static void flushSegment(List<String> segments, StringBuilder cur) {
        String seg = cur.toString().trim();
        cur.setLength(0);
        if (!seg.isEmpty()) {
            segments.add(seg);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 规则匹配主入口（对齐 CC filterRulesByContentsMatchingInput :778-935）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 按内容过滤匹配规则 · 对齐 CC {@code filterRulesByContentsMatchingInput}
     * （bashPermissions.ts:778-935）。
     *
     * <p>流程：重定向剥离 → exact 双候选（原命令 + 无重定向）/prefix 单候选 →
     * stripSafeWrappers 派生候选 → deny/ask 时 stripAllLeadingEnvVars fixed-point
     * 展开（去重 seen Set）→ prefix 模式 compound guard（allow 桶）→
     * exact/prefix（含 xargs 前缀）/wildcard（exact 模式拒绝）。
     *
     * <p>[DEL-WF4-02] 不再做 compound 子命令展开：CC filterRulesByContentsMatchingInput
     * （bashPermissions.ts:826-853）只做 env/wrapper 交替剥离，无子命令展开；复合命令
     * 子命令 deny/ask 由主链逐子命令检查（checkSandboxAutoAllow :1303-1336 /
     * checkSemanticsDeny :1431-1453 等价），Java 侧由
     * {@link #matchingDenyOrAskRule} 承担（S01 接入 BashTool 沙箱 auto-allow 预检）。
     *
     * @param command          工具 input 的 command 字段原文（CC :787 先 trim）
     * @param rules            候选规则集（同一行为桶）
     * @param exactMode        true = exact 匹配模式（CC matchMode==='exact'：
     *                         通配拒绝、prefix 规则要求整体相等）；false = prefix 模式
     * @param stripAllEnvVars  deny/ask 桶 true：任意 env 前缀剥离（CC :826-853）
     * @param skipCompoundCheck deny/ask 桶 true：跳过 compound guard（CC :859-868）
     * @return 匹配的规则列表（顺序保持传入）
     */
    public static List<PermissionRule> filterRulesByContentsMatchingInput(
            String command,
            Collection<PermissionRule> rules,
            boolean exactMode,
            boolean stripAllEnvVars,
            boolean skipCompoundCheck) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        String cmd = command == null ? "" : command.trim();
        RedirectionResult redirectionResult = extractOutputRedirections(cmd);
        String commandWithoutRedirections = redirectionResult.commandWithoutRedirections();

        // exact 双候选 / prefix 单候选（CC :795-801）
        List<String> commandsForMatching = exactMode
            ? List.of(cmd, commandWithoutRedirections)
            : List.of(commandWithoutRedirections);

        // stripSafeWrappers 派生候选（CC :806-809）
        List<String> commandsToTry = new ArrayList<>();
        for (String candidate : commandsForMatching) {
            commandsToTry.add(candidate);
            String stripped = stripSafeWrappers(candidate);
            if (!stripped.equals(candidate)) {
                commandsToTry.add(stripped);
            }
        }

        // deny/ask：两种剥离交替 fixed-point 展开（CC :826-853）
        if (stripAllEnvVars) {
            Set<String> seen = new LinkedHashSet<>(commandsToTry);
            int startIdx = 0;
            while (startIdx < commandsToTry.size()) {
                int endIdx = commandsToTry.size();
                for (int i = startIdx; i < endIdx; i++) {
                    String candidate = commandsToTry.get(i);
                    if (candidate == null || candidate.isEmpty()) {
                        continue;
                    }
                    String envStripped = stripAllLeadingEnvVars(candidate, null);
                    if (seen.add(envStripped)) {
                        commandsToTry.add(envStripped);
                    }
                    String wrapperStripped = stripSafeWrappers(candidate);
                    if (seen.add(wrapperStripped)) {
                        commandsToTry.add(wrapperStripped);
                    }
                }
                startIdx = endIdx;
            }
        }

        // compound 状态预计算（CC :861-868，仅 prefix 模式且非 skipCompoundCheck）
        Map<String, Boolean> compoundCache = new HashMap<>();
        if (!exactMode && !skipCompoundCheck) {
            for (String candidate : commandsToTry) {
                compoundCache.computeIfAbsent(candidate, BashRuleMatcher::isCompoundCommand);
            }
        }

        List<PermissionRule> result = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule.ruleValue().ruleContent() == null) {
                continue; // whole-tool 规则不走内容匹配
            }
            if (matchesAgainstCandidates(rule.ruleValue().ruleContent(),
                    commandsToTry, exactMode, compoundCache)) {
                result.add(rule);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Bash 规则内容匹配完成: 候选命令 {} 个、剥离展开 {} 个、规则 {} 条命中 {} 条",
                commandsForMatching.size(), commandsToTry.size(), rules.size(), result.size());
        }
        return result;
    }

    /**
     * 单规则内容匹配 · {@link #filterRulesByContentsMatchingInput} 的逐规则变体
     * （供 RuleQuery 逐 rule 匹配路径复用）。
     *
     * @param ruleContent      规则内容（{@code Bash(...)} 括号内）
     * @param command          工具 input 的 command 字段原文
     * @param exactMode        true = exact 匹配模式
     * @param stripAllEnvVars  deny/ask 桶 true
     * @param skipCompoundCheck deny/ask 桶 true
     * @return true = 匹配
     */
    public static boolean matchesRuleContent(
            String ruleContent,
            String command,
            boolean exactMode,
            boolean stripAllEnvVars,
            boolean skipCompoundCheck) {
        if (ruleContent == null || command == null) {
            return false;
        }
        return !filterRulesByContentsMatchingInput(
            command, List.of(ruleForMatch(ruleContent)), exactMode, stripAllEnvVars, skipCompoundCheck)
            .isEmpty();
    }

    /** 构造临时规则用于单规则匹配（source/behavior 不影响内容匹配）。 */
    private static PermissionRule ruleForMatch(String ruleContent) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent(BASH_TOOL_NAME, ruleContent));
    }

    /** 对候选列表逐一匹配（CC :874-933 的 some 语义）。 */
    private static boolean matchesAgainstCandidates(
            String ruleContent,
            List<String> candidates,
            boolean exactMode,
            Map<String, Boolean> compoundCache) {
        ShellPermissionRule parsed = parseRule(ruleContent);
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            boolean compound = compoundCache.getOrDefault(candidate, false);
            switch (parsed.type()) {
                case "exact" -> {
                    if (parsed.command().equals(candidate)) {
                        return true;
                    }
                }
                case "prefix" -> {
                    if (exactMode) {
                        // exact 模式：prefix 规则要求整体相等（CC :879-882）
                        if (parsed.prefix().equals(candidate)) {
                            return true;
                        }
                    } else {
                        // SECURITY: prefix 规则不匹配复合命令（CC :883-893）
                        if (compound) {
                            break;
                        }
                        if (prefixMatchesCandidate(parsed.prefix(), candidate)) {
                            return true;
                        }
                    }
                }
                case "wildcard" -> {
                    // SECURITY FIX: exact 模式通配拒绝（CC :915-922）
                    if (exactMode) {
                        break;
                    }
                    // SECURITY: 通配规则不匹配复合命令（CC :923-928）
                    if (compound) {
                        break;
                    }
                    if (matchWildcardPattern(parsed.pattern(), candidate)) {
                        return true;
                    }
                }
                default -> {
                    // 不可达：parseRule 只产三型
                }
            }
        }
        return false;
    }

    /**
     * prefix 规则匹配（CC :894-912）：
     * {@code cmd === prefix} 或 {@code cmd.startsWith(prefix + ' ')}；
     * 以及 {@code xargs <prefix>} 裸 xargs 前缀（{@code Bash(grep:*)} 匹配
     * {@code xargs grep pattern}，自然词边界：{@code xargs -n1 grep} 不匹配）。
     */
    private static boolean prefixMatchesCandidate(String prefix, String candidate) {
        if (candidate.equals(prefix)) {
            return true;
        }
        if (candidate.startsWith(prefix + " ")) {
            return true;
        }
        String xargsPrefix = "xargs " + prefix;
        if (candidate.equals(xargsPrefix)) {
            return true;
        }
        return candidate.startsWith(xargsPrefix + " ");
    }

    // ──────────────────────────────────────────────────────────────────────
    // sandbox auto-allow 的 deny/ask 优先检查（对齐 CC checkSandboxAutoAllow :1270-1359）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * checkSandboxAutoAllow 的 deny/ask 优先检查 · 对齐 CC :1276-1348。
     *
     * <p>顺序：全命令 deny（:1284）→ compound 逐子命令 deny 优先 / ask 次之
     * （:1303-1336，防 wildcard ask 降级子命令 deny）→ 全命令 ask（:1339）。
     * 返回首个命中的 deny/ask 规则（含行为标记）；无命中返回 null（→ 沙箱 auto-allow）。
     *
     * <p>[WF-4 S01] 本方法是 CC checkSandboxAutoAllow deny/ask 预检 + compound 逐子命令
     * 检查（bashPermissions.ts:1303-1336）的 Java 主链实现；[DEL-WF4-02] 删除
     * filterRulesByContentsMatchingInput 内 [S09] 子命令展开后，复合命令子命令 deny/ask
     * 唯一收敛于此（BashTool 沙箱 auto-allow 前预检接入）。
     *
     * @param command 工具 input 的 command 字段原文
     * @param permCtx 权限上下文（deny/ask 桶）
     * @return 首个命中的 deny/ask 规则 + 行为标记；无命中 null
     */
    public static DenyOrAskRule matchingDenyOrAskRule(String command, ToolPermissionContext permCtx) {
        if (permCtx == null || command == null) {
            return null;
        }
        List<PermissionRule> denyRules = flatten(permCtx.alwaysDenyRules());
        List<PermissionRule> askRules = flatten(permCtx.alwaysAskRules());
        // 1. 全命令 deny（CC :1284-1293）
        PermissionRule deny = first(filterRulesByContentsMatchingInput(
            command, denyRules, false, true, true));
        if (deny != null) {
            return new DenyOrAskRule(deny, true);
        }
        // 2. compound：逐子命令 deny 优先、ask 次之（CC :1303-1336）
        List<String> subcommands = splitCommandSegments(command);
        if (subcommands.size() > 1) {
            PermissionRule firstAsk = null;
            for (String sub : subcommands) {
                PermissionRule subDeny = first(filterRulesByContentsMatchingInput(
                    sub, denyRules, false, true, true));
                if (subDeny != null) {
                    return new DenyOrAskRule(subDeny, true);
                }
                if (firstAsk == null) {
                    firstAsk = first(filterRulesByContentsMatchingInput(
                        sub, askRules, false, true, true));
                }
            }
            if (firstAsk != null) {
                return new DenyOrAskRule(firstAsk, false);
            }
        }
        // 3. 全命令 ask（CC :1339-1348）
        PermissionRule ask = first(filterRulesByContentsMatchingInput(
            command, askRules, false, true, true));
        return ask != null ? new DenyOrAskRule(ask, false) : null;
    }

    /**
     * deny/ask 优先检查的匹配结果：命中的规则 + 行为标记。
     *
     * @param rule 命中的 deny 或 ask 规则
     * @param deny true = deny 桶命中（CC :1284/:1313 返回 behavior 'deny'）；
     *             false = ask 桶命中（CC :1327/:1339 返回 behavior 'ask'）
     */
    public record DenyOrAskRule(PermissionRule rule, boolean deny) {}

    /**
     * misparsing ask 的 exact-allow 覆盖查询 · 对齐 CC {@code bashToolCheckExactMatchPermission}
     * （bashPermissions.ts:991-1021）在 misparsing gate（:2105-2117）内只消费的 allow 分支。
     *
     * <p>CC misparsing gate 判定 remainder 仍 misparsing-ask 后，先以 exact 匹配模式查 allow 桶；
     * 命中显式 allow 规则则 allow 覆盖（用户对该具体命令做过 conscious choice），否则才构造 ask。
     * 本方法只复刻 exact 匹配的 allow 分支（CC :1026-1035）；deny/ask 分支 misparsing gate 不消费。
     *
     * <p>SECURITY: 必须 exact 模式（exactMode=true）。CC exact 模式对 wildcard 规则恒拒绝、
     * prefix 规则要求整体相等（bashPermissions.ts:879-882 / :915-922），避免复用 prefix 模式
     * （3.5 步 {@code getAllowRuleByContentsForTool}）过宽放行 {@code Bash(git:*)} 前缀覆盖
     * misparsing ask（安全降级）。
     *
     * @param command 工具 input 的 command 字段原文
     * @param permCtx 权限上下文（allow 桶）
     * @return 首个 exact 命中的 allow 规则；无命中返回 {@code null}
     */
    public static PermissionRule matchingExactAllowRule(String command, ToolPermissionContext permCtx) {
        if (permCtx == null || command == null) {
            return null;
        }
        List<PermissionRule> allowRules = flatten(permCtx.alwaysAllowRules()).stream()
            .filter(r -> BASH_TOOL_NAME.equals(r.ruleValue().toolName()))
            .toList();
        PermissionRule exactAllow = first(filterRulesByContentsMatchingInput(
            command, allowRules, true, false, false));
        if (log.isDebugEnabled()) {
            log.debug("Bash exact-allow 覆盖查询完成: allow 桶 {} 条规则、command={}、命中={}",
                allowRules.size(), command, exactAllow != null);
        }
        return exactAllow;
    }

    /**
     * prefix 模式 deny 桶查询 · 对齐 CC {@code matchingRulesForInput(input, ctx, 'prefix')
     * .matchingDenyRules[0]}（bashPermissions.ts:937-986 + checkEarlyExitDeny :1402-1412）。
     *
     * <p>AST too-complex / checkSemantics 失败路径的 deny 早退用（IMP-4）：整命令 prefix 模式
     * 查 deny 桶（stripAllEnvVars=true + skipCompoundCheck=true，CC :957-966 deny/ask 桶同参）。
     * 命中 → 返回规则（deny）；无命中 → null（调用方落 Ask(Other)，不把 deny 降级为 ask）。
     *
     * <p>与 {@link #matchingDenyOrAskRule} 的区别：后者是 CC checkSandboxAutoAllow 预检
     * （含 compound 逐子命令 + ask 桶，bashPermissions.ts:1276-1348）；本方法只查整命令
     * deny 桶（CC checkEarlyExitDeny :1402-1406 语义，供 checkSemanticsDeny 逐子命令复用）。
     *
     * @param command 工具 input 的 command 字段原文（或子命令 .text span）
     * @param permCtx 权限上下文（deny 桶）
     * @return 首个 prefix 命中的 deny 规则；无命中返回 {@code null}
     */
    public static PermissionRule matchingPrefixDenyRule(String command, ToolPermissionContext permCtx) {
        if (permCtx == null || command == null) {
            return null;
        }
        List<PermissionRule> denyRules = flatten(permCtx.alwaysDenyRules());
        return first(filterRulesByContentsMatchingInput(
            command, denyRules, false, true, true));
    }

    /** 展开 source 分组的规则集合为列表。 */
    private static List<PermissionRule> flatten(Map<?, Set<PermissionRule>> bucket) {
        if (bucket == null) {
            return List.of();
        }
        List<PermissionRule> all = new ArrayList<>();
        for (Set<PermissionRule> rules : bucket.values()) {
            if (rules != null) {
                all.addAll(rules);
            }
        }
        return all;
    }

    private static PermissionRule first(List<PermissionRule> rules) {
        return rules.isEmpty() ? null : rules.get(0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 建议生成（对齐 CC bashPermissions.ts :161-337 + shellRuleMatching.ts :189-228）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 提取稳定命令前缀（命令 + 子命令）· 对齐 CC {@code getSimpleCommandPrefix}（:161-188）。
     *
     * <p>跳过 SAFE_ENV_VARS 白名单 env 赋值（非安全 env → null 回退 exact）；
     * 第二 token 必须是子命令形状 {@code /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/}。
     *
     * @param command 原始命令
     * @return 2 词前缀；无法提取返回 null
     */
    public static String getSimpleCommandPrefix(String command) {
        if (command == null) {
            return null;
        }
        String[] tokens = command.trim().split("\\s+");
        List<String> tokenList = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isEmpty()) {
                tokenList.add(t);
            }
        }
        if (tokenList.isEmpty()) {
            return null;
        }
        // 跳过白名单 env 赋值（CC :170-179）
        int i = 0;
        while (i < tokenList.size() && ENV_VAR_ASSIGN_RE.matcher(tokenList.get(i)).find()) {
            String varName = tokenList.get(i).split("=", 2)[0];
            if (!SAFE_ENV_VARS.contains(varName)) {
                return null;
            }
            i++;
        }
        List<String> remaining = tokenList.subList(i, tokenList.size());
        if (remaining.size() < 2) {
            return null;
        }
        String subcmd = remaining.get(1);
        // 第二 token 必须是子命令形状（非 flag/文件名/路径/URL/数字，CC :186）
        if (!subcmd.matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")) {
            return null;
        }
        return remaining.get(0) + " " + subcmd;
    }

    /**
     * UI 兜底首词提取 · 对齐 CC {@code getFirstWordPrefix}（:243-264）。
     * 与 getSimpleCommandPrefix 同款 SAFE_ENV_VARS 门 + BARE_SHELL_PREFIXES 黑名单拒绝
     * （{@code bash:*}/{@code sudo:*} 建议 ≈ 放行任意代码）。
     *
     * @param command 原始命令
     * @return 首词；无法提取返回 null
     */
    public static String getFirstWordPrefix(String command) {
        if (command == null) {
            return null;
        }
        String[] tokens = command.trim().split("\\s+");
        List<String> tokenList = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isEmpty()) {
                tokenList.add(t);
            }
        }
        int i = 0;
        while (i < tokenList.size() && ENV_VAR_ASSIGN_RE.matcher(tokenList.get(i)).find()) {
            String varName = tokenList.get(i).split("=", 2)[0];
            if (!SAFE_ENV_VARS.contains(varName)) {
                return null;
            }
            i++;
        }
        if (i >= tokenList.size()) {
            return null;
        }
        String cmd = tokenList.get(i);
        // 形状检查与 getSimpleCommandPrefix 一致（CC :261-262）
        if (!cmd.matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")) {
            return null;
        }
        if (BARE_SHELL_PREFIXES.contains(cmd)) {
            return null;
        }
        return cmd;
    }

    /**
     * heredoc 前稳定前缀提取 · 对齐 CC {@code extractPrefixBeforeHeredoc}（:307-337）。
     *
     * @param command 原始命令
     * @return {@code <<} 前的稳定前缀；无 heredoc 或无法提取返回 null
     */
    private static String extractPrefixBeforeHeredoc(String command) {
        if (command == null || !command.contains("<<")) {
            return null;
        }
        int idx = command.indexOf("<<");
        if (idx <= 0) {
            return null;
        }
        String before = command.substring(0, idx).trim();
        if (before.isEmpty()) {
            return null;
        }
        String prefix = getSimpleCommandPrefix(before);
        if (prefix != null) {
            return prefix;
        }
        // 兜底：跳过白名单 env 赋值取至多 2 token（CC :319-336）
        String[] tokens = before.split("\\s+");
        List<String> tokenList = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isEmpty()) {
                tokenList.add(t);
            }
        }
        int i = 0;
        while (i < tokenList.size() && ENV_VAR_ASSIGN_RE.matcher(tokenList.get(i)).find()) {
            String varName = tokenList.get(i).split("=", 2)[0];
            if (!SAFE_ENV_VARS.contains(varName)) {
                return null;
            }
            i++;
        }
        if (i >= tokenList.size()) {
            return null;
        }
        int end = Math.min(i + 2, tokenList.size());
        return String.join(" ", tokenList.subList(i, end));
    }

    /**
     * 精确命令的建议规则 · 对齐 CC {@code suggestionForExactCommand}（:266-295）。
     *
     * <p>heredoc → 前缀建议；多行 → 首行前缀建议；单行 → 2 词前缀建议
     * （{@code getSimpleCommandPrefix}）；否则 exact 建议。
     *
     * @param command 原始命令
     * @return 建议的 PermissionUpdate 列表（allow + localSettings）
     */
    public static List<PermissionUpdate> suggestionForExactCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        // heredoc 命令每次调用内容都变，exact 规则永不命中 → 前缀建议（CC :270-273）
        String heredocPrefix = extractPrefixBeforeHeredoc(command);
        if (heredocPrefix != null) {
            return suggestionForPrefix(heredocPrefix);
        }
        // 多行命令 → 首行前缀（CC :279-284）
        if (command.contains("\n")) {
            String firstLine = command.split("\n", 2)[0].trim();
            if (!firstLine.isEmpty()) {
                return suggestionForPrefix(firstLine);
            }
        }
        // 单行 → 2 词前缀（CC :289-292）
        String prefix = getSimpleCommandPrefix(command);
        if (prefix != null) {
            return suggestionForPrefix(prefix);
        }
        return suggestionForExactMatch(command);
    }

    /**
     * 前缀建议 · 对齐 CC {@code suggestionForPrefix}（shellRuleMatching.ts:211-228）：
     * {@code {prefix}:*} allow 规则写入 localSettings。
     *
     * @param prefix 命令前缀
     * @return 建议的 PermissionUpdate
     */
    public static List<PermissionUpdate> suggestionForPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        PermissionRule rule = new PermissionRule(
            PermissionRuleSource.LOCAL_SETTINGS,
            PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent(BASH_TOOL_NAME, prefix + ":*"));
        return List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(rule), PermissionBehavior.ALLOW));
    }

    /**
     * 精确命令建议 · 对齐 CC {@code suggestionForExactCommand} 的 exact 兜底
     * （shellRuleMatching.ts:189-206）：ruleContent = 命令原文。
     *
     * @param command 原始命令
     * @return 建议的 PermissionUpdate
     */
    private static List<PermissionUpdate> suggestionForExactMatch(String command) {
        PermissionRule rule = new PermissionRule(
            PermissionRuleSource.LOCAL_SETTINGS,
            PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent(BASH_TOOL_NAME, command));
        return List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(rule), PermissionBehavior.ALLOW));
    }

    private BashRuleMatcher() {
    }
}
