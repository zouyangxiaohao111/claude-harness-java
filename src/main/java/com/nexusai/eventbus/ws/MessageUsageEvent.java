package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 逐消息 usage 推送 · 每条 assistant 消息流式结束即发（实时）· [usage-push] 新增事件。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic，与
 * {@link MessageCompleteEvent} 同 topic；前端靠 {@code type} 区分）。
 *
 * <p><b>WHY 不复用 message.complete（规避提前退订）</b>: CC 每条 assistant 消息完成即把该消息
 * <b>自带 usage</b> 发 UI（Open-ClaudeCode/src/services/api/claude.ts:2244-2248 写回 usage）；
 * Java 旧实现只 turn 末发一次 message.complete（ChatService:928），turn 内每条 assistant 的 usage
 * 已挂 state 消息却从不推前端。前端把 message.complete 当 <b>turn 终态</b>（onSessionDone
 * 退订 activeStreams），若复用 complete 名做 per-round 会提前退订中断后续轮次流式 —— 故新增
 * {@code type="message.usage"}：{@code isComplete} 天然不匹配 → 前端消息级完成<b>绝不退订</b>
 * （useChatSocket dispatchEvent 在 isComplete 分支前匹配 message.usage，不调 onSessionDone）。
 *
 * <p><b>与 complete 的关系</b>: 本事件携带<b>该条 assistant 消息</b>的 usage + 上下文快照
 * （= 对齐 CC 消息自带 usage）；turn 末 complete.usage 仍为<b>本轮累计</b>（state.runUsage，
 * 对齐 CC result.usage）。同 topic FIFO 顺序天然 —— 消息级完成先于 turn 末 complete。
 *
 * <p>字段命名：{@code assistantMessageId}(=turnAssistantId，前端块 id 同源)、{@code usage}
 * （复用 {@link MessageUsageDto}，含 cache_read/creation + decode_ms）、上下文三字段
 * {@code contextWindow} / {@code contextTokensUsed} / {@code percentLeft}（camel，对齐 complete
 * 事件 context 字段命名 —— 前端 useMemo([msgs,...]) 重算缓存%/上下文条直接消费）。
 *
 * <p>CC original 行号：message.usage 写回 UI（claude.ts:2244-2248）/ 消息自带 usage
 * （agentToolUtils.ts:238-256）/ result.usage 累计（QueryEngine.ts:790-816/:861）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageUsageEvent extends StreamEvent {

    private final String assistantMessageId;
    /**
     * 该条 assistant 消息的 usage（snake_case 对象，复用 {@link MessageUsageDto}，含
     * cache_read/creation + decode_ms）· null → NON_NULL 省略。
     */
    private final MessageUsageDto usage;
    /** 窗口权威值（tokens）· CC original: context.ts:118-144 窗口权威值（同 complete 事件）。 */
    private final long contextWindow;
    /** 当前上下文用量（tokens）· CC original: context.ts current_usage（协议分派，同 complete 事件）。 */
    private final long contextTokensUsed;
    /** 上下文余量百分比（0-100，Integer 可空）· null → NON_NULL 省略。 */
    private final Integer percentLeft;

    public MessageUsageEvent(String sessionId, String userMessageId, String assistantMessageId,
                             MessageUsageDto usage, long contextWindow, long contextTokensUsed,
                             Integer percentLeft) {
        super("message.usage", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.usage = usage;
        this.contextWindow = contextWindow;
        this.contextTokensUsed = contextTokensUsed;
        this.percentLeft = percentLeft;
    }

    /**
     * 便捷静态工厂（对齐 MessageChunkEvent.of / MessageCompleteEvent 构造风格）。
     *
     * @param sessionId          会话 ID
     * @param userMessageId      触发本轮响应的 user 消息 id（消息链推导，对齐 chunk 事件）
     * @param assistantMessageId 本条 assistant 消息 id（=turnAssistantId，前端块 id 同源）
     * @param usage              本条 usage DTO（null → 整事件跳过，调用方已守卫）
     * @param contextWindow      上下文窗口（模型 max_context_tokens 回落 1M）
     * @param contextTokensUsed  当前上下文用量（协议分派）
     * @param percentLeft        余量百分比（clamp ≥0；null → NON_NULL 省略）
     * @return message.usage 事件
     */
    public static MessageUsageEvent of(String sessionId, String userMessageId, String assistantMessageId,
                                       MessageUsageDto usage, long contextWindow, long contextTokensUsed,
                                       Integer percentLeft) {
        return new MessageUsageEvent(sessionId, userMessageId, assistantMessageId,
            usage, contextWindow, contextTokensUsed, percentLeft);
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public MessageUsageDto getUsage() { return usage; }
    public long getContextWindow() { return contextWindow; }
    public long getContextTokensUsed() { return contextTokensUsed; }
    public Integer getPercentLeft() { return percentLeft; }
}
