package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [V-FB-03 返工] tengu_query_error 遥测全量计数测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC query.ts:959-966 的 tengu_query_error
 * 事件统计 <b>全量</b> assistant 消息数（{@code assistantMessages: assistantMessages.length}）
 * 与全量 tool_use 块数（{@code toolUses: assistantMessages.flatMap(...tool_use).length}）。
 * Java 旧实现仅计当前失败流的单条 capturedMsg（0/1）+ 其 toolCalls —— 半实现（verify V-FB-03）。
 *
 * <p><b>RED tooth</b>: 回退「全量计数」为 0/1 硬编码后本测试必须 fail —— 场景为「前一回合
 * 已提交 1 条带 tool_use 的 assistant 消息，本回合 LLM 调用不可恢复错误」→ tengu_query_error
 * 应统计 assistantMessages=1 + toolUses=1（全量），若退化为 0/1 则日志 assistantMessages=0
 * （capturedMsg 为 null）→ 断言失败。
 */
class LlmAgentLoopTenguQueryErrorCountTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * provider：1st 调用返回 tool_use assistant 消息（触发工具执行 → 下一回合），
     * 2nd 调用 onError 抛不可恢复 400（非 max_tokens/非 fast-mode → 不可重试 → STREAM_ERROR）。
     */
    private LlmProviderFactory providerFactory(AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash", input)), "", null, 0L));
            } else {
                // 不可恢复错误：400 + 非溢出 + 非 fast-mode + 无 x-should-retry → shouldRetry=false
                onErr.accept(new LlmApiException(400, Map.of(), "bad request"));
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
    @DisplayName("tengu_query_error 全量计数：前一回合已提交 tool_use assistant → assistantMessages=1 + toolUses=1 · CC query.ts:960-961")
    void tenguQueryErrorCountsCommittedAssistantAndToolUses() {
        attachLogAppender();
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProviderFactory factory = providerFactory(callCount);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class),
            null, null, null, null, null, null, null,
            null,
            null, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        LlmAgentLoop.queryLoop(
            QueryParams.forLoop(
                state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // STREAM_ERROR 退出（不可恢复 400）· 前一回合已提交 tool_use assistant
        assertThat(state.exitReason()).isEqualTo(AgentState.ExitReason.STREAM_ERROR);

        List<String> tenguLines = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("tengu_query_error"))
            .collect(Collectors.toList());
        assertThat(tenguLines)
            .as("[V-FB-03] tengu_query_error 事件必须已发出（STREAM_ERROR 分支 · CC query.ts:959-966）")
            .isNotEmpty();

        // 全量计数：assistantMessages=1（前一回合提交的 tool_use assistant）+ toolUses=1（其 toolCalls）
        assertThat(tenguLines.get(0))
            .as("[V-FB-03] assistantMessages 应统计全量已提交 assistant 消息（含前回合 tool_use 消息），"
                + "而非当前失败流的 0/1（CC query.ts:960 assistantMessages.length）")
            .contains("assistantMessages=1")
            .as("[V-FB-03] toolUses 应统计全量 tool_use 块（跨全部 assistant 消息），而非当前流的 0/1"
                + "（CC query.ts:961 flatMap 全量 tool_use）")
            .contains("toolUses=1");
    }
}
