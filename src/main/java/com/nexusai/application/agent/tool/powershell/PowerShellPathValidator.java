package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PowerShell 路径约束校验 · 对齐 CC {@code pathValidation.ts:1528-1906 checkPathConstraints}
 * （powershellToolHasPermission 链内 step 4.44 deny-capable 路径检查，CC :1271-1279 消费）。
 *
 * <p>parse-succeeded 分支由 {@link PowerShellPermissionChain} 调用，承接：
 * <ul>
 *   <li>{@code extractPathsFromCommand}（pathValidation.ts:1304-1508）：按 CMDLET_PATH_CONFIG 提取
 *       位置/命名路径参数；复杂参数类型（数组字面量/子表达式/未知参数/复杂 colon 值）标记
 *       {@code hasUnvalidatablePathArg} → ask</li>
 *   <li>{@code dangerousRemovalDeny}（pathValidation.ts:848-857）：remove-item 对系统关键路径
 *       （/、~、/etc 等）硬 deny——与 parse-failed 分支的 :825-840 互补</li>
 *   <li>{@code validatePath} / {@code isPathAllowed}（pathValidation.ts:1013-977）：Edit deny 规则、
 *       auto-edit 路径安全、工作目录、allow 规则逐层判定</li>
 *   <li>{@code compoundCommandHasCd} 门控（pathValidation.ts:1606-1617）：cd 复合命令内任何路径操作
 *       都因 cwd 漂移而 ask（BashTool parity）</li>
 * </ul>
 *
 * <p>两遍遍历（pathValidation.ts:1541-1559）：deny 优先于 ask——先检查全部语句的 deny，再取首个 ask，
 * 防止 statement1 的 ask 掩盖 statement2 的 deny。纯静态工具类，无状态；Edit 规则复用
 * {@link RuleQuery#getEditRuleByContentsForPath}（与 {@code WritePermissionChecker} 同源近似）。
 */
public final class PowerShellPathValidator {

    private static final Logger log = LoggerFactory.getLogger(PowerShellPathValidator.class);

    private PowerShellPathValidator() {
        throw new AssertionError("utility class - do not instantiate");
    }

    // ── 内部可编辑/可读路径 carve-out（TR-C2-Q2 / 组 1-4⑤）──
    // isPathAllowed step2（写）/ step3.5（读）对齐 CC pathValidation.ts isPathAllowed 的
    // checkEditableInternalPath / checkReadableInternalPath：PowerShellPermissionChain 在
    // check() 内注入（setInternalPathCarveOut）本线程的判定函数（agent-memory/auto-memory/
    // bundled-skills），finally 清除。ThreadLocal 保证并发请求互不泄漏；未设置 → carve-out 关闭。
    private static final ThreadLocal<java.util.function.BiFunction<String, String, Boolean>>
        INTERNAL_PATH_CARVEOUT = new ThreadLocal<>();

    /** 注入内部路径 carve-out 判定（resolvedPath, operationType→是否内部路径放行）。package-private 供链注入。 */
    static void setInternalPathCarveOut(java.util.function.BiFunction<String, String, Boolean> fn) {
        INTERNAL_PATH_CARVEOUT.set(fn);
    }

    /** 清除本线程 carve-out 判定（PowerShellPermissionChain.check finally 调用，防跨请求泄漏）。 */
    static void clearInternalPathCarveOut() {
        INTERNAL_PATH_CARVEOUT.remove();
    }

    /** 内部路径判定是否命中（未注入 → false，carve-out 关闭走原 fail-safe）。 */
    private static boolean isInternalPathCarveOut(String resolvedPath, String operationType) {
        java.util.function.BiFunction<String, String, Boolean> fn = INTERNAL_PATH_CARVEOUT.get();
        if (fn == null) {
            return false;
        }
        return Boolean.TRUE.equals(fn.apply(resolvedPath, operationType));
    }

    /** 文件操作类型 · 对齐 CC pathValidation.ts:52 FileOperationType（'read' | 'write' | 'create'）。 */
    private static final String OP_READ = "read";
    private static final String OP_WRITE = "write";
    /** 重定向目标创建操作 · CC pathValidation.ts:1950/:2003 validatePath(target, cwd, ctx, 'create')。 */
    private static final String OP_CREATE = "create";

    /** 单个 cmdlet 的路径参数配置 · 对齐 CC pathValidation.ts:88-123 CmdletPathConfig。 */
    record CmdletPathConfig(String operationType, List<String> pathParams, List<String> leafOnlyPathParams,
                            List<String> knownSwitches, List<String> knownValueParams,
                            int positionalSkip, boolean optionalWrite) {
        CmdletPathConfig(String op, List<String> path, List<String> leaf, List<String> sw, List<String> val) {
            this(op, path, leaf, sw, val, 0, false);
        }
    }

    /** 安全路径元素类型（可静态提取为字面量路径）· CC pathValidation.ts:1294 SAFE_PATH_ELEMENT_TYPES。 */
    private static final Set<String> SAFE_PATH_ELEMENT_TYPES = Set.of("StringConstant", "Parameter");

    /** 通用 switch 参数（合并进每 cmdlet knownSwitches）· CC commonParameters.ts:12。 */
    private static final List<String> COMMON_SWITCHES = List.of("-verbose", "-debug");

    /** 通用取值参数（合并进每 cmdlet knownValueParams）· CC commonParameters.ts:14-25。 */
    private static final List<String> COMMON_VALUE_PARAMS = List.of(
        "-erroraction", "-warningaction", "-informationaction", "-progressaction",
        "-errorvariable", "-warningvariable", "-informationvariable",
        "-outvariable", "-outbuffer", "-pipelinevariable");

    /** PowerShell 通配符（braces 是字面量）· CC pathValidation.ts:50 GLOB_PATTERN_REGEX。 */
    private static final Pattern GLOB_PATTERN_REGEX = Pattern.compile("[*?\\[\\]]");

    /** 消息中最多列出的工作目录数 · CC pathValidation.ts:46 MAX_DIRS_TO_LIST。 */
    private static final int MAX_DIRS_TO_LIST = 5;

    // ════════════════════════════════════════════════════════════════════════
    // CMDLET_PATH_CONFIG · 对齐 CC pathValidation.ts:125-765（逐条转写，禁简化）
    // ════════════════════════════════════════════════════════════════════════
    private static final List<String> P = List.of("-path", "-literalpath", "-pspath", "-lp");
    private static final List<String> SW_FORCE = List.of("-force", "-whatif", "-confirm", "-usetransaction");
    private static final List<String> VAL_FILTER = List.of("-filter", "-include", "-exclude", "-credential");

    private static final Map<String, CmdletPathConfig> CMDLET_PATH_CONFIG = Map.ofEntries(
        // ── Write/create 操作（CC :125-374）──
        Map.entry("set-content", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction", "-nonewline", "-asbytestream"),
            List.of("-value", "-filter", "-include", "-exclude", "-credential", "-encoding", "-stream"))),
        Map.entry("add-content", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction", "-nonewline", "-asbytestream"),
            List.of("-value", "-filter", "-include", "-exclude", "-credential", "-encoding", "-stream"))),
        Map.entry("remove-item", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-recurse", "-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"))),
        Map.entry("clear-content", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"))),
        Map.entry("out-file", new CmdletPathConfig(OP_WRITE,
            List.of("-filepath", "-path", "-literalpath", "-pspath", "-lp"), List.of(),
            List.of("-append", "-force", "-noclobber", "-nonewline", "-whatif", "-confirm"),
            List.of("-inputobject", "-encoding", "-width"))),
        Map.entry("tee-object", new CmdletPathConfig(OP_WRITE,
            List.of("-filepath", "-path", "-literalpath", "-pspath", "-lp"), List.of(),
            List.of("-append"),
            List.of("-inputobject", "-variable", "-encoding"))),
        Map.entry("export-csv", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-append", "-force", "-noclobber", "-notypeinformation", "-includetypeinformation",
                "-useculture", "-noheader", "-whatif", "-confirm"),
            List.of("-inputobject", "-delimiter", "-encoding", "-quotefields", "-usequotes"))),
        Map.entry("export-clixml", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-noclobber", "-whatif", "-confirm"),
            List.of("-inputobject", "-depth", "-encoding"))),
        Map.entry("new-item", new CmdletPathConfig(OP_WRITE, P, List.of("-name"),
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-itemtype", "-value", "-credential", "-type"))),
        Map.entry("copy-item", new CmdletPathConfig(OP_WRITE,
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destination"), List.of(),
            List.of("-container", "-force", "-passthru", "-recurse", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-fromsession", "-tosession"))),
        Map.entry("move-item", new CmdletPathConfig(OP_WRITE,
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destination"), List.of(),
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential"))),
        Map.entry("rename-item", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-newname", "-credential", "-filter", "-include", "-exclude"))),
        Map.entry("set-item", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-value", "-credential", "-filter", "-include", "-exclude")))
    );
    // 追加 write 段剩余：*ItemProperty / clear-item / export-alias（CC :698-764）
    private static final Map<String, CmdletPathConfig> CMDLET_PATH_CONFIG_W2 = Map.ofEntries(
        Map.entry("set-itemproperty", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-value", "-type", "-filter", "-include", "-exclude", "-credential", "-inputobject"))),
        Map.entry("new-itemproperty", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-value", "-propertytype", "-type", "-filter", "-include", "-exclude", "-credential"))),
        Map.entry("remove-itemproperty", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"))),
        Map.entry("clear-item", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential"))),
        Map.entry("export-alias", new CmdletPathConfig(OP_WRITE, P, List.of(),
            List.of("-append", "-force", "-noclobber", "-passthru", "-whatif", "-confirm"),
            List.of("-name", "-description", "-scope", "-as"))),
        // ── Read 操作（CC :375-577）──
        Map.entry("get-content", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-force", "-usetransaction", "-wait", "-raw", "-asbytestream"),
            List.of("-readcount", "-totalcount", "-tail", "-first", "-head", "-last", "-filter", "-include",
                "-exclude", "-credential", "-delimiter", "-encoding", "-stream"))),
        Map.entry("get-childitem", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-recurse", "-force", "-name", "-usetransaction", "-followsymlink", "-directory", "-file",
                "-hidden", "-readonly", "-system"),
            List.of("-filter", "-include", "-exclude", "-depth", "-attributes", "-credential"))),
        Map.entry("get-item", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-force", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"))),
        Map.entry("get-itemproperty", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"))),
        Map.entry("get-itempropertyvalue", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"))),
        Map.entry("get-filehash", new CmdletPathConfig(OP_READ, P, List.of(), List.of(),
            List.of("-algorithm", "-inputstream"))),
        Map.entry("get-acl", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-audit", "-allcentralaccesspolicies", "-usetransaction"),
            List.of("-inputobject", "-filter", "-include", "-exclude"))),
        Map.entry("format-hex", new CmdletPathConfig(OP_READ, P, List.of(), List.of("-raw"),
            List.of("-inputobject", "-encoding", "-count", "-offset"))),
        Map.entry("test-path", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-isvalid", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-pathtype", "-credential", "-olderthan", "-newerthan"))),
        Map.entry("resolve-path", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-relative", "-usetransaction", "-force"),
            List.of("-credential", "-relativebasepath"))),
        Map.entry("convert-path", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-usetransaction"), List.of())),
        Map.entry("select-string", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-simplematch", "-casesensitive", "-quiet", "-list", "-notmatch", "-allmatches",
                "-noemphasis", "-raw"),
            List.of("-inputobject", "-pattern", "-include", "-exclude", "-encoding", "-context", "-culture"))),
        Map.entry("set-location", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-passthru", "-usetransaction"), List.of("-stackname"))),
        Map.entry("push-location", new CmdletPathConfig(OP_READ, P, List.of(),
            List.of("-passthru", "-usetransaction"), List.of("-stackname"))),
        Map.entry("pop-location", new CmdletPathConfig(OP_READ, List.of(), List.of(),
            List.of("-passthru", "-usetransaction"), List.of("-stackname"))),
        Map.entry("select-xml", new CmdletPathConfig(OP_READ, P, List.of(), List.of(),
            List.of("-xml", "-content", "-xpath", "-namespace"))),
        Map.entry("get-winevent", new CmdletPathConfig(OP_READ, List.of("-path"), List.of(),
            List.of("-force", "-oldest"),
            List.of("-listlog", "-logname", "-listprovider", "-providername", "-maxevents", "-computername",
                "-credential", "-filterxpath", "-filterxml", "-filterhashtable"))),
        // ── 写路径 cmdlet（输出参数，CC :579-764）──
        Map.entry("invoke-webrequest", new CmdletPathConfig(OP_WRITE,
            List.of("-outfile", "-infile"), List.of(),
            List.of("-allowinsecureredirect", "-allowunencryptedauthentication", "-disablekeepalive",
                "-nobodyprogress", "-passthru", "-preservefileauthorizationmetadata", "-resume",
                "-skipcertificatecheck", "-skipheadervalidation", "-skiphttperrorcheck", "-usebasicparsing",
                "-usedefaultcredentials"),
            List.of("-uri", "-method", "-body", "-contenttype", "-headers", "-maximumredirection",
                "-maximumretrycount", "-proxy", "-proxycredential", "-retryintervalsec", "-sessionvariable",
                "-timeoutsec", "-token", "-transferencoding", "-useragent", "-websession", "-credential",
                "-authentication", "-certificate", "-certificatethumbprint", "-form", "-httpversion"),
            1, true))
    );
    private static final Map<String, CmdletPathConfig> CMDLET_PATH_CONFIG_W3 = Map.ofEntries(
        Map.entry("invoke-restmethod", new CmdletPathConfig(OP_WRITE,
            List.of("-outfile", "-infile"), List.of(),
            List.of("-allowinsecureredirect", "-allowunencryptedauthentication", "-disablekeepalive",
                "-followrellink", "-nobodyprogress", "-passthru", "-preservefileauthorizationmetadata",
                "-resume", "-skipcertificatecheck", "-skipheadervalidation", "-skiphttperrorcheck",
                "-usebasicparsing", "-usedefaultcredentials"),
            List.of("-uri", "-method", "-body", "-contenttype", "-headers", "-maximumfollowrellink",
                "-maximumredirection", "-maximumretrycount", "-proxy", "-proxycredential",
                "-responseheaderstvariable", "-retryintervalsec", "-sessionvariable", "-statuscodevariable",
                "-timeoutsec", "-token", "-transferencoding", "-useragent", "-websession", "-credential",
                "-authentication", "-certificate", "-certificatethumbprint", "-form", "-httpversion"),
            1, true)),
        Map.entry("expand-archive", new CmdletPathConfig(OP_WRITE,
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destinationpath"), List.of(),
            List.of("-force", "-passthru", "-whatif", "-confirm"), List.of())),
        Map.entry("compress-archive", new CmdletPathConfig(OP_WRITE,
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destinationpath"), List.of(),
            List.of("-force", "-update", "-passthru", "-whatif", "-confirm"),
            List.of("-compressionlevel")))
    );

    /** 合并查询 CMDLET_PATH_CONFIG（CC pathValidation.ts:1310 单表语义）。 */
    static CmdletPathConfig lookupCmdletConfig(String canonical) {
        CmdletPathConfig c = CMDLET_PATH_CONFIG.get(canonical);
        if (c != null) return c;
        c = CMDLET_PATH_CONFIG_W2.get(canonical);
        if (c != null) return c;
        return CMDLET_PATH_CONFIG_W3.get(canonical);
    }

    /** PS 参数前缀 dash 字符 · 对齐 CC parser.ts PS_TOKENIZER_DASH_CHARS。 */
    static boolean isDashChar(char c) {
        return c == '-' || c == '\u2013' || c == '\u2014' || c == '\u2015';
    }

    /** PS 参数名前缀匹配（CC pathValidation.ts:772-782 matchesParam，允许歧义前缀）。 */
    static boolean matchesParam(String paramLower, List<String> paramList) {
        for (String p : paramList) {
            if (p.equals(paramLower) || (paramLower.length() > 1 && p.startsWith(paramLower))) {
                return true;
            }
        }
        return false;
    }

    /** colon 语法值含表达式构造（数组/子表达式/变量/反引号）· CC pathValidation.ts:793-803。 */
    static boolean hasComplexColonValue(String rawValue) {
        return rawValue.contains(",")
            || rawValue.startsWith("(")
            || rawValue.startsWith("[")
            || rawValue.contains("`")
            || rawValue.contains("@(")
            || rawValue.startsWith("@{")
            || rawValue.contains("$");
    }

    /** 是否 PS 参数（elementType=Parameter，无则按 dash 前缀）· CC parser.ts:1647。 */
    static boolean isPowerShellParameter(String arg, String elementType) {
        if (elementType != null) {
            return "Parameter".equals(elementType);
        }
        return !arg.isEmpty() && isDashChar(arg.charAt(0));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 入口 · 对齐 CC pathValidation.ts:1528-1567 checkPathConstraints
    // ════════════════════════════════════════════════════════════════════════
    /** 路径提取结果 · 对齐 CC extractPathsFromCommand 返回 {paths, operationType, hasUnvalidatablePathArg, optionalWrite}。 */
    record PathExtraction(List<String> paths, String operationType, boolean hasUnvalidatablePathArg, boolean optionalWrite) {}

    /**
     * 路径约束检查（两遍：deny 优先于 ask）。
     *
     * @param input                原始 input（仅消息展示，rule 匹配不依赖）
     * @param parsed               parse-succeeded 的 ParsedResult
     * @param permCtx              权限上下文（可为 null → 仅危险删除 deny / cd 复合 ask 生效）
     * @param cwd                  校验基准 cwd
     * @param compoundCommandHasCd 复合命令是否含 cwd 变更 cmdlet（CC :1532）
     * @return deny | ask | passthrough（passthrough 由调用方过滤不加入 decisions）
     */
    public static PermissionResult check(JsonNode input, PowerShellAstService.ParsedResult parsed,
                                         ToolPermissionContext permCtx, Path cwd, boolean compoundCommandHasCd) {
        if (!parsed.valid()) {
            return passthrough("Cannot validate paths for unparsed command");
        }
        PermissionResult firstAsk = null;
        for (PowerShellAstService.Statement st : parsed.statements()) {
            PermissionResult r = checkStatement(st, permCtx, cwd, compoundCommandHasCd);
            if (r instanceof PermissionResult.Deny) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPathValidator: 路径约束 deny 命中 statement={}", st.text());
                }
                return r;
            }
            if (r instanceof PermissionResult.Ask && firstAsk == null) {
                firstAsk = r;
            }
        }
        if (firstAsk != null && log.isDebugEnabled()) {
            log.debug("PowerShellPathValidator: 路径约束 ask 命中 compoundCd={}", compoundCommandHasCd);
        }
        // 重定向目标校验（CC pathValidation.ts:1937-2041 nested + statement redirections 目标
        // validatePath('create')，OPD-PS-06）。deny 优先于已记录的 ask（CC 两遍遍历 deny > ask 语义）；
        // 仅当无 statement ask 时补 redirection ask（CC firstAsk ??= 语义）。
        PermissionResult redirResult = checkRedirections(parsed, permCtx, cwd);
        if (redirResult instanceof PermissionResult.Deny) {
            return redirResult;
        }
        if (redirResult instanceof PermissionResult.Ask && firstAsk == null) {
            firstAsk = redirResult;
        }
        return firstAsk != null ? firstAsk : passthrough("All path constraints validated successfully");
    }

    /**
     * 重定向目标校验 · 对齐 CC pathValidation.ts:1937-2041（nested + statement redirections 目标
     * validatePath('create')）。deny-capable（Edit deny 规则命中 → deny）+ 工作目录判定
     * （'create' 按写路径处理，isPathAllowed 同 write，OPD-PS-06）。
     *
     * <p>滤除 merging（2>&1）与 $null（> $null）目标（CC :1942-1944/:1995-1997 一致，复用
     * {@link PowerShellPermissionChain#getFileRedirections}）。消息按 CC :1959-1963 语义：
     * Edit deny 规则 → deny；否则 → ask（工作目录外 / 危险路径）。
     *
     * @return deny（Edit deny 规则命中）| ask（目标被阻断/越界）| null（全部放行）
     */
    private static PermissionResult checkRedirections(PowerShellAstService.ParsedResult parsed,
                                                       ToolPermissionContext permCtx, Path cwd) {
        PermissionResult firstAsk = null;
        for (PowerShellAstService.Redirection r : PowerShellPermissionChain.getFileRedirections(parsed)) {
            PathCheck v = validatePath(r.target(), cwd, permCtx, OP_CREATE);
            if (v.allowed()) continue;
            if (v.rule() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPathValidator: 重定向目标 Edit deny 阻断 target={}", r.target());
                }
                return deny("输出重定向到 '" + v.resolvedPath() + "' 被阻断。为安全起见，Claude Code 仅可写入本会话允许的工作目录内的文件",
                    new PermissionDecisionReason.Rule(v.rule()));
            }
            if (firstAsk == null) {
                firstAsk = ask(v.message() != null ? v.message()
                    : "输出重定向目标 '" + v.resolvedPath() + "' 在允许工作目录之外，需人工审批");
            }
        }
        return firstAsk;
    }

    /** 单语句路径检查 · 对齐 CC pathValidation.ts:1569-1906 checkPathConstraintsForStatement。 */
    private static PermissionResult checkStatement(PowerShellAstService.Statement st,
                                                   ToolPermissionContext permCtx, Path cwd,
                                                   boolean compoundCommandHasCd) {
        PermissionResult firstAsk = null;
        if (compoundCommandHasCd) {
            firstAsk = ask("复合命令更改工作目录（Set-Location/Push-Location/Pop-Location/New-PSDrive）"
                + "——相对路径无法按原 cwd 校验，需人工审批");
        }
        String pipelineSourceText = null;
        for (PowerShellAstService.CommandElement cmd : st.commands()) {
            if (!"CommandAst".equals(cmd.elementType())) {
                pipelineSourceText = cmd.text();
                continue;
            }
            String canonical = ReadOnlyCommandTable.resolveToCanonical(cmd.name());
            PathExtraction ex = extractPaths(cmd, canonical);
            if (pipelineSourceText != null) {
                // 管道表达式源 deny 猜测（CC :1652-1681）：路径不可静态校验，但 Edit deny 仍须命中
                String stripped = pipelineSourceText.replaceAll("^['\"]|['\"]$", "");
                PermissionResult guessed = checkDenyRuleForGuessedPath(stripped, cwd, permCtx, ex.operationType());
                if (guessed instanceof PermissionResult.Deny) {
                    return guessed;
                }
                firstAsk = ask(canonical + " 的路径来自管道表达式源，无法静态校验，需人工审批");
                pipelineSourceText = null;
            }
            if (ex.hasUnvalidatablePathArg()) {
                firstAsk = ask(canonical + " 使用参数或复杂路径表达式（数组字面量/子表达式/未知参数），无法静态校验，需人工审批");
            }
            if (!OP_READ.equals(ex.operationType()) && !ex.optionalWrite()
                && ex.paths().isEmpty() && lookupCmdletConfig(canonical) != null) {
                firstAsk = ask(canonical + " 是写操作但无法确定目标路径，需人工审批");
                continue;
            }
            boolean isRemoval = "remove-item".equals(canonical);
            for (String filePath : ex.paths()) {
                // 原始路径先查危险删除（safeResolvePath 会把 / 规范化成 C:\，击败字符串比较）
                if (isRemoval && PowerShellPermissionChain.isDangerousRemovalRawPath(filePath)) {
                    return dangerousRemovalDeny(filePath);
                }
                PathCheck v = validatePath(filePath, cwd, permCtx, ex.operationType());
                if (isRemoval && v.resolvedPath() != null
                    && PowerShellPermissionChain.isDangerousRemovalPath(v.resolvedPath())) {
                    return dangerousRemovalDeny(v.resolvedPath());
                }
                if (!v.allowed()) {
                    if (v.rule() != null) {
                        return deny("路径 '" + v.resolvedPath() + "' 被 Edit deny 规则阻断",
                            new PermissionDecisionReason.Rule(v.rule()));
                    }
                    firstAsk = ask(v.message() != null ? v.message()
                        : canonical + " 目标 '" + v.resolvedPath() + "' 在工作目录之外，需人工审批");
                }
            }
        }
        // nestedCommands（控制流内嵌命令）镜像主循环（CC :1811-1906）
        PermissionResult nested = checkNestedCommands(st.nestedCommands(), permCtx, cwd);
        if (nested instanceof PermissionResult.Deny) {
            return nested;
        }
        if (nested instanceof PermissionResult.Ask && firstAsk == null) {
            firstAsk = nested;
        }
        return firstAsk;
    }

    // ════════════════════════════════════════════════════════════════════════
    // extractPathsFromCommand · 对齐 CC pathValidation.ts:1296-1508
    // ════════════════════════════════════════════════════════════════════════
    /** 路径校验结果 · 对齐 CC validatePath 返回 {allowed, resolvedPath, decisionReason}。 */
    record PathCheck(boolean allowed, String resolvedPath, String message, PermissionRule rule) {}

    /**
     * 从命令元素提取文件路径。位置参数 + 命名路径参数按 CMDLET_PATH_CONFIG 提取；
     * 复杂参数类型（数组/子表达式/未知参数/复杂 colon 值）置 hasUnvalidatablePathArg。
     */
    static PathExtraction extractPaths(PowerShellAstService.CommandElement cmd, String canonical) {
        CmdletPathConfig config = lookupCmdletConfig(canonical);
        if (config == null) {
            return new PathExtraction(List.of(), OP_READ, false, false);
        }
        List<String> switchParams = new ArrayList<>(config.knownSwitches());
        switchParams.addAll(COMMON_SWITCHES);
        List<String> valueParams = new ArrayList<>(config.knownValueParams());
        valueParams.addAll(COMMON_VALUE_PARAMS);

        List<String> paths = new ArrayList<>();
        List<String> args = cmd.args();
        List<String> elementTypes = cmd.elementTypes();
        boolean hasUnvalidatablePathArg = false;
        int positionalsSeen = 0;
        int positionalSkip = config.positionalSkip();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null || arg.isEmpty()) continue;
            String argElementType = elementTypes != null && elementTypes.size() > i + 1 ? elementTypes.get(i + 1) : null;
            if (isPowerShellParameter(arg, argElementType)) {
                String normalized = "-" + arg.substring(1);
                int colonIdx = normalized.indexOf(':', 1);
                String paramName = colonIdx > 0 ? normalized.substring(0, colonIdx) : normalized;
                String paramLower = paramName.toLowerCase();
                if (matchesParam(paramLower, config.pathParams())) {
                    String value = null;
                    if (colonIdx > 0) {
                        String rawValue = arg.substring(colonIdx + 1);
                        if (hasComplexColonValue(rawValue)) {
                            hasUnvalidatablePathArg = true;
                        } else {
                            value = rawValue;
                        }
                    } else {
                        String nextVal = i + 1 < args.size() ? args.get(i + 1) : null;
                        String nextType = elementTypes != null && elementTypes.size() > i + 2 ? elementTypes.get(i + 2) : null;
                        if (nextVal != null && !isPowerShellParameter(nextVal, nextType)) {
                            value = nextVal;
                            if (checkArgElementType(elementTypes, i + 1)) {
                                hasUnvalidatablePathArg = true;
                            }
                            i++;
                        }
                    }
                    if (value != null) {
                        paths.add(value);
                    }
                } else if (config.leafOnlyPathParams() != null
                    && matchesParam(paramLower, config.leafOnlyPathParams())) {
                    String value = null;
                    if (colonIdx > 0) {
                        String rawValue = arg.substring(colonIdx + 1);
                        if (hasComplexColonValue(rawValue)) {
                            hasUnvalidatablePathArg = true;
                        } else {
                            value = rawValue;
                        }
                    } else {
                        String nextVal = i + 1 < args.size() ? args.get(i + 1) : null;
                        String nextType = elementTypes != null && elementTypes.size() > i + 2 ? elementTypes.get(i + 2) : null;
                        if (nextVal != null && !isPowerShellParameter(nextVal, nextType)) {
                            value = nextVal;
                            if (checkArgElementType(elementTypes, i + 1)) {
                                hasUnvalidatablePathArg = true;
                            }
                            i++;
                        }
                    }
                    if (value != null) {
                        if (value.contains("/") || value.contains("\\")
                            || value.equals(".") || value.equals("..")) {
                            hasUnvalidatablePathArg = true;
                        } else {
                            paths.add(value);
                        }
                    }
                } else if (matchesParam(paramLower, switchParams)) {
                    // switch：不消费下一个 token
                } else if (matchesParam(paramLower, valueParams)) {
                    if (colonIdx > 0) {
                        String rawValue = arg.substring(colonIdx + 1);
                        if (hasComplexColonValue(rawValue)) {
                            hasUnvalidatablePathArg = true;
                        }
                    } else {
                        String nextArg = i + 1 < args.size() ? args.get(i + 1) : null;
                        String nextArgType = elementTypes != null && elementTypes.size() > i + 2 ? elementTypes.get(i + 2) : null;
                        if (nextArg != null && !isPowerShellParameter(nextArg, nextArgType)) {
                            if (checkArgElementType(elementTypes, i + 1)) {
                                hasUnvalidatablePathArg = true;
                            }
                            i++;
                        }
                    }
                } else {
                    // 未知参数：整体标记 unvalidatable，但 colon 值仍提取供 deny 匹配（CC :1463-1486）
                    hasUnvalidatablePathArg = true;
                    if (colonIdx > 0) {
                        String rawValue = arg.substring(colonIdx + 1);
                        if (!hasComplexColonValue(rawValue)) {
                            paths.add(rawValue);
                        }
                    }
                }
                continue;
            }
            // 位置参数
            if (positionalsSeen < positionalSkip) {
                positionalsSeen++;
                continue;
            }
            positionalsSeen++;
            if (checkArgElementType(elementTypes, i)) {
                hasUnvalidatablePathArg = true;
            }
            paths.add(arg);
        }
        return new PathExtraction(paths, config.operationType(), hasUnvalidatablePathArg,
            config.optionalWrite());
    }

    /** 元素类型非 StringConstant/Parameter → unvalidatable（CC pathValidation.ts:1334-1340）。 */
    private static boolean checkArgElementType(List<String> elementTypes, int argIdx) {
        if (elementTypes == null || elementTypes.size() <= argIdx + 1) return false;
        String et = elementTypes.get(argIdx + 1);
        return et != null && !SAFE_PATH_ELEMENT_TYPES.contains(et);
    }

    // ════════════════════════════════════════════════════════════════════════
    // validatePath + isPathAllowed · 对齐 CC pathValidation.ts:1013-1264 / :863-977
    // ════════════════════════════════════════════════════════════════════════
    /** ~ 展开（CC pathValidation.ts:820-829 expandTilde）。 */
    private static String expandTilde(String filePath) {
        if (filePath.equals("~") || filePath.startsWith("~/") || filePath.startsWith("~\\")) {
            return System.getProperty("user.home", "") + filePath.substring(1);
        }
        return filePath;
    }

    /** 反斜杠转正斜杠（CC normalize；PS Core 全平台归一化）。 */
    private static String normalizeSlashes(String s) {
        return s.replace('\\', '/');
    }

    /** 解析路径：绝对路径（盘符/斜杠开头）直接使用，否则相对 cwd（CC resolve(cwd, path)）。 */
    private static String resolveAgainstCwd(String path, Path cwd) {
        // Windows 陷阱：Paths.get("/etc/hosts").isAbsolute() 为 false（drive-relative），
        // 而 CC path.isAbsolute('/etc/hosts') 为 true。按 CC 语义：前导分隔符或盘符即绝对。
        boolean absoluteLike = path.startsWith("/") || path.startsWith("\\")
            || path.matches("^[a-zA-Z]:.*");
        try {
            if (absoluteLike || Paths.get(path).isAbsolute()) {
                return Paths.get(path).normalize().toString();
            }
        } catch (Exception ignored) {
            // 非法路径字符（如 Windows 盘符嵌在相对段）→ 交给 isPathAllowed 失败
        }
        return Paths.get(cwd.toString(), path).normalize().toString();
    }

    /**
     * 单路径校验 · 对齐 CC pathValidation.ts:1013-1264 validatePath。
     * 返回 allowed=false 时 message/rule 决定 ask 还是 deny。
     */
    static PathCheck validatePath(String filePath, Path cwd, ToolPermissionContext permCtx, String operationType) {
        String cleanPath = expandTilde(filePath.replaceAll("^['\"]|['\"]$", ""));
        String normalizedPath = normalizeSlashes(cleanPath);

        // 反引号转义：无法静态校验 → deny 猜测 + ask（CC :1032-1061）
        if (normalizedPath.contains("`")) {
            PermissionResult guessed = checkDenyRuleForGuessedPath(normalizedPath.replace("`", ""), cwd, permCtx, operationType);
            if (guessed instanceof PermissionResult.Deny) {
                PermissionRule r = ((PermissionResult.Deny) guessed).reason() instanceof PermissionDecisionReason.Rule rr ? rr.rule() : null;
                return new PathCheck(false, normalizedPath, null, r);
            }
            return new PathCheck(false, normalizedPath, "路径含反引号转义字符，无法静态校验，需人工审批", null);
        }
        // 模块限定 provider 路径（::）→ deny 猜测 + ask（CC :1063-1096）
        if (normalizedPath.contains("::")) {
            String afterProvider = normalizedPath.substring(normalizedPath.indexOf("::") + 2);
            PermissionResult guessed = checkDenyRuleForGuessedPath(afterProvider, cwd, permCtx, operationType);
            if (guessed instanceof PermissionResult.Deny) {
                PermissionRule r = ((PermissionResult.Deny) guessed).reason() instanceof PermissionDecisionReason.Rule rr ? rr.rule() : null;
                return new PathCheck(false, normalizedPath, null, r);
            }
            return new PathCheck(false, normalizedPath, "模块限定 provider 路径（::）无法静态校验，需人工审批", null);
        }
        // UNC 路径（网络请求/NTLM 泄漏）→ 阻断（CC :1098-1114）
        if (normalizedPath.startsWith("//") || normalizedPath.toLowerCase().contains("davwwwroot")
            || normalizedPath.toLowerCase().contains("@ssl@")) {
            return new PathCheck(false, normalizedPath, "UNC 路径可能触发网络请求与凭据泄漏，已阻断", null);
        }
        // 变量展开语法 → ask（CC :1116-1126）
        if (normalizedPath.contains("$") || normalizedPath.contains("%")) {
            return new PathCheck(false, normalizedPath, "路径含变量展开语法，需人工审批", null);
        }
        // 非文件系统 provider（env:/HKLM:/...）→ ask（CC :1128-1159）
        // Windows 需 2+ 字母避免误伤 C:；本平台统一按 Windows 语义（CC getPlatform 运行时分支）
        if (Pattern.compile("^[a-z0-9]{2,}:", Pattern.CASE_INSENSITIVE).matcher(normalizedPath).find()) {
            return new PathCheck(false, normalizedPath, "路径 '" + normalizedPath + "' 使用非文件系统 provider，需人工审批", null);
        }
        // 通配符（CC :1161-1242）。write/create 均拒绝 glob 写（CC :1163 write||create）。
        if (GLOB_PATTERN_REGEX.matcher(normalizedPath).find()) {
            if (OP_WRITE.equals(operationType) || OP_CREATE.equals(operationType)) {
                return new PathCheck(false, normalizedPath, "写操作不允许 glob 通配符，请指定精确路径", null);
            }
            if (containsPathTraversal(normalizedPath)) {
                String abs = resolveAgainstCwd(normalizedPath, cwd);
                PathCheck p = isPathAllowed(normalizeSlashes(abs), cwd, permCtx, operationType);
                return new PathCheck(p.allowed(), normalizeSlashes(abs), p.message(), p.rule());
            }
            String basePath = getGlobBaseDirectory(normalizedPath);
            String absBase = resolveAgainstCwd(basePath, cwd);
            PermissionRule deny = editDenyRule(normalizeSlashes(absBase), cwd, permCtx);
            if (deny != null) {
                return new PathCheck(false, normalizeSlashes(absBase), null, deny);
            }
            return new PathCheck(false, normalizeSlashes(absBase),
                "glob 模式路径无法静态校验（glob 展开内符号链接不可见），需人工审批", null);
        }
        // 常规解析 + isPathAllowed（CC :1244-1263）
        String abs = resolveAgainstCwd(normalizedPath, cwd);
        PathCheck p = isPathAllowed(normalizeSlashes(abs), cwd, permCtx, operationType);
        return new PathCheck(p.allowed(), normalizeSlashes(abs), p.message(), p.rule());
    }

    /** 路径是否含 .. 遍历（CC containsPathTraversal）。 */
    private static boolean containsPathTraversal(String s) {
        return s.contains("..");
    }

    /** 取第一个通配符前的目录基（CC pathValidation.ts:1266-1278）。 */
    static String getGlobBaseDirectory(String filePath) {
        var m = GLOB_PATTERN_REGEX.matcher(filePath);
        if (!m.find()) return filePath;
        String beforeGlob = filePath.substring(0, m.start());
        int lastSep = Math.max(beforeGlob.lastIndexOf('/'), beforeGlob.lastIndexOf('\\'));
        if (lastSep == -1) return ".";
        return beforeGlob.substring(0, lastSep + 1).isEmpty() ? "/" : beforeGlob.substring(0, lastSep + 1);
    }

    /** Edit deny 规则查询（CC matchingRuleForInput 'edit' 桶；Java 用 content glob 近似）。 */
    private static PermissionRule editDenyRule(String path, Path cwd, ToolPermissionContext permCtx) {
        if (permCtx == null) return null;
        return RuleQuery.getEditRuleByContentsForPath(permCtx, path, PermissionBehavior.DENY,
            cwd == null ? null : cwd.toString());
    }

    /** Edit allow 规则查询。 */
    private static PermissionRule editAllowRule(String path, Path cwd, ToolPermissionContext permCtx) {
        if (permCtx == null) return null;
        return RuleQuery.getEditRuleByContentsForPath(permCtx, path, PermissionBehavior.ALLOW,
            cwd == null ? null : cwd.toString());
    }

    /** 危险目录（auto-edit 禁改）· 对齐 CC filesystem.ts:74-79（WritePermissionChecker 同源）。
     *  '.claude' 保留 CC mirror；'.nexusai' 改动态 NexusaiPaths.getProjectDirName()（决策 D1，appName≠nexusai 时仍判危险）。 */
    private static final Set<String> DANGEROUS_DIRECTORIES = Set.of(".git", ".vscode", ".idea", ".claude");
    /** 危险文件（auto-edit 禁改）· 对齐 CC filesystem.ts:57-68。 */
    private static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".zshrc",
        ".zprofile", ".profile", ".ripgreprc", ".mcp.json", ".claude.json", ".nexusai.json");

    /**
     * 解析后路径判定 · 对齐 CC pathValidation.ts:863-977 isPathAllowed。
     * 顺序：deny 规则 →（写）auto-edit 安全 → 工作目录 → allow 规则 → 兜底。
     */
    static PathCheck isPathAllowed(String resolvedPath, Path cwd, ToolPermissionContext permCtx, String operationType) {
        // 1. Edit deny 规则（CC :871-883）
        PermissionRule deny = editDenyRule(resolvedPath, cwd, permCtx);
        if (deny != null) {
            return new PathCheck(false, resolvedPath, null, deny);
        }
        // 2. 内部可编辑路径白名单（CC :885-889 checkEditableInternalPath）· TR-C2-Q2/组 1-4⑤ 接入
        // PowerShell 权限链（PowerShellPermissionChain 注入 agent-memory/auto-memory carve-out）。
        // 必须先于 auto-edit 安全（CC 注释：内部路径在 ~/.claude/ 下，先于危险目录判定）。
        if (!OP_READ.equals(operationType) && isInternalPathCarveOut(resolvedPath, "edit")) {
            return new PathCheck(true, resolvedPath, null, null);
        }
        // 2.5 写操作 auto-edit 安全（CC :899-915）
        if (!OP_READ.equals(operationType)) {
            String msg = pathSafetyForAutoEdit(resolvedPath);
            if (msg != null) {
                return new PathCheck(false, resolvedPath, msg, null);
            }
        }
        // 3. 工作目录内（CC :917-927）
        boolean inWorkingDir = isInWorkingDir(resolvedPath, cwd, permCtx);
        if (inWorkingDir) {
            if (OP_READ.equals(operationType) || (permCtx != null && permCtx.mode() == PermissionMode.ACCEPT_EDITS)) {
                return new PathCheck(true, resolvedPath, null, null);
            }
        }
        // 3.5 read 内部可读路径（CC :929-938 checkReadableInternalPath）· TR-C2-Q2/组 1-4⑤ 接入
        // （agent-memory/auto-memory/bundled-skills）。3.7 sandbox write allowlist → N/A（Java 无 sandbox）。
        if (OP_READ.equals(operationType) && isInternalPathCarveOut(resolvedPath, "read")) {
            return new PathCheck(true, resolvedPath, null, null);
        }
        // 4. allow 规则（CC :961-973）
        PermissionRule allow = editAllowRule(resolvedPath, cwd, permCtx);
        if (allow != null) {
            return new PathCheck(true, resolvedPath, null, allow);
        }
        // 5. 不在任何允许范围
        return new PathCheck(false, resolvedPath,
            "为安全起见，Claude Code 仅可访问本会话允许的工作目录内的文件：'" + resolvedPath + "' 在范围外", null);
    }

    /** auto-edit 路径安全检查（紧凑版）· 对齐 CC filesystem.ts:620-665 + WritePermissionChecker.checkPathSafetyForAutoEdit。 */
    private static String pathSafetyForAutoEdit(String expanded) {
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            return "路径含 UNC 前缀，可能触发网络请求";
        }
        String[] segments = expanded.split("[\\/]");
        String nexusaiDir = NexusaiPaths.getProjectDirName();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isEmpty()) continue;
            // 动态 nexusai 目录判定（决策 D1，appName≠nexusai 时仍判危险）
            if (segment.equalsIgnoreCase(nexusaiDir)) {
                String next = i + 1 < segments.length ? segments[i + 1] : null;
                if (next != null && next.equalsIgnoreCase("worktrees")) {
                    continue; // .{appName}/worktrees 结构性目录（D7 carve-out）
                }
                return "路径命中危险目录 " + segment + "（auto-edit 禁改）";
            }
            for (String dir : DANGEROUS_DIRECTORIES) {
                if (segment.equalsIgnoreCase(dir)) {
                    return "路径命中危险目录 " + dir + "（auto-edit 禁改）";
                }
            }
        }
        if (segments.length > 0) {
            String fileName = segments[segments.length - 1];
            for (String f : DANGEROUS_FILES) {
                if (f.equalsIgnoreCase(fileName)) {
                    return "路径命中危险文件 " + f + "（auto-edit 禁改）";
                }
            }
        }
        return null;
    }

    /** 工作目录内判定（cwd + additionalWorkingDirectories）· 对齐 CC pathInAllowedWorkingPath 的 Java 近似。 */
    static boolean isInWorkingDir(String resolvedPath, Path cwd, ToolPermissionContext permCtx) {
        try {
            Path resolved = Paths.get(resolvedPath).toAbsolutePath().normalize();
            if (cwd != null && resolved.startsWith(cwd.toAbsolutePath().normalize())) {
                return true;
            }
            if (permCtx != null && permCtx.additionalWorkingDirectories() != null) {
                for (var entry : permCtx.additionalWorkingDirectories().values()) {
                    if (entry.path() == null) continue;
                    Path extra = Paths.get(entry.path()).toAbsolutePath().normalize();
                    if (resolved.startsWith(extra)) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkDenyRuleForGuessedPath · 对齐 CC pathValidation.ts:984-1008
    // ════════════════════════════════════════════════════════════════════════
    /** 仅查 deny 规则的路径猜测（:: / 反引号 / 管道源场景）。只 deny 不 auto-allow。 */
    static PermissionResult checkDenyRuleForGuessedPath(String strippedPath, Path cwd,
                                                        ToolPermissionContext permCtx, String operationType) {
        if (strippedPath == null || strippedPath.isEmpty() || strippedPath.contains("\0")) {
            return null;
        }
        String tildeExpanded = expandTilde(strippedPath);
        String abs = normalizeSlashes(resolveAgainstCwd(tildeExpanded, cwd));
        PermissionRule deny = editDenyRule(abs, cwd, permCtx);
        if (deny != null) {
            return deny("路径 '" + abs + "' 被 Edit deny 规则阻断", new PermissionDecisionReason.Rule(deny));
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // nestedCommands 镜像 · 对齐 CC pathValidation.ts:1811-1906
    // ════════════════════════════════════════════════════════════════════════
    private static PermissionResult checkNestedCommands(List<PowerShellAstService.CommandElement> nested,
                                                        ToolPermissionContext permCtx, Path cwd) {
        PermissionResult firstAsk = null;
        if (nested == null) return null;
        for (PowerShellAstService.CommandElement cmd : nested) {
            String canonical = ReadOnlyCommandTable.resolveToCanonical(cmd.name());
            PathExtraction ex = extractPaths(cmd, canonical);
            if (ex.hasUnvalidatablePathArg()) {
                firstAsk = ask(canonical + " 使用参数或复杂路径表达式，无法静态校验，需人工审批");
            }
            if (!OP_READ.equals(ex.operationType()) && !ex.optionalWrite()
                && ex.paths().isEmpty() && lookupCmdletConfig(canonical) != null) {
                firstAsk = ask(canonical + " 是写操作但无法确定目标路径，需人工审批");
                continue;
            }
            boolean isRemoval = "remove-item".equals(canonical);
            for (String filePath : ex.paths()) {
                if (isRemoval && PowerShellPermissionChain.isDangerousRemovalRawPath(filePath)) {
                    return dangerousRemovalDeny(filePath);
                }
                PathCheck v = validatePath(filePath, cwd, permCtx, ex.operationType());
                if (isRemoval && v.resolvedPath() != null
                    && PowerShellPermissionChain.isDangerousRemovalPath(v.resolvedPath())) {
                    return dangerousRemovalDeny(v.resolvedPath());
                }
                if (!v.allowed()) {
                    if (v.rule() != null) {
                        return deny("路径 '" + v.resolvedPath() + "' 被 Edit deny 规则阻断",
                            new PermissionDecisionReason.Rule(v.rule()));
                    }
                    firstAsk = ask(v.message() != null ? v.message()
                        : canonical + " 目标 '" + v.resolvedPath() + "' 在工作目录之外，需人工审批");
                }
            }
        }
        return firstAsk;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结果构造
    // ════════════════════════════════════════════════════════════════════════
    /** 危险删除硬 deny · 对齐 CC pathValidation.ts:848-857 dangerousRemovalDeny。 */
    static PermissionResult dangerousRemovalDeny(String path) {
        return new PermissionResult.Deny(
            "Remove-Item on system path '" + path + "' is blocked. This path is protected from removal.",
            new PermissionDecisionReason.Other("Removal targets a protected system path"), null);
    }

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
