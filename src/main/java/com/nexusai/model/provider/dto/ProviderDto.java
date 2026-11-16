package com.nexusai.model.provider.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 响应：Provider 完整信息（嵌套 models）。
 *
 * <p>[V296] 尾部 3 个只读字段描述凭据形态，供前端显示「已登录 ChatGPT / 已用 API key」。
 * ⛔ 绝不回传 oauth access / refresh / state（任何明文或密文都不出接口）。
 */
public record ProviderDto(
    String id,
    String name,
    ProviderType type,
    String baseUrl,
    String apiKeyMasked,
    Map<String, String> extraHeaders,
    boolean enabled,
    List<ModelDto> models,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /** 凭据形态：api_key | codex_oauth（null 视为 api_key）。 */
    String authMode,
    String oauthAccountId,
    /** OAuth access token 过期时刻（epoch 毫秒）。 */
    Long oauthExpiresAt,
    /** [V299] 归属品牌 key（BuiltinProviderCatalog.BRANDS[].key；null = 自定义连接）。 */
    String brandKey
) {}
