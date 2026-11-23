package com.nexusai.model.connector.dto;

/**
 * 断开连接结果 · {@code DELETE /api/market/connectors/{id}}。
 *
 * <p>断开是幂等的：本来未连接也返回 {@code revoked=true}。
 *
 * @param revoked 是否已断开
 */
public record RevokedResponse(boolean revoked) {
}
