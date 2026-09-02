package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WF-6 filesystem 域对齐 CC 核心测试 · {@link PathValidation} 通用路径校验核心。
 *
 * <p><b>覆盖 OPD-WF5-02-01/02/03/05 验收标准</b>（CC 真源自证）：
 * <ol>
 *   <li><b>OPD-WF5-02-01</b>：{@code hasSuspiciousWindowsPathPattern} 7 类全查
 *       （filesystem.ts:537-602）；并验证旧 {@code .{3,}} 无分隔符边界过度命中已修正
 *       （{@code ...name]} 不命中）。</li>
 *   <li><b>OPD-WF5-02-02</b>：{@code checkEditableInternalPath} / {@code checkReadableInternalPath}
 *       17 分支 reason 文案对齐 CC（filesystem.ts:1479-1605 / :1611-1777）；plan 写分支按
 *       OD-20 passthrough（写盘仍走 ask）。</li>
 *   <li><b>OPD-WF5-02-03</b>：{@code pathInWorkingPath} 大小写归一 + macOS /private 归一
 *       （filesystem.ts:709-744）。</li>
 *   <li><b>OPD-WF5-02-05</b>：核心 {@code isPathAllowed} / {@code validatePath} /
 *       {@code formatDirectoryList} / {@code getGlobBaseDirectory} / {@code expandTilde} /
 *       {@code isPathInSandboxWriteAllowlist}（pathValidation.ts:38-123 / :141-263 / :373-485）。</li>
 * </ol>
 */
@DisplayName("WF-6 · PathValidation 核心（OPD-WF5-02-01/02/03/05）")
class PathValidationTest {

    /** 测试环境：claudeConfigHomeDir / nexusaiConfigHomeDir 与 effectiveCwd 隔离，避免命中真实用户目录。 */
    private static PathValidationEnv env() {
        return new PathValidationEnv(
            "session-1", "agent-1",
            "C:/proj", "C:/proj",
            "C:/Users/u/.claude",  // claudeConfigHomeDir（只读兼容根 D3/D4）
            "C:/Users/u/.claude",  // nexusaiConfigHomeDir（自有主根 D1，白名单内部路径基址）
            true,          // scratchpadEnabled
            "C:/tmp/claude",
            false,         // hasAutoMemPathOverride
            "C:/Users/u/.claude/memory",
            "C:/tmp/claude/bundled-skills/0.2.33/nonce");
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. OPD-WF5-02-01 · hasSuspiciousWindowsPathPattern 7 类
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ADS 冒号（位置 2 之后）→ true（CC filesystem.ts:546-551，Windows 平台）")
    void suspicious_adsColon() {
        assumeTrue(PathValidation.isWindows(),
            "ADS 冒号检查仅 Windows/WSL 平台（CC getPlatform 门）");
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/file.txt::$DATA")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/.bashrc:hidden")).isTrue();
        // 盘符冒号（位置 1）不误伤
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/file.txt")).isFalse();
    }

    @Test
    @DisplayName("8.3 短名 ~\\d → true（CC :556-558）")
    void suspicious_83ShortName() {
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/GIT~1")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/SETTIN~1.JSON")).isTrue();
    }

    @Test
    @DisplayName("长路径前缀 \\\\?\\ / \\\\.\\ / //?/ / //./ → true（CC :562-569）")
    void suspicious_longPathPrefix() {
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("\\\\?\\C:\\Users\\x\\file")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("\\\\.\\C:\\file")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("//?/C:/file")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("//./C:/file")).isTrue();
    }

    @Test
    @DisplayName("尾点/尾空格 → true（CC :574-576）")
    void suspicious_trailingDotSpace() {
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/.git.")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/settings.json ")).isTrue();
    }

    @Test
    @DisplayName("DOS 设备名 .CON/.PRN/.AUX/.NUL/COM1-9/LPT1-9 → true（CC :581-583）")
    void suspicious_dosDeviceName() {
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/.git.CON")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/settings.json.PRN")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/file.AUX")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/device.NUL")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/file.COM9")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/file.LPT1")).isTrue();
    }

    @Test
    @DisplayName("3+ 连续点作路径段（分隔符边界）→ true；...name] 不误伤（修正 .{3,} 过度命中）")
    void suspicious_threePlusDots_boundary() {
        // 分隔符边界内 → true（CC :590-592）
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/.../file")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/path/...")).isTrue();
        // OPD-WF5-02-01 修正：无分隔符边界的 ...name]（Next.js catch-all 路由）不再误伤
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/foo...name]")).isFalse();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/x/version...beta")).isFalse();
    }

    @Test
    @DisplayName("UNC 子检查（仅 Windows）→ true；正常路径 → false")
    void suspicious_unc() {
        assumeTrue(PathValidation.isWindows(),
            "containsVulnerableUncPath 仅 Windows 平台生效（readOnlyCommandValidation.ts:1564）");
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("\\\\server\\share\\file")).isTrue();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("//192.168.1.1/share")).isTrue();
    }

    @Test
    @DisplayName("正常路径（盘符冒号/普通文件名）→ false")
    void suspicious_normalPath() {
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/proj/src/Main.java")).isFalse();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/proj/.git/config")).isFalse();
        assertThat(PathValidation.hasSuspiciousWindowsPathPattern("C:/proj/readme.txt")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. OPD-WF5-02-02 · 内部路径白名单 17 分支
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读白名单 session-memory → allow（CC :1620-1629）")
    void read_sessionMemory() {
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/session-memory/summary.md", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
    }

    @Test
    @DisplayName("读白名单 project-dir → allow（CC :1633-1642）")
    void read_projectDir() {
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/projects/sess.jsonl", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
    }

    @Test
    @DisplayName("读白名单 plan 文件 → allow；写 plan 按 OD-20 passthrough")
    void plan_branches() {
        String planFile = "C:/Users/u/.claude/plans/session-1.md";
        PathValidation.InternalPathResult read = PathValidation.checkReadableInternalPath(planFile, env());
        assertThat(read.allowed())
            .as("CC :1645-1654 plan 读分支 auto-allow")
            .isTrue();
        assertThat(read.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Plan files for current session are allowed for reading"));

        PathValidation.InternalPathResult write = PathValidation.checkEditableInternalPath(planFile, env());
        assertThat(write.allowed())
            .as("OD-20 子项4：plan 写盘走 ask（CC :1488-1497 auto-allow 在 Java 不采用）")
            .isFalse();
    }

    @Test
    @DisplayName("读白名单 tool-results → allow（S2 迁 config-home 根，project-dir 分支先行命中同 CC 顺序）")
    void read_toolResults() {
        // S2: tool-results 根 = {configHome}/projects/{sanitizePath(effectiveCwd)}/{sessionId}/tool-results。
        // 迁移后 tool-results 落 config-home projects 树内，PathValidation project-dir 分支
        // （env.projectDir() = {configHome}/projects/）先行命中 —— 与 CC 分支顺序一致
        // （filesystem.ts:1638 isProjectDirPath 先于 :1656 tool-results；cwd==originalCwd 时 CC
        //   tool-results 同样被 project-dir 遮蔽）。模型 FileRead 读回 persisted-output 仍放行
        //   （不 fail-closed，满足 R1 白名单同步语义），仅 decisionReason 走 project-dir 文案。
        String toolResultsPath = "C:/Users/u/.claude/projects/C--proj/session-1/tool-results/output.txt";
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(toolResultsPath, env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .as("tool-results 落 config-home projects 树 → project-dir 分支先行（CC :1638 顺序）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
    }

    @Test
    @DisplayName("读写白名单 scratchpad → allow（CC :1500-1509 / :1677-1686，isScratchpadEnabled 门）")
    void scratchpad_branches() {
        String scratch = "C:/tmp/claude/C--proj/session-1/scratchpad/notes.md";
        assertThat(PathValidation.checkReadableInternalPath(scratch, env()).allowed()).isTrue();
        assertThat(PathValidation.checkEditableInternalPath(scratch, env()).allowed()).isTrue();

        // scratchpad 未启用 → 写分支不命中（读分支仍经 project-temp 放行，CC :1688-1701 覆盖整个 temp 空间）
        PathValidationEnv disabled = new PathValidationEnv(
            "session-1", "agent-1", "C:/proj", "C:/proj",
            "C:/Users/u/.claude", "C:/Users/u/.claude", false, "C:/tmp/claude", false, null, null);
        assertThat(PathValidation.checkEditableInternalPath(scratch, disabled).allowed())
            .as("CC :410-412 isScratchpadEnabled 门：禁用时写分支不命中")
            .isFalse();
        assertThat(PathValidation.checkReadableInternalPath(scratch, disabled).allowed())
            .as("project-temp（CC :1688-1701）覆盖 temp 全空间，读仍放行")
            .isTrue();
    }

    @Test
    @DisplayName("读白名单 project-temp → allow（CC :1688-1701，跨会话同项目 temp）")
    void read_projectTemp() {
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/tmp/claude/C--proj/other-session/notes.txt", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Project temp directory files are allowed for reading"));
    }

    @Test
    @DisplayName("读白名单 tasks / teams → allow（CC :1728-1741 / :1744-1757）")
    void read_tasksTeams() {
        PathValidation.InternalPathResult tasks = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/tasks/t1.json", env());
        assertThat(tasks.allowed()).isTrue();
        assertThat(tasks.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Task files are allowed for reading"));

        PathValidation.InternalPathResult teams = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/teams/t1.json", env());
        assertThat(teams.allowed()).isTrue();
        assertThat(teams.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Team files are allowed for reading"));
    }

    @Test
    @DisplayName("auto-mem：读恒 allow；写 !hasAutoMemPathOverride → allow、有 override → passthrough")
    void autoMem_branches() {
        String memFile = "C:/Users/u/.claude/memory/x.md";
        PathValidation.InternalPathResult read = PathValidation.checkReadableInternalPath(memFile, env());
        assertThat(read.allowed())
            .as("CC :1716-1725 读分支恒 isAutoMemPath 无 override 门")
            .isTrue();

        PathValidation.InternalPathResult write = PathValidation.checkEditableInternalPath(memFile, env());
        assertThat(write.allowed())
            .as("CC :1572-1581 !hasAutoMemPathOverride 门放行")
            .isTrue();

        PathValidationEnv overridden = new PathValidationEnv(
            "session-1", "agent-1", "C:/proj", "C:/proj",
            "C:/Users/u/.claude", "C:/Users/u/.claude", true, "C:/tmp/claude", true,
            "C:/Users/u/.claude/memory", null);
        assertThat(PathValidation.checkEditableInternalPath(memFile, overridden).allowed())
            .as("CC :1572 override 时写分支不 auto-allow（走正常权限流）")
            .isFalse();
    }

    @Test
    @DisplayName("写白名单 launch.json → allow（CC :1590-1602，项目级大小写不敏感）")
    void write_launchJson() {
        PathValidation.InternalPathResult r = PathValidation.checkEditableInternalPath(
            "C:/proj/.claude/launch.json", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Preview launch config is allowed for writing"));

        // 大小写不敏感（CC normalizeCaseForComparison）
        assertThat(PathValidation.checkEditableInternalPath(
            "C:/proj/.CLAUDE/LAUNCH.JSON", env()).allowed()).isTrue();
    }

    @Test
    @DisplayName("读白名单 bundled-skills → allow；nonce 前缀攻击不命中（CC :1764-1774）")
    void read_bundledSkills() {
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/tmp/claude/bundled-skills/0.2.33/nonce/skill/refs/guide.md", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Bundled skill reference files are allowed for reading"));

        // nonce 前缀攻击（root+"-evil"）→ 尾分隔符防御
        assertThat(PathValidation.checkReadableInternalPath(
            "C:/tmp/claude/bundled-skills/0.2.33/nonce-evil/x.md", env()).allowed())
            .as("CC :1764 `+ sep` 尾分隔符防 nonce 前缀攻击")
            .isFalse();
    }

    @Test
    @DisplayName("非内部路径 → passthrough")
    void internalPath_passthrough() {
        assertThat(PathValidation.checkReadableInternalPath("C:/proj/src/Main.java", env()).allowed()).isFalse();
        assertThat(PathValidation.checkEditableInternalPath("C:/proj/src/Main.java", env()).allowed()).isFalse();
        assertThat(PathValidation.checkEditableInternalPath(null, env()).allowed()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. OPD-WF5-02-03 · pathInWorkingPath 大小写 / macOS 私路径归一
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("大小写不敏感：C:/Proj/Sub 在工作目录 C:/proj 内（CC :723-728）")
    void workingPath_caseInsensitive() {
        assertThat(PathValidation.pathInWorkingPath("C:/Proj/Sub/file.txt", "C:/proj")).isTrue();
        assertThat(PathValidation.pathInWorkingPath("C:/PROJ/a.txt", "c:/proj")).isTrue();
    }

    @Test
    @DisplayName("macOS /private 归一：/private/var→/var、/private/tmp→/tmp（CC :716-721）")
    void workingPath_privateNormalization() {
        assumeTrue(!PathValidation.isWindows(),
            "macOS 私路径归一仅在 POSIX 路径形态下生效（Windows Java 路径为反斜杠）");
        assertThat(PathValidation.pathInWorkingPath("/private/var/log/x", "/var")).isTrue();
        assertThat(PathValidation.pathInWorkingPath("/private/tmp/claude/x", "/tmp")).isTrue();
        assertThat(PathValidation.pathInWorkingPath("/private/tmp-claude/x", "/tmp")).isFalse();
    }

    @Test
    @DisplayName("穿越路径 / 目录外 → false")
    void workingPath_traversalAndOutside() {
        assertThat(PathValidation.pathInWorkingPath("C:/proj/../etc/passwd", "C:/proj")).isFalse();
        assertThat(PathValidation.pathInWorkingPath("C:/other/x", "C:/proj")).isFalse();
        assertThat(PathValidation.pathInWorkingPath("C:/proj", "C:/proj")).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. OPD-WF5-02-05 · 核心 pathValidation 能力
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("formatDirectoryList ≤5 全列 / >5 前5+and N more（CC :38-51）")
    void formatDirectoryList() {
        assertThat(PathValidation.formatDirectoryList(List.of("a", "b"))).isEqualTo("'a', 'b'");
        assertThat(PathValidation.formatDirectoryList(List.of("a", "b", "c", "d", "e", "f")))
            .isEqualTo("'a', 'b', 'c', 'd', 'e', and 1 more");
    }

    @Test
    @DisplayName("getGlobBaseDirectory（CC :57-74）")
    void getGlobBaseDirectory() {
        assertThat(PathValidation.getGlobBaseDirectory("/path/to/*.txt")).isEqualTo("/path/to");
        assertThat(PathValidation.getGlobBaseDirectory("no-glob")).isEqualTo("no-glob");
    }

    @Test
    @DisplayName("expandTilde（CC :80-89）")
    void expandTilde() {
        String home = System.getProperty("user.home");
        assertThat(PathValidation.expandTilde("~")).isEqualTo(home);
        assertThat(PathValidation.expandTilde("~/x")).isEqualTo(home + "/x");
        assertThat(PathValidation.expandTilde("/abs/x")).isEqualTo("/abs/x");
    }

    @Test
    @DisplayName("isPathAllowed：工作目录内 read → allowed；目录外 → blocked")
    void isPathAllowed_workingDir() {
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, "C:/proj", "C:/proj", "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        assertThat(PathValidation.isPathAllowed("C:/proj/a.txt", null,
            PermissionUpdates.OperationType.READ, env, null, null).allowed()).isTrue();
        assertThat(PathValidation.isPathAllowed("C:/etc/passwd", null,
            PermissionUpdates.OperationType.READ, env, null, null).allowed()).isFalse();
        // 写 + 非 acceptEdits → 目录内也不 auto-allow（CC :207-209）
        assertThat(PathValidation.isPathAllowed("C:/proj/a.txt", null,
            PermissionUpdates.OperationType.WRITE, env, null, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("validatePath：写操作 glob 阻断；UNC/~/shell 展开阻断（CC :373-485）")
    void validatePath_blocks() {
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, "C:/proj", "C:/proj", "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        assertThat(PathValidation.validatePath("C:/proj/*.txt", "C:/proj", null,
            PermissionUpdates.OperationType.WRITE, env, null).allowed()).isFalse();
        // ~/ 已由 expandTilde 展开为绝对路径；~root 等变体未被展开 → 阻断（CC :401-411）
        assertThat(PathValidation.validatePath("~root/.ssh/id_rsa", "C:/proj", null,
            PermissionUpdates.OperationType.READ, env, null).allowed())
            .as("CC :401-411 tilde 变体（~root）阻断")
            .isFalse();
        assertThat(PathValidation.validatePath("$HOME/x", "C:/proj", null,
            PermissionUpdates.OperationType.READ, env, null).allowed())
            .as("CC :423-436 shell 展开阻断")
            .isFalse();
        assertThat(PathValidation.validatePath("C:/proj/a.txt", "C:/proj", null,
            PermissionUpdates.OperationType.READ, env, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("沙箱写白名单：allowOnly 内 → true；denyWithinAllow 内 / 目录外 / 无配置 → false（CC :101-123）")
    void sandboxWriteAllowlist() {
        PathValidation.SandboxWriteConfig cfg = new PathValidation.SandboxWriteConfig(
            List.of("C:/tmp/claude"), List.of("C:/tmp/claude/settings.json"));
        assertThat(PathValidation.isPathInSandboxWriteAllowlist("C:/tmp/claude/x.txt", cfg)).isTrue();
        assertThat(PathValidation.isPathInSandboxWriteAllowlist("C:/tmp/claude/settings.json", cfg))
            .as("CC :118-120 denyWithinAllow 内仍阻断")
            .isFalse();
        assertThat(PathValidation.isPathInSandboxWriteAllowlist("C:/tmp/other/x.txt", cfg)).isFalse();
        assertThat(PathValidation.isPathInSandboxWriteAllowlist("C:/tmp/claude/x.txt", null))
            .as("无配置（Java 沙箱执行域待专项）→ fail-closed false")
            .isFalse();
    }

    @Test
    @DisplayName("[G10] 白名单锚 originalCwd 层：cd 进子目录不改白名单根（对齐 CC allWorkingDirectories）")
    void g10_whitelist_anchoredAtOriginalCwdLayer() {
        // WHY（规则九 · G10）：CC allWorkingDirectories（filesystem.ts:667-674）= [getOriginalCwd(), ...]，
        //   锚启动/worktree 入口层，bash cd 进子目录后白名单根不变（pathInWorkingPath 树语义放行子树）。
        //   旧实现锚 env.effectiveCwd（=getCwd，随 cd 变）→ cd 进子目录后白名单根=子目录（变窄）。
        //   本测试构造 effectiveCwd≠originalCwd（模拟 bash cd 进子目录），验证白名单仍锚 originalCwd：
        //   - originalCwd 子树内的 cd 子目录 → 放行（旧锚 effectiveCwd 也放行，两实现一致）
        //   - effectiveCwd 子树外、originalCwd 子树内的路径 → 放行（区分 G10：旧锚 effectiveCwd 拒放，新锚放行）
        //   - originalCwd 树外 → 拒放（对齐 CC pathInAllowedWorkingPath every/树语义）
        String originalCwd = "C:/proj";
        String cdSubdir = "C:/proj/sub"; // 模拟 bash cd 进子目录（effectiveCwd=子目录）
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, cdSubdir, originalCwd, "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);

        // cd 子目录内 → 放行（两种锚都放行；WHY: 子目录在 originalCwd 子树内）
        assertThat(PathValidation.isInAllowedWorkingPath(
            "C:/proj/sub/file.txt", null, env, null))
            .as("cd 进 originalCwd 子目录后的路径仍放行（白名单根稳定在 originalCwd 层）")
            .isTrue();
        // originalCwd 子树内、effectiveCwd(子目录) 子树外 → 放行（G10 区分：旧锚 effectiveCwd 会拒放变窄）
        assertThat(PathValidation.isInAllowedWorkingPath(
            "C:/proj/sibling.txt", null, env, null))
            .as("originalCwd 树内、cd 子目录树外路径放行（白名单锚 originalCwd 不随 cd 变窄，对齐 CC）")
            .isTrue();
        // originalCwd 树外 → 拒放（对齐 CC 树语义，effcetiveCwd=子目录也不在 originalCwd 树内则拒）
        assertThat(PathValidation.isInAllowedWorkingPath(
            "C:/other/out.txt", null, env, null))
            .as("originalCwd 树外路径拒放（对齐 CC every/树语义）")
            .isFalse();
    }

    @Test
    @DisplayName("[G10] 白名单 additionalWorkingDirectories 仍并入（对齐 CC allWorkingDirectories ...additional）")
    void g10_whitelist_includesAdditionalWorkingDirs() {
        // WHY: CC allWorkingDirectories = [getOriginalCwd(), ...additional.keys()]，additional
        //   （如 symlink PWD 注入 source=session）仍须并入白名单。验证锚改 originalCwd 后
        //   additional 语义不回归。
        String originalCwd = "C:/proj";
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, "C:/proj", originalCwd, "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(),
            Map.of("C:/extra/link", new AdditionalWorkingDirectory("C:/extra/link", PermissionRuleSource.SESSION)));

        assertThat(PathValidation.isInAllowedWorkingPath("C:/extra/link/file.txt", permCtx, env, null))
            .as("additionalWorkingDirectories（symlink PWD 注入 source=session）仍并入白名单")
            .isTrue();
        assertThat(PathValidation.isInAllowedWorkingPath("C:/proj/a.txt", permCtx, env, null))
            .as("originalCwd 白名单锚不受 additional 注入影响")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // WF-1D · DEL-06 · PathValidationEnv originalCwd 走统一入口 CwdResolution
    // 对齐 CC getOriginalCwd()（state.ts:500-502）作 projectTempDir/launchJsonPath 锚。
    // WHY：原 Java 直读 System.getProperty("user.dir")，绑定项目场景 originalCwd 恒 JVM
    //   启动目录 → projectTempDir 落 {tmp}/{sanitize(user.dir)} 而非 {tmp}/{sanitize(boundProject)}，
    //   launch.json 落 {user.dir}/.claude/launch.json 而非 {boundProject}/.claude/launch.json，
    //   worktree/绑定项目场景权限白名单与内部路径锚全部错位（G9/G4）。
    // ════════════════════════════════════════════════════════════════════

    @AfterEach
    void clearCwdState() {
        CwdResolution.clearCurrentOverride();
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    /** 13 参最小 ToolUseContext 工厂（sessionId 固定，便于绑定 SessionProjectRoot）。 */
    private static ToolUseContext ctxWithSession(String sessionId, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()),
            PermissionMode.DEFAULT, Map.of(), false, "", effectiveCwd);
    }

    @Test
    @DisplayName("[WF-1D] fromToolUseContext: 绑定项目 → originalCwd=boundProject（非 user.dir）")
    void fromToolUseContext_originalCwd_usesBoundProject(@TempDir Path projectDir) throws Exception {
        // WHY: CC getOriginalCwd 作 projectTempDir/launchJsonPath 锚。绑定项目场景必须取 boundProject。
        String sessionId = "11111111-1111-1111-1111-111111111111";
        SessionProjectRoot.setForSession(sessionId.toString(), projectDir.toString());
        Path fakeEffective = Path.of("D:/some/other/effective/cwd");

        PathValidationEnv env = PathValidationEnv.fromToolUseContext(ctxWithSession(sessionId, fakeEffective));

        assertThat(env.originalCwd())
            .as("originalCwd 必须取 boundProject（CwdResolution.getOriginalCwdLayer），不得直读 user.dir")
            .isEqualTo(projectDir.toRealPath().toString());
        assertThat(env.effectiveCwd())
            .as("effectiveCwd 仍为 ctx 传入的会话工作目录快照（与 originalCwd 不同层）")
            .isEqualTo(fakeEffective.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("[WF-1D] fromToolUseContext: 未绑定 → originalCwd 回落 user.dir（经统一入口，INV-4/INV-6）")
    void fromToolUseContext_originalCwd_fallsBackToUserDir(@TempDir Path fakeEffective) throws Exception {
        // WHY: 未绑定会话 boundProject=null → getOriginalCwdLayer 回落 user.dir（INV-4），
        //   但经统一入口而非直读（INV-6）。
        String sessionId = "22222222-2222-2222-2222-222222222222";
        // 不调 SessionProjectRoot.setForSession → 未绑定

        PathValidationEnv env = PathValidationEnv.fromToolUseContext(ctxWithSession(sessionId, fakeEffective));

        assertThat(env.originalCwd())
            .as("未绑定回落 user.dir（经 CwdResolution 统一入口，不直读 System.getProperty）")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
    }

    @Test
    @DisplayName("[WF-1D] forProcess: originalCwd 经统一入口（无 session 槽 → 回落 user.dir，INV-6）")
    void forProcess_originalCwd_viaUnifiedEntry() throws Exception {
        // WHY: forProcess 无 sessionId 槽（record sessionId=null），getOriginalCwdLayer(null) →
        //   boundProject(null) ?? user.dir。原直读 user.dir 改走统一入口（INV-6 无 user.dir 直读残留）。
        PathValidationEnv env = PathValidationEnv.forProcess(Path.of("C:/proc/cwd"));

        assertThat(env.sessionId()).isNull();
        assertThat(env.originalCwd())
            .as("forProcess 无 session → originalCwd 回落 user.dir（经统一入口）")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        assertThat(env.effectiveCwd())
            .isEqualTo(Path.of("C:/proc/cwd").toAbsolutePath().normalize().toString());
    }
}
