package com.nexusai.model.session.dto;

/**
 * 产物内容写回结果 · PUT {@code /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content}。
 *
 * <p><b>WHY 要回 sizeBytes</b>：前端编辑器保存后要把「文件大小」标签刷新成本次写入的真实字节数
 * （UTF-8 字节数，⛔ 不是 Java {@code String.length()} 的字符数 —— 中文一个字 3 字节，
 * 按字符数回填会让标签与磁盘上的文件对不上）。
 *
 * <p><b>WHY 要回 updatedAt</b>：前端列表按 {@code updatedAt} 排序（DB 里是应用显式写入的
 * ISO-8601 偏移<b>文本</b>，不是时间戳类型）⇒ 出站保持同一形态，前端不必二次解析。
 *
 * <p>字段与前端 {@code ArtifactSaveResult}（{@code front/src/api/artifacts.ts}）一一对应。
 *
 * @param id        产物行 id（art-xxx）
 * @param sizeBytes 写入后文件的 UTF-8 字节数
 * @param updatedAt 写入时刻（ISO-8601 偏移文本，与 DB 列同形态）
 */
public record ArtifactContentUpdateResponse(
        String id,
        long sizeBytes,
        String updatedAt
) {
}
