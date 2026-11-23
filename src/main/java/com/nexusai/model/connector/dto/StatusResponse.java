package com.nexusai.model.connector.dto;

import java.util.List;

/**
 * 连接状态 · {@code GET /api/market/connectors/{id}/status} 与手动凭据接口的响应。
 *
 * @param connected     是否已连接（有可用凭据且未判定失效）
 * @param authType      连接器声明的鉴权类型
 * @param account       授权账号（拿不到为 null）
 * @param expiresAt     token 过期时间（epoch millis；null = 未知/不过期）
 * @param missingScopes 期望 scope 中尚未授予的部分（空数组 = 齐全或无法判断）
 */
public record StatusResponse(
        boolean connected,
        String authType,
        String account,
        Long expiresAt,
        List<String> missingScopes
) {
}
