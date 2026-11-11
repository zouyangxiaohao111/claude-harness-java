package com.nexusai.model.session.dto;

import com.nexusai.repository.session.entity.SessionArtifactRecord;

/**
 * 会话「产物 / 来源」只读出站 DTO · GET {@code /api/v1/sessions/{sessionId}/artifacts}。
 *
 * <p>字段与 {@code session_artifact} 表（V205）一一对应，只减不加：{@code sessionId} /
 * {@code createdAt} 不出站。
 *
 * <p><b>{@code sourceMessageId} 出站</b>（2026-11-05 起）：产物按「轮次」归属，
 * 前端用它在每轮气泡下展示本轮产物；会话级总数仍由后端 {@code total} 口径给出。
 * 历史行该字段为 {@code null} = 归属未知（⛔ 不伪造）。
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
        /**
         * 归属的用户消息 id（本轮提问）。
         *
         * <p>{@code null} = 归属未知（V276 之前的历史行，或写入方拿不到轮次）
         * —— 前端应把它们归入「历史 / 未知归属」分组，⛔ 不要猜。
         */
        String sourceMessageId,
        /**
         * 产物来源通道 · {@code deliverables}（写进会话交付物目录）| {@code project-write}
         * （Write/Edit 写过的项目内文档）。
         *
         * <p>{@code null} = 未标注（V279 之前的历史行）。前端据此画角标区分
         * 「Agent 主动交付的文件」与「顺手改的项目文档」，⛔ 不要把 null 当成任一种。
         */
        String origin,
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
                r.getSizeBytes(), r.getSummary(), r.getSourceMessageId(),
                r.getOrigin(), r.getUpdatedAt(), directory);
    }
}
