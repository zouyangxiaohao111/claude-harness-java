package com.nexusai.application.agent;

import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.skill.SkillCatalog;
import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [E-1a] fork 屏蔽档（task 4）· 每个受屏蔽的副作用点「fork 来源不触发 / 主线程来源触发」。
 *
 * <p><b>WHY 本测试存在（意图 · CLAUDE.md 规则九）</b>：后台 fork（compact / session-memory /
 * extract-memories / auto-dream）今天走专用循环 {@code ProductionForkedQuery}；E-1b 收敛到主循环
 * {@code queryLoop} 后，主循环内<b>主会话专属副作用</b>会对 fork 也执行 —— 而 Java 的
 * {@code AgentLoopContext} 携带主会话级 bean（事件桥 / state / 注册表），CC 靠 fork 的隔离上下文
 * 天然隔离。若不设门，fork 会：把 turn 生命周期发进主会话 UI 事件流、把 fork 消息喂给主会话
 * post-sampling hooks、与主线程共用 skill_listing 槽位、并在超窗时触发<b>嵌套压缩</b>。
 *
 * <p><b>断言面 = 可观测后果</b>（不是内部计数器）：published 事件列表 / post-sampling hook 调用数 /
 * {@code state.exitReason()}（BLOCKING_LIMIT 与否）/ 落库消息里的 skill_listing。
 *
 * <p><b>RED teeth</b>：删掉任一门的 {@code QuerySource.isBackgroundForkSource} 判定 →
 * 对应用例 RED。
 */
@DisplayName("[E-1a] fork 屏蔽档：后台 fork 来源不触发主会话专属副作用（主线程/子代理不变）")
class E1aForkShieldGateTest {

    /** 组装后 token 估算超窗 → 未豁免来源必被 blocking-limit 拦截（见 LlmAgentLoopBlockingLimitTest 同款夹具）。 */
    private static final int MOCK_TOKEN_USAGE = 100_000;
    /** contextWindow 50000 → blockingLimit = 47000 &lt; 100000 → 触发。 */
    private static final int MOCK_CONTEXT_WINDOW = 50_000;

    @AfterEach
    void tearDown() {
        PostSamplingHookRegistry.clearAll();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 后台 fork 来源：blocking-limit 豁免 + 不发 turn 事件 + 不跑 post-sampling
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 来源(EXTRACT_MEMORIES): 超窗不被 blocking-limit 拦截 + 不发 turn 事件 + 不跑 post-sampling")
    void backgroundForkSource_allShieldsActive() {
        Drive d = drive(QuerySource.EXTRACT_MEMORIES, true);

        assertThat(d.exitReason).as("后台 fork 豁免 blocking-limit 预检（fork 继承完整对话，预检会死锁）")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(d.events).as("后台 fork 不得把 turn 生命周期发进主会话 UI 事件流")
            .isEmpty();
        assertThat(d.postSamplingCalls).as("后台 fork 不得把消息喂给主会话 post-sampling hooks").isZero();
    }

    @Test
    @DisplayName("对照 主线程来源(USER): 超窗 → BLOCKING_LIMIT（夹具确实是「热」的）+ 发 turn 事件 + 跑 post-sampling")
    void mainThreadSource_control_fixtureIsHotAndHooksFire() {
        Drive hot = drive(QuerySource.USER, true);
        assertThat(hot.exitReason)
            .as("同一夹具下主线程来源必须被拦截 —— 否则本测试的「热夹具」不成立，fork 侧断言会假绿")
            .isEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(hot.events).as("被拦截时不发 AgentTurnCompleted（turn 未完成；Started 在预检之前已发）")
            .extracting(e -> e.getClass().getSimpleName())
            .doesNotContain("AgentTurnCompletedEvent");

        Drive normal = drive(QuerySource.USER, false);
        assertThat(normal.exitReason).as("正常预算下主线程照常跑完").isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(normal.events).as("主线程必须发 turn 事件（AgentTurnStarted + AgentTurnCompleted）")
            .extracting(e -> e.getClass().getSimpleName())
            .contains("AgentTurnStartedEvent", "AgentTurnCompletedEvent");
        assertThat(normal.postSamplingCalls).as("主线程必须跑 post-sampling hooks").isPositive();
    }

    @Test
    @DisplayName("对照 子代理来源(FORK): 不在屏蔽档 —— 超窗照旧拦截、turn 事件照发、post-sampling 照跑（现网不变）")
    void subagentForkSource_notShielded_behaviorUnchanged() {
        Drive hot = drive(QuerySource.FORK, true);
        assertThat(hot.exitReason)
            .as("FORK 是子代理（不是后台 fork）—— CC 侧走同一 query() 且照常受 blocking 预检，本批不改其行为")
            .isEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);

        Drive normal = drive(QuerySource.FORK, false);
        assertThat(normal.events).as("子代理 turn 事件照发（现网行为不变）")
            .extracting(e -> e.getClass().getSimpleName())
            .contains("AgentTurnStartedEvent", "AgentTurnCompletedEvent");
        assertThat(normal.postSamplingCalls).as("子代理 post-sampling 照跑（现网行为不变）").isPositive();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. skill_listing 槽位（注册表键 = agentId ?? ""，fork agentId=null 会与主线程共用槽）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 来源(EXTRACT_MEMORIES): 不注入 skill_listing、不占用主线程槽位；对照主线程 USER 注入")
    void backgroundForkSource_doesNotPolluteSkillListingSlot() {
        SkillCatalog catalog = mockCatalog();
        SkillListingSentRegistry.reset();
        LlmProvider provider = plainTextProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = ctxWithCatalog(catalog, factory);

        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMessage("m1", "q"));

        com.nexusai.application.agent.loop.QueryParams callerParams0 = com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), null, tuc(sessionId), QuerySource.EXTRACT_MEMORIES,
                "test-model", null, null, null, null, null,
                deps(ctx, true), ProviderConfig.empty())
                .withCanUseTool(allowAllCanUseTool());
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams0.deps().context(), callerParams0, state),
            state, new ArrayList<>());

        assertThat(listingContent(state.rawMessages()))
            .as("后台 fork 不得注入 skill_listing（对摘要/记忆提取无意义，且会与主线程共用注册表槽位）")
            .isNull();
        assertThat(SkillListingSentRegistry.isInitialized(sessionId, ""))
            .as("fork 不得占用/改写主线程（agentKey=\"\"）的 skill_listing 槽位")
            .isFalse();

        // 对照：同一 ctx/注册表下主线程来源照常注入
        AgentState mainState = new AgentState("sys", sessionId, null);
        mainState.appendMessage(userMessage("m2", "q2"));
        com.nexusai.application.agent.loop.QueryParams callerParams1 = com.nexusai.application.agent.loop.QueryParams.forLoop(
                mainState.rawMessages(), null, tuc(sessionId), QuerySource.USER,
                "test-model", null, null, null, null, null,
                deps(ctx, true), ProviderConfig.empty());
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams1.deps().context(), callerParams1, mainState),
            mainState, new ArrayList<>());
        assertThat(listingContent(mainState.rawMessages()))
            .as("对照：主线程来源必须注入 skill_listing（门只屏蔽后台 fork 来源）")
            .isNotNull()
            .contains("commit");
    }

    // ════════════════════════════════════════════════════════════════════
    // 驱动脚手架
    // ════════════════════════════════════════════════════════════════════

    /** 一次 run 的可观测后果。 */
    private static final class Drive {
        AgentState.ExitReason exitReason;
        List<Object> events;
        int postSamplingCalls;
    }

    private static Drive drive(QuerySource source, boolean hotBudget) {
        Drive d = new Drive();
        List<Object> events = new ArrayList<>();
        AtomicInteger hookCalls = new AtomicInteger();
        PostSamplingHookRegistry.clearAll();
        PostSamplingHookRegistry.register((PostSamplingContext ctx) -> hookCalls.incrementAndGet());

        ApplicationEventPublisher publisher = event -> {
            synchronized (events) {
                events.add(event);
            }
        };

        LlmProvider provider = plainTextProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null,
            hotBudget ? TestContexts.tokenBudgetBeans(MOCK_CONTEXT_WINDOW, MOCK_TOKEN_USAGE) : null,
            FeatureFlags.ALL_DISABLED,
            new AgentLoopContext.EventBridge(publisher, null, null));

        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMessage("m1", "q"));

        QuerySource s = source;
        com.nexusai.application.agent.loop.QueryParams callerParams2 = com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), null, tuc(sessionId), s, "test-model",
                null, null, null, null, null, deps(ctx, s == QuerySource.USER), ProviderConfig.empty())
                .withCanUseTool(allowAllCanUseTool());
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams2.deps().context(), callerParams2, state),
            state, new ArrayList<>());

        d.exitReason = state.exitReason();
        synchronized (events) {
            d.events = List.copyOf(events);
        }
        d.postSamplingCalls = hookCalls.get();
        return d;
    }

    private static LoopDeps deps(AgentLoopContext ctx, boolean mainLoop) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return mainLoop; }
            @Override public String resolveModel() { return "test-model"; }
        };
    }

    /** 带 Skill 工具的 TUC（skill_listing 注入前置：availableTools 含 Skill，CC attachments.ts:2750-2755）。 */
    private static ToolUseContext tuc(String sessionId) {
        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        return new ToolUseContext(UUID.randomUUID(), sessionId,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            java.util.Map.of(), List.of(skillTool));
    }

    /** 最小 32 参 ctx（位置 6 = skillCatalog、位置 11 = provider factory）；其余基础设施 null。 */
    private static AgentLoopContext ctxWithCatalog(SkillCatalog catalog, LlmProviderFactory factory) {
        return new AgentLoopContext(
            null, null, null, null, null, catalog, null, null, null,
            null, factory, null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private static SkillCatalog mockCatalog() {
        SkillCatalog catalog = mock(SkillCatalog.class);
        List<Command> commands = List.of(cmd("commit"), cmd("review"));
        when(catalog.getModelInvocableCommandsForListing()).thenReturn(commands);
        when(catalog.getModelInvocableCommands()).thenReturn(commands);
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(Command::getName)
                .collect(java.util.stream.Collectors.joining("\n"));
        });
        return catalog;
    }

    private static Command cmd(String name) {
        Command c = mock(Command.class);
        when(c.getName()).thenReturn(name);
        return c;
    }

    /** 纯文本 stop provider（单轮收尾 → 触发 AgentTurnCompleted + post-sampling）。 */
    @SuppressWarnings("unchecked")
    private static LlmProvider plainTextProvider() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        org.mockito.stubbing.Answer<Object> answer = inv -> {
            Object[] args = inv.getArguments();
            // 19 参 = blocks+thinkingConfig 重载（onChunk/onAssistantMessage/onComplete 各后移一位）
            int chunkIdx = args.length == 19 ? 10 : 9;
            int msgIdx = args.length == 19 ? 11 : 10;
            int doneIdx = args.length == 19 ? 17 : 16;
            java.util.function.Consumer<String> onChunk =
                (java.util.function.Consumer<String>) args[chunkIdx];
            java.util.function.Consumer<AssistantMessage> onMsg =
                (java.util.function.Consumer<AssistantMessage>) args[msgIdx];
            Runnable onComplete = (Runnable) args[doneIdx];
            // 必须先发 chunk：纯文本分支的空响应守卫判 text/chunkCount（只发 onMsg 会被判
            //   NO_ASSISTANT_TEXT 提前退出，落不到 AgentTurnCompleted / post-sampling 断言面）
            onChunk.accept("plain text reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of(), "", null, 5L));
            }
            onComplete.run();
            return null;
        };
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Mockito.doAnswer(answer).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 恒 ALLOW 的受限 canUseTool（后台 fork 来源必须注入 —— E-1a fail-loud 契约）。 */
    private static HookPermissionResolver.CanUseTool allowAllCanUseTool() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            ToolPermissionGate.DecisionResult.allow();
    }

    private static String listingContent(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if ("skill_listing".equals(m.subtype())) {
                return m.content();
            }
        }
        return null;
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
