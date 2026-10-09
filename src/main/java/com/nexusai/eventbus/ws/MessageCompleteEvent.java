package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.application.agent.cost.CostTracker;

import java.util.Map;

/**
 * 响应完成 · 流结束信号
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 *
 * <p>字段命名贴合前端协议：{@code content} / {@code reasoning} / {@code finishReason}
 * / {@code inputTokens} / {@code outputTokens}（块 3 现有投影，兼容旧前端保留）
 *
 * <p><b>[V-TOK 实施] 照抄 CC result 事件结构</b>（对齐 CC result 事件，见 plan §一 目标 JSON）：
 * <ul>
 *   <li>{@code usage}（snake_case 对象）＝本轮累计 usage（= CC result.usage / message.usage）</li>
 *   <li>{@code total_cost_usd}＝会话累计花费（值=人民币元，字段名对齐 CC）</li>
 *   <li>{@code modelUsage}（camelCase 8 字段 Map）＝按模型用量桶（CC result.modelUsage）</li>
 *   <li>{@code duration_ms} / {@code num_turns}＝轮耗时（ms，净新增近似）/ 轮数</li>
 *   <li>{@code contextWindow} / {@code contextTokensUsed} / {@code percentLeft}＝上下文快照
 *       （常驻每轮推，对齐 CC StatusLine / context.ts:118-144）</li>
 * </ul>
 *
 * <p>CC original 行号：result.usage (state.ts:704-710) / result.total_cost_usd
 * (state.ts:704-710) / result.modelUsage (cost-tracker.ts:29-38) / result.duration_ms /
 * result.num_turns / context.ts:118-144（current_usage = input + cache_creation + cache_read，不含 output）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageCompleteEvent extends StreamEvent {

    private final String assistantMessageId;
    private final String content;
    private final String reasoning;
    private final String finishReason;       // stop | length | tool_calls | content_filter | error
    private final Integer inputTokens;
    private final Integer outputTokens;
    /**
     * 后端测推理耗时（ms）· 净新增字段，非 CC 对齐（CC 无 reasoning 计时字段，自验
     * sessionStorage.ts:1706/2209-2252 的 turn_duration 非推理耗时）。语义 = 推理流首 SSE
     * reasoning chunk 至推理阶段结束（首 content chunk 或 onAssistantMessage）的时间跨度；
     * null = 无 reasoning（@JsonInclude NON_NULL 省略，前端容错）。
     */
    private final Long reasoningDurationMs;
    /**
     * 本轮累计 usage（snake_case 对象）· CC original: {@code result.usage}（state.ts:704-710 /
     * agentToolUtils.ts:238-256）。null = 无 usage 上报（NON_NULL 省略）。
     */
    private final MessageUsageDto usage;
    /**
     * 会话累计花费 · 值=人民币元（用户拍板：字段名对齐 CC、不换算 USD）。
     * CC original: {@code result.total_cost_usd}（state.ts:704-710）。
     */
    @JsonProperty("total_cost_usd")
    private final double totalCostUsd;
    /**
     * 按模型 8 字段 camelCase 用量桶 · CC original: {@code result.modelUsage}
     * （cost-tracker.ts:29-38）。null = 无累计（NON_NULL 省略）。
     */
    private final Map<String, CostTracker.ModelUsage> modelUsage;
    /**
     * 本轮耗时（ms）· 净新增近似（Java 无 API 累计计时，用 turn 墙钟；非 CC API duration_ms）。
     * CC original: {@code result.duration_ms}。
     */
    @JsonProperty("duration_ms")
    private final long durationMs;
    /**
     * 本轮 turn 数 · CC original: {@code result.num_turns}。
     */
    @JsonProperty("num_turns")
    private final int numTurns;
    /**
     * 窗口权威值（tokens）· CC original: {@code modelUsage[model].contextWindow}
     * （context.ts:118-144 窗口权威值，result 事件才给）。
     */
    private final long contextWindow;
    /**
     * 当前上下文用量（tokens）· CC original: {@code context.ts} current_usage =
     * input_tokens + cache_creation_input_tokens + cache_read_input_tokens（不含 output）。
     */
    private final long contextTokensUsed;
    /**
     * 上下文余量百分比（0-100，Integer 可空）· CC original: context.ts 百分比 =
     * current_usage / contextWindow；null → NON_NULL 省略。
     */
    private final Integer percentLeft;
    /**
     * [sm-boundary-reload] 本轮是否发生过<b>落库成功</b>的压缩（turn 级）· 净新增字段，非 CC 对齐
     * （CC 无此出站字段；CC 的同名状态 {@code STATE.pendingPostCompaction} 是<b>进程内</b>且被
     * 「压缩后首个 API success」消费，见 AgentState.turnCompacted javadoc 的差异说明）。
     *
     * <p><b>语义</b>：true = 本 turn 有压缩结果 append-only 落库成功（值 = 装配点
     * {@code AgentState.consumeTurnCompacted()}，读+清一次）。前端据此在收尾（finalizeBlocks）后
     * <b>重拉尾页</b>补上「已压缩 · 对话历史已总结」分割线（对账层兜底；实时层是同点推送的
     * {@code message.insert}）。恒出站（primitive，NON_NULL 不省略）→ 旧前端忽略未知字段即可。
     */
    private final boolean compacted;

    public MessageCompleteEvent(String sessionId, String userMessageId,
                                String assistantMessageId, String content, String reasoning,
                                String finishReason, Integer inputTokens, Integer outputTokens,
                                Long reasoningDurationMs) {
        this(sessionId, userMessageId, assistantMessageId, content, reasoning, finishReason,
            inputTokens, outputTokens, reasoningDurationMs,
            null, 0.0, null, 0L, 0, 0L, 0L, null, false);
    }

    /**
     * [sm-boundary-reload] 向后兼容构造器：保留既有 17 参调用方（旧 canonical 形状：…contextWindow,
     * contextTokensUsed, percentLeft），compacted 默认 {@code false}。
     *
     * <p>WHY：{@code compacted} 是本批新增的<b>第 18 参</b>（⛔ 既有参数序不动）。既有调用方
     * （如 {@code MessageCompleteEventSerializationTest} 的既有用例）按位置传 17 参 —— 不给本重载
     * 就必须逐个改调用点，且「忘记补参」会静默编译失败/错位。默认 false = 改前无 compacted 时的行为。
     */
    public MessageCompleteEvent(String sessionId, String userMessageId,
                                String assistantMessageId, String content, String reasoning,
                                String finishReason, Integer inputTokens, Integer outputTokens,
                                Long reasoningDurationMs,
                                MessageUsageDto usage, double totalCostUsd,
                                Map<String, CostTracker.ModelUsage> modelUsage,
                                long durationMs, int numTurns,
                                long contextWindow, long contextTokensUsed, Integer percentLeft) {
        this(sessionId, userMessageId, assistantMessageId, content, reasoning, finishReason,
            inputTokens, outputTokens, reasoningDurationMs,
            usage, totalCostUsd, modelUsage, durationMs, numTurns,
            contextWindow, contextTokensUsed, percentLeft, false);
    }

    public MessageCompleteEvent(String sessionId, String userMessageId,
                                String assistantMessageId, String content, String reasoning,
                                String finishReason, Integer inputTokens, Integer outputTokens,
                                Long reasoningDurationMs,
                                MessageUsageDto usage, double totalCostUsd,
                                Map<String, CostTracker.ModelUsage> modelUsage,
                                long durationMs, int numTurns,
                                long contextWindow, long contextTokensUsed, Integer percentLeft,
                                boolean compacted) {
        super("message.complete", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.content = content;
        this.reasoning = reasoning;
        this.finishReason = finishReason;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.reasoningDurationMs = reasoningDurationMs;
        this.usage = usage;
        this.totalCostUsd = totalCostUsd;
        this.modelUsage = modelUsage;
        this.durationMs = durationMs;
        this.numTurns = numTurns;
        this.contextWindow = contextWindow;
        this.contextTokensUsed = contextTokensUsed;
        this.percentLeft = percentLeft;
        this.compacted = compacted;
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public String getContent() { return content; }
    public String getReasoning() { return reasoning; }
    public String getFinishReason() { return finishReason; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Long getReasoningDurationMs() { return reasoningDurationMs; }
    public MessageUsageDto getUsage() { return usage; }
    public double getTotalCostUsd() { return totalCostUsd; }
    public Map<String, CostTracker.ModelUsage> getModelUsage() { return modelUsage; }
    public long getDurationMs() { return durationMs; }
    public int getNumTurns() { return numTurns; }
    public long getContextWindow() { return contextWindow; }
    public long getContextTokensUsed() { return contextTokensUsed; }
    public Integer getPercentLeft() { return percentLeft; }
    public boolean isCompacted() { return compacted; }
}
