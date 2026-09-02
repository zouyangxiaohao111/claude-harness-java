package com.nexusai.model.provider.dto;

import java.util.Map;

/** 创建 Provider 请求体 */
public record ProviderCreateRequest(
    String name,
    ProviderType type,
    String baseUrl,
    String apiKey,
    Map<String, String> extraHeaders,
    Boolean enabled
) {}
