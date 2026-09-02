package com.nexusai.infra.llm;

/**
 * LlmProvider 运行时配置。
 *
 * <p>把 baseUrl + decrypted apiKey 打包成一个 record，让 LlmProvider 接口不依赖
 * ProviderService / ProviderEntity（解耦 + 易测试）。
 *
 * <p>apiKey 永远是运行时解密后的明文，调用方（如 ChatService / ProviderService.test）
 * 必须确保仅在调用栈内使用，<b>绝不</b>写日志 / 进 DTO。
 */
public record ProviderConfig(String baseUrl, String apiKey) {

    public static ProviderConfig empty() {
        return new ProviderConfig(null, null);
    }

    public boolean isUsable() {
        return apiKey != null && !apiKey.isBlank();
    }
}
