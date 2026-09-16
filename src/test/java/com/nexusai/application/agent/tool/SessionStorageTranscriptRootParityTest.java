package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [F1 · 2026-09-16] transcript 存储根「两槽分裂」收口实证 —— <b>锚 = 会话绑定项目根（稳定）</b>。
 *
 * <h2>WHY（CLAUDE.md 规则 9 · 测试验证意图，不是验证行为）</h2>
 * 「会话的 transcript 存储根」曾由<b>两个槽</b>承载且取的不是同一个值：
 * <ul>
 *   <li><b>写侧</b> {@link SessionStorage#sessionProjectDir(String)} —— 原实现
 *       {@code getProjectDir(getOriginalCwdLayer(sessionId))}。而 {@code originalCwd} 槽被
 *       {@code EnterWorktreeTool.applySessionCwd}（{@code SessionCwdHolder.setOriginalCwd}）
 *       <b>重锚</b> ⇒ <b>进 worktree 后写侧换根</b>。</li>
 *   <li><b>读侧</b> {@code LlmAgentLoop} 给 {@code SubagentStop} hook 算
 *       {@code agent_transcript_path} —— 取 {@code ctx.sessionState().workspaceDir()}
 *       （= {@code LlmAgentLoop.resolveSessionProjectRoot} 落下的<b>会话绑定项目根</b>）⇒ 稳定。</li>
 * </ul>
 * ⇒ 读侧按稳定根去找、写侧却把文件写进了 worktree slug 目录 ⇒
 * <b>hook 看到的是 MISSING</b>。这与 CC 的 <b>gh-30217</b> 同型
 * （CC {@code sessionStorage.ts:207-211} 注释逐字：「Without this, hooks get a transcript_path
 * computed from originalCwd while the actual file was written to sessionProjectDir — different
 * directories, so the hook sees MISSING (gh-30217)」）。
 *
 * <p><b>本测试守护的意图</b>：transcript 是「会话身份」的存储（history / resume / hook 载荷），
 * 其根必须<b>只由会话绑定项目根决定</b>，⛔ 不得被 worktree（一次性隔离目录）挪走。若有人把
 * 任一侧改回随 {@code originalCwd}（worktree）变，本类必红。
 *
 * <h2>CC 对照（⭐ 与 CC 效果等价、机制有意不同）</h2>
 * CC 靠 {@code ensureCurrentSessionFile()}「第一次算一次 ⇒ 缓存进 {@code this.sessionFile} 实例字段」
 * （{@code sessionStorage.ts:1298-1304}）实现「首次之后固定」；本仓是<b>每次现算</b>，故改用
 * <b>稳定槽</b>（{@link SessionProjectRoot}）而非缓存 —— 有意选择：缓存要引入会话级可变状态与
 * 失效语义，而本仓已有「会话绑定项目根」这一现成稳定槽（批 P9 / P10a 两次确认的
 * CC {@code getProjectRoot()} 对应物，且 {@code ChatService.restoreWorktreeForResume} 已有同款先例）。
 *
 * <h2>RED 条件（反向实验配方）</h2>
 * 把 {@link SessionStorage#sessionProjectDir(String)} 的实现改回
 * {@code getProjectDir(Path.of(CwdResolution.getOriginalCwdLayer(sessionId)))} ⇒
 * {@link #writeSideRoot_isStableBoundProject_notWorktree()} /
 * {@link #enteringAndExitingWorktree_doesNotMoveRoot()} /
 * {@link #readSide_matchesWriteSide_sameFile()} 三条同时翻红。
 *
 * <p><b>夹具要点</b>：{@code SessionProjectRoot.setForSession} 内部走
 * {@link SessionProjectRoot#isValidProjectRoot}（<b>绝对路径且目录存在</b>）⇒ 夹具必须用
 * {@code @TempDir} 下<b>真实创建</b>的目录；且必须<b>显式</b> {@code setForSession} ——
 * 单测环境里 {@code NoDatabaseSessionProjectRootExtension} 对<b>任意</b> sessionId 答
 * {@code sessionlessEnvironment()}，不显式登记的会话会走「无会话命名出口」而不是绑定根。
 */
@DisplayName("[F1] transcript 存储根两槽收口：锚 = 会话绑定项目根（⛔ 不随 worktree 变）")
class SessionStorageTranscriptRootParityTest {

    private static final String SESSION_ID = "sess-f1-parity";
    private static final String AGENT_ID = "a0000000f1parity";

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        // 进程级 static 槽（冻结表 / originalCwd 重锚层）必须清理，否则跨类污染。
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION_ID);
    }

    /** 绑定项目根（会话身份锚）· 真实目录（isValidProjectRoot 要求）。 */
    private Path boundProject() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
    }

    /** worktree 目录（一次性隔离目录）· 真实目录（normalizeCwd 走 realpath）。 */
    private Path worktree() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-worktree")).toRealPath();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 守护 ①：写侧根 = 会话绑定项目根（⛔ 不被 worktree 重锚挪走）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[F1] 写侧落点锚会话绑定项目根；进 worktree 后仍不动")
    void writeSideRoot_isStableBoundProject_notWorktree() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        SessionProjectRoot.setForSession(SESSION_ID, proj.toString());
        // 前置：originalCwd 槽确已被 worktree 重锚（否则不构成 F1 场景）
        SessionCwdHolder.setOriginalCwd(SESSION_ID, wt.toString());
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION_ID))
            .as("夹具前置：originalCwd 层 = worktreePath（EnterWorktreeTool.applySessionCwd 的效果）")
            .isEqualTo(wt.toString());

        Path actual = SessionStorage.sessionProjectDir(SESSION_ID);

        assertThat(actual)
            .as("transcript 存储根必须 = 会话绑定项目根的 slug 目录（写侧锚稳定）；"
                + "⛔ 被 worktree 重锚 ⇒ hook 按读侧找不到写侧写的文件（CC gh-30217 同型）")
            .isEqualTo(SessionStorage.getProjectDir(proj));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 守护 ②：读侧（SubagentStop hook 的 agent_transcript_path）与写侧落同一文件
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[F1] 读侧 agent_transcript_path == 写侧 sidechain 落点（两槽同源）")
    void readSide_matchesWriteSide_sameFile() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        SessionProjectRoot.setForSession(SESSION_ID, proj.toString());
        SessionCwdHolder.setOriginalCwd(SESSION_ID, wt.toString());

        // 写侧实际落点：SubagentExecutor.resolveSessionDir → sessionProjectDir（同一 seam）
        Path written = SessionStorage.sessionProjectDir(SESSION_ID)
            .resolve(SESSION_ID).resolve(SessionStorage.SUBAGENTS_SUBDIR)
            .resolve(SessionStorage.AGENT_FILE_PREFIX + AGENT_ID + SessionStorage.AGENT_FILE_EXT);

        // 读侧：SubagentStop hook 用的 seam（与 LlmAgentLoop 调用点同一个方法）
        Path readSide = SessionStorage.getAgentTranscriptPathForSession(SESSION_ID, AGENT_ID);

        assertThat(readSide)
            .as("hook 的 agent_transcript_path 必须指向写侧真正写下的文件"
                + "（两槽分裂 ⇒ hook 看到 MISSING，CC gh-30217 同型）")
            .isEqualTo(written);
        // ⚠️ 上一条是**等式**断言 ⇒ 两侧「一起被改坏」时仍绿（单点变异抓不到）。
        //   故必须补一条**绝对锚**断言：读侧路径必须落在**会话绑定项目根**的 slug 下。
        //   WHY：真正要守的不变量是「hook 找得到文件」+「文件在会话身份锚下」两条，缺一不可。
        //   ⛔ 用 Path 结构断言而非 AssertJ startsWith（后者内部 toRealPath，slug 目录未创建时会抛
        //     NoSuchFileException —— 本仓已见过该环境性 flake）；结构断言是纯字符串比较，零 IO。
        assertThat(readSide.getParent().getParent().getParent())
            .as("读侧路径必须落在绑定项目根的 slug 下（绝对锚 · 防两侧一起漂到 worktree slug）")
            .isEqualTo(SessionStorage.getProjectDir(proj));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 守护 ③：进 / 出 worktree 落点不变（会话内根唯一）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[F1] 进 worktree → 落点不变；退出 worktree → 落点仍不变")
    void enteringAndExitingWorktree_doesNotMoveRoot() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        SessionProjectRoot.setForSession(SESSION_ID, proj.toString());

        Path before = SessionStorage.sessionProjectDir(SESSION_ID);

        SessionCwdHolder.setOriginalCwd(SESSION_ID, wt.toString());   // EnterWorktreeTool
        Path inWorktree = SessionStorage.sessionProjectDir(SESSION_ID);

        SessionCwdHolder.clearOriginalCwd(SESSION_ID);                // ExitWorktreeTool
        Path afterExit = SessionStorage.sessionProjectDir(SESSION_ID);

        assertThat(inWorktree)
            .as("进 worktree 后 transcript 根必须不变（会话内根唯一）")
            .isEqualTo(before);
        assertThat(afterExit)
            .as("退出 worktree 后 transcript 根仍不变（往返幂等）")
            .isEqualTo(before);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 守护 ④：flat transcript 写侧锚同样稳定
    //   （ChatService.appendReasoningDurationToTranscript 现在改走
    //    SessionStorage.sessionProjectRoot(sessionId)，再由 appendReasoningDuration 内部
    //    getTranscriptPath 派生一次 —— ⛔ 不得再传已派生的 project dir，会双重包裹）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[F1] flat transcript 写侧锚（sessionProjectRoot）= 绑定项目根，且进出 worktree 不变")
    void flatTranscriptWriteAnchor_isStable() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        SessionProjectRoot.setForSession(SESSION_ID, proj.toString());

        Path before = Path.of(SessionStorage.sessionProjectRoot(SESSION_ID));
        SessionCwdHolder.setOriginalCwd(SESSION_ID, wt.toString());
        Path inWorktree = Path.of(SessionStorage.sessionProjectRoot(SESSION_ID));

        assertThat(before)
            .as("flat transcript 写侧锚 = 会话绑定项目根（⛔ 不是 worktree、⛔ 不是已派生的 project dir）")
            .isEqualTo(proj);
        assertThat(inWorktree)
            .as("进 worktree 后 flat transcript 写侧锚必须不变（否则 flat 主记录跨 slug 分裂）")
            .isEqualTo(before);
        // 派生一次后必须落在绑定项目 slug 下（⛔ 防双重包裹）
        assertThat(SessionStorage.getTranscriptPath(before, SESSION_ID))
            .as("getTranscriptPath(root) 派生一次 = {configHome}/projects/{slug(bound)}/{sid}.jsonl")
            .isEqualTo(SessionStorage.getProjectDir(proj).resolve(SESSION_ID + ".jsonl"));
    }
}
