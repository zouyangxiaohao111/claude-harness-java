package com.nexusai.model.session.dto;

/**
 * 产物内容写回请求体 · PUT {@code /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content}。
 *
 * <p><b>WHY 单独一个 request record</b>：出站 {@link ArtifactContentUpdateResponse} 与入站
 * 请求体的字段集合完全不同（入站只有 {@code content}），⛔ 不共用一个 record —— 出站多一个字段
 * 就会让前端多收到一个字段，入站多一个字段就会让「不传 content」与「传 content=null」分不清。
 *
 * <p>{@code content} 为 null ⇒ 服务层判 400（参数缺失）；空串 {@code ""} 是<b>合法</b>的
 * 「清空文件」（用户主动把文件清空 ≠ 漏传参数）。
 *
 * @param content 新内容（UTF-8 文本；null = 参数缺失 ⇒ 400）
 */
public record ArtifactContentUpdateRequest(
        String content
) {
}
