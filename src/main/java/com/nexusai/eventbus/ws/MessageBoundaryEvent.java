package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * [snip-persist-field] Snip 裁剪边界事件 · 后端把 SnipTool 注入的 snip_boundary 消息
 * （removedUuids = 被裁剪消息 id 列表）实时推送前端，供「被裁剪消息右上角」标注「已裁剪」。
 *
 * <p><b>时机</b>：replayAndPersist 落库 snip_boundary 时同步推送（turn 结束统一收口，模型
 * 回复完成即标注，≈实时）。F5 持久由 GET /messages 返回 boundary 消息（snipMetadata）兜底——
 * 前端实时（本事件）+ F5（重拉解析）共用同一「按 removedUuids 标注消息」逻辑。
 *
 * <p><b>topic</b>: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic，对齐 CC
 * 会话单一事件流）。前端 useChatSocket 收到 {@code type === 'message.boundary'} 后把
 * {@code removedUuids} 并入会话 snippedIds 集合 → Message 组件按 id 标注角标。
 *
 * @see com.nexusai.application.agent.compact.SnipCompactor#SUBTYPE_SNIP_BOUNDARY
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageBoundaryEvent extends StreamEvent {

    /** 被裁剪的消息 id 列表（snipMetadata.removedUuids，snipCompact.ts:99-106 / snipProjection.ts:31） */
    private final List<String> removedUuids;

    /** 摘要文本（boundary content = 模型传入的 reason / 默认 "Snipped N messages"）· 可选展示 */
    private final String summary;

    public MessageBoundaryEvent(String sessionId, String userMessageId,
                                List<String> removedUuids, String summary) {
        super("message.boundary", sessionId, userMessageId);
        this.removedUuids = removedUuids;
        this.summary = summary;
    }

    public List<String> getRemovedUuids() { return removedUuids; }
    public String getSummary() { return summary; }
}
