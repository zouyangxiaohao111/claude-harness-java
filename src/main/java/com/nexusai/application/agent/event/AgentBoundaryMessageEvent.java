package com.nexusai.application.agent.event;

import com.nexusai.application.agent.AgentState;
import com.nexusai.model.session.dto.ChatMessageDto;

/**
 * Agent 压缩边界消息事件 · 对齐 CC query.ts:406-408
 * {@code if (snipResult.boundaryMessage) yield snipResult.boundaryMessage}.
 *
 * <p>snip 步骤产出 boundary 消息时发布一次（主循环 snip 块内, CC query.ts:401-410）,
 * 经 {@code AgentLoopContext.publishEvent} 双通道对外:
 * <ul>
 *   <li><b>Spring 事件通道</b> —— 本 record 发布给 {@code ApplicationEventPublisher}
 *       监听方（前端/SDK 通道, 前端域 EXTERNAL 见 §7-30 注记）;</li>
 *   <li><b>runStream 流通道</b> —— {@code LlmAgentLoop.adaptToAgentEvent} 适配为
 *       {@link com.nexusai.application.agent.AgentEvent.BoundaryMessage} 入
 *       EVENT_BUFFER, {@code runStream(RunRequest)} 消费方（REPL/SDK/CCR Remote）
 *       在事件流中可见, 等价 CC AsyncGenerator 的 yield。</li>
 * </ul>
 *
 * <p><b>双通道语义</b>: boundary 消息经流事件对外呈现, 本身不落入
 * {@code state.messages}（模型面消息链仍为 snip 后的 {@code snipResult.messages},
 * removedUuids 剔除后, 随下轮发送给模型）——与 CC 一致: yield 是流事件, messagesForQuery 继续走模型。
 *
 * @see com.nexusai.application.agent.AgentEvent.BoundaryMessage
 * @see AgentTurnStartedEvent
 */
public record AgentBoundaryMessageEvent(AgentState state, ChatMessageDto boundaryMessage) {

    public AgentBoundaryMessageEvent {
        if (boundaryMessage == null) {
            throw new IllegalArgumentException("boundaryMessage is null");
        }
    }
}
