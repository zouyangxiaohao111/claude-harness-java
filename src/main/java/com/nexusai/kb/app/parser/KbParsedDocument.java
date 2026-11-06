package com.nexusai.kb.app.parser;

import java.util.List;
import java.util.Map;

/**
 * 解析产物 · 一份文档解析后的全部结果（文本 + 图片引用 + 元数据）。
 *
 * <p><b>纯数据对象</b>：不含任何落库/分块逻辑（那属于上层编排）。解析器的唯一产出就是本 record。
 *
 * <p><b>{@code text} 的形态</b>：Markdown 优先（Excel 表格、PPT 分页、PDF 分页都转成 Markdown 结构），
 * 纯文本类原样返回。下游 {@code KbTextSplitter} 能识别 Markdown 表格与标题，故结构化转换是
 * 分块质量的前提，不是「好看」。
 *
 * @param text     解析出的文本（Markdown 优先）；恒非 null（可能为空串）
 * @param images   图片引用（未提取到图片时为空列表）
 * @param metadata 文档级元数据（标题/作者/MIME/页数/截断与视觉失败标记等）
 */
public record KbParsedDocument(
    String text,
    List<KbImageRef> images,
    Map<String, String> metadata
) {

    // ── metadata 键（解析器与上层共用同一批字面量，避免各写各的） ──

    /** 原始文件名。 */
    public static final String META_FILE_NAME = "fileName";
    /** 探测到的 MIME 类型（Tika {@code Content-Type}）。 */
    public static final String META_MIME_TYPE = "mimeType";
    /** 文档标题（Tika {@code title} / {@code dc:title}）。 */
    public static final String META_TITLE = "title";
    /** 作者（Tika {@code dc:creator}）。 */
    public static final String META_AUTHOR = "author";
    /** 页数（PDF / PPT）。 */
    public static final String META_PAGE_COUNT = "pageCount";
    /** 字符数（截断后的实际长度）。 */
    public static final String META_TEXT_LENGTH = "textLength";
    /** 命中上限被截断（单文档 2,000,000 字符 / PDF 200 页）。 */
    public static final String META_TRUNCATED = "truncated";
    /** 视觉补强失败（fail-soft：文本保留，此标记为 true）。 */
    public static final String META_VISION_FAILED = "visionFailed";
    /** 视觉补强的失败原因（配合 {@code visionFailed}）。 */
    public static final String META_VISION_FAILURE_REASON = "visionFailureReason";
    /** 实际走了视觉补强的页数。 */
    public static final String META_VISION_PAGES = "visionPages";
    /** 解析器名称（{@code KbPdfParser} / {@code KbOfficeParser} / {@code KbTextParser}）。 */
    public static final String META_PARSER = "parser";

    public KbParsedDocument {
        text = text == null ? "" : text;
        images = images == null ? List.of() : List.copyOf(images);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 空产物（解析成功但文档无内容时用，不是失败）。 */
    public static KbParsedDocument empty(Map<String, String> metadata) {
        return new KbParsedDocument("", List.of(), metadata);
    }
}
