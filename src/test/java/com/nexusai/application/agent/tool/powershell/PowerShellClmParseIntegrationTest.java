package com.nexusai.application.agent.tool.powershell;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * parse 路径集成测试（RC-4/C2）：意图 = CLM 安全校验在生产解析路径上的真实可达性。
 *
 * <p>不直接构造 record（PowerShellToolPermissionTest 等既有测试已覆盖纯构造层），本类真走
 * {@code parseJsonOutput → parseStatement → transformCommandAst}，以真实 PS 5.1 parse-script.ps1
 * 输出（88f212ef 同源，本环境 powershell.exe 实跑捕获）为 fixture，证明 RC-1（parseStatement 读
 * elements 而非 commands）/ RC-2（children 按 array-of-object 经 mapElementType 归一）修复后：
 * checkStartProcess -Verb 冒号结构层、checkComObject -TypeName 三路、checkTypeLiterals 均非死代码。
 *
 * <p>CC 校验器顺序（powershellSecurity.ts validators :1054-1079）：checkMemberInvocations(:1073)
 * 紧邻 checkTypeLiterals(:1074) 且在前——故 {@code [Reflection.Assembly]::Load('x')}（成员调用 +
 * 类型字面量）先命中成员调用 ask（含类型名的 checkTypeLiterals 消息须用无成员调用的纯类型字面量
 * {@code [System.Net.WebClient]} 验证）。
 */
class PowerShellClmParseIntegrationTest {

    private static final PowerShellAstService SERVICE = new PowerShellAstService();

    /** 真走 parseJsonOutput（package-private 测试入口，与生产 parseAst 同一变换路径）。 */
    private static PowerShellAstService.ParsedResult parse(String json, String original) {
        return SERVICE.parseJsonOutput(json, original);
    }

    // ════════════════════════════════════════════════════════════════════════
    // RC-1 + RC-2：checkStartProcess -Verb 冒号结构层（children array-of-object 映射）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Start-Process -Verb:RunAs notepad → children 映射为 StringConstant/RunAs，结构层 (a) 命中")
    void startProcessVerbColonStructuralLayerReachable() {
        // 真实 PS5.1 输出（powershell.exe 实跑捕获）：statements[].elements[].commandElements，
        // -Verb:RunAs 为 CommandParameterAst 且 children=[{type:StringConstantExpressionAst,text:'RunAs'}]。
        String json = """
            {"originalCommand":"Start-Process -Verb:RunAs notepad","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"redirections":[],"commandElements":[{"value":"Start-Process","type":"StringConstantExpressionAst","text":"Start-Process"},{"children":[{"text":"RunAs","type":"StringConstantExpressionAst"}],"type":"CommandParameterAst","text":"-Verb:RunAs"},{"value":"notepad","type":"StringConstantExpressionAst","text":"notepad"}],"type":"CommandAst","text":"Start-Process -Verb:RunAs notepad"}],"type":"PipelineAst","text":"Start-Process -Verb:RunAs notepad"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":[]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "Start-Process -Verb:RunAs notepad");
        assertTrue(parsed.valid(), "真实 PS1 JSON 应解析为 valid");

        // RC-1：parseStatement 从 elements 构建 commands（raw 无 commands 键，旧代码恒空列表）
        List<PowerShellAstService.CommandElement> commands = parsed.statements().get(0).commands();
        assertEquals(1, commands.size(), "命令级 validator 必须收到真实命令（非空列表）");
        PowerShellAstService.CommandElement c = commands.get(0);
        assertEquals("Start-Process", c.name());
        assertEquals("cmdlet", c.nameType());
        assertEquals(List.of("-Verb:RunAs", "notepad"), c.args());
        assertEquals(List.of("StringConstant", "Parameter", "StringConstant"), c.elementTypes());

        // RC-2：children 按 array-of-object 经 mapElementType 归一（raw 'StringConstantExpressionAst'
        // → 'StringConstant'），与 args[0] 对齐 —— 旧代码 isArray 误判导致 kids 恒空（结构层死代码）
        List<List<PowerShellAstService.CommandElementChild>> children = c.children();
        assertEquals(2, children.size(), "children 与 args 对齐（2 个 arg）");
        assertEquals(1, children.get(0).size(), "args[0]('-Verb:RunAs') 应有 1 个子节点");
        assertEquals("StringConstant", children.get(0).get(0).type(),
            "child.type 必须经 mapElementType 归一，否则 'StringConstantExpressionAst' 不命中");
        assertEquals("RunAs", children.get(0).get(0).text());
        assertTrue(children.get(1).isEmpty(), "args[1]('notepad') 无 colon 子节点");

        // 结构层 (a) 命中：findAskMessage 返回提权 ask（证明 checkStartProcessVerbColon 非死代码）
        String msg = PowerShellCommandSafety.findAskMessage(parsed);
        assertNotNull(msg, "结构层 (a) 必须命中");
        assertTrue(msg.contains("提升权限"), "消息应含提权语义，实际: " + msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkTypeLiterals：typeLiterals 提取 → 非 CLM ask（CC 校验器顺序修正）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[Reflection.Assembly]::Load('x') → typeLiterals 提取 + 成员调用 ask 先命中（CC 顺序）")
    void memberInvocationFiresBeforeTypeLiterals() {
        // 真实输出：typeLiterals=['Reflection.Assembly'] + securityPatterns.hasMemberInvocations=true。
        // CC validators 顺序 checkMemberInvocations(:1073) 先于 checkTypeLiterals(:1074)，
        // 故消息为成员调用 ask 而非类型名 ask —— 计划断言 #2 以此修正（以 CC 源码为准）。
        String json = """
            {"originalCommand":"[Reflection.Assembly]::Load('x')","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"securityPatterns":{"hasMemberInvocations":true},"elements":[{"expressionType":"InvokeMemberExpressionAst","redirections":[],"type":"CommandExpressionAst","text":"[Reflection.Assembly]::Load('x')"}],"type":"PipelineAst","text":"[Reflection.Assembly]::Load('x')"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":["Reflection.Assembly"]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "[Reflection.Assembly]::Load('x')");
        assertTrue(parsed.valid());
        assertEquals(List.of("Reflection.Assembly"), parsed.typeLiterals(),
            "typeLiterals 提取必须到达 ParsedResult（生产可达）");
        assertTrue(parsed.hasMemberInvocations(), "InvokeMemberExpressionAst → hasMemberInvocations");
        assertNotNull(PowerShellCommandSafety.findAskMessage(parsed),
            "成员调用 → ask（CC 校验器顺序 :1073 先于 :1074）");
    }

    @Test
    @DisplayName("[System.Net.WebClient]（纯类型字面量）→ checkTypeLiterals ask 且消息含类型名")
    void pureTypeLiteralHitsCheckTypeLiterals() {
        // 无成员调用/无脚本块/无子表达式的纯类型字面量 → 仅 checkTypeLiterals 命中，
        // 消息必须含类型名（证明 typeLiterals 生产可达 checkTypeLiterals）。
        String json = """
            {"originalCommand":"[System.Net.WebClient]","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"expressionType":"TypeExpressionAst","redirections":[],"type":"CommandExpressionAst","text":"[System.Net.WebClient]"}],"type":"PipelineAst","text":"[System.Net.WebClient]"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":["System.Net.WebClient"]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "[System.Net.WebClient]");
        assertTrue(parsed.valid());
        assertEquals(List.of("System.Net.WebClient"), parsed.typeLiterals());
        assertFalse(parsed.hasMemberInvocations(), "TypeExpressionAst 非成员调用");
        String msg = PowerShellCommandSafety.findAskMessage(parsed);
        assertNotNull(msg, "CLM 外类型字面量必须 ask");
        assertTrue(msg.contains("System.Net.WebClient"),
            "checkTypeLiterals 消息应含类型名，实际: " + msg);
    }

    @Test
    @DisplayName("[string[]]/[int] 等 CLM 白名单内类型字面量 → 不 ask（CLM 集合生产生效）")
    void clmAllowedTypeLiteralNoAsk() {
        String json = """
            {"originalCommand":"[int]5","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"expressionType":"TypeExpressionAst","redirections":[],"type":"CommandExpressionAst","text":"[int]5"}],"type":"PipelineAst","text":"[int]5"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":["int"]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "[int]5");
        assertNull(PowerShellCommandSafety.findAskMessage(parsed),
            "CLM 白名单内类型字面量不触发 ask（139 集合生产生效）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkComObject -TypeName 三路（colon / positional / space）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("New-Object -TypeName:System.Net.WebClient（colon）→ checkComObject -TypeName ask")
    void comObjectTypeNameColonAsk() {
        String json = """
            {"originalCommand":"New-Object -TypeName:System.Net.WebClient","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"redirections":[],"commandElements":[{"value":"New-Object","type":"StringConstantExpressionAst","text":"New-Object"},{"children":[{"text":"System.Net.WebClient","type":"StringConstantExpressionAst"}],"type":"CommandParameterAst","text":"-TypeName:System.Net.WebClient"}],"type":"CommandAst","text":"New-Object -TypeName:System.Net.WebClient"}],"type":"PipelineAst","text":"New-Object -TypeName:System.Net.WebClient"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":[]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "New-Object -TypeName:System.Net.WebClient");
        assertTrue(parsed.valid());
        PowerShellAstService.CommandElement c = parsed.statements().get(0).commands().get(0);
        assertEquals("New-Object", c.name());
        assertEquals(List.of("-TypeName:System.Net.WebClient"), c.args());
        assertEquals("Parameter", c.elementTypes().get(1), "colon 参数须映射为 Parameter");
        String msg = PowerShellCommandSafety.findAskMessage(parsed);
        assertNotNull(msg, "-TypeName colon 三路提取须命中 checkComObject");
        assertTrue(msg.contains("System.Net.WebClient"), "消息应含越界类型名，实际: " + msg);
    }

    @Test
    @DisplayName("New-Object System.Net.WebClient（positional）→ checkComObject -TypeName ask")
    void comObjectTypeNamePositionalAsk() {
        String json = """
            {"originalCommand":"New-Object System.Net.WebClient","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"redirections":[],"commandElements":[{"value":"New-Object","type":"StringConstantExpressionAst","text":"New-Object"},{"value":"System.Net.WebClient","type":"StringConstantExpressionAst","text":"System.Net.WebClient"}],"type":"CommandAst","text":"New-Object System.Net.WebClient"}],"type":"PipelineAst","text":"New-Object System.Net.WebClient"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":[]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "New-Object System.Net.WebClient");
        assertTrue(parsed.valid());
        assertNotNull(PowerShellCommandSafety.findAskMessage(parsed),
            "-TypeName positional-0 三路提取须命中 checkComObject");
    }

    @Test
    @DisplayName("New-Object -TypeName string → CLM 白名单内，checkComObject 不 ask")
    void comObjectTypeNameClmAllowedNoAsk() {
        String json = """
            {"originalCommand":"New-Object -TypeName string","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"redirections":[],"commandElements":[{"value":"New-Object","type":"StringConstantExpressionAst","text":"New-Object"},{"text":"-TypeName","type":"CommandParameterAst"},{"value":"string","type":"StringConstantExpressionAst","text":"string"}],"type":"CommandAst","text":"New-Object -TypeName string"}],"type":"PipelineAst","text":"New-Object -TypeName string"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":[]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "New-Object -TypeName string");
        assertTrue(parsed.valid());
        assertNull(PowerShellCommandSafety.findAskMessage(parsed),
            "'string' 在 CLM 白名单内（139 集合），-TypeName 分支不应 ask");
    }

    @Test
    @DisplayName("New-Object -ComObject WScript.Shell → -ComObject 分支 ask")
    void comObjectComObjectAsk() {
        String json = """
            {"originalCommand":"New-Object -ComObject WScript.Shell","hasStopParsing":false,"variables":[],"errors":[],"statements":[{"elements":[{"redirections":[],"commandElements":[{"value":"New-Object","type":"StringConstantExpressionAst","text":"New-Object"},{"text":"-ComObject","type":"CommandParameterAst"},{"value":"WScript.Shell","type":"StringConstantExpressionAst","text":"WScript.Shell"}],"type":"CommandAst","text":"New-Object -ComObject WScript.Shell"}],"type":"PipelineAst","text":"New-Object -ComObject WScript.Shell"}],"hasUsingStatements":false,"hasScriptRequirements":false,"valid":true,"typeLiterals":[]}
            """.strip();
        PowerShellAstService.ParsedResult parsed = parse(json, "New-Object -ComObject WScript.Shell");
        assertTrue(parsed.valid());
        assertNotNull(PowerShellCommandSafety.findAskMessage(parsed),
            "-ComObject 分支须命中 checkComObject");
    }

    // ════════════════════════════════════════════════════════════════════════
    // RC-3：真实 parseAst 冒烟（资源已恢复，本环境 powershell.exe 可用）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseAst 真实冒烟：classpath 含 parse-script.ps1 时 valid 且 commands 非空")
    void parseAstSmokeWithRestoredResource() {
        Assumptions.assumeTrue(powerShellAvailable(),
            "环境无 PowerShell，跳过真实 parseAst 冒烟（资源已恢复，生产可达性由上方 JSON 集成用例覆盖）");
        PowerShellAstService.ParsedResult parsed =
            new PowerShellAstService().parseAst("Start-Process -Verb:RunAs notepad");
        assertTrue(parsed.valid(), "资源恢复后 parseAst 应产出有效 JSON，errors=" + parsed.errors());
        assertFalse(parsed.statements().isEmpty(), "应解析出语句");
        assertFalse(parsed.statements().get(0).commands().isEmpty(),
            "statements[0].commands() 非空（RC-1 后 parseStatement 从 elements 构建）");
        assertNotNull(PowerShellCommandSafety.findAskMessage(parsed),
            "真实解析链上 checkStartProcess 冒号结构层命中");
    }

    private static boolean powerShellAvailable() {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-NoLogo", "-Command", "$true").redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
