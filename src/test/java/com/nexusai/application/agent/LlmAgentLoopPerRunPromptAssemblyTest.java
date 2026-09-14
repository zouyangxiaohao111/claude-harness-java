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
 * 若材料收集的落点/形态被改坏，下列测试须变红：
 * <ol>
 *   <li>{@link #blocksAreStableAcrossRoundsWithinRun()} —— 跨轮稳定性（材料收集确定性输入下
 *       该条是「目标行为」断言）；</li>
 *   <li>{@link #postSamplingHookReceivesRealContextNotEmptyStub()} —— 空桩修复（空桩若被改回
 *       {@code Map.of()} 必红）；</li>
 *   <li>{@link #threePathsBlocksByteIdenticalToGolden()} —— 三路（主线程/子代理/hook agent）
 *       系统提示产物逐位相同（金样取自改动前的 master 运行输出）；</li>
 *   <li>{@link #callerSuppliedSystemPrompt_skipsMaterialCollection()} —— 调用方已组装
 *       （{@code params.systemPrompt()} 非空）时 loop **不得覆盖**：发送 blocks 必须来自调用方传入值
 *       （[prompt-assembly-B] 本用例现在正是「loop() 收到已折好的提示，自己不重折」的直接锁 ——
 *       它**故意不**调 collectRunMaterial）。</li>
 * </ol>
 *
 * <p><b>[小档 2026-09-12] 「每 run 只收集一次」不再设计数观测点</b>：该性质当前只是「只有一个调用点」
 * 的偶然事实，**不是结构性成立的**；为它往生产代码里加 {@code AtomicInteger} + 两个测试访问器属
 * 「生产承载测试专用埋点」，已删除。结构性保证由 E-1b（材料收集上移到 4 个调用方）提供。
 * 相应地本类不再断言该性质 —— 它对可观测产物不敏感，无法用产物断言替代，硬造观测点得不偿失。
 *
 * <p><b>[prompt-assembly-B · E-1b-1] 材料收集已上移到调用方</b>：{@code loop()} 不再自行收集
 * （旧守卫「params.systemPrompt 非空 ⇒ 跳过收集」随之上移为唯一形态 —— 调用方传什么就发什么）。
 * 本类作为 {@code queryLoop} 的**调用方**，在驱动前按生产契约调
 * {@code LlmAgentLoop.collectRunMaterial(ctx, params, state)} 回灌三通道（三个生产调用方
 * {@code doRun} / {@code SubagentExecutor} / {@code ExecAgentHook} 同款位置）——
 * 这也是「三路金样逐位相同」得以继续成立的前提：材料收集的产物与落点均未变，只是 owner 从
 * {@code loop()} 换成调用方。
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
    void resetHooks() {
        PostSamplingHookRegistry.clearAll();
    }

    @AfterEach
    void clearHooks() {
        PostSamplingHookRegistry.clearAll();
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
        // [E-1a pre-append 契约] PostSamplingContext.systemPrompt = 组装段数组（**未经**
        //   appendSystemContext），systemContext 独立随第 4 参透传（上方已断言）；
        //   append 在使用点（fork 发送边界 ProductionForkedQuery / hook 查询 ApiQueryHookHelper）。
        assertThat(ps.systemPrompt())
            .as("[E-1a] systemPrompt 必须是 pre-append 形态（不得预先并入 systemContext 段）")
            .noneSatisfy(seg -> assertThat(seg).contains("gitStatus: GIT-STATUS-GOLDEN"));
        assertThat(com.nexusai.application.agent.prompt.SystemPromptContextProvider.appendSystemContext(
                com.nexusai.application.agent.prompt.SystemPrompt.from(ps.systemPrompt()),
                ps.systemContext()))
            .as("使用点 append 后 systemContext 的 `key: value` 行必须并入系统提示段数组（CC api.ts:437-447）")
            .anySatisfy(seg -> assertThat(seg).contains("gitStatus: GIT-STATUS-GOLDEN"));
        assertThat(run.modelCalls.get()).isEqualTo(2);
    }

    // ─────────────────── 守卫：调用方已组装 ⇒ 跳过材料收集（fork 收敛通道） ───────────────

    @Test
    @DisplayName("[守卫] params.systemPrompt 非空 ⇒ 跳过材料收集，发送 blocks = 调用方传入值（loop 不得覆盖）")
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

        // [prompt-assembly-B] 本用例**故意不收集**：验证 loop() 收到调用方已折好的提示后不重折/不覆盖
        //   （CC query.ts:393-411「Immutable params — never reassigned during the query loop」）。
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        assertThat(captured)
            .as("发送 blocks 必须来自调用方传入的 systemPrompt（+ 其 systemContext 并尾段），不得被 loop 覆盖")
            .allSatisfy(b -> assertThat(b).isEqualTo(List.of(
                // appendSystemContext 产 2 段（[系统提示, "callerSysKey: callerSysValue"]），
                // splitSysPromptPrefix 以空行拼接为单 block（CC buildSystemPromptBlocks 同款）。
                new SystemPromptBlock("CALLER-ASSEMBLED-PROMPT\n\ncallerSysKey: callerSysValue", CacheScope.ORG))));
    }

    // ─────────── 调用方边界：主线程 doRun（生产调用方）已收集 ⇒ provider 边界可见 ───────────

    /**
     * [prompt-assembly-B] 生产调用方 {@code LlmAgentLoop.doRun}（主线程）必须**自己**收集材料：
     * 断言落在 <b>provider 边界</b>（{@code provider.stream(...)} 收到的 blocks / history）——
     * 而不是任何生产代码里的观测点。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：本批把材料收集从 {@code loop()} 内部搬到三个生产调用方
     * （{@code doRun} / {@code SubagentExecutor} / {@code ExecAgentHook}）。若某个调用方漏调
     * {@code collectRunMaterial}（= 变异：用未回灌的 params 直接交给 queryLoop），其发送的
     * system prompt 就只剩空段 → 本用例的 append 尾段断言必红。
     *
     * <p><b>RED 条件（变异验证）</b>：删掉 {@code doRun} 里的
     * {@code queryParams = collectRunMaterial(mainCtx, queryParams, state);} → run 走
     * {@code loop()} 时 {@code params.systemPrompt()} 为空 ⇒ append 尾段
     * （{@code E1B-APPEND-MARKER}，只可能来自材料收集的 buildEffectiveSystemPrompt）不再到达
     * provider；同时 userContext 通道（{@code currentDate} meta user 消息）缺失 → 红。
     *
     * <p>标记串选 appendSystemPrompt（{@code RunRequest.forTest(..., appendSystemPrompt)} 重载）：
     * 它经 RunRequest → AgentState.appendSystemPrompt → 材料收集（恒末尾追加）→ s10 → 发送 blocks，
     * 全程与仓库内容 / 环境无关，断言确定性。
     */
    @Test
    @DisplayName("[调用方边界] doRun（主线程调用方）收集三通道 → provider 边界收到 append 尾段 + userContext 前置")
    void mainThreadCaller_doRunCollectsMaterial_providerBoundarySeesIt() {
        final String appendMarker = "E1B-APPEND-MARKER-7f3a";
        List<List<SystemPromptBlock>> blocks = new ArrayList<>();
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        org.mockito.stubbing.Answer<Object> answer = inv -> {
            Object[] args = inv.getArguments();
            @SuppressWarnings("unchecked")
            List<SystemPromptBlock> b = (List<SystemPromptBlock>) args[2];
            @SuppressWarnings("unchecked")
            List<ChatMessageDto> h = (List<ChatMessageDto>) args[3];
            blocks.add(b == null ? List.of() : List.copyOf(b));
            histories.add(h == null ? List.of() : List.copyOf(h));
            // [fix-junit 2026-09-14] 按重载 arity 分派：20 参 = blocks+thinkingConfig（各后移一位）；
            //   19 参 = 无 thinkingConfig 的重载（onChunk@9 / onAssistantMessage@10 / onComplete@16）。
            // ⛔ 原为 `== 19 ? 大值 : 小值` ⇒ 19 参时 msgIdx=11 = onToolCallComplete（Consumer<ToolUseBlock>）
            //   ⇒ 对它 accept(AssistantMessage) 抛 ClassCastException ⇒ onComplete 永不执行 ⇒
            //   300s STREAM_TIMEOUT × 重试（实测单类 2111s、3 条断言失败）。守卫见
            //   LlmProviderStreamArityInvariantTest（再追加 stream 形参 ⇒ 立刻翻红）。
            int msgIdx = args.length == 20 ? 11 : 10;
            int doneIdx = args.length == 20 ? 17 : 16;
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<AssistantMessage> onMsg =
                (java.util.function.Consumer<AssistantMessage>) args[msgIdx];
            Runnable onComplete = (Runnable) args[doneIdx];
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        };
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
        // [fix-junit 2026-09-14] 第二个重载：**20 个 matcher** ⇒ 绑定 20 参 blocks+thinkingConfig 重载
        //   （⛔ 原为 19 个 matcher = 与上一桩重复的死桩，20 参重载从未被覆盖；hook agent 路径走的正是它）。
        //   两桩的 matcher 数**必须差 1**（19 vs 20）—— 这是「两种重载各打一桩」的唯一实现方式。
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // 生产入口：AgentLoop.run(RunRequest) → doRun（真实 AgentLoopContext 由 buildMainLoopContext 构造）
        new LlmAgentLoop(factory).run(
            RunRequest.forTest("hello", "test-model", null, appendMarker));

        assertThat(blocks).as("provider 必须被调用").isNotEmpty();
        String sent = blocks.get(0).stream().map(SystemPromptBlock::text)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(sent)
            .as("[调用方边界] doRun 未收集材料 ⇒ append 尾段不会到达 provider（变异必红）；"
                + "收集到位 ⇒ EffectiveSystemPromptBuilder 的 append 恒末尾段可见")
            .contains(appendMarker);
        assertThat(histories.get(0))
            .as("[调用方边界] userContext 经 prependUserContext 前置 meta user 消息（currentDate 恒在）"
                + "—— 仅当 doRun 收集了 userContext 才出现")
            .anyMatch(m -> m.isMeta() && m.content() != null && m.content().contains("currentDate"));
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

        com.nexusai.application.agent.loop.QueryParams callerParams0 = com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), List.of(), tuc(sessionId, mode), qs, "test-model",
                null, null, null, null, null, depsOf(ctx, "MAIN".equals(kind), "fixed-chain"),
                ProviderConfig.empty());
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams0.deps().context(), callerParams0, state),
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
     *
     * <p><b>[fix-junit 2026-09-14 订正]</b> ⛔ 原文「两种 stream 重载（blocks 重载 /
     * blocks+thinkingConfig 重载）都必须打桩——hook agent 走后者」：该句<b>意图正确</b>，但实现
     * 曾与它不符，已于 [fix-junit 2026-09-14] 修好，现为<b>真</b>：
     * 下方两个桩的 matcher 数<b>差 1</b>（<b>19</b> vs <b>20</b>）—— 19 参桩（{@code anyList()} 在 index 2
     * ⇒ 排除 default String 版）绑定 <b>19 参 blocks 抽象重载</b>；20 参桩绑定
     * <b>20 参 blocks+thinkingConfig 重载</b>，即 hook agent 路径（{@code ModelCaller} 的
     * thinkingConfig 分支）所走的那一个。⛔ 修改任一桩时<b>必须保持 matcher 数差 1</b>。
     *
     * <p><b>修复史（勿删 · 两处同因，均已修）</b>：批 2b（{@code d1a42bc}）给该桩<b>追加了第 19 个
     * matcher</b>，却没同步两个位置常量；随后给所有重载追加 {@code agentContext} 又把三档 arity 由
     * 18/18/19 推到 19/19/20 ⇒ {@code == 19 ? 大值 : 小值} 语义反转，19 参时取到大档索引 ⇒
     * {@code ClassCastException: AssistantMessage cannot be cast to ToolUseBlock} ⇒
     * {@code onComplete} 永不执行 ⇒ 300s {@code STREAM_TIMEOUT} × 重试（实测本类 2111s / 3 条红）。
     * 且第二个桩当时也是 19 个 matcher（死桩）⇒ 20 参重载从未被覆盖 ⇒ hook 臂恒红并再白烧一次 300s。
     * 两者已一并修复：本类改后 = <b>5 run / 0 F / 秒级</b>。守卫见
     * {@code LlmProviderStreamArityInvariantTest}（再给 {@code stream} 追加/中部插入形参 ⇒ 它立刻翻红）。
     *
     * <p>[C][fix-junit 2026-09-14 更正] 参数个数（实测 {@code LlmProvider.class.getMethods()}，
     * 由 {@code LlmProviderStreamArityInvariantTest} 钉住）：本接口 {@code stream} 共 <b>3 个重载</b>，
     * arity = <b>19 / 19 / 20</b> —— 19 参 = 抽象 blocks 重载（{@code ? querySource}，但无
     * thinkingConfig）与 default String 版（有 thinkingConfig）；<b>20 参 = blocks+thinkingConfig</b>
     * （{@code ? querySource + thinkingConfig}）。⛔ 原注释「blocks 重载 = 18 / blocks+thinkingConfig
     * 重载 = 19」已过期：{@code Boolean skipCacheWrite} 与 {@code AgentContext agentContext}
     * 两次<b>末尾追加</b>把三档整体推成 19/19/20。下方答案的位置索引按 <b>20 = 带 thinkingConfig</b>
     * 分派（该重载 onChunk/onAssistantMessage 比 19 参者后移一位）。
     */
    @SuppressWarnings("unchecked")
    private static LlmProviderFactory newProviderFactory(List<List<SystemPromptBlock>> captured,
                                                        AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        org.mockito.stubbing.Answer<Object> answer = inv -> {
            Object[] args = inv.getArguments();
            List<SystemPromptBlock> blocks = (List<SystemPromptBlock>) args[2];
            captured.add(blocks == null ? null : List.copyOf(blocks));
            // [fix-junit 2026-09-14] 按重载 arity 分派：20 参 = blocks+thinkingConfig（各后移一位）；
            //   19 参 = 无 thinkingConfig 的重载（onChunk@9 / onAssistantMessage@10 / onComplete@16）。
            // ⛔ 原为 `== 19 ? 大值 : 小值` ⇒ 19 参时 msgIdx=11 = onToolCallComplete（Consumer<ToolUseBlock>）
            //   ⇒ 对它 accept(AssistantMessage) 抛 ClassCastException ⇒ onComplete 永不执行 ⇒
            //   300s STREAM_TIMEOUT × 重试（实测单类 2111s、3 条断言失败）。守卫见
            //   LlmProviderStreamArityInvariantTest（再追加 stream 形参 ⇒ 立刻翻红）。
            int msgIdx = args.length == 20 ? 11 : 10;
            int doneIdx = args.length == 20 ? 17 : 16;
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
        // 19 参重载之一（anyList() 在 index 2 → 排除 default String 版，只可能绑定抽象 blocks 变体）
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
        // [fix-junit 2026-09-14] 第二个重载（**活桩**）：20 个 matcher ⇒ 绑定 20 参 blocks+thinkingConfig 重载
        //   —— hook agent 走的正是它（ModelCaller 的 thinkingConfig 分支）。⛔ 原为 19 个 matcher：
        //   与上一桩重复 ⇒ 死桩，20 参重载从未被覆盖 ⇒ hook 臂无 provider 回应 ⇒ 白烧一次 300s 流超时
        //   且 threePaths 的 hook 臂恒红。两桩 matcher 数必须差 1（19 vs 20）。
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
