package com.nexusai.model.session.dto;

import com.nexusai.repository.session.entity.SessionArtifactRecord;

/**
 * 会话「产物 / 来源」只读出站 DTO · GET {@code /api/v1/sessions/{sessionId}/artifacts}。
 *
 * <p>字段与 {@code session_artifact} 表（V205）一一对应，只减不加：{@code sessionId} /
 * {@code createdAt} / {@code sourceMessageId} 不出站（前端已按会话取，溯源字段当前恒空）。
 *
 * <p>{@code updatedAt} 是应用写入的 ISO-8601 偏移文本（DB 原始串直出，不二次解析 ——
 * 解析失败会让一条脏行把整个列表打成 500）。前端按字符串排序/格式化即可。
 */
public record SessionArtifactDto(
        String id,
        /** {@code deliverable}（产物）| {@code source}（来源）。 */
        String kind,
        String title,
        /** 产物文件绝对路径（source 为 null）。 */
        String localPath,
        /** 来源链接（deliverable 为 null）。 */
        String url,
        /** 产物文件字节数（目录 / 不可读 → null）。 */
        Long sizeBytes,
        String summary,
        String updatedAt
) {

    /** 行 → DTO（null 行 → null；字段原样透出，不做业务判断）。 */
    public static SessionArtifactDto from(SessionArtifactRecord r) {
        if (r == null) {
            return null;
        }
        return new SessionArtifactDto(
                r.getId(), r.getKind(), r.getTitle(), r.getLocalPath(), r.getUrl(),
                r.getSizeBytes(), r.getSummary(), r.getUpdatedAt());
    }
}
