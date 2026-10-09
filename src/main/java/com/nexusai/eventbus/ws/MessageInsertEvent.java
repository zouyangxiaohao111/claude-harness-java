package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * [sm-boundary-reload] 服务端插入行事件 · 后端把压缩（SM/auto）<b>落库成功</b>的 compact boundary 行
 * 实时推给前端，前端按行 {@code id} 幂等插入消息列表 → 聊天区<b>即时</b>出现「已压缩 · 对话历史已总结」
 * 分割线（本轮压缩落库那一刻，不必等 turn 收尾 / F5）。
 *
 * <p><b>与 {@code message.boundary}（snip）的区别（勿混用）</b>：{@code message.boundary} 只带
 * {@code removedUuids} 增量（前端给<b>已有</b>消息打「已裁剪」角标，不新增行）；本事件携带
 * <b>完整行体</b>（{@code messages}）—— 因为 compact boundary 是一条<b>新行</b>，前端手上没有它，
 * 只能整行插进去。二者都是「落库即推」，但载体语义相反（标注既有行 vs 插入新行）。
 *
 * <p><b>时机</b>：{@code ChatService.compactPersistListener} 内、append-only 落库返回后（同点即推）。
 * F5 持久由 GET /messages 返回 boundary 行兜底 —— 前端幂等（同 id 不重复插）保证「实时 + 重拉」收敛。
 *
 * <p><b>载荷来源（如实）</b>：行体 = 落库返回的<b>内存归一化 DTO</b>（id 与 DB 同源 ——
 * {@code appendPostCompactMessages} 的返回，即前端幂等收敛键；不是另起一次 DB 查询）。因此
 * 时间类字段（{@code time}/{@code createdAt} 等）呈现的是内存快照时刻的值，<b>以对账层重拉
 * （GET /messages）的结果为准</b>；前端不应用本事件的时间字段做排序/分组依据。
 *
 * <p><b>topic</b>: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic，对齐 CC 会话单一事件流）。
 * 前端 useChatSocket 收到 {@code type === 'message.insert'} 后把 {@code messages} 落进会话列表
 * （id 幂等 + 插列表尾）。{@code userMessageId} 恒 null（本事件不承载 turn 归属：boundary 行的归属
 * 在其自身字段里，前端按行处理，不需要 flow 锚点）。
 *
 * @see MessageBoundaryEvent
 * @see com.nexusai.application.agent.compact.BoundaryReader#isCompactBoundaryMessage(ChatMessageDto)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageInsertEvent extends StreamEvent {

    /** 要插入的完整消息行（本轮落库成功的 compact boundary 行；判据 BoundaryReader.isCompactBoundaryMessage 单源） */
    private final List<ChatMessageDto> messages;

    public MessageInsertEvent(String sessionId, List<ChatMessageDto> messages) {
        super("message.insert", sessionId, null);
        this.messages = messages;
    }

    public List<ChatMessageDto> getMessages() { return messages; }
}
