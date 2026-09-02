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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellPathValidator 测试 · 对齐 CC pathValidation.ts checkPathConstraints 核心不变量。
 *
 * <p>WHY：修正版计划把危险删除 deny 从 pre-parse 移入 parse-failed 片段循环 + parse-succeeded 的
 * checkPathConstraints（pathValidation.ts:1735/1850）——本测试验证 parse-success 危险删除 deny、
 * Edit deny 规则、cd 复合 ask、unvalidatable ask、只读 passthrough 五条路径。
 */
class PowerShellPathValidatorTest {

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

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    private static ToolPermissionContext permCtx(PermissionRule... rules) {
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> deny = new java.util.HashMap<>();
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> ask = new java.util.HashMap<>();
        for (PermissionRule r : rules) {
            (r.ruleBehavior() == PermissionBehavior.DENY ? deny : ask)
                .computeIfAbsent(PermissionRuleSource.SESSION, k -> new java.util.HashSet<>()).add(r);
        }
        return new ToolPermissionContext(PermissionMode.DEFAULT, java.util.Map.of(), deny, ask,
            java.util.Map.of(), false, false, java.util.Map.of(), false, false, null);
    }

    private static PermissionRule editDeny(String glob) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            new PermissionRuleValue("Edit", glob));
    }

    @Test
    @DisplayName("remove-item 危险路径（/）→ Deny（pathValidation.ts:1735 原始路径检查）")
    void dangerousRemovalDenied() {
        JsonNode in = input("Remove-Item / -Recurse -Force");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Remove-Item", "cmdlet", "/", "-Recurse", "-Force")),
            permCtx(), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Remove-Item / 在 parse-success 下必须硬 deny（isDangerousRemovalRawPath('/')）");
    }

    @Test
    @DisplayName("remove-item 家目录（~）→ Deny")
    void dangerousRemovalHomeDenied() {
        JsonNode in = input("Remove-Item ~");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Remove-Item", "cmdlet", "~")),
            permCtx(), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Remove-Item ~ 展开家目录后必须硬 deny");
    }

    @Test
    @DisplayName("Edit deny 规则命中路径 → Deny（CC :1765-1771）")
    void editDenyRuleDenies() {
        JsonNode in = input("Set-Content /etc/hosts x");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Content", "cmdlet", "/etc/hosts", "x")),
            permCtx(editDeny("//etc/**")), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Edit(//etc/**) deny 规则（// 前缀 = 文件系统根 root-relative，对齐 BashPathValidatorTest.editDenyRuleDenies）"
                + "命中 Set-Content /etc/hosts → 必须 deny");
    }

    @Test
    @DisplayName("cd 复合 + 路径操作 → Ask（compoundCommandHasCd 门控，pathValidation.ts:1606-1617）")
    void compoundCdAsk() {
        JsonNode in = input("Set-Location ./x ; Get-Content ./secret");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Location", "cmdlet", "./x"), cmd("Get-Content", "cmdlet", "./secret")),
            permCtx(), Path.of("C:/work/project"), true);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "cd 复合命令内路径操作因 cwd 漂移必须 ask（CC BashTool parity）");
    }

    @Test
    @DisplayName("写操作无目标路径 → Ask（CC :1709-1721 write-zero-paths）")
    void writeNoPathAsk() {
        JsonNode in = input("Set-Content");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Content", "cmdlet")),
            permCtx(), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "Set-Content 写操作但无法确定目标路径 → 必须 ask");
    }

    @Test
    @DisplayName("只读路径在工作目录内 → passthrough（无 deny/ask）")
    void readOnlyPassthrough() {
        JsonNode in = input("Get-Content ./file.txt");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Get-Content", "cmdlet", "./file.txt")),
            permCtx(), Path.of("C:/work/project"), false);
        assertTrue(r instanceof PermissionResult.Passthrough
                || r instanceof PermissionResult.Allow,
            "工作目录内只读路径 → 不应 deny（passthrough 由调用方 reduce 决定）");
    }
}
