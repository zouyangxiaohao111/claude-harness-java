package com.nexusai.model.provider.dto;

/**
 * Provider 类型 · 对应后端 LlmProvider 实现
 *
 * <p>[ODF-B2] 增 {@link #openai_sdk}：openai_compatible 默认路由已切官方 SDK
 * （OpenAiSdkProvider），本枚举允许显式建 SDK 类型 provider。
 */
public enum ProviderType {
    openai_compatible,
    openai_sdk,
    /**
     * OpenAI Responses API（{@code POST /v1/responses}）· 与 chat/completions 平级的独立协议。
     *
     * <p>由 {@code ResponsesSdkProvider} 实现；图片支持 {@code input_image}，且工具结果可带图
     * （{@code function_call_output.output} 为数组），区别于 {@link #openai_compatible}。
     */
    openai_response,
    anthropic,
    /**
     * Google 订阅（Gemini Code Assist / Cloud Code OAuth）· 独立于 API-key 的
     * {@code openai_compatible} Gemini。
     *
     * <p>由 {@code GoogleGeminiProvider} 实现；请求发往 {@code https://cloudcode-pa.googleapis.com}
     * 且必须携带 {@code providers.oauth_project}（V338）。
     */
    gemini_code_assist,
    mock
}
