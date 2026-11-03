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
 *
 * <p>{@code directory} = {@code local_path} 指向的是<b>目录</b>而不是文件。
 * <b>读时判定、不落库</b>（{@code session_artifact} 表没有这一列，也不需要加）——
 * 目录不会被归档（只复制普通文件），它的路径必然落在会话归档目录之外，
 * 前端必须知道这一点才画对图标、给对大小标签、禁掉预览/编辑。
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
        String updatedAt,
        /** {@code localPath} 指向的是<b>目录</b>（true）还是文件（false）。 */
        boolean directory
) {

    /**
     * 行 → DTO（null 行 → null；字段原样透出，不做业务判断）。
     *
     * @param r          产物行
     * @param directory  {@code local_path} 是否指向目录（由服务层读时判定）
     */
    public static SessionArtifactDto from(SessionArtifactRecord r, boolean directory) {
        if (r == null) {
            return null;
        }
        return new SessionArtifactDto(
                r.getId(), r.getKind(), r.getTitle(), r.getLocalPath(), r.getUrl(),
                r.getSizeBytes(), r.getSummary(), r.getUpdatedAt(), directory);
    }
}
