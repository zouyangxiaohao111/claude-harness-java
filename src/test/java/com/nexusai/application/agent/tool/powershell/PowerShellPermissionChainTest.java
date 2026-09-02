package com.nexusai.application.agent.tool.powershell;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMP-PS1 聚焦测试 · PowerShell colon-bound P0 修复 + 安全链（P0-2 + 组 1-4 C）。
 *
 * <p>WHY（意图验证）：
 * <ul>
 *   <li><b>colon-bound P0（REQ-P0-2 / C76）</b>：{@code Get-Process -Name:$env:SECRET} 的
 *       {@code -Name:$env:SECRET} 是单个 CommandParameterAst，其 .Argument（VariableExpressionAst）
 *       是 children 子节点而非独立 CommandElement → 旧 isAllowlistedCommand 的 elementTypes 白名单
 *       把 Parameter 放过 → 只读链净自动放行（secret 泄漏向量）。修复后必须非 Allow（走 ask/
 *       passthrough）。</li>
 *   <li><b>CLIXML fail-closed（REQ-G1-4-4 / TR-C1-D-4）</b>：移除头剥离后 CLIXML 前缀使 JSON 解析
 *       失败 → valid=false → 权限链降级 ask（CC fail-closed，不剥离头）。</li>
 *   <li><b>hasAssignments 消息对齐 CC（REQ / TR-C2-⊕-C2-1）</b>：findAskMessage 不再返回 Java-only
 *       赋值专属消息；赋值语句经 step5 fail-closed 仍 ask（net ask 相同，仅消息归因对齐 CC）。</li>
 *   <li><b>isPathAllowed 内部路径接入权限链（REQ-G1-4-5 / TR-C2-Q2）</b>：agent-memory/auto-memory/
 *       bundled-skills 内部可读/可编辑路径经 carve-out 放行（CC isPathAllowed step2/step3.5），
 *       工作目录外不再误 ask。</li>
 * </ul>
 *
 * <p>pwsh 运行时不可用（本机/CI）时用例用 {@link FakeAstService} 注入可控 ParsedResult（不依赖 pwsh）；
 * colon-bound 单元用例直接构造 CommandElement（children 形状按 parser.ts transformCommandAst 对齐）。
 */
class PowerShellPermissionChainTest {

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

    /** 默认全 StringConstant 元素类型。 */
    private static PowerShellAstService.CommandElement cmd(String name, String nameType, String... args) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        for (String ignored : args) {
            elementTypes.add("StringConstant");
        }
        String text = name + (args.length > 0 ? " " + String.join(" ", args) : "");
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
    }

    /** 显式元素类型 + children（对齐 parser.ts transformCommandAst：args[i] ↔ elementTypes[i+1] ↔ children[i]）。 */
    private static PowerShellAstService.CommandElement cmdWithChildren(String name, String nameType,
                                                                       List<String> args, List<String> argElementTypes,
                                                                       List<List<PowerShellAstService.CommandElementChild>> children) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        elementTypes.addAll(argElementTypes);
        String text = name + (args.isEmpty() ? "" : " " + String.join(" ", args));
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            args, elementTypes, text, List.of(), children);
    }

    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        stmts.add(new PowerShellAstService.Statement("PipelineAst",
            cmds[0].text() + (cmds.length > 1 ? " ; " + cmds[1].text() : ""),
            List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false, false,
            false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode input(String command) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    // ════════════════════════════════════════════════════════════════════════
    // P0-2 / C76：colon-bound Parameter 子节点检查（EV-C2-021，CC readOnlyValidation.ts:1409-1424）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isAllowlistedCommand：Get-Process -Name:$env:SECRET（children=Variable）→ 拒绝（P0 colon-bound）")
    void colonBoundVariableChildRejected() {
        // `-Name:$env:SECRET` 是单个 CommandParameterAst，elementTypes='Parameter'，.Argument=
        // VariableExpressionAst 挂在 children[0]（对齐 CC parser.ts transformCommandAst）。
        // 旧实现把 Parameter 放过 → 净自动放行（secret 泄漏）；修复后必须 return false。
        PowerShellAstService.CommandElement c = cmdWithChildren("Get-Process", "cmdlet",
            List.of("-Name:$env:SECRET"), List.of("Parameter"),
            List.of(List.of(new PowerShellAstService.CommandElementChild("Variable", "$env:SECRET"))));
        PowerShellPermissionChain chain = new PowerShellPermissionChain(new PowerShellAstService());
        assertFalse(chain.isAllowlistedCommand(c),
            "colon-bound 参数子节点为 Variable（$env:SECRET）→ 必须 return false（CC readOnlyValidation.ts:1409-1414）");
    }

    @Test
    @DisplayName("isAllowlistedCommand：Get-Process -Name:svchost（children=StringConstant）→ 放行")
    void colonBoundStringChildAllowed() {
        // colon-bound 但绑定值静态可验证（StringConstant）→ 不泄漏，放行（CC children 全 StringConstant 才允许）。
        PowerShellAstService.CommandElement c = cmdWithChildren("Get-Process", "cmdlet",
            List.of("-Name:svchost"), List.of("Parameter"),
            List.of(List.of(new PowerShellAstService.CommandElementChild("StringConstant", "svchost"))));
        PowerShellPermissionChain chain = new PowerShellPermissionChain(new PowerShellAstService());
        assertTrue(chain.isAllowlistedCommand(c),
            "colon-bound 参数子节点全 StringConstant → 静态可验证，放行（CC :1409-1414 some 全通过）");
    }

    @Test
    @DisplayName("isAllowlistedCommand：children 缺失回退字符串考古（-Name:$env:SECRET）→ 拒绝")
    void colonBoundFallbackRejectedWhenNoChildren() {
        // 旧 parser/测试桩 children 缺失（undefined）→ CC :1416-1424 fallback 字符串考古：
        // 冒号后含 $ 元字符即 return false。Java CommandElement.children()==空列表 → 走 fallback。
        PowerShellAstService.CommandElement c = cmdWithChildren("Get-Process", "cmdlet",
            List.of("-Name:$env:SECRET"), List.of("Parameter"), List.of());
        PowerShellPermissionChain chain = new PowerShellPermissionChain(new PowerShellAstService());
        assertFalse(chain.isAllowlistedCommand(c),
            "children 缺失 + 冒号后含 $ → 字符串考古必须 return false（CC :1417-1424 fallback）");
    }

    @Test
    @DisplayName("isAllowlistedCommand：children 缺失回退（-Name:svchost 无元字符）→ 放行")
    void colonBoundFallbackAllowedWhenSafe() {
        PowerShellAstService.CommandElement c = cmdWithChildren("Get-Process", "cmdlet",
            List.of("-Name:svchost"), List.of("Parameter"), List.of());
        PowerShellPermissionChain chain = new PowerShellPermissionChain(new PowerShellAstService());
        assertTrue(chain.isAllowlistedCommand(c),
            "children 缺失 + 冒号后无元字符 → 字符串考古不拒绝，flag 白名单放行（CC :1417-1424）");
    }

    @Test
    @DisplayName("权限链端到端：Get-Process -Name:$env:SECRET → 非 Allow（P0 净自动放行修复）")
    void colonBoundSecretChainNotAutoAllowed() {
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmdWithChildren("Get-Process", "cmdlet",
            List.of("-Name:$env:SECRET"), List.of("Parameter"),
            List.of(List.of(new PowerShellAstService.CommandElementChild("Variable", "$env:SECRET"))))));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("Get-Process -Name:$env:SECRET"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "Get-Process -Name:$env:SECRET 不得只读 auto-allow（C76 secret 泄漏向量，CC :1409-1424）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // TR-C1-D-4：CLIXML 头剥离移除 → fail-closed（EV-C1-082，CC 不剥离头）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseJsonOutput：CLIXML 头前缀输出 → valid=false（fail-closed，CC 不剥离头）")
    void clixmlPrefixedOutputFailsClosed() {
        // PS5.1 stdout 重定向可能产出 '#< CLIXML' 头。移除 Java 头剥离后，该输出进 parseJsonOutput
        // 无法 readTree → valid=false → 权限链降级 ask（fail-closed）。旧实现剥离头后解析成功更宽容。
        String clixml = "#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">"
            + "<Obj S=\"cmdlet\" RefId=\"0\">{\"valid\":true,\"statements\":[]}</Obj></Objs>";
        PowerShellAstService.ParsedResult parsed =
            new PowerShellAstService().parseJsonOutput(clixml, "Get-Process");
        assertFalse(parsed.valid(),
            "CLIXML 头输出无法解析为纯 JSON → valid=false（fail-closed，CC 不剥离 CLIXML 头）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // TR-C2-⊕-C2-1：findAskMessage 无独立赋值消息（EV-C2-023，CC powershellSecurity.ts 无此 validator）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findAskMessage：hasAssignments 语句 → null（不再返回 Java-only 赋值消息）")
    void assignmentHasNoDedicatedAskMessage() {
        PowerShellAstService.ParsedResult assign = new PowerShellAstService.ParsedResult(true, List.of(),
            false, false, false, false, false, false, false, false, true,
            List.of(), List.of(), List.of(), List.of(), "$x = 1");
        assertNull(PowerShellCommandSafety.findAskMessage(assign),
            "赋值语句不得再返回 Java-only 消息『命令包含赋值表达式』（CC 无独立 assignment validator，net ask 经 step5）");
    }

    @Test
    @DisplayName("权限链：$x = 1 → 非 Allow（net ask 保留，经 step5 fail-closed）")
    void assignmentStillNotAutoAllowed() {
        // 消息归因删除后，赋值语句仍不可 auto-allow：AssignmentStatementAst 无 CommandAst 子命令，
        // step5 fail-closed（CC :1593-1597）push → 需审批（非 Allow）。
        FakeAstService ast = new FakeAstService();
        ast.stub(new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false,
            false, false, false, false, true, List.of(), List.of(),
            List.of(new PowerShellAstService.Statement("AssignmentStatementAst", "$x = 1",
                List.of(new PowerShellAstService.CommandElement("", "unknown", "CommandExpressionAst",
                    List.of(), List.of("Other"), "$x = 1", List.of(), List.of())), List.of())),
            List.of(), "$x = 1"));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        PermissionResult result = tool.checkPermissions(input("$x = 1"), null);
        assertFalse(result instanceof PermissionResult.Allow,
            "赋值语句不可 auto-allow（net ask 经 step5 fail-closed 保留，CC :1593-1597）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // TR-C2-Q2 / 组 1-4⑤：isPathAllowed 内部路径接入权限链（EV-C2-027，CC isPathAllowed step2/step3.5）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPathAllowed：内部可读路径 carve-out 放行（机制层，CC step3.5）")
    void internalReadablePathCarveOutAllowed() {
        PowerShellPathValidator.setInternalPathCarveOut((p, op) ->
            "read".equals(op) && p.replace('\\', '/').contains("agent-memory"));
        try {
            PowerShellPathValidator.PathCheck pc = PowerShellPathValidator.validatePath(
                "C:/Users/u/.claude/agent-memory/team/MEMORY.md", Path.of("C:/work"), null, "read");
            assertTrue(pc.allowed(),
                "agent-memory 内部可读路径必须经 carve-out 放行（CC isPathAllowed step3.5 checkReadableInternalPath）");
        } finally {
            PowerShellPathValidator.clearInternalPathCarveOut();
        }
    }

    @Test
    @DisplayName("isPathAllowed：内部可读路径未注入 carve-out → 工作目录外仍拒绝（fail-safe）")
    void internalPathWithoutCarveOutStillRejected() {
        // 未注入 carve-out（bean 缺失/测试隔离）→ isPathAllowed 走原 fail-safe：工作目录外 + 无 allow 规则
        // → 拒绝。证明 carve-out 是显式接入，不是静默放行。
        PowerShellPathValidator.PathCheck pc = PowerShellPathValidator.validatePath(
            "C:/Users/u/.claude/agent-memory/team/MEMORY.md", Path.of("C:/work"), null, "read");
        assertFalse(pc.allowed(),
            "未注入 carve-out 时工作目录外内部路径必须仍拒绝（fail-safe，不得静默放行）");
    }

    @Test
    @DisplayName("权限链端到端：Get-Content auto-memory 路径 → Allow（AutoMemPaths bean 注入接线）")
    void autoMemPathCarveOutChainAllowed() {
        // 生产接线：PowerShellPermissionChain 注入 AutoMemPaths（@Autowired(required=false)），
        // check() 内 setInternalPathCarveOut(this::internalPathCarveOut) → isPathAllowed step3.5 放行
        // auto-memory 内部读路径 → 整链 Allow（读内部路径无需工作目录外 ask）。
        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> "C:\\ps-test-proj-" + System.nanoTime(),   // 唯一 projectRoot（防静态缓存跨测试污染）
            () -> "C:\\ps-test-mem",
            () -> "C:\\ps-test-mem\\projects\\team\\memory\\",   // override → getAutoMemPath 直接返回
            () -> null);
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Get-Content", "cmdlet", "C:/ps-test-mem/projects/team/memory/MEMORY.md")));
        PowerShellPermissionChain chain = new PowerShellPermissionChain(ast);
        chain.setAutoMemPaths(autoMemPaths);
        PowerShellTool tool = new PowerShellTool(chain);
        PermissionResult result = tool.checkPermissions(
            input("Get-Content C:/ps-test-mem/projects/team/memory/MEMORY.md"), null);
        assertInstanceOf(PermissionResult.Allow.class, result,
            "auto-memory 内部可读路径必须经 carve-out 整链放行（CC isPathAllowed step3.5，TR-C2-Q2 接入）");
    }

    @Test
    @DisplayName("写 carve-out：override 生效时 auto-memory 写不自动放行（CC filesystem.ts:1572 守卫）")
    void autoMemPathOverrideDisablesWriteCarveOut() {
        // WHY：CLAUDE_COWORK_MEMORY_PATH_OVERRIDE 是调用方任意指定目录，CC checkEditableInternalPath
        // 对 memdir 写分支显式 !hasAutoMemPathOverride()（filesystem.ts:1572）——override 目录不获特殊
        // 权限处理，写走正常权限流 step5→ask。无此守卫则 PowerShell 写 override 目录被自动放行（fail-open，
        // 与 EditFileTool:162 / WriteFileTool:221 既有规范冲突）。读分支（checkReadableInternalPath
        // filesystem.ts:1716）CC 无 override 守卫，仍放行。
        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> "C:\\ps-test-proj-" + System.nanoTime(),   // 唯一 projectRoot（防静态缓存跨测试污染）
            () -> "C:\\ps-test-mem",
            () -> "C:\\ps-test-mem\\projects\\team\\memory\\",   // override 生效
            () -> null);
        PowerShellPermissionChain chain = new PowerShellPermissionChain(new FakeAstService());
        chain.setAutoMemPaths(autoMemPaths);
        String memPath = "C:/ps-test-mem/projects/team/memory/MEMORY.md";
        assertFalse(chain.internalPathCarveOut(memPath, "edit"),
            "override 生效时 auto-memory 写必须走正常权限流（CC filesystem.ts:1572，不得自动放行）");
        assertTrue(chain.internalPathCarveOut(memPath, "read"),
            "读 carve-out CC 无 override 守卫（filesystem.ts:1716），仍放行");
    }
}
