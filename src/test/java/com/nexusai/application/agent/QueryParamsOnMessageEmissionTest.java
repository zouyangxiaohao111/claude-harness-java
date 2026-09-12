package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * [D] {@code QueryParams.onMessage} 消息级回调意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 的 {@code query()} 是 async generator
 * （query.ts:276-285），fork 在消费侧逐条 {@code outputMessages.push(message) + onMessage?.(message)}
 * （forkedAgent.ts:564-625/:578）—— dream task 进度 UI 与 {@code touchedPaths} 收集都建在
 * 「产出即回调」上。Java 的 {@code queryLoop} 返回聚合 {@code LoopResult}，<b>结构上产不出中间消息</b>，
 * 故本批把 onMessage 做成 QueryParams 通道（字段 + wither + 逐处透传 + 3 个真实发射点）。
 *
 * <p><b>RED 变异</b>：去掉任一发射点的 {@code onMessage.accept(...)}（AgentLoopContext
 * assistant(tool_calls) / AgentLoopContext tool_result / LlmAgentLoop 纯文本 assistant）→
 * 本测试对应断言必红（少一条 / 顺序错）。
 *
 * <p><b>取舍边界</b>（显式记录，非「顺手漏掉」）：只发「真实模型产出」两类 —— assistant 与
 * tool_result。错误/合成消息（max_output_tokens 截断提示、API 错误消息、stop hook 注入、队列注入
 * user 消息、工具 newMessages）<b>不发射</b>：它们不是模型 yield 的消息，CC 侧也由别的通道承载
 * （synthetic message / attachment）。
 */
@DisplayName("[D] QueryParams.onMessage：一次含工具轮的 run 逐条回调 assistant + tool_result")
class QueryParamsOnMessageEmissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次「工具轮 → 纯文本收尾轮」的脚本化 provider。 */
    private static LlmProvider toolThenTextProvider(AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            // 18-arg blocks 重载（[C] 后 skipCacheWrite 为末参）· 位置索引与 Listener 顺序不变
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (callCount.incrementAndGet() == 1) {
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash", input))));
            } else {
                // 纯文本轮必须走真实 text 通道（loop 的 text 由 onChunk 增量拼接 → 缺 chunk 会被
                // 空响应守卫判 NO_ASSISTANT_TEXT，消息不落库）
                onChunk.accept("done");
                onMsg.accept(new AssistantMessage("done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any());
        return provider;
    }

    /** 组装 loop 运行环境（真实 ToolRegistry + mock provider factory）。 */
    private static QueryParams runParams(LlmProvider provider, AgentState state,
                                        Consumer<ChatMessageDto> onMessage) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        com.nexusai.application.agent.loop.AgentLoopContext ctx = TestContexts.agentLoopContext(
            ToolRegistry.from(List.of(TestContexts.dummyTool("Bash"))), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public com.nexusai.application.agent.loop.AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-onmsg").withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
        return params.withOnMessage(onMessage);
    }

    private static AgentState stateWithUserMessage() {
        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        return state;
    }

    @Test
    @DisplayName("工具轮 run：onMessage 收到 assistant(tool_calls) → tool_result → assistant(纯文本)")
    void onMessage_receivesAssistantAndToolResult() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = toolThenTextProvider(callCount);
        AgentState state = stateWithUserMessage();
        List<ChatMessageDto> emitted = new ArrayList<>();

        LlmAgentLoop.queryLoop(runParams(provider, state, emitted::add), state, new ArrayList<>());

        // 发射点 ① assistant(tool_calls)
        ChatMessageDto assistantToolCalls = emitted.stream()
            .filter(m -> m.role() == Role.assistant && m.toolCalls() != null && !m.toolCalls().isEmpty())
            .findFirst().orElse(null);
        assertThat(assistantToolCalls)
            .as("onMessage 必须收到 assistant(tool_calls)（发射点 ① AgentLoopContext）").isNotNull();
        assertThat(assistantToolCalls.toolCalls().get(0).id()).isEqualTo("toolu_1");

        // 发射点 ② tool_result（CC query() yield 的 user 消息类型）
        ChatMessageDto toolResult = emitted.stream()
            .filter(m -> m.role() == Role.tool).findFirst().orElse(null);
        assertThat(toolResult)
            .as("onMessage 必须收到 tool_result（发射点 ② AgentLoopContext）").isNotNull();
        assertThat(toolResult.toolCallId()).isEqualTo("toolu_1");

        // 发射点 ③ 纯文本 assistant（收尾轮）
        ChatMessageDto plainText = emitted.stream()
            .filter(m -> m.role() == Role.assistant
                && (m.toolCalls() == null || m.toolCalls().isEmpty())
                && "done".equals(m.content()))
            .findFirst().orElse(null);
        assertThat(plainText)
            .as("onMessage 必须收到纯文本 assistant（发射点 ③ LlmAgentLoop）").isNotNull();

        // 顺序 = CC for-await 消费顺序（产出即回调）
        assertThat(emitted.indexOf(assistantToolCalls))
            .as("assistant(tool_calls) 必须先于 tool_result 回调")
            .isLessThan(emitted.indexOf(toolResult));
        assertThat(emitted.indexOf(toolResult))
            .as("tool_result 必须先于收尾文本回调").isLessThan(emitted.indexOf(plainText));
        assertThat(callCount.get()).as("两轮模型调用（工具轮 + 收尾轮）").isEqualTo(2);
    }

    @Test
    @DisplayName("未注入 onMessage → 零行为变化（紧凑构造器归一 no-op，run 正常收尾）")
    void onMessage_notInjected_noBehaviorChange() {
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = toolThenTextProvider(callCount);
        AgentState state = stateWithUserMessage();

        List<ChatMessageDto> before = List.copyOf(state.rawMessages());
        QueryParams params = runParams(provider, state, msg -> { });
        // 显式传 null 覆盖（紧凑构造器必须归一为 no-op，否则发射点 NPE）
        LlmAgentLoop.queryLoop(params.withOnMessage(null), state, new ArrayList<>());

        assertThat(callCount.get()).as("未注入回调不影响模型调用轮次").isEqualTo(2);
        assertThat(state.rawMessages()).as("消息落库行为逐条不变").hasSizeGreaterThan(before.size());
        assertThat(state.rawMessages().get(state.rawMessages().size() - 1).content())
            .as("收尾轮纯文本仍是最后一条").isEqualTo("done");
    }
}
