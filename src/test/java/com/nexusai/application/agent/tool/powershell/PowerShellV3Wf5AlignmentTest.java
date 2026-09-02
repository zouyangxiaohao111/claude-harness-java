package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.DangerousPatternDetector;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdateApplier;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * permissions_v3 WF-5 PowerShell 域对齐聚焦测试（OPD-PS-01..06 + OPD-WF4-DEC-02 PS 部分）。
 *
 * <p>WHY（意图验证，全部 CC 真源）：
 * <ul>
 *   <li>OPD-PS-01：exact 规则 canonical 交叉匹配 —— 'deny rm foo' 拦 'Remove-Item foo' 且
 *       'deny Remove-Item foo' 拦 'rm foo'（CC powershellPermissions.ts:252-283）。</li>
 *   <li>OPD-PS-02：containsVulnerableUncPath 8 模式（混合分隔/IPv4/IPv6 补齐）+ getPlatform!=='windows'
 *       平台门（CC readOnlyCommandValidation.ts:1562-1638）。</li>
 *   <li>OPD-PS-03：顶层 checkPermissionMode 补入 collect —— acceptEdits 写 cmdlet 整链 Allow
 *       （CC powershellPermissions.ts:1347-1352）。</li>
 *   <li>OPD-PS-04：ALT dash 归一 —— en/em-dash 参数前缀经 psExeHasParamAbbreviation 命中
 *       （CC powershellSecurity.ts:83-100，'Start-Process foo –Verb RunAs' 之前漏检）。</li>
 *   <li>OPD-PS-05：getFileRedirections 滤 isMerging + $null —— 'Get-Process > $null' 不再误 ask
 *       （CC parser.ts:1703-1719）。</li>
 *   <li>OPD-PS-06：重定向目标 validatePath('create') deny-capable + 工作目录（CC pathValidation.ts:1937-2041）。</li>
 *   <li>OPD-WF4-DEC-02：isDangerousPowerShellPermission 归 PowerShell 工具域（CC permissionSetup.ts:157-233）。</li>
 * </ul>
 */
class PowerShellV3Wf5AlignmentTest {

    // ════════════════════════════════════════════════════════════════════════
    // 测试桩
    // ════════════════════════════════════════════════════════════════════════

    static final class FakeAstService extends PowerShellAstService {
        private PowerShellAstService.ParsedResult result;

        void stub(PowerShellAstService.ParsedResult result) {
            this.result = result;
        }

        @Override
        public PowerShellAstService.ParsedResult parseAst(String script) {
            return result != null ? result : invalid();
        }

        static PowerShellAstService.ParsedResult invalid() {
            return new PowerShellAstService.ParsedResult(false, List.of("stub"), false, false, false, false, false,
                false, false, false, false, List.of(), List.of(), List.of(), List.of(), "stub");
        }
    }

    private static PowerShellAstService.CommandElement cmd(String name, String nameType, String... args) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        for (int i = 0; i < args.length; i++) {
            elementTypes.add("StringConstant");
        }
        String text = name + (args.length > 0 ? " " + String.join(" ", args) : "");
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
    }

    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        stmts.add(new PowerShellAstService.Statement("PipelineAst",
            cmds[0].text() + (cmds.length > 1 ? " ; " + cmds[1].text() : ""),
            List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false,
            false, false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
    }

    /** 带语句级 redirections 的 ParsedResult（OPD-PS-05/06 用）。 */
    private static PowerShellAstService.ParsedResult withRedirections(
            PowerShellAstService.CommandElement commandElement, String commandText,
            List<PowerShellAstService.Redirection> redirections) {
        PowerShellAstService.Statement st = new PowerShellAstService.Statement("PipelineAst",
            commandText, List.of(commandElement), List.of());
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false,
            false, false, false, false, List.of(), List.of(), List.of(st), redirections, commandText);
    }

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    private static PermissionRule rule(PermissionBehavior b, String content) {
        return new PermissionRule(PermissionRuleSource.SESSION, b,
            new PermissionRuleValue("PowerShell", content));
    }

    private static ToolPermissionContext permCtxOf(PermissionRule... rules) {
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> deny = new java.util.HashMap<>();
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> ask = new java.util.HashMap<>();
        for (PermissionRule r : rules) {
            (r.ruleBehavior() == PermissionBehavior.DENY ? deny : ask)
                .computeIfAbsent(PermissionRuleSource.SESSION, k -> new java.util.HashSet<>()).add(r);
        }
        return new ToolPermissionContext(PermissionMode.DEFAULT, java.util.Map.of(), deny, ask,
            java.util.Map.of(), false, false, java.util.Map.of(), false, false, null);
    }

    private static ToolPermissionContext allowCtxOf(PermissionRule... rules) {
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> allow = new java.util.HashMap<>();
        for (PermissionRule r : rules) {
            allow.computeIfAbsent(PermissionRuleSource.SESSION, k -> new java.util.HashSet<>()).add(r);
        }
        return new ToolPermissionContext(PermissionMode.DEFAULT, allow, java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), false, false, java.util.Map.of(), false, false, null);
    }

    private static ToolUseContext ctxWith(ToolPermissionContext permCtx) {
        return new ToolUseContext(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            java.util.Map.of(), List.of(), null, null, List.of(), permCtx, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", null, null, java.util.Map.of(),
            null, null, null, null, null);
    }

    private static ToolUseContext ctxAcceptEdits() {
        ToolPermissionContext permCtx = new ToolPermissionContext(PermissionMode.ACCEPT_EDITS,
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            false, false, java.util.Map.of(), false, false, null);
        // effectiveCwd 显式指定 C:/work/project —— 规避测试 worktree 位于 .claude/ 下导致
        // pathSafetyForAutoEdit 命中危险目录 .claude 的假阳性（生产 cwd 由会话注入）。
        return new ToolUseContext(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.ACCEPT_EDITS,
            java.util.Map.of(), List.of(), null, null, List.of(), permCtx, PermissionMode.ACCEPT_EDITS,
            java.util.Map.of(), false, "", Path.of("C:/work/project"), null, java.util.Map.of(),
            null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-01 · exact 规则 canonical 交叉匹配（CC powershellPermissions.ts:252-283）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-01: deny 'rm foo' 拦 'Remove-Item foo'（exact canonical 交叉，rule-side canonical）")
    void exactDenyRmFooBlocksRemoveItemFoo() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Write-Output", "cmdlet", "x"), cmd("Remove-Item", "cmdlet", "foo")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "rm foo")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x ; Remove-Item foo"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "'deny rm foo' 必须经 exact canonical 交叉拦 'Remove-Item foo'（CC :265-283 ruleCanonical===inputCanonical && ruleRest===inputRest）");
    }

    @Test
    @DisplayName("OPD-PS-01: deny 'Remove-Item foo' 拦 'rm foo'（matchesCommand(canonicalCommand)）")
    void exactDenyRemoveItemFooBlocksRmFoo() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Write-Output", "cmdlet", "x"), cmd("rm", "cmdlet", "foo")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Remove-Item foo")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x ; rm foo"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "'deny Remove-Item foo' 必须拦 'rm foo'（CC :254-256 matchesCommand(canonicalCommand)）");
    }

    @Test
    @DisplayName("OPD-PS-01: allow 'rm foo' 不放行 'Remove-Item bar'（rest 不等不匹配，防 fail-open）")
    void exactAllowRestMismatchNotMatch() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Write-Output", "cmdlet", "x"), cmd("Remove-Item", "cmdlet", "bar")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "rm foo")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x ; Remove-Item bar"), ctx);
        assertFalse(result instanceof PermissionResult.Allow,
            "'allow rm foo' 不得放行 'Remove-Item bar'（exact canonical 交叉要求 ruleRest===inputRest）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-02 · containsVulnerableUncPath 8 模式 + Windows 平台门（readOnlyCommandValidation.ts:1562-1638）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-02: 混合分隔 /\\(bash 转义) 与 \\\\(反向) 均判 UNC")
    void uncMixedSeparators() {
        PowerShellPermissionChain win = new PowerShellPermissionChain(null, true);
        assertTrue(win.containsVulnerableUncPath("/\\evil\\share"),
            "混合分隔 /\\ 在 bash 转义后是 UNC（CC :1594 mixedSlashUncPattern）");
        assertTrue(win.containsVulnerableUncPath("\\\\/evil\\share"),
            "混合分隔 \\\\/ 在 bash 转义后是 UNC（CC :1602 reverseMixedSlashUncPattern）");
    }

    @Test
    @DisplayName("OPD-PS-02: IPv4 / IPv6 UNC 判中（defense-in-depth 显式检查）")
    void uncIpv4Ipv6() {
        PowerShellPermissionChain win = new PowerShellPermissionChain(null, true);
        assertTrue(win.containsVulnerableUncPath("\\\\192.168.1.1\\share"),
            "IPv4 UNC \\\\192.168.1.1\\share 必须判中（CC :1621-1626）");
        assertTrue(win.containsVulnerableUncPath("//10.0.0.1/path"),
            "IPv4 forward-slash //10.0.0.1/path 必须判中");
        assertTrue(win.containsVulnerableUncPath("\\\\[2001:db8::1]\\share"),
            "IPv6 UNC \\\\[2001:db8::1]\\share 必须判中（CC :1630-1635）");
        assertTrue(win.containsVulnerableUncPath("//[::1]/path"),
            "IPv6 forward-slash //[::1]/path 必须判中");
    }

    @Test
    @DisplayName("OPD-PS-02: WebDAV/DavWWWRoot 模式 + URL 排除")
    void uncWebDavAndUrlExclusion() {
        PowerShellPermissionChain win = new PowerShellPermissionChain(null, true);
        assertTrue(win.containsVulnerableUncPath("\\\\server@SSL@8443\\path"),
            "WebDAV @SSL@port 必须判中（CC :1609）");
        assertTrue(win.containsVulnerableUncPath("\\\\server\\DavWWWRoot\\path"),
            "DavWWWRoot 标记必须判中（CC :1615）");
        assertFalse(win.containsVulnerableUncPath("https://example.com"),
            "URL :// 不得误判（CC :1582 negative lookbehind (?<!:)）");
    }

    @Test
    @DisplayName("OPD-PS-02: 非 Windows 平台门 —— 恒 false（getPlatform()!=='windows'）")
    void uncPlatformGate() {
        PowerShellPermissionChain nonWin = new PowerShellPermissionChain(null, false);
        assertFalse(nonWin.containsVulnerableUncPath("\\\\server\\share"),
            "非 Windows 平台 containsVulnerableUncPath 恒 false（CC :1564-1566 getPlatform()!=='windows' → false）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-03 · 顶层 checkPermissionMode 补入 collect（CC powershellPermissions.ts:1347-1352）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-03: acceptEdits + Set-Content 写 cmdlet → 整链 Allow（顶层 mode collect）")
    void topLevelModeCollectAllowsAcceptEditsWrite() {
        // 旧实现仅 step5 逐子命令合成单语句 AST 调用 checkPermissionMode —— acceptEdits 写 cmdlet
        // 落 step5 passthrough 而非 Allow。补入顶层 collect 后 decisionReason(mode acceptEdits) 参与
        // reduce → Allow（CC :1347-1352 push checkPermissionMode 结果）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Set-Content", "cmdlet", "file.txt")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Set-Content file.txt"), ctxAcceptEdits());
        assertInstanceOf(PermissionResult.Allow.class, result,
            "acceptEdits 模式顶层 checkPermissionMode 必须放行 Set-Content（CC :1349-1352）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-04 · ALT dash（en/em-dash）归一化（CC powershellSecurity.ts:83-100 psExeHasParamAbbreviation）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-04: New-Object –ComObject（en-dash）→ COM ask（旧 ASCII startsWith 漏检）")
    void altDashComObjectAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "\u2013ComObject", "WScript.Shell")));
        assertNotNull(msg, "en-dash –ComObject 必须命中 checkComObject（CC :353 psExeHasParamAbbreviation）");
        assertTrue(msg.contains("COM"), "消息应指向 COM 对象，实际: " + msg);
    }

    @Test
    @DisplayName("OPD-PS-04: Start-Process foo –Verb RunAs（em-dash 空格语法）→ 提权 ask")
    void altDashStartProcessVerbRunAsAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "\u2014Verb", "RunAs")));
        assertNotNull(msg, "em-dash –Verb RunAs 空格语法必须命中 checkStartProcess（CC :561-569）");
        assertTrue(msg.contains("提升权限"), "消息应含提权语义，实际: " + msg);
    }

    @Test
    @DisplayName("OPD-PS-04: pwsh –EncodedCommand（en-dash）→ 编码参数 ask")
    void altDashEncodedCommandAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("pwsh", "cmdlet", "\u2013EncodedCommand", "QQ==")));
        assertNotNull(msg, "en-dash –EncodedCommand 必须命中 checkEncodedCommand（CC :171）");
    }

    @Test
    @DisplayName("OPD-PS-04: ForEach-Object –MemberName（en-dash）→ ask")
    void altDashForEachMemberNameAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("ForEach-Object", "cmdlet", "\u2013MemberName", "Kill")));
        assertNotNull(msg, "en-dash –MemberName 必须命中 checkForEachMemberName（CC :509）");
    }

    @Test
    @DisplayName("OPD-PS-04: Start-Job –FilePath script.ps1（horizontal-bar dash）→ ask")
    void altDashFilePathExecutionAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Job", "cmdlet", "\u2015FilePath", "script.ps1")));
        assertNotNull(msg, "horizontal-bar dash –FilePath 必须命中 checkDangerousFilePathExecution（CC :462）");
    }

    @Test
    @DisplayName("OPD-PS-04: ASCII -Verb RunAs 既有路径仍绿（回归）")
    void asciiVerbRunAsStillAsks() {
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-Verb", "RunAs"))),
            "ASCII -Verb RunAs space 语法必须仍命中");
    }

    private static PowerShellAstService.ParsedResult parsed(
            List<String> typeLiterals, PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        if (cmds.length > 0) {
            stmts.add(new PowerShellAstService.Statement("PipelineAst", cmds[0].text(), List.of(cmds), List.of()));
        }
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false,
            false, false, false, false, List.of(), typeLiterals, stmts, List.of(), "cmd");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-05 · getFileRedirections 滤 isMerging + $null（CC parser.ts:1703-1719）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-05: getFileRedirections 滤 merging($null 目标) 与 $null，保留真实文件")
    void fileRedirectionsFilterSemantics() {
        PowerShellAstService.ParsedResult p = withRedirections(
            cmd("Get-Process", "cmdlet"),
            "Get-Process > out.txt",
            List.of(new PowerShellAstService.Redirection("$null", false),
                new PowerShellAstService.Redirection("", true),
                new PowerShellAstService.Redirection("out.txt", false)));
        List<PowerShellAstService.Redirection> file = PowerShellPermissionChain.getFileRedirections(p);
        assertEquals(1, file.size(), "getFileRedirections 应滤 $null 与 merging，仅保留 out.txt（CC :1713-1719）");
        assertEquals("out.txt", file.get(0).target());
    }

    @Test
    @DisplayName("OPD-PS-05: 'Get-Process > $null' 整链不误 ask → Allow（只读允许）")
    void nullRedirectionNoMisAsk() {
        PowerShellAstService.CommandElement getProc = new PowerShellAstService.CommandElement(
            "Get-Process", "cmdlet", "CommandAst", List.of(), List.of("StringConstant"),
            "Get-Process > $null", List.of("$null"), List.of());
        FakeAstService ast = new FakeAstService();
        ast.stub(withRedirections(getProc, "Get-Process > $null",
            List.of(new PowerShellAstService.Redirection("$null", false))));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process > $null"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "'Get-Process > $null' 丢弃输出不写文件，不得因文件重定向误 ask（CC getFileRedirections 滤 $null，OPD-PS-05）");
    }

    @Test
    @DisplayName("OPD-PS-05: 'Get-Process 2>&1' merging 重定向不触发文件重定向 ask")
    void mergingRedirectionNoMisAsk() {
        PowerShellAstService.CommandElement getProc = new PowerShellAstService.CommandElement(
            "Get-Process", "cmdlet", "CommandAst", List.of(), List.of("StringConstant"),
            "Get-Process 2>&1", List.of(), List.of());
        FakeAstService ast = new FakeAstService();
        ast.stub(withRedirections(getProc, "Get-Process 2>&1",
            List.of(new PowerShellAstService.Redirection("", true))));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process 2>&1"), null);
        assertFalse(result instanceof PermissionResult.Ask,
            "2>&1 merging 重定向（MergingRedirectionAst）不写文件，不得触发文件重定向 ask（CC :1716 isMerging 滤除）");
    }

    @Test
    @DisplayName("OPD-PS-05: 'Get-Process > out.txt' 真实文件重定向仍 ask（回归）")
    void realFileRedirectionStillAsks() {
        PowerShellAstService.CommandElement getProc = new PowerShellAstService.CommandElement(
            "Get-Process", "cmdlet", "CommandAst", List.of(), List.of("StringConstant"),
            "Get-Process > out.txt", List.of("out.txt"), List.of());
        FakeAstService ast = new FakeAstService();
        ast.stub(withRedirections(getProc, "Get-Process > out.txt",
            List.of(new PowerShellAstService.Redirection("out.txt", false))));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process > out.txt"), null);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "真实文件重定向仍须 ask（CC :1337-1345 仅滤 $null/merging）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-PS-06 · 重定向目标 validatePath('create')（CC pathValidation.ts:1937-2041）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-PS-06: 重定向目标工作目录外 → 路径校验 ask（deny-capable + 工作目录）")
    void redirectionTargetOutsideWorkingDirAsks() {
        PowerShellAstService.CommandElement setContent = new PowerShellAstService.CommandElement(
            "Set-Content", "cmdlet", "CommandAst", List.of("file.txt"), List.of("StringConstant", "StringConstant"),
            "Set-Content file.txt > C:/outside/target.txt",
            List.of("C:/outside/target.txt"), List.of());
        PowerShellAstService.ParsedResult parsed = withRedirections(setContent, "Set-Content file.txt",
            List.of(new PowerShellAstService.Redirection("C:/outside/target.txt", false)));
        PermissionResult r = PowerShellPathValidator.check(input("x"), parsed, null,
            Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "重定向目标 C:/outside/target.txt 在工作目录外必须 ask（CC :1999-2038 validatePath 'create'）");
    }

    @Test
    @DisplayName("OPD-PS-06: 重定向目标 Edit deny 规则命中 → deny（deny-capable）")
    void redirectionTargetEditDenyDenies() {
        PowerShellAstService.CommandElement setContent = new PowerShellAstService.CommandElement(
            "Set-Content", "cmdlet", "CommandAst", List.of("file.txt"), List.of("StringConstant", "StringConstant"),
            "Set-Content file.txt > /etc/hosts",
            List.of("/etc/hosts"), List.of());
        PowerShellAstService.ParsedResult parsed = withRedirections(setContent, "Set-Content file.txt",
            List.of(new PowerShellAstService.Redirection("/etc/hosts", false)));
        PermissionRule denyRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            new PermissionRuleValue("Edit", "/etc/**"));
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> deny = new java.util.HashMap<>();
        deny.computeIfAbsent(PermissionRuleSource.SESSION, k -> new java.util.HashSet<>()).add(denyRule);
        ToolPermissionContext permCtx = new ToolPermissionContext(PermissionMode.DEFAULT,
            java.util.Map.of(), deny, java.util.Map.of(), java.util.Map.of(),
            false, false, java.util.Map.of(), false, false, null);
        PermissionResult r = PowerShellPathValidator.check(input("x"), parsed, permCtx,
            Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "重定向目标命中 Edit deny 规则必须 deny（CC :1965-1970/:2018-2023 decisionReason.type==='rule' → deny）");
    }

    @Test
    @DisplayName("OPD-PS-06: '> $null' 重定向目标不参与路径校验（无 ask）")
    void nullRedirectionTargetSkipsPathValidation() {
        PowerShellAstService.CommandElement getProc = new PowerShellAstService.CommandElement(
            "Get-Process", "cmdlet", "CommandAst", List.of(), List.of("StringConstant"),
            "Get-Process > $null", List.of("$null"), List.of());
        PowerShellAstService.ParsedResult parsed = withRedirections(getProc, "Get-Process > $null",
            List.of(new PowerShellAstService.Redirection("$null", false)));
        PermissionResult r = PowerShellPathValidator.check(input("x"), parsed, null,
            Path.of("C:/work/project"), false);
        assertTrue(r instanceof PermissionResult.Passthrough,
            "> $null 重定向目标跳过 validatePath（CC :1944/:1997 isNullRedirectionTarget），无 deny/ask");
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-WF4-DEC-02 · isDangerousPowerShellPermission 归 PowerShell 工具域
    //（CC permissionSetup.ts:157-233）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OPD-WF4-DEC-02: PS 专有危险 allow 规则判危险（pwsh/iex/Start-Process）")
    void dangerousPowerShellPermissionPatterns() {
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "pwsh"),
            "PowerShell(pwsh) 嵌套 shell 必须判危险");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "iex:*"),
            "PowerShell(iex:*) 前缀必须判危险");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "Start-Process"),
            "PowerShell(Start-Process) 进程生成必须判危险");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "npm run"),
            "跨平台 CROSS_PLATFORM_CODE_EXEC 共享（CC :179）");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "npm.exe run"),
            ".exe 变体（'npm run'→'npm.exe run'，CC :218-230）");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", null),
            "整工具 allow（ruleContent null）恒最危险（CC :165-168）");
        assertTrue(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "*"),
            "content '*' 匹配一切恒危险（CC :172-175）");
        assertFalse(PowerShellCommandSafety.isDangerousPowerShellPermission("PowerShell", "Get-Process"),
            "常规只读 cmdlet 不危险");
        assertFalse(PowerShellCommandSafety.isDangerousPowerShellPermission("Bash", "pwsh"),
            "工具门：非 PowerShell 工具不参与（CC :161-163）");
    }

    @Test
    @DisplayName("OPD-WF4-DEC-02: DangerousPatternDetector 委托 PowerShell + Bash 不再含 PS 形态")
    void dangerousPatternDetectorDelegatesPowerShell() {
        DangerousPatternDetector detector = new DangerousPatternDetector(new NullApplier());
        assertTrue(detector.isDangerousRule(
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    new PermissionRuleValue("PowerShell", "pwsh"))),
            "PowerShell(pwsh) allow 规则必须经委托判危险（OPD-WF4-DEC-02 归域接线）");
        assertFalse(detector.isDangerousRule(
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    new PermissionRuleValue("Bash", "powershell -c x"))),
            "Bash 规则含 'powershell' 字样不再被 PS 专有形态误判（PS Pattern 已从 DANGEROUS_BASH_PATTERNS 删除）");
        assertFalse(detector.isDangerousRule(
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    new PermissionRuleValue("PowerShell", "Get-Process"))),
            "PowerShell 常规 allow 规则不危险");
    }

    /** 不修改 ctx 的空 applier（DangerousPatternDetector 构造要求非 null）。 */
    private static final class NullApplier extends PermissionUpdateApplier {
        @Override
        public ToolPermissionContext apply(PermissionUpdate update, ToolPermissionContext context) {
            return context;
        }
    }
}
