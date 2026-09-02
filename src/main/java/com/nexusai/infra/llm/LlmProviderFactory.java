package com.nexusai.infra.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LLM Provider 工厂 · 按 type + 是否已配置 apiKey 分发
 *
 * <p>路由规则：
 * <ul>
 *   <li>{@code config == null} 或 {@code config.apiKey()} 为空 → 走 {@link MockLlmProvider}
 *       （开发模式 / 兜底 / 失败演示）</li>
 *   <li>{@code providerType == "openai_compatible"} → {@link OpenAiSdkProvider}（官方 openai-java SDK）·
 *       [ODF-B2 / OpenAI-SDK 迁移] 默认路由走官方 SDK，对齐 CC 官方 SDK 优先
 *       （Open-ClaudeCode/src/services/api/client.ts:1 {@code import Anthropic from '@anthropic-ai/sdk'}）·
 *       [OpenAI-SDK 迁移] 旧手写 HTTP {@code OpenAiProvider} 已删除，能力全部迁入 SDK 版</li>
 *   <li>{@code providerType == "openai_sdk"} → {@link OpenAiSdkProvider}（官方 openai-java SDK）</li>
 *   <li>{@code providerType == "anthropic"} → {@link AnthropicSdkProvider}（官方 anthropic-java SDK，
 *       [DEC-RV-07] 替代手写 HTTP {@code AnthropicProvider}（旧类已删除））</li>
 *   <li>未知 type → 默认走 {@link OpenAiSdkProvider}（OpenAI 协议是事实标准，官方 SDK 优先）</li>
 * </ul>
 *
 * <p>使用方（ChatService.resolveProvider）负责从 ProviderEntity 解密得到 apiKey，
 * 再调本工厂。工厂不做解密（解耦）。
 *
 * <p>两种官方 SDK provider 选择依据（[OpenAI-SDK 迁移] 旧手写 HTTP provider 均已删除）：
 * <ul>
 *   <li>{@link OpenAiSdkProvider}：官方 openai-java SDK, 支持 OpenAI 兼容协议（DeepSeek 等）· 默认</li>
 *   <li>{@link AnthropicSdkProvider}：官方 anthropic-java SDK, 支持 Claude 系列
 *       （[DEC-RV-07] 替代 {@code AnthropicProvider} 手写 HTTP，旧类已删除）</li>
 * </ul>
 */
@Component
public class LlmProviderFactory {

    @Autowired private LlmProvider mockLlmProvider;
    @Autowired private LlmProvider openAiSdkProvider;
    @Autowired private AnthropicSdkProvider anthropicProvider;

    /**
     * 按 provider type + config 路由。
     *
     * @param config       解密后的配置；null 或 apiKey 空 → mock
     * @param providerType "openai_compatible" / "openai_sdk" / "anthropic" / null
     * @return 永不返回 null（至少返回 mock）
     */
    public LlmProvider getProvider(ProviderConfig config, String providerType) {
        if (config == null || !config.isUsable()) {
            return mockLlmProvider;
        }
        if (providerType == null || providerType.isBlank()) {
            return openAiSdkProvider;     // 默认 OpenAI 兼容 · [ODF-B2] 官方 SDK 优先
        }
        return switch (providerType.toLowerCase()) {
            case "openai_compatible" -> openAiSdkProvider;   // [ODF-B2] 默认路由走官方 SDK
            case "openai_sdk" -> openAiSdkProvider;
            case "anthropic" -> anthropicProvider;
            default -> openAiSdkProvider; // 未知 → 假设 OpenAI 兼容（事实标准）· 官方 SDK 优先
        };
    }

    /** 便捷 overload：默认 type = openai_sdk（[ODF-B2] openai_compatible 亦走 SDK） */
    public LlmProvider getProvider(ProviderConfig config) {
        return getProvider(config, "openai_sdk");
    }
}
