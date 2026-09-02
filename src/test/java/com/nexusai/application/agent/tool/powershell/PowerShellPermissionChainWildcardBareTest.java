package com.nexusai.application.agent.tool.powershell;

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
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShell A5 链 wildcard / 驱动器子级 / 裸仓库 OR 语义测试。
 *
 * <p>WHY（意图验证，CC 真源）：
 * <ul>
 *   <li>wildcard：deny {@code Get-Process *} 必须拦 {@code Get-Process -Name foo} 与裸
 *       {@code Get-Process}（shellRuleMatching.ts:90-154 尾随 {@code ' *'} 唯一星 → {@code ( .*)?}）；
 *       {@code \*} 字面星；大小写不敏感；多星 {@code '* run *'} 不误拦 {@code npm run}。</li>
 *   <li>wildcard canonical 交叉：deny {@code rm *} 必须拦 {@code Remove-Item secret.txt}
 *       （powershellPermissions.ts:307-328 resolveToCanonical 交叉，封别名绕过）。</li>
 *   <li>驱动器子级：isDangerousRemovalPath({@code C:/Windows})→true、({@code C:/Windows/System32})→false
 *       （pathValidation.ts:319 WINDOWS_DRIVE_CHILD_REGEX 仅直接子级）。</li>
 *   <li>裸仓库：.git 为文件→false（worktree 短路）；仅 objects/→true（OR 语义非 AND）
 *       （git.ts:876-925）。</li>
 * </ul>
 */
class PowerShellPermissionChainWildcardBareTest {

    /** 可控 AST 桩：不启动 pwsh，返回预置 ParsedResult（沿用 PowerShellToolPermissionTest.FakeAstService）。 */
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

    private static ToolUseContext ctxWithCwd(Path cwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", cwd);
    }

    // ════════════════════════════════════════════════════════════════════════
    // wildcard 算法 · matchWildcardPattern 直接单测（对齐 shellRuleMatching.ts:90-154）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("matchWildcardPattern：Get-Process * 拦带参与裸命令（尾随 ' *' 唯一星 → '( .*)?'）")
    void wildcardTrailingOptionalMatchesBareCommand() {
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("Get-Process *", "Get-Process -Name foo", true),
            "Get-Process * 必须命中 Get-Process -Name foo");
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("Get-Process *", "Get-Process", true),
            "尾随 ' *' 唯一未转义星 → '( .*)?'，Get-Process * 必须命中裸 Get-Process（CC :136-145）");
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("git *", "git", true),
            "git * 命中裸 git（对齐 prefix 语义 git:*）");
    }

    @Test
    @DisplayName("matchWildcardPattern：大小写不敏感；多星 '* run *' 不误拦 'npm run'")
    void wildcardCaseInsensitiveAndMultiStarNoFalsePositive() {
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("get-process *", "Get-Process x", true),
            "PowerShell 规则匹配大小写不敏感（CC caseInsensitive=true :243）");
        assertFalse(PowerShellPermissionChain.matchWildcardPattern("* run *", "npm run", true),
            "多星模式尾随星不做 '( .*)?'，'* run *' 不得命中无尾参的 'npm run'（CC :140-145 防误拦）");
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("* run *", "npm run build", true),
            "'* run *' 有尾参时仍命中");
    }

    @Test
    @DisplayName("matchWildcardPattern：锚定 + 字面星/反斜杠转义 + 大小写敏感关闭")
    void wildcardAnchorEscapeAndCaseSensitivity() {
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("ab*c", "abXC", true),
            "* 匹配任意字符序列（大小写不敏感）");
        assertFalse(PowerShellPermissionChain.matchWildcardPattern("ab*c", "abxcy", true),
            "正则锚定 ^...$：ab*c 不得命中 abxcy");
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("git \\*", "git *", true),
            "\\* 转义为字面星号：git \\* 命中 'git *'（CC :107-112）");
        assertFalse(PowerShellPermissionChain.matchWildcardPattern("git \\*", "git x", true),
            "git \\* 不得命中 git x（字面星 ≠ 通配）");
        assertFalse(PowerShellPermissionChain.matchWildcardPattern("abc*", "ABC", false),
            "caseInsensitive=false 时大小写敏感（CC :150 flags）");
        // regex 特殊字符须被转义为字面（CC :126 [.+?^${}()|[\]\\'"]）
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("findstr (hello)", "findstr (hello)", true),
            "( ) 是 regex 特殊字符，须转义为字面否则抛 PatternSyntaxException");
        assertTrue(PowerShellPermissionChain.matchWildcardPattern("foo.bar *", "foo.bar x", true),
            ". 须转义为字面（字面点匹配，非任意字符）");
        assertFalse(PowerShellPermissionChain.matchWildcardPattern("foo.bar *", "fooxbar x", true),
            ". 转义后不得当任意字符匹配 fooxbar");
    }

    @Test
    @DisplayName("hasWildcards：未转义 * 判定（偶数反斜杠含 0；':*' 结尾不算通配）")
    void hasWildcardsSemantics() {
        assertTrue(PowerShellPermissionChain.hasWildcards("rm *"), "rm * 含未转义通配");
        assertFalse(PowerShellPermissionChain.hasWildcards("rm:*"), "':*' 结尾是 legacy 前缀语法非通配（CC :56-58）");
        assertFalse(PowerShellPermissionChain.hasWildcards("git \\*"), "\\* 单反斜杠转义 → 无未转义通配");
        assertTrue(PowerShellPermissionChain.hasWildcards("a\\\\*"), "两个反斜杠（偶数）→ * 仍未转义（CC :62-75）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // wildcard deny 集成 · 走 subCommandRules → matchingRule → psRuleMatches（非 RuleQuery STEP1）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("deny(Get-Process *) 拦复合命令子命令裸 Get-Process（psRuleMatches wildcard 分支）")
    void wildcardDenyBlocksCompoundSubcommand() {
        // 整串首词 Write-Output → RuleQuery STEP1 的 ^Get-Process( .*)?$ 不命中；子命令裸
        // Get-Process 经 subCommandRules 走本地 psRuleMatches wildcard 分支（尾随 '( .*)?'）→ Deny。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Write-Output", "cmdlet", "x"), cmd("Get-Process", "cmdlet")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "Get-Process *")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x ; Get-Process"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "deny(Get-Process *) 必须拦子命令裸 Get-Process（wildcard 分支 + 尾随 '( .*)?'）");
    }

    @Test
    @DisplayName("deny(rm *) 经 canonical 交叉拦 Remove-Item secret.txt（别名绕过封死）")
    void wildcardCanonicalCrossDeniesRemoveItem() {
        // 整串首词 Write-Output → STEP1 不命中；子命令 Remove-Item secret.txt 经 psRuleMatches
        // canonical 交叉：ruleCanonical(rm→remove-item)===inputCanonical → canonicalPattern
        // 'remove-item *' 命中 canonicalCommand（powershellPermissions.ts:307-328）。
        FakeAstService ast = new FakeAstService();
        ast.stub(single(cmd("Write-Output", "cmdlet", "x"), cmd("Remove-Item", "cmdlet", "secret.txt")));
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(permCtxOf(rule(PermissionBehavior.DENY, "rm *")));
        PermissionResult result = tool.checkPermissions(input("Write-Output x ; Remove-Item secret.txt"), ctx);
        assertInstanceOf(PermissionResult.Deny.class, result,
            "deny(rm *) 必须经 canonical 交叉拦 Remove-Item secret.txt（封 rm→remove-item 别名绕过）");
    }

    @Test
    @DisplayName("2c escape hatch：wildcard allow 不参与 exact 短路（parse-failed 降级 ask，防 fail-open）")
    void wildcardAllowNotExactShortCircuit() {
        // exactAllowRule 跳过通配规则（CC :240-242 exact 模式 wildcard 返回 false）。若未跳过，
        // 'Write-Output *' 会命中 'Write-Output hello' 在 2c fail-open 放行 → 必须 Ask。
        FakeAstService ast = new FakeAstService();
        // 不 stub → 默认 valid=false
        PowerShellTool tool = new PowerShellTool(new PowerShellPermissionChain(ast));
        ToolUseContext ctx = ctxWith(allowCtxOf(rule(PermissionBehavior.ALLOW, "Write-Output *")));
        PermissionResult result = tool.checkPermissions(input("Write-Output hello"), ctx);
        assertFalse(result instanceof PermissionResult.Allow,
            "wildcard allow 规则不得在 2c exact-allow 短路 fail-open（CC exact 模式 wildcard=false）");
        assertInstanceOf(PermissionResult.Ask.class, result,
            "parse-failed + wildcard allow 未短路 → 降级 ask");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 驱动器子级 · isDangerousRemovalPath（对齐 pathValidation.ts:318-367）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isDangerousRemovalPath：C:/Windows 危险 / C:/Windows/System32 不危险 / C:/ 盘根危险")
    void driveChildDangerousRemoval() {
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("C:/Windows"),
            "C:/Windows 驱动器直接子级必须危险（WINDOWS_DRIVE_CHILD pathValidation.ts:319）");
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("C:\\Windows"),
            "反斜杠形式 C:\\Windows 折叠后同样危险（replaceAll [\\\\/]+ → /）");
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("C:/Users"),
            "C:/Users 驱动器直接子级危险");
        assertFalse(PowerShellPermissionChain.isDangerousRemovalPath("C:/Windows/System32"),
            "C:/Windows/System32 非直接子级（[^/]+ 不可跨 /）→ 不危险");
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("C:/"),
            "C:/ 驱动器根危险（WINDOWS_DRIVE_ROOT）");
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("C:"),
            "C: 驱动器根（可省尾斜杠）危险");
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("D:/tmp/"),
            "尾斜杠折叠后 D:/tmp 仍是驱动器直接子级 → 危险");
        assertFalse(PowerShellPermissionChain.isDangerousRemovalPath("C:/work/project"),
            "驱动器深层路径 C:/work/project 不危险（仅直接子级）");
    }

    @Test
    @DisplayName("isDangerousRemovalPath：根直接子级 / 深路径基线（回归）")
    void rootChildBaseline() {
        assertTrue(PowerShellPermissionChain.isDangerousRemovalPath("/etc"),
            "/etc 根直接子级危险（lastSlash==0）");
        assertFalse(PowerShellPermissionChain.isDangerousRemovalPath("/home/user/file.txt"),
            "/home/user/file.txt 深路径不危险");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 裸仓库 OR 语义 · isCurrentDirectoryBareGitRepo（对齐 git.ts:876-925）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName(".git 为文件（worktree/submodule gitdir 引用）→ 非裸仓库（短路 false）")
    void bareGitWorktreeDotGitFileIsFalse(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".git"), "gitdir: ../.git/worktrees/task");
        assertFalse(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            ".git 是文件时 Git 跟随 gitdir 引用，不属裸仓库（git.ts:882-886）");
    }

    @Test
    @DisplayName("正常仓库 .git/HEAD 为文件 → 非裸仓库（false）")
    void bareGitNormalRepoHeadIsFalse(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".git/HEAD"), "ref: refs/heads/main");
        assertFalse(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            "有效 .git/HEAD 时 Git 不会回退 cwd 发现（git.ts:887-896）");
    }

    @Test
    @DisplayName("仅 objects/ 目录 → 裸仓库（true）：OR 语义非 AND")
    void bareGitObjectsOnlyIsTrue(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("objects"));
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            "无 .git 但 cwd 含 objects/ 指示 → true（git.ts:914-916 flag if ANY exist）");
    }

    @Test
    @DisplayName("仅 refs/ 目录 → 裸仓库（true）")
    void bareGitRefsOnlyIsTrue(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("refs"));
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            "仅 refs/ 指示即 true（OR 语义）");
    }

    @Test
    @DisplayName("仅 HEAD 文件 → 裸仓库（true）")
    void bareGitHeadFileOnlyIsTrue(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("HEAD"), "ref: refs/heads/main");
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            "仅 HEAD 文件指示即 true（git.ts:909-912）");
    }

    @Test
    @DisplayName("无任何指示 → 非裸仓库（false）")
    void bareGitNoIndicatorsIsFalse(@TempDir Path tmp) throws Exception {
        assertFalse(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            "空目录无 HEAD/objects/refs 指示 → false");
    }

    @Test
    @DisplayName("攻击场景：.git 目录存在但无有效 HEAD + cwd 含 objects/ → 裸仓库（true）")
    void bareGitDotGitDirNoHeadPlusObjectsIsTrue(@TempDir Path tmp) throws Exception {
        // 攻击者建 .git/ 目录（无 HEAD 文件）使 Git 回退 cwd 发现，cwd 又含 objects/ → 裸仓库
        Files.createDirectories(tmp.resolve(".git"));
        Files.createDirectories(tmp.resolve("objects"));
        assertTrue(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            ".git 目录无有效 HEAD + cwd objects/ → Git 回退 cwd 发现（git.ts:897-900 + :914-916）");
    }

    @Test
    @DisplayName("空 .git/ 目录仅自身，无 HEAD/objects/refs → 非裸仓库（false）")
    void bareGitEmptyDotGitDirIsFalse(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        assertFalse(PowerShellPermissionChain.isCurrentDirectoryBareGitRepo(ctxWithCwd(tmp)),
            ".git 目录存在但无 HEAD，且 cwd 无任何裸仓库指示 → false");
    }
}
