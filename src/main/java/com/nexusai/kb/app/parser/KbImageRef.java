package com.nexusai.kb.app.parser;

/**
 * 图片引用 · 解析副产物中的一张图片在磁盘上的位置与来源。
 *
 * <p><b>为什么不内嵌二进制</b>：解析产物要落 {@code kb_knowledge.images}（JSON）并且会被反复读取；
 * base64 塞进文档文本会让分块与检索全部变慢。故解析阶段只落盘 + 记路径，真正需要二进制时再读。
 *
 * @param pageIndex  所在页（PDF 1-based；Office/纯文本为 0，表示「不属于某一页」）
 * @param imagePath  图片在磁盘上的绝对路径
 * @param caption    图片说明（视觉模型给出的描述；无则空串）
 * @param sourceKind 来源：{@code pdf-page}（整页渲染）/ {@code pdf-embedded}（PDF 内嵌图）/
 *                   {@code office-embedded}（Office 文档内嵌图）
 */
public record KbImageRef(
    int pageIndex,
    String imagePath,
    String caption,
    String sourceKind
) {

    /** PDF 整页渲染图（P-1：扫描件/图文混排靠它过视觉模型）。 */
    public static final String KIND_PDF_PAGE = "pdf-page";
    /** PDF 内嵌位图（P-4：图表、印章、照片）。 */
    public static final String KIND_PDF_EMBEDDED = "pdf-embedded";
    /** Office 文档内嵌图（Word/Excel/PPT 里的图片）。 */
    public static final String KIND_OFFICE_EMBEDDED = "office-embedded";

    public KbImageRef {
        caption = caption == null ? "" : caption;
        sourceKind = sourceKind == null || sourceKind.isBlank() ? KIND_OFFICE_EMBEDDED : sourceKind;
    }

    /** 无视觉说明的引用（视觉补强关闭 / 失败时保留引用本身，说明留空）。 */
    public static KbImageRef of(int pageIndex, String imagePath, String sourceKind) {
        return new KbImageRef(pageIndex, imagePath, "", sourceKind);
    }

    /** 附上视觉模型给出的说明（保留原 pageIndex / path / kind）。 */
    public KbImageRef withCaption(String newCaption) {
        return new KbImageRef(pageIndex, imagePath, newCaption, sourceKind);
    }
}
