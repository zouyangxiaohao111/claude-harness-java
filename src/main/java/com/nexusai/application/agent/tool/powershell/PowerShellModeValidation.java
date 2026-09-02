package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * PowerShell 权限模式校验 · 对齐 CC {@code modeValidation.ts:132-404 checkPermissionMode}
 * （session A5 反思返工新增）。
 *
 * <p>在 acceptEdits 模式下自动放行文件系统写 cmdlet（{@link #ACCEPT_EDITS_ALLOWED_CMDLETS}），
 * 与 BashTool modeValidation 对齐。全部守卫失败返回 'passthrough'（交给通用管线），
 * 全部通过返回 'allow'。step5 per-subcommand 循环逐子命令调用本类（CC
 * powershellPermissions.ts:1554-1570）。
 *
 * <p>Java 同步签名映射 CC async；{@link #checkPermissionMode} 返回与 CC 一致（allow | passthrough）。
 *
 * <p>单链说明（WF1-04 删除 master/IT3 死链 · tool-module-align）：本类仅消费生产链
 * {@link PowerShellAstService.ParsedResult}（{@link PowerShellPermissionChain} 链）。permissions IT3
 * 遗留的第二套并行实现 PowerShellPermissions 链已整体删除，其 checkPermissionMode /
 * isSymlinkCreatingCommand 死重载一并移除，仅保留 HEAD/WF-A 单链重载，签名
 * {@link #checkPermissionMode(JsonNode, PowerShellAstService.ParsedResult, ToolPermissionContext, AllowlistChecker)}。
 */
public final class PowerShellModeValidation {

    private static final Logger log = LoggerFactory.getLogger(PowerShellModeValidation.class);

    private PowerShellModeValidation() {
        throw new AssertionError("PowerShellModeValidation is a utility class - do not instantiate");
    }

    /**
     * acceptEdits 模式自动放行的文件系统写 cmdlet · 对齐 CC
     * {@code ACCEPT_EDITS_ALLOWED_CMDLETS}（modeValidation.ts:33-38）。
     *
     * <p>仅简单写 cmdlet（首个位置参数 = -Path），Tier 3 复杂参数绑定 cmdlet（new-item/
     * copy-item/move-item 等）不在表内 → 落到 ask。别名经 {@link ReadOnlyCommandTable#resolveToCanonical}
     * 解析后自动命中（rm→remove-item, ac→add-content）。
     */
    static final Set<String> ACCEPT_EDITS_ALLOWED_CMDLETS = Set.of(
        "set-content", "add-content", "remove-item", "clear-content");

    /** 创建文件系统链接的 New-Item -ItemType 值 · 对齐 CC LINK_ITEM_TYPES（modeValidation.ts:56）。 */
    private static final Set<String> LINK_ITEM_TYPES = Set.of("symboliclink", "junction", "hardlink");

    // ════════════════════════════════════════════════════════════════════════
    // 入口 · 对齐 CC modeValidation.ts:132 checkPermissionMode
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 权限模式校验（HEAD/WF-A 链）· 对齐 CC {@code modeValidation.ts:132-404 checkPermissionMode}。
     *
     * <p>在 acceptEdits 模式下自动放行文件系统写 cmdlet。CC 顺序：
     * bypass/dontAsk → passthrough；非 acceptEdits → passthrough；未解析 → passthrough；
     * 安全标志（子表达式/脚本块/成员调用/splatting/赋值/stop-parsing/展开串）→ passthrough；
     * 空 segments → passthrough；复合 cwd 失步守卫（cd+write / symlink-create）→ passthrough；
     * 逐命令：非 CommandAst → passthrough；nameType=application → passthrough；
     * elementTypes 白名单（StringConstant/Parameter）→ passthrough；colon 绑定表达式 → passthrough；
     * safe-output/pipeline-tail → skip；非 ACCEPT_EDITS_ALLOWED_CMDLETS → passthrough；
     * argLeaksValue → passthrough；全部通过 → allow。
     *
     * @param input     工具输入（含 command）
     * @param parsed    AST 解析结果（step5 传合成单语句 ParsedResult）
     * @param permCtx   权限上下文
     * @param allowlist 只读 allowlist 判定回调（{@link PowerShellPermissionChain#isAllowlistedCommand}）
     * @return allow | passthrough（CC 无 deny）
     */
    public static PermissionResult checkPermissionMode(
            JsonNode input,
            PowerShellAstService.ParsedResult parsed,
            ToolPermissionContext permCtx,
            AllowlistChecker allowlist) {
        PermissionMode mode = permCtx != null ? permCtx.mode() : PermissionMode.DEFAULT;
        // Skip bypass and dontAsk modes (handled elsewhere) · CC :138-146
        if (mode == PermissionMode.BYPASS_PERMISSIONS || mode == PermissionMode.DONT_ASK) {
            return passthrough("Mode is handled in main permission flow");
        }
        if (mode != PermissionMode.ACCEPT_EDITS) {
            return passthrough("No mode-specific validation required");
        }
        if (parsed == null || !parsed.valid()) {
            return passthrough("Cannot validate mode for unparsed command");
        }
        // SECURITY: 子表达式/脚本块/成员调用/splatting/赋值/stop-parsing/展开串 → 不可自动放行 · CC :165-180
        if (parsed.hasSubExpressions() || parsed.hasScriptBlocks() || parsed.hasMemberInvocations()
                || parsed.hasSplatting() || parsed.hasAssignments() || parsed.hasStopParsing()
                || parsed.hasExpandableStrings()) {
            return passthrough("Command contains subexpressions, script blocks, or member invocations that require approval");
        }
        List<PowerShellAstService.Statement> segments = parsed.statements();
        // SECURITY: 空 segments 且 valid → 无命令可校验，不自动放行 · CC :185-190
        if (segments.isEmpty()) {
            return passthrough("No commands found to validate for acceptEdits mode");
        }
        int totalCommands = 0;
        for (PowerShellAstService.Statement seg : segments) {
            totalCommands += seg.commands().size();
        }
        if (totalCommands > 1) {
            boolean hasCdCommand = false;
            boolean hasSymlinkCreate = false;
            boolean hasWriteCommand = false;
            for (PowerShellAstService.Statement seg : segments) {
                for (PowerShellAstService.CommandElement cmd : seg.commands()) {
                    if (!"CommandAst".equals(cmd.elementType())) continue;
                    if (PowerShellPermissionChain.isCwdChangingCmdlet(cmd.name())) hasCdCommand = true;
                    if (isSymlinkCreatingCommand(cmd)) hasSymlinkCreate = true;
                    if (isAcceptEditsAllowedCmdlet(cmd.name())) hasWriteCommand = true;
                }
            }
            // SECURITY: 复合命令 cwd 失步守卫（cd+write）· CC :218-224
            if (hasCdCommand && hasWriteCommand) {
                return passthrough("Compound command contains a directory-changing command (Set-Location/Push-Location/Pop-Location) with a write operation — cannot auto-allow because path validation uses stale cwd");
            }
            // SECURITY: 链接创建复合守卫 · CC :235-241
            if (hasSymlinkCreate) {
                return passthrough("Compound command creates a filesystem link (New-Item -ItemType SymbolicLink/Junction/HardLink) — cannot auto-allow because path validation cannot follow just-created links");
            }
        }
        for (PowerShellAstService.Statement segment : segments) {
            for (PowerShellAstService.CommandElement cmd : segment.commands()) {
                PermissionResult r = checkCommandElement(input, cmd, allowlist);
                if (r != null) return r;
            }
            if (segment.nestedCommands() != null) {
                for (PowerShellAstService.CommandElement cmd : segment.nestedCommands()) {
                    PermissionResult r = checkCommandElement(input, cmd, allowlist);
                    if (r != null) return r;
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("PowerShellModeValidation: acceptEdits 模式自动放行 input={}", input);
        }
        return new PermissionResult.Allow(input,
            new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS), null, false, null, null);
    }

    /** 逐命令校验 · 对齐 CC modeValidation.ts:244-391（commands + nestedCommands 共用）。 */
    private static PermissionResult checkCommandElement(
            JsonNode input,
            PowerShellAstService.CommandElement cmd,
            AllowlistChecker allowlist) {
        // SECURITY: 表达式管线源 / 控制流合成元素 / 非 PipelineAst 重定向 · CC :246-268
        if (!"CommandAst".equals(cmd.elementType())) {
            return passthrough("Pipeline contains expression source (" + cmd.elementType() + ") that cannot be statically validated");
        }
        // SECURITY: nameType 门（原始名含路径字符 = 脚本/exe，不可按 cmdlet 自动放行）· CC :273-278
        if ("application".equals(cmd.nameType())) {
            return passthrough("Command '" + cmd.name() + "' resolved from a path-like name and requires approval");
        }
        // SECURITY: elementTypes 白名单 + colon 绑定表达式 · CC :297-319
        List<String> elementTypes = cmd.elementTypes();
        if (elementTypes != null) {
            for (int i = 1; i < elementTypes.size(); i++) {
                String t = elementTypes.get(i);
                if (!"StringConstant".equals(t) && !"Parameter".equals(t)) {
                    return passthrough("Command argument has unvalidatable type (" + t + ") — variable paths cannot be statically resolved");
                }
                if ("Parameter".equals(t)) {
                    List<String> args = cmd.args();
                    String arg = i - 1 < args.size() ? args.get(i - 1) : "";
                    int colonIdx = arg.indexOf(':');
                    if (colonIdx > 0 && arg.substring(colonIdx + 1).matches(".*[$(@{\\[].*")) {
                        return passthrough("Colon-bound parameter contains an expression that cannot be statically validated");
                    }
                }
            }
        }
        // safe-output / allowlisted pipeline-tail → skip（不影响前置命令语义）· CC :327-332
        if (isSafeOutputCommand(cmd.name()) || isAllowlistedPipelineTail(cmd, allowlist)) {
            return null;
        }
        if (!isAcceptEditsAllowedCmdlet(cmd.name())) {
            return passthrough("No mode-specific handling for '" + cmd.name() + "' in acceptEdits mode");
        }
        // SECURITY: 参数泄漏值（变量/脚本块/子表达式等）→ 不可静态验证 · CC :347-352
        if (argLeaksValue(cmd.name(), cmd)) {
            return passthrough("Arguments in '" + cmd.name() + "' cannot be statically validated in acceptEdits mode");
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 判定助手
    // ════════════════════════════════════════════════════════════════════════

    /**
     * fail-closed 门：只读 auto-allow 唯一放行形状 · 对齐 CC {@code readOnlyValidation.ts:1072-1082
     * isProvablySafeStatement}。
     *
     * <p>仅 PipelineAst 且所有元素为 CommandAst 才返回 true —— 其余（赋值/控制流/表达式源/
     * chain 操作符）一律 false（fail-closed by construction）。step5 fail-closed 门
     * （:1523-1531）与第二段（:1593-1597）共用。
     */
    static boolean isProvablySafeStatement(PowerShellAstService.Statement stmt) {
        if (stmt == null || !"PipelineAst".equals(stmt.type())) return false;
        if (stmt.commands().isEmpty()) return false;
        for (PowerShellAstService.CommandElement c : stmt.commands()) {
            if (!"CommandAst".equals(c.elementType())) return false;
        }
        return true;
    }

    /** acceptEdits 写 cmdlet 判定 · 对齐 CC modeValidation.ts:40-47 isAcceptEditsAllowedCmdlet。 */
    static boolean isAcceptEditsAllowedCmdlet(String name) {
        String canonical = ReadOnlyCommandTable.resolveToCanonical(name);
        return ACCEPT_EDITS_ALLOWED_CMDLETS.contains(canonical);
    }

    /**
     * New-Item 是否创建文件系统链接（HEAD/WF-A 链）· 对齐 CC {@code modeValidation.ts:82-117 isSymlinkCreatingCommand}。
     *
     * <p>处理 PS 参数缩写（-it/-ite.../-itemtype；-ty/-typ.../-type）、Unicode dash 前缀
     * （en-dash/em-dash/horizontal-bar）、`/` 前缀（PS 5.1）、colon 绑定值（-it:Junction）、
     * 反引号转义。三种链接类型（symboliclink/junction/hardlink）都会重定向运行时路径解析。
     */
    static boolean isSymlinkCreatingCommand(PowerShellAstService.CommandElement cmd) {
        String canonical = ReadOnlyCommandTable.resolveToCanonical(cmd.name());
        if (!"new-item".equals(canonical)) return false;
        List<String> args = cmd.args();
        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            if (raw.isEmpty()) continue;
            // 归一化 Unicode dash 前缀与 / → ASCII -（PS tokenizer 四种 dash + / 均为参数标记）
            char c0 = raw.charAt(0);
            String normalized = (PowerShellPermissionChain.isPowerShellDashChar(c0) || c0 == '/')
                ? "-" + raw.substring(1) : raw;
            String lower = normalized.toLowerCase();
            // 拆分 colon 绑定值：-it:SymbolicLink → param='-it', val='symboliclink'
            int colonIdx = lower.indexOf(':', 1);
            String paramRaw = colonIdx > 0 ? lower.substring(0, colonIdx) : lower;
            // 去反引号转义：-Item`Type → -ItemType
            String param = paramRaw.replace("`", "");
            if (!isItemTypeParamAbbrev(param)) continue;
            String rawVal = colonIdx > 0
                ? lower.substring(colonIdx + 1)
                : (i + 1 < args.size() ? args.get(i + 1).toLowerCase() : "");
            // 去反引号转义 + 去引号：-it:'SymbolicLink' 或 -it:"Junction"
            String val = rawVal.replace("`", "").replaceAll("^['\"]|['\"]$", "");
            if (LINK_ITEM_TYPES.contains(val)) {
                return true;
            }
        }
        return false;
    }

    /** New-Item -ItemType/-Type 参数缩写判定 · 对齐 CC modeValidation.ts:64-69 isItemTypeParamAbbrev。 */
    static boolean isItemTypeParamAbbrev(String p) {
        return (p.length() >= 3 && "-itemtype".startsWith(p))
            || (p.length() >= 3 && "-type".startsWith(p));
    }

    /**
     * 参数是否泄漏动态值 · 对齐 CC {@code readOnlyValidation.ts:76-115 argLeaksValue}。
     *
     * <p><b>D4（G33②）</b>：CC 优先查 {@code element.children}（readOnlyValidation.ts:96-111）——
     * colon-bound 参数（{@code -Flag:$env:SECRET}）是单个 CommandParameterAst，其 .Argument 是
     * children 子节点（Java {@link PowerShellAstService.CommandElementChild}，children[i-1] ↔ args[i-1]
     * ↔ elementTypes[i]，AstService:67-72/83-87）。任一子节点类型非 StringConstant（Variable /
     * ParenExpression 包裹任意管道 / Hashtable ...）即视为泄漏。仅当 children 缺失（旧 parser/测试桩，
     * CC :102-111 注释「pre-children parsers」）回退字符串考古：colon 绑定值含 {@code $ ( @ { [} 元字符
     * 即泄漏。
     */
    static boolean argLeaksValue(String cmd, PowerShellAstService.CommandElement element) {
        List<String> argTypes = element.elementTypes();
        List<String> args = element.args();
        if (argTypes == null || argTypes.size() < 2) return false;
        List<List<PowerShellAstService.CommandElementChild>> children = element.children();
        for (int i = 1; i < argTypes.size(); i++) {
            String t = argTypes.get(i);
            String arg = i - 1 < args.size() ? args.get(i - 1) : "";
            if (!"StringConstant".equals(t) && !"Parameter".equals(t)) {
                // ArrayLiteralAst→'Other' 等：字符串考古（Hashtable `@{`、ParenExpr `(`、变量 `$`、
                // 类型字面量 `[`、脚本块 `{`）；逗号裸标识符列表无元字符 → 继续
                if (!arg.matches(".*[$(@{\\[].*")) continue;
                return true;
            }
            if ("Parameter".equals(t)) {
                // D4：children 优先（CC :96-101）。children[i] 存在（含空列表——JS 空数组 truthy，
                // CC 空 children 不做考古 fallback）→ 仅按 children 判定；缺失 → 字符串考古（CC :102-111）。
                if (children != null && i - 1 < children.size()) {
                    List<PowerShellAstService.CommandElementChild> paramChildren = children.get(i - 1);
                    if (paramChildren != null) {
                        for (PowerShellAstService.CommandElementChild child : paramChildren) {
                            if (!"StringConstant".equals(child.type())) {
                                return true;
                            }
                        }
                        continue;
                    }
                }
                // children 缺失 fallback：colon 绑定值含元字符 → 泄漏
                int colonIdx = arg.indexOf(':');
                if (colonIdx > 0 && arg.substring(colonIdx + 1).matches(".*[$(@{\\[].*")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** safe-output cmdlet 判定（name-only）· 对齐 CC readOnlyValidation.ts:1038-1041。 */
    static boolean isSafeOutputCommand(String name) {
        return "out-null".equals(ReadOnlyCommandTable.resolveToCanonical(name));
    }

    /**
     * pipeline-tail 变换 cmdlet 判定 · 对齐 CC {@code readOnlyValidation.ts:1052-1061
     * isAllowlistedPipelineTail}（PIPELINE_TAIL_CMDLETS + isAllowlistedCommand 双重校验）。
     *
     * <p>Format-* 与 Select-Object 等从 SAFE_OUTPUT_CMDLETS 迁至 CMDLET_ALLOWLIST 的变换器，
     * 须通过 argLeaksValue 保护的 allowlist 才可 skip。
     */
    static boolean isAllowlistedPipelineTail(PowerShellAstService.CommandElement cmd, AllowlistChecker allowlist) {
        String canonical = ReadOnlyCommandTable.resolveToCanonical(cmd.name());
        switch (canonical) {
            case "format-table", "format-list", "format-wide", "format-custom", "measure-object",
                 "select-object", "sort-object", "group-object", "where-object", "out-string", "out-host":
                return allowlist.isAllowlisted(cmd);
            default:
                return false;
        }
    }

    private static PermissionResult passthrough(String message) {
        return new PermissionResult.Passthrough(message,
            new PermissionDecisionReason.Other(message), List.of(), null, null);
    }

    /** allowlist 判定回调 · step5 传 {@link PowerShellPermissionChain#isAllowlistedCommand}。 */
    @FunctionalInterface
    public interface AllowlistChecker {
        boolean isAllowlisted(PowerShellAstService.CommandElement element);
    }
}
