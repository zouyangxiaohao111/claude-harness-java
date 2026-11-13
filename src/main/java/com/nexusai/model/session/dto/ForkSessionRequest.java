package com.nexusai.model.session.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求：从某条消息处分叉会话。
 *
 * <p>{@code throughMessageId} = 分叉点所在轮的**最后一条消息**（前端取该轮末行）。
 * 后端按该消息的 {@code seq} 截断：只复制 {@code seq <= pivotSeq} 的消息。
 * 用消息 id 而不是 userMessageId，是因为压缩后/旧数据存在 {@code user_message_id = null}
 * 的轮，按消息定位更稳。
 */
public record ForkSessionRequest(@NotBlank String throughMessageId) {
}
