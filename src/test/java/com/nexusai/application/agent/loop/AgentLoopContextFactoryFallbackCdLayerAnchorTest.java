package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 P23 · ④ P21 守护的边界补齐] {@code AgentLoopContextFactory.freshSession} 的 workspaceDir
 * 末级兜底（{@code resolveFallbackWorkspaceDir}）<b>除了不随 worktree 重锚，也不得随 bash
 * {@code cd} 漂移</b>。
 *
 * <h2>WHY 需要另立（P21 的守护漏了一个维度）</h2>
 * P21 的 {@code AgentLoopContextFactoryWorkspaceDirAnchorTest} 只让
 * {@code SessionCwdHolder.setOriginalCwd}（worktree 重锚槽）与稳定锚取不同值 ⇒ 它只能抓住
 * 「锚 = {@code getOriginalCwdLayer}」这一类改坏。<b>实测（批 P23 主 agent 变异 X）</b>：把兜底
 * 换成 {@code CwdResolution.getCwd(sessionId)}（cd 覆盖层）时，P21 那 2 条断言<b>全绿</b>
 * —— 因为该用例从未写过 sessionCwd 槽（{@code SessionCwdHolder.set}），两腿在该夹具下同值。
 *
 * <p>而 {@code workspaceDir} 的语义是「<b>会话绑定项目根</b>」（其消费点经
 * {@code SessionStorage.getProjectDir(workspaceDir)} 派生 transcript / tool-results /
 * content-replacement / per-project 记忆目录）⇒ 它**必须**是**项目身份**，不是**文件操作**语义。
 * CC {@code getProjectRoot()} 的 javadoc 逐字：「Use for project identity (history, skills,
 * sessions) <b>not file operations</b>」；{@code CwdResolution.getProjectRoot} 的 javadoc 同样点名
 * 「任何一层落到 {@code getCwd}/{@code getOriginalCwd} 都会把这条不变量破坏掉
 * （前者被 bash {@code cd} 挪走，后者被 worktree 入口挪走）」。⇒ {@code getCwd} 对这个兜底<b>也是错的</b>。
 *
 * <h2>RED 条件（反向实验配方）</h2>
 * 把 {@code AgentLoopContextFactory.resolveFallbackWorkspaceDir} 的 {@code getProjectRoot} 换成
 * {@code CwdResolution.getCwd(sessionId)} ⇒
 * {@link #fallbackAnchor_doesNotFollowCdOverrideLayer()} <b>必红</b>（取到 cd 目录而非会话项目根）。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li>必须显式 {@code SessionCwdHolder.set(SESSION, cdDir)}（<b>sessionCwd 槽</b>，与 P21 用的
 *       {@code setOriginalCwd} 是<b>不同</b>的槽）—— 否则两腿同值，本类恒绿。</li>
 *   <li>三个目录必须互异，且用例自证「cd 槽确实生效」（{@code CwdResolution.getCwd} 真的返回 cd 目录）
 *       —— 这是反恒绿的显式前提。</li>
 * </ul>
 */
@DisplayName("[批 P23 · ④] workspaceDir 末级兜底锚不得随 bash cd 漂移（P21 守护的边界补齐）")
class AgentLoopContextFactoryFallbackCdLayerAnchorTest {

    private static final String SESSION = "sess-p23-wsdir-cd-layer";

    /** 稳定锚：会话绑定项目根。 */
    @TempDir
    Path boundProject;

    /** cd 槽：bash {@code cd} 落到的目录（与稳定锚不同值）。 */
    @TempDir
    Path cdDir;

    @BeforeEach
    void declareFixtures() {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
        SessionCwdHolder.set(SESSION, cdDir.toString());
    }

    @AfterEach
    void cleanupFixtures() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.clear(SESSION);
    }

    @Test
    @DisplayName("cd 到别处后，末级兜底产出的 workspaceDir 恒 = 会话绑定项目根")
    void fallbackAnchor_doesNotFollowCdOverrideLayer() {
        Path expected = Path.of(CwdResolution.normalizeCwd(boundProject.toString()));
        Path cdValue = Path.of(CwdResolution.normalizeCwd(cdDir.toString()));

        // 夹具自证（反恒绿）：cd 槽真的生效且与稳定锚不同值 ——
        // 否则「换成 getCwd」的变异体会与正确实现产出同一字符串，本类必然假绿。
        assertThat(CwdResolution.getCwd(SESSION))
            .as("cd 槽必须真的生效（getCwd 命中 sessionCwd 层）")
            .isEqualTo(cdValue.toString());
        assertThat(cdValue)
            .as("cd 槽必须与稳定锚不同值（同值 ⇒ 本类不可能变红）")
            .isNotEqualTo(expected);

        Path actual = new AgentLoopContextFactory()
            .forSession("/t", SESSION, "m").sessionState().workspaceDir();

        assertThat(actual)
            .as("workspaceDir 兜底 = 会话绑定项目根（项目身份语义）⇒ ⛔ 不得被 bash cd 挪走；"
                + "换成 CwdResolution.getCwd(sessionId) 时本条取到 cd 目录 ⇒ 红")
            .isEqualTo(expected);
        assertThat(actual)
            .as("⛔ 不得锚到 cd 覆盖层")
            .isNotEqualTo(cdValue);
    }
}
