package com.nexusai.model.session.dto;

/**
 * 响应：分叉出的新会话 + 本次复制的消息条数。
 *
 * <p>{@code session} 直接给 {@link SessionDto}，前端拿到即可插入左栏并跳转，
 * 不需要二次 GET。
 */
public record ForkSessionResponse(SessionDto session, int copiedMessages) {
}
