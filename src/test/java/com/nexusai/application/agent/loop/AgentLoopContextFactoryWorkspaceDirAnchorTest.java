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
 * [批 P21 · 兜底锚守护] {@code AgentLoopContextFactory.freshSession} 的 workspaceDir 末级兜底
 * （{@code resolveFallbackWorkspaceDir}）必须锚 <b>稳定会话项目根</b>
 * （{@link CwdResolution#getProjectRoot(String)}），⛔ 不得随 {@code EnterWorktreeTool} 重锚
 * （= F1 / CC gh-30217 同型）。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * 批 P13 修的是 <b>transcript 存储根</b>的两槽分裂（写侧取 {@code getOriginalCwdLayer} 随 worktree
 * 重锚、读侧取稳定槽 ⇒ hook 找不到写侧写的文件）。本类是<b>同一根因在另一处实例</b>上的守护：
 * {@code workspaceDir} 兜底同样取了会被重锚的 {@code getOriginalCwdLayer}。而 {@code workspaceDir}
 * 的<b>全部消费点</b>都把它当「会话绑定项目根」用（{@code SessionStorage.getProjectDir(workspaceDir)}
 * 派生 transcript / tool-results / content-replacement / per-project 记忆目录）⇒ 一旦随 worktree
 * 漂移，这些存储根会跟着分裂。
 *
 * <p><b>实测可达性（批 P21 · 全仓 grep）</b>：该兜底在生产里<b>只会以 {@code sessionId == null}
 * 被调用</b>（{@code shared(String)} 硬编码传 null；会传非 null sessionId 的 3 参
 * {@code forSession} / {@code build(null session)} 两条路径无生产调用方）⇒ 本缺陷是<b>潜伏</b>而非
 * 正在发生。既有 {@code AgentLoopContextFactoryTest} 只调 {@code shared(null)}（sessionId 恒 null），
 * <b>结构上不可能</b>发现本缺陷 —— 这正是本类必须另立的理由：必须走 3 参
 * {@code forSession(topic, sessionId, msg)} 才能让 sessionId 成为真值。
 *
 * <h2>RED 条件（反向实验配方）</h2>
 * 把 {@code AgentLoopContextFactory.resolveFallbackWorkspaceDir} 的锚改回
 * {@code CwdResolution.getOriginalCwdLayer(sessionId)}（即改前形态）⇒ {@link #fallbackAnchor_doesNotFollowWorktreeReanchor()}
 * <b>必红</b>（实测读数见批 P21 报告）：重锚后产出 worktree 目录而非会话项目根。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>必须让「重锚槽」与「稳定锚」取不同值</b>：{@code SessionProjectRoot.setForSession(SESSION, boundProject)}
 *       与 {@code SessionCwdHolder.setOriginalCwd(SESSION, worktree)} 指向<b>两个不同目录</b>。
 *       这是本类唯一的鉴别力来源 —— 两值相同则新旧实现产出同一字符串，本类不可能变红。</li>
 *   <li><b>必须显式 {@code setForSession}</b>：单测环境里 {@code NoDatabaseSessionProjectRootExtension}
 *       对<b>任意</b> sessionId 答 {@code sessionlessEnvironment()}（见 {@link SessionProjectRootTestSupport}），
 *       不显式登记则稳定锚会落到无会话命名出口（进程 {@code user.dir}），断言就失去意义。</li>
 *   <li><b>必须走 3 参 {@code forSession}</b>（而非 {@code shared}）：这是<b>唯一</b>能让
 *       {@code resolveFallbackWorkspaceDir} 收到非 null sessionId 的公开入口。</li>
 *   <li><b>字段 {@code AgentLoopContextFactory.workspaceDir}（Path bean）保持 null</b>：
 *       全仓无 {@code Path} 类型的 {@code @Bean} ⇒ 纯 {@code new} 即生产中该字段的真实取值，
 *       兜底分支必然执行（否则会被该字段短路，测不到兜底）。</li>
 * </ul>
 */
@DisplayName("[批 P21] workspaceDir 末级兜底锚 = 稳定会话项目根（不随 worktree 重锚 · F1 同型防线）")
class AgentLoopContextFactoryWorkspaceDirAnchorTest {

    private static final String SESSION = "sess-p21-wsdir-anchor";

    /** 会话绑定项目根（稳定锚）。 */
    @TempDir
    Path boundProject;

    /** 模拟 {@code EnterWorktreeTool} 重锚后的 worktree 目录（重锚槽）。 */
    @TempDir
    Path worktree;

    @BeforeEach
    void declareFixtures() {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
    }

    @AfterEach
    void cleanupFixtures() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION);
    }

    @Test
    @DisplayName("进/出 worktree 前后，末级兜底产出的 workspaceDir 恒 = 会话绑定项目根")
    void fallbackAnchor_doesNotFollowWorktreeReanchor() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        Path expected = Path.of(CwdResolution.normalizeCwd(boundProject.toString()));

        // ① 基线：未进 worktree（SessionCwdHolder.originalCwd 未设 ⇒ 只有 boundProject 层可命中）
        Path before = factory.forSession("/t", SESSION, "m").sessionState().workspaceDir();
        assertThat(before)
            .as("兜底必须取会话绑定项目根（而不是 user.dir / 重锚槽）")
            .isEqualTo(expected);

        // ② 进 worktree：EnterWorktreeTool.applySessionCwd → SessionCwdHolder.setOriginalCwd
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());

        Path inside = factory.forSession("/t", SESSION, "m").sessionState().workspaceDir();
        assertThat(inside)
            .as("workspaceDir 兜底 = 会话绑定项目根 ⇒ 进 worktree 后必须不变（F1/gh-30217 同型防线）；"
                + "改回 getOriginalCwdLayer 时本断言取到 worktree 目录 ⇒ 红")
            .isEqualTo(before);
        assertThat(inside)
            .as("⛔ 不得锚到 worktree 目录（重锚槽）")
            .isNotEqualTo(Path.of(CwdResolution.normalizeCwd(worktree.toString())));

        // ③ 出 worktree：ExitWorktreeTool.clearOriginalCwd ⇒ 仍不变
        SessionCwdHolder.clearOriginalCwd(SESSION);

        Path exited = factory.forSession("/t", SESSION, "m").sessionState().workspaceDir();
        assertThat(exited)
            .as("退出 worktree 后仍恒 = 会话绑定项目根")
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("与 AgentLoopContext.resolveDefaultWorkspaceDir 同源：无会话兜底（shared(null)）取同一稳定出口")
    void fallbackAnchor_isSameExitAsSessionlessDefault() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();

        // 无会话形态（shared() 是生产中唯一可达的兜底入口）：sessionId = null
        Path viaFactory = factory.shared(null).sessionState().workspaceDir();
        // 直接 new LoopSessionState（无工厂覆盖）时的默认值 = resolveDefaultWorkspaceDir
        Path viaDefault = new AgentLoopContext.LoopSessionState().workspaceDir();

        assertThat(viaFactory)
            .as("[批 P21 §三.3] 两条无会话兜底出口必须同值（同一无会话命名出口）"
                + " ⚠️ 本断言为「关系一致性」记录，新旧实现均绿，不承担捉红职责")
            .isEqualTo(viaDefault);
    }
}
