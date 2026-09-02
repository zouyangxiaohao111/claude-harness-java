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
    anthropic,
    mock
}
