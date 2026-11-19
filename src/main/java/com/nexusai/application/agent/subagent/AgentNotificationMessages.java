package com.nexusai.application.agent.subagent;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SendMessage 待办消息 → 子代理对话里的一条真实 user 消息（前端通知条的载体）。
 *
 * <p><b>为什么单源</b>：消费点有两处 —— 启动瞬间（{@code SubagentExecutor.runSubagentQueryLoop}
 * 启动段）与运行中每轮（{@code LlmAgentLoop.drainPendingAgentMessages}）。两处必须产出同一形态，
 * 否则「启动时收到的通知」与「运行中收到的通知」在前端会呈现不同结构（本仓历史缺陷：同一能力
 * 两套判据，一处修好另一处仍哑）。
 *
 * <p><b>契约</b>（对齐 CC {@code queued_command} + {@code createUserMessage}）：
 * <ul>
 *   <li>{@code id} = 入队时生成的稳定 ID —— 实时推送与转录重拉按同一 uuid 去重，只显示一次；</li>
 *   <li>{@code role=user}、{@code author}=发送方、{@code subtype="agent_message"}；</li>
 *   <li>{@code isMeta=true} —— 非真人输入（UI 隐藏于普通用户气泡判定，由前端通知条单独渲染）；</li>
 *   <li>{@code content}=原文 —— coordinator 包壳只在模型副本上做，历史/转录存原文；</li>
 *   <li>{@code structuredOutput.agent_summary} = SendMessage 的 5-10 词摘要 —— provider 不序列化
 *       该字段（模型只看到正文），仅供 transcript/前端展示。</li>
 * </ul>
 */
public final class AgentNotificationMessages {

    private AgentNotificationMessages() {
    }

    /**
     * 构造通知行 · 所有字段显式给出，不依赖任何"默认值恰好合适"。
     *
     * @param pm 入队记录（非 null）
     * @return 一条 isMeta 的 user 消息
     */
    public static ChatMessageDto toChatMessage(AgentNameRegistry.PendingAgentMessage pm) {
        OffsetDateTime at;
        try {
            at = pm.queuedAt() != null
                ? OffsetDateTime.parse(pm.queuedAt())
                : OffsetDateTime.now();
        } catch (Exception e) {
            at = OffsetDateTime.now();
        }
        Map<String, Object> structured =
            pm.summary() == null || pm.summary().isBlank()
                ? null : Map.of("agent_summary", pm.summary());
        return new ChatMessageDto(
            pm.id(), pm.sessionId(), Role.user, pm.from(), pm.text(), null,
            null, null, null, null, null, at, null, null,
            null, List.of(), List.of(), structured, true, false,
            null, "agent_message", false, null, null, null,
            null, null, null, null, null, false, false,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }
}
