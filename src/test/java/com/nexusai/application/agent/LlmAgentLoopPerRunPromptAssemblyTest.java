package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [prompt-assembly-A] 系统提示「材料收集」per-run 化（从 per-tool-round 搬到 per user turn）验收。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>——本批把组装链的<b>材料收集</b>半段
 * （{@code fetchSystemPromptParts} → {@code buildEffectiveSystemPrompt} → coordinator userContext
 * 合并）从 {@code loop()} 的 do-while 体内（每个工具轮重算一次）搬到 do-while 之前
 * （每个 run / 每个 user turn 一次），对齐 CC：
 * <ul>
 *   <li>CC {@code query.ts:393-411} 顶层解构 {@code systemPrompt/userContext/systemContext} 并注释
 *       「Immutable params — never reassigned during the query loop」；</li>
 *   <li>CC 材料收集由<b>调用方</b>每 turn 算一次（{@code utils/queryContext.ts:44} →
 *       {@code QueryEngine.ts:302}）；</li>
 *   <li>CC {@code query.ts:1991-1999} {@code refreshTools()} 只刷 tools，<b>不重算</b> systemPrompt
 *       —— 接受提示里的工具清单 stale。</li>
 * </ul>
 * 本批若被回退（材料收集搬回循环内），下列哪条测试必须变红：
 * <ol>
 *   <li>{@link #perRunMaterialCollection_executesExactlyOnce()} —— 计数观测点（工具轮数=2 时计数必须=1）；</li>
 *   <li>{@link #blocksAreStableAcrossRoundsWithinRun()} —— 跨轮稳定性（本仓材料收集确定性输入下
 *       回退后仍绿 ⇒ 该条是「目标行为」断言，真正的判别器是第 1 条）；</li>
 *   <li>{@link #postSamplingHookReceivesRealContextNotEmptyStub()} —— 空桩修复（回退后仍绿，
 *       但空桩若被改回 {@code Map.of()} 必红）；</li>
 *   <li>{@link #threePathsBlocksByteIdenticalToGolden()} —— 三路（主线程/子代理/hook agent）
 *       系统提示产物逐位相同（金样取自改动前的 master 运行输出）。</li>
 * </ol>
 *
 * <p>测试脚手架：mock provider 第 1 次回 tool_calls、第 2 次回 stop ⇒ 单 run 两个工具轮；
 * 捕获每次 provider 调用收到的 {@code systemPromptBlocks}（{@code stream} 第 3 实参）。
 */
class LlmAgentLoopPerRunPromptAssemblyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 金样（改动前 master f312653 实测捕获）· scope=ORG / text=custom 提示。 */
    private static final List<SystemPromptBlock> GOLDEN_MAIN =
        List.of(new SystemPromptBlock("MAIN-SYS-PROMPT", CacheScope.ORG));
    private static final List<SystemPromptBlock> GOLDEN_SUBAGENT =
        List.of(new SystemPromptBlock("SUBAGENT-SYS-PROMPT", CacheScope.ORG));
    private static final List<SystemPromptBlock> GOLDEN_HOOK =
        List.of(new SystemPromptBlock("HOOK-SYS-PROMPT", CacheScope.ORG));

    @BeforeEach
    void resetObservationPoint() {
        LlmAgentLoop.resetMaterialCollectionRunsForTest();
        PostSamplingHookRegistry.clearAll();
    }

    @AfterEach
    void clearHooks() {
        PostSamplingHookRegistry.clearAll();
    }

    // ─────────────────── 验收 1：run 内材料收集只执行一次 ───────────────────

    @Test
    @DisplayName("[验收1] run 内材料收集只执行一次：2 个工具轮 → 计数仍为 1（回退到循环内 ⇒ 2，必红）")
    void perRunMaterialCollection_executesExactlyOnce() {
        Run run = drive("MAIN", "MAIN-SYS-PROMPT", QuerySource.USER, "sys-new", null);

        assertThat(run.modelCalls.get())
            .as("脚手架自检：必须真的跑了 2 个工具轮（否则计数断言无意义）")
            .isEqualTo(2);
        assertThat(LlmAgentLoop.materialCollectionRunsForTest())
            .as("[验收1] 材料收集（fetchSystemPromptParts/buildEffectiveSystemPrompt/coordinator 合并）"
                + "必须在 do-while 外每 run 只执行一次 · CC query.ts:393-411「Immutable params」"
                + "+ QueryEngine.ts:302 调用方每 turn 一次；搬回 do-while 内 ⇒ 2 个工具轮 = 2 次，本断言必红")
            .isEqualTo(1);
    }

    // ─────────────────── 验收 4：提示跨轮稳定 ───────────────────

    @Test
    @DisplayName("[验收4] 同一 run 内第 N 轮 systemPromptBlocks == 第 1 轮（提示不再随工具轮重算）")
    void blocksAreStableAcrossRoundsWithinRun() {
        Run run = drive("MAIN", "MAIN-SYS-PROMPT", QuerySource.USER, "sys-stable", null);

        assertThat(run.blocksPerCall).as("至少两个工具轮才有跨轮可比").hasSize(2);
        assertThat(run.blocksPerCall.get(0))
            .as("首轮 vs 次轮 systemPromptBlocks 必须逐位相同（材料收集 per-run ⇒ 循环内输入不可变）")
            .isEqualTo(run.blocksPerCall.get(1));
        assertThat(run.blocksPerCall.get(1)).isEqualTo(GOLDEN_MAIN);
    }

    // ─────────────────── 验收 3：三路行为不变（金样比对） ───────────────────

    @Test
    @DisplayName("[验收3] 三路（主线程/子代理/hook agent）系统提示产物与 master 金样逐位相同")
    void threePathsBlocksByteIdenticalToGolden() {
        Run main = drive("MAIN", "MAIN-SYS-PROMPT", QuerySource.USER, "sys-main", null);
        Run sub = drive("SUBAGENT", "SUBAGENT-SYS-PROMPT", QuerySource.SUBAGENT, "sys-sub", null);
        Run hook = drive("HOOK", "HOOK-SYS-PROMPT", QuerySource.HOOK_AGENT, "sys-hook", null);

        assertThat(main.blocksPerCall).as("主线程：[{ORG, MAIN-SYS-PROMPT}] × 2 轮（金样来自改动前 master）")
            .hasSize(2).allSatisfy(b -> assertThat(b).isEqualTo(GOLDEN_MAIN));
        assertThat(sub.blocksPerCall).as("子代理：[{ORG, SUBAGENT-SYS-PROMPT}] × 2 轮")
            .hasSize(2).allSatisfy(b -> assertThat(b).isEqualTo(GOLDEN_SUBAGENT));
        assertThat(hook.blocksPerCall).as("hook agent：[{ORG, HOOK-SYS-PROMPT}] × 2 轮")
            .hasSize(2).allSatisfy(b -> assertThat(b).isEqualTo(GOLDEN_HOOK));

        assertThat(main.modelCalls.get()).isEqualTo(2);
        assertThat(sub.modelCalls.get()).isEqualTo(2);
        assertThat(hook.modelCalls.get()).isEqualTo(2);
    }

    // ─────────────────── 验收 2：空桩消失（PostSampling hook 拿到真上下文） ───────────────────

    @Test
    @DisplayName("[验收2] PostSampling hook 拿到的 userContext/systemContext 非空且键与材料收集产物一致")
    void postSamplingHookReceivesRealContextNotEmptyStub() throws Exception {
        // custom=null（默认组装路径，非 I-13 短路）⇒ systemContext 走 getSystemContext，
        // 注入可控 GitStatusProvider ⇒ 确定性非空。
        GitStatusProvider gp = Mockito.mock(GitStatusProvider.class);
        when(gp.getGitStatus()).thenReturn("GIT-STATUS-GOLDEN");

        CountDownLatch latch = new CountDownLatch(1);
        List<PostSamplingContext> seen = new ArrayList<>();
        PostSamplingHookRegistry.register(ctx -> {
            synchronized (seen) {
                seen.add(ctx);
            }
            latch.countDown();
        });

        Run run = drive("MAIN", null, QuerySource.USER, "sys-ctx", gp);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("post-sampling hook 必须被调用").isTrue();

        PostSamplingContext ps;
        synchronized (seen) {
            assertThat(seen).isNotEmpty();
            ps = seen.get(0);
        }
        // 旧实现：QueryParams.forLoop 硬编码 Map.of() → hook 恒拿空上下文（空桩）。
        assertThat(ps.userContext())
            .as("[验收2] userContext 必须非空（旧实现恒 Map.of() 空桩）——CC query.ts:183 getUserContext 产物")
            .isNotEmpty();
        assertThat(ps.userContext()).containsKey("currentDate");
        assertThat(ps.systemContext())
            .as("[验收2] systemContext 必须非空（旧实现恒 Map.of() 空桩）——CC query.ts:184 getSystemContext 产物"
                + "（custom=null 时不短路）；注入的 gitStatus 必须原样到达 hook")
            .containsEntry("gitStatus", "GIT-STATUS-GOLDEN");
        // systemContext 必须真被 appendSystemContext 并入系统提示尾段（键与 sysParts 一致）
        assertThat(ps.systemPrompt())
            .as("systemContext 的 `key: value` 行必须并入系统提示段数组（CC api.ts:437-447）")
            .anySatisfy(seg -> assertThat(seg).contains("gitStatus: GIT-STATUS-GOLDEN"));
        assertThat(run.modelCalls.get()).isEqualTo(2);
    }

    // ─────────────────── 守卫：调用方已组装 ⇒ 跳过材料收集（fork 收敛通道） ───────────────

    @Test
    @DisplayName("[守卫] params.systemPrompt 非空 ⇒ 跳过材料收集（计数 0）且 blocks = 调用方传入值")
    void callerSuppliedSystemPrompt_skipsMaterialCollection() {
        List<List<SystemPromptBlock>> captured = new ArrayList<>();
        LlmProviderFactory factory = newProviderFactory(captured, new AtomicInteger());
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        String sessionId = "sess-caller-supplied";
        AgentState state = new AgentState("THIS-CUSTOM-MUST-NOT-WIN", sessionId, null);
        state.appendMessage(userMessage("m1", "question"));

        com.nexusai.application.agent.loop.QueryParams params =
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), List.of("CALLER-ASSEMBLED-PROMPT"), tuc(sessionId, PermissionMode.DEFAULT),
                QuerySource.USER, "test-model", null, null, null, null, null,
                depsOf(ctx, true, "fixed-chain"), ProviderConfig.empty())
                .withUserContext(Map.of("callerKey", "callerValue"))
                .withSystemContext(Map.of("callerSysKey", "callerSysValue"));

        assertThat(params.systemPrompt()).isEqualTo(List.of("CALLER-ASSEMBLED-PROMPT"));
        assertThat(params.userContext()).containsEntry("callerKey", "callerValue");
        assertThat(params.systemContext()).containsEntry("callerSysKey", "callerSysValue");

        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        assertThat(LlmAgentLoop.materialCollectionRunsForTest())
            .as("[守卫] 调用方已组装（params.systemPrompt 非空）⇒ 材料收集**一次都不跑**"
                + "（CC query({systemPrompt}) 语义；fork 收敛预留通道）")
            .isZero();
        assertThat(captured)
            .as("发送 blocks 必须来自调用方传入的 systemPrompt（+ 其 systemContext 并尾段），不得被 loop 覆盖")
            .allSatisfy(b -> assertThat(b).isEqualTo(List.of(
                // appendSystemContext 产 2 段（[系统提示, "callerSysKey: callerSysValue"]），
                // splitSysPromptPrefix 以空行拼接为单 block（CC buildSystemPromptBlocks 同款）。
                new SystemPromptBlock("CALLER-ASSEMBLED-PROMPT\n\ncallerSysKey: callerSysValue", CacheScope.ORG))));
    }

    // ═══════════════════════════ 脚手架 ═══════════════════════════

    /** 单次 run 的观测结果。 */
    private static final class Run {
        final AtomicInteger modelCalls;
        final List<List<SystemPromptBlock>> blocksPerCall;

        Run(AtomicInteger modelCalls, List<List<SystemPromptBlock>> blocksPerCall) {
            this.modelCalls = modelCalls;
            this.blocksPerCall = blocksPerCall;
        }
    }

    private Run drive(String kind, String custom, QuerySource qs, String sessionId, GitStatusProvider gp) {
        List<List<SystemPromptBlock>> captured = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger();
        LlmProviderFactory factory = newProviderFactory(captured, callCount);

        AgentState state = new AgentState(custom, sessionId, null);
        state.appendMessage(userMessage("m1", "question"));

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        if (gp != null) {
            ctx.sessionState().setGitStatusProvider(gp);
        }
        PermissionMode mode = "HOOK".equals(kind) ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT;

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), List.of(), tuc(sessionId, mode), qs, "test-model",
                null, null, null, null, null, depsOf(ctx, "MAIN".equals(kind), "fixed-chain"),
                ProviderConfig.empty()),
            state, new ArrayList<>());

        return new Run(callCount, captured);
    }

    private static LoopDeps depsOf(AgentLoopContext ctx, boolean mainLoop, String chainId) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return mainLoop; }
            @Override public String resolveModel() { return "test-model"; }
            @Override public String uuid() { return chainId; }
        };
    }

    /** hook agent 走 toolExecContext 的 DONT_ASK 专属分支 → 必须带 DONT_ASK permCtx。 */
    private static ToolUseContext tuc(String sessionId, PermissionMode mode) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, mode,
                List.of(TestContexts.dummyTool("Bash")))
            .withPermissionContext(ToolPermissionContext.strict(mode), mode);
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /**
     * mock provider 工厂：第 1 次回 tool_calls（触发第 2 个工具轮）、第 2 次回 stop。
     * 两种 stream 重载（17 参 / 18 参带 thinkingConfig）都必须打桩——hook agent 走后者。
     */
    @SuppressWarnings("unchecked")
    private static LlmProviderFactory newProviderFactory(List<List<SystemPromptBlock>> captured,
                                                        AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        org.mockito.stubbing.Answer<Object> answer = inv -> {
            Object[] args = inv.getArguments();
            List<SystemPromptBlock> blocks = (List<SystemPromptBlock>) args[2];
            captured.add(blocks == null ? null : List.copyOf(blocks));
            int msgIdx = args.length == 18 ? 11 : 10;
            int doneIdx = args.length == 18 ? 17 : 16;
            java.util.function.Consumer<AssistantMessage> onMsg = args[msgIdx] == null ? null
                : (java.util.function.Consumer<AssistantMessage>) args[msgIdx];
            Runnable onComplete = (Runnable) args[doneIdx];
            if (callCount.incrementAndGet() == 1) {
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash", input)), "", null, 10L));
            } else {
                onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 10L));
            }
            onComplete.run();
            return null;
        };
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
