package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
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
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [ER-IMP-13] task_budget 跨 reactive compact 结转 · CC query.ts:1138-1146。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 在 proactive（query.ts:508-515）与 reactive
 * （query.ts:1138-1146）两个压缩路径做同一结转 {@code Math.max(0, (prev??total) - finalContextTokensFromLastResponse)}。
 * Java 端 proactive 已接线（LlmAgentLoopTaskBudgetCcTest），reactive 成功分支此前缺失 → 413/PTL 走
 * reactive compact 后 task_budget.remaining 不再倒数（provider 拿不到压缩后的 final context 窗口）。
 * 本测试驱动真实 reactive compact 成功路径，断言结转日志按同一公式产出 now=165000（total=200000
 * − preCompact measured=35000）。RED tooth：删除 reactive 分支 carryover 块 → 日志缺
 * "[ER-IMP-13 task_budget.remaining] reactive compact carryover" → fail。
 */
class LlmAgentLoopReactiveCarryoverTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    private void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    /** 60 条 user 消息 + 末位 assistant 带 usage（35000 final context）→ preCompact measured 非 0。 */
    private List<ChatMessageDto> preCompactMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(new ChatMessageDto(
                "m" + i, "s", Role.user, "user", "content " + i, null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null));
        }
        msgs.add(new ChatMessageDto(
            "last-a", null, Role.assistant, "assistant", "final response", null, List.of(),
            FinishReason.stop, 30_000, 5_000,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null));
        return msgs;
    }

    /** REACTIVE_COMPACT on + CONTEXT_COLLAPSE off → PTL 跳过 drain 直走 reactive compact。 */
    private AgentLoopContext reactiveCtx(LlmProviderFactory factory) {
        FeatureFlags flags = new FeatureFlags(true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        ReactiveCompactor rc = new ReactiveCompactor(
            new TokenEstimator()::estimateMessageTokens,
            (prompt, msgs) -> new CompactConversation.SummaryResult("reactive summary stub", null));
        rc.setEnabled(true);
        // [MR-T05] 融合后 AgentLoopContext record = 34 组件（DEL-14 删 commandQueue 后下标 −1，
        //   WF-3 sdkEventQueue 收尾 pos34）· 逐位对齐工作区已解 record（AgentLoopContext.java:125-171）
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class),  // 1 toolRegistry
            null,                                                                // 2 hookRegistry
            null,                                                                // 3 mcpServerService
            null,                                                                // 4 notificationQueue
            null,                                                                // 5 commandLifecycleNotifier
            null,                                                                // 6 skillCatalog
            null,                                                                // 7 memoryPrefetcher
            null,                                                                // 8 memoryStorage
            null,                                                                // 9 tokenBudgetChecker
            null,                                                                // 10 queryConfig
            factory,                                                             // 11 llmProviderFactory
            null,                                                                // 12 transientErrorHandler
            null,                                                                // 13 maxTokensHandler
            null,                                                                // 14 extractMemoriesAgent
            null,                                                                // 15 autoDreamConsolidator
            null,                                                                // 16 wsTemplate
            null,                                                                // 17 streamTopic
            null,                                                                // 18 streamSessionId
            null,                                                                // 19 streamUserMessageId
            flags,                                                               // 20 featureFlags
            rc,                                                                  // 21 reactiveCompactor
            null,                                                                // 22 contextCollapse
            null,                                                                // 23 skillDiscoveryPrefetch
            null,                                                                // 24 skillSearchPrefetch
            null,                                                                // 25 toolUseSummaryGenerator
            null,                                                                // 26 toolExecutionBeans
            null,                                                                // 27 tokenBudgetBeans
            null,                                                                // 28 eventBridge
            null,                                                                // 29 permissionContextBuilder
            null,                                                                // 30 promptSuggestion
            null,                                                                // 31 sessionState
            null,                                                                // 32 claudemdEngine
            null,                                                                // 33 modelConfigResolver
            null,                                                                // 34 sdkEventQueue
            null, null);                                                          // 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    /** 首次调用 PTL(413)，重试（reactive compact 后）返回 stop 纯文本 → 正常完成。 */
    private LlmProviderFactory ptlOnceThenStopProvider() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                // [IMP-A4-2 · A-24 isPtlError 收窄] CC 真源 PTL 错误（errors.ts:562-564 仅认
                //   'prompt is too long' 字面子串）——旧触发串 "prompt_too_long: ..."（下划线变体）
                //   是 A-24 拍板消除的异常级误触发类（CC 不会转成 'Prompt is too long' 消息）。
                onErr.accept(new LlmApiException(
                    413, Collections.emptyMap(),
                    "prompt is too long: 137500 tokens > 135000 maximum"));
            } else {
                Consumer<String> onChunk = inv.getArgument(9);
                Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                onChunk.accept("recovered reply");
                if (onMsg != null) {
                    onMsg.accept(new AssistantMessage("recovered reply", "stop", List.of()));
                }
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    @Test
    @DisplayName("reactive compact 成功后 task_budget 结转：max(0,(prev??total)−measured)=165000 · CC query.ts:1138-1146")
    void reactiveCompactSuccess_carriesTaskBudget() {
        attachLogAppender();

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        preCompactMessages().forEach(state::appendMessage);
        int original = state.messages().size();

        AgentLoopContext ctx = reactiveCtx(ptlOnceThenStopProvider());
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", 8,
            new TaskBudget(200_000), null, null, null, deps, ProviderConfig.empty());

        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        // ① reactive compact 真实成功（消息被压缩）
        assertThat(state.messages().size())
            .as("PTL 必须走 reactive compact 压缩消息（消息数下降）· CC query.ts:1138 reactive compacted")
            .isLessThan(original);
        // ② reactive 分支执行结转（与 proactive 同一公式）
        List<String> logs = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs)
            .as("reactive compact 成功必须执行 task_budget 结转（ER-IMP-13 · CC query.ts:1138-1146）")
            .anyMatch(l -> l.contains("[ER-IMP-13 task_budget.remaining] reactive compact carryover"));
        // ③ 结转值 = applyTaskBudgetCarryover(prev=null, total=200000, measured=35000) = 165000
        //    （preCompact 末位 assistant 带 usage 35000 = finalContextTokensFromLastResponse）
        assertThat(logs)
            .as("reactive 结转减法源 = finalContextTokensFromLastResponse(preCompact)=35000 → now=165000")
            .anyMatch(l -> l.contains("now=165000"));
    }
}
