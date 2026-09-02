package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S03] RuleQuery.extractMatchTarget PowerShell 分支 + 大小写不敏感独立匹配路径测试。
 *
 * <p>对齐 CC：
 * <ul>
 *   <li>{@code extractMatchTarget} —— PowerShell 工具取 {@code input.command} 字段
 *       （CC powershellPermissions.ts:176）</li>
 *   <li>{@code matchesPowerShellRuleContent} —— CC powershellPermissions.ts:170-333
 *       {@code filterRulesByContentsMatchingInput} matchesCommand 分支（非 canonical 部分）：
 *       exact/prefix/wildcard 三型规则全部大小写不敏感（OPD-PERM-37 独立路径）</li>
 *   <li>Bash/Edit 既有语义回归：Bash 前缀/精确仍大小写敏感，PathTool glob 不回归</li>
 *   <li>可达性：经 {@link RuleQuery#getRuleForInput} 驱动真实工具实例
 * 证明 {@code PowerShell(...)} 内容规则对 1a content-deny / 1f content-ask 可达。
 */
@DisplayName("[S03] RuleQuery PowerShell 内容规则提取与大小写不敏感匹配")
class RuleQueryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // 提取：extractMatchTarget
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractMatchTarget 提取")
    class ExtractionTests {

        @Test
        @DisplayName("PowerShell 工具提取 command 字段（Get-Process *）")
        void powerShell_extractsCommand() {
            assertThat(RuleQuery.extractMatchTarget("PowerShell", commandInput("Get-Process *")))
                .isEqualTo("Get-Process *");
        }

        @Test
        @DisplayName("input 为 null → null")
        void powerShell_nullInput() {
            assertThat(RuleQuery.extractMatchTarget("PowerShell", null)).isNull();
        }

        @Test
        @DisplayName("input 缺 command 字段 → null")
        void powerShell_missingCommandField() {
            ObjectNode input = JSON.createObjectNode();
            input.put("timeout", 100_000);
            assertThat(RuleQuery.extractMatchTarget("PowerShell", input)).isNull();
        }

        @Test
        @DisplayName("command 非文本类型 → null")
        void powerShell_nonTextCommand() {
            ObjectNode input = JSON.createObjectNode();
            input.put("command", 42);
            assertThat(RuleQuery.extractMatchTarget("PowerShell", input)).isNull();
        }

        @Test
        @DisplayName("回归：Bash 仍提取 command")
        void bash_stillExtractsCommand() {
            assertThat(RuleQuery.extractMatchTarget("Bash", commandInput("git status")))
                .isEqualTo("git status");
        }

        @Test
        @DisplayName("回归：文件工具提取 file_path（仅 CC 主名 Read/Edit/Write）")
        void fileTools_stillExtractFilePath() {
            // [R7 / OPD-WF3-DC-v4-05] 对齐 CC 严格 ===：仅 CC 主名 Read/Edit/Write 提取
            // file_path。snake_case（read_file/edit_file/write_file）与 lowercase（read/edit/write）
            // 是已删 alias 的兼容壳，生产经 toolNameMatches 严格 === 门控不可达，已从
            // extractMatchTarget 移除——此处不再断言死分支（未知工具名回落 default→null）。
            ObjectNode input = filePathInput("/Users/foo/bar.txt");
            for (String name : new String[]{"Edit", "Write", "Read"}) {
                assertThat(RuleQuery.extractMatchTarget(name, input))
                    .as("tool=%s 应提取 file_path", name)
                    .isEqualTo("/Users/foo/bar.txt");
            }
        }

        @Test
        @DisplayName("snake_case 旧名不再提取 file_path（已删 alias 兼容壳 → default→null）")
        void snakeCaseNames_doNotExtractFilePath() {
            ObjectNode input = filePathInput("/Users/foo/bar.txt");
            for (String name : new String[]{"edit_file", "write_file", "read_file", "edit", "write", "read"}) {
                assertThat(RuleQuery.extractMatchTarget(name, input))
                    .as("tool=%s 为已删 alias，不应提取 file_path（对齐 CC 严格 ===）", name)
                    .isNull();
            }
        }

        @Test
        @DisplayName("未知工具类型 → null（不匹配）")
        void unknownTool_null() {
            assertThat(RuleQuery.extractMatchTarget("Glob", commandInput("git status"))).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 独立大小写不敏感匹配器：matchesPowerShellRuleContent
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PowerShell 大小写不敏感匹配（独立路径）")
    class PowerShellCiMatcherTests {

        @Test
        @DisplayName("exact 规则：命令名大小写不敏感")
        void exact_caseInsensitive() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process", "get-process"))
                .as("Get-Process 规则应命中 get-process").isTrue();
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process", "GET-PROCESS"))
                .as("Get-Process 规则应命中 GET-PROCESS").isTrue();
        }

        @Test
        @DisplayName("exact 规则：参数不同不匹配")
        void exact_argsMustMatch() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process", "Get-Process chrome"))
                .as("exact 规则不得匹配带参数命令").isFalse();
            assertThat(RuleQuery.matchesPowerShellRuleContent("Write-Host hello", "write-host hell"))
                .as("exact 规则参数须完整相等").isFalse();
        }

        @Test
        @DisplayName(":* 前缀规则：带参数命令大小写不敏感命中")
        void prefix_matchesWithArgs() {
            assertThat(RuleQuery.matchesPowerShellRuleContent(
                "Remove-Item:*", "remove-item -Recurse -Force C:\\temp"))
                .as("Remove-Item:* 应命中 remove-item -Recurse -Force").isTrue();
        }

        @Test
        @DisplayName(":* 前缀规则：裸命令 equals 命中")
        void prefix_bareCommandEquals() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Remove-Item:*", "REMOVE-ITEM"))
                .as("Remove-Item:* 应命中裸 remove-item").isTrue();
        }

        @Test
        @DisplayName(":* 前缀规则：空格分隔，Get-ProcessFoo 不误命中")
        void prefix_spaceSeparator() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process:*", "get-processfoo"))
                .as("前缀须以空格分隔，get-processfoo 不得命中 Get-Process:*").isFalse();
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process:*", "Get-Process foo"))
                .as("Get-Process foo 应命中 Get-Process:*").isTrue();
        }

        @Test
        @DisplayName("通配规则：大小写不敏感命中")
        void wildcard_caseInsensitive() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process *", "GET-PROCESS chrome"))
                .as("Get-Process * 应命中 GET-PROCESS chrome").isTrue();
        }

        @Test
        @DisplayName("通配规则：尾随 * 可选（'git *' 命中裸 git，CC :136-145）")
        void wildcard_trailingOptional() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process *", "get-process"))
                .as("Get-Process * 应命中裸 get-process（尾随参数可选）").isTrue();
        }

        @Test
        @DisplayName("多通配符：尾随不省略（'* run *' 不命中 'npm run'，CC :140-141）")
        void wildcard_multiStarNoOptional() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("* run *", "npm run build"))
                .as("* run * 应命中 npm run build").isTrue();
            assertThat(RuleQuery.matchesPowerShellRuleContent("* run *", "npm run"))
                .as("* run * 不得命中 npm run（多通配符不做尾随省略）").isFalse();
        }

        @Test
        @DisplayName("\\* 转义星号：通配规则内只命中字面 *（CC shellRuleMatching.ts:106-111）")
        void wildcard_escapedStarLiteral() {
            // 规则须含未转义 * 才归类 wildcard（CC parsePermissionRule）；\\* 在其中是字面星号
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process \\* *", "get-process *"))
                .as("Get-Process \\* * 应命中 get-process *（\\* 匹配字面星号）").isTrue();
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process \\* *", "get-process x"))
                .as("Get-Process \\* * 不得命中 get-process x（\\* 要求字面星号）").isFalse();
        }

        @Test
        @DisplayName("\\\\ 转义反斜杠：通配规则内只命中字面 \\（CC shellRuleMatching.ts:113-117）")
        void wildcard_escapedBackslashLiteral() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Item \\\\ *", "get-item \\ x"))
                .as("Get-Item \\\\ * 应命中 get-item \\ x（\\\\ 匹配字面反斜杠）").isTrue();
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Item \\\\ *", "get-item x"))
                .as("Get-Item \\\\ * 不得命中 get-item x（\\\\ 要求字面反斜杠）").isFalse();
        }


        @Test
        @DisplayName("输入命令先 trim（CC :176）")
        void commandTrimmed() {
            assertThat(RuleQuery.matchesPowerShellRuleContent("Get-Process", "  get-process  "))
                .as("命令 trim 后应命中 Get-Process").isTrue();
        }

        @Test
        @DisplayName("空前缀 ':*' 不是前缀规则（CC /^(.+):\\*$/ 要求非空前缀）")
        void emptyPrefixNotPrefixRule() {
            assertThat(RuleQuery.matchesPowerShellRuleContent(":*", "Get-Process"))
                .as("':*' 空前缀不是前缀规则，按 exact 处理不得命中").isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 可达性：经 RuleQuery 公共 API + 真实工具实例（1a content-deny / 1f content-ask）
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PowerShell 内容规则可达性（集成，非 mock）")
    class ContentRuleReachabilityTests {

        @Test
        @DisplayName("1a：PowerShell(Remove-Item:*) deny 规则可达，命令名大小写不敏感")
        void powerShellDenyRule_reachable_ci() {
            ToolPermissionContext permCtx = denyCtx(rule("PowerShell", "Remove-Item:*"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new PowerShellTool(), commandInput("remove-item -Recurse -Force C:\\temp"));
            assertThat(hit).as("remove-item 应命中 PowerShell(Remove-Item:*)").isNotNull();
            assertThat(hit.ruleValue().ruleContent()).isEqualTo("Remove-Item:*");
        }

        @Test
        @DisplayName("1a：PowerShell(Get-Process) deny 精确规则可达，大小写不敏感")
        void powerShellDenyRule_exact_reachable() {
            ToolPermissionContext permCtx = denyCtx(rule("PowerShell", "Get-Process"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new PowerShellTool(), commandInput("GET-PROCESS"));
            assertThat(hit).as("GET-PROCESS 应命中 PowerShell(Get-Process)").isNotNull();
        }

        @Test
        @DisplayName("1a：PowerShell 内容不匹配 → null")
        void powerShellDenyRule_noMatch() {
            ToolPermissionContext permCtx = denyCtx(rule("PowerShell", "Get-Process"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new PowerShellTool(), commandInput("Get-Process chrome"));
            assertThat(hit).as("带参数命令不得命中 exact 规则").isNull();
        }

        @Test
        @DisplayName("1f：PowerShell 内容 ask 规则经 getRuleForInput 可达")
        void powerShellAskRule_reachable() {
            Map<PermissionRuleSource, Set<PermissionRule>> ask = new EnumMap<>(PermissionRuleSource.class);
            ask.put(PermissionRuleSource.SESSION,
                Set.of(new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ASK,
                    PermissionRuleValue.withContent("PowerShell", "Write-Host *"))));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, Map.of(), Map.of(), ask, Map.of());
            PermissionRule hit = RuleQuery.getRuleForInput(
                permCtx, new PowerShellTool(), commandInput("write-host hello"));
            assertThat(hit).as("write-host hello 应命中 PowerShell(Write-Host *)").isNotNull();
        }

        @Test
        @DisplayName("回归：Bash 大小写敏感语义不受 PowerShell 路径影响")
        void bashCaseSensitivity_preserved() {
            // NPM PUBLISH 大写规则不得命中小写命令（Bash 仍大小写敏感）
            ToolPermissionContext denyUpper = denyCtx(rule("Bash", "NPM PUBLISH:*"));
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                denyUpper, new BashTool(), commandInput("npm publish --access public")))
                .as("Bash 前缀匹配保持大小写敏感，NPM PUBLISH:* 不得命中 npm publish").isNull();
            // 大写命令命中大写规则
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                denyUpper, new BashTool(), commandInput("NPM PUBLISH --access public")))
                .as("NPM PUBLISH --access public 应命中 NPM PUBLISH:*").isNotNull();
        }

        @Test
        @DisplayName("回归：Bash(npm publish:*) 前缀规则仍命中")
        void bashPrefixRule_stillMatches() {
            ToolPermissionContext permCtx = denyCtx(rule("Bash", "npm publish:*"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new BashTool(), commandInput("npm publish --access public"));
            assertThat(hit).as("npm publish --access public 应命中 Bash(npm publish:*)").isNotNull();
        }

        @Test
        @DisplayName("回归：Edit 路径 glob 规则仍命中（edit_file 工具名 ↔ Edit 规则名等价组）")
        void editGlobRule_stillMatches(@TempDir java.nio.file.Path workspace) {
            ToolPermissionContext permCtx = denyCtx(rule("Edit", "/Users/foo/**"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new EditFileTool(new PathGuard(workspace)),
                filePathInput("/Users/foo/bar.txt"));
            assertThat(hit).as("/Users/foo/bar.txt 应命中 Edit(/Users/foo/**)").isNotNull();
        }

        @Test
        @DisplayName("回归：Read 精确规则仍命中，不匹配返回 null（read_file ↔ Read 等价组）")
        void readExactRule_stillMatches(@TempDir java.nio.file.Path workspace) {
            ToolPermissionContext permCtx = denyCtx(rule("Read", "secret.txt"));
            ReadFileTool readTool = new ReadFileTool(new PathGuard(workspace));
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                permCtx, readTool, filePathInput("secret.txt")))
                .as("secret.txt 应命中 Read(secret.txt)").isNotNull();
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                permCtx, readTool, filePathInput("other.txt")))
                .as("other.txt 不得命中 Read(secret.txt)").isNull();
        }

    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-13] toolNameMatches 等价组删除 → 精确 ===（OPD-WF3-DC-v4-05）
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("[IMP-13] toolNameMatches 精确 ===（CC permissions.ts:254，等价组已删）")
    class ToolNameMatchesExactTests {

        @Test
        @DisplayName("CC 名精确命中：Edit===Edit / Read===Read / Write===Write（=== 保留）")
        void exactCcName_matches() {
            // WHY: CC permissions.ts:254 `rule.ruleValue.toolName === nameForRuleMatch` 严格 ===。
            //   等价组删除不得破坏 CC 名对 CC 名的精确匹配（B2 后工具注册主名对齐 CC）。
            assertThat(RuleQuery.toolNameMatches("Edit", "Edit")).isTrue();
            assertThat(RuleQuery.toolNameMatches("Read", "Read")).isTrue();
            assertThat(RuleQuery.toolNameMatches("Write", "Write")).isTrue();
        }

        @Test
        @DisplayName("等价组已删：Edit 不得命中 edit_file/edit（CC 严格 ===）")
        void equivalentGroup_removed() {
            // WHY: OPD-WF3-DC-v4-05 用户拍板删等价组对齐 CC —— Java 文件工具名等价组
            //   (Edit↔edit_file↔edit) 是 CC 没有的放宽（⊕），删除后仅精确 ===。
            assertThat(RuleQuery.toolNameMatches("Edit", "edit_file")).isFalse();
            assertThat(RuleQuery.toolNameMatches("Edit", "edit")).isFalse();
            assertThat(RuleQuery.toolNameMatches("Read", "read_file")).isFalse();
            assertThat(RuleQuery.toolNameMatches("Write", "write_file")).isFalse();
        }

        @Test
        @DisplayName("历史 snake_case 规则名不再命中 CC 主名（H13 历史 transcript 兼容已评估）")
        void historicalSnakeCaseRuleName_noMatch() {
            // WHY: [H13 v4] 旧注释为"历史 transcript 携带旧 snake_case 名"保留等价组；拍板删组后，
            //   旧 read_file/edit_file/write_file 规则名不再命中 Read/Edit/Write（CC 语义）。
            assertThat(RuleQuery.toolNameMatches("read_file", "Read")).isFalse();
            assertThat(RuleQuery.toolNameMatches("edit_file", "Edit")).isFalse();
            assertThat(RuleQuery.toolNameMatches("write_file", "Write")).isFalse();
        }

        @Test
        @DisplayName("null 任一参数 → false")
        void nullArg_false() {
            assertThat(RuleQuery.toolNameMatches(null, "Read")).isFalse();
            assertThat(RuleQuery.toolNameMatches("Read", null)).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 辅助构造
    // ════════════════════════════════════════════════════════════════════

    private static ObjectNode commandInput(String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return input;
    }

    private static ObjectNode filePathInput(String filePath) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", filePath);
        return input;
    }

    private static PermissionRule rule(String toolName, String ruleContent) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent(toolName, ruleContent));
    }

    private static ToolPermissionContext denyCtx(PermissionRule denyRule) {
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
        deny.put(PermissionRuleSource.SESSION, Set.of(denyRule));
        return ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
    }

    // ════════════════════════════════════════════════════════════════════
    // WF-1D · DEL-06 · getEditRuleByContentsForPath cwd=null 兜底走统一入口
    // 对齐 CC resolve(cwd, path) cwd=getCwd()（bashPermissions.ts:1114 传 getCwd()）。
    // WHY：3 参重载（cwd=null）被 PathValidation.editDenyRule/editAllowRule 调用，原 Java
    //   兜底 System.getProperty("user.dir")，绑定项目场景 root-relative 匹配锚错根 →
    //   相对路径 edit 规则在该会话内永不命中（权限判定错位，G9）。改走 CwdResolution.getCwd()
    //   后 root=boundProject，相对路径规则正确锚定。
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WF-1D · DEL-06 · cwd=null 兜底走 CwdResolution（绑定项目 baseDir 取对）")
    class Wf1dBaseDirFallbackTests {

        @AfterEach
        void clearCwdState() {
            CwdResolution.clearCurrentOverride();
            SessionProjectRoot.reset();
            RequestContext.clear();
        }

        @Test
        @DisplayName("绑定项目 + cwd=null → root-relative 相对规则锚定 boundProject 命中（非 user.dir）")
        void cwdNull_usesBoundProjectAsRoot(@TempDir Path projectDir) throws Exception {
            // WHY: CC matchingRuleForInput 的 patternWithRoot 对无前缀规则 root=cwd=getCwd()。
            //   绑定项目场景 cwd 必须取 boundProject，否则相对规则 "sub/file.txt" 锚 user.dir
            //   而 target=boundProject/sub/file.txt 在 user.dir 之外 → rel=null → 不匹配 →
            //   edit allow 规则失效（应放行的写入被误 ask/deny）。
            String sessionId = "wf1d-ruleq-sess";
            SessionProjectRoot.setForSession(sessionId, projectDir.toString());
            RequestContext.setSession(sessionId);

            // session allow 规则：Edit 相对路径 sub/file.txt（无 // ~/ / 前缀 → root=cwd）
            PermissionRule allowRule = new PermissionRule(
                PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Edit", "sub/file.txt"));
            Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
            allow.put(PermissionRuleSource.SESSION, Set.of(allowRule));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());

            // 待匹配绝对路径（在 boundProject 下）
            String targetPath = projectDir.resolve("sub/file.txt").toString();

            // cwd=null → 走 :604 兜底。修复前 root=user.dir → 不命中返回 null；
            // 修复后 root=boundProject（CwdResolution.getCwd）→ 命中返回 allowRule。
            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                permCtx, targetPath, PermissionBehavior.ALLOW, null);

            assertThat(hit)
                .as("绑定项目场景 cwd=null 兜底必须取 boundProject，相对规则才能锚定命中")
                .isNotNull()
                .isEqualTo(allowRule);
            assertThat(CwdResolution.getCwd())
                .as("CwdResolution.getCwd 解析为 boundProject（统一入口）")
                .isEqualTo(projectDir.toRealPath().toString());
        }

        @Test
        @DisplayName("未绑定 + cwd=null → 回落 user.dir（经统一入口，INV-4/INV-6）不抛")
        void cwdNull_unboundFallsBackToUserDir(@TempDir Path projectDir) throws Exception {
            // WHY: 未绑定会话 boundProject=null → getCwd 回落 user.dir（INV-4），不抛异常。
            String sessionId = "wf1d-ruleq-unbound";
            RequestContext.setSession(sessionId);
            // 不绑定 SessionProjectRoot

            PermissionRule allowRule = new PermissionRule(
                PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Edit", "sub/file.txt"));
            Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
            allow.put(PermissionRuleSource.SESSION, Set.of(allowRule));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());

            // projectDir 不在 user.dir 下 → rel=null → 不匹配（无回归：未绑定时行为不变）
            String targetPath = projectDir.resolve("sub/file.txt").toString();
            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                permCtx, targetPath, PermissionBehavior.ALLOW, null);

            assertThat(hit)
                .as("未绑定 + 路径在 user.dir 之外 → 不命中（行为不变，无回归）")
                .isNull();
            assertThat(CwdResolution.getCwd())
                .as("未绑定回落 user.dir（经统一入口）")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        }
    }
}
