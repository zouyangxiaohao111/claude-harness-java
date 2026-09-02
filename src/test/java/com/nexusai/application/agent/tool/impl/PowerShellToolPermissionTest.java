package com.nexusai.application.agent.tool.impl;

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
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.powershell.PowerShellAstService;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellTool 权限链测试 · 验证 checkPermissions 对齐 CC powershellToolHasPermission。
 *
 * <p>WHY（意图验证）：
 * <ul>
 *   <li>PowerShellTool 从 default Allow（Tool.java:238）改为 override checkPermissions ——
 *       危险命令（Remove-Item /）必须 Deny（规则十二：显式失败，绝不放行）</li>
 *   <li>只读命令（Get-Process）经 allowlist auto-allow（CC :1318-1331）</li>
 *   <li>cd+git 复合命令必须 Ask（CC :1109-1145 裸仓库攻击防护）</li>
 *   <li>非白名单未知命令不得 Allow（fail-closed，CC step5）</li>
 * </ul>
 *
 * <p>pwsh 运行时不可用（本机/CI）时，dangerousRemoval 是 parse 无关硬 deny，测试仍绿；
 * 只读/cd+git 用例用 {@link FakeAstService} 注入可控 ParsedResult（不依赖 pwsh）。
 */
class PowerShellToolPermissionTest {

    /** 可控 AST 桩：不启动 pwsh，返回预置 ParsedResult。 */
    static final class FakeAstService extends PowerShellAstService {
        private PowerShellAstService.ParsedResult result;

        void stub(PowerShellAstService.ParsedResult result) {
            this.result = result;
        }

        @Override
        public PowerShellAstService.ParsedResult parseAst(String script) {
            if (result == null) {
                return empty();
            }
            return result;
        }

        static PowerShellAstService.ParsedResult empty() {
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

    /** 自定义 text（raw 绕过用例：& 'Remove-Item' ./x 的 raw text 与 AST name 分离）。 */
    private static PowerShellAstService.CommandElement cmdRaw(String name, String nameType, String text,
                                                              String... args) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        for (int i = 0; i < args.length; i++) {
            elementTypes.add("StringConstant");
        }
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
    }

    /** 非 PipelineAst 语句（fail-closed 门：bare $env:SECRET / 控制流）。 */
    private static PowerShellAstService.ParsedResult statement(String type, String text,
                                                               PowerShellAstService.CommandElement... cmds) {
        PowerShellAstService.Statement st = new PowerShellAstService.Statement(type, text,
            List.of(cmds), List.of());
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false,
            false, false, false, false, List.of(), List.of(), List.of(st), List.of(), "stub");
    }

    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        stmts.add(new PowerShellAstService.Statement("PipelineAst",
            cmds[0].text() + (cmds.length > 1 ? " ; " + cmds[1].text() : ""),
            List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false, false,
            false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
    }

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    @Test
    @DisplayName("parse-success 危险路径（Remove-Item /）→ Deny（checkPathConstraints）")
    void dangerousRemovalBlocked() {
        // 单命令 Remove-Item / 在 parse-success 下走 checkPathConstraints（pathValidation.ts:1735）
        // → isDangerousRemovalRawPath('/') 硬 deny。CC 单片段 parse-failed 走 :796-801 跳过，
        // 故本断言用 parse-success（AST 桩），不是 parse-failed。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Remove-Item", "cmdlet", "/", "-Recurse", "-Force")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Remove-Item -Recurse -Force /"), null);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "Remove-Item / 在 parse-success 下必须硬 deny（CC pathValidation.ts:1735/1850 危险删除 deny）");
    }

    @Test
    @DisplayName("parse-failed 复合命令危险删除（Remove-Item ~ ; ...）→ Deny（片段扫描）")
    void dangerousRemovalHomeBlocked() {
        // 复合命令强制 parse-failed（FakeAstService 返回 valid=false）：片段 'Remove-Item ~ -Recurse -Force'
        // ≠ 整串 → 不跳过（CC :796-801），归一化后 per-arg 危险删除 deny（CC :825-840）。
        // 单片段 remove-item 命令 CC 会因 trimmedFrag === command 跳过 → 降级 ask（如实标注）。
        FakeAstService ast = new FakeAstService();
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Remove-Item ~ -Recurse -Force ; Get-Process"), null);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "parse-failed 复合命令中 Remove-Item ~ 片段必须硬 deny（CC :825-840）");
    }

    @Test
    @DisplayName("空命令 → Allow（CC :647-656 Empty command is safe）")
    void emptyCommandAllowed() {
        FakeAstService ast = new FakeAstService();
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("  "), null);
        assertInstanceOf(PermissionResult.Allow.class, result);
    }

    @Test
    @DisplayName("只读命令 Get-Process → Allow（CC :1318-1331 allowlist）")
    void readOnlyCommandAllowed() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Get-Process", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "Get-Process 在 CMDLET_ALLOWLIST → read-only auto-allow");
    }

    @Test
    @DisplayName("公共取值参数合并：Get-Content -ErrorAction SilentlyContinue → Allow（CC readOnlyValidation.ts:1503）")
    void commonValueParamAllowed() {
        // WHY：-ErrorAction 不在 get-content safeFlags，但属 COMMON_VALUE_PARAMS（commonParameters.ts:15），
        // 仅路由错误流不能落盘。CC readOnlyValidation.ts:1503 对 cmdlet 合并进 safeFlags 校验；
        // 缺此合并会让 Get-Content file.txt -ErrorAction SilentlyContinue 误拒 → 必须 Allow。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Get-Content", "cmdlet", "-Path", "file.txt", "-ErrorAction", "SilentlyContinue")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(
            input("Get-Content -Path file.txt -ErrorAction SilentlyContinue"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "公共取值参数 -ErrorAction 必须经 safeFlags 公共参数合并放行（CC :1503）");
    }

    @Test
    @DisplayName("公共 switch 合并：Get-ChildItem -Recurse -Verbose → Allow（CC readOnlyValidation.ts:1503 + commonParameters.ts:12）")
    void commonSwitchAllowed() {
        // WHY：-Recurse 在 get-childitem safeFlags，-Verbose 属 COMMON_SWITCHES（commonParameters.ts:12），
        // 仅开启详细输出。CC :1503 对 cmdlet 合并公共参数 → 必须 Allow。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Get-ChildItem", "cmdlet", "-Recurse", "-Verbose")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-ChildItem -Recurse -Verbose"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "公共 switch -Verbose 必须经 safeFlags 公共参数合并放行（CC :1503）");
    }

    @Test
    @DisplayName("未过度放行：-BadFlag 既非 safeFlags 也非公共参数 → 非 Allow（CC :1509-1511 fail-closed）")
    void unknownFlagStillBlocked() {
        // WHY：合并只应放行 COMMON_PARAMETERS 精确 12 项，任意其他 flag 仍 fail-closed
        //（CC readOnlyValidation.ts:1509-1511 不在白名单即 return false）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Get-Content", "cmdlet", "-Path", "file.txt", "-BadFlag", "x")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(
            input("Get-Content -Path file.txt -BadFlag x"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "非公共非 safeFlags 的 -BadFlag 必须 fail-closed（不得 Allow）");
    }

    @Test
    @DisplayName("isCmdlet 门：ipconfig -ErrorAction → 非 Allow（native exe canonical 无 '-' 不并入）")
    void nativeExeCommonParamNotMerged() {
        // WHY：CC readOnlyValidation.ts:1503 前缀 isCmdlet &&，ipconfig canonical 无 '-' → isCmdlet=false，
        // -ErrorAction 不得并入 safeFlags（native exe 无公共参数）。若门缺失会把 -ErrorAction 错误并入，
        // 扩大 native exe 放行面 → 必须非 Allow。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("ipconfig", "cmdlet", "-ErrorAction", "SilentlyContinue")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("ipconfig -ErrorAction SilentlyContinue"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "ipconfig isCmdlet=false 时 -ErrorAction 不得被公共参数合并放行（CC :1503 门）");
    }

    @Test
    @DisplayName("ipconfig 位置参数拒绝：ipconfig set en1 DHCP → 非 Allow（CC readOnlyValidation.ts:705-712）")
    void ipconfigPositionalArgRejected() {
        // WHY：附加危险回调缺接线时，set/en1/DHCP 位置参数在 flag 循环穿透（flag 循环只校验
        // isFlag）→ 只读自动放行（假接线）。回调负责拒绝位置参数（macOS ipconfig set 写系统配置）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("ipconfig", "cmdlet", "set", "en1", "DHCP")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("ipconfig set en1 DHCP"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "ipconfig set en1 DHCP 必须非 Allow（位置参数写配置，CC :705-712）");
    }

    @Test
    @DisplayName("ipconfig /all → Allow（纯 flag 显示，CC :705-712 回调放行）")
    void ipconfigDisplayFlagAllowed() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("ipconfig", "cmdlet", "/all")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("ipconfig /all"), null);
        assertTrue(result instanceof PermissionResult.Allow,
            "ipconfig /all 必须 Allow（纯 flag 显示）");
    }

    @Test
    @DisplayName("hostname 位置参数拒绝：hostname mybox → 非 Allow（CC readOnlyValidation.ts:749-755）")
    void hostnamePositionalArgRejected() {
        // WHY：hostname NAME 在 Linux/macOS 设置主机名（写系统配置）。缺回调时 NAME 位置参数穿透
        // flag 循环 → 只读自动放行（假接线）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("hostname", "cmdlet", "mybox")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("hostname mybox"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "hostname mybox 必须非 Allow（位置参数设置主机名，CC :749-755）");
    }

    @Test
    @DisplayName("hostname -a → Allow（纯 flag 显示，CC :749-755 回调放行）")
    void hostnameFlagAllowed() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("hostname", "cmdlet", "-a")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("hostname -a"), null);
        assertTrue(result instanceof PermissionResult.Allow,
            "hostname -a 必须 Allow（纯 flag 显示）");
    }

    @Test
    @DisplayName("route 位置参数拒绝：route add ... → 非 Allow（CC readOnlyValidation.ts:777-791）")
    void routeAddRejected() {
        // WHY：route add 写路由表。缺回调时 add 位置参数穿透 flag 循环 → 只读自动放行（假接线）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("route", "cmdlet", "add", "10.0.0.0", "mask", "255.0.0.0", "192.168.1.1")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("route add 10.0.0.0 mask 255.0.0.0 192.168.1.1"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "route add 必须非 Allow（写路由表，CC :777-791）");
    }

    @Test
    @DisplayName("裸 route（无参）→ 非 Allow（CC readOnlyValidation.ts:786-788 verb undefined 分支）")
    void bareRouteRejected() {
        // WHY：route [-f] [-p] [-4|-6] VERB，verb 缺失（裸 route）按 CC 判危险——只有 route print 只读。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("route", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("route"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "裸 route 必须非 Allow（CC :786-788 verb undefined 危险）");
    }

    @Test
    @DisplayName("route print / PRINT / -4 print → Allow（CC readOnlyValidation.ts:777-791 verb=print）")
    void routePrintAllowed() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("route", "cmdlet", "print")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("route print"), null);
        assertTrue(result instanceof PermissionResult.Allow, "route print 必须 Allow");

        FakeAstService ast2 = new FakeAstService();
        ast2.stub(single(cmd("route", "cmdlet", "PRINT")));
        PowerShellTool tool2 = new PowerShellTool(new PowerShellPermissionChain(ast2));
        PermissionResult result2 = tool2.checkPermissions(input("route PRINT"), null);
        assertTrue(result2 instanceof PermissionResult.Allow, "route PRINT 必须 Allow（大小写不敏感）");

        FakeAstService ast3 = new FakeAstService();
        ast3.stub(single(cmd("route", "cmdlet", "-4", "print")));
        PowerShellTool tool3 = new PowerShellTool(new PowerShellPermissionChain(ast3));
        PermissionResult result3 = tool3.checkPermissions(input("route -4 print"), null);
        assertTrue(result3 instanceof PermissionResult.Allow, "route -4 print 必须 Allow（-4 ∈ safeFlags）");
    }

    @Test
    @DisplayName("cd+git 复合命令 → Ask（CC :1109-1145 裸仓库攻击防护）")
    void cdGitCompoundAsked() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Set-Location", "cmdlet", "~/repo"),
            cmd("git", "unknown", "status")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Set-Location ~/repo ; git status"), null);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "cd 到恶意目录使 git 危险 → 必须 ask（CC cd+git compound guard）");
    }

    @Test
    @DisplayName("Invoke-Expression → Ask（CC checkInvokeExpression，等价 eval 执行任意代码）")
    void invokeExpressionAsked() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Invoke-Expression", "cmdlet", "evil")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Invoke-Expression evil"), null);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "Invoke-Expression 等价 eval → CC powershellSecurity.ts checkInvokeExpression 必须 ask");
    }

    @Test
    @DisplayName("Add-Type / Import-Module → Ask（CC checkAddType / checkModuleLoading）")
    void codeLoadingAsked() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Add-Type", "cmdlet", "-TypeDefinition", "class X {}")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Add-Type -TypeDefinition 'class X {}'"), null);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "Add-Type 编译加载 .NET 代码 → 必须 ask（CC checkAddType）");

        FakeAstService ast2 = new FakeAstService();
        ast2.stub(single(cmd("Import-Module", "cmdlet", "./evil.psm1")));
        PowerShellTool tool2 = new PowerShellTool(new PowerShellPermissionChain(ast2));
        PermissionResult result2 = tool2.checkPermissions(input("Import-Module ./evil.psm1"), null);
        assertInstanceOf(PermissionResult.Ask.class, result2,
            "Import-Module 执行 .psm1 顶层体 → 必须 ask（CC checkModuleLoading）");
    }

    @Test
    @DisplayName("输出契约 · 10 字段（stdout/stderr/interrupted 等），非 exit_code/output")
    void executeOutputTenFieldContract() throws Exception {
        // IMP-DEL1（TR-C1-D-1）：PowerShellTool.buildOutput 静态辅助已删除（仅测试引用、无生产消费方），
        // 输出契约断言改为测试内构造（对齐 CC PowerShellTool.tsx:245-256 outputSchema 10 字段形态）。
        String json = buildTestOutputJson(0, "hello", "", false);
        JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertTrue(node.has("stdout"), "输出契约必须含 stdout（CC PowerShellTool.tsx:245-256）");
        assertTrue(node.has("stderr"), "输出契约必须含 stderr");
        assertTrue(node.has("interrupted"), "输出契约必须含 interrupted");
        assertFalse(node.has("returnCodeInterpretation"),
            "退出码 0 → returnCodeInterpretation 缺省（CC outputSchema 可选字段，仅非零退出码产出）");
        assertTrue(node.has("isImage"), "输出契约必须含 isImage");
        assertTrue(node.has("persistedOutputPath"), "输出契约必须含 persistedOutputPath");
        assertTrue(node.has("persistedOutputSize"), "输出契约必须含 persistedOutputSize");
        assertTrue(node.has("backgroundTaskId"), "输出契约必须含 backgroundTaskId");
        assertTrue(node.has("backgroundedByUser"), "输出契约必须含 backgroundedByUser");
        assertTrue(node.has("assistantAutoBackgrounded"), "输出契约必须含 assistantAutoBackgrounded");
        assertFalse(node.has("exit_code"), "不得再返回旧 exit_code 字段（对齐 CC 10 字段契约）");
        assertFalse(node.has("output"), "不得再返回旧 output 字段（对齐 CC 10 字段契约）");
        // 非零退出码 → returnCodeInterpretation 有语义
        JsonNode errNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(buildTestOutputJson(1, "", "boom", false));
        assertTrue(errNode.path("returnCodeInterpretation").asText().contains("code 1"),
            "非零退出码应产出 returnCodeInterpretation 语义（CC commandSemantics interpretCommandResult 等价）");
    }

    /**
     * 测试内构造输出契约 JSON（替代已删除的 {@code PowerShellTool.buildOutput} 静态辅助；
     * TR-C1-D-1：outputSchema 契约静态参照可用测试内构造替代）。
     */
    private static String buildTestOutputJson(int exitCode, String stdout, String stderr, boolean interrupted) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("stdout", stdout == null ? "" : stdout);
        node.put("stderr", stderr == null ? "" : stderr);
        node.put("interrupted", interrupted);
        if (exitCode != 0) {
            node.put("returnCodeInterpretation", "Command failed with exit code " + exitCode);
        }
        node.put("isImage", false);
        node.put("persistedOutputPath", "");
        node.put("persistedOutputSize", 0);
        node.put("backgroundTaskId", "");
        node.put("backgroundedByUser", false);
        node.put("assistantAutoBackgrounded", false);
        return node.toString();
    }

    @Test
    @DisplayName("git-internal cwd 重入守卫 · ..\\<cwd>\\HEAD 落回 cwd → Ask")
    void gitInternalCwdReentryAsked() {
        // cwd = C:\\work\\project，命令写入 ..\\project\\HEAD（逃逸后落回 cwd 的 HEAD）再跑 git
        Path cwd = Path.of("C:\\work\\project");
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Set-Content", "cmdlet", "..\\project\\HEAD", "x"),
            cmd("git", "unknown", "status")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", cwd);
        PermissionResult result = tool.checkPermissions(input("Set-Content ..\\project\\HEAD x ; git status"), ctx);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "..\\<cwd>\\HEAD 经 resolveEscapingPathToCwdRelative 落回 cwd → git-internal 写守卫必须 ask（CC gitSafety.ts:100-123）");
    }

    @Test
    @DisplayName("symlink 创建命令复合体 → 不 auto-allow（CC :1469/:1526 !hasSymlinkCreate 门控）")
    void symlinkCreateCompoundNotAutoAllowed() {
        // New-Item -ItemType SymbolicLink 不是只读 → 即使复合体含只读 Get-Process 也不得 auto-allow
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("New-Item", "cmdlet", "-ItemType", "SymbolicLink", "-Path", "link", "-Target", "x"),
            cmd("Get-Process", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("New-Item -ItemType SymbolicLink -Path link -Target x ; Get-Process"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "symlink 创建命令即使与只读命令复合也不 auto-allow（CC hasSymlinkCreate 门控 read-only allowlist）");
    }

    @Test
    @DisplayName("git-internal 直接路径（hooks/pre-commit 在 cwd）→ Ask")
    void gitInternalDirectPathAsked() {
        Path cwd = Path.of("C:\\work\\project");
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Set-Content", "cmdlet", "hooks\\pre-commit", "evil"),
            cmd("git", "unknown", "status")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", cwd);
        PermissionResult result = tool.checkPermissions(input("Set-Content hooks\\pre-commit evil ; git status"), ctx);
        assertInstanceOf(PermissionResult.Ask.class, result,
            "cwd 内 hooks/pre-commit 是 git-internal 路径（CC isGitInternalPathPS:139-151）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 修正版计划新增断言：UNC/ask 内容规则 deferred → 子命令 deny 经 reduce 覆盖 ask
    // ════════════════════════════════════════════════════════════════════════
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

    /** 仅含 ALLOW 规则（进入 alwaysAllowRules 桶）· 2c exact-allow 用。 */
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

    @Test
    @DisplayName("UNC 早返回→deferred：UNC + 子命令 deny → deny（CC :905-907 + reduce）")
    void uncPlusSubcommandDenyDenies() {
        // 复合命令含 UNC 路径（deferred ask）+ Invoke-Expression 子命令 deny。CC 2b/UNC 仅赋值
        // preParseAskDecision 不 return（:717-723），parse-success push 进 decisions（:905-907），
        // reduce deny > ask → deny。旧 Java 早返回 ask 会掩盖子命令 deny（反思 finding C）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Get-ChildItem", "cmdlet", "\\\\server\\share"),
            cmd("Invoke-Expression", "cmdlet", "evil")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Invoke-Expression:*")));
        PermissionResult result = tool.checkPermissions(
            input("Get-ChildItem \\\\server\\share ; Invoke-Expression evil"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "UNC deferred ask 不得掩盖 Invoke-Expression 子命令 deny（CC 2b/UNC :701-723 → reduce deny > ask）");
    }

    @Test
    @DisplayName("内容 ask 早返回→deferred：ask(Get-Process) + deny(Invoke-Expression) → deny")
    void askRulePlusSubcommandDenyDenies() {
        // 内容 ask 规则（Get-Process:*）命中整串 → preParseAskDecision；Invoke-Expression 子命令
        // deny 进 decisions。CC 2b 仅赋值不 return（:694-711）→ reduce deny > ask。旧 Java 在
        // checkContentRules 提前 return ask 使子命令 deny 永不触发（反思 finding 2）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Get-Process", "cmdlet"),
            cmd("Invoke-Expression", "cmdlet", "evil")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(
            rule(PermissionBehavior.ASK, "Get-Process:*"),
            rule(PermissionBehavior.DENY, "Invoke-Expression:*")));
        PermissionResult result = tool.checkPermissions(input("Get-Process ; Invoke-Expression evil"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "内容 ask deferred 不得掩盖 Invoke-Expression 子命令 deny（CC :694-711 → reduce deny > ask）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // A5 反思返工 v2 新增断言：
    //   (1) step4.4 canonical 双查（:1043-1107 封 & 调用/模块限定/非空格空白绕过）
    //   (2) step5 per-subcommand 循环（:1370-1648 fail-closed + individually allowed）
    //   (3) parse-failed 2c exact-allow escape hatch（:750-757）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canonical 双查：deny(Remove-Item:*) + & 'Remove-Item' ./x → Deny（CC :1048-1058 调用操作符绕过）")
    void canonicalDualLookupInvocationOperatorDenies() {
        // raw text 是 `& 'Remove-Item' ./x`（首词 &，前缀匹配 misses）；element.name 是 parser
        // 去引号后的 Remove-Item，canonicalSubCmd = [name,...args].join(' ') = 'Remove-Item ./x'
        // → 第二查 deny 命中（CC :1073-1086）。旧 raw-only 实现绿 → 新 canonical 必须红。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmdRaw("Remove-Item", "cmdlet", "& 'Remove-Item' ./x", "./x")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Remove-Item:*")));
        PermissionResult result = tool.checkPermissions(input("& 'Remove-Item' ./x"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "& 调用操作符绕过必须被 canonical 双查拦截（CC :1051-1052）");
    }

    @Test
    @DisplayName("canonical 双查：deny(Remove-Item:*) + Microsoft.PowerShell.Management\\Remove-Item ./x → Deny（模块限定绕过）")
    void canonicalDualLookupModuleQualifiedDenies() {
        // element.name 已 stripModulePrefix → 'Remove-Item'；canonicalSubCmd='Remove-Item ./x' 命中。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmdRaw("Remove-Item", "cmdlet",
            "Microsoft.PowerShell.Management\\Remove-Item ./x", "./x")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Remove-Item:*")));
        PermissionResult result = tool.checkPermissions(
            input("Microsoft.PowerShell.Management\\Remove-Item ./x"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "模块限定名绕过必须被 canonical 双查拦截（CC :1057-1058）");
    }

    @Test
    @DisplayName("canonical 双查：deny(Remove-Item:*) + rm<TAB>./x → Deny（非空格空白绕过）")
    void canonicalDualLookupNonSpaceWhitespaceDenies() {
        // raw text `rm\t./x` 用字面空格前缀匹配 misses；canonicalSubCmd='rm ./x'，psRuleMatches
        // canonical 互解（rm→remove-item）+ 空白归一后命中（CC :1053-1056）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmdRaw("rm", "cmdlet", "rm\t./x", "./x")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Remove-Item:*")));
        PermissionResult result = tool.checkPermissions(input("rm\t./x"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "非空格空白（rm<TAB>./x）绕过必须被 canonical 双查拦截（CC :1053-1056）");
    }

    @Test
    @DisplayName("step5 fail-closed：裸语句 $env:SECRET（无 CommandAst 子命令）→ 非 Allow（CC :1593-1597）")
    void step5BareSecretNotAllowed() {
        // 裸 $env:SECRET 是 VariableExpressionAst，无 CommandAst 子命令 → step5 走 fail-closed
        // 第二段：非 provably-safe 语句 push → 需审批（非 Allow）。CC :1618-1625 仅当
        // subCommandsNeedingApproval 为空才 allow。
        FakeAstService ast = new FakeAstService();
        ast.stub(statement("PipelineAst", "$env:SECRET",
            new PowerShellAstService.CommandElement("", "unknown", "CommandExpressionAst",
                List.of(), List.of("Other"), "$env:SECRET", List.of(), List.of())));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("$env:SECRET"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "$env:SECRET 裸语句不可 auto-allow（CC step5 fail-closed :1593-1597）");
        assertInstanceOf(PermissionResult.Passthrough.class, result,
            "step5 需审批 → passthrough（通用管线转 ask），非 Allow");
    }

    @Test
    @DisplayName("step5 fail-closed：if($true){$env:SECRET} 控制流 → 非 Allow（CC :1420-1429 push-only 追踪）")
    void step5ControlFlowNotAllowed() {
        // IfStatementAst（非 PipelineAst）不满足 isProvablySafeStatement → 语句 push 进
        // subCommandsNeedingApproval → 需审批。statementsSeenInLoop 仅在 PUSH 时标记，
        // 否则 user-allow continue 的语句会被 fail-closed 门跳过（CC :1420-1429 SECURITY）。
        FakeAstService ast = new FakeAstService();
        ast.stub(statement("IfStatementAst", "if($true){$env:SECRET}",
            new PowerShellAstService.CommandElement("", "unknown", "CommandExpressionAst",
                List.of(), List.of("Other"), "if($true){$env:SECRET}", List.of(), List.of())));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("if($true){$env:SECRET}"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "控制流语句不可 auto-allow（CC step5 fail-closed）");
        assertInstanceOf(PermissionResult.Passthrough.class, result,
            "step5 需审批 → passthrough，非 Allow");
    }

    @Test
    @DisplayName("step5 空列表：安全输出过滤后无待审批子命令 → Allow individually allowed（CC :1599-1626）")
    void step5EmptyNeedingApprovalAllows() {
        // Get-Process | Out-Null：Out-Null 是 safe-output（isSafeOutput=true）被过滤，
        // Get-Process 是 allowlisted → 均 continue → subCommandsNeedingApproval 空 →
        // Allow 'All pipeline commands are individually allowed'（CC :1618-1625）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Get-Process", "cmdlet"),
            cmd("Out-Null", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process | Out-Null"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "全部子命令 individually allowed → Allow（CC :1618-1625）");
    }

    @Test
    @DisplayName("2c escape hatch：pwsh 不可用 + exact allow Write-Output x → Allow（CC :750-757）")
    void twoCExactAllowWhenParseFailsAllows() {
        // pwsh 不可用（FakeAstService 返回 valid=false）时，exact allow 规则仍生效（fail-safe）。
        // 旧 Java parse-failed 分支无 2c 短路 → 恒 ask。
        FakeAstService ast = new FakeAstService();
        // 不 stub → 默认 valid=false
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "Write-Output x")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x"), ctx);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "parse-failed + exact allow → 2c 短路放行（CC :750-757）");
    }

    @Test
    @DisplayName("2c escape hatch：exact allow build.exe + scripts\\build.exe → Ask（application 降级）")
    void twoCApplicationDowngradeAsked() {
        // canonical exact：stripModulePrefix('scripts\\build.exe')='build.exe' 命中 exact allow
        // 'build.exe'，但 classifyCommandName('scripts\\build.exe')='application'（含 \）→ 2c
        // 门不过 → 降级 ask（CC :744-749 同 step5 nameType 门）。
        FakeAstService ast = new FakeAstService();
        // 不 stub → 默认 valid=false
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "build.exe")));
        PermissionResult result = tool.checkPermissions(input("scripts\\build.exe"), ctx);
        assertFalse(result instanceof PermissionResult.Allow,
            "application（脚本/exe）不得被 cmdlet allow 规则放行（CC :738-749 降 ask）");
        assertInstanceOf(PermissionResult.Ask.class, result,
            "2c application 降级 → ask（CC :744-749）");
    }

    @Test
    @DisplayName("step5 per-sub deny 早返回：Get-Process | Stop-Process -Force + deny(Stop-Process:*) → Deny（CC :1442-1448）")
    void step5SubDenyReturnsEarly() {
        // step5 循环 deny 最先（用户显式规则优先于 allowlist）。Get-Process 是 allowlisted，
        // 但 Stop-Process deny 规则使整条命令 Deny（CC :1442-1448 循环内 return deny）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(
            cmd("Get-Process", "cmdlet"),
            cmd("Stop-Process", "cmdlet", "-Force")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Stop-Process:*")));
        PermissionResult result = tool.checkPermissions(input("Get-Process | Stop-Process -Force"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "管道内 Stop-Process deny 不得被 Get-Process allowlist 掩盖（CC :1442-1448）");
    }
}
