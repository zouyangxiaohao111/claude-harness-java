package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.context.MemoryFileInfo;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.prompt.SessionPromptCacheRegistry;
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
import java.util.ArrayList;
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
 * <h2>⭐ [批 D3 2026-09-16 重做] 观测通道换成「引擎存在态」—— 为什么必须换</h2>
 * 本类原经 <b>{@code claudemdEngine == null}（降级态）</b> 观测该三元：降级态下
 * {@code UserContextProvider.claudeMd()} 直接读 {@code projectRoot/CLAUDE.md} ⇒ 该字段的值
 * 「看得见」。<b>但那是错的观测通道</b>，两条实测理由：
 * <ol>
 *   <li><b>用途在降级态根本不存在</b>：该字段服务于「引擎存在时当 <b>AutoMem/TeamMem 基址</b>」
 *       （{@code UserContextProvider:230} 的 {@code if (claudemdEngine != null)} 分支内的
 *       {@code getMemoryFiles(false, sessionId, projectRoot)}）。{@code engine == null} 时
 *       AutoMem/TeamMem 这条链根本不存在 ⇒ 该字段在那个通道上<b>只剩「扫描根」一个用途</b>
 *       ⇒ 用降级态 read 去观测「AutoMem 基址的锚」是<b>张冠李戴</b>。</li>
 *   <li><b>批 D3 已把降级态扫描根改为不看该字段</b>：降级态扫描根改按调用现算
 *       {@code CwdResolution.getOriginalCwdLayer}（= CC {@code claudemd.ts:850}
 *       {@code getOriginalCwd()}，<b>随 worktree 变</b>，见
 *       {@code UserContextProvider.degradedClaudeMdScanRoot()}）⇒ 降级态下该字段<b>观测上成为死值</b>，
 *       连「主腿胜出」的正向对照臂都读不到 {@code workspaceDir}（原实现下三条断言全红）。</li>
 * </ol>
 * ⇒ <b>本类改为观测引擎侧实参</b>：用 {@link CapturingEngine} 捕获
 * {@code getMemoryFiles(forceIncludeExternal, sessionId, sessionProjectRoot)} 的
 * <b>第 3 实参</b>—— 那正是「AutoMem/TeamMem 基址」本体，也就是 P23 要守的东西。
 * ⚠️ <b>守护意图不变</b>（仍抓「锚改回 {@code getOriginalCwdLayer}」/「改成 {@code getCwd}」），
 * 变的只是观测通道。⛔ 本类<b>不再</b>声称它守的是「降级态读哪个文件」（那是批 D3 的
 * {@code UserContextProviderDegradedScanRootTest} 的领地）。
 *
 * <h2>RED 条件（反向实验配方 · 改后实测有效）</h2>
 * <ul>
 *   <li>把 {@code LlmAgentLoop} 该处锚改回 {@code CwdResolution.getOriginalCwdLayer(...)} ⇒
 *       {@link #fallbackLeg_anchorsStableSessionProjectRoot()} 红（捕获到 worktree 目录）；</li>
 *   <li>改成 {@code CwdResolution.getCwd(...)} ⇒ 同一条红（捕获到 cd 目录）。</li>
 * </ul>
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>四个候选目录各不同值</b>（稳定锚 / 重锚槽 / cd 槽 / 主腿）—— 断言「捕获到哪一个」，
 *       而不是断言路径字符串的写法；四值相同则任何实现都绿。</li>
 *   <li><b>必须显式 {@code setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对任意 sessionId 答 sessionless
 *       （见 {@code SessionProjectRootTestSupport}），不显式登记稳定锚会落到进程 user.dir。</li>
 *   <li><b>必须注入引擎</b>（{@link CapturingEngine}）：降级态观测不到 {@code sessionProjectRoot}
 *       ⇒ 断言恒绿（这正是本类换通道的原因）。</li>
 * </ul>
 */
@DisplayName("[批 P23 · D3 重做观测通道] collectRunMaterial 的 UserContextProvider 兜底腿锚 = 稳定会话项目根")
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

    /** 每个用例独立的捕获器（⛔ 不用 static 共享，避免用例间串读）。 */
    private static CapturingEngine ENGINE;

    @BeforeEach
    void setUp() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        BundledSkillEnabledGates.bridgeSettingsMapper(null);

        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        SessionCwdHolder.set(SESSION, cdDir.toString());
        ENGINE = new CapturingEngine();
        // [步骤 2] 会话级 prompt 缓存 store 是<b>进程级静态表</b>（键 = sessionId）。本类四个用例
        //   共用固定 {@link #SESSION}，而每个用例都换一套 @TempDir 夹具并换新 {@link #ENGINE}；
        //   不归零则第 2 个用例起会复用前一个用例建的会话级 UserContextProvider ⇒
        //   {@code getMemoryFiles} 不再被调用 ⇒ 捕获为空 ⇒ 假红。
        //   ⚠️ 这不是「测试特权」：生产同等语义由 CC 决定 —— getUserContext 是进程级 memoize，
        //   会话中途 {@code EnterWorktreeTool} 重锚**不会**让它失效（见 P23/D3 分析与
        //   UserContextProvider 的 projectRoot javadoc）⇒ 本锚只在「会话首个 run」消费。
        //   同 PostCompactCleanup.resetForTest / SkillListingSentRegistry.reset 的既有隔离口径。
        SessionPromptCacheRegistry.resetForTest();
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
     * 捕获型引擎：只记 {@code getMemoryFiles} 的<b>第 3 实参</b>（{@code sessionProjectRoot}
     * = AutoMem/TeamMem 基址），不触真实 memory 链。
     *
     * <p>返回值取空列表 ⇒ 下游 {@code filterInjectedMemoryFiles}/{@code getClaudeMds} 得到空内容，
     * {@code claudeMd()} 走「无内容 → null」；⛔ 这不影响本类的断言 —— 断言的是<b>被捕获的实参</b>，
     * 它在任何下游失败之前就已记录（故本类不依赖 memory 链可用）。
     */
    private static final class CapturingEngine extends ClaudemdEngine {
        private final List<String> captured = new ArrayList<>();

        CapturingEngine() {
            super(null, null);
        }

        @Override
        public List<MemoryFileInfo> getMemoryFiles(boolean forceIncludeExternal, String sessionId,
                                                   String sessionProjectRoot) {
            captured.add(sessionProjectRoot);
            return List.of();
        }

        /** 最后一次捕获到的 {@code sessionProjectRoot}（null = 从未被调用 ⇒ 观测装置失效）。 */
        String lastSessionProjectRoot() {
            return captured.isEmpty() ? null : captured.get(captured.size() - 1);
        }
    }

    /**
     * 组装 {@code collectRunMaterial} 的 userContext.claudeMd 并返回捕获到的 AutoMem/TeamMem 基址。
     *
     * <p>夹具三件套：① 注入 {@link CapturingEngine}（引擎存在态 ⇒ 该字段走 AutoMem/TeamMem 基址通道）；
     * ② {@code state.sessionId()} 非 null；③ 四个候选目录互不相同。
     *
     * @param workspaceDir 主腿值（null ⇒ 强制走兜底腿）
     */
    private static String captureSessionProjectRoot(Path workspaceDir) {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null, null, null,
            ENGINE);
        ctx.sessionState().setWorkspaceDir(workspaceDir);
        AgentState state = new AgentState(null, SESSION, null);
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), List.of(), minimalTuc(), QuerySource.REPL_MAIN_THREAD, "test-model",
            null, null, null, null, null, depsOf(ctx), ProviderConfig.empty());
        LlmAgentLoop.collectRunMaterial(ctx, params, state);
        return ENGINE.lastSessionProjectRoot();
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
    @DisplayName("夹具有效性自检：三槽互异且观测装置确实在用（否则本类恒绿）")
    void fixture_actuallySeparatesTheThreeAnchors() {
        // WHY（规则十二 · 反恒绿）：本类全部鉴别力都来自「三个槽指向三个不同目录 + 观测装置生效」。
        // 若夹具写错（如 cd 槽未设），getCwd 变异体就会与正确实现同值 ⇒ 断言恒绿。
        assertThat(CwdResolution.getCwd(SESSION))
            .as("cd 槽必须真的生效（否则 getCwd 变异体与正确实现同值 ⇒ 恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(cdDir.toString()));
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("重锚槽必须真的生效（否则 getOriginalCwdLayer 变异体与正确实现同值 ⇒ 恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(worktree.toString()));
        assertThat(CwdResolution.getProjectRoot(SESSION))
            .as("稳定锚必须真的生效（显式 setForSession）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));

        // ⭐ 观测装置自检：引擎存在态下捕获器必须真的被调用（否则「捕获 == null」会让断言全绿/全红）
        String captured = captureSessionProjectRoot(null);
        assertThat(captured)
            .as("⭐ 观测装置必须生效：claudemdEngine 非 null ⇒ getMemoryFiles 必被调用并捕获 sessionProjectRoot"
                + "（若为 null ⇒ 本类观测通道失效，必须停下报告）")
            .isNotNull();
    }

    @Test
    @DisplayName("进/出 worktree 前后，兜底腿恒把稳定会话项目根传作 AutoMem/TeamMem 基址（⛔ 不随重锚）")
    void fallbackLeg_anchorsStableSessionProjectRoot() {
        assertThat(captureSessionProjectRoot(null))
            .as("兜底腿必须把稳定会话项目根传进 getMemoryFiles 的第 3 实参"
                + "（改回 getOriginalCwdLayer ⇒ 捕获 worktree 目录 ⇒ 红）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));

        // 进 worktree（EnterWorktreeTool.applySessionCwd 的同款效果）
        SessionCwdHolder.setOriginalCwd(SESSION, worktree.toString());
        assertThat(captureSessionProjectRoot(null))
            .as("重锚后仍恒传稳定会话项目根（F1/gh-30217 同型防线）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
        assertThat(captureSessionProjectRoot(null))
            .as("⛔ 不得锚到 worktree 目录（重锚槽）")
            .isNotEqualTo(CwdResolution.normalizeCwd(worktree.toString()));

        // 出 worktree（ExitWorktreeTool.clearOriginalCwd 的同款效果）
        SessionCwdHolder.clearOriginalCwd(SESSION);
        assertThat(captureSessionProjectRoot(null))
            .as("退出 worktree 后仍恒传稳定会话项目根")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
    }

    @Test
    @DisplayName("兜底腿恒不随 bash cd 漂移（⛔ 不得用 getCwd 层）")
    void fallbackLeg_doesNotFollowCdOverrideLayer() {
        // WHY：workspaceDir / 项目根语义是「会话绑定项目身份」，bash cd 是**文件操作**语义
        // （CC getProjectRoot() javadoc 逐字：'Use for project identity … not file operations'）。
        // cd 一挪，AutoMem/TeamMem 项目目录就会跟着换项目 —— 正是本类要挡的。
        // 夹具有效性由 fixture_actuallySeparatesTheThreeAnchors 保证（cd 槽确有值且与稳定锚不同）。
        String captured = captureSessionProjectRoot(null);
        assertThat(captured)
            .as("兜底腿必须传稳定会话项目根（改成 getCwd ⇒ 捕获 cd 目录 ⇒ 红）")
            .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
        assertThat(captured)
            .as("⛔ 不得锚到 bash cd 覆盖层")
            .isNotEqualTo(CwdResolution.normalizeCwd(cdDir.toString()));
    }

    @Test
    @DisplayName("正向对照：workspaceDir 非 null 时传主腿（证明断言真的在看该三元）")
    void primaryLeg_winsWhenWorkspaceDirPresent() throws Exception {
        // WHY（规则十二 · 反恒绿）：若断言装置其实没在看该三元（如被缓存、被别的来源覆盖），
        // 本用例会捕获到稳定锚而非主腿目录 ⇒ 红。
        // 主腿用**第四个**目录：与兜底腿期望值不同，才具备判别力。
        String captured = captureSessionProjectRoot(primaryDir);
        assertThat(captured)
            .as("主腿存在 ⇒ 直接传 workspaceDir 指向的第四个目录（本类断言确实作用于该三元，非恒绿）")
            .isEqualTo(CwdResolution.normalizeCwd(primaryDir.toString()));
    }
}
