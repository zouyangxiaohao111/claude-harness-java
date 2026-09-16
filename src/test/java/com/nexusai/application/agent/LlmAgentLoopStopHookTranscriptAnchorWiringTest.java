package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [批 P16 · 接线层守护] {@code LlmAgentLoop} 两处 Stop/SubagentStop hook 评估点构造的
 * {@code agent_transcript_path} 必须由 <b>稳定会话锚</b>
 * （{@link SessionStorage#getAgentTranscriptPathForSession(String, String)}）派生，
 * ⛔ 不得取自 {@code ctx.sessionState().workspaceDir()} 这个<b>上下文槽</b>。
 *
 * <h2>WHY（规则九 · 测试验证意图；为什么必须另立一类）</h2>
 * 批 P13 把两处读侧取值由 {@code SessionStorage.getAgentTranscriptPath(ctx.sessionState().workspaceDir(), …)}
 * 收口到 {@code getAgentTranscriptPathForSession(state.sessionId(), agentId)}（{@code LlmAgentLoop:8182-8185}
 * in-loop 与 {@code :8634-8635} §14）。同批 {@code SessionStorageTranscriptRootParityTest} 的
 * 守护①②③④ <b>全部断言在 {@code SessionStorage} seam 层</b>；既有的
 * {@code SubagentStopSingleFireTest} 只在<b>手工构造的 {@link HookEvent}</b> 上断言载荷形状
 * （不经过 {@code LlmAgentLoop}）。
 *
 * <p>实证（主 agent 验收 P13 时的变异 D）：把 {@code LlmAgentLoop:8632} 附近读侧改回
 * {@code getAgentTranscriptPath(ctx.sessionState().workspaceDir(), …)}，该批 <b>38 run 全绿</b> ⇒
 * 「生产调用方是否真接了新 seam」<b>没有任何一条断言守着</b>。本类补这一个洞：
 * <b>唯一入口 = {@code LlmAgentLoop.queryLoop(...)}</b>，观测面 = 传给
 * {@link HookRegistry#executeStopHooksCollecting} 的 {@link HookEvent} 载荷
 * （{@code transcriptPath()}，即 CC 的 {@code agent_transcript_path}）。
 *
 * <h2>本类覆盖两处调用点（各有独立用例 · ⛔ 缺一不可）</h2>
 * <ul>
 *   <li><b>in-loop（{@code :8182-8185}）</b>：模型产出纯文本（无 tool_calls）⇒ 进入
 *       {@code !needsFollowUp} 分支的 stop hook 评估段。</li>
 *   <li><b>§14（{@code :8634-8635}）</b>：turn 中取消（{@code state.cancel()}）⇒ 走 abort
 *       break，{@code stopHooksEvaluated} 仍为 false ⇒ 循环退出后由 §14 评估。
 *       <b>必须单独覆盖</b>：两处是<b>同一方法的两个独立表达式</b>，只改其一不会被另一处的用例发现。</li>
 * </ul>
 *
 * <h2>RED 条件（反向实验配方 · 实测见批 P16 报告）</h2>
 * 把 {@code LlmAgentLoop:8182-8185}（in-loop）改回
 * {@code SessionStorage.getAgentTranscriptPath(ctx.sessionState().workspaceDir(), state.sessionId(), stopMainAgentId)}
 * ⇒ 本类 in-loop 用例红；把 {@code :8634-8635}（§14）作同样改动 ⇒ §14 用例红。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>必须让「上下文槽」与「稳定会话锚」取不同值</b>：
 *       {@code ctx.sessionState().setWorkspaceDir(worktree)} +
 *       {@code SessionProjectRoot.setForSession(sessionId, boundProject)}。
 *       这是本类<b>唯一</b>的鉴别力来源 —— 若两槽同值，「改回 workspaceDir 槽」与「新 seam」
 *       产出同一个字符串，本类<b>不可能</b>变红（这正是 P13 变异 D 全绿的原因）。
 *       <p>生产形态对照：{@code ctx.sessionState().workspaceDir} 是<b>上下文建立时</b>落下的槽
 *       （{@code AgentLoopContextFactory.freshSession} / {@code LlmAgentLoop.buildSessionStateFromInstance}），
 *       而 {@code sessionProjectRoot(sessionId)} 是<b>按会话现算</b>的稳定锚 —— 二者不同源，
 *       本夹具把这一差别放大到可观测（两个不同目录）。</li>
 *   <li><b>必须显式 {@code SessionProjectRoot.setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对<b>任意</b> sessionId 答
 *       {@code sessionlessEnvironment()}（见 {@link SessionProjectRootTestSupport}），
 *       不显式登记则稳定锚会落到「无会话命名出口」（进程 {@code user.dir}）。</li>
 *   <li><b>{@code state.agentId()} 必须非 null</b>（= 子代理/agent 循环形态）：两处调用点都以
 *       {@code agentId != null} 为前置，否则 {@code transcriptPath} 为 null、载荷里没有本字段，
 *       断言会变成「恒 null 就绿」的假装置。</li>
 * </ul>
 */
@DisplayName("[批 P16] LlmAgentLoop 接线：stop hook 载荷 agent_transcript_path 派生自稳定会话锚")
class LlmAgentLoopStopHookTranscriptAnchorWiringTest {

    private static final String SESSION = "sess-p16-stop-hook";

    @TempDir
    Path tempDir;

    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void cleanup() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用例 1 · in-loop 调用点（LlmAgentLoop:8182-8185）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("in-loop stop hook 载荷：agent_transcript_path = 稳定会话锚派生（⛔ 不是 ctx.workspaceDir 槽）")
    void inLoopStopHook_transcriptPath_derivesFromStableSessionAnchor() throws Exception {
        Path proj = Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
        Path wt = Files.createDirectories(tempDir.resolve("proj-worktree")).toRealPath();
        Arm arm = armLoop(proj, wt, /*cancelDuringStream=*/ false);

        LlmAgentLoop.queryLoop(arm.callerParams, arm.state, new ArrayList<>());

        assertThat(arm.captured)
            .as("stop hook 必须被评估恰好一次（否则下面的断言是空跑）")
            .hasSize(1);
        HookEvent ev = arm.captured.get(0);
        assertThat(ev.transcriptPath())
            .as("in-loop 调用点（LlmAgentLoop:8182-8185）必须由 getAgentTranscriptPathForSession"
                + "（稳定会话锚）派生。反向实验：改回 getAgentTranscriptPath(ctx.sessionState()"
                + ".workspaceDir(), …) ⇒ 落点变成 worktree slug ⇒ 红")
            .isEqualTo(expectedStablePath());
        assertThat(ev.transcriptPath())
            .as("⛔ 不得取自 ctx.sessionState().workspaceDir() 槽（与稳定锚不同源的另一个槽）")
            .isNotEqualTo(contextSlotPath(wt));
        assertAbsoluteAnchor(ev, proj);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用例 2 · §14 调用点（LlmAgentLoop:8634-8635）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§14 stop hook 载荷：agent_transcript_path = 稳定会话锚派生（⛔ 不是 ctx.workspaceDir 槽）")
    void section14StopHook_transcriptPath_derivesFromStableSessionAnchor() throws Exception {
        Path proj = Files.createDirectories(tempDir.resolve("proj-bound-14")).toRealPath();
        Path wt = Files.createDirectories(tempDir.resolve("proj-worktree-14")).toRealPath();
        // 流中取消 ⇒ abort break（:6762-6764）⇒ stopHooksEvaluated 仍为 false ⇒ 由 §14 评估
        Arm arm = armLoop(proj, wt, /*cancelDuringStream=*/ true);

        LlmAgentLoop.queryLoop(arm.callerParams, arm.state, new ArrayList<>());

        assertThat(arm.state.exitReason())
            .as("装置上膛证明：本用例必须走 ABORTED 退出路径（§14 分支），否则覆盖不到 §14 调用点")
            .isEqualTo(AgentState.ExitReason.ABORTED);
        assertThat(arm.captured)
            .as("§14 stop hook 必须被评估（abort 退出路径下 stopHooksEvaluated 仍 false）")
            .hasSize(1);
        HookEvent ev = arm.captured.get(0);
        assertThat(ev.transcriptPath())
            .as("§14 调用点（LlmAgentLoop:8634-8635）必须由 getAgentTranscriptPathForSession"
                + "（稳定会话锚）派生。反向实验：改回 getAgentTranscriptPath(ctx.sessionState()"
                + ".workspaceDir(), …) ⇒ 落点变成 worktree slug ⇒ 红")
            .isEqualTo(expectedStablePath());
        assertThat(ev.transcriptPath())
            .as("⛔ 不得取自 ctx.sessionState().workspaceDir() 槽（与稳定锚不同源的另一个槽）")
            .isNotEqualTo(contextSlotPath(wt));
        assertAbsoluteAnchor(ev, proj);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════════

    /** 夹具载体：被驱动到 stop hook 评估的 loop 状态 + 捕获到的 HookEvent。 */
    private static final class Arm {
        AgentState state;
        com.nexusai.application.agent.loop.QueryParams callerParams;
        final List<HookEvent> captured = Collections.synchronizedList(new ArrayList<>());
    }

    /** 稳定会话锚（getAgentTranscriptPathForSession）应有的落点（agentId 同生产：UUID 字符串）。 */
    private String expectedStablePath() {
        return SessionStorage.getAgentTranscriptPathForSession(SESSION, agentId.toString()).toString();
    }

    /** 上下文槽（ctx.sessionState().workspaceDir()）派生出来的「另一个槽」落点。 */
    private String contextSlotPath(Path worktree) {
        return SessionStorage.getAgentTranscriptPath(worktree, SESSION, agentId.toString()).toString();
    }

    /** 绝对锚（结构性断言 · 零 IO）：路径必须落在<b>绑定项目根</b>的 slug 下。 */
    private static void assertAbsoluteAnchor(HookEvent ev, Path boundProject) {
        assertThat(Path.of(ev.transcriptPath()).getParent().getParent().getParent())
            .as("载荷路径必须落在【绑定项目根】的 slug 目录下（绝对锚 · 防两侧一起漂到 worktree slug）")
            .isEqualTo(SessionStorage.getProjectDir(boundProject));
    }

    /**

     * 构造并预跑（{@code collectRunMaterial}）一次 in-loop / §14 stop hook 评估。
     *
     * <p>驱动形态对齐 {@code LlmAgentLoopStopHookBlockingIsMetaTest}：真实 {@code LlmAgentLoop.queryLoop}
     * + mocked provider（单文本帧 → 无 tool_calls）+ mocked {@link HookRegistry}（捕获 HookEvent、
     * 返回「无阻塞」结果 ⇒ 循环正常收敛）。
     *
     * @param cancelDuringStream true ⇒ provider 帧内 {@code state.cancel()}（走 abort break → §14）
     */
    private Arm armLoop(Path boundProject, Path worktree, boolean cancelDuringStream) {
        SessionProjectRoot.setForSession(SESSION, boundProject.toString());
        // 前置：两槽必须取不同值（本类鉴别力的唯一来源）
        assertThat(SessionProjectRoot.getForSession(SESSION))
            .as("夹具前置：稳定会话锚 = 绑定项目根")
            .isEqualTo(boundProject.toString());

        LlmProvider provider = Mockito.mock(LlmProvider.class);
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        Arm arm = new Arm();
        arm.state = new AgentState("sys", SESSION, agentId);
        arm.state.appendMessage(new ChatMessageDto(
            "m1", SESSION, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        arm.state.setBudgetTracker(checker.createBudgetTracker());

        // provider 桩必须逐参对齐 stream 的<b>当前</b> arity（既有同域测试用 19 参抽象重载；
        //   位置索引 = 9 onChunk / 10 onMsg / 16 onComplete）。
        Mockito.doAnswer(inv -> {
            if (cancelDuringStream) {
                arm.state.cancel();
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("done");
            onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 100L));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any())).thenAnswer(inv -> {
            arm.captured.add(inv.getArgument(0));
            // 「无阻塞 / 不阻止续行」⇒ 循环正常收敛（对齐 CC executeStopHooks 空结果）
            return new HookRegistry.StopHookCollectResult(
                List.<GenericHook.HookResult>of(), 0, List.of(), List.of(), false, null, false);
        });

        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)),
            factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        // ⭐ 关键夹具：把「上下文槽」显式设为与稳定锚<b>不同的</b>目录（生产里该槽在上下文建立时落下，
        //   与会话稳定锚不同源；本夹具把这一差别放大为两个真实目录）。
        ctx.sessionState().setWorkspaceDir(worktree);
        assertThat(ctx.sessionState().workspaceDir())
            .as("夹具前置：上下文槽 = worktree（≠ 绑定根）⇒ 「改回 workspaceDir 槽」必然产出不同字符串")
            .isEqualTo(worktree);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        com.nexusai.application.agent.loop.QueryParams rawParams =
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                arm.state.rawMessages(), null,
                tucWithNotification().withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty());
        arm.callerParams = LlmAgentLoop.collectRunMaterial(ctx, rawParams, arm.state);
        return arm;
    }

    /** 32 参构造器构建 TUC（of() 工厂不含 UI 回调）· 对齐同域既有测试。 */
    private static ToolUseContext tucWithNotification() {
        return new ToolUseContext(
            UUID.randomUUID(), SESSION, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            n -> {}, null, null, null, null, null, null, null, null, null);
    }
}
