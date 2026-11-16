package com.nexusai.model.provider.dto;

import java.util.Map;

/** 更新 Provider 请求体（PATCH 语义：null 字段不改） */
public record ProviderUpdateRequest(
    String name,
    ProviderType type,
    String baseUrl,
    String apiKey,
    Map<String, String> extraHeaders,
    Boolean enabled,
    /** [V299] 归属品牌 key（null = 不改；空串 = 改为自定义）。 */
    String brandKey
) {}
