package com.nexusai.infra.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LlmProviderFactory 路由单测 · 对齐 CC 官方 SDK 优先（Open-ClaudeCode/src/services/api/client.ts:1
 * {@code import Anthropic from '@anthropic-ai/sdk'}）。
 *
 * <p><b>WHY (规则九)</b>: openai_compatible 默认路由到哪个实现决定线上每一笔请求走手写 HTTP
 * 还是官方 SDK。本测试锁死路由不变量：openai_compatible / 未知 / null / blank type 均走
 * OpenAiSdkProvider；config 不可用走 mock 兜底；anthropic 走 AnthropicSdkProvider（[DEC-RV-07]）。
 */
class LlmProviderFactoryTest {

    private LlmProviderFactory factory;
    private LlmProvider mockLlmProvider;
    private LlmProvider openAiSdkProvider;
    private AnthropicSdkProvider anthropicProvider;

    @BeforeEach
    void setUp() {
        factory = new LlmProviderFactory();
        mockLlmProvider = mock(LlmProvider.class, "mockLlmProvider");
        openAiSdkProvider = mock(LlmProvider.class, "openAiSdkProvider");
        anthropicProvider = mock(AnthropicSdkProvider.class, "anthropicProvider");
        ReflectionTestUtils.setField(factory, "mockLlmProvider", mockLlmProvider);
        ReflectionTestUtils.setField(factory, "openAiSdkProvider", openAiSdkProvider);
        ReflectionTestUtils.setField(factory, "anthropicProvider", anthropicProvider);
    }

    private ProviderConfig usable() {
        return new ProviderConfig("https://api.example.com", "sk-test");
    }

    @Nested
    @DisplayName("config 不可用 → mock 兜底（不变量：永不返回 null）")
    class UnusableConfig {

        @Test
        @DisplayName("config == null → mockLlmProvider")
        void nullConfig_returnsMock() {
            assertThat(factory.getProvider(null, "openai_compatible"))
                .isSameAs(mockLlmProvider);
        }

        @Test
        @DisplayName("apiKey 空 → mockLlmProvider")
        void blankApiKey_returnsMock() {
            assertThat(factory.getProvider(ProviderConfig.empty(), "openai_compatible"))
                .isSameAs(mockLlmProvider);
        }
    }

    @Nested
    @DisplayName("openai_compatible 默认路由 → OpenAiSdkProvider（CC 官方 SDK 优先）")
    class OpenaiCompatibleRoute {

        @Test
        @DisplayName("type=openai_compatible → openAiSdkProvider（[OpenAI-SDK 迁移] 旧手写 HTTP 已删除）")
        void openaiCompatible_returnsSdk() {
            assertThat(factory.getProvider(usable(), "openai_compatible"))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("type=null → openAiSdkProvider")
        void nullType_returnsSdk() {
            assertThat(factory.getProvider(usable(), null))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("type=blank → openAiSdkProvider")
        void blankType_returnsSdk() {
            assertThat(factory.getProvider(usable(), "  "))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("未知 type → openAiSdkProvider（OpenAI 协议是事实标准）")
        void unknownType_returnsSdk() {
            assertThat(factory.getProvider(usable(), "ollama"))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("type 大小写不敏感 → openAiSdkProvider")
        void mixedCase_returnsSdk() {
            assertThat(factory.getProvider(usable(), "OpenAI_Compatible"))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("overload 默认 type → openAiSdkProvider")
        void overload_returnsSdk() {
            assertThat(factory.getProvider(usable()))
                .isSameAs(openAiSdkProvider);
        }
    }

    @Nested
    @DisplayName("显式 type 路由")
    class ExplicitTypeRoute {

        @Test
        @DisplayName("type=openai_sdk → openAiSdkProvider")
        void openaiSdk_returnsSdk() {
            assertThat(factory.getProvider(usable(), "openai_sdk"))
                .isSameAs(openAiSdkProvider);
        }

        @Test
        @DisplayName("type=anthropic → anthropicProvider")
        void anthropic_returnsAnthropic() {
            assertThat(factory.getProvider(usable(), "anthropic"))
                .isSameAs(anthropicProvider);
        }
    }
}
