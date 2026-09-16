package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** 测试环境：claudeConfigHomeDir / nexusaiConfigHomeDir 与 effectiveCwd 隔离，避免命中真实用户目录。 */
    private static PathValidationEnv env() {
        return new PathValidationEnv(
            "session-1", "agent-1",
            "C:/proj", "C:/proj",
            "C:/proj",             // sessionProjectRoot（[批 E2] 稳定项目根 ⇒ slug = C--proj）
            "C:/Users/u/.claude",  // claudeConfigHomeDir（只读兼容根 D3/D4）
            "C:/Users/u/.claude",  // nexusaiConfigHomeDir（自有主根 D1，白名单内部路径基址）
            true,          // scratchpadEnabled
            "C:/tmp/claude",
            false,         // hasAutoMemPathOverride
            "C:/Users/u/.claude/memory",
            "C:/tmp/claude/bundled-skills/0.2.33/nonce");
    }

    /** [批 E2] 本项目 slug（= AutoMemPaths.sanitizePath(sessionProjectRoot)，与落盘介质同源）。 */
    private static final String PROJ_SLUG = AutoMemPaths.sanitizePath("C:/proj");

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
    @DisplayName("[E2] 读白名单 session-memory → allow（真实介质形状，CC :1620-1629）")
    void read_sessionMemory() {
        // [批 E2] 夹具改为**真实介质形状**：{configHome}/projects/{slug}/{sessionId}/session-memory/summary.md
        //   （SessionMemoryService.resolvePath:2272-2283）。旧夹具
        //   "{configHome}/session-memory/summary.md" 是**假门**——实测该目录 0 文件，
        //   真实 summary.md 全在 projects/{slug}/{sid}/ 下，旧断言只因 project-dir 宽口兜住才「绿」
        //   （reason 实为 "Project directory files..."，与本用例断言不符 ⇒ 本用例改前必红）。
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/projects/" + PROJ_SLUG + "/session-1/session-memory/summary.md", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .as("必须是 session-memory 分支本身命中（⛔ 不是 project-dir / tool-results 等别分支兜住）")
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
    }

    @Test
    @DisplayName("[E2] 读白名单 project-dir → allow（当前项目 slug 内，CC :1633-1642）")
    void read_projectDir() {
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(
            "C:/Users/u/.claude/projects/" + PROJ_SLUG + "/session-1/session.jsonl", env());
        assertThat(r.allowed()).isTrue();
        assertThat(r.decisionReason())
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
    }

    // ════════════════════════════════════════════════════════════════════
    // [批 E2] 内部路径白名单：session-memory 假门 + project-dir 跨项目宽口
    //   CC 真源：filesystem.ts:261-263 getSessionMemoryDir（= projectDir/sessionId/session-memory）、
    //            :284-291 isProjectDirPath（= getProjectDir(getCwd())，只放行**当前项目**）。
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[E2] 宽口：别的项目（别的 slug）下的路径 → 任何分支都不放行")
    void e2_otherProject_notAllowedByAnyBranch() {
        // ⛔ 必须断言「具体不命中」而非只断 allowed()：本项目 slug 之外的路径若被任何分支放行，
        //    本断言即红（旧行为 = project-dir 宽口 {configHome}/projects/ 放行**全部 662 个项目**
        //    —— 实测 configHome/projects/ 下 662 个 slug 目录）。
        String otherProject = "C:/Users/u/.claude/projects/C--some-other-project/session-9/session.jsonl";
        PathValidation.InternalPathResult r = PathValidation.checkReadableInternalPath(otherProject, env());
        assertThat(r.allowed())
            .as("别的项目的历史 transcript / session-memory 不得被静默放行（对齐 CC :284-291 只放当前项目）")
            .isFalse();
        assertThat(r.decisionReason()).isNull();

        // 别的项目的 session-memory 同样不得放行（旧行为：project-dir 宽口放行）
        String otherSessionMemory =
            "C:/Users/u/.claude/projects/C--some-other-project/session-9/session-memory/summary.md";
        assertThat(PathValidation.checkReadableInternalPath(otherSessionMemory, env()).allowed())
            .as("别的项目的 session-memory 不得被静默放行")
            .isFalse();
    }

    @Test
    @DisplayName("[E2] 防过度收窄：本项目 session-memory / transcript / subagent / tool-results 仍放行")
    void e2_currentProject_stillAllowed() {
        String base = "C:/Users/u/.claude/projects/" + PROJ_SLUG;
        assertThat(PathValidation.checkReadableInternalPath(base + "/session-1/session-memory/summary.md", env())
            .decisionReason())
            .as("本项目 session-memory")
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
        assertThat(PathValidation.checkReadableInternalPath(base + "/session-1/session.jsonl", env())
            .decisionReason())
            .as("本项目 transcript（媒体 SessionStorage.sessionProjectDir 同根）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
        assertThat(PathValidation.checkReadableInternalPath(base + "/session-1/subagents/agent-a1.jsonl", env())
            .decisionReason())
            .as("本项目 subagent sidechain")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
        assertThat(PathValidation.checkReadableInternalPath(base + "/session-1/tool-results/o.txt", env())
            .decisionReason())
            .as("本项目 tool-results（落点 slug 与本项目 root 一致时被 project-dir 先行遮蔽，同 CC 顺序）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
    }

    @Test
    @DisplayName("[E2][③′ 腿 2] 稳定根半：cd/worktree 后仍按**稳定项目根**取 slug 放行")
    void e2_anchor_stableHalf_allowsSessionProjectRootSlug() {
        // effectiveCwd 模拟 bash cd 进子目录（= CC getCwd，会被 cd 改）；sessionProjectRoot = 稳定项目根。
        PathValidationEnv cdEnv = new PathValidationEnv(
            "session-1", "agent-1", "C:/proj/sub", "C:/proj",
            "C:/proj",             // sessionProjectRoot（稳定锚 ⇒ slug = C--proj）
            "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);

        assertThat(cdEnv.projectDirs())
            .as("两半各自独立：应同时含 cwd 半（C--proj-sub）与稳定根半（C--proj）")
            .containsExactly(
                Path.of("C:/Users/u/.claude", "projects", AutoMemPaths.sanitizePath("C:/proj/sub"))
                    .normalize().toString() + java.io.File.separator,
                Path.of("C:/Users/u/.claude", "projects", PROJ_SLUG).normalize().toString()
                    + java.io.File.separator);

        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + PROJ_SLUG + "/session-1/session.jsonl", cdEnv).decisionReason())
            .as("稳定根半：cd 进子目录后仍按**稳定项目根**取 slug 放行"
                + "（⭐ 删掉稳定根半 ⇒ 本断言必红，因为 C--proj 不在 cwd 半 C--proj-sub 内）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));

        // 稳定根半的 session-memory（介质写盘落点）亦须放行
        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + PROJ_SLUG + "/session-1/session-memory/summary.md", cdEnv)
            .decisionReason())
            .as("介质写盘的 session-memory 稳定根半必须放行（否则提取链读回静默拿到空内容）")
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
    }

    @Test
    @DisplayName("[E2][③′ 腿 1] cwd 半：effectiveCwd 的 slug 子树整体放行（对齐 CC getProjectDir(getCwd())）")
    void e3_anchor_cwdHalf_allowsEffectiveCwdSlug() {
        PathValidationEnv cdEnv = new PathValidationEnv(
            "session-1", "agent-1", "C:/proj/sub", "C:/proj",
            "C:/proj",             // 稳定根 ≠ cwd ⇒ 两个 slug 分裂，正好分离两条腿
            "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        String cwdSlug = AutoMemPaths.sanitizePath("C:/proj/sub");

        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + cwdSlug + "/session-1/session.jsonl", cdEnv).decisionReason())
            .as("cwd 半：effectiveCwd 的 slug 放行（对齐 CC isProjectDirPath = getProjectDir(getCwd())）"
                + "（⭐ 删掉 cwd 半 ⇒ 本断言必红）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));

        // ⭐ [③′ 有意变更] 收窄版曾断言「effectiveCwd slug 下的**非** tool-results 文件不得放行」——
        //   ③′ 起该断言**反转**：CC 的 isProjectDirPath 是**整个** getProjectDir(getCwd()) 子树，
        //   不只 tool-results。故此处明确钉住新语义（防止有人按旧断言「修回去」）。
        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + cwdSlug + "/session-1/tool-results/o.txt", cdEnv).decisionReason())
            .as("cwd 半下的 tool-results 落点也由 **project-dir** 分支放行（见下方阴影登记）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));
        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + cwdSlug + "/other.txt", cdEnv).allowed())
            .as("cwd 半是整子树放行（③′ 与 CC 一致），非仅 tool-results")
            .isTrue();
    }

    @Test
    @DisplayName("[③′ ①] bash 通道：forProcess(resolutionBase) 的 cwd 半放行「当前 cwd 的 slug」")
    void e3_forProcess_cwdHalf_allowsCurrentCwdSlug() {
        // bash 侧真实构造：BashPathValidator.java:947 `PathValidationEnv.forProcess(resolutionBase)`，
        //   resolutionBase = 本次 bash 命令的相对路径解析基准 = **当前 cwd**（随 cd 变，⛔ 无需 sessionId）。
        //   CC 对照：CC 的 bash 路径校验确实走到 isProjectDirPath(getCwd())（pathValidation.ts:232
        //   步骤 3.5 → filesystem.ts:284-291）⇒ CC 的 bash 放行「当前 cwd 那个 slug」。
        Path base = Path.of("C:/proj").toAbsolutePath().normalize();
        PathValidationEnv bashEnv = PathValidationEnv.forProcess(base);

        // 夹具前提自检（fail-loud）：真实工厂下 cwd 半必须可得（原 E2 收窄把它整条砍掉了 ⇒ 本条改前必红）
        assertThat(bashEnv.sessionProjectRoot())
            .as("夹具前提：forProcess 结构性无会话 ⇒ 稳定根半恒缺（这正是 ③′ 要用 cwd 半的原因）")
            .isNull();
        assertThat(bashEnv.projectDirs())
            .as("夹具前提：forProcess 的 effectiveCwd = resolutionBase ⇒ cwd 半必须可得")
            .hasSize(1);

        String slug = AutoMemPaths.sanitizePath(base.toString());
        String target = Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", slug, "sess-1", "session.jsonl")
            .toString();
        assertThat(PathValidation.checkReadableInternalPath(target, bashEnv).decisionReason())
            .as("⭐ ③′ ①：bash 必须能读「当前 cwd 的 slug」（与 CC 一致；E2 收窄版此处为 passthrough ⇒ 改前必红）")
            .isEqualTo(new PermissionDecisionReason.Other("Project directory files are allowed for reading"));

        // bash 侧 session-memory 仍 fail-closed（无 sessionId ⇒ CC 的 join(…, undefined, …) 无意义）
        assertThat(bashEnv.sessionMemoryDirs())
            .as("bash 无会话身份 ⇒ session-memory 两半皆无 ⇒ fail-closed")
            .isEmpty();
    }

    @Test
    @DisplayName("[③′ ②] bash 反向：把 resolutionBase 换成别的目录 ⇒ 那个 slug 不被放行（防又变宽口）")
    void e3_forProcess_otherCwdSlug_notAllowed() {
        Path baseA = Path.of("C:/projA").toAbsolutePath().normalize();
        Path baseB = Path.of("C:/projB").toAbsolutePath().normalize();
        PathValidationEnv envA = PathValidationEnv.forProcess(baseA);

        String slugB = AutoMemPaths.sanitizePath(baseB.toString());
        String otherTarget = Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", slugB, "sess-1",
            "session.jsonl").toString();
        assertThat(PathValidation.checkReadableInternalPath(otherTarget, envA).allowed())
            .as("⭐ ③′ ②：cwd=A 时不得放行 slug(B) ⇒ 挡住「又变回 662 个 slug 的宽口」")
            .isFalse();

        // 对照：同一 env 下 slug(A) 放行（证明上面那条不是「整条不通」的假红）
        String slugA = AutoMemPaths.sanitizePath(baseA.toString());
        assertThat(PathValidation.checkReadableInternalPath(
                Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", slugA, "sess-1", "session.jsonl")
                    .toString(), envA).allowed())
            .as("对照组：cwd=A 时 slug(A) 必须放行（否则上一条断言无鉴别力）")
            .isTrue();
    }

    @Test
    @DisplayName("[E2][③′] 两半皆缺 ⇒ 两分支 fail-closed；缺一半 ⇒ 只那一半失效（⛔ 都不回落宽口）")
    void e2_nullSession_failClosed() {
        // (a) 两半皆缺（effectiveCwd=null 且 sessionProjectRoot=null）⇒ 空列表 ⇒ 两分支都不命中
        PathValidationEnv empty = new PathValidationEnv(
            null, null, null, "C:/proj", null,
            "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        assertThat(empty.sessionMemoryDirs()).as("无 slug ⇒ session-memory 空（兑现 fail-closed 契约）").isEmpty();
        assertThat(empty.projectDirs()).as("无 slug ⇒ project-dir 空（⛔ 不回落宽口 projects/ 根）").isEmpty();
        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + PROJ_SLUG + "/session-1/session.jsonl", empty).allowed())
            .as("两半皆缺 ⇒ 本项目 transcript 也不放行（fail-closed）")
            .isFalse();

        // (b) sessionId=null 但 cwd 在（⭐ 真实 forProcess 形态）⇒
        //     project-dir 只含 **cwd 半**（③′ 有意行为）；session-memory 仍 fail-closed（无会话身份）
        PathValidationEnv bashLike = PathValidationEnv.forProcess(Path.of("C:/proj"));
        assertThat(bashLike.projectDirs())
            .as("bash：只 cwd 半（⭐ 恰 1 项，⛔ 不是整个 projects/ 根）")
            .hasSize(1);
        assertThat(bashLike.sessionMemoryDirs())
            .as("bash 无会话身份 ⇒ session-memory 仍 fail-closed")
            .isEmpty();

        // (c) sessionId 有、稳定根缺（介质侧解析失败态）⇒ 稳定根半失效，
        //     但 cwd 半仍在（③′：bash/无绑定场景按 cwd 放行）—— 关键是**不回落整根**
        PathValidationEnv noRoot = new PathValidationEnv(
            "session-1", "agent-1", "C:/proj", "C:/proj", null,
            "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        assertThat(noRoot.projectDirs())
            .as("稳定根缺 ⇒ project-dirs 只含 cwd 半（⭐ 恰 1 项，⛔ 不得回落 {configHome}/projects/ 宽口）")
            .containsExactly(Path.of("C:/Users/u/.claude", "projects", PROJ_SLUG).normalize().toString()
                + java.io.File.separator);
        assertThat(PathValidation.checkReadableInternalPath(
                "C:/Users/u/.claude/projects/" + "C--some-other-project" + "/session-1/session.jsonl", noRoot)
            .allowed())
            .as("稳定根缺时仍不得放行**别的** slug（防「缺一半就变宽口」）")
            .isFalse();
    }

    @Test
    @DisplayName("[E2] 与落盘介质同源：白名单 slug == SessionStorage.sessionProjectDir（活体对照）")
    void e2_slugSameSourceAsMedia() {
        // 经生产工厂构造（真实锚链：fromToolUseContext → CwdResolution.getProjectRoot），
        // 再与**介质自己**的派生（SessionStorage.sessionProjectDir）逐字对照 —— 二者若不同源，
        // 媒体写 → 权限读 回环即断裂（session memory 提取会静默拿到空内容），本断言即红。
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", Path.of("C:/proj"));
        PathValidationEnv env = PathValidationEnv.fromToolUseContext(tuc);

        String mediaDir = SessionStorage.sessionProjectDir(sessionId).normalize().toString();
        String expectSessionMemory = Path.of(mediaDir, sessionId, "session-memory").normalize().toString()
            + java.io.File.separator;
        // [③′] 两半 ⇒ 用 contains 断言「介质同源那一腿必须在内」（⛔ 不是 equals：还有 cwd 半）
        assertThat(env.sessionMemoryDirs())
            .as("白名单 session-memory 必须含与介质 SessionStorage.sessionProjectDir 同源的那一腿")
            .contains(expectSessionMemory);
        assertThat(env.projectDirs())
            .as("白名单 project-dir 必须含介质同一 slug 目录（⛔ 不是整个 projects/ 根）")
            .contains(mediaDir + java.io.File.separator);
        // cwd 半也在（③′）：本 env effectiveCwd = "C:/proj" ⇒ slug C--proj
        assertThat(env.projectDirs())
            .as("③′ cwd 半：effectiveCwd 的 slug 也须在内")
            .contains(Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", "C--proj")
                .normalize().toString() + java.io.File.separator);
    }

    @Test
    @DisplayName("[E2] 项目根解析失败（L1 命中但 L2 无绑定）⇒ 白名单 fail-closed（⛔ 不把抛带进权限路径）")
    void e2_projectRootResolutionFailure_failClosedNotThrow() {
        // 该态存在性：worktree 入口写了 SessionCwdHolder.originalCwd（L1 命中 ⇒ getOriginalCwdLayer
        //   正常返回、不抛），但会话无绑定项目（L2 MISS）⇒ **getProjectRoot 抛**。
        //   ⛔ 若无本测试，PathValidationEnv 里那个 catch 就是「声称守护、实际守不住」的冗余守卫
        //   （本仓已被抓过多次）。本用例同时用 assertThatThrownBy 证明「catch 是承重的」。
        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        com.nexusai.application.agent.agent.SessionCwdHolder.setOriginalCwd(sid, "C:/wt");
        SessionProjectRoot.setDbResolver(s -> SessionProjectRoot.Lookup.unbound());
        try {
            assertThatThrownBy(() -> CwdResolution.getProjectRoot(sid))
                .as("夹具前提自检：该态下 getProjectRoot 确实抛（⇒ PathValidationEnv 的 catch 承重）")
                .isInstanceOf(IllegalStateException.class);

            ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), sid, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", Path.of("C:/wt"));
            PathValidationEnv env = PathValidationEnv.fromToolUseContext(tuc);

            assertThat(env.sessionProjectRoot()).as("解析失败 ⇒ 稳定项目根为 null（⛔ 不回落 user.dir）").isNull();
            // [③′] 稳定根半缺失 ⇒ 只剩 cwd 半（本 env effectiveCwd = "C:/wt"）——
            //   ⭐ 恰 1 项即证明「缺一半」**不会**退化成整个 projects/ 根宽口。
            assertThat(env.projectDirs())
                .as("解析失败 ⇒ 稳定根半缺失、只剩 cwd 半（⛔ 不得回落 projects/ 宽口）")
                .hasSize(1);
            // ⭐ 非平凡断言（用**真实** config home 拼目标，⛔ 不是假前缀的同义反复）：
            //   别的项目 slug 必须在白名单外。
            assertThat(PathValidation.checkReadableInternalPath(
                    Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", "C--some-other-project",
                        "session-1", "session.jsonl").toString(), env).allowed())
                .as("解析失败 ⇒ 别的项目 slug 仍不放行（cwd 半只覆盖 C--wt，⛔ 不是宽口）")
                .isFalse();
            // 该态下介质写盘 slug 不可得（介质侧同样抛）⇒ 其 session-memory 亦不放行
            assertThat(PathValidation.checkReadableInternalPath(
                    Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects", PROJ_SLUG,
                        "session-1", "session-memory", "summary.md").toString(), env).allowed())
                .as("解析失败 ⇒ 介质写盘 slug 的 session-memory 不放行（fail-closed）")
                .isFalse();
        } finally {
            com.nexusai.application.agent.agent.SessionCwdHolder.clearOriginalCwd(sid);
            SessionProjectRoot.setDbResolver(s -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        }
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
            "session-1", "agent-1", "C:/proj", "C:/proj", "C:/proj",
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
            "session-1", "agent-1", "C:/proj", "C:/proj", "C:/proj",
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
            "s1", null, "C:/proj", "C:/proj", "C:/proj", "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);
        // 末位 cwd = 会话 cwd（root-relative edit 规则匹配根锚）；本用例 permCtx=null（无规则桶）
        // ⇒ cwd 不参与判定，取本场景会话 cwd "C:/proj"（与 env.effectiveCwd 同源）。
        assertThat(PathValidation.isPathAllowed("C:/proj/a.txt", null,
            PermissionUpdates.OperationType.READ, env, null, null, "C:/proj").allowed()).isTrue();
        assertThat(PathValidation.isPathAllowed("C:/etc/passwd", null,
            PermissionUpdates.OperationType.READ, env, null, null, "C:/proj").allowed()).isFalse();
        // 写 + 非 acceptEdits → 目录内也不 auto-allow（CC :207-209）
        assertThat(PathValidation.isPathAllowed("C:/proj/a.txt", null,
            PermissionUpdates.OperationType.WRITE, env, null, null, "C:/proj").allowed()).isFalse();
    }

    @Test
    @DisplayName("[G9] 会话 cwd 显式下传：同一 root-relative deny 规则 + 同一路径，传会话 cwd 命中 deny，传 null 不命中")
    void sessionCwd_anchorsRootRelativeEditDenyRule(@TempDir Path projectDir) throws Exception {
        // WHY（规则九 · G9）：批 3c 删 MDC 会话槽后，root-relative {@code Edit(...)} 规则
        //   （项目/本地 settings 里以 {@code ./} 或无前缀写的 deny/allow）若拿不到会话 cwd，
        //   匹配根锚回落进程 user.dir；会话绑定项目 ≠ 进程启动目录时锚错根 ⇒ 相对规则永不命中
        //   ⇒ 应被 deny 的写入被放行（权限判定错位）。
        //   本用例用**真实临时目录**（非 mock、非 "C:/proj" 常量）作会话 cwd，钉住两侧：
        //   ① cwd = 会话 cwd → 相对规则锚定会话项目根 → deny 命中（cwd 若未下传/被丢 ⇒ 变红）；
        //   ② cwd = null（无会话）→ 根锚回落 user.dir、会话项目在其外 → 不命中
        //      （回落契约与改动前逐字一致）。
        Path projectRoot = projectDir.toRealPath();
        String sessionCwd = projectRoot.toString();
        String target = projectRoot.resolve("sub/secret.txt").toString();
        // 前置（鉴别力）：临时目录与 user.dir 必须互不包含，否则两侧锚同根 ⇒ 用例失去鉴别力。
        //   用 assertThat 而非 assumeTrue —— 前提不成立必须**响亮失败**（规则十二），不得静默跳过。
        Path userDir = Path.of(System.getProperty("user.dir")).toRealPath();
        assertThat(sessionCwd.startsWith(userDir.toString())
                || userDir.toString().startsWith(sessionCwd))
            .as("临时目录 %s 与 user.dir %s 必须互不包含，否则两侧同根、用例无鉴别力", sessionCwd, userDir)
            .isFalse();

        // 同一 root-relative deny 规则：Edit("sub/secret.txt")（无 //、~/、/ 前缀 → root = cwd）
        PermissionRule denyRule = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Edit", "sub/secret.txt"));
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new EnumMap<>(PermissionRuleSource.class);
        deny.put(PermissionRuleSource.SESSION, Set.of(denyRule));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());

        // env 工作目录锚会话项目根（让 ② 确定性地走到 step3「工作目录内 read → allowed」收尾，
        //   不依赖 deny 以外的偶发分支）
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, sessionCwd, sessionCwd, sessionCwd, "C:/Users/u/.claude", "C:/Users/u/.claude",
            false, "C:/tmp/claude", false, null, null);

        // ① 传会话 cwd → deny 命中（step1 deny 优先于其余各步）
        PathValidation.PathCheckResult withSession = PathValidation.isPathAllowed(target, permCtx,
            PermissionUpdates.OperationType.READ, env, null, null, sessionCwd);
        assertThat(withSession.allowed())
            .as("会话 cwd 显式下传 ⇒ root-relative deny 规则锚定会话项目根 ⇒ 必须命中 deny（G9 不得复现）")
            .isFalse();
        assertThat(withSession.decisionReason())
            .as("命中来源必须是该 deny 规则本体（step1 deny 优先级）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
        assertThat(((PermissionDecisionReason.Rule) withSession.decisionReason()).rule())
            .as("decisionReason 携带的规则 = 传入的 root-relative deny 规则")
            .isEqualTo(denyRule);

        // ② 传 null（无会话）→ 同一路径、同一规则不命中（根锚回落 user.dir）
        PathValidation.PathCheckResult noSession = PathValidation.isPathAllowed(target, permCtx,
            PermissionUpdates.OperationType.READ, env, null, null, null);
        assertThat(noSession.allowed())
            .as("无会话（cwd=null）⇒ 根锚回落 user.dir、会话项目在其外 ⇒ 相对规则不命中（回落契约不变）")
            .isTrue();
    }

    @Test
    @DisplayName("validatePath：写操作 glob 阻断；UNC/~/shell 展开阻断（CC :373-485）")
    void validatePath_blocks() {
        PathValidationEnv env = new PathValidationEnv(
            "s1", null, "C:/proj", "C:/proj", "C:/proj", "C:/Users/u/.claude", "C:/Users/u/.claude",
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
            "s1", null, cdSubdir, originalCwd, originalCwd, "C:/Users/u/.claude", "C:/Users/u/.claude",
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
            "s1", null, "C:/proj", originalCwd, originalCwd, "C:/Users/u/.claude", "C:/Users/u/.claude",
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
        SessionProjectRoot.reset();
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
