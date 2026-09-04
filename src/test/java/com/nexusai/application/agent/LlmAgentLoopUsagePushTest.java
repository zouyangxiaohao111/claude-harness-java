package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.eventbus.ws.MessageUsageEvent;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [usage-push] LlmAgentLoop.publishMessageUsage 定向测试（mock wsTemplate + TokenBudgetBeans）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: 逐条 assistant 流式结束要<b>实时</b>推 message.usage
 * （对齐 CC claude.ts:2244-2248 message.usage 写回 UI）+ <b>run 级累计</b>（turn 末 complete.usage
 * 读累计）。本测试锁定：
 * <ol>
 *   <li><b>推送 + 累计</b>：有 ws → convertAndSend 发 MessageUsageEvent（type=message.usage，
 *       携带该条 usage + decode_ms + 上下文快照），且 state.runUsage() 已累计；</li>
 *   <li><b>无 ws 仍累计</b>：非流式 / 单测无 wsTemplate → 跳过推送但 runUsage() 仍累计
 *       （complete.usage 口径不依赖流式通道）；</li>
 *   <li><b>null 守卫</b>：msg null → 不推不累计（无 usage 上报消息不污染）。</li>
 * </ol>
 */
@DisplayName("[usage-push] LlmAgentLoop.publishMessageUsage")
class LlmAgentLoopUsagePushTest {

    private AgentLoopContext ctx(SimpMessagingTemplate ws, ModelMapper modelMapper, ProviderMapper providerMapper) {
        // 32 参 compat 构造器（modelConfigResolver/sdkEventQueue/queueEventPublisher/modelCostCalculator 置 null）。
        // 位置：16=wsTemplate 17=streamTopic 18=streamSessionId 19=streamUserMessageId 20=featureFlags
        //      27=tokenBudgetBeans（= agent 参数序：1 ToolRegistry, 2-15 组件 null, 16-19 ws/stream,
        //      20 FeatureFlags, 21-26 组件 null, 27 TokenBudgetBeans, 28-32 组件 null）
        return new AgentLoopContext(
            mock(com.nexusai.application.agent.tool.ToolRegistry.class),
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            ws, "topic", "sess", "um",
            FeatureFlags.ALL_DISABLED,
            null, null, null, null, null, null,
            new AgentLoopContext.TokenBudgetBeans(null, modelMapper, providerMapper),
            null, null, null, null, null);
    }

    private ModelMapper modelMapper = mock(ModelMapper.class);
    private ProviderMapper providerMapper = mock(ProviderMapper.class);

    private AgentState newState() {
        AgentState state = new AgentState("sys", "sess-x", null);
        state.setCurrentModel("deepseek-v4-flash");
        return state;
    }

    @Test
    @DisplayName("有 ws → 推 message.usage（含 usage+decode_ms+上下文快照）且 runUsage 累计")
    void pushesMessageUsageAndAccumulatesRunUsage() {
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);
        AgentLoopContext ctx = ctx(ws, modelMapper, providerMapper);
        AgentState state = newState();
        AgentUsage usage = new AgentUsage(1000L, 500L, 100L, 200L, null, null, null);
        AssistantMessage msg = new AssistantMessage("回复", "stop", List.of(), "思考", null, usage);

        LlmAgentLoop.publishMessageUsage(ctx, state, "deepseek-v4-flash", "msg-u", "a-final", msg, 1234L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq("topic"), payload.capture());
        assertThat(payload.getValue())
            .as("推送负载必须是 message.usage 事件")
            .isInstanceOf(MessageUsageEvent.class);
        MessageUsageEvent evt = (MessageUsageEvent) payload.getValue();
        assertThat(evt.getType()).isEqualTo("message.usage");
        assertThat(evt.getSessionId()).isEqualTo("sess");
        assertThat(evt.getUserMessageId()).isEqualTo("msg-u");
        assertThat(evt.getAssistantMessageId()).isEqualTo("a-final");
        assertThat(evt.getUsage()).isNotNull();
        assertThat(evt.getUsage().inputTokens()).isEqualTo(1000L);
        assertThat(evt.getUsage().outputTokens()).isEqualTo(500L);
        assertThat(evt.getUsage().cacheReadInputTokens()).isEqualTo(200L);
        assertThat(evt.getUsage().cacheCreationInputTokens()).isEqualTo(100L);
        assertThat(evt.getUsage().decodeMs()).isEqualTo(1234L);

        // 上下文快照：模型不可判定（mock mapper unstubbed）→ 窗口回落 1M；非 anthropic → used=input
        assertThat(evt.getContextWindow()).isEqualTo(1_048_576L);
        assertThat(evt.getContextTokensUsed()).isEqualTo(1000L);
        assertThat(evt.getPercentLeft()).isEqualTo(100); // round((1-1000/1M)*100)=100

        // run 级累计（turn 末 complete.usage 读此）
        assertThat(state.runUsage().inputTokens()).isEqualTo(1000L);
        assertThat(state.runUsage().outputTokens()).isEqualTo(500L);
        assertThat(state.runUsage().cacheReadInputTokens()).isEqualTo(200L);
    }

    @Test
    @DisplayName("无 ws（非流式/单测）→ 跳过推送但 runUsage 仍累计（complete 口径不依赖流式通道）")
    void noWsStillAccumulatesRunUsage() {
        AgentLoopContext ctx = ctx(null, modelMapper, providerMapper);
        AgentState state = newState();
        AgentUsage usage = new AgentUsage(2000L, 800L, 50L, 300L, null, null, null);
        AssistantMessage msg = new AssistantMessage("回复", "stop", List.of(), null, null, usage);

        LlmAgentLoop.publishMessageUsage(ctx, state, "deepseek-v4-flash", "msg-u", "a-final", msg, null);

        assertThat(state.runUsage().inputTokens()).as("无 ws 仍累计 runUsage").isEqualTo(2000L);
        assertThat(state.runUsage().outputTokens()).isEqualTo(800L);
        assertThat(state.runUsage().cacheReadInputTokens()).isEqualTo(300L);
    }

    @Test
    @DisplayName("null 守卫：msg null → 不推不累计（无 usage 上报消息不污染）")
    void nullMsgSkipsPushAndAccumulation() {
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);
        AgentLoopContext ctx = ctx(ws, modelMapper, providerMapper);
        AgentState state = newState();

        LlmAgentLoop.publishMessageUsage(ctx, state, "deepseek-v4-flash", "msg-u", "a-final", null, null);

        verify(ws, never()).convertAndSend(eq("topic"), org.mockito.ArgumentMatchers.<Object>any());
        assertThat(state.runUsage().inputTokens()).isZero();
        assertThat(state.runUsage().outputTokens()).isZero();
    }
}
