package com.nexusai.model.connector.dto;

/**
 * 发起授权响应 · {@code POST /api/market/connectors/{id}/start}。
 *
 * <p>授权码 + loopback 回调流程（不是 device code）：前端拿到 {@code authorizeUrl} 后用系统浏览器打开，
 * 再按 {@code interval} 秒轮询 {@code /poll?state=}。
 *
 * @param authorizeUrl 授权页 URL（含 PKCE challenge 与 state）
 * @param state        本轮一次性 state（轮询与回调校验用）
 * @param redirectUri  本轮 loopback 回调地址（展示/排查用）
 * @param interval     建议轮询间隔（秒）
 * @param expiresIn    state 有效期（秒）
 */
public record StartResponse(
        String authorizeUrl,
        String state,
        String redirectUri,
        int interval,
        int expiresIn
) {
}
