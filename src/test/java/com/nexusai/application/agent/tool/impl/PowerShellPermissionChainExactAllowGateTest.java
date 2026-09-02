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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R39 整命令精确 allow 门（parse-success）测试 · 对齐 CC powershellPermissions.ts:1281-1316。
 *
 * <p>WHY（意图验证）：
 * <ul>
 *   <li>parse-success 下，整命令精确 allow 规则须入 decisions[]（无 deny/ask 时经 reduce 浮现
 *       Allow）——没有此门，非只读命令的精确 allow 会落 step5 passthrough，用户配置的精确
 *       allow 规则失效。</li>
 *   <li>nameType=application 门：脚本/exe 路径（含 \ 或 .）不得被 cmdlet 精确 allow 规则放行
 *       （CC :1300-1305 finding #10，防本地 .ps1 冒名执行）。</li>
 *   <li>argLeaksValue 门：Write-Output $env:X 等泄漏变量值不得被精确 allow 放行（CC :1293-1298
 *       finding #32，防 API key 泄漏）。</li>
 * </ul>
 *
 * <p>pwsh 运行时不可用（本机/CI）时用 {@link GateAstService} 桩注入可控 ParsedResult（不依赖
 * 真实 pwsh），只测 R39 门逻辑。
 */
class PowerShellPermissionChainExactAllowGateTest {

    /** 可控 AST 桩：不启动 pwsh，返回预置 ParsedResult。 */
    static final class GateAstService extends PowerShellAstService {
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

    /** 普通命令元素（args 全 StringConstant，不泄漏）。 */
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

    /** 泄漏值命令元素：首个参数 elementType=Variable（argLeaksValue 触发，CC readOnlyValidation.ts:76-115）。 */
    private static PowerShellAstService.CommandElement leakyCmd(String name, String nameType, String arg) {
        List<String> elementTypes = List.of("StringConstant", "Variable");
        String text = name + " " + arg;
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(arg), elementTypes, text, List.of(), List.of());
    }

    /** 单语句 PipelineAst，含多个 CommandAst 命令（复合体）。 */
    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        StringBuilder text = new StringBuilder(cmds[0].text());
        for (int i = 1; i < cmds.length; i++) {
            text.append(" ; ").append(cmds[i].text());
        }
        stmts.add(new PowerShellAstService.Statement("PipelineAst", text.toString(), List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false, false,
            false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
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

    /** 仅含 ALLOW 规则（进入 alwaysAllowRules 桶）· R39 exact-allow 用。 */
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
    @DisplayName("R39（a）整命令精确 allow 复合体 · 非只读命令 Get-Command; Get-Alias → Allow（CC :1281-1316）")
    void exactAllowGateAllowsNonReadOnlyCompound() {
        // Get-Command 不在 CMDLET_ALLOWLIST（非只读），Get-Alias 是只读。复合体整体非只读
        // （isReadOnlyCommand 因 Get-Command 不 allowlisted 返回 false）。没有 R39 门时该命令
        // 会落 step5 passthrough 而非 Allow；R39 门把整命令精确 allow 入 decisions[] → Allow。
        GateAstService ast = new GateAstService();
        ast.stub(single(cmd("Get-Command", "cmdlet"), cmd("Get-Alias", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "Get-Command; Get-Alias")));
        PermissionResult result = tool.checkPermissions(input("Get-Command; Get-Alias"), ctx);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "非只读复合命令的整命令精确 allow 必须经 R39 门放行（无此门则 step5 passthrough）");
    }

    @Test
    @DisplayName("R39（b）任一子命令 nameType=application → 不放行（CC :1300-1305 finding #10）")
    void exactAllowGateBlocksApplicationNameType() {
        // exact allow 规则命中 'Get-Command'，但 AST 元素 nameType=application（脚本/exe 路径，
        // 含 \ 或 . 冒名 cmdlet）。R39 nameType 门必须拦截：PowerShell 会执行本地 .ps1 文件。
        GateAstService ast = new GateAstService();
        ast.stub(single(cmd("Get-Command", "application")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "Get-Command")));
        PermissionResult result = tool.checkPermissions(input("Get-Command"), ctx);
        assertFalse(result instanceof PermissionResult.Allow,
            "nameType=application 的脚本/路径不得被 cmdlet 精确 allow 规则放行（CC :1300-1305）");
    }

    @Test
    @DisplayName("R39（c）任一子命令 argLeaksValue → 不放行（CC :1293-1298 finding #32）")
    void exactAllowGateBlocksArgLeaks() {
        // Write-Output $env:X 精确 allow 规则命中，但参数 elementType=Variable（泄漏变量值）。
        // R39 argLeaksValue 门必须拦截：用户 allow Write-Output:* 未意图 auto-allow 泄漏 $env:X。
        GateAstService ast = new GateAstService();
        ast.stub(single(leakyCmd("Write-Output", "cmdlet", "$env:X")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "Write-Output $env:X")));
        PermissionResult result = tool.checkPermissions(input("Write-Output $env:X"), ctx);
        assertFalse(result instanceof PermissionResult.Allow,
            "argLeaksValue 的变量泄漏不得被精确 allow 放行（CC :1293-1298 finding #32）");
    }
}
