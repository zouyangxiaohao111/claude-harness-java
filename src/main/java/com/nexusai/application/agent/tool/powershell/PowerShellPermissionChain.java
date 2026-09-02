package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import com.nexusai.application.agent.skill.BundledSkillFileExtractor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PowerShell 权限主链 · 对齐 CC {@code powershellPermissions.ts:639-1648 powershellToolHasPermission}
 * 的 collect-then-reduce 全流程（session A5，替换旧 3 态 PowerShellPermission）。
 *
 * <p>流程（CC 顺序）：空命令 allow → deny 早返回 / ask 内容规则 + UNC 预检查 → preParseAskDecision
 * deferred（CC :701-723 仅赋值不 return）→ parse-failed 片段扫描（归一化 + 危险删除硬 deny +
 * deny 规则）→ collect-then-reduce（deferred ask 最先 push / 安全标志 / using / provider-UNC /
 * per-subcommand deny-ask / cd+git / bare git repo / git-internal 写守卫 / .git 写守卫 /
 * checkPathConstraints（{@link PowerShellPathValidator}）/ 只读 allowlist / 文件重定向）
 * → reduce deny &gt; ask &gt; allow → step5 fail-closed。
 *
 * <p>ask-masks-deny 结构性关闭：CC :694-723 把内容 ask / UNC 从「早返回」改为「仅赋值
 * preParseAskDecision」，parse-success 后 push 进 decisions[]（:905-907），子命令 deny
 * （:1043-1107）经 reduce 覆盖它；parse-failed 时先返 deferred ask 保留规则归因（:858-860）。
 * 危险删除硬 deny 在 parse-failed 片段循环内（:825-840）与 parse-succeeded 的
 * checkPathConstraints（pathValidation.ts:1735/1850）两处落地。
 *
 * <p>Java 同步签名映射 CC async（Tool.checkPermissions 为同步）；规则匹配复用
 * {@link RuleQuery}（deny 桶优先）。全部决策经 {@link #reduce} 按 deny &gt; ask &gt; allow 收敛。
 */
@Component
public class PowerShellPermissionChain {

    private static final Logger log = LoggerFactory.getLogger(PowerShellPermissionChain.class);

    private final PowerShellAstService astService;

    /** win32 平台门（native exe {@code /} 前缀 flag 识别）· 对齐 CC PowerShellTool.isWindows()/PackageManagers.isWindows()。 */
    private final boolean isWindows;

    /**
     * 生产注入（Spring {@code @Autowired}）· 唯一 public 构造，委托二参构造注入平台门。
     * native exe（ipconfig/systeminfo/tasklist 等）在 win32 下 {@code /flag} 为 argv 约定 flag，
     * 非 win32 下 {@code /x} 视为路径（CC readOnlyValidation.ts:1483-1484 gating）。
     */
    @Autowired
    public PowerShellPermissionChain(PowerShellAstService astService) {
        this(astService, detectWindows());
    }

    /** 测试注入平台门（isWindows）· package-private 供同包测试绕过 os.name 探测。 */
    PowerShellPermissionChain(PowerShellAstService astService, boolean isWindows) {
        this.astService = astService;
        this.isWindows = isWindows;
    }

    /** win32 探测 · 对齐 CC PowerShellTool.isWindows()/PackageManagers.isWindows() 既有 idiom。
     *  package-private（非 private）供同包静态方法 {@link #isCwdChangingCmdlet} 的 ndr/mount Windows 门复用。 */
    static boolean detectWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ── 内部可编辑/可读路径 carve-out 依赖（TR-C2-Q2 / 组 1-4⑤，对齐 CC pathValidation.ts isPathAllowed
    // step2/step3.5）──
    // Java 平台等价逻辑已实现在 ReadPermissionChecker（agent-memory/auto-memory/bundled-skills 读
    // carve-out）与工具层（agent-memory/auto-memory 编辑 carve-out）。PowerShell 链此前未接入（D-3），
    // 注入三个内部路径 bean（required=false：无 bean 的测试/嵌入场景下 carve-out 禁用，走原 fail-safe）。
    private AutoMemPaths autoMemPaths;

    private AgentMemoryDirectory agentMemoryDirectory;

    private BundledSkillFileExtractor bundledSkillFileExtractor;

    @Autowired(required = false)
    public void setAutoMemPaths(AutoMemPaths autoMemPaths) {
        this.autoMemPaths = autoMemPaths;
    }

    @Autowired(required = false)
    public void setAgentMemoryDirectory(AgentMemoryDirectory agentMemoryDirectory) {
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    @Autowired(required = false)
    public void setBundledSkillFileExtractor(BundledSkillFileExtractor bundledSkillFileExtractor) {
        this.bundledSkillFileExtractor = bundledSkillFileExtractor;
    }

    /**
     * 内部可编辑/可读路径 carve-out 判定（对齐 CC pathValidation.ts isPathAllowed step2/step3.5）：
     * 写/创建操作查 checkEditableInternalPath（agent-memory/auto-memory）；读操作查
     * checkReadableInternalPath（agent-memory/auto-memory/bundled-skills）。命中 → PowerShell
     * 路径校验放行（读/写内部路径无需工作目录外 ask），与 Read/WritePermissionChecker 工具层语义一致。
     *
     * <p>写分支 auto-memory carve-out 额外要求 {@code !hasAutoMemPathOverride()}（对齐 CC
     * filesystem.ts:1572 checkEditableInternalPath 与 EditFileTool/WriteFileTool 既有规范）：
     * override 是调用方任意指定目录，不获特殊权限处理，写走正常权限流（step5→ask）。
     * 读分支（checkReadableInternalPath filesystem.ts:1716）CC 无 override 守卫，不改。
     *
     * <p>bean 缺失（未注入）→ 恒 false（carve-out 关闭，走原 fail-safe 路径校验）。
     *
     * @param resolvedPath   解析后绝对路径（normalize 后）
     * @param operationType  read | edit（CC FileOperationType）
     * @return true = 内部路径，应放行
     */
    boolean internalPathCarveOut(String resolvedPath, String operationType) {
        if (resolvedPath == null) {
            return false;
        }
        boolean read = "read".equals(operationType);
        // 写/创建：agent-memory / auto-memory（CC checkEditableInternalPath isAgentMemoryPath + memdir）
        if (!read) {
            if (agentMemoryDirectory != null && agentMemoryDirectory.isAgentMemoryPath(resolvedPath)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: 内部可编辑路径 carve-out 放行（agent-memory）path={}",
                        resolvedPath);
                }
                return true;
            }
            // 写 carve-out 需 !hasAutoMemPathOverride() 守卫 · 对齐 CC filesystem.ts:1572
            // checkEditableInternalPath memdir 分支（override = 调用方任意指定目录，无特殊权限处理，
            // 写走正常权限流 step5→ask）+ EditFileTool:162 / WriteFileTool:221 既有规范。
            // 读 carve-out（checkReadableInternalPath filesystem.ts:1716）CC 无守卫，不改。
            if (autoMemPaths != null && !autoMemPaths.hasAutoMemPathOverride()
                && autoMemPaths.isAutoMemPath(resolvedPath)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: 内部可编辑路径 carve-out 放行（auto-memory）path={}",
                        resolvedPath);
                }
                return true;
            }
            return false;
        }
        // 读：agent-memory / auto-memory / bundled-skills（CC checkReadableInternalPath 三 carve-out）
        if (agentMemoryDirectory != null && agentMemoryDirectory.isAgentMemoryPath(resolvedPath)) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: 内部可读路径 carve-out 放行（agent-memory）path={}",
                    resolvedPath);
            }
            return true;
        }
        if (autoMemPaths != null && autoMemPaths.isAutoMemPath(resolvedPath)) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: 内部可读路径 carve-out 放行（auto-memory）path={}",
                    resolvedPath);
            }
            return true;
        }
        if (bundledSkillFileExtractor != null) {
            java.nio.file.Path skillsRoot = bundledSkillFileExtractor.getBundledSkillsRoot();
            if (skillsRoot != null) {
                String root = skillsRoot.normalize().toString().replace('\\', '/');
                String normalized = resolvedPath.replace('\\', '/');
                if (normalized.equals(root) || normalized.startsWith(root.endsWith("/") ? root : root + "/")) {
                    if (log.isDebugEnabled()) {
                        log.debug("PowerShellPermissionChain: 内部可读路径 carve-out 放行（bundled-skills）path={}",
                            resolvedPath);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** git 写入类 cmdlet（可把文件放到调用者指定路径）· 对齐 CC GIT_SAFETY_WRITE_CMDLETS:70-84。 */
    private static final java.util.Set<String> GIT_SAFETY_WRITE_CMDLETS = java.util.Set.of(
        "new-item", "set-content", "add-content", "out-file", "copy-item", "move-item",
        "rename-item", "expand-archive", "invoke-webrequest", "invoke-restmethod", "tee-object",
        "export-csv", "export-clixml");

    /** 外部归档解压应用（tar 解压后 git 的 TOCTOU）· 对齐 CC GIT_SAFETY_ARCHIVE_EXTRACTORS:96-112。 */
    private static final java.util.Set<String> GIT_SAFETY_ARCHIVE_EXTRACTORS = java.util.Set.of(
        "tar", "tar.exe", "bsdtar", "bsdtar.exe", "unzip", "unzip.exe", "7z", "7z.exe",
        "7za", "7za.exe", "gzip", "gzip.exe", "gunzip", "gunzip.exe", "expand-archive");

    private static final Pattern WINDOWS_DRIVE_ROOT = Pattern.compile("^[A-Za-z]:/?$");
    /** 驱动器子级（C:/Windows 危险，C:/Windows/System32 不危险）· 对齐 CC pathValidation.ts:319 WINDOWS_DRIVE_CHILD_REGEX。 */
    private static final Pattern WINDOWS_DRIVE_CHILD = Pattern.compile("^[A-Za-z]:/[^/]+$");
    /** 通配转义占位（\u0000 空字节哨兵）· 对齐 CC shellRuleMatching.ts:14 ESCAPED_STAR_PLACEHOLDER。 */
    private static final String ESCAPED_STAR_PLACEHOLDER = "\u0000ESCAPED_STAR\u0000";
    /** 反斜杠转义占位 · 对齐 CC shellRuleMatching.ts:15 ESCAPED_BACKSLASH_PLACEHOLDER。 */
    private static final String ESCAPED_BACKSLASH_PLACEHOLDER = "\u0000ESCAPED_BACKSLASH\u0000";

    /** 赋值前缀（$x = / $x += / $x ??=）· 对齐 CC powershellPermissions.ts:62 PS_ASSIGN_PREFIX_RE。 */
    private static final Pattern PS_ASSIGN_PREFIX = Pattern.compile("^\\$[\\w:]+\\s*(?:[+\\-*/%]|\\?\\?)?\\s*=\\s*");

    // ════════════════════════════════════════════════════════════════════════
    // 入口 · 对齐 CC powershellToolHasPermission:639
    // ════════════════════════════════════════════════════════════════════════
    public PermissionResult check(JsonNode input, ToolUseContext ctx, Tool tool) {
        String command = input.path("command").asText("").trim();
        if (command.isEmpty()) {
            return allow(input, "空命令安全");
        }

        // STEP 1+2a: deny/ask 内容规则（精确 + 前缀），CC :661-711。deny 早返回；ask → deferred（CC :701-723）
        ToolPermissionContext permCtx = ctx != null ? ctx.permissionContext() : null;
        PermissionRule contentRule = permCtx != null && tool != null
            ? RuleQuery.getRuleForInput(permCtx, tool, input)
            : null;
        if (contentRule != null && contentRule.ruleBehavior() == PermissionBehavior.DENY) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: deny 规则命中 rule={} command={}",
                    RuleQuery.ruleToString(contentRule), command);
            }
            return new PermissionResult.Deny(
                "Permission to use PowerShell with command " + command + " has been denied.",
                new PermissionDecisionReason.Rule(contentRule), null);
        }
        // 2b. ask 内容规则 → preParseAskDecision（CC :701-711 仅赋值不 return）
        PermissionResult preParseAsk = null;
        if (contentRule != null) {
            preParseAsk = ask("Permission to use PowerShell with command " + command + " requires approval", input);
        }
        // UNC 预检查 → preParseAskDecision（CC :717-723 仅赋值不 return）
        if (preParseAsk == null && containsVulnerableUncPath(command)) {
            preParseAsk = ask("命令包含 UNC 路径，可能触发网络请求", input);
        }

        PowerShellAstService.ParsedResult parsed = astService.parseAst(command);

        // parse-failed 降级（CC :764-874）。2c exact-allow 短路（:750-757）必须最先：
        // 仅当解析失败 + 无 pre-parse ask + 首词非 application 时才放行（pwsh 不可用 fail-safe
        // allow；脚本/exe 含 \ 降 ask）。exact allow 只在 exact match 时生效（不扩大前缀 allow）。
        if (!parsed.valid()) {
            PermissionRule exactAllow = exactAllowRule(permCtx, tool, command);
            if (exactAllow != null && preParseAsk == null
                    && !"application".equals(classifyCommandName(firstToken(command)))) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: parse-failed 2c exact-allow 短路 rule={} command={}",
                        RuleQuery.ruleToString(exactAllow), command);
                }
                return new PermissionResult.Allow(input,
                    new PermissionDecisionReason.Rule(exactAllow), null, false, null, null);
            }
            PermissionResult fragDeny = parseFailedFragmentScan(command, ctx, tool);
            if (fragDeny != null) {
                return fragDeny;
            }
            if (preParseAsk != null) {
                return preParseAsk; // CC :858-860 先返 deferred ask，保留规则归因
            }
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: 解析失败, 降级 ask command={} errors={}",
                    command, parsed.errors());
            }
            return ask("命令包含无法解析的语法", input);
        }

        // ═══ collect-then-reduce（CC :876-1368）═══
        List<PermissionResult> decisions = new ArrayList<>();
        List<PowerShellSubCommandInfo> allSub =
            astService.subCommandsForPermissionCheck(parsed, command);

        // 决策 0：deferred pre-parse ask（2b ask 规则或 UNC）· CC :905-907 最先 push，
        // first-of-behavior 消息优先；reduce 仍保证任何 deny 覆盖它
        if (preParseAsk != null) {
            decisions.add(preParseAsk);
        }

        // 安全校验 → ask · 对齐 CC powershellCommandIsSafe（powershellSecurity.ts:1042-1090 的
        // 24 validator 等价：Invoke-Expression / 动态命令名 / EncodedCommand / 嵌套 pwsh /
        // download cradle / 下载工具 / Add-Type / COM / 脚本文件执行 / ForEach -MemberName /
        // Start-Process 提权 / 脚本块注入 / 子表达式 / splatting / 类型字面量 / Invoke-Item /
        // 计划任务 / env 修改 / 模块加载 / 别名劫持 / WMI-CIM spawn / 赋值）。
        String safetyMsg = PowerShellCommandSafety.findAskMessage(parsed);
        if (safetyMsg != null) {
            decisions.add(ask(safetyMsg, input));
        }
        // using 语句 / #Requires → ask（CC :940-971）
        if (parsed.hasUsingStatements()) {
            decisions.add(ask("命令包含 using 语句，可能加载外部模块或程序集", input));
        }
        if (parsed.hasScriptRequirements()) {
            decisions.add(ask("命令包含 #Requires 指令，可能触发模块加载", input));
        }
        // provider/UNC 逐参数扫描 → ask（CC :973-1041）
        PermissionResult providerUnc = providerOrUncScan(parsed);
        if (providerUnc != null) {
            decisions.add(providerUnc);
        }
        // per-subcommand deny/ask 规则（CC :1043-1107，raw + canonical 双查）
        decisions.addAll(subCommandRules(parsed, input, ctx, tool, command));

        // 复合守卫标志（供 checkPathConstraints + step5）· CC :1127-1138
        boolean hasCdSubCommand = allSub.size() > 1
            && allSub.stream().anyMatch(s -> isCwdChangingCmdlet(s.element().name()));
        boolean hasSymlinkCreate = allSub.size() > 1
            && allSub.stream().anyMatch(s -> PowerShellModeValidation.isSymlinkCreatingCommand(s.element()));
        boolean hasGit = allSub.stream().anyMatch(s ->
            "git".equals(ReadOnlyCommandTable.resolveToCanonical(s.element().name())));
        // cd+git 复合守卫 → ask（CC :1109-1145）
        if (hasCdSubCommand && hasGit) {
            decisions.add(ask("cd/Set-Location 与 git 组合命令需审批，防止裸仓库攻击", input));
        }
        // 裸仓库守卫 → ask（CC :1155-1166）
        if (hasGit && isCurrentDirectoryBareGitRepo(ctx)) {
            decisions.add(ask("当前目录存在裸仓库指示（无 .git/HEAD 的 HEAD/objects/refs），git 可能从 cwd 执行钩子", input));
        }
        java.nio.file.Path cwd = effectiveCwd(ctx);
        // git-internal 写守卫 → ask（CC :1168-1234）
        if (hasGit && writesToGitInternal(parsed, allSub, cwd)) {
            decisions.add(ask("命令写入 git 内部路径（HEAD/objects/refs/hooks/.git）并运行 git，可能植入恶意钩子", input));
        }
        // 归档解压 + git → ask（CC :1219-1233）
        if (hasGit && allSub.stream().anyMatch(s ->
            GIT_SAFETY_ARCHIVE_EXTRACTORS.contains(s.element().name().toLowerCase()))) {
            decisions.add(ask("命令解压归档并运行 git，归档内容可能植入裸仓库指示", input));
        }
        // .git/ 写守卫（无 git 子命令也生效）· CC :1240-1257
        if (writesToDotGit(parsed, allSub, cwd)) {
            decisions.add(ask("命令写入 .git/ —— 植入的钩子或配置将在下次 git 操作时执行", input));
        }
        // 决策：路径约束 checkPathConstraints（CC :1259-1279，deny-capable）。危险删除 deny /
        // Edit deny / unvalidatable ask / cd 复合 ask 在此落地（PowerShellPathValidator）。
        // TR-C2-Q2 / 组 1-4⑤：注入内部可编辑/可读路径 carve-out（this::internalPathCarveOut），
        // isPathAllowed step2/step3.5 对 agent-memory/auto-memory/bundled-skills 内部路径放行。
        // ThreadLocal 单请求同步作用域，finally 清除防跨请求泄漏；未注入 bean → carve-out 恒 false。
        PowerShellPathValidator.setInternalPathCarveOut(this::internalPathCarveOut);
        PermissionResult pathResult;
        try {
            pathResult = PowerShellPathValidator.check(input, parsed, permCtx, cwd, hasCdSubCommand);
        } finally {
            PowerShellPathValidator.clearInternalPathCarveOut();
        }
        if (pathResult != null && !(pathResult instanceof PermissionResult.Passthrough)) {
            decisions.add(pathResult);
        }
        // 决策：exact allow（parse-succeeded）· 对齐 CC :1281-1316（R39）。整命令精确 allow 复合体
        // 在无 deny/ask 时经 reduce 浮现（子命令 deny → path constraints → exact allow 顺序，与 Bash 对齐）。
        // 双门：nameType!=='application'（防本地 .ps1/脚本路径冒名 cmdlet）+ !argLeaksValue（防
        // Write-Output $env:KEY 泄漏，finding #32）；allSub 全子命令 every 检查（pipeline 任一命令
        // 泄漏即不放行）。
        PermissionRule exactAllow = exactAllowRule(permCtx, tool, command);
        if (exactAllow != null
                && !allSub.isEmpty()
                && allSub.stream().allMatch(sc ->
                    !"application".equals(sc.element().nameType())
                        && !PowerShellModeValidation.argLeaksValue(sc.text(), sc.element()))) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: R39 整命令精确 allow 门放行 rule={} command={}",
                    RuleQuery.ruleToString(exactAllow), command);
            }
            decisions.add(new PermissionResult.Allow(input,
                new PermissionDecisionReason.Rule(exactAllow), null, false, null, null));
        }
        // 只读 allowlist → allow（CC :1318-1331；决策点无 symlink 门控，isReadOnlyCommand 已因
        // New-Item 等不在 allowlist 而拒绝 symlink 复合）。hasSymlinkCreate 门控属 CC step5
        // per-subcommand 循环（:1469/:1526/:1554），由 step5SubCommandApproval 处理。
        if (isReadOnlyCommand(command, parsed)) {
            decisions.add(allow(input, "命令只读且安全"));
        }
        // 文件重定向 → ask（CC :1333-1345）。getFileRedirections 滤 isMerging + $null 目标
        // （CC getFileRedirections parser.ts:1713-1719）：'Get-Process > $null' 丢弃输出不写文件，
        // 不误弹窗（OPD-PS-05）。
        if (!getFileRedirections(parsed).isEmpty()) {
            decisions.add(ask("命令包含文件重定向，可能写入任意路径", input));
        }
        // 决策：mode 专属处理（acceptEdits）· CC :1347-1352 checkPermissionMode 只返回
        // allow | passthrough，非 passthrough 推入 decisions。顶层 collect 补入（OPD-PS-03）——
        // 对齐 CC decisionReason（mode acceptEdits）在 reduce 中参与 deny > ask > allow 归因
        // （旧 Java 仅在 step5 逐子命令合成单语句 AST 时调用，顶层 acceptEdits 写 cmdlet 落
        // step5 passthrough 而非 Allow）。
        PermissionResult modeResult = PowerShellModeValidation.checkPermissionMode(
            input, parsed, permCtx, this::isAllowlistedCommand);
        if (modeResult != null && !(modeResult instanceof PermissionResult.Passthrough)) {
            decisions.add(modeResult);
        }

        // REDUCE deny > ask > allow（CC :1354-1368）
        PermissionResult denied = reduce(decisions, PermissionResult.Deny.class);
        if (denied != null) return denied;
        PermissionResult asked = reduce(decisions, PermissionResult.Ask.class);
        if (asked != null) return asked;
        PermissionResult allowed = reduce(decisions, PermissionResult.Allow.class);
        if (allowed != null) return allowed;

        // step5 per-subcommand 循环（CC :1370-1648）· 无任何 collect 决策时逐子命令独立审批
        return step5SubCommandApproval(command, input, parsed, ctx, tool, permCtx,
            hasCdSubCommand, hasSymlinkCreate);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结果构造 + reduce
    // ════════════════════════════════════════════════════════════════════════
    private PermissionResult allow(JsonNode input, String reason) {
        return new PermissionResult.Allow(input, new PermissionDecisionReason.Other(reason), null, false, null, null);
    }

    private PermissionResult ask(String message, JsonNode input) {
        return new PermissionResult.Ask(message, new PermissionDecisionReason.Other(message),
            List.of(), null, input, null, false, null, null);
    }

    private PermissionResult deny(String message) {
        return new PermissionResult.Deny(message, new PermissionDecisionReason.SafetyCheck(message, false), null);
    }

    private PermissionResult passthrough(JsonNode input, String command, List<PermissionUpdate> suggestions) {
        return new PermissionResult.Passthrough("PowerShell 命令需评估（非只读/未阻断/无规则）",
            new PermissionDecisionReason.Other("powershell step5 passthrough"),
            suggestions == null ? List.of() : suggestions, null, null);
    }

    private PermissionResult reduce(List<PermissionResult> decisions, Class<?> type) {
        for (PermissionResult d : decisions) {
            if (type.isInstance(d)) {
                return d;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 文件重定向收集 · 对齐 CC parser.ts:1703-1719（isNullRedirectionTarget + getFileRedirections）
    // ════════════════════════════════════════════════════════════════════════

    /** $null 重定向目标判定（> $null / > ${null} 丢弃输出）· CC parser.ts:1703-1706。 */
    static boolean isNullRedirectionTarget(String target) {
        String t = target == null ? "" : target.trim().toLowerCase();
        return t.equals("$null") || t.equals("${null}");
    }

    /**
     * 文件重定向列表（滤 isMerging + $null 目标）· 对齐 CC {@code getFileRedirections}
     * （parser.ts:1713-1719，其输入 getAllRedirections:1507-1527 = 语句级 + nestedCommands 级）。
     *
     * <p>语义：{@code 2>&1}（MergingRedirectionAst，isMerging=true）不写文件；{@code > $null} 丢弃输出
     * 等价 /dev/null 非文件系统写。二者滤除后 {@code Get-Process > $null} 不再误 ask（OPD-PS-05）。
     * nestedCommands 级 redirections（CC getAllRedirections :1515-1524）来自
     * {@link PowerShellAstService.CommandElement#redirections()}——transformCommandAst 已排除 merging
     * （空 target 不入表），故仅需再滤 $null。
     */
    static List<PowerShellAstService.Redirection> getFileRedirections(PowerShellAstService.ParsedResult parsed) {
        List<PowerShellAstService.Redirection> out = new ArrayList<>();
        for (PowerShellAstService.Redirection r : parsed.redirections()) {
            if (r.isMerging()) continue;
            if (r.target() == null || r.target().isEmpty()) continue;
            if (isNullRedirectionTarget(r.target())) continue;
            out.add(r);
        }
        for (PowerShellAstService.Statement st : parsed.statements()) {
            for (PowerShellAstService.CommandElement c : st.nestedCommands()) {
                for (String target : c.redirections()) {
                    if (target == null || target.isEmpty()) continue;
                    if (isNullRedirectionTarget(target)) continue;
                    out.add(new PowerShellAstService.Redirection(target, false));
                }
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // step5 per-subcommand 循环 · 对齐 CC :1370-1648
    // ════════════════════════════════════════════════════════════════════════

    /**
     * step5 逐子命令独立审批 · 对齐 CC {@code powershellPermissions.ts:1370-1648}。
     *
     * <p>流程（CC 顺序）：safe-output/cd-to-CWD 过滤 + nameType 门（:1379-1408）→ per-sub
     * deny/ask/allow 全量 check（:1434-1577）：deny→return；ask→push；user allow（
     * nameType!=='application' && !hasSymlinkCreate + argLeaksValue 门）→auto-allow 或 push；
     * application+allow→push（:1493-1503）；fail-closed（statement!=null && !hasCdSubCommand &&
     * !hasSymlinkCreate && isProvablySafeStatement && isAllowlistedCommand，:1523-1531）；
     * per-sub acceptEdits checkPermissionMode（:1554-1570）；否则 push → fail-closed 第二段
     * （:1593-1597 未 seen 的非 provably-safe 语句 push）→ 空列表：scriptBlocks→ask（:1607-1617）
     * 否则 allow('individually allowed')（:1618-1625）→ step6 passthrough + 逐子命令 suggestions。
     *
     * @param command          整条命令（deny 消息用）
     * @param input            工具输入
     * @param parsed           AST 解析结果
     * @param ctx              工具调用上下文（effectiveCwd）
     * @param tool             工具实例（规则桶查询）
     * @param permCtx          权限上下文
     * @param hasCdSubCommand  复合含 cd 类 cmdlet（:1127-1129）
     * @param hasSymlinkCreate 复合含链接创建 cmdlet（:1133-1135）
     */
    private PermissionResult step5SubCommandApproval(String command, JsonNode input,
                                                     PowerShellAstService.ParsedResult parsed,
                                                     ToolUseContext ctx, Tool tool,
                                                     ToolPermissionContext permCtx,
                                                     boolean hasCdSubCommand, boolean hasSymlinkCreate) {
        List<PowerShellSubCommandInfo> allSub = astService.subCommandsForPermissionCheck(parsed, command);
        java.nio.file.Path cwd = effectiveCwd(ctx);
        // 过滤：safe-output / cd-to-CWD 无操作 / nameType 门 · CC :1379-1408
        List<PowerShellSubCommandInfo> subCommands = new ArrayList<>();
        for (PowerShellSubCommandInfo info : allSub) {
            PowerShellAstService.CommandElement element = info.element();
            if (info.isSafeOutput()) {
                continue; // safe-output cmdlet 不独立审批，继承前置命令权限 · CC :1380-1382
            }
            // nameType 门：application 保留在审批列表（不可被安全过滤静默放行）· CC :1388-1390
            if ("application".equals(element.nameType())) {
                subCommands.add(info);
                continue;
            }
            String canonical = ReadOnlyCommandTable.resolveToCanonical(element.name());
            if (canonical.equals("set-location") && !element.args().isEmpty()) {
                // cd-to-CWD 无操作过滤（模型习惯，Bash parity）· CC :1392-1406。
                // 用 PS_TOKENIZER_DASH_CHARS 判参数（en-dash 等 Unicode dash 不能当位置参数）。
                String target = element.args().stream()
                    .filter(a -> a.isEmpty() || !isPowerShellDashChar(a.charAt(0)))
                    .findFirst().orElse(null);
                if (target != null && resolveCwd(cwd, target).equals(cwd)) {
                    continue;
                }
            }
            subCommands.add(info);
        }

        // statementsSeenInLoop：PUSH 时追踪（CC :1420-1429 SECURITY：仅 push 时标记，防止
        // user-allow continue 的语句被 fail-closed 门跳过 → `if($true){Get-Process;$env:SECRET}`
        // 中 $env:SECRET 被漏查）。
        Set<PowerShellAstService.Statement> statementsSeenInLoop = new HashSet<>();
        List<String> subCommandsNeedingApproval = new ArrayList<>();

        for (PowerShellSubCommandInfo info : subCommands) {
            String subCmd = info.text();
            PowerShellAstService.CommandElement element = info.element();
            PowerShellAstService.Statement statement = info.statement();
            // deny 规则最先（用户显式规则优先于 allowlist）· CC :1435-1448
            PermissionResult subResult = subCheckPermission(subCmd, permCtx, tool);
            if (subResult instanceof PermissionResult.Deny) {
                return subResult;
            }
            if (subResult instanceof PermissionResult.Ask) {
                if (statement != null) statementsSeenInLoop.add(statement);
                subCommandsNeedingApproval.add(subCmd);
                continue;
            }
            // 显式 allow 规则 auto-allow —— 但 NOT for applications/scripts（CC :1458-1503）
            if (subResult instanceof PermissionResult.Allow
                && !"application".equals(element.nameType()) && !hasSymlinkCreate) {
                // SECURITY: user allow rule 只断言 cmdlet 安全，不证明任意变量展开安全 ·
                // argLeaksValue 门（CC :1484-1490）
                if (PowerShellModeValidation.argLeaksValue(subCmd, element)) {
                    if (statement != null) statementsSeenInLoop.add(statement);
                    subCommandsNeedingApproval.add(subCmd);
                    continue;
                }
                continue; // user allow rule auto-allow
            }
            if (subResult instanceof PermissionResult.Allow) {
                // application+allow 或 hasSymlinkCreate：规则为 cmdlet 写的，脚本伪装 → push
                if (statement != null) statementsSeenInLoop.add(statement);
                subCommandsNeedingApproval.add(subCmd);
                continue;
            }
            // fail-closed 门 · CC :1523-1531。isAllowlistedCommand 只放行可完全静态验证的语句
            if (statement != null && !hasCdSubCommand && !hasSymlinkCreate
                && PowerShellModeValidation.isProvablySafeStatement(statement)
                && isAllowlistedCommand(element)) {
                continue;
            }
            // per-sub acceptEdits（BashTool parity）· CC :1554-1570。合成单语句 AST 委托
            // checkPermissionMode，复用其全部守卫（安全标志/ACCEPT_EDITS_ALLOWED_CMDLETS）。
            if (statement != null && !hasCdSubCommand && !hasSymlinkCreate) {
                PowerShellAstService.ParsedResult synthetic =
                    syntheticSingleStatement(subCmd, parsed, statement);
                PermissionResult modeResult = PowerShellModeValidation.checkPermissionMode(
                    inputWithCommand(subCmd), synthetic, permCtx, this::isAllowlistedCommand);
                if (modeResult instanceof PermissionResult.Allow) {
                    continue;
                }
            }
            if (statement != null) statementsSeenInLoop.add(statement);
            subCommandsNeedingApproval.add(subCmd);
        }

        // fail-closed 第二段 · CC :1593-1597。仅 push 未在循环中 seen 的非 provably-safe 语句。
        for (PowerShellAstService.Statement stmt : parsed.statements()) {
            if (!PowerShellModeValidation.isProvablySafeStatement(stmt)
                && !statementsSeenInLoop.contains(stmt)) {
                subCommandsNeedingApproval.add(stmt.text());
            }
        }

        if (subCommandsNeedingApproval.isEmpty()) {
            // SECURITY: 空列表 auto-allow 仅在无不可验证内容时安全。脚本块内容未被验证
            // （AssignmentStatementAst 等非 CommandAst 对 getAllCommands 不可见）→ ask · CC :1607-1617
            if (parsed.hasScriptBlocks()) {
                return ask("Pipeline consists of output-formatting cmdlets with script blocks — block content cannot be verified", input);
            }
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: step5 空列表 auto-allow command={}", command);
            }
            return allow(input, "All pipeline commands are individually allowed");
        }

        // step6：部分子命令需审批 → passthrough + 逐子命令 suggestions · CC :1628-1648
        List<PermissionUpdate> suggestions = new ArrayList<>();
        for (String subCmd : subCommandsNeedingApproval) {
            suggestions.addAll(suggestionForExactCommand(subCmd));
        }
        if (log.isDebugEnabled()) {
            log.debug("PowerShellPermissionChain: step5 需审批子命令={} command={}",
                subCommandsNeedingApproval, command);
        }
        return passthrough(input, command, suggestions);
    }

    /**
     * per-sub 全量权限 check · 对齐 CC {@code powershellToolCheckPermission}
     * （powershellPermissions.ts:435-520）：exact/prefix deny → ask → allow → passthrough。
     */
    private PermissionResult subCheckPermission(String subCmd, ToolPermissionContext permCtx, Tool tool) {
        if (permCtx == null || tool == null) {
            return null;
        }
        JsonNode subInput = inputWithCommand(subCmd);
        PermissionRule denyRule = matchingRule(permCtx, tool, PermissionBehavior.DENY, subCmd);
        if (denyRule != null) {
            return new PermissionResult.Deny(
                "Permission to use PowerShell with command " + subCmd + " has been denied.",
                new PermissionDecisionReason.Rule(denyRule), null);
        }
        PermissionRule askRule = matchingRule(permCtx, tool, PermissionBehavior.ASK, subCmd);
        if (askRule != null) {
            return ask("Permission to use PowerShell with command requires approval", subInput);
        }
        PermissionRule allowRule = matchingRule(permCtx, tool, PermissionBehavior.ALLOW, subCmd);
        if (allowRule != null) {
            return new PermissionResult.Allow(subInput,
                new PermissionDecisionReason.Rule(allowRule), null, false, null, null);
        }
        return null; // passthrough
    }

    /** 合成单语句 ParsedResult（per-sub acceptEdits 用）· 对齐 CC :1555-1564 的合成 AST。 */
    private PowerShellAstService.ParsedResult syntheticSingleStatement(
            String subCmd, PowerShellAstService.ParsedResult parent,
            PowerShellAstService.Statement statement) {
        return new PowerShellAstService.ParsedResult(true, List.of(),
            parent.hasStopParsing(), false, false,
            parent.hasScriptBlocks(), parent.hasSubExpressions(), parent.hasExpandableStrings(),
            parent.hasSplatting(), parent.hasMemberInvocations(), parent.hasAssignments(),
            parent.variables(), parent.typeLiterals(), List.of(statement), List.of(), subCmd);
    }

    /** resolve(cwd, target) === cwd 判定 · 对齐 CC :1403-1404。 */
    private static java.nio.file.Path resolveCwd(java.nio.file.Path cwd, String target) {
        try {
            return cwd.resolve(target).normalize();
        } catch (Exception e) {
            return cwd;
        }
    }

    /**
     * 逐子命令精确命令建议 · 对齐 CC {@code suggestionForExactCommand}（:150-155，
     * 多行/含 * 跳过 + sharedSuggestionForExactCommand）。
     */
    private static List<PermissionUpdate> suggestionForExactCommand(String command) {
        if (command.contains("\n") || command.contains("*")) {
            return List.of();
        }
        return List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(new PermissionRule(PermissionRuleSource.LOCAL_SETTINGS,
                PermissionBehavior.ALLOW, new PermissionRuleValue("PowerShell", command))),
            PermissionBehavior.ALLOW));
    }

    // ════════════════════════════════════════════════════════════════════════
    // UNC 检测 · 对齐 CC readOnlyCommandValidation.ts:1562-1638 containsVulnerableUncPath（8 模式）
    // ════════════════════════════════════════════════════════════════════════
    /** backslash UNC：\\server / \\server\share / \\server@port\share · CC :1572。 */
    private static final Pattern UNC_BACKSLASH = Pattern.compile(
        "\\\\[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE);
    /** forward-slash UNC：//server/share（(?<!:) 排除 URL ://）· CC :1582。 */
    private static final Pattern UNC_FORWARD_SLASH = Pattern.compile(
        "(?<!:)//[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE);
    /** 混合分隔（前斜杠 + 反斜杠）：/\\server → UNC（2+ 反斜杠，单反斜杠只是转义）· CC :1594。 */
    private static final Pattern UNC_MIXED_FORWARD = Pattern.compile("/\\\\{2,}[^\\s\\\\/]");
    /** 混合分隔（反斜杠 + 前斜杠）：\\\\/server → UNC · CC :1602。 */
    private static final Pattern UNC_MIXED_BACK = Pattern.compile("\\\\{2,}/[^\\s\\\\/]");
    /** WebDAV SSL/port：@SSL@8443 / @8443@SSL · CC :1609。 */
    private static final Pattern UNC_DAV_SSL = Pattern.compile("@SSL@\\d+|@\\d+@SSL", Pattern.CASE_INSENSITIVE);
    /** DavWWWRoot 标记（Windows WebDAV redirector）· CC :1615。 */
    private static final Pattern UNC_DAV_WWWROOT = Pattern.compile("DavWWWRoot", Pattern.CASE_INSENSITIVE);
    /** IPv4 UNC：\\\\192.168.1.1\share / //192.168.1.1/share · CC :1621-1626。 */
    private static final Pattern UNC_IPV4 = Pattern.compile(
        "^(?:\\\\\\\\|//)(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})[\\\\/]");
    /** IPv6 UNC：\\\\[2001:db8::1]\share / //[::1]/share · CC :1630-1635。 */
    private static final Pattern UNC_IPV6 = Pattern.compile(
        "^(?:\\\\\\\\|//)(\\[[\\da-fA-F:]+\\])[\\\\/]");

    /**
     * 路径/命令是否含可触发网络请求（NTLM/Kerberos 凭据泄漏、WebDAV 攻击）的 UNC 路径。
     *
     * <p>对齐 CC {@code readOnlyCommandValidation.ts:1562-1638 containsVulnerableUncPath} 的
     * <b>8 模式</b>（backslash / forward-slash / 混合分隔 ×2 / WebDAV SSL / DavWWWRoot / IPv4 /
     * IPv6）+ {@code getPlatform() !== 'windows'} 平台门（CC :1564-1566）。OPD-PS-02：
     * Java 旧实现仅 4 模式（缺混合分隔/IPv4/IPv6）且无平台门。
     *
     * <p>实例方法（非 static）以读取 {@link #isWindows} 平台门；非 Windows 平台恒 false。
     */
    boolean containsVulnerableUncPath(String s) {
        if (s == null || !isWindows) {
            return false;
        }
        if (UNC_BACKSLASH.matcher(s).find()) return true;
        if (UNC_FORWARD_SLASH.matcher(s).find()) return true;
        if (UNC_MIXED_FORWARD.matcher(s).find()) return true;
        if (UNC_MIXED_BACK.matcher(s).find()) return true;
        if (UNC_DAV_SSL.matcher(s).find()) return true;
        if (UNC_DAV_WWWROOT.matcher(s).find()) return true;
        if (UNC_IPV4.matcher(s).find()) return true;
        if (UNC_IPV6.matcher(s).find()) return true;
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 危险删除硬 deny · 对齐 CC pathValidation.ts:840 isDangerousRemovalRawPath + :852 dangerousRemovalDeny
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 原始路径危险删除判定（tilde 展开 + 反斜杠归一化）。
     * <p>public（原 package-private）：供 {@code BashPathValidator} 交叉复用（单一真理源，
     * 对齐 CC utils/permissions/pathValidation.ts isDangerousRemovalPath 被 Bash 与
     * PowerShell 双方 import）。
     */
    public static boolean isDangerousRemovalRawPath(String filePath) {
        if (filePath == null) return false;
        String expanded = expandTilde(filePath.replaceAll("^['\"]|['\"]$", "").replace('\\', '/'));
        return isDangerousRemovalPath(expanded);
    }

    /**
     * 危险路径表判定（单一真理源 · CC utils/permissions/pathValidation.ts:331-367）。
     * <p>public（原 package-private）：供 BashPathValidator 交叉建表复用，不重复实现。
     */
    public static boolean isDangerousRemovalPath(String resolvedPath) {
        String forwardSlashed = resolvedPath.replaceAll("[\\\\/]+", "/");
        if (forwardSlashed.equals("*") || forwardSlashed.endsWith("/*")) return true;
        String normalized = forwardSlashed.equals("/") ? forwardSlashed : forwardSlashed.replaceAll("/$", "");
        if (normalized.equals("/")) return true;
        if (WINDOWS_DRIVE_ROOT.matcher(normalized).matches()) return true;
        String home = System.getProperty("user.home", "").replaceAll("[\\\\/]+", "/");
        if (!home.isEmpty() && normalized.equals(home)) return true;
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash == 0) return true; // 根直接子目录（/usr /etc /tmp 等）
        // 驱动器子级（C:/Windows、C:/Users）危险 · 对齐 CC pathValidation.ts:362-364（C:/Windows/System32 不危险）
        if (WINDOWS_DRIVE_CHILD.matcher(normalized).matches()) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: 驱动器子级危险删除 path={}", normalized);
            }
            return true;
        }
        return false;
    }

    private static String expandTilde(String filePath) {
        if (filePath.equals("~") || filePath.startsWith("~/") || filePath.startsWith("~\\")) {
            return System.getProperty("user.home", "") + filePath.substring(1);
        }
        return filePath;
    }

    /** PS 参数前缀 dash 字符集 · 对齐 CC parser.ts PS_TOKENIZER_DASH_CHARS（ASCII - 加 Unicode 横线）。 */
    static boolean isPowerShellDashChar(char c) {
        return c == '-' || c == '\u2013' || c == '\u2014' || c == '\u2015';
    }

    // ════════════════════════════════════════════════════════════════════════
    // parse-failed 降级片段扫描 · 对齐 CC :784-874（归一化 + 危险删除 deny + deny 规则）
    // ════════════════════════════════════════════════════════════════════════
    private PermissionResult parseFailedFragmentScan(String command, ToolUseContext ctx, Tool tool) {
        ToolPermissionContext permCtx = ctx != null ? ctx.permissionContext() : null;
        String backtickStripped = command.replaceAll("`[\r\n]+\s*", "").replace("`", "");
        for (String fragment : backtickStripped.split("[;|\n\r{}()&]+")) {
            String trimmedFrag = fragment.trim();
            if (trimmedFrag.isEmpty()) continue;
            // 跳过整串（2a 已查原始文本）——除非以赋值/调用操作符前缀开头（CC :790-802）
            if (trimmedFrag.equals(command)
                && !trimmedFrag.matches("^\\$[\\w:].*")
                && !trimmedFrag.matches("^[&.]\\s.*")) {
                continue;
            }
            // 归一化：剥离赋值前缀（$x = $y = iex → iex）+ &/. 调用操作符（CC :815-824）
            String normalized = trimmedFrag;
            String prev;
            do {
                prev = normalized;
                normalized = PS_ASSIGN_PREFIX.matcher(normalized).replaceFirst("");
            } while (!normalized.equals(prev));
            normalized = normalized.replaceFirst("^[&.]\\s+", "");
            String rawFirst = normalized.split("\\s+")[0];
            String firstTok = rawFirst.replaceAll("^['\"]|['\"]$", "");
            String normalizedFrag = firstTok + normalized.substring(rawFirst.length());
            // 危险删除硬 deny（parse 无关，CC :825-840）：仅位置参数，跳过 -Param 前缀
            if ("remove-item".equals(ReadOnlyCommandTable.resolveToCanonical(firstTok))) {
                String[] parts = normalized.split("\\s+");
                for (int i = 1; i < parts.length; i++) {
                    String arg = parts[i];
                    if (arg.isEmpty()) continue;
                    if (isPowerShellDashChar(arg.charAt(0))) continue;
                    if (isDangerousRemovalRawPath(arg)) {
                        if (log.isDebugEnabled()) {
                            log.debug("PowerShellPermissionChain: 危险删除硬 deny path={}", arg);
                        }
                        return deny("Remove-Item on system path '" + arg + "' is blocked. This path is protected from removal.");
                    }
                }
            }
            // deny 规则扫描（对归一化片段，CC :841-852；多词规则 Remove-Item foo:* 仍命中）
            if (permCtx != null && tool != null) {
                PermissionRule denyRule =
                    RuleQuery.getDenyRuleByContentsForTool(permCtx, tool, inputWithCommand(normalizedFrag));
                if (denyRule != null && denyRule.ruleBehavior() == PermissionBehavior.DENY) {
                    return new PermissionResult.Deny(
                        "Permission to use PowerShell with command " + command + " has been denied.",
                        new PermissionDecisionReason.Rule(denyRule), null);
                }
            }
        }
        return null;
    }

    private JsonNode inputWithCommand(String command) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    // ════════════════════════════════════════════════════════════════════════
    // cd 复合守卫 / 符号链接 / 裸仓库 / git-internal / .git 写守卫
    // ════════════════════════════════════════════════════════════════════════
    /**
     * cd/Set-Location/New-PSDrive 类（改变后续路径解析命名空间）· 对齐 CC isCwdChangingCmdlet:1017-1033。
     *
     * <p>G8（ndr/mount）：CC :1023-1031 在 Windows 下另含 {@code ndr}/{@code mount}（New-PSDrive 的 PS 别名，
     * finding #21）——二者不在 COMMON_ALIASES（ReadOnlyCommandTable 无 ndr/mount 条目），须显式判定。
     * <b>POSIX 勿误加</b>（bug #15）：POSIX 的 mount 是 mount(8) 原生命令，误作 PSDrive 创建会假阳性
     * （CC :1027-1031 注释）。安全相关：{@code ndr p /root; Remove-Item p:/passwd} 经 PSDrive 别名绕过
     * cd 复合守卫（cd+write / cd+git / hasCdSubCommand）若不补此门。
     */
    static boolean isCwdChangingCmdlet(String name) {
        String canonical = ReadOnlyCommandTable.resolveToCanonical(name);
        if (canonical.equals("set-location") || canonical.equals("push-location")
                || canonical.equals("pop-location") || canonical.equals("new-psdrive")) {
            return true;
        }
        return detectWindows() && (canonical.equals("ndr") || canonical.equals("mount"));
    }

    /** 当前目录是否裸 git 仓库（无有效 .git 引用时的 HEAD/objects/refs 指示）· 对齐 CC git.ts:876-925 isCurrentDirectoryBareGitRepo。 */
    static boolean isCurrentDirectoryBareGitRepo(ToolUseContext ctx) {
        Path cwd = effectiveCwd(ctx);
        Path gitPath = cwd.resolve(".git");
        try {
            if (Files.isRegularFile(gitPath)) {
                // worktree/submodule — .git 是文件（gitdir 引用），Git 跟随它，不属裸仓库
                return false;
            }
            if (Files.isDirectory(gitPath)) {
                try {
                    if (Files.isRegularFile(gitPath.resolve("HEAD"))) {
                        // 正常仓库 — .git/HEAD 有效，Git 不会回退到 cwd 发现
                        return false;
                    }
                    // .git/HEAD 存在但非普通文件（攻击者用目录占位）— fall through
                } catch (SecurityException e) {
                    // .git 存在但无 HEAD — fall through
                }
            }
        } catch (SecurityException e) {
            // 无 .git — fall through to bare-repo 指示检查
        }
        // 无有效 .git/HEAD。任一裸仓库指示存在即 true（OR 语义，非 AND）· CC :906-924。
        // 每指示独立 try/catch，单指示 IO 错误不掩盖其余。
        try {
            if (Files.isRegularFile(cwd.resolve("HEAD"))) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: 裸仓库指示 HEAD isFile cwd={}", cwd);
                }
                return true;
            }
        } catch (SecurityException e) {
            // no HEAD
        }
        try {
            if (Files.isDirectory(cwd.resolve("objects"))) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: 裸仓库指示 objects/ isDirectory cwd={}", cwd);
                }
                return true;
            }
        } catch (SecurityException e) {
            // no objects/
        }
        try {
            if (Files.isDirectory(cwd.resolve("refs"))) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: 裸仓库指示 refs/ isDirectory cwd={}", cwd);
                }
                return true;
            }
        } catch (SecurityException e) {
            // no refs/
        }
        return false;
    }

    /** 解析有效 cwd（ctx.effectiveCwd 优先，无则会话 cwd / user.dir 兜底）· 对齐 CC getCwd()。
     *  cwd-align-ext：user.dir 兜底 → 会话 cwd（CC pathValidation.ts:1574 checkPathConstraintsForStatement
     *  用 getCwd 做越界基准）；无 sessionId 回落 user.dir（方案 1，零行为变化）。 */
    private static Path effectiveCwd(ToolUseContext ctx) {
        return ctx != null && ctx.effectiveCwd() != null
            ? ctx.effectiveCwd()
            : Path.of(fallbackCwd());
    }

    /**
     * effectiveCwd 缺失时的兜底 cwd · 对齐 CC getCwd()（pathValidation.ts:1574）。
     * 无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private static String fallbackCwd() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    /** 命令是否写入 git-internal 路径（HEAD/objects/refs/hooks/.git）· 对齐 CC gitSafety.ts isGitInternalPathPS。 */
    private boolean writesToGitInternal(PowerShellAstService.ParsedResult parsed,
                                        List<PowerShellSubCommandInfo> allSub, Path cwd) {
        for (PowerShellSubCommandInfo info : allSub) {
            PowerShellAstService.CommandElement c = info.element();
            for (String r : c.redirections()) {
                if (isGitInternalPathPS(r, cwd)) return true;
            }
            String canonical = ReadOnlyCommandTable.resolveToCanonical(c.name());
            if (GIT_SAFETY_WRITE_CMDLETS.contains(canonical)) {
                for (String a : c.args()) {
                    for (String piece : a.split(",")) {
                        if (isGitInternalPathPS(piece, cwd)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean writesToDotGit(PowerShellAstService.ParsedResult parsed,
                                   List<PowerShellSubCommandInfo> allSub, Path cwd) {
        for (PowerShellSubCommandInfo info : allSub) {
            PowerShellAstService.CommandElement c = info.element();
            for (String r : c.redirections()) {
                if (isDotGitPathPS(r, cwd)) return true;
            }
            String canonical = ReadOnlyCommandTable.resolveToCanonical(c.name());
            if (GIT_SAFETY_WRITE_CMDLETS.contains(canonical)) {
                for (String a : c.args()) {
                    for (String piece : a.split(",")) {
                        if (isDotGitPathPS(piece, cwd)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * git-internal 路径判定 · 对齐 CC {@code gitSafety.ts:139-151 isGitInternalPathPS}（cwd 重入全流程）。
     *
     * <p>含 {@code resolveCwdReentry}（去 {@code ../<cwd-basename>/} 重入）与
     * {@code resolveEscapingPathToCwdRelative}（逃逸 cwd 的 {@code ../} 绝对路径重解析落回 cwd）——
     * 这是 bare-repo HEAD 攻击的唯一守卫（CC gitSafety.ts:124-137 注释）。
     */
    static boolean isGitInternalPathPS(String arg, java.nio.file.Path cwd) {
        if (arg == null) return false;
        String n = resolveCwdReentry(normalizeGitPathArg(arg), cwd);
        if (matchesGitInternalPrefix(n)) return true;
        // 逃逸路径（../ 或绝对路径）：重解析落回 cwd 则仍须命中守卫
        if (n.startsWith("../") || n.startsWith("/") || n.matches("^[a-z]:.*")) {
            String rel = resolveEscapingPathToCwdRelative(n, cwd);
            if (rel != null && matchesGitInternalPrefix(rel)) return true;
        }
        return false;
    }

    static boolean isDotGitPathPS(String arg, java.nio.file.Path cwd) {
        if (arg == null) return false;
        String n = resolveCwdReentry(normalizeGitPathArg(arg), cwd);
        if (matchesDotGitPrefix(n)) return true;
        if (n.startsWith("../") || n.startsWith("/") || n.matches("^[a-z]:.*")) {
            String rel = resolveEscapingPathToCwdRelative(n, cwd);
            if (rel != null && matchesDotGitPrefix(rel)) return true;
        }
        return false;
    }

    private static boolean matchesGitInternalPrefix(String n) {
        if (n.equals("head") || n.equals(".git")) return true;
        if (n.startsWith(".git/") || n.matches("git~\\d+($|/)")) return true;
        for (String p : new String[]{"objects", "refs", "hooks"}) {
            if (n.equals(p) || n.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static boolean matchesDotGitPrefix(String n) {
        return n.equals(".git") || n.startsWith(".git/") || n.matches("git~\\d+($|/)");
    }

    /**
     * 归一化 PS 路径参数 · 对齐 CC {@code gitSafety.ts:28-64 normalizeGitPathArg}。
     * 顺序：去 colon 参数前缀（dash 字符 / /Path:）→ 去引号/反引号 → 去 FileSystem:: provider 前缀
     * → 去 drive-relative C:foo → \\→/ → Win32 逐组件去尾空格/点 → posix.normalize → 去 ./ → 小写。
     */
    static String normalizeGitPathArg(String arg) {
        String s = arg;
        if (s.length() > 0 && (isPowerShellDashChar(s.charAt(0)) || s.charAt(0) == '/')) {
            int c = s.indexOf(':', 1);
            if (c > 0) s = s.substring(c + 1);
        }
        s = s.replaceAll("^['\"]|['\"]$", "").replace("`", "");
        s = s.replaceAll("^(?:[A-Za-z0-9_.]+\\\\){0,3}FileSystem::", "");
        s = s.replaceAll("^[A-Za-z]:(?!/|\\\\)", "");
        s = s.replace('\\', '/');
        // Win32 per-component: 去尾空格再尾点（. 与 .. 保留），空组件（绝对路径标记）保留
        StringBuilder norm = new StringBuilder();
        for (String comp : s.split("/", -1)) {
            if (comp.isEmpty()) {
                norm.append('/');
                continue;
            }
            String c = comp;
            String prev;
            do {
                prev = c;
                c = c.replaceAll(" +$", "");
                if (c.equals(".") || c.equals("..")) break;
                c = c.replaceAll("\\.+$", "");
            } while (!c.equals(prev));
            norm.append(c.isEmpty() ? "." : c).append('/');
        }
        if (norm.length() > 0) norm.setLength(norm.length() - 1);
        s = posixNormalize(norm.toString());
        if (s.startsWith("./")) s = s.substring(2);
        return s.toLowerCase();
    }

    /** posix.normalize 等价（解析 . 与 ..，叠合 //），保留前导 /。 */
    static String posixNormalize(String s) {
        if (s == null || s.isEmpty()) return ".";
        boolean absolute = s.startsWith("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty() && !stack.peek().equals("..")) {
                    stack.pop();
                } else if (!absolute) {
                    stack.push("..");
                }
                continue;
            }
            stack.push(part);
        }
        StringBuilder sb = new StringBuilder();
        if (absolute) sb.append('/');
        java.util.List<String> reversed = new java.util.ArrayList<>(stack);
        java.util.Collections.reverse(reversed);
        for (int i = 0; i < reversed.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(reversed.get(i));
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    /**
     * 去 {@code ../<cwd-basename>/} 重入前缀 · 对齐 CC {@code gitSafety.ts:8-27 resolveCwdReentry}。
     * {@code ../project/hooks} 在 PS 中相对 cwd 解析回 {@code hooks}。
     */
    static String resolveCwdReentry(String normalized, java.nio.file.Path cwd) {
        if (normalized == null || !normalized.startsWith("../")) return normalized;
        String cwdBase = cwd == null ? "" : cwd.getFileName().toString().toLowerCase();
        if (cwdBase.isEmpty()) return normalized;
        String prefix = "../" + cwdBase + "/";
        String s = normalized;
        while (s.startsWith(prefix)) {
            s = s.substring(prefix.length());
        }
        if (s.equals("../" + cwdBase)) return ".";
        return s;
    }

    /**
     * 逃逸路径重解析落回 cwd · 对齐 CC {@code gitSafety.ts:100-123 resolveEscapingPathToCwdRelative}。
     * {@code ..\<cwd>\HEAD} / {@code C:\<full-cwd>\HEAD} posix.normalize 无法解析，须按真实 cwd 重解析；
     * 落回 cwd 内则返回 cwd 相对余段，否则 null（真外部路径交给 path-validation）。
     */
    static String resolveEscapingPathToCwdRelative(String n, java.nio.file.Path cwd) {
        java.nio.file.Path abs = cwd.resolve(n).normalize().toAbsolutePath();
        String absLower = abs.toString().toLowerCase();
        String cwdStr = cwd.toAbsolutePath().normalize().toString();
        String cwdLower = cwdStr.toLowerCase();
        String cwdWithSepLower = cwdStr.endsWith("/") || cwdStr.endsWith("\\")
            ? cwdLower : cwdLower + java.io.File.separator.toLowerCase();
        if (absLower.equals(cwdLower)) return ".";
        if (!absLower.startsWith(cwdWithSepLower)) return null;
        String remainder = abs.toString().substring(cwdStr.length());
        remainder = remainder.replace('\\', '/');
        while (remainder.startsWith("/")) remainder = remainder.substring(1);
        return remainder.toLowerCase();
    }

    // ════════════════════════════════════════════════════════════════════════
    // provider / UNC 逐参数扫描 · 对齐 CC :973-1041
    // ════════════════════════════════════════════════════════════════════════
    private static final Pattern NON_FS_PROVIDER_PATTERN =
        Pattern.compile("^(?:[\\w.]+\\\\)?(env|hklm|hkcu|function|alias|variable|cert|wsman|registry)::?",
            Pattern.CASE_INSENSITIVE);

    private PermissionResult providerOrUncScan(PowerShellAstService.ParsedResult parsed) {
        for (PowerShellAstService.Statement st : parsed.statements()) {
            for (PowerShellAstService.CommandElement c : st.commands()) {
                PermissionResult r = providerOrUncForArgs(c.args());
                if (r != null) return r;
            }
            for (PowerShellAstService.CommandElement c : st.nestedCommands()) {
                PermissionResult r = providerOrUncForArgs(c.args());
                if (r != null) return r;
            }
        }
        return null;
    }

    private PermissionResult providerOrUncForArgs(List<String> args) {
        for (String arg : args) {
            String value = arg;
            if (!value.isEmpty() && isPowerShellDashChar(value.charAt(0))) {
                int colonIdx = value.indexOf(':', 1);
                if (colonIdx > 0) {
                    value = value.substring(colonIdx + 1);
                }
            }
            value = value.replace("`", "");
            if (NON_FS_PROVIDER_PATTERN.matcher(value).find()) {
                return ask("命令参数 '" + arg + "' 使用非文件系统 provider 路径，需审批", null);
            }
            if (containsVulnerableUncPath(value)) {
                return ask("命令参数 '" + arg + "' 包含 UNC 路径，可能触发网络请求", null);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // per-subcommand deny/ask 规则 · 对齐 CC :1043-1107（raw + canonical 双查）
    // ════════════════════════════════════════════════════════════════════════
    private List<PermissionResult> subCommandRules(PowerShellAstService.ParsedResult parsed,
                                                   JsonNode input, ToolUseContext ctx, Tool tool,
                                                   String command) {
        List<PermissionResult> out = new ArrayList<>();
        ToolPermissionContext permCtx = ctx != null ? ctx.permissionContext() : null;
        if (permCtx == null || tool == null) {
            return out;
        }
        for (PowerShellSubCommandInfo info : astService.subCommandsForPermissionCheck(parsed, command)) {
            String subCmd = info.text();
            PowerShellAstService.CommandElement element = info.element();
            // canonicalSubCmd = [element.name, ...element.args].join(' ')（CC :1064-1065）。
            // element.name 已在 parser 层去引号 + stripModulePrefix，所以 `& 'Remove-Item' ./x` /
            // `Microsoft.PowerShell.Management\Remove-Item ./x` 的 canonical 命中 deny 规则。
            String canonicalSubCmd = element.name() != null && !element.name().isEmpty()
                ? element.name() + " " + String.join(" ", element.args())
                : null;
            // raw 先查 deny/ask（CC :1067-1071）
            PermissionRule denyRule = matchingRule(permCtx, tool, PermissionBehavior.DENY, subCmd);
            PermissionRule askRule = matchingRule(permCtx, tool, PermissionBehavior.ASK, subCmd);
            // raw deny 未命中且 canonical 存在 → canonical 再查（CC :1073-1086）。
            // canonical ask 仅当 raw ask 也未命中时补位。
            if (denyRule == null && canonicalSubCmd != null) {
                denyRule = matchingRule(permCtx, tool, PermissionBehavior.DENY, canonicalSubCmd);
                if (askRule == null) {
                    askRule = matchingRule(permCtx, tool, PermissionBehavior.ASK, canonicalSubCmd);
                }
            }
            if (denyRule != null) {
                out.add(new PermissionResult.Deny(
                    "Permission to use PowerShell with command " + command + " has been denied.",
                    new PermissionDecisionReason.Rule(denyRule), null));
            } else if (askRule != null) {
                out.add(ask("Permission to use PowerShell with command requires approval", input));
            }
        }
        return out;
    }

    /**
     * 按行为查规则桶中第一个匹配 subCmd 的规则 · 对齐 CC {@code matchingRulesForInput}
     * （powershellPermissions.ts:338-380）+ {@code filterRulesByContentsMatchingInput}
     * （:170-333）的前缀匹配语义（:182-183 strStartsWith 大小写不敏感 + :207-215 canonical
     * 归一化：别名解析 + 空白归一）。deny 桶 / ask 桶分别独立查询（CC 逐桶 matchingRulesForInput）。
     */
    private PermissionRule matchingRule(ToolPermissionContext permCtx, Tool tool,
                                        PermissionBehavior behavior, String command) {
        if (permCtx == null || command == null) {
            return null;
        }
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> bucket =
            switch (behavior) {
                case DENY -> permCtx.alwaysDenyRules();
                case ASK -> permCtx.alwaysAskRules();
                case ALLOW -> permCtx.alwaysAllowRules();
                default -> null;
            };
        if (bucket == null) {
            return null;
        }
        // CC :189-194 stripModulePrefixForRule：deny/ask 对 rule 名 strip（fail-safe 过度匹配），
        // allow 不 strip（防 fail-open）。
        boolean stripRuleModule = behavior != PermissionBehavior.ALLOW;
        for (java.util.Set<PermissionRule> rules : bucket.values()) {
            for (PermissionRule rule : rules) {
                if (rule.ruleBehavior() != behavior) continue;
                if (rule.ruleValue() == null || rule.ruleValue().ruleContent() == null) continue;
                if (rule.ruleValue().toolName() == null
                    || !rule.ruleValue().toolName().equals(tool.name())) continue;
                if (psRuleMatches(rule.ruleValue().ruleContent(), command, stripRuleModule)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * PS 前缀/通配/精确规则匹配 · 对齐 CC {@code filterRulesByContentsMatchingInput}（:170-333）。
     *
     * <p>三型分派（CC shellRuleMatching.ts:159-184 parsePermissionRule 同序）：
     * {@code :*} 结尾 → 前缀；含未转义通配 → wildcard（matchWildcardPattern + canonical 交叉，
     * 封 {@code rm *} 不拦 {@code Remove-Item secret.txt}）；否则 → 精确 equalsIgnoreCase。
     * 前缀与 wildcard 分支均对首词做 canonical 互解（resolveToCanonical + stripModulePrefix，:260-328）——
     * 封 {@code rm\t./x}（非空格空白）与 {@code Module\Remove-Item}（模块限定）两种绕过。
     *
     * @param stripRuleModule 是否对 rule 名 strip 模块前缀（deny/ask=true；allow=false 防 fail-open）
     */
    private boolean psRuleMatches(String ruleContent, String command, boolean stripRuleModule) {
        if (ruleContent == null || command == null) return false;
        String cmd = command.trim();
        // raw 前缀匹配（大小写不敏感）· CC :182-183 strStartsWith（literal space 分隔）
        if (ruleContent.endsWith(":*")) {
            String prefix = ruleContent.substring(0, ruleContent.length() - 2);
            if (cmd.equalsIgnoreCase(prefix)
                || cmd.toLowerCase().startsWith(prefix.toLowerCase() + " ")) {
                return true;
            }
            // canonical 互解 · CC :284-306。ruleCanonical === inputCanonical 时用 inputCanonical
            // 作 base 重建 canonicalPrefix（ruleRest）/canonicalCommand（cmdRest），空白归一为 ' '。
            String rawRuleName = prefix.split("\\s+")[0];
            String inputCmdName = cmd.split("\\s+")[0];
            String ruleName = stripRuleModule ? stripModulePrefix(rawRuleName) : rawRuleName;
            String ruleCanonical = ReadOnlyCommandTable.resolveToCanonical(ruleName);
            String inputCanonical = ReadOnlyCommandTable.resolveToCanonical(stripModulePrefix(inputCmdName));
            if (!ruleCanonical.equals(inputCanonical)) return false;
            String ruleRest = prefix.substring(rawRuleName.length()).replaceFirst("^\\s+", " ");
            String cmdRest = cmd.substring(inputCmdName.length()).replaceFirst("^\\s+", " ");
            String canonicalPrefix = inputCanonical + ruleRest;
            String canonicalCommand = inputCanonical + cmdRest;
            return canonicalCommand.equals(canonicalPrefix)
                || canonicalCommand.startsWith(canonicalPrefix + " ");
        }
        // wildcard 分支 · CC :239-243（raw matchWildcardPattern 大小写不敏感）+ :307-328（canonical 交叉）
        if (hasWildcards(ruleContent)) {
            if (matchWildcardPattern(ruleContent, cmd, true)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: wildcard 规则命中 rule={} command={}", ruleContent, cmd);
                }
                return true;
            }
            // canonical 交叉 · CC :307-328。ruleCanonical === inputCanonical 时用 inputCanonical
            // 作 base 重建 canonicalPattern（ruleRest）/canonicalCommand（cmdRest），空白归一为 ' '。
            String rawRuleCmdName = ruleContent.split("\\s+")[0];
            String inputCmdName = cmd.split("\\s+")[0];
            if (rawRuleCmdName.isEmpty() || inputCmdName.isEmpty()) return false;
            String ruleName = stripRuleModule ? stripModulePrefix(rawRuleCmdName) : rawRuleCmdName;
            String ruleCanonical = ReadOnlyCommandTable.resolveToCanonical(ruleName);
            String inputCanonical = ReadOnlyCommandTable.resolveToCanonical(stripModulePrefix(inputCmdName));
            if (!ruleCanonical.equals(inputCanonical)) return false;
            String ruleRest = ruleContent.substring(rawRuleCmdName.length()).replaceFirst("^\\s+", " ");
            String cmdRest = cmd.substring(inputCmdName.length()).replaceFirst("^\\s+", " ");
            String canonicalPattern = inputCanonical + ruleRest;
            String canonicalCommand = inputCanonical + cmdRest;
            if (matchWildcardPattern(canonicalPattern, canonicalCommand, true)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: wildcard canonical 交叉命中 rule={} command={}", ruleContent, cmd);
                }
                return true;
            }
            return false;
        }
        // 精确匹配（无 :* 前缀、无通配）· CC :225-226 exact strEquals + :252-283 canonical 交叉
        // （OPD-PS-01）。三层：raw equals → canonicalCommand equals → exact canonical 交叉
        // （ruleCanonical === inputCanonical && ruleRest === inputRest）。
        // 封 'deny rm foo' 拦 'Remove-Item foo'（rule-side canonical 解析）与
        // 'deny Remove-Item foo' 拦 'rm foo'（input-side canonical）。
        if (cmd.equalsIgnoreCase(ruleContent)) {
            return true;
        }
        String rawCmdName = cmd.split("\\s+")[0];
        if (rawCmdName.isEmpty()) {
            return false;
        }
        // canonicalCommand = inputCanonical + rest（空白归一为 ' '）· CC :201-215。
        String inputCanonical = ReadOnlyCommandTable.resolveToCanonical(stripModulePrefix(rawCmdName));
        String rest = cmd.substring(rawCmdName.length()).replaceFirst("^\\s+", " ");
        String canonicalCommand = inputCanonical + rest;
        // matchesCommand(canonicalCommand)：rule.command 与 canonical 化 command 相等
        //（'deny Remove-Item foo' 拦 'rm foo'）· CC :254-256。
        if (canonicalCommand.equalsIgnoreCase(ruleContent)) {
            return true;
        }
        // exact canonical 交叉 · CC :265-283。ruleCanonical === inputCanonical && ruleRest === inputRest。
        // SECURITY: rule 名 strip（deny/ask=true；allow=false 防 fail-open）· :260-264 stripModulePrefixForRule。
        String rawRuleCmdName = ruleContent.split("\\s+")[0];
        if (rawRuleCmdName.isEmpty()) {
            return false;
        }
        String ruleName = stripRuleModule ? stripModulePrefix(rawRuleCmdName) : rawRuleCmdName;
        String ruleCanonical = ReadOnlyCommandTable.resolveToCanonical(ruleName);
        if (ruleCanonical.equals(inputCanonical)) {
            String ruleRest = ruleContent.substring(rawRuleCmdName.length()).replaceFirst("^\\s+", " ");
            if (ruleRest.equalsIgnoreCase(rest)) {
                if (log.isDebugEnabled()) {
                    log.debug("PowerShellPermissionChain: exact canonical 交叉命中 rule={} command={}",
                        ruleContent, cmd);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * wildcard 模式匹配 · 对齐 CC shellRuleMatching.ts:90-154 matchWildcardPattern。
     *
     * <p>流程全量镜像：trim → {@code \*} 与 {@code \\} 转义占位 → 转义 regex 特殊字符（除 *）→
     * 未转义 * → {@code .*} → 占位还原为 {@code \*} / {@code \\} → 尾随 {@code ' *'} 且唯一未转义星
     * 时改 {@code ( .*)?}（{@code git *} 同时匹配裸 {@code git}，对齐 prefix 语义）→ {@code ^...\z}
     * 收尾（\z 避免 Java {@code $} 匹配末尾换行）+ DOTALL + 可选 CASE_INSENSITIVE。
     */
    static boolean matchWildcardPattern(String pattern, String command, boolean caseInsensitive) {
        if (pattern == null || command == null) return false;
        String trimmedPattern = pattern.trim();
        // 处理转义序列：\* 与 \\ → 占位
        StringBuilder processed = new StringBuilder(trimmedPattern.length());
        for (int i = 0; i < trimmedPattern.length(); ) {
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
        String p = processed.toString();
        // 转义 regex 特殊字符（除 *）· CC :126 [.+?^${}()|[\]\\'"]
        String escaped = p.replaceAll("([.+?^${}()|\\[\\]\\\\'\"])", "\\\\$1");
        // 未转义 * → .*
        String withWildcards = escaped.replace("*", ".*");
        // 占位还原为字面 \* 与 \\（regex 字面）
        String regexPattern = withWildcards
            .replace(ESCAPED_STAR_PLACEHOLDER, "\\*")
            .replace(ESCAPED_BACKSLASH_PLACEHOLDER, "\\\\");
        // 尾随 ' *' 且唯一未转义星 → '( .*)?'；多星模式（如 '* run *'）不转，防误拦 'npm run' · CC :136-145
        int unescapedStarCount = countChar(p, '*');
        if (regexPattern.endsWith(" .*") && unescapedStarCount == 1) {
            regexPattern = regexPattern.substring(0, regexPattern.length() - 3) + "( .*)?";
        }
        int flags = Pattern.DOTALL | (caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        return Pattern.compile("^" + regexPattern + "\\z", flags).matcher(command).matches();
    }

    /** 模式是否含未转义通配（'*' 前偶数个反斜杠含 0；':*' 结尾不算通配）· 对齐 CC shellRuleMatching.ts:54-78 hasWildcards。 */
    static boolean hasWildcards(String pattern) {
        if (pattern == null) return false;
        if (pattern.endsWith(":*")) return false;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                int backslashCount = 0;
                for (int j = i - 1; j >= 0 && pattern.charAt(j) == '\\'; j--) {
                    backslashCount++;
                }
                if (backslashCount % 2 == 0) return true;
            }
        }
        return false;
    }

    /** 统计字符串中字符出现次数。 */
    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    /** stripModulePrefix（module 限定名取末段）· 对齐 CC parser.ts:814-826 stripModulePrefix。 */
    private static String stripModulePrefix(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('\\');
        if (idx < 0) return name;
        if (name.matches("^[A-Za-z]:.*") || name.startsWith("\\\\")
            || name.startsWith(".\\") || name.startsWith("..\\")) {
            return name;
        }
        return name.substring(idx + 1);
    }

    /** classifyCommandName · 对齐 CC parser.ts:800-810。 */
    static String classifyCommandName(String name) {
        if (name == null) return "unknown";
        if (name.matches("^[A-Za-z]+-[A-Za-z][A-Za-z0-9_]*$")) return "cmdlet";
        if (name.matches(".*[.\\\\/].*")) return "application";
        return "unknown";
    }

    /** 命令首词 · 对齐 CC command.split(/\s+/)[0]。 */
    static String firstToken(String command) {
        if (command == null) return "";
        String[] parts = command.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    /**
     * exact allow 规则查询 · 对齐 CC {@code powershellToolCheckExactMatchPermission}
     * （:385-430）的 allow 分支：exact 模式（无 :* 前缀规则参与），raw 精确相等或 canonical
     * 互解（first token stripModulePrefix + resolveToCanonical 相等且 rest 相等，:265-283）。
     */
    private PermissionRule exactAllowRule(ToolPermissionContext permCtx, Tool tool, String command) {
        if (permCtx == null || command == null) return null;
        String trimmed = command.trim();
        for (java.util.Set<PermissionRule> rules : permCtx.alwaysAllowRules().values()) {
            for (PermissionRule rule : rules) {
                if (rule.ruleValue() == null || rule.ruleValue().ruleContent() == null) continue;
                if (rule.ruleValue().toolName() == null
                    || !rule.ruleValue().toolName().equals(tool.name())) continue;
                String content = rule.ruleValue().ruleContent();
                if (content.endsWith(":*")) continue; // 前缀规则不参与 exact
                if (hasWildcards(content)) continue; // 通配规则不参与 exact（CC :240-242 exact 模式 wildcard 返回 false）
                if (trimmed.equalsIgnoreCase(content)) return rule;
                // canonical exact · CC :265-283（exact 分支 ruleCanonical === inputCanonical && rest 相等）。
                // allow 规则不 strip rule 名模块前缀（CC stripModulePrefixForRule allow 不 strip，防 fail-open）
                String ruleFirst = firstToken(content);
                String cmdFirst = firstToken(trimmed);
                if (ruleFirst.isEmpty() || cmdFirst.isEmpty()) continue;
                String ruleCanonical = ReadOnlyCommandTable.resolveToCanonical(ruleFirst);
                String inputCanonical = ReadOnlyCommandTable.resolveToCanonical(stripModulePrefix(cmdFirst));
                if (!ruleCanonical.equals(inputCanonical)) continue;
                String ruleRest = content.substring(ruleFirst.length()).replaceFirst("^\\s+", " ");
                String cmdRest = trimmed.substring(cmdFirst.length()).replaceFirst("^\\s+", " ");
                if (ruleRest.equalsIgnoreCase(cmdRest)) return rule;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 只读命令判定 · 对齐 CC readOnlyValidation.ts:1168-1305 isReadOnlyCommand
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 同步契约 isReadOnlyCommand 无 AST 重载 · 对齐 CC readOnlyValidation.ts:1168-1177。
     *
     * <p>CC {@code isReadOnlyCommand(command, parsed?)} 在缺省 {@code parsed}（同步 isReadOnly 路径
     * 无 AST）时走 `if (!parsed) return false` 保守分支，恒 false。本重载镜像该语义——<b>非 stub</b>，
     * CC 同步契约本就恒 false（无法在不解析 AST 的情况下判定只读，而解析需真启 pwsh 30s 阻塞）。
     *
     * <p>真实只读 auto-allow 走 {@link #isReadOnlyCommand(String, PowerShellAstService.ParsedResult)}
     * （本类 {@code check()}:230 生产调用，异步 AST 版已闭环），不重复实现。唯一消费方 =
     * {@code PowerShellTool.isReadOnly}（CC PowerShellTool.tsx:300-315 同步契约前置 hasSyncSecurityConcerns）。
     *
     * @param command 原始 PowerShell 命令字符串
     * @return 恒 false（无 AST → 保守 false，CC readOnlyValidation.ts:1174-1177）
     */
    public boolean isReadOnlyCommand(String command) {
        return false;
    }

    boolean isReadOnlyCommand(String command, PowerShellAstService.ParsedResult parsed) {
        if (!parsed.valid()) return false;
        if (parsed.hasScriptBlocks() || parsed.hasSubExpressions() || parsed.hasExpandableStrings()
            || parsed.hasSplatting() || parsed.hasMemberInvocations() || parsed.hasAssignments()
            || parsed.hasStopParsing()) {
            return false;
        }
        int totalCommands = 0;
        for (PowerShellAstService.Statement st : parsed.statements()) {
            totalCommands += st.commands().size();
        }
        boolean hasCd = totalCommands > 1 && parsed.statements().stream()
            .flatMap(st -> st.commands().stream())
            .anyMatch(c -> isCwdChangingCmdlet(c.name()));
        if (hasCd) {
            return false;
        }
        // D5（G33②）：语句级文件重定向 → 非只读 · 对齐 CC readOnlyValidation.ts:1242-1251
        //   （pipeline.redirections 含 isMerging + isNullRedirectionTarget($null/${null})）。
        //   旧 Java 仅查 commands().get(0).redirections()（命令级）+ 仅判 $null（漏 ${null}）——
        //   语句级 `Get-Content foo > out.txt` 重定向未绑定到首命令 → 漏判文件重定向。本类
        //   getFileRedirections（:457-474）聚合语句级 parsed.redirections() + nestedCommands 级
        //   CommandElement.redirections() 并滤 isMerging + $null/${null} 目标（CC getAllRedirections
        //   parser.ts:1507-1527 + getFileRedirections :1713-1719 等价）。CC isReadOnlyCommand 不查
        //   命令级 redirections，故移除旧首命令命令级检查（对齐 CC）。
        if (!getFileRedirections(parsed).isEmpty()) {
            return false;
        }
        for (PowerShellAstService.Statement st : parsed.statements()) {
            if (st.commands().isEmpty()) return false;
            PowerShellAstService.CommandElement first = st.commands().get(0);
            if (!isAllowlistedCommand(first)) return false;
            for (int i = 1; i < st.commands().size(); i++) {
                PowerShellAstService.CommandElement c = st.commands().get(i);
                if ("application".equals(c.nameType())) return false;
                if ("out-null".equals(ReadOnlyCommandTable.resolveToCanonical(c.name())) && c.args().isEmpty()) {
                    continue;
                }
                if (!isAllowlistedCommand(c)) return false;
            }
            if (!st.nestedCommands().isEmpty()) return false;
        }
        return true;
    }

    /** 单个命令元素 allowlist 校验 · 对齐 CC readOnlyValidation.ts:1310-1516 isAllowlistedCommand。 */
    boolean isAllowlistedCommand(PowerShellAstService.CommandElement c) {
        if ("application".equals(c.nameType())) {
            String rawFirst = c.text().split("\s+")[0].toLowerCase();
            if (!rawFirst.equals("where.exe")) {
                return false;
            }
        }
        ReadOnlyCommandTable.CmdletConfig config = ReadOnlyCommandTable.lookupPsCmdlet(c.name());
        if (config == null) {
            return false;
        }
        // 对齐 CC readOnlyValidation.ts:1346：附加危险回调在 flag 校验前 invoke，为真即 return false。
        // ipconfig/hostname/route 位置参数拒绝挂在此回调（CC :705-712 / :749-755 / :777-791），
        // 缺此 invoke 则位置参数在 flag 循环中穿透、被只读自动放行（假接线）。
        if (config.callback() != null && config.callback().isDangerous(c.name(), c.args())) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: 附加危险回调判危 name={} args={}", c.name(), c.args());
            }
            return false;
        }
        String canonical = ReadOnlyCommandTable.resolveToCanonical(c.name());
        if (canonical.equals("git") || canonical.equals("gh") || canonical.equals("docker") || canonical.equals("dotnet")) {
            return isExternalCommandSafe(canonical, c.args());
        }
        // cmdlet 判定（canonical 含 Verb-Noun 的 '-'）· 对齐 CC readOnlyValidation.ts:1445
        // （isCmdlet = canonical.includes('-')）。外部命令 git/gh/docker/dotnet 已在上方早返回，
        // native exe 无 [CmdletBinding()] 公共参数，isCmdlet=false。
        boolean isCmdlet = canonical.contains("-");
        if (config.argLeaksValue()) {
            for (String a : c.args()) {
                if (a.contains("$")) return false;
            }
        }
        List<String> elementTypes = c.elementTypes();
        if (elementTypes.size() < c.args().size() + 1) {
            return false;
        }
        for (int i = 1; i < elementTypes.size(); i++) {
            String t = elementTypes.get(i);
            if (!t.equals("StringConstant") && !t.equals("Parameter")) {
                String arg = c.args().size() >= i ? c.args().get(i - 1) : "";
                if (arg.matches(".*[$(@{\\[].*")) {
                    return false;
                }
            }
            // P0-2/C76（EV-C2-021，CC readOnlyValidation.ts:1409-1424）：colon-bound 参数
            // （`-Flag:$env:SECRET`）是单个 CommandParameterAst —— 其 .Argument 是 children 子节点
            // 而非独立 CommandElement，故 elementTypes 显示 'Parameter' 被上面白名单放过 → 净自动放行
            // （secret 泄漏向量，`Get-Process -Name:$env:SECRET` 链上无任何 ask）。
            // 对齐 CC：查询 parser children[] 树（children[i-1] 与 args[i-1] 对齐），任一子节点类型
            // 非 StringConstant 即 return false（fail-closed，`-InputObject:@{k=v}`/`-Name:('x' > f)`
            // 等树上形态也命中）；children 缺失（旧 parser/测试桩）时回退字符串考古：冒号后含
            // `$( @ { [` 元字符即拒绝（CC :1417-1424 fallback）。
            if (t.equals("Parameter")) {
                List<PowerShellAstService.CommandElementChild> paramChildren =
                    c.children() != null && c.children().size() >= i
                        ? c.children().get(i - 1) : null;
                if (paramChildren != null) {
                    if (paramChildren.stream().anyMatch(ch -> !"StringConstant".equals(ch.type()))) {
                        if (log.isDebugEnabled()) {
                            log.debug("PowerShellPermissionChain: colon-bound 参数子节点非 StringConstant 拒绝 "
                                + "name={} arg={} children={}", c.name(),
                                c.args().size() >= i ? c.args().get(i - 1) : "", paramChildren);
                        }
                        return false;
                    }
                } else {
                    String arg = c.args().size() >= i ? c.args().get(i - 1) : "";
                    int colonIdx = arg.indexOf(':');
                    if (colonIdx > 0 && arg.substring(colonIdx + 1).matches(".*[$(@{\\[].*")) {
                        if (log.isDebugEnabled()) {
                            log.debug("PowerShellPermissionChain: colon-bound 参数元字符回退拒绝 "
                                + "name={} arg={}", c.name(), arg);
                        }
                        return false;
                    }
                }
            }
        }
        if (config.allowAllFlags()) {
            return true;
        }
        for (int i = 0; i < c.args().size(); i++) {
            String arg = c.args().get(i);
            if (arg.isEmpty()) continue;
            // 对齐 CC readOnlyValidation.ts:1481-1484：cmdlet 用 dash 前缀；native exe 用 ASCII '-' 或
            // win32 '/' 前缀（argv 约定，parser 把 /S 当位置参数而非 CommandParameterAst）。
            boolean isFlag = isCmdlet
                ? isPowerShellDashChar(arg.charAt(0))
                : (arg.startsWith("-") || (isWindows && arg.startsWith("/")));
            if (isFlag && !isCmdlet && log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: native exe flag 检测 name={} arg={} win32={}",
                    c.name(), arg, isWindows);
            }
            if (isFlag) {
                // 对齐 CC :1489：native exe safeFlags 以 / 存储（如 /all），不前置 '-'。
                String paramName = isCmdlet ? "-" + arg.substring(1) : arg;
                int colonIndex = paramName.indexOf(':');
                if (colonIndex > 0) paramName = paramName.substring(0, colonIndex);
                // 大小写不敏感比较（CC readOnlyValidation.ts:1506-1508 safeFlags some()）
                String pLower = paramName.toLowerCase();
                // 公共参数 continue 跳过 · 对齐 CC readOnlyValidation.ts:1502-1504。经
                // [CmdletBinding()] 每个 cmdlet 都接受 -ErrorAction/-Verbose/-Debug 等公共参数，
                // 只路由 error/warning/progress 流，不能让只读 cmdlet 写；无此跳过则
                // Get-Content file.txt -ErrorAction SilentlyContinue 会误 prompt。仅 cmdlet
                // （native exe 无公共参数）。COMMON_PARAMETERS 单源于 ReadOnlyCommandTable（对齐
                // CC commonParameters.ts:12-30）。
                if (isCmdlet && ReadOnlyCommandTable.COMMON_PARAMETERS.contains(pLower)) {
                    if (log.isDebugEnabled()) {
                        log.debug("PowerShellPermissionChain: 公共参数放行 param={} cmdlet={}", pLower, canonical);
                    }
                    continue;
                }
                boolean safe = config.safeFlags().stream()
                    .anyMatch(f -> f.toLowerCase().equals(pLower));
                if (!safe) {
                    return false;
                }
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 外部命令（git/gh/docker/dotnet）只读校验 · 对齐 CC readOnlyValidation.ts:1522-1823
    // ════════════════════════════════════════════════════════════════════════
    private static final java.util.Set<String> DANGEROUS_GIT_GLOBAL_FLAGS = java.util.Set.of(
        "-c", "-C", "--exec-path", "--config-env", "--git-dir", "--work-tree", "--attr-source");
    private static final java.util.Set<String> DOTNET_READ_ONLY_FLAGS = java.util.Set.of(
        "--version", "--info", "--list-runtimes", "--list-sdks");

    /**
     * git 短全局 flag 附着式值（-ccore.pager=sh / -C/path 无空格直接拼接）· 对齐 CC
     * readOnlyValidation.ts:1582 DANGEROUS_GIT_SHORT_FLAGS_ATTACHED。isGitSafe 内做前缀匹配
     * （:1622-1630），阻断 {@code git -ccore.pager=sh log}（RCE）与 {@code git -C-trap status}。
     */
    private static final java.util.List<String> DANGEROUS_GIT_SHORT_FLAGS_ATTACHED = java.util.List.of("-c", "-C");

    /**
     * git 全局 flag 消费独立值 token（无 inline '=' 时 idx+=2，防值被误判为子命令）· 对齐 CC
     * readOnlyValidation.ts:1566-1576 GIT_GLOBAL_FLAGS_WITH_VALUES。本 Java 集仅含非危险 3 条——
     * -c/-C/--exec-path/--config-env/--git-dir/--work-tree 已在 DANGEROUS_GIT_GLOBAL_FLAGS 拒绝，
     * 无需跳值（CC 全集含全部 8 条，Java 保留 CC 全集语义仅裁剪已拒分支）。
     */
    private static final java.util.Set<String> GIT_GLOBAL_FLAGS_WITH_VALUES = java.util.Set.of(
        "--namespace", "--super-prefix", "--shallow-file");

    /** validateFlags flag 形检测 · 对齐 CC readOnlyCommandValidation.ts:1645 FLAG_PATTERN。 */
    private static final Pattern FLAG_PATTERN = Pattern.compile("^-[a-zA-Z0-9_-]");
    /** git -&lt;number&gt; 简写 = -n &lt;number&gt; · CC :1764。 */
    private static final Pattern GIT_NUMERIC_SHORTHAND = Pattern.compile("^-\\d+$");
    /** grep/rg 附着数值 -A20/-B10 的值部分 · CC :1781。 */
    private static final Pattern GREP_ATTACHED_NUMERIC = Pattern.compile("^\\d+$");
    /** git --sort 反向排序值（-refname）· CC :1872。 */
    private static final Pattern GIT_SORT_REVERSE = Pattern.compile("^-[a-zA-Z].*");

    private boolean isExternalCommandSafe(String command, List<String> args) {
        for (String a : args) {
            if (a.contains("$")) {
                return false; // 变量引用在运行时展开 → parser differential
            }
        }
        switch (command) {
            case "git":
                return isGitSafe(args);
            case "gh":
                return isGhSafe(args);
            case "docker":
                return isDockerSafe(args);
            case "dotnet":
                if (args.isEmpty()) return false;
                for (String a : args) {
                    if (!DOTNET_READ_ONLY_FLAGS.contains(a.toLowerCase())) return false;
                }
                return true;
            default:
                return false;
        }
    }

    private boolean isGitSafe(List<String> args) {
        if (args.isEmpty()) return true;
        int idx = 0;
        while (idx < args.size()) {
            String arg = args.get(idx);
            if (arg == null || !arg.startsWith("-")) break;
            // CC :1622-1630 — 附着式短 flag 前缀匹配（git -ccore.pager=sh log / -C/path status → 拒绝）。
            // '-c' 的 arg.charAt(shortFlag.length()) != '-' 守卫：git config key 不以 '-' 开头。
            // '-C' 无该守卫：目录路径可始于 '-'，git -C-trap status 必须拒绝。
            for (String shortFlag : DANGEROUS_GIT_SHORT_FLAGS_ATTACHED) {
                if (arg.length() > shortFlag.length() && arg.startsWith(shortFlag)
                        && ("-C".equals(shortFlag) || arg.charAt(shortFlag.length()) != '-')) {
                    if (log.isDebugEnabled()) {
                        log.debug("PowerShellPermissionChain: git 附着式危险短 flag 拒绝 arg={}", arg);
                    }
                    return false;
                }
            }
            boolean hasInlineValue = arg.contains("=");
            String flagName = hasInlineValue ? arg.split("=")[0] : arg;
            if (DANGEROUS_GIT_GLOBAL_FLAGS.contains(flagName)) return false;
            // CC :1637-1641 — 无 inline '=' 且 flag 消费独立值 → idx+=2（防值被误判为子命令）
            if (!hasInlineValue && GIT_GLOBAL_FLAGS_WITH_VALUES.contains(flagName)) {
                idx += 2;
            } else {
                idx++;
            }
        }
        if (idx >= args.size()) return true;
        String first = args.get(idx).toLowerCase();
        String second = idx + 1 < args.size() ? args.get(idx + 1).toLowerCase() : "";
        ReadOnlyCommandTable.ExternalCommandConfig config = ReadOnlyCommandTable.lookupExternalCommand("git " + first + " " + second);
        int subTokens = 2;
        if (config == null) {
            config = ReadOnlyCommandTable.lookupExternalCommand("git " + first);
            subTokens = 1;
        }
        if (config == null) return false;
        List<String> flagArgs = args.subList(idx + subTokens, args.size());
        // CC :1679-1692 — git ls-remote URL 拒绝（:// http/git 协议、@ SSH、: URL/路径分隔——数据
        // exfil 向量；$ 变量引用已由 isExternalCommandSafe 顶层 blanket 拒绝覆盖）
        if ("ls-remote".equals(first)) {
            for (String arg : flagArgs) {
                if (!arg.startsWith("-")
                        && (arg.contains("://") || arg.contains("@") || arg.contains(":") || arg.contains("$"))) {
                    if (log.isDebugEnabled()) {
                        log.debug("PowerShellPermissionChain: git ls-remote URL 拒绝 arg={}", arg);
                    }
                    return false;
                }
            }
        }
        // CC :1694-1699 — 附加命令危险回调（git reflog/tag/branch/remote/remote show 写能力拦截）
        if (config.callback() != null && config.callback().isDangerous("", flagArgs)) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: git 附加命令回调判危险 subcommand={} args={}", first, flagArgs);
            }
            return false;
        }
        return validateFlags(flagArgs, 0, config, "git", null);
    }

    private boolean isGhSafe(List<String> args) {
        // CC :1703-1707 — gh 网络命令仅 ant 用户放行（process.env.USER_TYPE !== 'ant' → false）。
        // Java 同 MockRateLimits/Vcr 读 System.getenv("USER_TYPE")（fail-closed：非 ant 全部拒绝）
        if (!isAntUser()) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: gh 非 ant 用户拒绝（USER_TYPE={}）", System.getenv("USER_TYPE"));
            }
            return false;
        }
        if (args.isEmpty()) return true;
        ReadOnlyCommandTable.ExternalCommandConfig config = null;
        int subTokens = 0;
        if (args.size() >= 2) {
            config = ReadOnlyCommandTable.lookupExternalCommand("gh " + args.get(0).toLowerCase() + " " + args.get(1).toLowerCase());
            subTokens = 2;
        }
        if (config == null && args.size() >= 1) {
            config = ReadOnlyCommandTable.lookupExternalCommand("gh " + args.get(0).toLowerCase());
            subTokens = 1;
        }
        if (config == null) return false;
        List<String> flagArgs = args.subList(subTokens, args.size());
        // CC :1745-1749 — flagArgs 含 $ → 拒绝（裸变量位置参数运行时展开 → 数据 exfil）
        for (String arg : flagArgs) {
            if (arg.contains("$")) return false;
        }
        // CC :1750-1755 — ghIsDangerousCallback（HOST/OWNER/REPO 三段 exfil 拒绝）
        if (config.callback() != null && config.callback().isDangerous("", flagArgs)) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellPermissionChain: gh 附加命令回调判危险 args={}", flagArgs);
            }
            return false;
        }
        return validateFlags(flagArgs, 0, config, null, null);
    }

    private boolean isDockerSafe(List<String> args) {
        if (args.isEmpty()) return true;
        String oneWord = "docker " + args.get(0).toLowerCase();
        if (ReadOnlyCommandTable.isExternalReadOnlyCommand(oneWord)) {
            return true; // docker ps / docker images 无 flag 约束
        }
        ReadOnlyCommandTable.ExternalCommandConfig config = ReadOnlyCommandTable.lookupExternalCommand(oneWord);
        if (config == null) return false;
        List<String> flagArgs = args.subList(1, args.size());
        // CC :1800-1805 — 附加命令危险回调（docker 当前无回调，保留 1:1 移植）
        if (config.callback() != null && config.callback().isDangerous("", flagArgs)) {
            return false;
        }
        return validateFlags(flagArgs, 0, config, null, null);
    }

    /**
     * flag walker · 对齐 CC {@code validateFlags}（readOnlyCommandValidation.ts:1684-1893 完整语义）。
     *
     * <p>覆盖：(a) xargs 目标命令检测（:1704-1717，Bash 专用——PowerShell 调用侧恒传
     * {@code xargsTargetCommands=null}，1:1 移植保留）；(b) {@code --} 且 respectsDoubleDash!=false →
     * break，否则当位置参数继续（:1719-1731）；(c) {@code --flag=value} 用 hasEquals（非 inlineValue
     * 真值）区分空值 {@code -E=}（:1735-1758）；(d) git {@code -<数字>} 简写 = -n（:1764）；(e) grep/rg
     * 附着数值 {@code -A20/-B10}（截 '-A' 前缀 + {@code \d+} 值，:1771-1794）；(f) 短 flag 捆绑 {@code -nr}：
     * 每个单字符 flag 必须存在且全部 NONE 类型，否则拒绝（防 xargs -rI 解析差，:1796-1830）；(g) string
     * 参数值 '-' 前缀拒绝 + git --sort 反向排序例外（:1863-1879，仅附着式 {@code --sort=-refname} 可达）；
     * (h) validateFlagArgument（number={@code ^\d+$}、char=len1、{} =/{} /、EOF=EOF，:1881-1884）。
     *
     * @param commandName          git/grep/rg 特判（git 数字简写、--sort 反向、grep/rg 附着数值）；
     *                             isGitSafe 传 "git"，isGhSafe/isDockerSafe 传 null
     * @param xargsTargetCommands  Bash-only xargs 目标命令集；PowerShell 调用侧恒传 null
     */
    static boolean validateFlags(List<String> tokens, int startIndex,
                                 ReadOnlyCommandTable.ExternalCommandConfig config,
                                 String commandName, List<String> xargsTargetCommands) {
        int i = startIndex;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token == null || token.isEmpty()) {
                i++;
                continue;
            }
            // CC :1704-1717 — xargs 目标命令检测（Bash 专用，PowerShell 调用侧恒传 null，1:1 移植保留）
            if (xargsTargetCommands != null && "xargs".equals(commandName)
                    && (!token.startsWith("-") || token.equals("--"))) {
                if (token.equals("--") && i + 1 < tokens.size()) {
                    i++;
                    token = tokens.get(i);
                }
                if (token != null && xargsTargetCommands.contains(token)) {
                    break;
                }
                return false;
            }
            // CC :1719-1731 — respectsDoubleDash 默认 true：遇 -- 结束 flag 解析；false（pyright）当位置参数继续
            if (token.equals("--")) {
                if (config.respectsDoubleDash()) {
                    i++;
                    break;
                }
                i++;
                continue;
            }
            // CC :1733 — flag 形：以 '-' 开头、长度 >1、命中 FLAG_PATTERN（-5/-nr/-A20/--sort 均命中）
            if (token.startsWith("-") && token.length() > 1 && FLAG_PATTERN.matcher(token).find()) {
                // CC :1735-1758 — --flag=value 拆分；hasEquals 单独追踪（-E= 空值 vs 无 =）
                boolean hasEquals = token.contains("=");
                int eqIdx = token.indexOf('=');
                String flag = eqIdx == -1 ? token : token.substring(0, eqIdx);
                String inlineValue = eqIdx == -1 ? "" : token.substring(eqIdx + 1);
                if (flag.isEmpty()) {
                    return false;
                }
                ReadOnlyCommandTable.FlagArgType flagArgType = config.safeFlags().get(flag);
                if (flagArgType == null) {
                    // CC :1764-1768 — git -<数字> 简写 = -n <number>（git log -5）
                    if ("git".equals(commandName) && GIT_NUMERIC_SHORTHAND.matcher(flag).matches()) {
                        i++;
                        continue;
                    }
                    // CC :1771-1794 — grep/rg 附着数值 -A20/-B10（截 '-A' 前缀 + \d+ 值）
                    if (("grep".equals(commandName) || "rg".equals(commandName))
                            && flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        String potentialFlag = flag.substring(0, 2);
                        String potentialValue = flag.substring(2);
                        ReadOnlyCommandTable.FlagArgType attachedType = config.safeFlags().get(potentialFlag);
                        if (attachedType != null && GREP_ATTACHED_NUMERIC.matcher(potentialValue).matches()) {
                            if (attachedType == ReadOnlyCommandTable.FlagArgType.NUMBER
                                    || attachedType == ReadOnlyCommandTable.FlagArgType.STRING) {
                                if (validateFlagArgument(potentialValue, attachedType)) {
                                    i++;
                                    continue;
                                }
                                return false; // 附着数值非法（如 -A20x）
                            }
                        }
                    }
                    // CC :1796-1830 — 短 flag 捆绑 -nr：每个单字符 flag 必须存在且全部 NONE 类型
                    //（含参 flag 在捆绑中经 GNU getopt 消费下一 token → parser differential → RCE）
                    if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        for (int j = 1; j < flag.length(); j++) {
                            String singleFlag = "-" + flag.charAt(j);
                            ReadOnlyCommandTable.FlagArgType flagType = config.safeFlags().get(singleFlag);
                            if (flagType == null) {
                                return false; // 捆绑中某单字符 flag 不在 safeFlags
                            }
                            if (flagType != ReadOnlyCommandTable.FlagArgType.NONE) {
                                return false; // 捆绑中某 flag 需参 → 拒绝整捆
                            }
                        }
                        i++;
                        continue;
                    }
                    return false; // CC :1828-1830 — 未知 flag
                }
                // CC :1833-1839 — NONE 类型不能带值（hasEquals 覆盖 -FLAG= 空值）
                if (flagArgType == ReadOnlyCommandTable.FlagArgType.NONE) {
                    if (hasEquals) {
                        return false;
                    }
                    i++;
                    continue;
                }
                // CC :1840-1861 — 带参 flag
                String argValue;
                if (hasEquals) {
                    argValue = inlineValue;
                    i++;
                } else {
                    // 下一 token 是 flag 形（-x）→ 缺少必选参数（GNU getopt 对必选参 flag 无条件消费）
                    if (i + 1 >= tokens.size()
                            || (tokens.get(i + 1) != null && tokens.get(i + 1).startsWith("-")
                                && tokens.get(i + 1).length() > 1
                                && FLAG_PATTERN.matcher(tokens.get(i + 1)).find())) {
                        return false;
                    }
                    argValue = tokens.get(i + 1);
                    i += 2;
                }
                // CC :1863-1879 — string 参数 '-' 前缀拒绝 + git --sort 反向排序例外（仅附着式可达：
                // detached 形态下 '-' 前缀值会在上一步被误判为缺失参数而先拒绝）
                if (flagArgType == ReadOnlyCommandTable.FlagArgType.STRING && argValue != null
                        && argValue.startsWith("-")) {
                    if ("--sort".equals(flag) && "git".equals(commandName)
                            && GIT_SORT_REVERSE.matcher(argValue).matches()) {
                        // 反向排序（-refname / -version:refname）放行
                    } else {
                        return false;
                    }
                }
                // CC :1881-1884 — 按类型校验参数值
                if (!validateFlagArgument(argValue, flagArgType)) {
                    return false;
                }
            } else {
                // CC :1886-1889 — 非 flag token → 位置参数，放行
                i++;
            }
        }
        return true;
    }

    /** flag 参数值校验 · 对齐 CC validateFlagArgument（readOnlyCommandValidation.ts:1650-1670）。 */
    private static boolean validateFlagArgument(String value, ReadOnlyCommandTable.FlagArgType type) {
        if (value == null) return false;
        return switch (type) {
            case NUMBER -> value.matches("\\d+");
            case STRING -> true; // 任何串（含空）合法；'-' 前缀由 validateFlags :1867 单独拒绝
            case CHAR -> value.length() == 1;
            case LITERAL_EMPTY_BRACES -> value.equals("{}");
            case LITERAL_EOF -> value.equals("EOF");
            case NONE -> false; // NONE 类型不应进入此校验（validateFlags 已单独处理）
        };
    }

    /**
     * 同步正则式安全顾虑预检 · 对齐 CC {@code hasSyncSecurityConcerns}
     * （PowerShellTool/readOnlyValidation.ts:1112-1159，isReadOnly 同步前置正则）。
     *
     * <p>移植自被删 PowerShellReadOnlyValidation.java:581-600 已验实现（git HEAD）；负向后瞻
     * {@code (?<!:)//} 以 {@code (^|[^:])//} 两段匹配替代（CC 注释 :1149 亦用同类替代）。返回 true
     * 表示命令含应阻断只读放行的模式。
     */
    public static boolean hasSyncSecurityConcerns(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        // ① 子表达式 $(...) 可执行任意代码 · CC :1119
        if (trimmed.contains("$(")) return true;
        // ② splatting @variable 传任意参数 · CC :1127（[^\w.] 排除 user@example.com / file.@{u}）
        if (Pattern.compile("(?:^|[^\\w.])@\\w+").matcher(trimmed).find()) return true;
        // ③ 成员调用 .Method() 可调任意 .NET 方法 · CC :1132
        if (Pattern.compile("\\.\\w+\\s*\\(").matcher(trimmed).find()) return true;
        // ④ 赋值 $var = ... 可改状态 · CC :1137
        if (Pattern.compile("\\$\\w+\\s*[+\\-*/]?=").matcher(trimmed).find()) return true;
        // ⑤ --% stop-parsing 全裸传给原生命令 · CC :1142
        if (trimmed.contains("--%")) return true;
        // ⑥ UNC 路径 \\server\share 或 //server/share 触发网络请求 · CC :1149
        if (trimmed.contains("\\\\")) return true;
        if (Pattern.compile("(^|[^:])\\/\\/").matcher(trimmed).find()) return true;
        // ⑦ 静态方法调用 [Type]::Method() 可调任意 .NET 方法 · CC :1154
        if (trimmed.contains("::")) return true;
        return false;
    }

    /** gh ant 用户门 · 对齐 CC readOnlyValidation.ts:1705（process.env.USER_TYPE !== 'ant' → false）。 */
    private static boolean isAntUser() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }
}
