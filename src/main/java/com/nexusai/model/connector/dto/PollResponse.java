package com.nexusai.model.connector.dto;

/**
 * 轮询授权结果 · {@code GET /api/market/connectors/{id}/poll?state=}。
 *
 * <p>{@code state} ∈ PENDING | AUTHORIZED | ERROR。前端按 {@code interval} 秒调一次；
 * AUTHORIZED 后建议再拉一次 {@code /status} 刷新已连接状态。
 *
 * @param state   PENDING | AUTHORIZED | ERROR
 * @param message 失败原因（PENDING/AUTHORIZED 时为 null）
 * @param account 授权账号（拿不到为 null）
 */
public record PollResponse(String state, String message, String account) {
}
