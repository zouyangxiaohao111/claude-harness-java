package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;

import java.util.List;
import java.util.Objects;

/**
 * LLM 单次流式调用的完整 assistant message · 对齐 OpenAI 协议的一个 response choice。
 *
 * <p>从流式响应中累积而来（per-message 而非 per-chunk），用于让
 * {@link com.nexusai.application.agent.LlmAgentLoop} 在收到完整 message 后
 * 检查 tool_calls 并决定是否继续 loop。
 *
 * <h2>对齐 CC query.ts line 834</h2>
 * <p>CC 在 streaming message handler 内 set {@code needsFollowUp = true}
 * （检测到 tool_use 块时）。本 record 是这个机制的 Java 端体现：
 * {@link #hasToolCalls()} 对应 CC 的 tool_use 块检测。
 *
 * <p><b>apiError（本 session ER-IMP-07 对齐）</b>：CC {@code AssistantMessage.apiError}
 * 是 provider 层归一化的消息级错误信号。claude.ts:2266-2292 把原始 stop_reason
 * {@code 'max_tokens'} / {@code 'model_context_window_exceeded'} 归一化为
 * {@code apiError: 'max_output_tokens'}（isApiErrorMessage=true）。循环层恢复判定
 * （query.ts:178 isWithheldMaxOutputTokens）只认这个消息级信号，不再依赖 finishReason
 * 字符串--Java 旧谓词 {@code "length".equals(finishReason)} 与 Anthropic raw
 * {@code 'max_tokens'} 不匹配（DC-21 根因）。
 *
 * <p><b>usage（DEC-04 数据源闭环 R2-USAGE + R32-06 嵌套字段补齐）</b>：对齐 CC {@code message.usage}
 * （BetaUsage 对象，agentToolUtils.ts:238-256 / finalizeAgentTool :355 直接透传）。
 * Java 端以 {@link AgentUsage} record 承载 7 子字段；Anthropic 从 API 响应解析 4 个 token
 * 字段 (input_tokens/output_tokens/cache_creation_input_tokens/cache_read_input_tokens) 并于
 * message_start usage 解析嵌套 3 字段 (server_tool_use/service_tier/cache_creation，对齐 CC
 * claude.ts:2947-2963 updateUsage)；OpenAI 无等价嵌套字段 → null（如实暴露缺口）。
 * 旧 scalar {@code outputTokens} 组件收敛为 {@code usage.outputTokens()} 投影
 * （32 处旧调用方签名不变，行为同源）。
 * 无 API usage 上报 → {@link AgentUsage#EMPTY} 零初始化哨兵（对齐 CC emptyUsage.ts:8）。
 *
 * @param content      累积的文本内容（可能为 null -- 纯 tool_call response）
 * @param finishReason OpenAI 的 stop / tool_calls / length · Anthropic raw stop_reason
 * @param toolCalls    LLM 发出的工具调用（可能为空 -- 纯文本 response）
 * @param reasoning    推理内容（reasoning content，可能为空）
 * @param apiError     消息级 API 错误信号（CC claude.ts:2274 original: apiError='max_output_tokens'；null=正常响应）
 * @param usage        本次 API 响应的 usage 对象（CC original: message.usage, agentToolUtils.ts:238-256；
 *                     非 null，无上报时为零初始化 EMPTY）
 * @param requestId    [RF-1] 父 assistant message 的 API request_id（CC original:
 *                     assistantMessage.requestId, AgentTool.tsx:723/:778；nullable，流式
 *                     provider 未捕获 request_id 时 null）
 */
public record AssistantMessage(
    String content,
    String finishReason,
    List<ToolUseBlock> toolCalls,
    String reasoning,
    String apiError,
    AgentUsage usage,
    String requestId
) {

    public AssistantMessage {
        if (finishReason == null) finishReason = "stop";
        if (toolCalls == null) toolCalls = List.of();
        if (reasoning == null) reasoning = "";
        if (usage == null) usage = AgentUsage.EMPTY; // 零初始化哨兵 · CC emptyUsage.ts:8
        // [RF-1] requestId nullable（CC assistantMessage.requestId 可为 undefined）——
        //   父 assistant message 的 API request_id，透传到子 agent 上下文 invokingRequestId 归因；
        //   流式 provider 未捕获 request_id 时 null。
    }

    /** 6-arg 便捷构造器（requestId=null · 兼容既有 6 参调用方：
     *  AnthropicSdkProvider / OpenAiSdkProvider / MockLlmProvider 等流式/非流式构建点）。 */
    public AssistantMessage(String content, String finishReason, List<ToolUseBlock> toolCalls,
                            String reasoning, String apiError, AgentUsage usage) {
        this(content, finishReason, toolCalls, reasoning, apiError, usage, null);
    }

    /**
     * 6-arg 便捷构造器（outputTokens scalar → usage 投影，兼容既有调用方）。
     *
     * <p>provider 只上报 output_tokens（无 input/cache）时使用：input/cache 字段 0。
     * CC BetaUsage 同字段语义（claude.ts:2214 message_delta usage.output_tokens）。
     *
     * @param outputTokens API 上报的实际输出 token 数 · CC original: usage.output_tokens；
     *                     0 = API 未上报或 provider 未提取
     */
    public AssistantMessage(String content, String finishReason, List<ToolUseBlock> toolCalls, String reasoning, String apiError, long outputTokens) {
        this(content, finishReason, toolCalls, reasoning, apiError,
            new AgentUsage(0L, outputTokens, null, null, null, null, null));
    }

    /** 5-arg 委托（outputTokens=0，兼容既有调用方）。 */
    public AssistantMessage(String content, String finishReason, List<ToolUseBlock> toolCalls, String reasoning, String apiError) {
        this(content, finishReason, toolCalls, reasoning, apiError, 0L);
    }

    /** 4-arg 委托（apiError=null，outputTokens=0，兼容既有调用方）。 */
    public AssistantMessage(String content, String finishReason, List<ToolUseBlock> toolCalls, String reasoning) {
        this(content, finishReason, toolCalls, reasoning, null, 0L);
    }

    /** 兼容 3-arg 调用 (没 reasoning，apiError=null，outputTokens=0)。 */
    public AssistantMessage(String content, String finishReason, List<ToolUseBlock> toolCalls) {
        this(content, finishReason, toolCalls, "", null, 0L);
    }

    /** API 上报的实际输出 token 数 · CC original: usage.output_tokens（AgentUsage 投影）. */
    public long outputTokens() {
        return usage.outputTokens();
    }

    /** API 上报的实际输入 token 数 · CC original: usage.input_tokens（AgentUsage 投影）. */
    public long inputTokens() {
        return usage.inputTokens();
    }

    /** API 上报的 cache read token 数 · CC original: usage.cache_read_input_tokens（AgentUsage 投影，可 null）. */
    public Long cacheReadInputTokens() {
        return usage.cacheReadInputTokens();
    }

    /** API 上报的 cache creation token 数 · CC original: usage.cache_creation_input_tokens（AgentUsage 投影，可 null）. */
    public Long cacheCreationInputTokens() {
        return usage.cacheCreationInputTokens();
    }

    /**
     * 是否为 max_output_tokens 截断错误 · 对齐 CC query.ts:178
     * {@code isWithheldMaxOutputTokens = msg?.type==='assistant' && msg.apiError==='max_output_tokens'}。
     *
     * <p>唯一可信的触发信号是消息级 {@code apiError}（provider 层归一化产物），
     * 不依赖 finishReason 字符串--这是对 DC-21（Java 独有 {@code "length"} 谓词
     * 与 Anthropic raw 'max_tokens' 不匹配）的修复。
     */
    public boolean isMaxOutputTokensError() {
        return "max_output_tokens".equals(apiError);
    }

    /** 是否包含工具调用（CC line 834 的检测条件）。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 文本长度（含空字符串检查）。 */
    public int contentLength() {
        return content == null ? 0 : content.length();
    }

    @Override
    public String toString() {
        return "AssistantMessage{finishReason=" + finishReason
            + ", contentLen=" + contentLength()
            + ", toolCalls=" + toolCalls.size()
            + ", usage=" + usage + "}";
    }
}
