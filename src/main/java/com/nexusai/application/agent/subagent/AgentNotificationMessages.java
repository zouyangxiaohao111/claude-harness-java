package com.nexusai.application.agent.subagent;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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

    /**
     * 通知行 → 子代理 transcript 落盘 Map（**入队即落库**的唯一写入形态）。
     *
     * <p><b>WHY 需要第二条写入通道</b>：{@link #toChatMessage} 只在**消费点**（子代理下一轮
     * drain / 启动段 drain）被 append，若子代理没有活消费点（已结束 / 空闲），待办永远留在
     * {@code AgentNameRegistry.pendingMessages} 内存里 ⇒ transcript 没有行 ⇒ 子代理会话视图
     * 永远看不到通知。本方法让入队侧可以立刻写出一条可读的行；消费侧再 append 时由
     * {@link AgentTranscript#recordSidechainTranscript} 按 uuid 去重（同一 uuid 只落一次）。
     *
     * <p>与 {@link #toChatMessage} 的字段逐项对齐：{@code role=user, isMeta=true,
     * subtype=agent_message, author=发送方, content=正文}；{@code summary} 是惰性键（空则省略）。
     * ⛔ 不写 {@code structuredOutput} / {@code peer_source} —— 子代理通知不是跨会话来信，
     * 前端必须走 agent 通知条而不是 peer 卡片（见 MessageList 的 isAgentNotice 分支）。
     *
     * @param pm 入队记录（非 null）
     * @return 可直接交 {@link AgentTranscript#recordSidechainTranscript} 的 JSONL 行
     */
    public static Map<String, Object> toTranscriptMap(AgentNameRegistry.PendingAgentMessage pm) {
        return toTranscriptMap(pm, pm != null ? pm.id() : null);
    }

    /**
     * 通知行 → transcript 落盘 Map（允许覆盖 uuid · 终态补写的孤儿修复路径用）。
     *
     * @param pm           入队记录（非 null）
     * @param uuidOverride 落盘 uuid；null → 用 {@code pm.id()}
     * @return JSONL 行
     */
    public static Map<String, Object> toTranscriptMap(AgentNameRegistry.PendingAgentMessage pm,
                                                      String uuidOverride) {
        Map<String, Object> map = new LinkedHashMap<>();
        String uuid = uuidOverride != null && !uuidOverride.isBlank()
            ? uuidOverride : (pm != null ? pm.id() : null);
        if (uuid != null) {
            map.put("uuid", uuid);
        }
        map.put("role", "user");
        map.put("content", pm != null ? pm.text() : null);
        if (pm != null && pm.from() != null && !pm.from().isBlank()) {
            map.put("author", pm.from());
        }
        map.put("subtype", "agent_message");
        map.put("isMeta", true);
        if (pm != null && pm.summary() != null && !pm.summary().isBlank()) {
            map.put("summary", pm.summary());
        }
        map.put("timestamp", pm != null && pm.queuedAt() != null
            ? pm.queuedAt() : OffsetDateTime.now().toString());
        return map;
    }
}
