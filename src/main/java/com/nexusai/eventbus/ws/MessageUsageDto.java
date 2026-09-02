package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.application.agent.tool.AgentUsage;

import java.util.List;

/**
 * {@link MessageCompleteEvent#usage} 载荷 · snake_case 出站 DTO（对齐 CC result.usage /
 * message.usage，agentToolUtils.ts:238-256 + emptyUsage.ts:19-21）。
 *
 * <p><b>为何独立 DTO（非直接序列化 AgentUsage）</b>：AgentUsage 本体经 ChatMessageDto 出站
 * （camelCase 投影），给 agentToolResult.usage 透传；若直接给 AgentUsage 加
 * {@code @JsonNaming(SnakeCase)} 会连带改变 ChatMessageDto 既有出站契约（风险 L-1 审计点）。
 * 本 DTO 以 {@code @JsonProperty} 显式 snake_case 映射，字段全集照抄 CC usage schema，零影响 AgentUsage。
 *
 * <p>顶层 {@code @JsonInclude(NON_NULL)}：与 MessageCompleteEvent 类级 NON_NULL 一致
 * （Jackson 根配置向下传播，null 嵌套字段省略）。
 *
 * <p>CC original 行号：input_tokens (agentToolUtils.ts:239) / output_tokens (:240) /
 * cache_creation_input_tokens (:241) / cache_read_input_tokens (:242) /
 * server_tool_use (:243-248) / service_tier (:249) / cache_creation (:250-255) /
 * inference_geo (emptyUsage.ts:19) / iterations (:20) / speed (:21) /
 * cache_deleted_input_tokens（message.usage 顶层，microCompact.ts:374）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageUsageDto(
    @JsonProperty("input_tokens") long inputTokens,
    @JsonProperty("output_tokens") long outputTokens,
    @JsonProperty("cache_creation_input_tokens") Long cacheCreationInputTokens,
    @JsonProperty("cache_read_input_tokens") Long cacheReadInputTokens,
    @JsonProperty("server_tool_use") ServerToolUseDto serverToolUse,
    @JsonProperty("service_tier") String serviceTier,
    @JsonProperty("cache_creation") CacheCreationDto cacheCreation,
    @JsonProperty("inference_geo") String inferenceGeo,
    @JsonProperty("iterations") List<String> iterations,
    @JsonProperty("speed") String speed,
    @JsonProperty("cache_deleted_input_tokens") Long cacheDeletedInputTokens,
    // [B7-R9] 后端测输出解码耗时 ms · 净新增字段（非 CC 对齐）
    // WHY: 前端 t/s = output_tokens*1000/decode_ms（F4）。CC usage schema 无 decode_ms 对应
    //     （agentToolUtils.ts:238-256 7 子字段 + emptyUsage.ts:19-21 + cache_deleted_input_tokens
    //     均无耗时字段）；值源 = ChatMessageDto.decodeMs（LlmAgentLoop firstTokenMs 打点）。
    //     null → @JsonInclude(NON_NULL) 省略（前端 null=无数据）。
    @JsonProperty("decode_ms") Long decodeMs
) {

    /** CC original: server_tool_use（agentToolUtils.ts:243-248）。 */
    public record ServerToolUseDto(
        @JsonProperty("web_search_requests") long webSearchRequests,
        @JsonProperty("web_fetch_requests") long webFetchRequests
    ) {}

    /** CC original: cache_creation（agentToolUtils.ts:250-255）。 */
    public record CacheCreationDto(
        @JsonProperty("ephemeral_1h_input_tokens") long ephemeral1hInputTokens,
        @JsonProperty("ephemeral_5m_input_tokens") long ephemeral5mInputTokens
    ) {}

    /**
     * 从 AgentUsage 映射（null → null，由 @JsonInclude(NON_NULL) 省略）。
     *
     * @param u        provider 解析的 usage（null → null）
     * @param decodeMs 后端测输出解码耗时 ms（B7-R9；null → NON_NULL 省略）
     */
    public static MessageUsageDto from(AgentUsage u, Long decodeMs) {
        if (u == null) {
            return null;
        }
        return new MessageUsageDto(
            u.inputTokens(),
            u.outputTokens(),
            u.cacheCreationInputTokens(),
            u.cacheReadInputTokens(),
            u.serverToolUse() != null
                ? new ServerToolUseDto(u.serverToolUse().webSearchRequests(), u.serverToolUse().webFetchRequests())
                : null,
            u.serviceTier(),
            u.cacheCreation() != null
                ? new CacheCreationDto(u.cacheCreation().ephemeral1hInputTokens(), u.cacheCreation().ephemeral5mInputTokens())
                : null,
            u.inferenceGeo(),
            u.iterations(),
            u.speed(),
            u.cacheDeletedInputTokens(),
            decodeMs);
    }
}
