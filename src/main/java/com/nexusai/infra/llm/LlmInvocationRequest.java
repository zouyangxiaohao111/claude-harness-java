package com.nexusai.infra.llm;

import com.nexusai.application.agent.subagent.AgentContext;

/**
 * 非流式调用请求 · {@link LlmInvocationGateway#invoke} 的统一入参。
 *
 * <p><b>WHY 需要它</b>：改造前 21 处旁路调用各自直呼
 * {@code chat / chatWithOptions / chatWithRaw / chatWithOptionsMessage}，参数形态不一
 * （有的带 {@link LlmProvider.ChatRequestOptions}，有的只有单条 user 消息），且<b>没有任何一处
 * 携带归属信息或记账器</b> ⇒ 网关无法为它们记账。本 record 把「请求 + 归属 + 记账器」固定为一束，
 * 使旁路调用迁移到网关时只需构造一次。
 *
 * <p><b>归因载体</b>：{@link #agentContext()} 与流式路径语义一致（provider 的 per-LLM-call 归因边）；
 * {@link #usageAttribution()} / {@link #usageRecorder()} 是计费归属与记账出口（可 null = 不记账，
 * 与改造前行为等价）。
 *
 * @param config           解密后的运行时配置（baseUrl + apiKey）
 * @param modelName        模型名（调用方传入名；计费键归一化由账本负责）
 * @param systemPrompt     系统提示词（可 null）
 * @param userMessage      单条 user 消息
 * @param options          chat 选项（可 null = 退化到无选项调用）
 * @param querySource      调用来源字符串（{@code QuerySource} 值 / 旁路自述名；可 null）
 * @param providerType     provider 类型覆盖（可 null = 网关按模型名解析）
 * @param agentContext     归因上下文（可 null）
 * @param usageAttribution 计费归属（可 null = 归 UNKNOWN）
 * @param usageRecorder    记账器（可 null = 不记账）
 */
public record LlmInvocationRequest(
        ProviderConfig config,
        String modelName,
        String systemPrompt,
        String userMessage,
        LlmProvider.ChatRequestOptions options,
        String querySource,
        String providerType,
        AgentContext agentContext,
        UsageAttribution usageAttribution,
        LlmUsageRecorder usageRecorder
) {

    /** 最简构造（单条 user 消息、无选项、无归属、不记账）—— 逐字对齐旧 {@code chat(...)} 语义。 */
    public static LlmInvocationRequest simple(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
        return new LlmInvocationRequest(config, modelName, systemPrompt, userMessage, null, null, null,
            null, null, null);
    }

    /** 带选项 + 归属 + 记账器的构造（旁路调用迁移用）。 */
    public static LlmInvocationRequest of(ProviderConfig config, String modelName, String systemPrompt,
                                          String userMessage, LlmProvider.ChatRequestOptions options,
                                          String querySource, UsageAttribution attribution,
                                          LlmUsageRecorder recorder) {
        return new LlmInvocationRequest(config, modelName, systemPrompt, userMessage, options, querySource,
            null, options != null ? options.agentContext() : null, attribution, recorder);
    }

    /** 追加归属与记账器（不可变拷贝）。 */
    public LlmInvocationRequest withUsage(UsageAttribution attribution, LlmUsageRecorder recorder) {
        return new LlmInvocationRequest(config, modelName, systemPrompt, userMessage, options, querySource,
            providerType, agentContext, attribution, recorder);
    }
}
