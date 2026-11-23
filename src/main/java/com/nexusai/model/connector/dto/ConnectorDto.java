package com.nexusai.model.connector.dto;

import com.nexusai.model.connector.ConnectorCredentialField;

import java.util.List;

/**
 * 连接器视图 · {@code GET /api/market/connectors} 的 connectors[] 元素。
 *
 * <p>形状按实施计划 §1.1 冻结；{@code connected/status/account/expiresAt} 是<b>连接状态</b>，
 * 来自 {@code connector_credentials}（V336），不是安装状态。
 *
 * @param id        连接器 id（= key，稳定标识）
 * @param key       连接器 key（= mcp server 名）
 * @param name      展示名
 * @param description 描述
 * @param authType  oauth2 | apiKey | personalToken | customFields | imapAppPassword
 * @param category  分类
 * @param scopes    期望 scope
 * @param connected 是否已连接（凭据存在且未判定失效）
 * @param status    unconnected | pending | connected | error | disabled
 * @param account   授权账号（拿不到为 null，不编造）
 * @param expiresAt token 过期时间（epoch millis；null = 未知/不过期）
 * @param hidden    是否默认隐藏
 * @param icon      [2026-11-23] 品牌图标标识（catalog.json 透传；null → 前端回落首字母色块）
 * @param color     [2026-11-23] 品牌主题色（catalog.json 透传；null → 前端用默认色）
 * @param phase     [2026-11-23] 目录阶段（available 等）
 * @param supportTier supported（可授权运行）/ credentialOnly（只存凭据）/ unavailable（置灰）
 * @param unavailableReason unavailable 时给用户看的中文原因（其余档为 null）
 * @param docUrl          官方文档 URL（详情抽屉「查看文档」）
 * @param authHint        授权提示（中文扁平串）
 * @param hasCredentials  是否已存有用户凭据（access/refresh/extra 任一密文非空；DCR client 不算）
 * @param credentialFields 手动凭据表单字段（catalog 声明；空列表 = 前端用通用表单）
 * @param official         [2026-11-23] 是否有厂商官方 MCP
 * @param authMode         [2026-11-23] oauth2 | bearer | header | query | none | unsupported
 */
public record ConnectorDto(
        String id,
        String key,
        String name,
        String description,
        String authType,
        String category,
        List<String> scopes,
        boolean connected,
        String status,
        String account,
        Long expiresAt,
        boolean hidden,
        String icon,
        String color,
        String phase,
        String supportTier,
        String unavailableReason,
        String docUrl,
        String authHint,
        boolean hasCredentials,
        List<ConnectorCredentialField> credentialFields,
        boolean official,
        String authMode
) {

    public ConnectorDto {
        credentialFields = credentialFields == null ? List.of() : List.copyOf(credentialFields);
    }
}
