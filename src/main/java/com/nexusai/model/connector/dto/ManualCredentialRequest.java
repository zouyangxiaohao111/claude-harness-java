package com.nexusai.model.connector.dto;

import java.util.Map;

/**
 * 手动凭据 · {@code POST /api/market/connectors/{id}/credentials}。
 *
 * <p>用途：连接器无动态客户端注册（DCR）时必须先填 clientId/clientSecret；非 oauth2 类型
 * （apiKey / personalToken / customFields / imapAppPassword）直接填对应字段。字段整体加密落库，
 * 读接口不回明文。
 *
 * @param authType 鉴权类型（应与连接器声明一致；缺省取连接器声明）
 * @param fields   字段键值（clientId/clientSecret/accessToken/refreshToken/…）
 */
public record ManualCredentialRequest(String authType, Map<String, String> fields) {
}
