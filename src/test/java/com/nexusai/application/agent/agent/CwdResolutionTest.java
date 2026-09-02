package com.nexusai.application.agent.agent;

import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CwdResolution 工作目录域统一入口 · 对齐 CC utils/cwd.ts pwd()/getCwd() + bootstrap/state.ts 四层 STATE。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 cwd 解析是<b>分层回落</b>语义：{@code pwd() = override ?? STATE.cwd}
 * （cwd.ts:19-21），{@code getCwd() = try pwd() catch → getOriginalCwd()}（cwd.ts:26-32）。若 Java 端分层
 * 顺序错误或某层异常抛出，则文件工具/git/权限相对路径会取错 cwd（跨项目污染 / cd 后用旧 cwd）。本测试
 * 锁定五场景分层正确 + 异常 safeGet 回落 + cd-in-worktree 合并存储语义（INV-2）。
 *
 * <p>场景对应 AC-1 五场景：
 * <ol>
 *   <li>override 非空 → override</li>
 *   <li>override 空 + sessionCwd 非空 → sessionCwd</li>
 *   <li>+ boundProject 非空 → boundProject（override/sessionCwd 均空时）</li>
 *   <li>全空 → user.dir</li>
 *   <li><b>活跃 worktree 内 cd 后 getCwd 返回 cd 子目录</b>（合并存储，WorktreeCwdTracker 不作优先层，INV-2）</li>
 * </ol>
 */
@DisplayName("[CC-CWD-01/02/04] CwdResolution 三层 getCwd + override + originalCwd 层")
class CwdResolutionTest {

    @AfterEach
    void cleanup() {
        CwdResolution.clearCurrentOverride();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    @Test
    @DisplayName("场景①: override 非空 → 返回 override (对齐 CC cwdOverrideStorage.getStore ??)")
    void scenario1_overrideWins(@TempDir Path overrideDir) throws Exception {
        // WHY: CC pwd() 优先取 AsyncLocalStorage override（cwd.ts:19-21），并发 agent 各自隔离。
        SessionCwdHolder.set("sess-a", "/some/session-cwd");
        SessionProjectRoot.setForSession("sess-a", "/some/bound-project");

        String result = CwdResolution.runWithCwdOverride(overrideDir.toString(),
                () -> CwdResolution.getCwd("sess-a"));

        assertThat(result)
            .as("override 必须压过 sessionCwd / boundProject")
            .isEqualTo(overrideDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景②: override 空 + sessionCwd 非空 → 返回 sessionCwd (对齐 CC STATE.cwd)")
    void scenario2_sessionCwdWinsWhenNoOverride(@TempDir Path sessionDir) throws Exception {
        // WHY: 无 override 时 pwd() 回 getCwdState()=STATE.cwd。worktree 入口与 cd 共用此层 [Fix-R1]。
        SessionProjectRoot.setForSession("sess-a", "/some/bound-project");
        SessionCwdHolder.set("sess-a", sessionDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("sessionCwd 必须压过 boundProject")
            .isEqualTo(sessionDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景③: override+sessionCwd 均 空 + boundProject 非空 → 返回 boundProject (D-1: getForSession)")
    void scenario3_boundProjectWhenNoSessionCwd(@TempDir Path projectDir) throws Exception {
        // WHY: boundProject 层对齐 CC originalCwd（启动目录）。D-1 裁决：只读 getForSession，不读 resolve()
        // （resolve 回落 env/config home 属身份域，会使 user.dir 成死代码 + 身份域泄入工作目录域）。
        SessionProjectRoot.setForSession("sess-a", projectDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("boundProject 必须是 getForSession 的绑定值，不得读 resolve() 回落链")
            .isEqualTo(projectDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景④: 全空 → 返回 user.dir (对齐 CC 进程启动 cwd 兜底)")
    void scenario4_userDirWhenAllEmpty() throws Exception {
        // WHY: 全空时回落 JVM user.dir（对齐 CC 进程启动 cwd 兜底，INV-4）。
        String result = CwdResolution.getCwd("sess-unbound");

        assertThat(result)
            .as("全空必须回落 user.dir")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
    }

    @Test
    @DisplayName("场景⑤ [Fix-R1]: 活跃 worktree 内 cd 后 getCwd 返回 cd 子目录 (合并存储 INV-2)")
    void scenario5_cdInWorktreeReturnsCdSubdir(@TempDir Path worktreeBase) throws Exception {
        // WHY: [Fix-R1] worktree 入口与 cd 共用 SessionCwdHolder（对齐 CC 单 STATE.cwd）。
        //       若 WorktreeCwdTracker 作 getCwd 优先层，活跃 worktree 内 cd 后 getCwd 会返回 worktree 基路径
        //       而非 cd 子目录，违反 INV-2「cd 后用新 cwd」与 CC Shell.ts 行为。
        //       本测试验证 CwdResolution 不读 WorktreeCwdTracker，sessionCwd 层（cd 写入）才是 worktree 内的真相。
        Path sub = worktreeBase.resolve("deep/sub");
        sub.toFile().mkdirs();

        // worktree 入口：写 SessionCwdHolder（与 bash cd 同槽）
        SessionCwdHolder.set("sess-a", worktreeBase.toString());
        // 模拟 WorktreeCwdTracker 仍记录基路径（退出恢复用）—— CwdResolution 不应读它
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setCwd("sess-a", worktreeBase);

        // 入口时 getCwd 应返回 worktree 基路径（sessionCwd 层）
        String afterEnter = CwdResolution.getCwd("sess-a");
        assertThat(afterEnter).isEqualTo(worktreeBase.toRealPath().toString());

        // bash cd 子目录 → 覆盖同槽 sessionCwd
        SessionCwdHolder.set("sess-a", sub.toString());

        String afterCd = CwdResolution.getCwd("sess-a");
        assertThat(afterCd)
            .as("cd 后 getCwd 必须返回 cd 子目录，不得返回 WorktreeCwdTracker 记录的基路径 (INV-2)")
            .isEqualTo(sub.toRealPath().toString())
            .isNotEqualTo(worktreeBase.toRealPath().toString());

        // 清理 tracker（避免跨测试污染）
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd("sess-a");
    }

    @Test
    @DisplayName("getOriginalCwdLayer: boundProject 非空 → boundProject；否则 user.dir (D-1: 不读 resolve)")
    void originalCwdLayer_boundProjectOrUserDir(@TempDir Path projectDir) throws Exception {
        // WHY: CC getOriginalCwd (state.ts:500-502) 作 CLAUDE.md 扫描/存档锚。D-1 裁决 Java 端 = getForSession ?? user.dir。
        SessionProjectRoot.setForSession("sess-a", projectDir.toString());
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .isEqualTo(projectDir.toRealPath().toString());

        SessionProjectRoot.clearSession("sess-a");
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("未绑定回落 user.dir，不读 resolve() 回落链")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
    }

    @Test
    @DisplayName("[INV-3] 进 worktree 后 getOriginalCwdLayer 返回 worktreePath 非 boundProject")
    void originalCwdLayer_worktreeEntryReanchorsToWorktreePath(@TempDir Path boundProject,
                                                              @TempDir Path worktreePath) throws Exception {
        // WHY (规则九): CC EnterWorktreeTool.ts:94-96 三连 process.chdir + setCwd(worktreePath)
        //   + setOriginalCwd(getCwd())。setOriginalCwd 此时=getCwd()=worktreePath（setCwd 已先写），
        //   故 STATE.originalCwd 重锚到 worktreePath。CC claudemd.ts:851 getOriginalCwd() 用作
        //   CLAUDE.md 扫描根、会话存档锚——worktree 会话内这两者必须走 worktreePath 而非 boundProject，
        //   否则 worktree 内的 CLAUDE.md 不会被读到、存档锚到 boundProject 错位。
        //   D-1 红线不变：getOriginalCwdLayer 不读 resolve()（新 originalCwd 槽是独立槽，非 resolve）。
        SessionProjectRoot.setForSession("sess-a", boundProject.toString());

        // 进 worktree 前：originalCwdLayer = boundProject（启动锚）
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("进 worktree 前 originalCwdLayer 必须是 boundProject")
            .isEqualTo(boundProject.toRealPath().toString());

        // 进 worktree：对齐 CC setOriginalCwd(getCwd())=worktreePath 重锚 originalCwd 层
        SessionCwdHolder.setOriginalCwd("sess-a", worktreePath.toString());

        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("进 worktree 后 originalCwdLayer 必须重锚到 worktreePath (INV-3)，不得仍是 boundProject")
            .isEqualTo(worktreePath.toRealPath().toString())
            .isNotEqualTo(boundProject.toRealPath().toString());
    }

    @Test
    @DisplayName("[INV-3] Exit worktree 后 originalCwdLayer 回落 boundProject (对齐 CC ExitWorktreeTool:129 恢复)")
    void originalCwdLayer_exitRestoresBoundProject(@TempDir Path boundProject,
                                                    @TempDir Path worktreePath) throws Exception {
        // WHY: CC ExitWorktreeTool.ts:126-129 restoreSessionToOriginalCwd: setCwd(originalCwd)
        //   + setOriginalCwd(originalCwd)——退出恢复到 pre-worktree originalCwd。Java 端无 pre-worktree
        //   originalCwd 持久化（boundProject 是稳定身份不变），clearOriginalCwd 回落 boundProject
        //   对齐 CC pre-worktree originalCwd=boundProject 语义。退出后 CLAUDE.md 扫描/存档应回到 boundProject。
        SessionProjectRoot.setForSession("sess-a", boundProject.toString());
        SessionCwdHolder.setOriginalCwd("sess-a", worktreePath.toString());
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .isEqualTo(worktreePath.toRealPath().toString());

        // Exit worktree：clear originalCwd 槽（对齐 CC 退出恢复）
        SessionCwdHolder.clearOriginalCwd("sess-a");

        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("Exit worktree 后 originalCwdLayer 必须回落 boundProject (INV-3 恢复)")
            .isEqualTo(boundProject.toRealPath().toString())
            .isNotEqualTo(worktreePath.toRealPath().toString());
    }

    @Test
    @DisplayName("normalizeCwd: realpath + NFC 归一化，不抛异常")
    void normalizeCwd_realpathAndNfcSafe(@TempDir Path dir) throws Exception {
        // WHY: 对齐 CC setCwdState NFC + Shell.ts realpathSync，避免符号链接/Unicode 假阳性。目录被删不抛（catch 兜底）。
        String normalized = CwdResolution.normalizeCwd(dir.toString());
        assertThat(normalized).isEqualTo(dir.toRealPath().toString());

        // 不存在路径不抛异常，回原值 + NFC
        String ghost = CwdResolution.normalizeCwd("/nonexistent/ghost/path");
        assertThat(ghost).isNotNull();
    }

    @Test
    @DisplayName("异常 safeGet: 层异常不抛，逐层回落 (对齐 CC getCwd catch → getOriginalCwd)")
    void safeGet_fallsBackOnException() throws Exception {
        // WHY: CC getCwd() try pwd() catch → getOriginalCwd()。Java 端各层 safeGet 异常回 null 逐层回落，最终 user.dir 恒非 null。
        //   boundProject 层 getForSession 对未绑定 sessionId 返回 null（非异常），此处验证整体不抛 + 回落 user.dir。
        String result = CwdResolution.getCwd("never-bound-session");
        assertThat(result)
            .as("未绑定会话必须回落 user.dir 且不抛异常")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
    }
}
