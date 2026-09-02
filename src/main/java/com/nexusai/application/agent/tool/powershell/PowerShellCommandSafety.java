package com.nexusai.application.agent.tool.powershell;

import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PowerShell 安全校验 · 对齐 CC {@code powershellSecurity.ts:1042-1090 powershellCommandIsSafe}
 * 的 24 个 validator 等价（session A5 移植）。
 *
 * <p>返回第一个命中 validator 的 ask 消息（CC validator 循环 first-ask-wins），
 * 全部 passthrough 返回 null。数据源为 {@link PowerShellAstService.ParsedResult}（AST 解析结果）。
 *
 * <p>7 分类集（FILEPATH_EXECUTION / DANGEROUS_SCRIPT_BLOCK / MODULE_LOADING / NETWORK /
 * ALIAS_HIJACK / WMI_CIM / ARG_GATED）对齐 CC {@code utils/powershell/dangerousCmdlets.ts}。
 */
public final class PowerShellCommandSafety {

    private static final Logger log = LoggerFactory.getLogger(PowerShellCommandSafety.class);

    private PowerShellCommandSafety() {
        throw new AssertionError("utility class - do not instantiate");
    }

    // ========================================================================
    // dangerousCmdlets.ts 7 分类集
    // ========================================================================

    /** 脚本文件执行 cmdlet（-FilePath/位置参数执行脚本文件）· CC dangerousCmdlets.ts:17-22。 */
    private static final Set<String> FILEPATH_EXECUTION_CMDLETS = Set.of(
        "invoke-command", "start-job", "start-threadjob", "register-scheduledjob");

    /** 危险脚本块 cmdlet（脚本块参数执行任意代码）· CC dangerousCmdlets.ts:28-39。 */
    private static final Set<String> DANGEROUS_SCRIPT_BLOCK_CMDLETS = Set.of(
        "invoke-command", "invoke-expression", "start-job", "start-threadjob",
        "register-scheduledjob", "register-engineevent", "register-objectevent",
        "register-wmievent", "new-pssession", "enter-pssession");

    /** 模块加载 cmdlet（.psm1 顶层体执行）· CC dangerousCmdlets.ts:45-53。 */
    private static final Set<String> MODULE_LOADING_CMDLETS = Set.of(
        "import-module", "ipmo", "install-module", "save-module", "update-module",
        "install-script", "save-script");

    /** 网络 cmdlet（wildcard 规则使 exfil/download 免提示）· CC dangerousCmdlets.ts:82-85。 */
    private static final Set<String> NETWORK_CMDLETS = Set.of(
        "invoke-webrequest", "invoke-restmethod");

    /** 别名/变量劫持 cmdlet · CC dangerousCmdlets.ts:92-101。 */
    private static final Set<String> ALIAS_HIJACK_CMDLETS = Set.of(
        "set-alias", "sal", "new-alias", "nal", "set-variable", "sv", "new-variable", "nv");

    /** WMI/CIM 进程生成 · CC dangerousCmdlets.ts:110-114。 */
    private static final Set<String> WMI_CIM_CMDLETS = Set.of(
        "invoke-wmimethod", "iwmi", "invoke-cimmethod");

    /** 下载工具（LOLBAS）· CC powershellSecurity.ts DOWNLOADER_NAMES。 */
    private static final Set<String> DOWNLOADER_NAMES = Set.of(
        "invoke-webrequest", "iwr", "invoke-restmethod", "irm", "new-object", "start-bitstransfer");

    /** 计划任务持久化 cmdlet · CC powershellSecurity.ts SCHEDULED_TASK_CMDLETS。 */
    private static final Set<String> SCHEDULED_TASK_CMDLETS = Set.of(
        "register-scheduledtask", "new-scheduledtask", "new-scheduledtaskaction", "set-scheduledtask");

    /** 脚本块安全消费 cmdlet（过滤/输出类）· CC powershellSecurity.ts SAFE_SCRIPT_BLOCK_CMDLETS。 */
    private static final Set<String> SAFE_SCRIPT_BLOCK_CMDLETS = Set.of(
        "where-object", "sort-object", "select-object", "group-object",
        "format-table", "format-list", "format-wide", "format-custom");

    /** env 作用域写 cmdlet · CC powershellSecurity.ts ENV_WRITE_CMDLETS。 */
    private static final Set<String> ENV_WRITE_CMDLETS = Set.of(
        "set-item", "si", "new-item", "ni", "remove-item", "ri", "del", "rm", "rd",
        "rmdir", "erase", "clear-item", "cli", "set-content", "add-content", "ac");

    /** PowerShell 可执行文件基名集合 · CC powershellSecurity.ts:35-40 POWERSHELL_EXECUTABLES。 */
    private static final Set<String> POWERSHELL_EXECUTABLES = Set.of(
        "pwsh", "pwsh.exe", "powershell", "powershell.exe");

    /**
     * PowerShell 接受的替代参数前缀字符（等价 ASCII 连字符 U+002D）·
     * CC powershellSecurity.ts:67-72 PS_ALT_PARAM_PREFIXES。
     *
     * <p>PowerShell tokenizer（SpecialCharacters.IsDash）与 powershell.exe
     * CommandLineParameterParser 均接受四种 dash + Windows PowerShell 5.1 的 {@code /} 参数分隔符。
     */
    private static final Set<Character> PS_ALT_PARAM_PREFIXES = Set.of(
        '/', '\u2013', '\u2014', '\u2015');

    /**
     * 从命令名提取基名（处理 / 与 \ 路径）· 对齐 CC powershellSecurity.ts:46-57 isPowerShellExecutable。
     * 全路径（/usr/bin/pwsh、C:\...\powershell.exe、.\pwsh）返回 basename 命中集合即 true。
     */
    private static boolean isPowerShellExecutable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (POWERSHELL_EXECUTABLES.contains(lower)) {
            return true;
        }
        int lastSep = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        if (lastSep >= 0) {
            return POWERSHELL_EXECUTABLES.contains(lower.substring(lastSep + 1));
        }
        return false;
    }

    /**
     * 命令参数是否为某个 PS 参数的无歧义缩写 · 对齐 CC parser.ts:1663-1684 commandHasArgAbbreviation。
     * 例：minPrefix '-en' 匹配 '-en'/'-enc'/'-enco'...（-encodedcommand 的任意无歧义前缀）。
     * 处理 colon 绑定值拆分（-en:base64value → -en）与反引号转义（`-Member`Name` → -MemberName）。
     */
    private static boolean commandHasArgAbbreviation(PowerShellAstService.CommandElement cmd,
                                                      String fullParam, String minPrefix) {
        String lowerFull = fullParam.toLowerCase(Locale.ROOT);
        String lowerMin = minPrefix.toLowerCase(Locale.ROOT);
        for (String a : cmd.args()) {
            int colonIndex = a.indexOf(':', 1);
            String paramPart = colonIndex > 0 ? a.substring(0, colonIndex) : a;
            String lower = paramPart.replace("`", "").toLowerCase(Locale.ROOT);
            if (lower.startsWith(lowerMin) && lowerFull.startsWith(lower)
                && lower.length() <= lowerFull.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * psExeHasParamAbbreviation · 对齐 CC powershellSecurity.ts:83-100。
     *
     * <p>在 {@link #commandHasArgAbbreviation} 之外，把 {@code /}、en-dash、em-dash、horizontal-bar
     * 前缀参数归一化为 ASCII '-' 后重查。所有 PS 参数检查均须用它（不仅 pwsh.exe 调用）——
     * {@code Start-Process foo –Verb RunAs}（en-dash）与 {@code New-Object –ComObject} 之前漏检
     * （OPD-PS-04，ASCII startsWith 只认连字符）。
     */
    private static boolean psExeHasParamAbbreviation(PowerShellAstService.CommandElement cmd,
                                                      String fullParam, String minPrefix) {
        if (commandHasArgAbbreviation(cmd, fullParam, minPrefix)) {
            return true;
        }
        List<String> normalizedArgs = new ArrayList<>(cmd.args().size());
        for (String a : cmd.args()) {
            if (a.length() > 0 && PS_ALT_PARAM_PREFIXES.contains(a.charAt(0))) {
                normalizedArgs.add("-" + a.substring(1));
            } else {
                normalizedArgs.add(a);
            }
        }
        PowerShellAstService.CommandElement normalized = new PowerShellAstService.CommandElement(
            cmd.name(), cmd.nameType(), cmd.elementType(), normalizedArgs, cmd.elementTypes(),
            cmd.text(), cmd.redirections(), cmd.children());
        return commandHasArgAbbreviation(normalized, fullParam, minPrefix);
    }

    // ========================================================================
    // CLM 类型白名单 · 对齐 CC clmTypes.ts CLM_ALLOWED_TYPES（139 个 lowercase 条目）
    // ========================================================================

    /**
     * ConstrainedLanguage 允许类型集合（微软 CLM allowlist 反演：类型字面量不在集合内 → ask）。
     *
     * <p>安全剔除（CC clmTypes.ts:21-26/41/112-118/168-178）：adsi/adsisearcher（LDAP 网络绑定）、
     * wmi/wmiclass/wmisearcher/cimsession（远程 WMI 查询 / CIM 远程会话）及其 FQ 等价
     * （DirectoryEntry/DirectorySearcher/ManagementObject/ManagementClass/ManagementObjectSearcher）
     * 均不在此集合。CC 注释称 '~90 primitives'（clmTypes.ts:791）为过时注释，实际 139 条。
     */
    private static final Set<String> CLM_ALLOWED_TYPES = Set.of(
        // 类型加速器短名（AST TypeName.Name 字面）
        "alias", "allowemptycollection", "allowemptystring", "allownull",
        "argumentcompleter", "argumentcompletions", "array", "bigint", "bool",
        "byte", "char", "cimclass", "cimconverter", "ciminstance", "cimtype",
        "cmdletbinding", "cultureinfo", "datetime", "decimal", "double",
        "dsclocalconfigurationmanager", "dscproperty", "dscresource",
        "experimentaction", "experimental", "experimentalfeature", "float",
        "guid", "hashtable", "int", "int16", "int32", "int64", "ipaddress",
        "ipendpoint", "long", "mailaddress", "norunspaceaffinity", "nullstring",
        "objectsecurity", "ordered", "outputtype", "parameter", "physicaladdress",
        "pscredential", "pscustomobject", "psdefaultvalue", "pslistmodifier",
        "psobject", "psprimitivedictionary", "pstypenameattribute", "ref",
        "regex", "sbyte", "securestring", "semver", "short", "single", "string",
        "supportswildcards", "switch", "timespan", "uint", "uint16", "uint32",
        "uint64", "ulong", "uri", "ushort", "validatecount", "validatedrive",
        "validatelength", "validatenotnull", "validatenotnullorempty",
        "validatenotnullorwhitespace", "validatepattern", "validaterange",
        "validatescript", "validateset", "validatetrusteddata",
        "validateuserdrive", "version", "void", "wildcardpattern",
        "x500distinguishedname", "x509certificate", "xml",
        // System.* 全名（加速器解析到 System.* 的 FQ，AST 可能发 FQ）
        "system.array", "system.boolean", "system.byte", "system.char",
        "system.datetime", "system.decimal", "system.double", "system.guid",
        "system.int16", "system.int32", "system.int64",
        "system.numerics.biginteger", "system.sbyte", "system.single",
        "system.string", "system.timespan", "system.uint16", "system.uint32",
        "system.uint64", "system.uri", "system.version", "system.void",
        "system.collections.hashtable", "system.text.regularexpressions.regex",
        "system.globalization.cultureinfo", "system.net.ipaddress",
        "system.net.ipendpoint", "system.net.mail.mailaddress",
        "system.net.networkinformation.physicaladdress",
        "system.security.securestring",
        "system.security.cryptography.x509certificates.x509certificate",
        "system.security.cryptography.x509certificates.x500distinguishedname",
        "system.xml.xmldocument",
        // System.Management.Automation.*（PS 专属加速器 FQ 等价）
        "system.management.automation.pscredential",
        "system.management.automation.pscustomobject",
        "system.management.automation.pslistmodifier",
        "system.management.automation.psobject",
        "system.management.automation.psprimitivedictionary",
        "system.management.automation.psreference",
        "system.management.automation.semanticversion",
        "system.management.automation.switchparameter",
        "system.management.automation.wildcardpattern",
        "system.management.automation.language.nullstring",
        // Microsoft.Management.Infrastructure.*（CIM 加速器 FQ 等价）
        "microsoft.management.infrastructure.cimclass",
        "microsoft.management.infrastructure.cimconverter",
        "microsoft.management.infrastructure.ciminstance",
        "microsoft.management.infrastructure.cimtype",
        // 其余短名加速器 FQ + 惰性基类 + ModuleSpecification
        "system.collections.specialized.ordereddictionary",
        "system.security.accesscontrol.objectsecurity",
        "object", "system.object",
        "microsoft.powershell.commands.modulespecification");

    /**
     * 归一化类型名：大小写折叠 → 去数组后缀（[]）→ 去泛型实参 → trim ·
     * 对齐 CC {@code clmTypes.ts:194-203 normalizeTypeName}。
     */
    static String normalizeTypeName(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT)
            .replaceAll("\\[\\]$", "")
            .replaceAll("\\[.*\\]$", "")
            .trim();
    }

    /** 类型字面量是否在 CLM 白名单内 · 对齐 CC {@code clmTypes.ts:209-211 isClmAllowedType}。 */
    static boolean isClmAllowedType(String typeName) {
        return CLM_ALLOWED_TYPES.contains(normalizeTypeName(typeName));
    }

    // ========================================================================
    // 入口 · 对齐 CC powershellCommandIsSafe:1042
    // ========================================================================

    /**
     * 24 validator 循环：返回首个 ask 消息，全部 passthrough 返回 null。
     *
     * <p>顺序对齐 CC {@code powershellSecurity.ts:1054-1079 validators}（OPD-PS-04 重排）：
     * InvokeExpression → DynamicCommandName → EncodedCommand → PwshCommandOrFile →
     * DownloadCradles → DownloadUtilities → AddType → ComObject → DangerousFilePathExecution →
     * InvokeItem → ScheduledTask → ForEachMemberName → StartProcess → ScriptBlockInjection →
     * SubExpressions → ExpandableStrings → Splatting → StopParsing → MemberInvocations →
     * TypeLiterals → EnvVarManipulation → ModuleLoading → RuntimeStateManipulation → WmiProcessSpawn。
     * 消息归因顺序改变可观察（同一命令命中多个 validator 时返回最前的消息，对齐 CC first-ask-wins）。
     */
    public static String findAskMessage(PowerShellAstService.ParsedResult parsed) {
        String msg = checkInvokeExpression(parsed);
        if (msg != null) return msg;
        msg = checkDynamicCommandName(parsed);
        if (msg != null) return msg;
        msg = checkEncodedCommand(parsed);
        if (msg != null) return msg;
        msg = checkPwshCommandOrFile(parsed);
        if (msg != null) return msg;
        msg = checkDownloadCradles(parsed);
        if (msg != null) return msg;
        msg = checkDownloadUtilities(parsed);
        if (msg != null) return msg;
        msg = checkAddType(parsed);
        if (msg != null) return msg;
        msg = checkComObject(parsed);
        if (msg != null) return msg;
        msg = checkDangerousFilePathExecution(parsed);
        if (msg != null) return msg;
        msg = checkInvokeItem(parsed);
        if (msg != null) return msg;
        msg = checkScheduledTask(parsed);
        if (msg != null) return msg;
        msg = checkForEachMemberName(parsed);
        if (msg != null) return msg;
        msg = checkStartProcess(parsed);
        if (msg != null) return msg;
        msg = checkScriptBlockInjection(parsed);
        if (msg != null) return msg;
        // AST 标志类（CC 独立 validators :15-19 顺序：SubExpressions → ExpandableStrings →
        // Splatting → StopParsing → MemberInvocations）
        if (parsed.hasSubExpressions()) return "命令包含子表达式 $()，可能隐藏命令执行";
        if (parsed.hasExpandableStrings()) return "命令包含带内嵌表达式的展开字符串";
        if (parsed.hasSplatting()) return "命令使用 splatting (@variable)，可能掩盖参数";
        if (parsed.hasStopParsing()) return "命令使用 stop-parsing 标记 (--%)";
        if (parsed.hasMemberInvocations()) return "命令调用 .NET 方法，可访问系统 API";
        // checkTypeLiterals 紧邻 checkMemberInvocations（CC validators 顺序 :1073-1074），
        // 纯类型字面量（如 [Reflection.Assembly] 无成员调用）仅此一处命中
        msg = checkTypeLiterals(parsed);
        if (msg != null) return msg;
        msg = checkEnvVarManipulation(parsed);
        if (msg != null) return msg;
        msg = checkModuleLoading(parsed);
        if (msg != null) return msg;
        msg = checkRuntimeStateManipulation(parsed);
        if (msg != null) return msg;
        msg = checkWmiProcessSpawn(parsed);
        if (msg != null) return msg;
        // TR-C2-⊕-C2-1（EV-C2-023，对齐 CC powershellSecurity.ts）：CC 无独立 assignment validator。
        // 赋值语句（$x = ...）经 powershellPermissions.ts step5 fail-closed 同样 ask（net ask 相同），
        // 本 Java-only 消息删除后消息归因对齐 CC（不在此处返回赋值专属 ask）。
        return null;
    }

    // ========================================================================
    // 24 validators · 对齐 CC powershellSecurity.ts
    // ========================================================================

    private static List<PowerShellAstService.CommandElement> allCmds(PowerShellAstService.ParsedResult parsed) {
        java.util.ArrayList<PowerShellAstService.CommandElement> out = new java.util.ArrayList<>();
        for (PowerShellAstService.Statement st : parsed.statements()) {
            out.addAll(st.commands());
            out.addAll(st.nestedCommands());
        }
        return out;
    }

    /** checkInvokeExpression：Invoke-Expression/iex 等价 eval，可执行任意代码。 */
    private static String checkInvokeExpression(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if ("invoke-expression".equals(ReadOnlyCommandTable.resolveToCanonical(c.name()))) {
                return "命令使用 Invoke-Expression，可执行任意代码";
            }
        }
        return null;
    }

    /** checkDynamicCommandName：命令名是动态表达式（非 StringConstant），无法静态验证。 */
    private static String checkDynamicCommandName(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if (!"CommandAst".equals(c.elementType())) continue;
            List<String> types = c.elementTypes();
            if (types.isEmpty()) continue;
            String nameType = types.get(0);
            if (nameType != null && !nameType.equals("StringConstant")) {
                return "命令名是动态表达式，无法静态验证";
            }
        }
        return null;
    }

    /** checkEncodedCommand：pwsh 可执行带 -EncodedCommand 参数（掩盖意图）· CC :166-180。 */
    private static String checkEncodedCommand(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if (!isPowerShellExecutable(c.name())) continue;
            if (psExeHasParamAbbreviation(c, "-encodedcommand", "-e")) {
                return "命令使用编码参数（-EncodedCommand），掩盖真实意图";
            }
        }
        return null;
    }

    /** checkPwshCommandOrFile：嵌套 pwsh/powershell 进程无法验证（任意命令位）· CC :192-205。 */
    private static String checkPwshCommandOrFile(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if (isPowerShellExecutable(c.name())) {
                return "命令生成嵌套 PowerShell 进程，无法静态验证";
            }
        }
        return null;
    }

    /** checkDownloadCradles：下载 + IEX 组合（同语句管道或跨语句）。 */
    private static String checkDownloadCradles(PowerShellAstService.ParsedResult parsed) {
        List<PowerShellAstService.CommandElement> all = allCmds(parsed);
        boolean hasDownloader = all.stream().anyMatch(c -> isDownloader(c.name()));
        boolean hasIex = all.stream().anyMatch(c -> isIex(c.name()));
        if (hasDownloader && hasIex) {
            return "命令下载并执行远程代码（download cradle）";
        }
        return null;
    }

    private static boolean isDownloader(String name) {
        return DOWNLOADER_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isIex(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("invoke-expression") || lower.equals("iex");
    }

    /** checkDownloadUtilities：Start-BitsTransfer / certutil -urlcache / bitsadmin /transfer。 */
    private static String checkDownloadUtilities(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (lower.equals("start-bitstransfer")) {
                return "命令通过 BITS 传输下载文件";
            }
            if (lower.equals("certutil") || lower.equals("certutil.exe")) {
                for (String a : c.args()) {
                    String la = a.toLowerCase(Locale.ROOT);
                    if (la.equals("-urlcache") || la.equals("/urlcache")) {
                        return "命令使用 certutil 从 URL 下载";
                    }
                }
            }
            if (lower.equals("bitsadmin") || lower.equals("bitsadmin.exe")) {
                for (String a : c.args()) {
                    if (a.toLowerCase(Locale.ROOT).equals("/transfer")) {
                        return "命令通过 BITS 传输下载文件";
                    }
                }
            }
        }
        return null;
    }

    /** checkAddType：Add-Type 编译并加载 .NET 代码。 */
    private static String checkAddType(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if ("add-type".equals(ReadOnlyCommandTable.resolveToCanonical(c.name()))) {
                return "命令编译并加载 .NET 代码（Add-Type）";
            }
        }
        return null;
    }

    /** checkComObject：New-Object -ComObject 实例化 COM 对象（执行原语）+ -TypeName 越界 CLM。 */
    private static String checkComObject(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if (!c.name().toLowerCase(Locale.ROOT).equals("new-object")) continue;
            // -ComObject min abbrev = -com（-co 在 PS5.1 因 -Confirm 公共参数歧义，CC :350-353）
            if (psExeHasParamAbbreviation(c, "-comobject", "-com")) {
                return "命令实例化 COM 对象，可能具有执行能力";
            }
            // -TypeName 三路提取（colon/space/positional-0）→ CLM 白名单检查（CC checkComObject:360-427）
            String typeName = extractTypeName(c);
            if (typeName != null && !isClmAllowedType(typeName)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellCommandSafety: New-Object -TypeName 越界 CLM type={}", typeName);
                }
                return "New-Object 实例化 ConstrainedLanguage 白名单外的 .NET 类型 '" + typeName + "'";
            }
        }
        return null;
    }

    /**
     * New-Object -TypeName 三路提取 · 对齐 CC {@code checkComObject:360-427}。
     * ① colon-bound（-TypeName:Foo.Bar）；② space-separated（-TypeName Foo.Bar）；
     * ③ positional-0（跳过命名参数后首个非 dash arg）。①②同循环逐 arg 先 colon 后 space。
     */
    private static String extractTypeName(PowerShellAstService.CommandElement c) {
        List<String> args = c.args();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String lower = a.toLowerCase(Locale.ROOT);
            // ① colon-bound：-TypeName:Foo.Bar（paramPart 为冒号前，须是 -typename 前缀）
            if (lower.startsWith("-t") && lower.contains(":")) {
                int colonIdx = a.indexOf(':');
                String paramPart = lower.substring(0, colonIdx);
                if ("-typename".startsWith(paramPart)) {
                    return a.substring(colonIdx + 1);
                }
            }
            // ② space-separated：-TypeName Foo.Bar
            if (lower.startsWith("-t") && "-typename".startsWith(lower) && i + 1 < args.size()) {
                return args.get(i + 1);
            }
        }
        // ③ positional-0：-TypeName 是 NetParameterSet 默认位置参数，跳过命名参数后取首个非 dash arg
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("-")) {
                return a; // 首个非 dash arg = positional TypeName
            }
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-t") && "-typename".startsWith(lower)) {
                i++; // 消费 -TypeName 值（①②已处理，此处仅跳过）
                continue;
            }
            if (lower.contains(":")) continue; // colon-bound 单 token，无独立值
            if ("-strict".equals(lower)) continue; // switch 参数无值
            if ("-argumentlist".equals(lower) || "-comobject".equals(lower) || "-property".equals(lower)) {
                i++; // 消费值参数的值
                continue;
            }
            // 未知参数：保守跳过（不消费值）
        }
        return null;
    }

    /** checkDangerousFilePathExecution：FILEPATH_EXECUTION_CMDLETS 带 -FilePath/-LiteralPath 或位置脚本参数。 */
    private static String checkDangerousFilePathExecution(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            String resolved = ReadOnlyCommandTable.resolveToCanonical(lower);
            if (!FILEPATH_EXECUTION_CMDLETS.contains(resolved)) continue;
            // -f 对全部四个 -FilePath 参数无歧义；-l 对 Start-Job -LiteralPath 无歧义（CC :447-449）
            if (psExeHasParamAbbreviation(c, "-filepath", "-f")
                || psExeHasParamAbbreviation(c, "-literalpath", "-l")) {
                return c.name() + " -FilePath 执行任意脚本文件";
            }
            for (int i = 0; i < c.args().size(); i++) {
                List<String> types = c.elementTypes();
                String argType = types.size() > i + 1 ? types.get(i + 1) : "";
                String arg = c.args().get(i);
                if ("StringConstant".equals(argType) && arg != null && !arg.startsWith("-")) {
                    return c.name() + " 位置字符串参数绑定 -FilePath 执行脚本文件";
                }
            }
        }
        return null;
    }

    /** checkForEachMemberName：ForEach-Object -MemberName 按字符串名调用方法。 */
    private static String checkForEachMemberName(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            String resolved = ReadOnlyCommandTable.resolveToCanonical(lower);
            if (!resolved.equals("foreach-object")) continue;
            // ForEach-Object 以 -m 开头的参数仅 -MemberName（CC :508-509）
            if (psExeHasParamAbbreviation(c, "-membername", "-m")) {
                return "ForEach-Object -MemberName 按字符串名调用方法，无法验证";
            }
            for (int i = 0; i < c.args().size(); i++) {
                List<String> types = c.elementTypes();
                String argType = types.size() > i + 1 ? types.get(i + 1) : "";
                String arg = c.args().get(i);
                if ("StringConstant".equals(argType) && arg != null && !arg.startsWith("-")) {
                    return "ForEach-Object 位置字符串参数绑定 -MemberName，按名调用方法";
                }
            }
        }
        return null;
    }

    /** -Verb 冒号参数名正则（-Verb:/-V:/-v:...，含 Unicode 横线 + 反斜杠归一）· CC :580-610 (a)。 */
    private static final Pattern VERB_COLON_PARAM = Pattern.compile(
        "^[-\\u2013\\u2014\\u2015/]v[a-z]*:", Pattern.CASE_INSENSITIVE);

    /** -Verb 冒号绑定 RunAs 兜底正则（引号/反引号/空白绕过）· CC :580-610 (b)。 */
    private static final Pattern VERB_COLON_RUNAS = Pattern.compile(
        "^[-\\u2013\\u2014\\u2015/]v[a-z]*:['\"` ]*runas['\"` ]*$", Pattern.CASE_INSENSITIVE);

    /** checkStartProcess：-Verb RunAs 提权（space + 冒号双层）/ 目标为 PowerShell 可执行。 */
    private static String checkStartProcess(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (!lower.equals("start-process") && !lower.equals("saps") && !lower.equals("start")) continue;
            // -Verb/-v 缩写（含 en/em-dash 前缀归一，CC :561-569 psExeHasParamAbbreviation）+
            // 裸 runas token（space 语法）
            if (psExeHasParamAbbreviation(c, "-verb", "-v")
                && c.args().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).equals("runas"))) {
                return "命令请求提升权限（-Verb RunAs）";
            }
            // 冒号语法双层：-Verb:RunAs / -Verb:'RunAs' / -Verb:"RunAs" / -V`erb:RunAs（引号/反引号绕过）
            String colon = checkStartProcessVerbColon(c);
            if (colon != null) return colon;
            for (String a : c.args()) {
                // D1（G33②）：CC :621-630 用 isPowerShellExecutable(stripped)（powershellSecurity.ts:46-57，
                // 剥路径 basename 判定）。旧 Java 对 4 个 PS 可执行名做精确字符串相等——`Start-Process
                // C:\...\powershell.exe`（路径限定 PS exe）漏判（消息归因偏移，net 仍 ask，fail-safe）。
                String stripped = a.replaceAll("^['\"]|['\"]$", "");
                if (isPowerShellExecutable(stripped)) {
                    return "Start-Process 生成嵌套 PowerShell 进程，无法验证";
                }
            }
        }
        return null;
    }

    /** checkStartProcess -Verb 冒号语法双层（结构层 children + 正则兜底）· 对齐 CC :580-610。 */
    private static String checkStartProcessVerbColon(PowerShellAstService.CommandElement c) {
        List<String> args = c.args();
        List<List<PowerShellAstService.CommandElementChild>> children = c.children();
        // (a) 结构层：children[i] ↔ args[i]，child.text 归一化（去引号/反引号/空白）为 runas
        if (children != null && !children.isEmpty()) {
            for (int i = 0; i < args.size(); i++) {
                String argClean = args.get(i).replace("`", "");
                if (!VERB_COLON_PARAM.matcher(argClean).find()) continue;
                List<PowerShellAstService.CommandElementChild> kids = i < children.size() ? children.get(i) : null;
                if (kids == null || kids.isEmpty()) continue;
                for (PowerShellAstService.CommandElementChild child : kids) {
                    if (child.text().replaceAll("['\"`\\s]", "").toLowerCase(Locale.ROOT).equals("runas")) {
                        if (log.isDebugEnabled()) {
                            log.debug("PowerShellCommandSafety: Start-Process -Verb 冒号 RunAs 命中（结构层） arg={}",
                                args.get(i));
                        }
                        return "命令请求提升权限（-Verb RunAs）";
                    }
                }
            }
        }
        // (b) 正则兜底：-Verb:'RunAs' / -Verb:"RunAs" / -Verb:`runas（引号/反引号绕过旧 /...:runas$/ 模式）
        for (String a : args) {
            String clean = a.replace("`", "");
            if (VERB_COLON_RUNAS.matcher(clean).matches()) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellCommandSafety: Start-Process -Verb 冒号 RunAs 命中（正则兜底） arg={}", a);
                }
                return "命令请求提升权限（-Verb RunAs）";
            }
        }
        return null;
    }

    /** checkInvokeItem：Invoke-Item/ii 用默认处理器打开文件（可执行文件=RCE）。 */
    private static String checkInvokeItem(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (lower.equals("invoke-item") || lower.equals("ii")) {
                return "Invoke-Item 用默认处理器打开文件，在可执行文件上运行任意代码";
            }
        }
        return null;
    }

    /** checkScheduledTask：计划任务持久化（Register-ScheduledTask 等 + schtasks /create）。 */
    private static String checkScheduledTask(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (SCHEDULED_TASK_CMDLETS.contains(lower)) {
                return c.name() + " 创建或修改计划任务（持久化原语）";
            }
            if (lower.equals("schtasks") || lower.equals("schtasks.exe")) {
                for (String a : c.args()) {
                    String la = a.toLowerCase(Locale.ROOT);
                    if (la.equals("/create") || la.equals("/change") || la.equals("-create")
                        || la.equals("-change")) {
                        return "schtasks create/change 修改计划任务（持久化原语）";
                    }
                }
            }
        }
        return null;
    }

    /** checkEnvVarManipulation：env 作用域变量 + 写 cmdlet / 赋值。 */
    private static String checkEnvVarManipulation(PowerShellAstService.ParsedResult parsed) {
        boolean hasEnvVar = parsed.variables().stream().anyMatch(v ->
            v.toLowerCase(Locale.ROOT).startsWith("env:"));
        if (!hasEnvVar) return null;
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            if (ENV_WRITE_CMDLETS.contains(c.name().toLowerCase(Locale.ROOT))) {
                return "命令修改环境变量";
            }
        }
        if (parsed.hasAssignments()) return "命令修改环境变量";
        return null;
    }

    /** checkModuleLoading：模块加载 cmdlet 执行 .psm1 顶层体。 */
    private static String checkModuleLoading(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            String resolved = ReadOnlyCommandTable.resolveToCanonical(lower);
            if (MODULE_LOADING_CMDLETS.contains(resolved) || MODULE_LOADING_CMDLETS.contains(lower)) {
                return "命令加载/安装/下载 PowerShell 模块或脚本，可执行任意代码";
            }
        }
        return null;
    }

    /** checkRuntimeStateManipulation：Set-Alias/Set-Variable 影响未来命令解析。 */
    private static String checkRuntimeStateManipulation(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String raw = c.name().toLowerCase(Locale.ROOT);
            String lower = raw.contains("\\") ? raw.substring(raw.lastIndexOf('\\') + 1) : raw;
            if (ALIAS_HIJACK_CMDLETS.contains(lower)) {
                return "命令创建或修改别名/变量，影响未来命令解析";
            }
        }
        return null;
    }

    /** checkWmiProcessSpawn：Invoke-WmiMethod/Invoke-CimMethod 生成任意进程。 */
    private static String checkWmiProcessSpawn(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (WMI_CIM_CMDLETS.contains(lower)) {
                return c.name() + " 可通过 WMI/CIM (Win32_Process Create) 生成任意进程";
            }
        }
        return null;
    }

    /** checkScriptBlockInjection：危险 cmdlet 的脚本块可执行任意代码。 */
    private static String checkScriptBlockInjection(PowerShellAstService.ParsedResult parsed) {
        if (!parsed.hasScriptBlocks()) return null;
        for (PowerShellAstService.CommandElement c : allCmds(parsed)) {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (DANGEROUS_SCRIPT_BLOCK_CMDLETS.contains(lower)) {
                return "命令包含危险 cmdlet 的脚本块，可能执行任意代码";
            }
        }
        boolean allSafe = allCmds(parsed).stream().allMatch(c -> {
            String lower = c.name().toLowerCase(Locale.ROOT);
            if (SAFE_SCRIPT_BLOCK_CMDLETS.contains(lower)) {
                return true;
            }
            // D2（G33②）：CC powershellSecurity.ts:692-696 先 COMMON_ALIASES 解析再查 SAFE 集。
            // 旧 Java 仅查原始名——`Get-Process | ? { ... }`（? = Where-Object 别名）SAFE 集不命中 →
            // over-ask（fail-safe，用户体验回归，非安全漏洞）。resolveToCanonical 等价 COMMON_ALIASES。
            String resolved = ReadOnlyCommandTable.resolveToCanonical(lower);
            return SAFE_SCRIPT_BLOCK_CMDLETS.contains(resolved);
        });
        if (allSafe) return null;
        return "命令包含脚本块，可能执行任意代码";
    }

    /**
     * checkTypeLiterals：类型字面量在 CLM 白名单外 → ask · 对齐 CC
     * {@code powershellSecurity.ts:800-813}。遍历全部 type literal，任一非 CLM 即 ask。
     * 在 checkMemberInvocations 之后运行（成员调用粗判，本检查给精确类型信号）。
     */
    private static String checkTypeLiterals(PowerShellAstService.ParsedResult parsed) {
        for (String t : parsed.typeLiterals()) {
            if (!isClmAllowedType(t)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellCommandSafety: checkTypeLiterals 命中 CLM 外类型 type={}", t);
                }
                return "命令使用 ConstrainedLanguage 白名单外的 .NET 类型 [" + t + "]";
            }
        }
        return null;
    }

    // ========================================================================
    // 危险 PowerShell allow 规则（auto-mode 剥离）· 对齐 CC permissionSetup.ts:157-233
    // isDangerousPowerShellPermission（OPD-WF4-DEC-02：DangerousPatternDetector 的 PS 专有
    // 形态归入 PowerShell 工具域）
    // ========================================================================

    /** 跨平台代码执行入口 · CC dangerousPatterns.ts:18-42 CROSS_PLATFORM_CODE_EXEC（与 Bash 共享）。 */
    static final List<String> CROSS_PLATFORM_CODE_EXEC = List.of(
        "python", "python3", "python2", "node", "deno", "tsx", "ruby", "perl", "php", "lua",
        "npx", "bunx", "npm run", "yarn run", "pnpm run", "bun run",
        "bash", "sh", "ssh");

    /** PS 专有危险模式 · CC permissionSetup.ts:178-209（CROSS_PLATFORM_CODE_EXEC + PS 专有）。 */
    static final List<String> DANGEROUS_POWER_SHELL_PATTERNS = buildDangerousPowerShellPatterns();

    private static List<String> buildDangerousPowerShellPatterns() {
        List<String> p = new ArrayList<>(CROSS_PLATFORM_CODE_EXEC);
        p.addAll(List.of(
            // 嵌套 PS + 可从 PS 启动的 shell
            "pwsh", "powershell", "cmd", "wsl",
            // 字符串/脚本块求值器
            "iex", "invoke-expression", "icm", "invoke-command",
            // 进程生成器
            "start-process", "saps", "start", "start-job", "sajb", "start-threadjob",
            // 事件/会话代码执行
            "register-objectevent", "register-engineevent", "register-wmievent", "register-scheduledjob",
            "new-pssession", "nsn", "enter-pssession", "etsn",
            // .NET 逃生舱
            "add-type", "new-object"));
        return p;
    }

    /**
     * PowerShell allow 规则是否危险（auto mode 剥离，防分类器绕过）· 对齐 CC
     * {@code isDangerousPowerShellPermission}（permissionSetup.ts:157-233）。
     *
     * <p>危险 = 允许执行任意代码（嵌套 shell / Invoke-Expression / Start-Process 等）的 PowerShell
     * allow 规则。PowerShell 大小写不敏感，content 先 lowercase。形态匹配 exact-shape
     * （content===pattern / pattern:* / pattern* / pattern * / pattern -*）+ .exe 变体
     * （'npm run' → 'npm.exe run'，CC :218-230）。整工具 allow（ruleContent null/空）与
     * content '*' 恒危险（CC :165-175）。
     *
     * <p>OPD-WF4-DEC-02：DangerousPatternDetector 中的 PS 专有形态（powershell/pwsh/IEX/
     * Invoke-WebRequest/Start-Process/...）归入 PowerShell 工具域（本方法），Bash 检测器不再
     * 误剥含 'powershell' 字样的 Bash 规则。消费方 {@code DangerousPatternDetector.isDangerousRule}
     * 对 "PowerShell" 工具委托本方法（CC isDangerousClassifierPermission :280-285 组合语义）。
     *
     * @param toolName    工具名（仅 "PowerShell" 参与）
     * @param ruleContent 规则内容（null/空 = 整工具 allow = 最危险）
     * @return true = 危险 allow 规则，auto mode 应剥离
     */
    public static boolean isDangerousPowerShellPermission(String toolName, String ruleContent) {
        if (!"PowerShell".equals(toolName)) {
            return false;
        }
        if (ruleContent == null || ruleContent.isEmpty()) {
            return true;
        }
        String content = ruleContent.trim().toLowerCase(Locale.ROOT);
        if (content.equals("*")) {
            return true;
        }
        for (String pattern : DANGEROUS_POWER_SHELL_PATTERNS) {
            if (content.equals(pattern)) return true;
            if (content.equals(pattern + ":*")) return true;
            if (content.equals(pattern + "*")) return true;
            if (content.equals(pattern + " *")) return true;
            if (content.startsWith(pattern + " -") && content.endsWith("*")) return true;
            // .exe 变体 —— 加在首词后（'python' → 'python.exe'；'npm run' → 'npm.exe run'）· CC :218-230
            String exe;
            int sp = pattern.indexOf(' ');
            if (sp == -1) {
                exe = pattern + ".exe";
            } else {
                exe = pattern.substring(0, sp) + ".exe" + pattern.substring(sp);
            }
            if (content.equals(exe)) return true;
            if (content.equals(exe + ":*")) return true;
            if (content.equals(exe + "*")) return true;
            if (content.equals(exe + " *")) return true;
            if (content.startsWith(exe + " -") && content.endsWith("*")) return true;
        }
        return false;
    }
}
