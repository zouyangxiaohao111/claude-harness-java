package com.nexusai.application.agent.tool;

import java.util.List;

/**
 * 子 Agent usage 对象 · 对齐 CC {@code agentToolUtils.ts:238-256} agentToolResultSchema 的
 * {@code usage} 7 子字段.
 *
 * <p>CC 真源 (Pattern #9, 已 grep 自验):<pre>
 * usage: z.object({
 *   input_tokens: z.number(),                       // :239
 *   output_tokens: z.number(),                      // :240
 *   cache_creation_input_tokens: z.number().nullable(),   // :241
 *   cache_read_input_tokens: z.number().nullable(),       // :242
 *   server_tool_use: { web_search_requests, web_fetch_requests }.nullable(), // :243-248
 *   service_tier: z.enum(['standard','priority','batch']).nullable(),       // :249
 *   cache_creation: { ephemeral_1h_input_tokens, ephemeral_5m_input_tokens }.nullable(), // :250-255
 * })</pre>
 *
 * <p>finalizeAgentTool (agentToolUtils.ts:355) 直接透传 {@code lastAssistantMessage.message.usage}
 * 到 AgentToolResult.usage；totalTokens 单独由 {@code getTokenCountFromUsage} (:319) 计算.
 *
 * <p>[DEC-04 数据源闭环 R2-USAGE][R32-06 嵌套字段补齐] Java 端从 3 个 LLM provider
 * (Anthropic/OpenAI/Mock) 响应解析 usage 填充本 record:
 * <ul>
 *   <li>AnthropicSdkProvider: 4 token 字段 (input_tokens/output_tokens/cache_creation_input_tokens/
 *       cache_read_input_tokens) 从 SDK message_start/message_delta usage 解析 + 嵌套 3 字段
 *       (server_tool_use/service_tier/cache_creation) 从 message_start usage 的 SDK Optional 访问器
 *       ({@code Usage.serverToolUse()}/{@code serviceTier()}/{@code cacheCreation()}) 解析
 *       (agentToolUtils.ts:243-255)。</li>
 *   <li>OpenAiSdkProvider: 仅 streaming final chunk usage 解析
 *       (prompt_tokens/completion_tokens/prompt_tokens_details.cached_tokens →
 *       input_tokens/output_tokens/cache_read_input_tokens); non-streaming ChatCompletion 路径
 *       (chatWithRaw/chatWithOptions) 未解析 usage → 不填充本 record (已登记缺口)。OpenAI usage 无
 *       server_tool_use/service_tier/cache_creation 等价 → 嵌套 3 字段 null。</li>
 *   <li>Mock: MockLlmProvider 经 setMockUsage 直接注入完整 AgentUsage。</li>
 * </ul>
 * 数据流: provider → AssistantMessage.usage → ChatMessageDto.usage →
 * SubagentExecutor.extractUsageFromMessages → SubagentResult.usage (对齐 CC message.usage 透传链).
 * 嵌套字段 (server_tool_use / service_tier / cache_creation) OpenAI/Mock 无等价 → null,
 * 如实暴露缺口 (S4-2b), 不伪造.
 *
 * <p><b>[IMP-C4] EMPTY_USAGE 10 字段补齐</b>（REQ-G3-2-4 / 组 3-2）: CC emptyUsage.ts:19-21 顶层
 * {@code inference_geo} / {@code iterations} / {@code speed} 已建模（E7 ✗ 关闭）。
 *
 * <p>WHY record (规则 11, 与 SubagentResult / AgentRunOptions 同风格): CC 无类, JSON 对象字面量
 * → Java idiomatic record; 嵌套对象拆独立 record 保持类型安全.
 *
 * @param inputTokens              CC original: input_tokens (agentToolUtils.ts:239)
 * @param outputTokens             CC original: output_tokens (agentToolUtils.ts:240)
 * @param cacheCreationInputTokens CC original: cache_creation_input_tokens (agentToolUtils.ts:241, nullable)
 * @param cacheReadInputTokens     CC original: cache_read_input_tokens (agentToolUtils.ts:242, nullable)
 * @param serverToolUse            CC original: server_tool_use (agentToolUtils.ts:243-248, nullable)
 * @param serviceTier              CC original: service_tier (agentToolUtils.ts:249, nullable enum 'standard'|'priority'|'batch')
 * @param cacheCreation            CC original: cache_creation (agentToolUtils.ts:250-255, nullable)
 * @param inferenceGeo             CC original: inference_geo (emptyUsage.ts:19, 零初始化 '')
 * @param iterations               CC original: iterations (emptyUsage.ts:20, 零初始化 [])
 * @param speed                    CC original: speed (emptyUsage.ts:21, 零初始化 'standard')
 * @param cacheDeletedInputTokens  CC original: cache_deleted_input_tokens（message.usage 顶层字段，
 *     microCompact.ts:374；Anthropic API 累计/sticky —— 上次 cache_edits 删除的 input token 数）。
 *     非 agentToolResultSchema.usage 7 子字段（agentToolUtils.ts:238-256），而是 CC 从
 *     {@code lastAsst.message.usage} 直接读取的扩展字段（microCompact.ts:372-383）；Java 以此
 *     nullable 扩展承载（Provider 提取后 → ChatMessageDto.usage() → MicroCompactor baseline/累计）。
 *     SDK 无等价访问器，经 {@code usage.additionalProperties().get("cache_deleted_input_tokens")} 解析；
 *     OpenAI/Mock 无等价 → null（如实暴露缺口）。
 */
public record AgentUsage(
        long inputTokens,
        long outputTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        ServerToolUse serverToolUse,
        String serviceTier,
        CacheCreation cacheCreation,
        String inferenceGeo,
        List<String> iterations,
        String speed,
        Long cacheDeletedInputTokens
) {
    /**
     * 便捷构造器（7 参）· 3 个新增 EMPTY_USAGE 字段按 CC emptyUsage.ts:19-21 零初始化默认
     * （inference_geo='' / iterations=[] / speed='standard'）。
     *
     * <p>WHY: Anthropic/OpenAI/Mock provider 解析路径无这些字段（SDK 无等价访问器），
     * 缺省 = CC EMPTY 零初始化形状，避免 10+ 构造点逐处重复默认值（对齐规则 5 单点声明）。
     */
    public AgentUsage(
            long inputTokens,
            long outputTokens,
            Long cacheCreationInputTokens,
            Long cacheReadInputTokens,
            ServerToolUse serverToolUse,
            String serviceTier,
            CacheCreation cacheCreation) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
            serverToolUse, serviceTier, cacheCreation, "", List.of(), "standard", null);
    }
    /**
     * 零初始化 usage 哨兵 · 对齐 CC {@code EMPTY_USAGE} (emptyUsage.ts:8-22, 10 顶层字段全零初始化,
     * 含嵌套 server_tool_use/service_tier/cache_creation 零初始化, service_tier='standard').
     *
     * <p><b>[IMP-C4] 10 顶层字段等价</b>（REQ-G3-2-4 / E7 ✗ 关闭）: 4 token 字段 0, 嵌套
     * ServerToolUse/CacheCreation 零初始化 (非 null), serviceTier='standard'（emptyUsage.ts:13）,
     * inferenceGeo=''（:19）、iterations=[]（:20）、speed='standard'（:21）。CC 消费点:
     * QueryEngine.ts:206/658/791, claude.ts:1765/1864/2822, print.ts:2438/4856,
     * bridgeMessaging.ts:410, forkedAgent.ts:504/564.
     */
    public static final AgentUsage EMPTY =
        new AgentUsage(0L, 0L, 0L, 0L,
            new ServerToolUse(0L, 0L), "standard", new CacheCreation(0L, 0L),
            "", List.of(), "standard", null);

    /**
     * 从 ChatMessageDto 的 inputTokens/outputTokens 2 字段投影 usage · 对齐 CC finalizeAgentTool
     * usage 透传的 Java 旧数据源. [DEC-04] 后主路径是 provider 解析的完整 usage (ChatMessageDto.usage()),
     * 本工厂保留用于 DB 持久化/旧构造消息 (仅 2 字段) 的投影回退. 嵌套字段 (cache_creation /
     * server_tool_use / service_tier) 为 null, 如实暴露缺口, 不伪造.
     *
     * @param inputTokens  LLM provider 响应的 input tokens (可为 null)
     * @param outputTokens LLM provider 响应的 output tokens (可为 null)
     * @return usage record
     */
    public static AgentUsage fromInputOutput(Integer inputTokens, Integer outputTokens) {
        return new AgentUsage(
            inputTokens != null ? inputTokens : 0L,
            outputTokens != null ? outputTokens : 0L,
            null, null, null, null, null);
    }

    /**
     * 汇总全部 4 个 token 字段 · 对齐 CC {@code getTokenCountFromUsage} (tokens.ts:46-53)
     * {@code input_tokens + (cache_creation_input_tokens ?? 0) + (cache_read_input_tokens ?? 0) + output_tokens}.
     *
     * <p>WHY: finalizeAgentTool totalTokens 用本公式 (agentToolUtils.ts:319), SubagentExecutor.extractTotalTokens
     * 必须同源, 否则 totalTokens 与 usage 各算各的 (对齐规则 5 禁止同语义双实现漂移).
     *
     * @return 4 字段之和 (nullable 缓存字段按 CC ?? 0)
     */
    public long totalTokens() {
        return inputTokens
            + (cacheCreationInputTokens != null ? cacheCreationInputTokens : 0L)
            + (cacheReadInputTokens != null ? cacheReadInputTokens : 0L)
            + outputTokens;
    }

    /**
     * CC original: server_tool_use (agentToolUtils.ts:243-248) — 本轮 provider 服务端工具调用计数.
     *
     * @param webSearchRequests CC original: web_search_requests (agentToolUtils.ts:245)
     * @param webFetchRequests  CC original: web_fetch_requests (agentToolUtils.ts:246)
     */
    public record ServerToolUse(
            long webSearchRequests,
            long webFetchRequests
    ) {}

    /**
     * CC original: cache_creation (agentToolUtils.ts:250-255) — prompt cache 创建输入 token 计数.
     *
     * @param ephemeral1hInputTokens CC original: ephemeral_1h_input_tokens (agentToolUtils.ts:252)
     * @param ephemeral5mInputTokens CC original: ephemeral_5m_input_tokens (agentToolUtils.ts:253)
     */
    public record CacheCreation(
            long ephemeral1hInputTokens,
            long ephemeral5mInputTokens
    ) {}
}
