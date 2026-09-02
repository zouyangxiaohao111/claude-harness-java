package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PathValidation;
import com.nexusai.application.agent.permission.PathValidationEnv;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Bash 路径约束校验 · 对齐 CC {@code tools/BashTool/pathValidation.ts:1013-1109 checkPathConstraints}
 * + {@code utils/permissions/pathValidation.ts:331-485}（isDangerousRemovalPath / validatePath / isPathAllowed）。
 *
 * <p>补齐 Bash 侧 path 约束（登记于探查 D-03，BashTool.java 原注释自认 "path 约束未实现"）。
 * 实现四类安全防护（CC bashToolCheckPermission 第 3 步，在 allow 规则之前、deny/ask 之后）：
 * <ul>
 *   <li><b>路径越界</b>：ls /etc、cat ~/.ssh、find /tmp、git diff --no-index → ask</li>
 *   <li><b>cd+write</b>：{@code cd .claude && mv test.txt settings.json} → ask（相对 cwd 漂移）</li>
 *   <li><b>cd+redirect</b>：{@code cd x && echo hi > y} → ask（redirect 目标按原 cwd 校验不可靠）</li>
 *   <li><b>危险删除</b>：rm /、rm ~、rmdir /etc、rm /usr → ask（message 明确
 *       'cannot be auto-allowed by permission rules'，与 CC checkDangerousRemovalPaths 一致）</li>
 *   <li><b>sed 写文件</b>：{@code sed -i 's/x/y/' /etc/passwd} → ask（write 覆盖）</li>
 * </ul>
 *
 * <p>PATH_EXTRACTORS 36 命令表（cd/ls/find/mkdir/touch/rm/rmdir/mv/cp/cat/head/tail/sort/uniq/wc/
 * cut/paste/column/tr/file/stat/diff/awk/strings/hexdump/od/base64/nl/grep/rg/sed/git/jq/
 * sha256sum/sha1sum/md5sum）对齐 CC pathValidation.ts:190-509。{@code filterOutFlags} 处理 POSIX
 * {@code --}（防 {@code rm -- -/../.claude/settings.json} 攻击）。危险删除表复用
 * {@link PowerShellPermissionChain#isDangerousRemovalPath}（单一真理源，对齐 CC 共享
 * utils/permissions/pathValidation.ts）。
 *
 * <p>纯静态工具类；deny=Edit-deny 规则，ask=越界/不可静态校验（message-only，与 PowerShellPathValidator
 * 同款 Java 惯例，suggestions 传空 List）。isPathAllowed 核心逻辑（deny→auto-edit 安全→workdir→allow）
 * 与 PowerShellPathValidator 同构复刻（约 40 行，登记三处漂移风险，后续可抽共享 PathValidationUtils）。
 */
public final class BashPathValidator {

    private static final Logger log = LoggerFactory.getLogger(BashPathValidator.class);

    private BashPathValidator() {
        throw new AssertionError("utility class - do not instantiate");
    }

    /** 文件操作类型 · 对齐 CC utils/permissions/pathValidation.ts:27 FileOperationType。 */
    private static final String OP_READ = "read";
    private static final String OP_WRITE = "write";
    private static final String OP_CREATE = "create";

    /** 通配符正则（含 brace）· 对齐 CC utils/permissions/pathValidation.ts:25 GLOB_PATTERN_REGEX。 */
    private static final Pattern GLOB_PATTERN_REGEX = Pattern.compile("[*?\\[\\]{}]");

    /** 进程替换正则 · 对齐 CC pathValidation.ts:1028（{@code >> >( | > >( | <(}）。 */
    private static final Pattern PROCESS_SUBSTITUTION = Pattern.compile(">>\\s*>\\s*\\(|>\\s*>\\s*\\(|<\\s*\\(");

    // ════════════════════════════════════════════════════════════════════════
    // PATH_EXTRACTORS 36 命令表 · 对齐 CC pathValidation.ts:190-509
    // ════════════════════════════════════════════════════════════════════════
    private static final Set<String> SUPPORTED_PATH_COMMANDS = Set.of(
        "cd", "ls", "find", "mkdir", "touch", "rm", "rmdir", "mv", "cp", "cat",
        "head", "tail", "sort", "uniq", "wc", "cut", "paste", "column", "tr", "file",
        "stat", "diff", "awk", "strings", "hexdump", "od", "base64", "nl", "grep", "rg",
        "sed", "git", "jq", "sha256sum", "sha1sum", "md5sum");

    private static final Map<String, Function<List<String>, List<String>>> PATH_EXTRACTORS =
        Map.ofEntries(
            Map.entry("cd", BashPathValidator::extractCd),
            Map.entry("ls", BashPathValidator::extractLs),
            Map.entry("find", BashPathValidator::extractFind),
            Map.entry("tr", BashPathValidator::extractTr),
            Map.entry("grep", BashPathValidator::extractGrep),
            Map.entry("rg", BashPathValidator::extractRg),
            Map.entry("sed", BashPathValidator::extractSed),
            Map.entry("jq", BashPathValidator::extractJq),
            Map.entry("git", BashPathValidator::extractGit),
            // 其余简单命令 → filterOutFlags（对齐 CC 直接引用 filterOutFlags）
            Map.entry("mkdir", BashPathValidator::filterOutFlags),
            Map.entry("touch", BashPathValidator::filterOutFlags),
            Map.entry("rm", BashPathValidator::filterOutFlags),
            Map.entry("rmdir", BashPathValidator::filterOutFlags),
            Map.entry("mv", BashPathValidator::filterOutFlags),
            Map.entry("cp", BashPathValidator::filterOutFlags),
            Map.entry("cat", BashPathValidator::filterOutFlags),
            Map.entry("head", BashPathValidator::filterOutFlags),
            Map.entry("tail", BashPathValidator::filterOutFlags),
            Map.entry("sort", BashPathValidator::filterOutFlags),
            Map.entry("uniq", BashPathValidator::filterOutFlags),
            Map.entry("wc", BashPathValidator::filterOutFlags),
            Map.entry("cut", BashPathValidator::filterOutFlags),
            Map.entry("paste", BashPathValidator::filterOutFlags),
            Map.entry("column", BashPathValidator::filterOutFlags),
            Map.entry("file", BashPathValidator::filterOutFlags),
            Map.entry("stat", BashPathValidator::filterOutFlags),
            Map.entry("diff", BashPathValidator::filterOutFlags),
            Map.entry("awk", BashPathValidator::filterOutFlags),
            Map.entry("strings", BashPathValidator::filterOutFlags),
            Map.entry("hexdump", BashPathValidator::filterOutFlags),
            Map.entry("od", BashPathValidator::filterOutFlags),
            Map.entry("base64", BashPathValidator::filterOutFlags),
            Map.entry("nl", BashPathValidator::filterOutFlags),
            Map.entry("sha256sum", BashPathValidator::filterOutFlags),
            Map.entry("sha1sum", BashPathValidator::filterOutFlags),
            Map.entry("md5sum", BashPathValidator::filterOutFlags));

    /** 操作类型表 · 对齐 CC COMMAND_OPERATION_TYPE（pathValidation.ts:552-589）。 */
    private static final Map<String, String> COMMAND_OPERATION_TYPE = Map.ofEntries(
        Map.entry("cd", OP_READ), Map.entry("ls", OP_READ), Map.entry("find", OP_READ),
        Map.entry("mkdir", OP_CREATE), Map.entry("touch", OP_CREATE),
        Map.entry("rm", OP_WRITE), Map.entry("rmdir", OP_WRITE), Map.entry("mv", OP_WRITE),
        Map.entry("cp", OP_WRITE), Map.entry("cat", OP_READ), Map.entry("head", OP_READ),
        Map.entry("tail", OP_READ), Map.entry("sort", OP_READ), Map.entry("uniq", OP_READ),
        Map.entry("wc", OP_READ), Map.entry("cut", OP_READ), Map.entry("paste", OP_READ),
        Map.entry("column", OP_READ), Map.entry("tr", OP_READ), Map.entry("file", OP_READ),
        Map.entry("stat", OP_READ), Map.entry("diff", OP_READ), Map.entry("awk", OP_READ),
        Map.entry("strings", OP_READ), Map.entry("hexdump", OP_READ), Map.entry("od", OP_READ),
        Map.entry("base64", OP_READ), Map.entry("nl", OP_READ), Map.entry("grep", OP_READ),
        Map.entry("rg", OP_READ), Map.entry("sed", OP_WRITE), Map.entry("git", OP_READ),
        Map.entry("jq", OP_READ), Map.entry("sha256sum", OP_READ), Map.entry("sha1sum", OP_READ),
        Map.entry("md5sum", OP_READ));

    /**
     * 命令是否为写/创建操作 · 对齐 CC COMMAND_OPERATION_TYPE（pathValidation.ts:552-589）。
     *
     * <p>IMP-B2 RO-17b 消费：git-internal 路径写入检测（readOnlyValidation.ts:1840-1864
     * commandWritesToGitInternalPaths）用该表区分"可在新路径创建文件"的命令（write/create）
     * 与只读/删除/就地修改命令。rm/rmdir/sed 由调用方经 NON_CREATING_WRITE_COMMANDS 排除
     * （CC :1788-1817 仅 write/create 且非删除/就地修改命令产写路径）。
     *
     * @param command 命令首词
     * @return {@code true} 命令操作类型为 write 或 create
     */
    public static boolean isWriteOrCreateCommand(String command) {
        if (command == null) {
            return false;
        }
        String op = COMMAND_OPERATION_TYPE.get(command);
        return OP_WRITE.equals(op) || OP_CREATE.equals(op);
    }

    // ── PATH_EXTRACTORS 实现 ──
    /** cd：全部参数拼接为一条路径；无参 → 家目录。对齐 CC pathValidation.ts:195。
     *  Windows {@code cd /d <盘符路径>} 的 {@code /d}（盘符切换 flag）跳过——CC 无此问题
     *  （Unix cd 无 /d flag，Node path.resolve 宽容）；Java Paths.get 遇含 ':' 拼接路径
     *  抛 InvalidPathException → checkPermissions 异常降级 ask（2026-08-24 实测）。 */
    private static List<String> extractCd(List<String> args) {
        // Windows cd /d 盘符切换 flag：/d 是 flag 非路径，跳过（Unix cd 无此 flag）
        if (!args.isEmpty() && "/d".equals(args.get(0))
                && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            args = args.subList(1, args.size());
        }
        return args.isEmpty() ? List.of(System.getProperty("user.home", "")) : List.of(String.join(" ", args));
    }

    /** ls：filterOutFlags，默认 '.'。对齐 CC pathValidation.ts:198-201。 */
    private static List<String> extractLs(List<String> args) {
        List<String> paths = filterOutFlags(args);
        return paths.isEmpty() ? List.of(".") : paths;
    }

    /** find：收集直到首个非全局 flag 的位置路径 + path-taking flags。对齐 CC pathValidation.ts:211-269。 */
    private static List<String> extractFind(List<String> args) {
        List<String> paths = new ArrayList<>();
        Set<String> pathFlags = Set.of("-newer", "-anewer", "-cnewer", "-mnewer", "-samefile",
            "-path", "-wholename", "-ilname", "-lname", "-ipath", "-iwholename");
        Pattern newerPattern = Pattern.compile("^-newer[acmBt][acmtB]$");
        boolean foundNonGlobalFlag = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null || arg.isEmpty()) continue;
            if (afterDoubleDash) {
                paths.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                afterDoubleDash = true;
                continue;
            }
            if (arg.startsWith("-")) {
                if (Set.of("-H", "-L", "-P").contains(arg)) continue;
                foundNonGlobalFlag = true;
                if (pathFlags.contains(arg) || newerPattern.matcher(arg).matches()) {
                    if (i + 1 < args.size()) {
                        paths.add(args.get(i + 1));
                        i++;
                    }
                }
                continue;
            }
            if (!foundNonGlobalFlag) {
                paths.add(arg);
            }
        }
        return paths.isEmpty() ? List.of(".") : paths;
    }

    /** tr：跳过 SET1 或 SET1+SET2。对齐 CC pathValidation.ts:301-310。 */
    private static List<String> extractTr(List<String> args) {
        boolean hasDelete = args.stream().anyMatch(a ->
            a != null && ("-d".equals(a) || "--delete".equals(a)
                || (a.startsWith("-") && a.contains("d"))));
        List<String> nonFlags = filterOutFlags(args);
        return nonFlags.size() <= (hasDelete ? 1 : 2)
            ? List.of()
            : new ArrayList<>(nonFlags.subList(hasDelete ? 1 : 2, nonFlags.size()));
    }

    /** grep：pattern-then-paths，-r/-R 无 paths → 默认 '.'。对齐 CC pathValidation.ts:313-341。 */
    private static List<String> extractGrep(List<String> args) {
        Set<String> flags = Set.of("-e", "--regexp", "-f", "--file", "--exclude", "--include",
            "--exclude-dir", "--include-dir", "-m", "--max-count", "-A", "--after-context",
            "-B", "--before-context", "-C", "--context");
        List<String> paths = parsePatternCommand(args, flags, List.of());
        if (paths.isEmpty() && args.stream().anyMatch(a -> "-r".equals(a) || "-R".equals(a) || "--recursive".equals(a))) {
            return List.of(".");
        }
        return paths;
    }

    /** rg：pattern-then-paths，默认 '.'。对齐 CC pathValidation.ts:344-369。 */
    private static List<String> extractRg(List<String> args) {
        Set<String> flags = Set.of("-e", "--regexp", "-f", "--file", "-t", "--type", "-T",
            "--type-not", "-g", "--glob", "-m", "--max-count", "--max-depth", "-r", "--replace",
            "-A", "--after-context", "-B", "--before-context", "-C", "--context");
        return parsePatternCommand(args, flags, List.of("."));
    }

    /** sed：-f 脚本文件 + script 之后的位置路径。对齐 CC pathValidation.ts:372-428。 */
    private static List<String> extractSed(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean skipNext = false;
        boolean scriptFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            String arg = args.get(i);
            if (arg == null || arg.isEmpty()) continue;
            if (!afterDoubleDash && "--".equals(arg)) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && arg.startsWith("-")) {
                if ("-f".equals(arg) || "--file".equals(arg)) {
                    if (i + 1 < args.size()) {
                        paths.add(args.get(i + 1));
                        skipNext = true;
                    }
                    scriptFound = true;
                } else if ("-e".equals(arg) || "--expression".equals(arg)) {
                    skipNext = true;
                    scriptFound = true;
                } else if (arg.contains("e") || arg.contains("f")) {
                    scriptFound = true;
                }
                continue;
            }
            if (!scriptFound) {
                scriptFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths;
    }

    /** jq：filter-then-paths。对齐 CC pathValidation.ts:433-488。 */
    private static List<String> extractJq(List<String> args) {
        List<String> paths = new ArrayList<>();
        Set<String> flagsWithArgs = Set.of("-e", "--expression", "-f", "--from-file", "--arg",
            "--argjson", "--slurpfile", "--rawfile", "--args", "--jsonargs", "-L",
            "--library-path", "--indent", "--tab");
        boolean filterFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (!afterDoubleDash && "--".equals(arg)) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && arg.startsWith("-")) {
                String flag = arg.split("=")[0];
                if ("-e".equals(flag) || "--expression".equals(flag)) {
                    filterFound = true;
                }
                if (flagsWithArgs.contains(flag) && !arg.contains("=")) {
                    i++;
                }
                continue;
            }
            if (!filterFound) {
                filterFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths;
    }

    /** git：仅 diff --no-index 取 2 路径。对齐 CC pathValidation.ts:491-508。 */
    private static List<String> extractGit(List<String> args) {
        if (!args.isEmpty() && "diff".equals(args.get(0)) && args.contains("--no-index")) {
            List<String> filePaths = filterOutFlags(new ArrayList<>(args.subList(1, args.size())));
            return filePaths.size() <= 2 ? filePaths : new ArrayList<>(filePaths.subList(0, 2));
        }
        return List.of();
    }

    /**
     * 剔 flag 保留位置参数，处理 POSIX {@code --}。对齐 CC pathValidation.ts:126-139 filterOutFlags。
     * {@code --} 之后全部视为位置参数（防 {@code rm -- -/../.claude/settings.json} 攻击）。
     */
    private static List<String> filterOutFlags(List<String> args) {
        List<String> result = new ArrayList<>();
        boolean afterDoubleDash = false;
        for (String arg : args) {
            if (afterDoubleDash) {
                result.add(arg);
            } else if ("--".equals(arg)) {
                afterDoubleDash = true;
            } else if (arg != null && !arg.startsWith("-")) {
                result.add(arg);
            }
        }
        return result;
    }

    /** grep/rg/jq 式 pattern-then-paths。对齐 CC pathValidation.ts:142-184 parsePatternCommand。 */
    private static List<String> parsePatternCommand(List<String> args, Set<String> flagsWithArgs, List<String> defaults) {
        List<String> paths = new ArrayList<>();
        boolean patternFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (!afterDoubleDash && "--".equals(arg)) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && arg.startsWith("-")) {
                String flag = arg.split("=")[0];
                if (flag != null && Set.of("-e", "--regexp", "-f", "--file").contains(flag)) {
                    patternFound = true;
                }
                if (flag != null && flagsWithArgs.contains(flag) && !arg.contains("=")) {
                    i++;
                }
                continue;
            }
            if (!patternFound) {
                patternFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths.isEmpty() ? defaults : paths;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 入口 · 对齐 CC pathValidation.ts:1013-1109 checkPathConstraints
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Bash 路径约束检查入口。
     *
     * <p>流程（CC 顺序）：进程替换 → 输出重定向提取 → 危险重定向 → 子命令切分 →
     * compoundCommandHasCd 判定 → 输出重定向校验 → 逐子命令路径校验（deny 优先于 ask）。
     *
     * @param command bash 命令（input.command）
     * @param cwd     校验基准 cwd（ctx.effectiveCwd）
     * @param permCtx 权限上下文（可为 null → 仅危险删除 / cd 复合 ask 生效）
     * @return deny | ask | passthrough
     */
    public static PermissionResult check(String command, Path cwd, ToolPermissionContext permCtx) {
        if (command == null || command.isBlank()) {
            return passthrough("空命令，无路径可校验");
        }
        // 1. 进程替换（可执行任意写文件命令，无法作为 redirect target 检出）→ ask（CC :1028-1038）
        if (PROCESS_SUBSTITUTION.matcher(command).find()) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: 进程替换命中, ask command={}", command);
            }
            return ask("Process substitution (>(...) or <(...)) can execute arbitrary commands and requires manual approval");
        }
        // 2. 输出重定向提取 + 危险标记（CC :1046-1061）
        BashParser.OutputRedirections redirs = BashParser.extractOutputRedirectTargets(command);
        if (redirs.hasDangerousRedirection()) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: 重定向目标含 shell 展开语法, ask command={}", command);
            }
            return ask("Shell expansion syntax in paths requires manual approval");
        }
        // 3. 子命令切分 + compoundCommandHasCd（CC :1090-1101）
        //    OPD-WF5-FS-071：AST argv 分支——一次 tokenize 直接产每子命令 argv，
        //    替代 splitCommand_DEPRECATED 字符串段 + shell-quote 二次 re-parse
        //    （shell-quote 单引号反斜杠 bug 会静默返回 [] 跳过路径校验，CC :1072-1101）。
        List<Subcommand> subcommands = tokenizeSubcommands(command);
        boolean compoundCommandHasCd = subcommands.stream()
            .anyMatch(sub -> isCdArgv(sub.argv()));
        // 4. 输出重定向校验（cd+redirect → ask；target /dev/null 跳过；create 类型校验）
        PermissionResult redirectResult = validateOutputRedirections(
            redirs.redirections(), cwd, permCtx, compoundCommandHasCd);
        if (!(redirectResult instanceof PermissionResult.Passthrough)) {
            return redirectResult;
        }
        // 5. 逐子命令 argv 路径校验（deny 优先于 ask）
        PermissionResult firstAsk = null;
        for (Subcommand sub : subcommands) {
            PermissionResult r = validateSinglePathCommandArgv(sub, cwd, permCtx, compoundCommandHasCd);
            if (r instanceof PermissionResult.Deny) {
                return r;
            }
            if (r instanceof PermissionResult.Ask && firstAsk == null) {
                firstAsk = r;
            }
        }
        if (firstAsk != null) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: 路径约束 ask 命中 compoundCd={} command={}",
                    compoundCommandHasCd, command);
            }
            return firstAsk;
        }
        return passthrough("所有路径命令校验通过");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 单命令校验 · 对齐 CC pathValidation.ts:888-922 validateSinglePathCommandArgv
    // ════════════════════════════════════════════════════════════════════════
    /** AST 子命令（raw text 保留供 sed 只读 allowlist；argv 为 tokenizer 已剥引号的参数）。 */
    private record Subcommand(String text, List<String> argv) {}

    /**
     * 子命令 argv 切分 · 对齐 CC checkPathConstraints astCommands 分支
     * （pathValidation.ts:1072-1101）：一次 {@link BashParser#tokenize} 直接产每子命令 argv，
     * 替代 splitCommand_DEPRECATED 字符串段 + shell-quote 二次 re-parse（shell-quote 单引号
     * 反斜杠 bug 会静默返回 [] 跳过路径校验，CC :1040-1045/:1072-1076）。
     *
     * <p>语义与 {@code splitCommandDeprecated} 对齐：OPERATOR 分隔符切分、剥输出重定向操作符
     * 及其静态 target（防 {@code ls > /etc/passwd} 把 target 当参数）；每子命令同时保留
     * raw text（token 原文空格拼接，供 {@code sedCommandIsAllowedByAllowlist(stripSafeWrappers(cmd.text))}
     * CC :912-916 用）。
     *
     * @param command 原始 bash 命令
     * @return 子命令 argv 列表（tokenize 失败 → 空）
     */
    private static List<Subcommand> tokenizeSubcommands(String command) {
        List<BashParser.Token> tokens;
        try {
            tokens = BashParser.tokenize(command);
        } catch (Exception e) {
            return List.of();
        }
        List<Subcommand> result = new ArrayList<>();
        List<String> curArgv = new ArrayList<>();
        StringBuilder curText = new StringBuilder();
        boolean skipRedirectTarget = false;
        for (BashParser.Token t : tokens) {
            BashParser.TokenKind k = t.kind();
            if (skipRedirectTarget) {
                skipRedirectTarget = false;
                continue;
            }
            if (k == BashParser.TokenKind.OPERATOR) {
                String op = t.text();
                if (op.equals(">") || op.equals(">>") || op.equals(">&") || op.equals("&>")
                        || op.equals("<") || op.equals("<<") || op.equals("<&") || op.equals("<<<")) {
                    skipRedirectTarget = true; // 剥重定向操作符 + 下一 token（target）
                    continue;
                }
                if (op.equals("&&") || op.equals("||") || op.equals(";") || op.equals(";;")
                        || op.equals(";&") || op.equals("|") || op.equals("&")) {
                    flushSubcommand(result, curArgv, curText);
                    continue;
                }
                continue;
            }
            if (k == BashParser.TokenKind.NEWLINE || k == BashParser.TokenKind.EOF) {
                flushSubcommand(result, curArgv, curText);
                continue;
            }
            if (k == BashParser.TokenKind.WORD || k == BashParser.TokenKind.STRING
                    || k == BashParser.TokenKind.RAW_STRING || k == BashParser.TokenKind.ANSI_C_STRING
                    || k == BashParser.TokenKind.VARIABLE || k == BashParser.TokenKind.COMMAND_SUBST
                    || k == BashParser.TokenKind.ARITH_EXPANSION) {
                if (!curArgv.isEmpty()) {
                    curText.append(' ');
                }
                curText.append(t.text());
                curArgv.add(t.text().replaceAll("^['\"]|['\"]$", ""));
            }
            // HEREDOC_TAG / HEREDOC_BODY / COMMENT → 跳过（非命令词）
        }
        return result;
    }

    private static void flushSubcommand(List<Subcommand> result, List<String> curArgv, StringBuilder curText) {
        if (!curArgv.isEmpty()) {
            result.add(new Subcommand(curText.toString().trim(), new ArrayList<>(curArgv)));
        }
        curArgv.clear();
        curText.setLength(0);
    }

    /**
     * 单子命令 argv 路径校验 · 对齐 CC {@code validateSinglePathCommandArgv}
     * （pathValidation.ts:888-922）：用 tokenizer 已解析的 argv 直接校验，不再 re-parse
     * 命令字符串。先 {@link #stripWrappersFromArgv} 剥安全 wrapper（CC :894），再
     * baseCmd=argv[0] 判定 SUPPORTED_PATH_COMMANDS（CC :901-907）。
     *
     * @param sub                子命令（argv + raw text）
     * @param cwd                校验基准 cwd
     * @param permCtx            权限上下文
     * @param compoundCommandHasCd 复合命令是否含 cd
     * @return deny | ask | passthrough
     */
    private static PermissionResult validateSinglePathCommandArgv(Subcommand sub, Path cwd,
            ToolPermissionContext permCtx, boolean compoundCommandHasCd) {
        List<String> stripped = stripWrappersFromArgv(sub.argv());
        if (stripped.isEmpty()) {
            return passthrough("空命令 - 无路径可校验");
        }
        String baseCmd = stripped.get(0);
        List<String> args = new ArrayList<>(stripped.subList(1, stripped.size()));
        if (!SUPPORTED_PATH_COMMANDS.contains(baseCmd)) {
            return passthrough("命令 '" + baseCmd + "' 不是路径受限命令");
        }
        // sed 只读覆盖（-n p 纯读 → read；否则 write）· CC :912-916（argv 分支用
        //   sedCommandIsAllowedByAllowlist(stripSafeWrappers(cmd.text))，raw text 保引号）
        String operationTypeOverride = baseCmd.equals("sed")
            && SedValidation.sedCommandIsAllowedByAllowlist(
                BashRuleMatcher.stripSafeWrappers(sub.text()), false)
            ? OP_READ : null;
        return createPathChecker(baseCmd, operationTypeOverride)
            .apply(args, cwd, permCtx, compoundCommandHasCd);
    }

    /**
     * argv 级安全 wrapper 剥离 · 对齐 CC {@code stripWrappersFromArgv}
     * （pathValidation.ts:1263-1303 + bashPermissions.ts:633-668 skipTimeoutFlags）：
     * 剥 time/nohup/timeout/nice/stdbuf/env 前置 wrapper；env 已由 tokenizer 分开
     * （CC AST envVars 分离，Java tokenizer 前缀 {@code VAR=val} 是独立 WORD）。
     *
     * <p>SECURITY 不变量与 CC 一致：timeout 时长非 {@code \d+(\.\d+)?[smhd]?} → 不剥
     * （CC :1274）；stdbuf/env 未知 flag → 不剥（fail-closed，CC :1233/:1251-1252）。
     *
     * @param argv 子命令 argv（tokenizer 已剥引号）
     * @return 剥 wrapper 后的 argv（不可解析时返回原 argv 副本）
     */
    private static List<String> stripWrappersFromArgv(List<String> argv) {
        List<String> a = new ArrayList<>(argv);
        for (;;) {
            String first = a.isEmpty() ? null : a.get(0);
            if ("time".equals(first) || "nohup".equals(first)) {
                a = new ArrayList<>(a.subList(a.size() > 1 && "--".equals(a.get(1)) ? 2 : 1, a.size()));
            } else if ("timeout".equals(first)) {
                int i = skipTimeoutFlags(a);
                if (i < 0 || i >= a.size() || !a.get(i).matches("\\d+(?:\\.\\d+)?[smhd]?")) {
                    return a; // 未识别时长 → 不剥（CC :1274，fail-closed）
                }
                a = new ArrayList<>(a.subList(i + 1, a.size()));
            } else if ("nice".equals(first)) {
                if (a.size() > 2 && "-n".equals(a.get(1)) && a.get(2).matches("-?\\d+")) {
                    a = new ArrayList<>(a.subList(a.size() > 3 && "--".equals(a.get(3)) ? 4 : 3, a.size()));
                } else if (a.size() > 1 && a.get(1).matches("-\\d+")) {
                    a = new ArrayList<>(a.subList(a.size() > 2 && "--".equals(a.get(2)) ? 3 : 2, a.size()));
                } else {
                    a = new ArrayList<>(a.subList(a.size() > 1 && "--".equals(a.get(1)) ? 2 : 1, a.size()));
                }
            } else if ("stdbuf".equals(first)) {
                int i = skipStdbufFlags(a);
                if (i < 0) {
                    return a;
                }
                a = new ArrayList<>(a.subList(i, a.size()));
            } else if ("env".equals(first)) {
                int i = skipEnvFlags(a);
                if (i < 0) {
                    return a;
                }
                a = new ArrayList<>(a.subList(i, a.size()));
            } else {
                return a;
            }
        }
    }

    /** timeout flag 跳过索引 · 对齐 CC skipTimeoutFlags（bashPermissions.ts:633-668）。 */
    private static int skipTimeoutFlags(List<String> a) {
        int i = 1;
        while (i < a.size()) {
            String arg = a.get(i);
            String next = i + 1 < a.size() ? a.get(i + 1) : null;
            if (arg.equals("--foreground") || arg.equals("--preserve-status") || arg.equals("--verbose")) {
                i++;
            } else if (arg.matches("^--(?:kill-after|signal)=[A-Za-z0-9_.+-]+$")) {
                i++;
            } else if ((arg.equals("--kill-after") || arg.equals("--signal")) && next != null
                    && next.matches("[A-Za-z0-9_.+-]+")) {
                i += 2;
            } else if (arg.equals("--")) {
                i++;
                break; // end-of-options marker
            } else if (arg.startsWith("--")) {
                return -1;
            } else if (arg.equals("-v")) {
                i++;
            } else if ((arg.equals("-k") || arg.equals("-s")) && next != null
                    && next.matches("[A-Za-z0-9_.+-]+")) {
                i += 2;
            } else if (arg.matches("^-[ks][A-Za-z0-9_.+-]+$")) {
                i++;
            } else if (arg.startsWith("-")) {
                return -1;
            } else {
                break;
            }
        }
        return i;
    }

    /** stdbuf flag 跳过索引 · 对齐 CC skipStdbufFlags（pathValidation.ts:1225-1237）。 */
    private static int skipStdbufFlags(List<String> a) {
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
                return -1; // unknown flag: fail closed
            } else {
                break;
            }
        }
        return i > 1 && i < a.size() ? i : -1;
    }

    /** env flag 跳过索引 · 对齐 CC skipEnvFlags（pathValidation.ts:1244-1256）。 */
    private static int skipEnvFlags(List<String> a) {
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
                return -1; // -S/-C/-P/unknown: fail closed
            } else {
                break;
            }
        }
        return i < a.size() ? i : -1;
    }

    /** 路径检查器 · 对齐 CC createPathChecker（pathValidation.ts:703-784）。 */
    private static PathChecker createPathChecker(String command, String operationTypeOverride) {
        return (args, cwd, permCtx, compoundCommandHasCd) -> {
            PermissionResult result = validateCommandPaths(
                command, args, cwd, permCtx, compoundCommandHasCd, operationTypeOverride);
            if (result instanceof PermissionResult.Deny) {
                return result;
            }
            // 危险删除在显式 deny 之后、其他结果之前（CC :732-737）
            if ("rm".equals(command) || "rmdir".equals(command)) {
                PermissionResult dangerous = checkDangerousRemovalPaths(command, args, cwd);
                if (!(dangerous instanceof PermissionResult.Passthrough)) {
                    return dangerous;
                }
            }
            return result;
        };
    }

    @FunctionalInterface
    private interface PathChecker {
        PermissionResult apply(List<String> args, Path cwd, ToolPermissionContext permCtx,
                               boolean compoundCommandHasCd);
    }

    /** 命令路径校验 · 对齐 CC validateCommandPaths（pathValidation.ts:603-701）。 */
    private static PermissionResult validateCommandPaths(String command, List<String> args,
            Path cwd, ToolPermissionContext permCtx, boolean compoundCommandHasCd,
            String operationTypeOverride) {
        Function<List<String>, List<String>> extractor = PATH_EXTRACTORS.get(command);
        if (extractor == null) {
            return passthrough("命令 '" + command + "' 无路径提取器");
        }
        List<String> paths = extractor.apply(args);
        String operationType = operationTypeOverride != null ? operationTypeOverride : COMMAND_OPERATION_TYPE.get(command);

        // COMMAND_VALIDATOR（mv/cp 禁 flag，防 --target-directory 绕过）· CC :596-628
        if (("mv".equals(command) || "cp".equals(command)) && hasAnyFlag(args)) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: {} 含 flag, ask（可绕过路径提取）", command);
            }
            return ask(command + " with flags requires manual approval to ensure path safety. For security, "
                + "NexusAI cannot automatically validate " + command + " commands that use flags, "
                + "as some flags like --target-directory=PATH can bypass path validation.");
        }

        // cd+write 阻断 · CC :645-655
        if (compoundCommandHasCd && !OP_READ.equals(operationType)) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: cd+write 复合命令, ask command={}", command);
            }
            return ask("Commands that change directories and perform write operations require explicit "
                + "approval to ensure paths are evaluated correctly. For security, NexusAI cannot "
                + "automatically determine the final working directory when 'cd' is used in compound commands.");
        }

        for (String path : paths) {
            PathCheck v = validatePath(path, cwd, permCtx, operationType);
            if (!v.allowed()) {
                if (v.rule() != null) {
                    return deny("路径 '" + v.resolvedPath() + "' 被 Edit deny 规则阻断",
                        new PermissionDecisionReason.Rule(v.rule()));
                }
                return ask(v.message() != null ? v.message()
                    : command + " in '" + v.resolvedPath() + "' was blocked. For security, NexusAI may "
                        + "only access the allowed working directories for this session.");
            }
        }
        return passthrough("路径校验通过: " + command);
    }

    private static boolean hasAnyFlag(List<String> args) {
        for (String arg : args) {
            if (arg != null && arg.startsWith("-")) {
                return true;
            }
        }
        return false;
    }

    /** 危险删除硬 ask · 对齐 CC checkDangerousRemovalPaths（pathValidation.ts:70-108）。 */
    private static PermissionResult checkDangerousRemovalPaths(String command, List<String> args, Path cwd) {
        Function<List<String>, List<String>> extractor = PATH_EXTRACTORS.get(command);
        List<String> paths = extractor.apply(args);
        for (String path : paths) {
            String cleanPath = expandTilde(path.replaceAll("^['\"]|['\"]$", ""));
            String absolutePath = isAbsoluteLike(cleanPath)
                ? normalizeSlashes(cleanPath)
                : resolveAgainstCwd(cleanPath, cwd);
            if (PowerShellPermissionChain.isDangerousRemovalPath(absolutePath)) {
                if (log.isDebugEnabled()) {
                    log.debug("BashPathValidator: 危险删除命中 command={} path={}", command, absolutePath);
                }
                return ask("Dangerous " + command + " operation detected: '" + absolutePath
                    + "'\n\nThis command would remove a critical system directory. This requires explicit "
                    + "approval and cannot be auto-allowed by permission rules.");
            }
        }
        return passthrough("未检出危险删除: " + command);
    }

    /** 输出重定向校验 · 对齐 CC validateOutputRedirections（pathValidation.ts:924-1003）。 */
    private static PermissionResult validateOutputRedirections(List<BashParser.RedirectTarget> redirections,
            Path cwd, ToolPermissionContext permCtx, boolean compoundCommandHasCd) {
        if (compoundCommandHasCd && !redirections.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("BashPathValidator: cd+redirect 复合命令, ask redirections={}", redirections.size());
            }
            return ask("Commands that change directories and write via output redirection require explicit "
                + "approval to ensure paths are evaluated correctly. For security, NexusAI cannot "
                + "automatically determine the final working directory when 'cd' is used in compound commands.");
        }
        for (BashParser.RedirectTarget r : redirections) {
            String target = r.target();
            if ("/dev/null".equals(target)) {
                continue; // /dev/null 恒安全（丢弃输出）
            }
            PathCheck v = validatePath(target, cwd, permCtx, OP_CREATE);
            if (!v.allowed()) {
                if (v.rule() != null) {
                    return deny("Output redirection to '" + v.resolvedPath() + "' was blocked by a deny rule.",
                        new PermissionDecisionReason.Rule(v.rule()));
                }
                return ask(v.message() != null ? v.message()
                    : "Output redirection to '" + v.resolvedPath() + "' was blocked. For security, NexusAI "
                        + "may only write to files in the allowed working directories for this session.");
            }
        }
        return passthrough("未检出不安全重定向");
    }

    // ════════════════════════════════════════════════════════════════════════
    // validatePath + isPathAllowed · 对齐 CC utils/permissions/pathValidation.ts:373-485 / 141-263
    // ════════════════════════════════════════════════════════════════════════
    /** 路径校验结果 · 对齐 CC ResolvedPathCheckResult。 */
    private record PathCheck(boolean allowed, String resolvedPath, String message, PermissionRule rule) {}

    private static PathCheck validatePath(String path, Path cwd, ToolPermissionContext permCtx, String operationType) {
        String cleanPath = expandTilde(path.replaceAll("^['\"]|['\"]$", ""));
        String normalizedPath = normalizeSlashes(cleanPath);

        // UNC 网络路径（凭据泄漏）→ 阻断（CC :383-392）
        if (normalizedPath.startsWith("//") || normalizedPath.toLowerCase().contains("davwwwroot")
                || normalizedPath.toLowerCase().contains("@ssl@")) {
            return new PathCheck(false, normalizedPath, "UNC 网络路径需人工审批", null);
        }
        // tilde 变体（~user/~+/~-）→ ask（CC :401-411）
        if (cleanPath.startsWith("~")) {
            return new PathCheck(false, normalizedPath, "Tilde 展开变体（~user, ~+, ~-）路径需人工审批", null);
        }
        // shell 展开语法（$ / % / = 开头）→ ask（CC :423-436）
        if (cleanPath.contains("$") || cleanPath.contains("%") || cleanPath.startsWith("=")) {
            return new PathCheck(false, normalizedPath, "路径含 shell 展开语法，需人工审批", null);
        }
        // glob（CC :443-463）
        if (GLOB_PATTERN_REGEX.matcher(normalizedPath).find()) {
            if (OP_WRITE.equals(operationType) || OP_CREATE.equals(operationType)) {
                return new PathCheck(false, normalizedPath,
                    "写操作不允许 glob 通配符，请指定精确路径", null);
            }
            return validateGlobPattern(normalizedPath, cwd, permCtx, operationType);
        }
        // 常规解析 + isPathAllowed（CC :465-485）
        String abs = resolveAgainstCwd(normalizedPath, cwd);
        return isPathAllowed(normalizeSlashes(abs), cwd, permCtx, operationType);
    }

    /** glob 模式校验（基目录解析）。对齐 CC validateGlobPattern（utils/pathValidation.ts:269-316）。 */
    private static PathCheck validateGlobPattern(String cleanPath, Path cwd, ToolPermissionContext permCtx, String operationType) {
        if (containsPathTraversal(cleanPath)) {
            String abs = resolveAgainstCwd(cleanPath, cwd);
            return isPathAllowed(normalizeSlashes(abs), cwd, permCtx, operationType);
        }
        String basePath = getGlobBaseDirectory(cleanPath);
        String absBase = resolveAgainstCwd(basePath, cwd);
        return isPathAllowed(normalizeSlashes(absBase), cwd, permCtx, operationType);
    }

    private static boolean containsPathTraversal(String s) {
        return s.contains("..");
    }

    private static String getGlobBaseDirectory(String filePath) {
        java.util.regex.Matcher m = GLOB_PATTERN_REGEX.matcher(filePath);
        if (!m.find()) {
            return filePath;
        }
        String beforeGlob = filePath.substring(0, m.start());
        int lastSep = Math.max(beforeGlob.lastIndexOf('/'), beforeGlob.lastIndexOf('\\'));
        if (lastSep == -1) {
            return ".";
        }
        String base = beforeGlob.substring(0, lastSep + 1);
        return base.isEmpty() ? "/" : base;
    }

    /** 解析后路径判定 · 对齐 CC isPathAllowed（utils/pathValidation.ts:141-263）。
     *  OPD-WF5-02-05：步骤 2/2.5/3.5/3.7 委派核心 {@link PathValidation}（内部路径白名单 /
     *  auto-edit 安全检查含可疑 Windows 模式 / 沙箱写白名单），Bash 保留扩展层结构。 */
    private static PathCheck isPathAllowed(String resolvedPath, Path cwd, ToolPermissionContext permCtx, String operationType) {
        PathValidationEnv env = PathValidationEnv.forProcess(cwd);
        boolean read = OP_READ.equals(operationType);
        // 1. Edit deny 规则（CC :151-162；OPD-WF5-FS-052 root-relative，传 cwd 锚定根）
        PermissionRule deny = editDenyRule(resolvedPath, permCtx, cwd);
        if (deny != null) {
            return new PathCheck(false, resolvedPath, null, deny);
        }
        // 2. 内部可编辑路径（写/create；CC :164-176）
        if (!read) {
            PathValidation.InternalPathResult internalEdit = PathValidation.checkEditableInternalPath(resolvedPath, env);
            if (internalEdit.allowed()) {
                return new PathCheck(true, resolvedPath, null, null);
            }
        }
        // 2.5 写操作 auto-edit 安全（CC :181-196；含可疑 Windows 模式 / Claude config / 危险文件）
        if (!read) {
            PathValidation.SafetyCheckResult safety = PathValidation.checkPathSafetyForAutoEdit(resolvedPath, env);
            if (!safety.safe()) {
                return new PathCheck(false, resolvedPath, safety.message(), null);
            }
        }
        // 3. 工作目录内（CC :201-211）
        boolean inWorkingDir = isInWorkingDir(resolvedPath, cwd, permCtx);
        if (inWorkingDir) {
            if (read || (permCtx != null && permCtx.mode() == PermissionMode.ACCEPT_EDITS)) {
                return new PathCheck(true, resolvedPath, null, null);
            }
        }
        // 3.5 内部可读路径（读；CC :215-223）
        if (read) {
            PathValidation.InternalPathResult internalRead = PathValidation.checkReadableInternalPath(resolvedPath, env);
            if (internalRead.allowed()) {
                return new PathCheck(true, resolvedPath, null, null);
            }
        }
        // 3.7 sandbox write allowlist → 无配置（null）→ 不命中（沙箱执行域待专项探查 OPD-WF4-DEC-03）
        // 4. allow 规则（CC :248-259；OPD-WF5-FS-052 root-relative，传 cwd 锚定根）
        PermissionRule allow = editAllowRule(resolvedPath, permCtx, cwd);
        if (allow != null) {
            return new PathCheck(true, resolvedPath, null, allow);
        }
        // 5. 不在任何允许范围
        return new PathCheck(false, resolvedPath,
            "为安全起见，NexusAI 仅可访问本会话允许的工作目录内的文件：'" + resolvedPath + "' 在范围外", null);
    }

    /**
     * 工作目录内判定 · 对齐 CC {@code pathInAllowedWorkingPath}
     * （utils/permissions/filesystem.ts:683-707）+ {@code getPathsForPermissionCheck}
     * （utils/fsOperations.ts:288-382）双侧 realpath 语义。
     *
     * <p>G3-1（symlink 逃逸）：双侧（输入 resolved 路径 vs cwd/additionalWorkingDirectories）
     * 均先 {@link Path#toRealPath()}（存在时，解析全部 symlink 与 {@code ..}）后比对——
     * 项目内软链指向项目外文件不会被误判"在目录内"（{@code ./evil-link -> /etc/passwd}：
     * realpath 后 = /etc/passwd 不在 cwd 内 → 拒绝，读/写均拒）。目标不存在（ENOENT，写新文件）
     * → 找最深已存在祖先 realpath 再 rejoin 非存在尾段（对齐 CC {@code resolveDeepestExistingAncestorSync}
     * fsOperations.ts:215-270，防 {@code /cwd/symlinkdir/newfile → symlinkdir->/etc} 写逃逸）；
     * 全路径均不存在或解析失败 → 回退 lexical {@code toAbsolutePath().normalize()}
     * （CC safeResolvePath ENOENT 回退原路径语义，fsOperations.ts:138-178）。
     * Windows junction/mklink 亦经 {@code toRealPath()} 解析。
     *
     * <p>比较层归一（本遗留项）：最终包含判定委派 {@link #pathInWorkingPathNormalized}，
     * 对齐 CC {@code pathInWorkingPath}（filesystem.ts:709-744）——比对前对双侧做
     * (a) macOS {@code /private/var → /var}、{@code /private/tmp → /tmp} 物理路径前缀归一
     * （realpath 后 macOS 给物理路径 {@code /private/var/...}，与未解析工作目录 {@code /var/...}
     * 失配会误拒；CC 正则无条件应用，非 mac 平台真实路径不出现该前缀恒 no-op）；
     * (b) 大小写不敏感（macOS/Windows 文件系统语义，防 {@code .cLauDe/CoMmAnDs} 大小写变体
     * 绕过/误拒）。只改"判定在目录内"的比较方向，realpath 双侧展开（G3-1）与
     * deny/越界拒绝逻辑不动——原 escape（项目内→外）仍拒绝。
     *
     * @param resolvedPath 已解析的绝对路径（validatePath 产）
     * @param cwd          校验基准 cwd（effectiveCwd）
     * @param permCtx      权限上下文（additionalWorkingDirectories 扩展白名单）
     * @return 解析后物理路径是否在某工作目录内
     */
    private static boolean isInWorkingDir(String resolvedPath, Path cwd, ToolPermissionContext permCtx) {
        try {
            Path resolved = resolvePhysical(Paths.get(resolvedPath));
            if (cwd != null && pathInWorkingPathNormalized(resolved, resolvePhysical(cwd))) {
                return true;
            }
            if (permCtx != null && permCtx.additionalWorkingDirectories() != null) {
                for (var entry : permCtx.additionalWorkingDirectories().values()) {
                    if (entry.path() == null) continue;
                    Path extra = resolvePhysical(Paths.get(entry.path()));
                    if (pathInWorkingPathNormalized(resolved, extra)) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /**
     * 归一 + 大小写不敏感包含判定 · 对齐 CC {@code pathInWorkingPath}
     * （utils/permissions/filesystem.ts:709-744）完整比较语义：
     * {@code expandPath}（Java 侧由 resolvePhysical 已做 realpath/lexical 展开）→
     * macOS {@code /private/var|/private/tmp} 前缀归一 → {@code normalizeCaseForComparison}
     * 小写归一 → {@code relativePath} 包含判定。
     *
     * <p>isInWorkingDir 使用；包私有暴露供聚焦测试（跨平台确定性验证，不受
     * OS 平台/真实文件系统大小写语义影响）。
     *
     * @param resolvedPath 已解析物理路径（resolvePhysical 产出）
     * @param workingPath  工作目录（resolvePhysical 产出）
     * @return resolvedPath 是否在 workingPath 目录内（归一 + 大小写不敏感）
     */
    static boolean pathInWorkingPathNormalized(Path resolvedPath, Path workingPath) {
        Path r = normalizeMacPrivateSymlinks(resolvedPath);
        Path w = normalizeMacPrivateSymlinks(workingPath);
        boolean inside = pathStartsWithIgnoreCase(w, r);
        if (log.isDebugEnabled()) {
            log.debug("BashPathValidator: 目录包含判定 resolved={} working={} inside={}",
                r, w, inside);
        }
        return inside;
    }

    /**
     * macOS 物理路径前缀归一 · 对齐 CC {@code pathInWorkingPath} 的
     * {@code .replace(/^\/private\/var\//, '/var/').replace(/^\/private\/tmp(\/|$)/, '/tmp$1')}
     * （filesystem.ts:716-721）。macOS 下 {@code /var}、{@code /tmp} 是指向
     * {@code /private/var}、{@code /private/tmp} 的 symlink，realpath 后给物理路径，
     * 与未解析工作目录比对会失配 → 双侧同款前缀归一。镜像 CC 正则边界：
     * {@code /private/var} 需带尾斜杠（{@code /private/var} 单独不归一），
     * {@code /private/tmp} 需斜杠或结尾。CC 无条件应用（非 mac 平台真实路径
     * 不出现该前缀恒为 no-op）。
     *
     * @param p 原始路径
     * @return 归一后路径（无变化时返回原对象）
     */
    static Path normalizeMacPrivateSymlinks(Path p) {
        if (p == null) {
            return null;
        }
        String s = normalizeSlashes(p.toString());
        String normalized;
        if (s.startsWith("/private/var/")) {
            normalized = "/var/" + s.substring("/private/var/".length());
        } else if (s.equals("/private/tmp") || s.startsWith("/private/tmp/")) {
            normalized = "/tmp" + s.substring("/private/tmp".length());
        } else {
            return p;
        }
        if (log.isDebugEnabled()) {
            log.debug("BashPathValidator: macOS /private 前缀归一 {} -> {}", s, normalized);
        }
        return Paths.get(normalized);
    }

    /**
     * 大小写不敏感目录包含 · 对齐 CC {@code pathInWorkingPath} 的
     * {@code normalizeCaseForComparison}（filesystem.ts:90-92 + 723-728）：
     * macOS/Windows 文件系统大小写不敏感，包含判定须忽略大小写，防
     * {@code .cLauDe/CoMmAnDs} 大小写变体绕过安全校验。
     *
     * <p>逐段比较 root + name 元素（等价 {@link Path#startsWith(Path)} 但忽略大小写），
     * 防 {@code /work/proj} vs {@code /work/projextra} 边界误判。仅用于"判定在目录内"，
     * 不改变 deny/escape 收紧方向（base 非 candidate 前缀 → false）。
     *
     * @param base      工作目录
     * @param candidate 待判定路径
     * @return candidate 是否在 base 目录内（忽略大小写）
     */
    static boolean pathStartsWithIgnoreCase(Path base, Path candidate) {
        if (base == null || candidate == null) {
            return false;
        }
        Path baseRoot = base.getRoot();
        Path candRoot = candidate.getRoot();
        if (baseRoot != null || candRoot != null) {
            if (baseRoot == null || candRoot == null) {
                return false;
            }
            if (!baseRoot.toString().equalsIgnoreCase(candRoot.toString())) {
                return false;
            }
        }
        if (base.getNameCount() > candidate.getNameCount()) {
            return false;
        }
        for (int i = 0; i < base.getNameCount(); i++) {
            if (!base.getName(i).toString().equalsIgnoreCase(candidate.getName(i).toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 物理路径规范化（realpath 优先，ENOENT 走最深已存在祖先，再失败走 lexical）·
     * 对齐 CC {@code safeResolvePath}（fsOperations.ts:138-178）+ {@code resolveDeepestExistingAncestorSync}
     * （fsOperations.ts:215-270）。Windows junction/mklink 经 {@code toRealPath()} 解析。
     */
    private static Path resolvePhysical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            // ENOENT / EACCES：找最深已存在祖先 realpath + rejoin 非存在尾段；无则 lexical
            Path deepest = resolveDeepestExistingAncestor(p.toAbsolutePath());
            return deepest != null ? deepest : p.toAbsolutePath().normalize();
        }
    }

    /**
     * 找最深已存在祖先 realpath，非存在尾段 rejoin · 对齐 CC {@code resolveDeepestExistingAncestorSync}
     * （fsOperations.ts:215-270，lstat 逐级上溯 + readlink 处理 dangling symlink）。
     * 全路径均不存在或祖先解析失败 → null（调用方回退 lexical）。
     */
    private static Path resolveDeepestExistingAncestor(Path abs) {
        ArrayDeque<String> segments = new ArrayDeque<>();
        Path dir = abs;
        for (;;) {
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) break;
            if (Files.isSymbolicLink(dir)) {
                // 已存在 symlink（live 或 dangling）：live → realpath；dangling → readlink 手动解析
                try {
                    return joinTail(dir.toRealPath(), segments);
                } catch (IOException e) {
                    try {
                        Path target = Files.readSymbolicLink(dir);
                        Path absTarget = target.isAbsolute() ? target : parent.resolve(target);
                        return joinTail(absTarget, segments);
                    } catch (IOException e2) {
                        return null;
                    }
                }
            }
            if (Files.exists(dir)) {
                // 已存在非 symlink 组件：一次 realpath 解析祖先中的 symlink；无则 null（按 lexical）
                try {
                    Path resolved = dir.toRealPath();
                    if (!resolved.equals(dir)) return joinTail(resolved, segments);
                } catch (IOException ignored) {
                }
                return null;
            }
            Path name = dir.getFileName();
            if (name == null) break;
            segments.push(name.toString());
            dir = parent;
        }
        return null;
    }

    /** 将非存在尾段 rejoin 到已解析祖先上。 */
    private static Path joinTail(Path base, ArrayDeque<String> segments) {
        Path result = base;
        for (String seg : segments) result = result.resolve(seg);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 路径辅助 · 对齐 CC utils/permissions/pathValidation.ts + PowerShellPathValidator 同构
    // ════════════════════════════════════════════════════════════════════════
    private static String expandTilde(String filePath) {
        if (filePath.equals("~") || filePath.startsWith("~/") || filePath.startsWith("~\\")) {
            return System.getProperty("user.home", "") + filePath.substring(1);
        }
        return filePath;
    }

    private static String normalizeSlashes(String s) {
        return s.replace('\\', '/');
    }

    private static boolean isAbsoluteLike(String path) {
        return path.startsWith("/") || path.startsWith("\\") || path.matches("^[a-zA-Z]:.*");
    }

    private static String resolveAgainstCwd(String path, Path cwd) {
        boolean absoluteLike = isAbsoluteLike(path);
        try {
            if (absoluteLike || Paths.get(path).isAbsolute()) {
                return Paths.get(path).normalize().toString();
            }
        } catch (Exception ignored) {
            // 非法路径字符 → 交给 isPathAllowed 失败
        }
        return Paths.get(cwd.toString(), path).normalize().toString();
    }

    private static PermissionRule editDenyRule(String path, ToolPermissionContext permCtx, Path cwd) {
        if (permCtx == null) return null;
        return RuleQuery.getEditRuleByContentsForPath(
            permCtx, path, PermissionBehavior.DENY, cwd == null ? null : cwd.toString());
    }

    private static PermissionRule editAllowRule(String path, ToolPermissionContext permCtx, Path cwd) {
        if (permCtx == null) return null;
        return RuleQuery.getEditRuleByContentsForPath(
            permCtx, path, PermissionBehavior.ALLOW, cwd == null ? null : cwd.toString());
    }

    /** argv 级 cd 判定 · 对齐 CC isNormalizedCdCommand（bashPermissions.ts:2603-2611）：先剥
     *  wrapper（timeout/time/nice/stdbuf/nohup/env）再首词匹配 cd/pushd/popd，防
     *  `timeout 10 cd .claude && mv ...` / `'cd' .claude`（引号包裹首词）漏检 cd。 */
    private static boolean isCdArgv(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return false;
        }
        List<String> stripped = stripWrappersFromArgv(argv);
        if (stripped.isEmpty()) {
            return false;
        }
        String first = stripped.get(0);
        return first.equals("cd") || first.equals("pushd") || first.equals("popd");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结果构造 · 与 PowerShellPathValidator 同款 message-only 约定
    // ════════════════════════════════════════════════════════════════════════
    private static PermissionResult deny(String message, PermissionDecisionReason reason) {
        return new PermissionResult.Deny(message, reason, null);
    }

    private static PermissionResult ask(String message) {
        return new PermissionResult.Ask(message, new PermissionDecisionReason.Other(message),
            List.of(), null, null, null, false, null, List.of());
    }

    private static PermissionResult passthrough(String message) {
        return new PermissionResult.Passthrough(message, new PermissionDecisionReason.Other(message),
            List.of(), null, null);
    }
}
