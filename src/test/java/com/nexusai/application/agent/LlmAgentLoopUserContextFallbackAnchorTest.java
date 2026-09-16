package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 P23 · F1 同型第三处守护] {@code LlmAgentLoop.collectRunMaterial} 内
 * {@code UserContextProvider} 的 <b>兜底腿</b>必须锚 <b>稳定会话项目根</b>
 * （{@link CwdResolution#getProjectRoot(String)}），⛔ 不得随 {@code EnterWorktreeTool} 重锚，
 * ⛔ 也不得随 bash {@code cd} 漂移。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * 该三元的<b>主腿</b>是 {@code ctx.sessionState().workspaceDir()}，其唯一生产写入点为
 * {@code LlmAgentLoop.resolveSessionProjectRoot} → {@code SessionProjectRoot.lookup}
 * （{@code sessions.main_project_id → projects.path}），与 {@code getProjectRoot} 同一条判据。
 * 兜底腿原取 {@code getOriginalCwdLayer}（多一层 {@code SessionCwdHolder.getOriginalCwd}
 * 重锚槽）⇒ <b>同一三元两条腿语义不同</b>，进 worktree 后兜底腿漂到 worktreePath = F1
 * （CC gh-30217）同型。批 P13 修 transcript 存储根、P21 修
 * {@code AgentLoopContextFactory.resolveFallbackWorkspaceDir}，本类守的是第三处。
 *
 * <h2>可达性（本批实测，⛔ 不是推断）</h2>
 * 与本仓既有认知（P21 处 {@code sessionId 恒 null}）<b>不同</b>：本兜底的 sessionId 来自
 * {@code state.sessionId()}，<b>可真非 null</b>。机制：
 * <ol>
 *   <li>{@code LlmAgentLoop.workspaceDir} 字段<b>初值 null</b>（只有
 *       {@code resolveSessionProjectRoot} 成功分支才赋值）；</li>
 *   <li>{@code buildSessionStateFromInstance()} <b>无条件</b>
 *       {@code session.setWorkspaceDir(workspaceDir)} ⇒ 解析失败时
 *       {@code LoopSessionState.workspaceDir} 被写成 null（覆盖其非 null 默认值）；</li>
 *   <li>主循环经 5 参 {@code forSession(..., session, ...)} 用该实例传入 session。</li>
 * </ol>
 * 故兜底腿可达。实测读数见类内两条用例的红/绿记录（批 P23 报告）。
 *
 * <h2>RED 条件（反向实验配方）</h2>
 * 把 {@code LlmAgentLoop} 该处锚改回 {@code CwdResolution.getOriginalCwdLayer(...)} ⇒
 * {@link #fallbackAnchor_doesNotFollowWorktreeReanchor()} 红（读到 worktree 的 CLAUDE.md 哨兵）；
 * 改成 {@code CwdResolution.getCwd(...)} ⇒ 同一条红（读到 cd 目录的哨兵）。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>三个候选目录各放一个可区分的 CLAUDE.md 哨兵</b> —— 断言「读到哪一个」，
 *       而不是断言路径字符串；这是本类唯一的鉴别力来源。三值相同则任何实现都绿。</li>
 *   <li><b>必须让「重锚槽」「cd 槽」「稳定锚」取三个不同目录</b>：任一相同 ⇒ 对应断言恒绿。</li>
 *   <li><b>必须显式 {@code setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对任意 sessionId 答 sessionless
 *       （见 {@code SessionProjectRootTestSupport}），不显式登记稳定锚会落到进程 user.dir。</li>
 *   <li><b>{@code claudemdEngine} 必须为 null</b>（{@code TestContexts} 默认即 null）：
 *       此时 {@code UserContextProvider.claudeMd()} 走 {@code projectRoot/CLAUDE.md} 子集分支，
 *       本兜底值直接决定读哪个文件；若注入引擎，扫描根改由 {@code sessionId} 解析，
 *       本兜底值就<b>观测不到</b>（断言恒绿）。</li>
 * </ul>
 */
@DisplayName("[批 P23] collectRunMaterial 的 UserContextProvider 兜底腿锚 = 稳定会话项目根（F1 同型第三处）")
class LlmAgentLoopUserContextFallbackAnchorTest {

    private static final String SESSION = "sess-p23-fallback-anchor";

    /** 稳定锚：DB 绑定项目根（= sessions.main_project_id → projects.path）。 */
    @TempDir
    Path boundProject;

    /** 重锚槽：模拟 {@code EnterWorktreeTool} 重锚后的 worktree 目录。 */
    @TempDir
    Path worktree;

    /** cd 槽：模拟 bash {@code cd} 落到子目录后的当前工作目录。 */
    @TempDir
    Path cdDir;

    /** 正向对照用：主腿（{@code workspaceDir}）指向的第四个目录（与三个锚槽均不同值）。 */
    @TempDir
    Path primaryDir;

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        BundledSkillEnabledGates.bridgeSettingsMapper(null);

        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        SessionCwdHolder.set(SESSION, cdDir.toString());

        Files.writeString(boundProject.resolve("CLAUDE.md"), "BOUND_MARKER");
        Files.writeString(worktree.resolve("CLAUDE.md"), "WORKTREE_MARKER");
        Files.writeString(cdDir.resolve("CLAUDE.md"), "CD_MARKER");
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION);
        SessionCwdHolder.clear(SESSION);
        NexusaiPaths.setConfigHomeDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    /**
     * 组装 {@code collectRunMaterial} 的 userContext.claudeMd（claudemdEngine=null ⇒
     * 单文件子集分支，值 = 兜底锚目录下 CLAUDE.md 的 trim 内容）。
     *
     * <p>夹具三件套：① {@code workspaceDir=null} 强制走兜底腿；② {@code state.sessionId()}
     * 非 null（本兜底的可达前提）；③ 三个候选目录的哨兵互不相同。
     */
    private static String assembledClaudeMd() {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        ctx.sessionState().setWorkspaceDir(null);
        AgentState state = new AgentState(null, SESSION, null);
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), List.of(), minimalTuc(), QuerySource.REPL_MAIN_THREAD, "test-model",
            null, null, null, null, null, depsOf(ctx), ProviderConfig.empty());
        return LlmAgentLoop.collectRunMaterial(ctx, params, state).userContext().get("claudeMd");
    }

    private static ToolUseContext minimalTuc() {
        return ToolUseContext.of(UUID.randomUUID(), SESSION,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT, List.of());
    }

    private static LoopDeps depsOf(AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
            @Override public String uuid() { return "p23-fallback-anchor"; }
        };
    }

    @Test
    @DisplayName("夹具有效性自检：cd 槽与重锚槽确实生效且与稳定锚不同值（否则本类恒绿）")
    void fixture_actuallySeparatesTheThreeAnchors() {
        // WHY（规则十二 · 反恒绿）：本类全部鉴别力都来自「三个槽指向三个不同目录」。
        // 若夹具写错（如 cd 槽未设），getCwd 变异体就会与正确实现同值 ⇒ 断言恒绿。
        // 本用例把该前提显式钉住：三槽互异，且两个「错锚」出口确实能取到各自的值。
        assertThat(CwdResolution.getCwd(SESSION))
            .as("cd 槽必须真的生效（否则 getCwd 变异体与正确实现同值 ⇒ 恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(cdDir.toString()));
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("重锚槽必须真的生效（否则 getOriginalCwdLayer 变异体与正确实现同值 ⇒ 恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(worktree.toString()));
        assertThat(CwdResolution.getProjectRoot(SESSION))
            .as("稳定锚必须真的生效（显式 setForSession）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
    }

    @Test
    @DisplayName("进/出 worktree 前后，兜底腿恒读稳定会话项目根（⛔ 不随 EnterWorktreeTool 重锚）")
    void fallbackAnchor_doesNotFollowWorktreeReanchor() {
        String before = assembledClaudeMd();
        assertThat(before)
            .as("兜底腿必须读稳定会话项目根的 CLAUDE.md（改回 getOriginalCwdLayer ⇒ 读到 WORKTREE_MARKER ⇒ 红）")
            .isEqualTo("BOUND_MARKER");
        assertThat(before)
            .as("⛔ 不得锚到 worktree 目录（重锚槽）")
            .isNotEqualTo("WORKTREE_MARKER");

        // 进 worktree（EnterWorktreeTool.applySessionCwd 的同款效果）
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        assertThat(assembledClaudeMd())
            .as("重锚后仍恒读稳定会话项目根（F1/gh-30217 同型防线）")
            .isEqualTo("BOUND_MARKER");

        // 出 worktree（ExitWorktreeTool.clearOriginalCwd 的同款效果）
        SessionCwdHolder.clearOriginalCwd(SESSION);
        assertThat(assembledClaudeMd())
            .as("退出 worktree 后仍恒读稳定会话项目根")
            .isEqualTo("BOUND_MARKER");
    }

    @Test
    @DisplayName("兜底腿恒不随 bash cd 漂移（⛔ 不得用 getCwd 层）")
    void fallbackAnchor_doesNotFollowCdOverrideLayer() {
        // WHY：workspaceDir / 项目根的语义是「会话绑定项目身份」，bash cd 是**文件操作**语义
        // （CC getProjectRoot() javadoc 逐字：'Use for project identity … not file operations'）。
        // cd 一挪，读到的 CLAUDE.md 与 auto-memory 项目目录都会跟着换项目 —— 正是本类要挡的。
        // 夹具有效性由 fixture_actuallySeparatesTheThreeAnchors 保证（cd 槽确有值且与稳定锚不同）。
        String md = assembledClaudeMd();
        assertThat(md)
            .as("兜底腿必须读稳定会话项目根（改成 getCwd ⇒ 读到 CD_MARKER ⇒ 红）")
            .isEqualTo("BOUND_MARKER");
        assertThat(md)
            .as("⛔ 不得锚到 bash cd 覆盖层")
            .isNotEqualTo("CD_MARKER");
    }

    @Test
    @DisplayName("正向对照：workspaceDir 非 null 时读主腿（证明上面的断言真的在看该三元）")
    void primaryLeg_winsWhenWorkspaceDirPresent() throws Exception {
        // WHY（规则十二 · 反恒绿）：若上面的断言装置其实没在看 UserContextProvider（如被缓存、
        // 被别的来源覆盖），本用例会读到 BOUND_MARKER 而非 PRIMARY_MARKER ⇒ 红。
        // 主腿用**第四个**目录：与兜底腿期望值不同，才具备判别力。
        Files.writeString(primaryDir.resolve("CLAUDE.md"), "PRIMARY_MARKER");
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        ctx.sessionState().setWorkspaceDir(primaryDir);
        AgentState state = new AgentState(null, SESSION, null);
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), List.of(), minimalTuc(), QuerySource.REPL_MAIN_THREAD, "test-model",
            null, null, null, null, null, depsOf(ctx), ProviderConfig.empty());
        String md = LlmAgentLoop.collectRunMaterial(ctx, params, state).userContext().get("claudeMd");

        assertThat(md)
            .as("主腿存在 ⇒ 直接用 workspaceDir 指向的第四个目录（本类断言确实作用于该三元，非恒绿）")
            .isEqualTo("PRIMARY_MARKER");
    }
}
