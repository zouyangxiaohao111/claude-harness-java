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

    /** [D1 usage-source] 用户自己的请求（含 busy-queued 排队消息）· 前端底部数字（缓存%/上下文/t·s）只统计它。 */
    public static final String SOURCE_USER = "user";
    /**
     * [D1 usage-source] 后台来源 · CronIdleExecutor 起的 cron 调度 / 任务通知 run（复用会话 stream topic
     * 推本事件，但<b>不是用户自己的请求</b>）。
     *
     * <p>WHY 需要这个标记：实测 63% 的 message.usage 事件出自 {@code cron-idle-*} 线程，前端底部
     * 「缓存利用率 / 当前上下文」取「最后一条带 usage 的条目」⇒ 被后台任务那轮顶掉（数字与用户操作
     * 无关地跳变）。标记在 usage-push 时点由 run 级来源（{@code AgentState.usageSource}）带入。
     */
    public static final String SOURCE_BACKGROUND = "background";

    private final String assistantMessageId;
    /**
     * 该条 assistant 消息的 usage（snake_case 对象，复用 {@link MessageUsageDto}，含
     * cache_read/creation + decode_ms）· null → NON_NULL 省略。
     */
    private final MessageUsageDto usage;
    /**
     * [D1 usage-source] 本条 usage 的来源（{@link #SOURCE_USER} / {@link #SOURCE_BACKGROUND}）·
     * null → NON_NULL 省略（= 前端按 {@code SOURCE_USER} 计，向后兼容：旧帧无该字段不得让底部数字消失）。
     */
    private final String source;
    /** 窗口权威值（tokens）· CC original: context.ts:118-144 窗口权威值（同 complete 事件）。 */
    private final long contextWindow;
    /** 当前上下文用量（tokens）· CC original: context.ts current_usage（协议分派，同 complete 事件）。 */
    private final long contextTokensUsed;
    /** 上下文余量百分比（0-100，Integer 可空）· null → NON_NULL 省略。 */
    private final Integer percentLeft;

    public MessageUsageEvent(String sessionId, String userMessageId, String assistantMessageId,
                             MessageUsageDto usage, long contextWindow, long contextTokensUsed,
                             Integer percentLeft) {
        this(sessionId, userMessageId, assistantMessageId, usage, contextWindow, contextTokensUsed,
            percentLeft, null);
    }

    /**
     * [D1 usage-source] 带来源标记的构造器（additive；原 7 参构造器保留 → 既有调用方零改动）。
     *
     * @param source 来源（{@link #SOURCE_USER} / {@link #SOURCE_BACKGROUND}；null = 不标记 → 字段省略）
     */
    public MessageUsageEvent(String sessionId, String userMessageId, String assistantMessageId,
                             MessageUsageDto usage, long contextWindow, long contextTokensUsed,
                             Integer percentLeft, String source) {
        super("message.usage", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.usage = usage;
        this.source = source;
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

    /**
     * [D1 usage-source] 带来源的静态工厂（additive · 7 参重载保留）。
     *
     * @param source {@link #SOURCE_USER} / {@link #SOURCE_BACKGROUND}（null → 不标记，字段省略）
     */
    public static MessageUsageEvent of(String sessionId, String userMessageId, String assistantMessageId,
                                       MessageUsageDto usage, long contextWindow, long contextTokensUsed,
                                       Integer percentLeft, String source) {
        return new MessageUsageEvent(sessionId, userMessageId, assistantMessageId,
            usage, contextWindow, contextTokensUsed, percentLeft, source);
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public MessageUsageDto getUsage() { return usage; }
    /** [D1 usage-source] 来源标记（可能为 null：旧调用方 / 未标记路径 → 前端按 user 计）。 */
    public String getSource() { return source; }
    public long getContextWindow() { return contextWindow; }
    public long getContextTokensUsed() { return contextTokensUsed; }
    public Integer getPercentLeft() { return percentLeft; }
}
