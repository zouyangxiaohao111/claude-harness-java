package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
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
