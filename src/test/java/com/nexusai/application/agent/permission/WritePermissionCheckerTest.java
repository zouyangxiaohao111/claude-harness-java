package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-AL-02 · {@link WritePermissionChecker} 全链测试（CC {@code filesystem.ts:1205-1412
 * checkWritePermissionForTool} 九步决策链）。
 *
 * <p><b>对齐锚点（CC 真源，行号当次 read 自验）</b>：
 * <ol>
 *   <li>deny（:1219-1239）→ 1.5 internal-path（:1241-1250，Java N/A passthrough）→
 *       1.6 .claude/** session allow（:1252-1300，session-only 桶 + 范围校验）→
 *       1.7 安全检查（:1302-1338）→ ask rule（:1340-1358）→
 *       acceptEdits+workingdir（:1360-1375）→ allow rule（:1377-1393）→ 兜底 ask（:1395-1411）。</li>
 *   <li>规则匹配仅限 content 规则：matchingRuleForInput → getRuleByContentsForToolName 过滤
 *       {@code ruleContent !== undefined}（permissions.ts:380-388）——whole-tool allow 不命中。</li>
 *   <li>1.6 范围校验（:1281-1290）：ruleContent 以 '/.claude/' 或 '~/.claude/' 开头、
 *       不含 '..'、以 '/**' 结尾（CLAUDE_FOLDER_PERMISSION_PATTERN / GLOBAL 常量
 *       FileEditTool/constants.ts:5/:8）。</li>
 *   <li>1.7 三道安全检查（:630-661）：suspicious windows（classifierApprovable=false）→
 *       claude config（true）→ dangerous file/dir（true），顺序固定。</li>
 * </ol>
 *
 * <p><b>路径选择注意</b>：本机 {@code %TEMP%} 含 8.3 短名（{@code ADMINI~1.DES}）会命中
 * suspicious-Windows-pattern，测试统一用项目 {@code target/} 下路径与合成路径；
 * input 路径用绝对路径（Java content-rule 匹配为 glob 直比，不做 root-relative 归一）。
 */
@DisplayName("P-AL-02 · WritePermissionChecker 全链（CC filesystem.ts:1205-1412）")
class WritePermissionCheckerTest {

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 的类 javadoc。

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 13 参工厂：显式 effectiveCwd（null 会被 ToolUseContext 归一为进程 CWD）。 */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ctx(permCtx, effectiveCwd, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * [OD-FINAL-3b] 显式 sessionId 重载：供需控制 SessionCwdHolder originalCwd 槽的用例
     * （白名单锚已收敛到 CwdResolution.getOriginalCwdLayer，对齐 CC allWorkingDirectories
     * 锚 getOriginalCwd；effectiveCwd 不再作白名单锚，仅 expandPath 相对路径基准）。
     */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd, String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext rulesCtx(
            PermissionMode mode,
            Map<PermissionRuleSource, Set<PermissionRule>> allow,
            Map<PermissionRuleSource, Set<PermissionRule>> deny,
            Map<PermissionRuleSource, Set<PermissionRule>> ask) {
        return ToolPermissionContext.of(mode, allow, deny, ask, Map.of());
    }

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 工作目录（兄弟目录，与目标路径互不包含）。 */
    private static Path cwdDir() {
        return Paths.get("target", "pal02-cwd-" + rand());
    }

    /** 目标文件所在目录（工作目录外；无 8.3 短名/ADS 等可疑模式）。 */
    private static Path targetDir() {
        return Paths.get("target", "pal02-" + rand());
    }

    /**
     * root-relative glob 规则内容 · 对齐 CC patternWithRoot（filesystem.ts:853-917）：
     * 绝对路径加 {@code //} 前缀 → 文件系统根锚定（Windows 盘符形 {@code //c/...}），
     * 匹配目标经 expandPath 展开后与根做相对路径比对（OPD-WF5-FS-052 重构对齐）。
     */
    private static String toGlob(Path dir) {
        String abs = dir.toAbsolutePath().toString();
        if (abs.matches("^[a-zA-Z]:.*")) {
            // D:\code\... → //d/code/...（CC :867-887 Windows 盘符根）
            return "//" + abs.substring(0, 1).toLowerCase() + abs.substring(2).replace('\\', '/') + "/**";
        }
        return "//" + abs.replace('\\', '/') + "/**";
    }

    private static String targetFile(Path dir) {
        return dir.toAbsolutePath().resolve("a.txt").toString();
    }

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior, String content) {
        return new PermissionRule(source, behavior, PermissionRuleValue.withContent("Edit", content));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. deny / ask / allow 规则链
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edit deny content 规则 → Deny(Rule)")
    void editContentDeny_returnsDeny() {
        Path dir = targetDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, toGlob(dir)))), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(dir));

        PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("CC: decisionReason=Rule(deny rule)（filesystem.ts:1229-1238）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("edit deny content 规则 + 相对 file_path 经 backfill 绝对化 → Deny(Rule)（FIX-A-R2）")
    void editContentDeny_relativeFilePath_backfilledThenDenies() {
        // 相对展开基座必须是绝对路径（镜像 CC getCwd() 绝对语义）
        Path dir = targetDir().toAbsolutePath();
        PathGuard guard = new PathGuard(dir);
        ReadFileTool tool = new ReadFileTool(guard);
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, toGlob(dir)))), Map.of()), dir);
        WritePermissionChecker checker = new WritePermissionChecker();

        // 镜像 StreamingToolExecutor 把 backfilledInput（file_path 绝对化）透传给 permission 门
        JsonNode backfilled = tool.backfillObservableInput(input("secret/a.txt"));

        PermissionResult result = checker.check(tool, backfilled, ctx);

        assertThat(result)
            .as("CC: 相对 file_path 经 backfill 绝对化后命中 edit deny 规则（防相对路径绕过权限门）")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("CC: decisionReason=Rule(deny rule)（filesystem.ts:1219-1239）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("edit ask content 规则 → Ask(Rule)")
    void editContentAsk_returnsAsk() {
        Path dir = targetDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ASK, toGlob(dir))))), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(dir));

        PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("CC: decisionReason=Rule(ask rule)（filesystem.ts:1348-1357）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("edit allow content 规则 → Allow(Rule)")
    void editContentAllow_returnsAllow() {
        Path dir = targetDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, toGlob(dir)))), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(dir));

        PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("CC: decisionReason=Rule(allow rule)（filesystem.ts:1384-1392）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("edit whole-tool allow（无 ruleContent）→ 不命中（CC content-only），工作目录外兜底 Ask")
    void wholeToolAllow_notMatched_defaultAsk() {
        Path dir = targetDir();
        PermissionRule wholeTool = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, PermissionRuleValue.wholeTool("Edit"));
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION, Set.of(wholeTool)), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(dir));

        PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

        assertThat(result)
            .as("CC: matchingRuleForInput 过滤 ruleContent===undefined（permissions.ts:380-388）→ whole-tool allow 不命中步骤 4")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. acceptEdits 模式 + 工作目录
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("acceptEdits 模式 + 工作目录内 → Allow(Mode ACCEPT_EDITS)")
    void acceptEdits_inWorkingDir_allow() {
        Path dir = targetDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.ACCEPT_EDITS, Map.of(), Map.of(), Map.of()), dir);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(dir));

        PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("CC: decisionReason=Mode(acceptEdits)（filesystem.ts:1366-1374）")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS));
    }

    @Test
    @DisplayName("acceptEdits 模式但工作目录外 → 兜底 Ask（CC: 需 && isInWorkingDir）")
    void acceptEdits_outsideWorkingDir_defaultAsk() {
        Path dir = targetDir();
        Path cwd = cwdDir();
        // [OD-FINAL-3b] 白名单锚=getOriginalCwdLayer（CC allWorkingDirectories 锚 getOriginalCwd）。
        // 显式设会话 originalCwd=cwdDir，使 targetDir（兄弟目录）落在白名单外，对齐 CC
        // originalCwd 会话稳定语义（cd 不改白名单根）。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionCwdHolder.setOriginalCwd(sessionId.toString(), cwd.toAbsolutePath().toString());
        try {
            ToolUseContext ctx = ctx(rulesCtx(PermissionMode.ACCEPT_EDITS, Map.of(), Map.of(), Map.of()), cwd, sessionId);
            WritePermissionChecker checker = new WritePermissionChecker();
            Tool tool = new ReadFileTool(new PathGuard(cwd));

            PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

            assertThat(result)
                .as("CC: acceptEdits && isInWorkingDir 双条件（filesystem.ts:1366）→ 目录外不自动放行")
                .isInstanceOf(PermissionResult.Ask.class);
            assertThat(((PermissionResult.Ask) result).reason())
                .as("CC: 目录外兜底 reason=workingDir（filesystem.ts:1405-1410）")
                .isInstanceOf(PermissionDecisionReason.WorkingDir.class);
        } finally {
            SessionCwdHolder.clearOriginalCwd(sessionId.toString());
        }
    }

    @Test
    @DisplayName("无规则 + 工作目录外 → 兜底 Ask(WorkingDir)")
    void defaultAsk_outsideWorkingDir() {
        Path dir = targetDir();
        Path cwd = cwdDir();
        // [OD-FINAL-3b] 白名单锚=getOriginalCwdLayer；设 originalCwd=cwdDir 使 targetDir 在白名单外。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionCwdHolder.setOriginalCwd(sessionId.toString(), cwd.toAbsolutePath().toString());
        try {
            ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd, sessionId);
            WritePermissionChecker checker = new WritePermissionChecker();
            Tool tool = new ReadFileTool(new PathGuard(cwd));

            PermissionResult result = checker.check(tool, input(targetFile(dir)), ctx);

            assertThat(result).isInstanceOf(PermissionResult.Ask.class);
            assertThat(((PermissionResult.Ask) result).reason())
                .isInstanceOf(PermissionDecisionReason.WorkingDir.class);
        } finally {
            SessionCwdHolder.clearOriginalCwd(sessionId.toString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1.6 .claude/** session allow
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(".claude/** session allow 范围校验通过 → Allow（安全检查前放行）")
    void claudeFolderSessionAllow_scopeOk_allow() {
        // 合成路径 '~/.claude/skills/foo/bar.md'：glob 字面匹配 '~' 前缀（CC GLOBAL 模式族）
        String pattern = "~/.claude/skills/foo/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("~/.claude/skills/foo/bar.md"), ctx);

        assertThat(result)
            .as("CC: 1.6 session 桶 .claude/** allow 在安全检查前放行（filesystem.ts:1252-1300）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName(".claude/** session allow 含 '..' → 范围校验失败 → 安全检查 Ask")
    void claudeFolderSessionAllow_scopeViolation_notAllow() {
        String pattern = "~/.claude/../**"; // CC :1288 '..' 拒绝，防逃逸
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("~/.claude/../escape.md"), ctx);

        assertThat(result)
            .as("CC: 范围校验（filesystem.ts:1281-1290）拒绝 '..' → 落入 1.7 安全检查（.claude 段危险 → Ask）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("安全检查 reason=SafetyCheck（filesystem.ts:1332-1336）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("非 session 源的 .claude allow → 1.6 不命中（session-only），安全检查 Ask")
    void claudeFolderAllow_userSettingsSource_notSessionOnly() {
        String pattern = "~/.claude/skills/foo/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.USER_SETTINGS, Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW, pattern))), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("~/.claude/skills/foo/bar.md"), ctx);

        assertThat(result)
            .as("CC: 1.6 仅 session 桶（filesystem.ts:1262-1272 session-only 上下文）→ userSettings 规则不能绕过安全检查")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // T3-exemption-prefix-same-source · 1.6 豁免前缀与自有根同源（去硬编码 .claude）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 自有根 skills 下的绝对路径（{@code {user.home}/.{appName}/skills/foo/bar.md}，POSIX 形）。
     *
     * <p>⛔ 刻意用真实 {@link NexusaiPaths#getAppConfigHomeDir()} 而非 {@code @TempDir} 覆写：
     * 本组用例要证的正是「产出侧 pattern 能被消费侧 1.6 收下」，而 1.6 的
     * {@code '~/'} 根锚定为 {@code System.getProperty("user.home")}（RuleQuery
     * matchesPathRuleRootRelative）。若覆写 configHome 到临时目录，产出侧 base 段与
     * {@code '~/'} 前缀就不同源（该缺口属既有登记项，非本任务范围）⇒ 用例将无法覆盖生产形态。
     * 仅做路径字符串运算，不触碰磁盘。
     */
    private static String nexusaiSkillAbsPath(String skill) {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "skills", skill, "bar.md")
            .toString().replace('\\', '/');
    }

    /** 自有根 skills 路径的 {@code '~/'} 显示形（同一条路径的另一种 input 形态）。 */
    private static String nexusaiSkillTildePath(String skill, String file) {
        return "~/" + NexusaiPaths.getProjectDirName() + "/skills/" + skill + "/" + file;
    }

    @Test
    @DisplayName("自有根 skills 会话授权：getClaudeSkillScope 真实产出的 pattern 被 1.6 收下 → Allow(Rule)（前缀同源修复）")
    void nexusaiSkillRootSessionAllow_producedPatternIsAccepted() {
        // WHY：1.6 范围校验此前只接受 {'/.claude/','~/.claude/'}，而 getClaudeSkillScope 产出的第三条
        //   pattern 是 '~/.{appName}/skills/<name>/**' ⇒ 结构上必被拒 → 落 1.7 safety Ask
        //   （自有根段在 DANGEROUS_DIRECTORIES 判定内）→ 步骤 4（edit allow rule）不可达
        //   ⇒ 用户确认过的会话授权永不生效，每次同类调用重新弹窗。
        //   实机证据：sessions.session_permission_rules 已写入
        //   {"toolName":"Edit","ruleContent":"~/.nexusai/skills/tbox-generator/**"}，下次同类调用仍弹。
        //   因此判据不是「硬编码多一个 .nexusai 字符串」，而是「产出侧 pattern 必须被消费侧收下」。
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));
        String absPath = nexusaiSkillAbsPath("foo");

        // ① 走真实 1.7 安全检查 Ask，取出 getClaudeSkillScope 真实产出的 ruleContent
        //    （⛔ 不手写死串 —— 手写只能证明「我又写了一遍」，「同源」必须由产出侧自己给出）
        ToolUseContext probeCtx =
            ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        PermissionResult probe = checker.check(tool, input(absPath), probeCtx);
        assertThat(probe)
            .as("前置：自有根 skills 路径无会话规则时可编辑性未定 → 落 1.7 安全检查 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask probeAsk = (PermissionResult.Ask) probe;
        assertThat(probeAsk.suggestions())
            .as("1.7 安全检查 Ask 须附 getClaudeSkillScope 会话级 addRules 建议（filesystem.ts:1312-1327）")
            .hasSize(1);
        String produced = ((PermissionUpdate.AddRules) probeAsk.suggestions().get(0))
            .rules().get(0).ruleValue().ruleContent();
        assertThat(produced)
            .as("产出侧 pattern = '~/' + getProjectDirName() + '/skills/' + skillName + '/**'")
            .isEqualTo("~/" + NexusaiPaths.getProjectDirName() + "/skills/foo/**");

        // ② 把①的真实产出原样作为 SESSION allow 规则再跑同一路径 → 必须在 1.6（安全检查之前）放行
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION,
            Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, produced))),
            Map.of(), Map.of()), cwdDir());
        PermissionResult result = checker.check(tool, input(absPath), ctx);

        assertThat(result)
            .as("产出→消费同源：用户确认过的会话授权必须生效（不得再落 1.7 safety Ask）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("1.6 .claude/** session allow 命中：reason=Rule（filesystem.ts:1281-1300）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("自有根 skills 会话授权（'~/' 显示形 input 路径）→ 同样被 1.6 收下 → Allow(Rule)")
    void nexusaiSkillRootSessionAllow_tildeFormPath_allow() {
        // WHY：LLM 给的 file_path 可能是 '~/.nexusai/skills/foo/bar.md' 这种显示形（工具 getPath
        //   原样透传，1.6/步骤 4 按 CC filesystem.ts:1262 用原始 path 匹配）。若修复只对绝对形生效，
        //   显示形仍会弹窗 —— 实机证据里的授权内容就是 '~/.nexusai/...' 形态。
        String pattern = "~/" + NexusaiPaths.getProjectDirName() + "/skills/foo/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION,
            Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))),
            Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input(nexusaiSkillTildePath("foo", "bar.md")), ctx);

        assertThat(result)
            .as("'~/' 显示形同样走 1.6 范围校验 + root-relative 匹配（root=user.home）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("护栏·不得放宽到自有根：'~/.nexusai/**' 不在同源接受集 → 仍落 1.7 safety Ask")
    void nexusaiRootWildcard_stillSafetyAsk() {
        // WHY：安全边界不得因新增前缀而放宽。同源前缀必须停在 'skills/' 段 —— 若实现把前缀退化为
        //   '~/.{appName}/'（如为省事只取 getProjectDirName()），自有根下的 settings.json /
        //   hooks / 任意配置都会被 1.6 直接放行（属扩大豁免面）。本用例即该退化的判别器：
        //   规则在 1.6 root-relative 匹配层是命中的，唯因前缀不在接受集而被范围校验拒。
        String pattern = "~/" + NexusaiPaths.getProjectDirName() + "/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION,
            Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))),
            Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result =
            checker.check(tool, input(nexusaiSkillTildePath("foo", "bar.md")), ctx);

        assertThat(result)
            .as("豁免面未放宽：自有根级通配不得从 1.6 放行（否则 settings.json 一并放行）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("1.6 拒收 → 落 1.7 自有根危险段 Ask(SafetyCheck)")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("护栏·'..' 拒绝不得失效：'~/.nexusai/skills/../x/**' 不产生 1.6 Allow")
    void nexusaiSkillRootDotDot_stillSafetyAsk() {
        // WHY：安全边界不得因新增前缀而放宽。'..' 段是逃离 skills/ 的唯一后门。
        //   注（实测语义，非注释推断）：expandPathForMatch 会 normalize 目标（RuleQuery:1134
        //   abs.normalize()），故含 '..' 的 pattern 在 glob 层通常已无法命中；1.6 的 '..' 护栏
        //   与之互为纵深防御（CC filesystem.ts:1288 同款 isExcluded-style 守卫）。
        //   本用例锁定「无论如何不产生 1.6 Allow」这一结果。
        String pattern = "~/" + NexusaiPaths.getProjectDirName() + "/skills/../x/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION,
            Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))),
            Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool,
            input("~/" + NexusaiPaths.getProjectDirName() + "/skills/../x/y.md"), ctx);

        assertThat(result)
            .as("'..' 逃逸用例不得被 1.6 放行（否则可借 skills/../ 授出 skills/ 之外的写权限）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("落 1.7 自有根危险段 Ask(SafetyCheck)")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("机制钉桩：'~/evil/**' 的终点是步骤 4（完整 permCtx allow rule），不是 1.6")
    void arbitraryHomeRule_endsAtStep4Not16() {
        // WHY：钉住 1.6 的语义边界 —— 1.6 的范围校验只决定「是否在 1.7 安全门之前放行」，
        //   它不是 allow 规则的硬闸；同一条会话规则还能在步骤 4（filesystem.ts:1377-1393，
        //   用完整 permCtx 而非 session-only）命中。故「越界用例仍被拒」不能拿
        //   '~/evil/**' 当例子（该路径 1.7 三道检查全不触发，最终 Allow(Rule)），
        //   否则后人会把 1.6 误改成「放行全部 allow 规则」的硬闸，反而放宽安全检查。
        String pattern = "~/evil/**";
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(
            PermissionRuleSource.SESSION,
            Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, pattern))),
            Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("~/evil/x.md"), ctx);

        assertThat(result)
            .as("步骤 4 edit allow rule 命中（1.6 范围校验不构成硬闸，CC matchingRuleForInput 亦无范围校验）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("reason=Rule（步骤 4 filesystem.ts:1384-1392，非 1.6）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // OPD-WF5-FS-052 · matchingRuleForInput root-relative（// 根 / ~/ home / 单 / cwd 根）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Edit(/.claude/**) 单 / 前缀（cwd 根）→ deny 命中 {cwd}/.claude/**（root-relative）")
    void editContentDeny_projectRooted_claudeDeny() {
        Path cwd = Path.of("C:/proj/wpc11-root");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "/.claude/**"))), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool,
            input("C:/proj/wpc11-root/.claude/skills/foo/bar.md"), ctx);

        assertThat(result)
            .as("CC: Edit(/.claude/**) 单 / 前缀锚定 session/cwd 根（rootPathForSource :899-905）→ 命中 cwd/.claude/**")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("Edit(/etc/**) 单 / 前缀（cwd 根）→ 不命中绝对 /etc/hosts（root-relative 语义）")
    void editContentDeny_singleSlashNotRootAnchored() {
        Path cwd = Path.of("C:/proj/wpc11-root");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "/etc/**"))), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool, input("C:/etc/hosts"), ctx);

        assertThat(result)
            .as("CC: /etc/** 单 / 前缀锚定 cwd（非文件系统根），绝对 /etc/hosts 不命中 → 安全检查/兜底 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Edit(//etc/**) 双 / 前缀（文件系统根）→ deny 命中 /etc/hosts（root-relative）")
    void editContentDeny_doubleSlashRootAnchored() {
        Path cwd = Path.of("C:/proj/wpc11-root");
        // /etc/hosts 归一为当前驱动根（Windows C:\\etc\\hosts 形），与 // 前缀根（文件系统根）
        // 一致；显式异盘路径（C:/etc/hosts vs 当前盘）属 CC 盘符边界，不在此用例。
        String absEtc = Paths.get("/etc/hosts").toAbsolutePath().toString();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(
            PermissionRuleSource.SESSION, Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "//etc/**"))), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool, input(absEtc), ctx);

        assertThat(result)
            .as("CC: Edit(//etc/**) 双 / 前缀锚定文件系统根（patternWithRoot :860-892）→ 命中 /etc/hosts")
            .isInstanceOf(PermissionResult.Deny.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // OPD-WF5-FS-018 · getClaudeSkillScope（session 级 skill 写保护建议）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("path 在 cwd/.claude/skills/{name}/ → 1.7 安全检查 Ask 附会话级 Edit(/.claude/skills/{name}/**) 建议")
    void skillScopeSafetyAsk_attachesSessionSkillRuleSuggestion() {
        Path cwd = Path.of("C:/proj/wpc11-skills");
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool,
            input("C:/proj/wpc11-skills/.claude/skills/my-skill/bar.md"), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.suggestions())
            .as("CC: 1.7 安全检查 ask 附 getClaudeSkillScope 会话级 addRules 建议（filesystem.ts:1312-1327）")
            .hasSize(1);
        PermissionUpdate update = ask.suggestions().get(0);
        assertThat(update).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules addRules = (PermissionUpdate.AddRules) update;
        assertThat(addRules.destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
        assertThat(addRules.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(addRules.rules()).hasSize(1);
        assertThat(addRules.rules().get(0).ruleValue().ruleContent())
            .as("pattern = 前缀 + skillName + '/**'（filesystem.ts:151）")
            .isEqualTo("/.claude/skills/my-skill/**");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T4-safety-ask-suggestion-fallback · 1.7 safety ask 非 skill 路径回落
    // generateSuggestions（CC filesystem.ts:1327）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非 skill 路径触发 1.7 safety → Ask.suggestions 非空（回落 generateSuggestions，filesystem.ts:1327）")
    void safetyAsk_nonSkillPath_suggestionsNonEmpty() {
        Path cwd = cwdDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool, input("C:/x/.bashrc"), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionDecisionReason reason = ((PermissionResult.Ask) result).reason();
        assertThat(reason)
            .as("前置：C:/x/.bashrc 命中 1.7 危险文件（DANGEROUS_FILES filesystem.ts:57-68），"
                + "且非 skill 路径（getClaudeSkillScope → null）⇒ 走回落分支")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionDecisionReason.SafetyCheck) reason).classifierApprovable()).isTrue();

        List<PermissionUpdate> suggestions = ((PermissionResult.Ask) result).suggestions();
        assertThat(suggestions)
            .as("WHY 这条断言重要：前端「一键授权」第三档【只在 suggestions 非空时渲染】"
                + "（PermissionBubble.tsx:97 / permissionSuggestionLabels.ts:253 /"
                + " useChatSocket.ts:155 空数组 → null）。回落前本仓恒传 List.of() ⇒"
                + " 弹窗只剩「允许 / 拒绝」两档，用户点什么都生不出规则；"
                + "CC filesystem.ts:1327 在 safety 失败时恒给非空建议 ⇒ 本用例即该对齐的可执行契约")
            .isNotEmpty();
        assertThat(suggestions)
            .as("DEFAULT mode ⇒ shouldSuggestAcceptEdits=true（PermissionUpdates.java:247-248，CC :1449-1451）"
                + "⇒ 必含 SetMode(session, acceptEdits)")
            .anyMatch(u -> u instanceof PermissionUpdate.SetMode setMode
                && setMode.destination() == PermissionUpdate.Destination.SESSION
                && setMode.mode() == PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("可疑 Windows 路径（ADS）safety → 返回 Ask.suggestions 非空且不抛异常")
    void safetyAsk_suspiciousWindowsAds_suggestionsNonEmptyWithoutThrow() {
        Path cwd = cwdDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        PermissionResult result = checker.check(tool, input("C:/x/file.txt::$DATA"), ctx);

        assertThat(result)
            .as("WHY 重要：ADS 冒号路径让 Java Paths.get 抛 InvalidPathException，而 CC 的兜底"
                + " dirname 是纯字符串操作、恒不抛（path.ts:149）⇒ 本仓权限链必须返回 Ask（弹窗）"
                + "而不是抛异常——抛异常意味着工具直接失败、用户连授权入口都看不到"
                + "（PermissionUpdates.getDirectoryForPath 的 dirname 降级即为此而设）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionResult.Ask) result).suggestions())
            .as("同 1：suspicious-Windows safety 亦须附非空建议（CC :1327 单点算一次，三分支共用）")
            .isNotEmpty();
    }

    @Test
    @DisplayName("工作目录内非 skill safety 路径（.claude/settings.json）→ 仍非空（不落空）")
    void safetyAsk_inWorkingDirNonSkillPath_suggestionsNonEmpty() {
        Path cwd = cwdDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        // 工作目录内的 Claude 配置文件（CC isClaudeConfigFilePath filesystem.ts:200-222）
        String path = cwd.resolve(".claude").resolve("settings.json").toString();
        PermissionResult result = checker.check(tool, input(path), ctx);

        assertThat(((PermissionResult.Ask) result).reason())
            .as("前置：命中 1.7 Claude 配置文件分支（filesystem.ts:641-650）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionResult.Ask) result).suggestions())
            .as("WHY 重要：工作目录内不等于「无需建议」——安全工作目录内 + 非 skill 路径同样"
                + "要给出 SetMode(acceptEdits) 建议，否则用户在目录内改 .claude/settings.json 时"
                + "依旧只能「允许 / 拒绝」，无第三档可选（CC :1327 同一口径）")
            .anyMatch(u -> u instanceof PermissionUpdate.SetMode setMode
                && setMode.mode() == PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("残留契约：BUBBLE mode + 工作目录内 → 仍为空（CC 同构判据，非本仓分歧）")
    void safetyAsk_bubbleMode_inWorkingDir_residualEmpty() {
        Path cwd = cwdDir();
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.BUBBLE, Map.of(), Map.of(), Map.of()), cwd);
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));

        String path = cwd.resolve(".claude").resolve("settings.json").toString();
        PermissionResult result = checker.check(tool, input(path), ctx);

        assertThat(result)
            .as("前置：BUBBLE 不改变 1.7 安全判定（仍 ask）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).suggestions())
            .as("【显式钉住的残留，⛔ 不是已修好】PermissionUpdates.shouldSuggestAcceptEdits 只认"
                + " DEFAULT/PLAN（PermissionUpdates.java:247-248 = CC :1449-1451 同一判据）；"
                + " 路径在工作目录内 ⇒ AddDirectories 亦不产生 ⇒ BUBBLE/ACCEPT_EDITS/DONT_ASK/AUTO"
                + " 四态在「目录内」一格仍返回空建议。这是 CC 同构行为（子 Agent 以 BUBBLE 运行时"
                + " 触发目录内敏感文件 ask 会落进此洞），故本批【刻意不补】——⛔ 不得以顺手放宽"
                + " mode 判据的方式私自补掉，如需覆盖须作为独立对齐项另行登记。"
                + " 本用例把该洞钉成显式契约，防止未来被误读成「已全覆盖」")
            .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1.7 安全检查
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("可疑 Windows 路径（ADS）→ Ask(SafetyCheck, classifierApprovable=false)")
    void suspiciousWindowsPath_ask() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("C:/x/file.txt::$DATA"), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionDecisionReason reason = ((PermissionResult.Ask) result).reason();
        assertThat(reason).isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionDecisionReason.SafetyCheck) reason).classifierApprovable())
            .as("CC: suspicious windows classifierApprovable=false（filesystem.ts:636）")
            .isFalse();
    }

    @Test
    @DisplayName("危险文件（.bashrc）→ Ask(SafetyCheck, classifierApprovable=true)")
    void dangerousFile_bashrc_ask() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("C:/x/.bashrc"), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionDecisionReason reason = ((PermissionResult.Ask) result).reason();
        assertThat(reason).isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionDecisionReason.SafetyCheck) reason).classifierApprovable())
            .as("CC: dangerous file classifierApprovable=true（filesystem.ts:658）")
            .isTrue();
    }

    @Test
    @DisplayName("危险目录（.git/ 下）→ Ask(SafetyCheck)")
    void dangerousDirectory_git_ask() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("C:/proj/.git/config"), ctx);

        assertThat(result)
            .as("CC: DANGEROUS_DIRECTORIES 含 .git（filesystem.ts:74-79 + :451-471）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName(".claude/worktrees/ 与 .nexusai/worktrees/ 结构性目录 → 不判危险（决策 D7），无规则时兜底 Ask")
    void claudeWorktrees_notDangerous() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        // CC 兼容：.claude/worktrees（filesystem.ts:456-468 例外）
        PermissionResult claudeResult = checker.check(
            tool, input("C:/proj/.claude/worktrees/main/notes.md"), ctx);
        assertThat(claudeResult)
            .as("CC: .claude/worktrees 是结构性路径，跳过危险判定（filesystem.ts:456-468）→ 不因 .claude 段 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) claudeResult).reason())
            .as("无危险命中（SafetyCheck 不触发）→ 兜底 reason 非 SafetyCheck")
            .isNotInstanceOf(PermissionDecisionReason.SafetyCheck.class);

        // nexusai 自有根（决策 D7）：.nexusai/worktrees 同样结构性放行
        PermissionResult nexusaiResult = checker.check(
            tool, input("C:/proj/.nexusai/worktrees/main/notes.md"), ctx);
        assertThat(nexusaiResult)
            .as("D7: .nexusai/worktrees 是 nexusai 自有结构性路径 → 不因 .nexusai 段 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) nexusaiResult).reason())
            .as("无危险命中（SafetyCheck 不触发）→ 兜底 reason 非 SafetyCheck")
            .isNotInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("Claude 配置文件（.claude/settings.json）→ Ask(SafetyCheck, true)")
    void claudeSettingsJson_ask() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, input("C:/proj/.claude/settings.json"), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionDecisionReason reason = ((PermissionResult.Ask) result).reason();
        assertThat(reason).isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionDecisionReason.SafetyCheck) reason).classifierApprovable())
            .as("CC: claude config classifierApprovable=true（filesystem.ts:647）")
            .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 前置守卫
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("缺少 path → Ask(missing path)（对齐 ReadPermissionChecker）")
    void missingPath_ask() {
        ToolUseContext ctx = ctx(rulesCtx(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of()), cwdDir());
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        PermissionResult result = checker.check(tool, JSON.createObjectNode(), ctx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).message()).contains("缺少 path");
    }

    @Test
    @DisplayName("ctx==null → fail-loud IAE（对齐 ReadPermissionChecker 守卫）")
    void nullCtx_failsLoud() {
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwdDir()));

        assertThatThrownBy(() -> checker.check(tool, input("C:/x/a.txt"), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ctx is null");
    }
}
