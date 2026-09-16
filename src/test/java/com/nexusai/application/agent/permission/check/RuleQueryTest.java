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
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
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

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 的类 javadoc。（外层声明对全部 @Nested 子类同样生效。）

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

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
                permCtx, new PowerShellTool(), commandInput("remove-item -Recurse -Force C:\\temp"), null);
            assertThat(hit).as("remove-item 应命中 PowerShell(Remove-Item:*)").isNotNull();
            assertThat(hit.ruleValue().ruleContent()).isEqualTo("Remove-Item:*");
        }

        @Test
        @DisplayName("1a：PowerShell(Get-Process) deny 精确规则可达，大小写不敏感")
        void powerShellDenyRule_exact_reachable() {
            ToolPermissionContext permCtx = denyCtx(rule("PowerShell", "Get-Process"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new PowerShellTool(), commandInput("GET-PROCESS"), null);
            assertThat(hit).as("GET-PROCESS 应命中 PowerShell(Get-Process)").isNotNull();
        }

        @Test
        @DisplayName("1a：PowerShell 内容不匹配 → null")
        void powerShellDenyRule_noMatch() {
            ToolPermissionContext permCtx = denyCtx(rule("PowerShell", "Get-Process"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new PowerShellTool(), commandInput("Get-Process chrome"), null);
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
                permCtx, new PowerShellTool(), commandInput("write-host hello"), null);
            assertThat(hit).as("write-host hello 应命中 PowerShell(Write-Host *)").isNotNull();
        }

        @Test
        @DisplayName("回归：Bash 大小写敏感语义不受 PowerShell 路径影响")
        void bashCaseSensitivity_preserved() {
            // NPM PUBLISH 大写规则不得命中小写命令（Bash 仍大小写敏感）
            ToolPermissionContext denyUpper = denyCtx(rule("Bash", "NPM PUBLISH:*"));
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                denyUpper, new BashTool(), commandInput("npm publish --access public"), null))
                .as("Bash 前缀匹配保持大小写敏感，NPM PUBLISH:* 不得命中 npm publish").isNull();
            // 大写命令命中大写规则
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                denyUpper, new BashTool(), commandInput("NPM PUBLISH --access public"), null))
                .as("NPM PUBLISH --access public 应命中 NPM PUBLISH:*").isNotNull();
        }

        @Test
        @DisplayName("回归：Bash(npm publish:*) 前缀规则仍命中")
        void bashPrefixRule_stillMatches() {
            ToolPermissionContext permCtx = denyCtx(rule("Bash", "npm publish:*"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new BashTool(), commandInput("npm publish --access public"), null);
            assertThat(hit).as("npm publish --access public 应命中 Bash(npm publish:*)").isNotNull();
        }

        @Test
        @DisplayName("[P19] Edit 路径 glob 规则经 matchesContent 入口仍命中（root 锚 = 显式 cwd）")
        void editGlobRule_stillMatches(@TempDir java.nio.file.Path workspace) {
            // [P19] 本入口（getDenyRuleByContentsForTool）的 PathTool 分支已改 root-relative。
            //   source=SESSION ⇒ `/…` 根 = cwd（CC rootPathForSource filesystem.ts:748-751），
            //   故规则路径与待匹配路径必须相对<b>同一</b> cwd 表达。
            //   ⚠️ 旧断言（规则 "/Users/foo/**" + 绝对 target + cwd=null）编码的是「绝对串 glob」
            //   ——正是轴 C 的缺陷本身，不能保留。
            ToolPermissionContext permCtx = denyCtx(rule("Edit", "/sub/**"));
            PermissionRule hit = RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new EditFileTool(new PathGuard(workspace)),
                filePathInput(workspace.resolve("sub/bar.txt").toString()),
                workspace.toString());
            assertThat(hit).as("cwd/sub/bar.txt 应命中 Edit(/sub/**)（根锚 = cwd）").isNotNull();
        }

        @Test
        @DisplayName("[P19] Read 无前缀精确规则按 cwd 相对匹配（root=cwd），越界不命中")
        void readExactRule_stillMatches(@TempDir java.nio.file.Path workspace) {
            // [P19] 对齐 CC patternWithRoot 无前缀分支（filesystem.ts:906-916）：root=null
            //   ⇒ matchingRuleForInput 取 getCwd() 当根（:991-992）。规则 "secret.txt" 因此
            //   表达「<cwd>/secret.txt」而非「任意位置的 secret.txt」。
            ToolPermissionContext permCtx = denyCtx(rule("Read", "secret.txt"));
            ReadFileTool readTool = new ReadFileTool(new PathGuard(workspace));
            String cwd = workspace.toString();
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                permCtx, readTool, filePathInput("secret.txt"), cwd))
                .as("无前缀规则 root=cwd ⇒ cwd/secret.txt 命中").isNotNull();
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                permCtx, readTool, filePathInput("other.txt"), cwd))
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
    // P19 · Read 桶的根锚（轴 C · 两桶同根）
    //
    // 对齐 CC getPatternsByRoot（claude-code-best/src/utils/permissions/filesystem.ts:919-953）：
    // {@code toolType='edit'}（:926-928 → FILE_EDIT_TOOL_NAME）与 {@code toolType='read'}
    // （:929-931 → FILE_READ_TOOL_NAME）在 :943 <b>共同</b>落到
    // {@code patternWithRoot(pattern, rule.source)} ⇒ <b>两桶共用一份根锚</b>，差异只在
    // 「哪些规则进桶」（CC 按 toolName 过滤），不在「怎么锚根」。
    // 匹配侧 {@code matchingRuleForInput}（:955-1025）同样不分桶：按 root 分桶后
    // {@code relativePath(root ?? getCwd(), fileAbsolutePath)}，{@code ..} 开头 ⇒ 越界不匹配（:998-1003）。
    //
    // WHY：P14 只把 <b>Edit 桶</b>（getEditRuleByContentsForPath）改成 root-relative，
    //   <b>Read 桶</b>（matchesContent → matchRuleContent 的 PathTool 分支）仍走
    //   {@code matchesGlob(ruleContent, 绝对路径)} —— 无根锚。⇒ 同一 {@code /agents/**}
    //   规则，Read 桶按绝对串 glob 判、Edit 桶按根锚相对判，<b>同规则两桶结论可相反</b>
    //   （用户级规则误命中/该命中不命中，权限判定错位且用户看不出原因）。
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("P19 · Read 桶根锚（CC patternWithRoot：edit/read 两桶同一份根）")
    class P19ReadBucketRootAnchorTests {

        @AfterEach
        void clearConfigHomeOverride() {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }

        /** Read 工具实例（真实工具，非 mock——走 matchesContent 的 tool.name() 门控）。 */
        private ReadFileTool readTool(Path workspace) {
            return new ReadFileTool(new PathGuard(workspace));
        }

        private PermissionRule contentRule(PermissionRuleSource source, String toolName, String content) {
            return new PermissionRule(source, PermissionBehavior.DENY,
                PermissionRuleValue.withContent(toolName, content));
        }

        private ToolPermissionContext denyCtxForSource(PermissionRuleSource source, PermissionRule rule) {
            Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
            deny.put(source, Set.of(rule));
            return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
        }

        /** Edit 桶（P14 已 root-relative）对该 path 的判定。 */
        private PermissionRule editBucket(ToolPermissionContext permCtx, String path, String cwd) {
            return RuleQuery.getEditRuleByContentsForPath(
                permCtx, path, PermissionBehavior.DENY, cwd);
        }

        /** Read 桶（本批改造对象）对同一 path 的判定。 */
        private PermissionRule readBucket(
                ToolPermissionContext permCtx, Tool tool, String path, String cwd) {
            return RuleQuery.getDenyRuleByContentsForTool(
                permCtx, tool, filePathInput(path), cwd);
        }

        // ── 1. 两桶一致（本批核心不变量） ──────────────────────────────────

        @Test
        @DisplayName("两桶一致 · USER_SETTINGS 的 `/agents/**`：两桶对同一 path 判定必须相同（根内命中）")
        void sameRuleContent_editAndReadBuckets_agreeInRoot(
                @TempDir Path configHome, @TempDir Path projectDir) {
            // WHY（轴 C）：CC 两桶同一份 patternWithRoot ⇒ 同 ruleContent / 同 source / 同 path
            //   必须同结论。若两桶仍用两套模型，用户级规则会在「读」与「写」上给出相反答案 ——
            //   用户看不到原因，且 deny/allow 的语义随工具漂移。
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
            String inRoot = configHome.resolve("agents/x.md").toString();

            PermissionRule editHit = editBucket(
                denyCtxForSource(PermissionRuleSource.USER_SETTINGS,
                    contentRule(PermissionRuleSource.USER_SETTINGS, "Edit", "/agents/**")),
                inRoot, projectDir.toString());
            PermissionRule readHit = readBucket(
                denyCtxForSource(PermissionRuleSource.USER_SETTINGS,
                    contentRule(PermissionRuleSource.USER_SETTINGS, "Read", "/agents/**")),
                readTool(projectDir), inRoot, projectDir.toString());

            assertThat(readHit)
                .as("Read 桶也必须锚 configHome（CC filesystem.ts:943 两桶共用 patternWithRoot）")
                .isNotNull();
            assertThat(editHit != null)
                .as("两桶结论必须一致：edit=%s read=%s", editHit, readHit)
                .isEqualTo(readHit != null);
        }

        @Test
        @DisplayName("两桶一致 · USER_SETTINGS 的 `/agents/**`：⛔ 两桶都不得锚 cwd（根外不命中）")
        void sameRuleContent_editAndReadBuckets_agreeOutsideRoot(
                @TempDir Path configHome, @TempDir Path projectDir) {
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
            String outside = projectDir.resolve("agents/x.md").toString();

            PermissionRule editHit = editBucket(
                denyCtxForSource(PermissionRuleSource.USER_SETTINGS,
                    contentRule(PermissionRuleSource.USER_SETTINGS, "Edit", "/agents/**")),
                outside, projectDir.toString());
            PermissionRule readHit = readBucket(
                denyCtxForSource(PermissionRuleSource.USER_SETTINGS,
                    contentRule(PermissionRuleSource.USER_SETTINGS, "Read", "/agents/**")),
                readTool(projectDir), outside, projectDir.toString());

            assertThat(readHit)
                .as("用户级 `/…` 规则不得锚 cwd —— 否则用户级配置会去命中<b>项目</b>路径（误 deny）")
                .isNull();
            assertThat(editHit).as("Edit 桶同一 path 同样不命中（P14 已钉）").isNull();
        }

        // ── 2. 根锚生效 / 越界不匹配 ──────────────────────────────────────

        @Test
        @DisplayName("Read 桶 · 非 USER_SETTINGS 源（session）的 `/…` 规则锚 cwd（防「一律 configHome」过度纠正）")
        void readBucket_slashRule_anchorsCwdForNonUserSettings(
                @TempDir Path configHome, @TempDir Path projectDir) {
            // CC rootPathForSource（filesystem.ts:746-758）：只有 userSettings 走 settings 根，
            //   session/cliArg/command → getOriginalCwd()（= 会话 cwd）。
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
            PermissionRule rule = contentRule(PermissionRuleSource.SESSION, "Read", "/agents/**");

            assertThat(readBucket(
                denyCtxForSource(PermissionRuleSource.SESSION, rule),
                readTool(projectDir), projectDir.resolve("agents/x.md").toString(), projectDir.toString()))
                .as("session 源 `/agents/**` 根 = cwd ⇒ 命中 cwd/agents/x.md")
                .isNotNull();
            assertThat(readBucket(
                denyCtxForSource(PermissionRuleSource.SESSION, rule),
                readTool(projectDir), configHome.resolve("agents/x.md").toString(), projectDir.toString()))
                .as("session 源不得锚 configHome（那是 userSettings 的根）")
                .isNull();
        }

        @Test
        @DisplayName("Read 桶 · 越界不匹配：cwd 之外的路径（`..` 语义）不命中无前缀相对规则")
        void readBucket_outsideCwd_noMatch(@TempDir Path projectDir, @TempDir Path otherDir) {
            // CC matchingRuleForInput :998-1003：relativePath 以 `..` 开头 ⇒ continue（不匹配）。
            PermissionRule rule = contentRule(PermissionRuleSource.SESSION, "Read", "sub/file.txt");
            ToolPermissionContext permCtx =
                denyCtxForSource(PermissionRuleSource.SESSION, rule);

            assertThat(readBucket(permCtx, readTool(projectDir),
                projectDir.resolve("sub/file.txt").toString(), projectDir.toString()))
                .as("root 内 cwd/sub/file.txt 命中").isNotNull();
            assertThat(readBucket(permCtx, readTool(projectDir),
                otherDir.resolve("sub/file.txt").toString(), projectDir.toString()))
                .as("root 之外（rel 以 .. 开头）⇒ 不命中（CC :998-1003）").isNull();
        }

        @Test
        @DisplayName("Read 桶 · `~/…` 规则锚家目录（旧绝对串 glob 下恒不命中）")
        void readBucket_tildeRule_anchorsHome(@TempDir Path projectDir) {
            // CC patternWithRoot :893-898：`~/…` 根 = homedir().normalize('NFC')。
            PermissionRule rule = contentRule(PermissionRuleSource.SESSION, "Read", "~/.claude/**");
            String homeTarget = Path.of(System.getProperty("user.home"), ".claude", "x.md").toString();

            assertThat(readBucket(denyCtxForSource(PermissionRuleSource.SESSION, rule),
                readTool(projectDir), homeTarget, projectDir.toString()))
                .as("`~/.claude/**` 必须锚家目录才可能命中（旧实现把 `~` 当字面字符 ⇒ 永不命中）")
                .isNotNull();
        }

        @Test
        @DisplayName("Read 桶 · 裸绝对路径规则（无 `//` 前缀）按 cwd 相对 ⇒ 不命中文件系统绝对路径")
        void readBucket_bareAbsoluteRule_isCwdRelative(@TempDir Path projectDir) {
            // CC patternWithRoot：只有 `//…` 才是文件系统根；裸 `/etc/passwd` 走 `DIR_SEP` 分支
            //   ⇒ root = rootPathForSource(source)。source=SESSION ⇒ root=cwd ⇒ 该规则表达
            //   `<cwd>/etc/passwd`，不是 `/etc/passwd`。与 Edit 桶（EditFileToolErrorCode2Test
            //   已钉同一语义）一致 —— 这正是两桶同根的判别式。
            PermissionRule rule = contentRule(PermissionRuleSource.SESSION, "Read", "/etc/passwd");
            assertThat(readBucket(denyCtxForSource(PermissionRuleSource.SESSION, rule),
                readTool(projectDir), "/etc/passwd", projectDir.toString()))
                .as("裸绝对路径规则按 cwd 相对解析 ⇒ 文件系统 /etc/passwd 不在 cwd 下 ⇒ 不命中"
                    + "（要表达文件系统根须写 `//etc/passwd`）")
                .isNull();
            assertThat(editBucket(denyCtxForSource(PermissionRuleSource.SESSION,
                contentRule(PermissionRuleSource.SESSION, "Edit", "/etc/passwd")),
                "/etc/passwd", projectDir.toString()))
                .as("Edit 桶同一规则同结论（两桶同根，不得只改一侧）")
                .isNull();
        }

        // ── 3. 反向鉴别：Bash/PowerShell 分支不得消费 cwd ──────────────────

        @Test
        @DisplayName("非 PathTool 分支不消费 cwd：Bash 前缀规则在任意 cwd 下结论不变")
        void nonPathToolBranch_ignoresCwd(@TempDir Path projectDir) {
            // WHY：cwd 形参是为 PathTool 的根锚定新增的。若误把它喂给 Bash/PowerShell 匹配，
            //   命令规则语义会被路径域污染（权限判定错位）。
            PermissionRule bashRule = contentRule(
                PermissionRuleSource.SESSION, "Bash", "npm publish:*");
            ToolPermissionContext permCtx =
                denyCtxForSource(PermissionRuleSource.SESSION, bashRule);
            ObjectNode input = commandInput("npm publish --access public");

            assertThat(RuleQuery.getDenyRuleByContentsForTool(permCtx, new BashTool(), input, null))
                .as("cwd=null 命中 Bash(npm publish:*)").isNotNull();
            assertThat(RuleQuery.getDenyRuleByContentsForTool(
                permCtx, new BashTool(), input, projectDir.toString()))
                .as("cwd!=null 结论必须相同（Bash 分支不消费 cwd）").isNotNull();
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
    // WF-1D · DEL-06 · getEditRuleByContentsForPath 的 root-relative 匹配基准 cwd
    // 对齐 CC resolve(cwd, path) cwd=getCwd()（bashPermissions.ts:1114 传 getCwd()）。
    // WHY：root-relative 匹配的根锚定错根 → 相对路径 edit 规则在该会话内永不命中（权限判定错位，G9）。
    //
    // [批 3c] 语义已变：{@code RuleQuery} 是**静态工具、无会话入参** ⇒ 传 {@code cwd=null} 时显式按
    //   「无会话」解析 {@code CwdResolution.getCwd(null)}（= override / 进程 user.dir），主代码有注释
    //   + 首次 WARN 留痕，并明写「需要会话 cwd 的调用方须显式传 cwd（工具侧 ctx.effectiveCwd()）」。
    //   ⇒ 原「绑定项目经 ambient 会话槽解析」已不可构造；会话感知一律走**显式 cwd 形参**
    //   （下方 cwdExplicit_* 用例），「确无会话」走**显式 null**（下方 cwdNull_* 用例）。
    // [欠账清理批] 原 3 参重载（隐式 cwd=null）已删（全仓零调用方 + CC 无对应物）⇒ 每个调用方
    //   必须显式声明自己的校验基准；下面 cwdNull_* 用例改由 4 参 + 显式 null 表达同一契约。
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WF-1D · DEL-06 · getEditRuleByContentsForPath 的 root 锚定（显式 cwd vs 无会话回落）")
    class Wf1dBaseDirFallbackTests {

        @AfterEach
        void clearCwdState() {
            SessionProjectRoot.reset();
        }

        @Test
        @DisplayName("绑定项目 + 显式 cwd（= ctx.effectiveCwd 等价）→ root-relative 相对规则锚定 boundProject 命中（非 user.dir）")
        void cwdExplicit_usesBoundProjectAsRoot(@TempDir Path projectDir) throws Exception {
            // WHY: CC matchingRuleForInput 的 patternWithRoot 对无前缀规则 root=cwd=getCwd()。
            //   绑定项目场景 cwd 必须取会话项目的 cwd，否则相对规则 "sub/file.txt" 锚 user.dir
            //   而 target=boundProject/sub/file.txt 在 user.dir 之外 → rel=null → 不匹配 →
            //   edit allow 规则失效（应放行的写入被误 ask/deny）。
            // [批 3c] 会话来源 = **显式 cwd 形参**（调用方持 ctx.effectiveCwd() / 本测试持
            //   CwdResolution.getCwd(sessionId)）—— 原「经 ambient 会话槽解析」的装置已删。
            String sessionId = "wf1d-ruleq-sess";
            SessionProjectRoot.setForSession(sessionId, projectDir.toString());

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

            String sessionCwd = CwdResolution.getCwd(sessionId);
            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                permCtx, targetPath, PermissionBehavior.ALLOW, sessionCwd);

            assertThat(hit)
                .as("会话项目 cwd 显式传入时，相对规则必须锚定它才能命中（G9 不得复现）")
                .isNotNull()
                .isEqualTo(allowRule);
            assertThat(sessionCwd)
                .as("CwdResolution.getCwd(sessionId) 解析为 boundProject（统一入口）")
                .isEqualTo(projectDir.toRealPath().toString());
        }

        @Test
        @DisplayName("[批 3c] 显式 cwd=null → 按「无会话」解析回落 user.dir：绑定项目不可达，路径在 user.dir 外即不命中")
        void cwdNull_fallsBackToUserDir_noSession(@TempDir Path projectDir) throws Exception {
            // WHY（新契约）：{@code RuleQuery} 无会话入参 ⇒ 显式传 cwd=null 时只能按「无会话」解析。
            //   本用例钉住该回落（并作为「有人把会话接回静态工具」的反向鉴别器：若 boundProject
            //   被重新读到，下面 hit 会变非 null ⇒ 红）。
            String sessionId = "wf1d-ruleq-nosession";
            SessionProjectRoot.setForSession(sessionId, projectDir.toString());

            PermissionRule allowRule = new PermissionRule(
                PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Edit", "sub/file.txt"));
            Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
            allow.put(PermissionRuleSource.SESSION, Set.of(allowRule));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());

            // projectDir 不在 user.dir 下 → rel=null → 不匹配
            String targetPath = projectDir.resolve("sub/file.txt").toString();
            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                permCtx, targetPath, PermissionBehavior.ALLOW, null);

            assertThat(hit)
                .as("无会话（cwd=null）→ 基准回落 user.dir + 路径在 user.dir 之外 → 不命中")
                .isNull();
            assertThat(CwdResolution.getCwd(null))
                .as("无会话（null）回落 user.dir（经统一入口）")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        }

        @Test
        @DisplayName("未绑定 + 显式 cwd → 与 user.dir 一致时可命中（统一入口无会话语义）")
        void cwdExplicit_unboundFallsBackToUserDir(@TempDir Path projectDir) throws Exception {
            // WHY: 未绑定会话 boundProject=null → CwdResolution.getCwd(sessionId) 回落 user.dir（INV-4），
            //   不抛异常；该 cwd 作为 root 时，user.dir 之外的路径仍不命中（行为不变，无回归）。
            String sessionId = "wf1d-ruleq-unbound";
            // 不绑定 SessionProjectRoot

            PermissionRule allowRule = new PermissionRule(
                PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Edit", "sub/file.txt"));
            Map<PermissionRuleSource, Set<PermissionRule>> allow = new EnumMap<>(PermissionRuleSource.class);
            allow.put(PermissionRuleSource.SESSION, Set.of(allowRule));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());

            String targetPath = projectDir.resolve("sub/file.txt").toString();
            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                permCtx, targetPath, PermissionBehavior.ALLOW, CwdResolution.getCwd(sessionId));

            assertThat(hit)
                .as("未绑定 + 路径在 user.dir 之外 → 不命中（行为不变，无回归）")
                .isNull();
            assertThat(CwdResolution.getCwd(sessionId))
                .as("未绑定回落 user.dir（经统一入口）")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // P14 · Edit 规则 `/…` 的<b>根按规则来源（source）分派</b>
    //
    // 对齐 CC rootPathForSource（claude-code-best/src/utils/permissions/filesystem.ts:746-758）
    // + getSettingsRootPathForSource（claude-code-best/src/utils/settings/settings.ts:239-253）：
    // CC 的 patternWithRoot `DIR_SEP` 分支（filesystem.ts:899-905）取
    // `root = rootPathForSource(source)`，⛔ <b>不是恒等于 cwd</b>。逐源映射：
    // <ul>
    //   <li>{@code userSettings} → {@code getClaudeConfigHomeDir()}（CC envUtils.ts:7-14）；</li>
    //   <li>{@code projectSettings} / {@code localSettings} / {@code policySettings}
    //       → {@code getOriginalCwd()}（settings.ts:243-246）；</li>
    //   <li>{@code cliArg} / {@code command} / {@code session} → {@code getOriginalCwd()}
    //       （filesystem.ts:748-751）；</li>
    //   <li>{@code flagSettings} → {@code dirname(--settings 路径)}，路径不可得时回落
    //       {@code getOriginalCwd()}（settings.ts:248-251）—— nexusai web 无 CLI flag
    //       （{@code FlagSettingsLoader} 永远 empty）⇒ 恒取回落值 cwd。</li>
    // </ul>
    //
    // WHY：此前 `/…` 分支一律取 {@code cwd} 当根（源码注释自陈「settings 源≈cwd 近似」），
    //   等于把<b>用户级</b> settings 里 `Edit(/…/**)` 表达的「用户配置根下的路径」错锚到
    //   <b>项目</b>根 ⇒ 同一规则「Edit 命中 / 用户看不出为何失效」；反过来项目路径也可能
    //   被用户级规则误命中。本 @Nested 钉住「⛔ 别把所有源都改成 configHome」（防过度纠正）。
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("P14 · Edit 规则 `/…` 根按 source 分派（CC rootPathForSource）")
    class P14SlashRootPerSourceTests {

        @AfterEach
        void clearConfigHomeOverride() {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }

        private PermissionRule slashRule(PermissionRuleSource source, String content) {
            return new PermissionRule(source, PermissionBehavior.DENY,
                PermissionRuleValue.withContent("Edit", content));
        }

        private ToolPermissionContext denyCtxFor(PermissionRuleSource source, PermissionRule rule) {
            Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
            deny.put(source, Set.of(rule));
            return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
        }

        @Test
        @DisplayName("USER_SETTINGS + Edit(/agents/**) → 锚 configHome：命中 configHome/agents/x.md")
        void userSettingsSlashRule_anchorsConfigHome(@TempDir Path configHome, @TempDir Path projectDir) {
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
            PermissionRule rule = slashRule(PermissionRuleSource.USER_SETTINGS, "/agents/**");

            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                denyCtxFor(PermissionRuleSource.USER_SETTINGS, rule),
                configHome.resolve("agents/x.md").toString(),
                PermissionBehavior.DENY,
                projectDir.toString());

            assertThat(hit)
                .as("CC `userSettings` 的 `/…` 根 = config home（filesystem.ts:752-757）⇒ "
                    + "用户级 Edit(/agents/**) 必须命中 configHome/agents/x.md")
                .isNotNull()
                .isEqualTo(rule);
        }

        @Test
        @DisplayName("USER_SETTINGS + Edit(/agents/**) → ⛔不锚 cwd：不命中 cwd/agents/x.md")
        void userSettingsSlashRule_doesNotAnchorCwd(@TempDir Path configHome, @TempDir Path projectDir) {
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
            PermissionRule rule = slashRule(PermissionRuleSource.USER_SETTINGS, "/agents/**");

            PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                denyCtxFor(PermissionRuleSource.USER_SETTINGS, rule),
                projectDir.resolve("agents/x.md").toString(),
                PermissionBehavior.DENY,
                projectDir.toString());

            assertThat(hit)
                .as("用户级 `/…` 规则不得再锚 cwd —— 否则用户级配置会去命中<b>项目</b>路径（误 deny）")
                .isNull();
        }

        @Test
        @DisplayName("非 USER_SETTINGS 源（session/cliArg/command/project/local/policy/flag）仍锚 cwd —— 防过度纠正")
        void nonUserSettingsSources_stillAnchorCwd(@TempDir Path configHome, @TempDir Path projectDir) {
            NexusaiPaths.setConfigHomeDirOverride(configHome.toString());

            for (PermissionRuleSource source : List.of(
                    PermissionRuleSource.SESSION,
                    PermissionRuleSource.CLI_ARG,
                    PermissionRuleSource.COMMAND,
                    PermissionRuleSource.PROJECT_SETTINGS,
                    PermissionRuleSource.LOCAL_SETTINGS,
                    PermissionRuleSource.POLICY_SETTINGS,
                    PermissionRuleSource.FLAG_SETTINGS)) {
                PermissionRule rule = slashRule(source, "/agents/**");

                PermissionRule hit = RuleQuery.getEditRuleByContentsForPath(
                    denyCtxFor(source, rule),
                    projectDir.resolve("agents/x.md").toString(),
                    PermissionBehavior.DENY,
                    projectDir.toString());

                assertThat(hit)
                    .as("source=%s 的 `/agents/**` 仍须锚 cwd（CC rootPathForSource 只让 userSettings "
                        + "走 config home；policySettings 在 CC 也是 getOriginalCwd）", source)
                    .isNotNull()
                    .isEqualTo(rule);
            }
        }
    }
}
