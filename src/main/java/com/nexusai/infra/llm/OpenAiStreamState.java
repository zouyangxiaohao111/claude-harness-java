package com.nexusai.infra.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 流式状态累积器 — 一行一个 SSE data 块的累积上下文。
 *
 * <p>由 {@link OpenAiSdkProvider} 共享，
 * 替代各自内部的 StreamState 内部类。
 */
public class OpenAiStreamState {

    /** 文本内容累积 */
    public final StringBuilder content = new StringBuilder();

    /** DeepSeek reasoning_content / Anthropic thinking 等"思考"流（独立于 content） */
    public final StringBuilder reasoning = new StringBuilder();

    /** key = tool_call index · LinkedHashMap 保留顺序（OpenAI 协议保证按 index 顺序） */
    public final Map<Integer, OpenAiToolCallAccumulator> toolCalls = new LinkedHashMap<>();

    /** 流结束原因: "stop" / "tool_calls" / "length" / null */
    public String finishReason = null;

    /** [D-4] requestId 兜底（DEC-RV-14a 请求侧自建 ID · SDK 0.25.0 无 withRawResponse，
     *  响应侧 request-id 头不可达）· 透传到 AssistantMessage.requestId 供 invokingRequestId 归因。 */
    public String requestId = null;

    /** DEC-04 · final chunk usage.prompt_tokens（stream_options.include_usage=true 时出现） */
    public long inputTokens = 0L;

    /** DEC-04 · final chunk usage.completion_tokens */
    public long outputTokens = 0L;

    /** DEC-04 · usage.prompt_tokens_details.cached_tokens（OpenAI cache read 等价） */
    public long cacheReadInputTokens = 0L;

    /** [B2-R1/R2] DeepSeek/openai-compatible 顶层 usage.prompt_cache_miss_tokens（cache creation 等价）·
     *  镜像非流式 extractUsage (:1062-1078) 的 additionalProperties 读取；final chunk usage 时填充。 */
    public long cacheCreationInputTokens = 0L;
}
