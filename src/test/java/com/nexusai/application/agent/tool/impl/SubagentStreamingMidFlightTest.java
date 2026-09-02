package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4-1] mid-flight appendListener RED-GREEN 双证测试 (决策 2 主目标收尾).
 *
 * <p>规则九 (验证意图): CC {@code runAgent.ts:748-806} for-await 逐消息 yield —— 父 Agent 必须
 * <b>实时</b>观测子 Agent 产出 (首消息早于终态, liveness 信号), 且 yield 顺序 = 消息产生顺序.
 * Java 等价 = {@link AgentState#setAppendListener} 单点方案 (S4-1 决策): {@code appendMessage}
 * 是唯一消息 append 通道, 监听器 append 后同步触发 —— 替代旧后置批量 emit (P0-2 根因).
 *
 * <p>RED 依据: S4-1 前无 appendListener 字段/方法 (编译即失败); 若回退后置批量 emit,
 * "appendMessage 返回前 sink 已收到" 时间序断言红.
 */
@DisplayName("[S4-1] mid-flight appendListener (实时逐消息 emit)")
class SubagentStreamingMidFlightTest {

    private static ChatMessageDto dto(Role role, String content, List<ToolCallDto> toolCalls,
                                      Integer inputTokens, Integer outputTokens) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), role, null, content, null,
            toolCalls,
            (toolCalls != null && !toolCalls.isEmpty()) ? FinishReason.tool_calls : null,
            inputTokens, outputTokens, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("武装 listener 后 appendMessage 同步触发回调 (时间序: append 前未收到, append 后立即收到)")
    void appendMessage_shouldFireListenerSynchronously() {
        // WHY: 流式语义的核心是 "逐消息实时" — 回调必须在 appendMessage 返回前触发
        //   (CC for-await 每轮循环 yield, 父 Agent 不等终态). 后置批量 emit 会破坏该时间序.
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        List<ChatMessageDto> observed = new ArrayList<>();
        Consumer<ChatMessageDto> listener = observed::add;
        state.setAppendListener(listener);

        assertThat(observed).as("append 前监听器不得提前收到任何消息").isEmpty();

        ChatMessageDto msg = dto(Role.assistant, "首条产出", null, 10, 5);
        state.appendMessage(msg);

        assertThat(observed).as("appendMessage 返回前回调已同步触发 (实时性)")
            .containsExactly(msg);
    }

    @Test
    @DisplayName("回调携带消息经 toSubagentMessage 正确类型映射 (assistant → AssistantMessage / user → UserMessage)")
    void listenerCallback_shouldMapMessageTypesCorrectly() {
        // WHY: sink 消费端 (executeStreaming messageSink) 依赖 toSubagentMessage 类型映射 —
        //   assistant 消息含 usage (CC agentToolUtils.ts:355), 父 Agent 据此做 token budget 决策.
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        List<SubagentMessage> sink = new ArrayList<>();
        state.setAppendListener(msg -> sink.add(SubagentExecutor.toSubagentMessage(msg, "agent-1")));

        state.appendMessage(dto(Role.assistant, "结论文本", null, 120, 30));
        state.appendMessage(dto(Role.user, "用户提示", null, null, null));

        assertThat(sink).hasSize(2);
        assertThat(sink.get(0)).isInstanceOf(SubagentMessage.AssistantMessage.class);
        SubagentMessage.AssistantMessage am = (SubagentMessage.AssistantMessage) sink.get(0);
        assertThat(am.content()).isEqualTo("结论文本");
        assertThat(am.usage().inputTokens()).isEqualTo(120L);
        assertThat(am.usage().outputTokens()).isEqualTo(30L);
        assertThat(am.agentId()).as("消息发射点注入 agentId (CC SkillTool.ts:256)").isEqualTo("agent-1");
        assertThat(sink.get(1)).isInstanceOf(SubagentMessage.UserMessage.class);
    }

    @Test
    @DisplayName("逐消息 append → sink 顺序与产生顺序一致 (CC for-await yield 顺序)")
    void listener_shouldPreserveMessageOrder() {
        // WHY: 父 Agent 依赖消息顺序重建子 Agent 思维链 — 乱序 emit 会错乱 transcript/UI.
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        List<String> order = new ArrayList<>();
        state.setAppendListener(msg -> order.add(msg.content()));

        state.appendMessage(dto(Role.user, "m1", null, null, null));
        state.appendMessage(dto(Role.assistant, "m2", null, null, null));
        state.appendMessage(dto(Role.assistant, "m3", null, null, null));

        assertThat(order).containsExactly("m1", "m2", "m3");
    }

    @Test
    @DisplayName("解除后不再回调 (泄漏防护: queryLoop finally clearAppendListener)")
    void clearAppendListener_shouldStopCallbacks() {
        // WHY: runSubagentQueryLoop finally 解除监听 — 不解除则跨 execute 复用残留回调引用
        //   (异常/早退路径). 泄漏 = 后续无关 append 触发陈旧 sink.
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        AtomicInteger fired = new AtomicInteger(0);
        state.setAppendListener(msg -> fired.incrementAndGet());
        state.appendMessage(dto(Role.user, "m1", null, null, null));
        assertThat(fired.get()).as("武装期间必须触发").isEqualTo(1);

        state.clearAppendListener();
        state.appendMessage(dto(Role.user, "m2", null, null, null));
        assertThat(fired.get()).as("解除后不得再触发 (泄漏防护)").isEqualTo(1);
    }

    @Test
    @DisplayName("未武装默认零行为影响 (主循环不 setAppendListener 时无任何开销)")
    void unarmed_shouldBeNoOp() {
        // WHY: AgentState 是主循环共享类 (高扇出) — listener 默认 null, 主循环不武装,
        //   零行为影响 (S4 风险项 1 的 RED 证据).
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        state.appendMessage(dto(Role.user, "m1", null, null, null));
        state.appendMessage(dto(Role.assistant, "m2", null, 1, 1));
        // 无异常 = 通过; 消息正常落库
        assertThat(state.messages()).hasSize(2);
    }

    @Test
    @DisplayName("replaceMessages (压缩路径) 不触发 listener — 单通道语义 (append 才 yield)")
    void replaceMessages_shouldNotFireListener() {
        // WHY: CC 压缩重建 messages 不产生 per-message yield (compact 独立通道); Java
        //   replaceMessages 清空重建若也触发 listener 会双发. 单点语义: 仅 appendMessage yield.
        AgentState state = new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        AtomicInteger fired = new AtomicInteger(0);
        state.setAppendListener(msg -> fired.incrementAndGet());

        state.replaceMessages(List.of(dto(Role.user, "compacted", null, null, null)));

        assertThat(fired.get()).as("replaceMessages 不得触发逐消息 listener (CC compact 独立通道)")
            .isZero();
    }
}
