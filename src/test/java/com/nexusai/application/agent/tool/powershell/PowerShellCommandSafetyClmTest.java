package com.nexusai.application.agent.tool.powershell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellCommandSafety CLM 安全单测 · 对齐 CC {@code clmTypes.ts} + {@code powershellSecurity.ts}
 * 的 checkTypeLiterals / checkComObject -TypeName / checkStartProcess -Verb RunAs 冒号语法。
 *
 * <p>WHY（意图验证）：
 * <ul>
 *   <li>CLM 白名单是"不安全 .NET 类型"的单一权威边界，取代枚举单个危险类型（Reflection/Process/Marshal）</li>
 *   <li>checkComObject 的 -TypeName 经 StringConstantExpressionAst（非 TypeExpressionAst），CLM 不自动触发，
 *       必须独立三路提取（colon/space/positional-0）</li>
 *   <li>checkStartProcess 的 -Verb:RunAs 冒号语法可被引号/反引号绕过旧 space-only 检测（bug #14）</li>
 * </ul>
 */
class PowerShellCommandSafetyClmTest {

    /** 可控命令元素：elementTypes[0]=StringConstant 避免 checkDynamicCommandName 抢先 ask。 */
    private static PowerShellAstService.CommandElement cmd(String name, String nameType, String... args) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        for (int i = 0; i < args.length; i++) {
            elementTypes.add("Parameter");
        }
        String text = name + (args.length > 0 ? " " + String.join(" ", args) : "");
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
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

    // ---- normalizeTypeName / isClmAllowedType ----

    @Test
    @DisplayName("normalizeTypeName 去数组后缀/泛型实参/空白并大小写折叠（CC clmTypes.ts:194-203）")
    void normalizeTypeNameFolds() {
        assertEquals("string", PowerShellCommandSafety.normalizeTypeName("String[]"));
        assertEquals("list", PowerShellCommandSafety.normalizeTypeName("List[int]"));
        assertEquals("system.net.webclient", PowerShellCommandSafety.normalizeTypeName("System.Net.WebClient"));
        assertEquals("int", PowerShellCommandSafety.normalizeTypeName(" INT "));
    }

    @Test
    @DisplayName("isClmAllowedType 白名单边界：CLM 内放行，反射/网络/进程类型拒绝")
    void isClmAllowedTypeBoundary() {
        assertTrue(PowerShellCommandSafety.isClmAllowedType("int"));
        assertTrue(PowerShellCommandSafety.isClmAllowedType("System.String"));
        assertTrue(PowerShellCommandSafety.isClmAllowedType("string[]"));
        assertTrue(PowerShellCommandSafety.isClmAllowedType("object"));
        assertFalse(PowerShellCommandSafety.isClmAllowedType("System.Net.WebClient"));
        assertFalse(PowerShellCommandSafety.isClmAllowedType("Reflection.Assembly"));
        assertFalse(PowerShellCommandSafety.isClmAllowedType("System.Diagnostics.Process"));
        // 安全剔除项不得在 CLM 内（CC clmTypes.ts 移除 adsi/wmi/cimsession）
        assertFalse(PowerShellCommandSafety.isClmAllowedType("adsi"));
        assertFalse(PowerShellCommandSafety.isClmAllowedType("wmi"));
        assertFalse(PowerShellCommandSafety.isClmAllowedType("cimsession"));
    }

    // ---- checkTypeLiterals ----

    @Test
    @DisplayName("checkTypeLiterals：[Reflection.Assembly] 越界 CLM → ask 且消息含类型名（CC :800-813）")
    void typeLiteralOutsideClmAsks() {
        String msg = PowerShellCommandSafety.findAskMessage(parsed(List.of("Reflection.Assembly")));
        assertNotNull(msg, "越界类型字面量必须 ask");
        assertTrue(msg.contains("Reflection.Assembly"), "消息应包含越界类型名");
    }

    @Test
    @DisplayName("checkTypeLiterals：[int] 在 CLM 内 → 不因类型字面量命中 ask")
    void typeLiteralInsideClmPasses() {
        assertNull(PowerShellCommandSafety.findAskMessage(parsed(List.of("int"))));
    }

    // ---- checkComObject -TypeName ----

    @Test
    @DisplayName("checkComObject -TypeName 三路提取：positional/space/colon 越界类型均 ask")
    void newObjectTypeNameOutsideClmAsks() {
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "System.Net.WebClient"))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "-TypeName", "System.Net.WebClient"))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "-TypeName:System.Net.WebClient"))));
    }

    @Test
    @DisplayName("checkComObject：CLM 内 -TypeName 放行；-ComObject 仍命中 COM 分支")
    void newObjectTypeNameInsideClmOrComObject() {
        assertNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "-TypeName", "string"))));
        String com = PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("New-Object", "cmdlet", "-ComObject", "WScript.Shell")));
        assertNotNull(com, "-ComObject 仍应命中 COM 分支");
        assertTrue(com.contains("COM"), "消息应指向 COM 对象");
    }

    // ---- checkStartProcess 冒号语法 ----

    @Test
    @DisplayName("checkStartProcess -Verb:RunAs 冒号语法（正则兜底 + 引号/反引号绕过）→ ask")
    void startProcessVerbColonAsks() {
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-Verb:RunAs"))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-Verb:\"RunAs\""))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-Verb:`runas"))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-V`erb:RunAs"))));
    }

    @Test
    @DisplayName("checkStartProcess -Verb RunAs space 语法既有路径仍绿")
    void startProcessVerbSpaceStillAsks() {
        assertNotNull(PowerShellCommandSafety.findAskMessage(
            parsed(List.of(), cmd("Start-Process", "cmdlet", "-Verb", "RunAs"))));
    }

    @Test
    @DisplayName("checkStartProcess 结构层：children 引号绑定值归一化后 runas → ask（CC :580-610 a）")
    void startProcessVerbColonStructuralChildren() {
        PowerShellAstService.CommandElement c = new PowerShellAstService.CommandElement(
            "start-process", "cmdlet", "CommandAst",
            List.of("-Verb:RunAs"), List.of("StringConstant", "Parameter"),
            "Start-Process -Verb:RunAs", List.of(),
            List.of(List.of(new PowerShellAstService.CommandElementChild("StringConstant", "'RunAs'"))));
        assertNotNull(PowerShellCommandSafety.findAskMessage(parsed(List.of(), c)),
            "结构层 children 引号绑定值归一化后应命中 runas");
    }
}
