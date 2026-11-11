package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 源位置定位 · 从分块指回<b>原文件</b>的某个位置（页码 / 段落序号 / 幻灯片 / 行区间 / 字符区间）。
 *
 * <p><b>源站对照</b>：WeKnora {@code internal/types/source_locator.go:32-72}（{@code SourceLocator}）。
 * 字段名逐字照抄源站 JSON tag（snake_case），序列化后前端与原站查看器都能直接读。
 *
 * <p><b>⭐ 序号一律 1-based</b>（源站 :10-11 明文）：{@code page} / {@code slide} / {@code block} /
 * {@code section} / {@code rowStart} / {@code rowEnd} 都从 1 开始。
 *
 * <p><b>⭐ {@code start}/{@code end} 是「码点」偏移，不是 {@code String.length()}</b>
 * （源站 :58-59「rune offsets into the original text file」）。本仓所有构造点都必须用
 * {@code codePointCount} 换算 —— 用 char 下标会让中文/emoji 文档整体偏移。
 *
 * <p><b>⭐ {@code bbox} 是比例值 0–1，不是像素</b>（源站 :46-48：{@code [x0,y0,x1,y1]} 相对页面宽高，
 * 原点在渲染页左上角）。
 *
 * <p><b>⭐ 空值必须省略</b>（源站字段全是 {@code omitempty}）：{@code mapping}/{@code partial}/
 * {@code sourceId}/{@code sourceHash} 为空表示「legacy / 未验证对齐」，写出来反而会让查看器误判。
 * 故整个类用 {@code @JsonInclude(NON_NULL)}，且 {@code partial} 单列按默认值省略。
 *
 * @param type       locator 类型，取值见 {@link #TYPE_PDF} … {@link #TYPE_SECTION}
 * @param mapping    已验证的文本↔源对齐标记（空 = legacy，源站 :36-37）
 * @param partial    本块有一部分没有源映射（源站 :38-39）
 * @param sourceId   解析结果内部的块标识，<b>不是</b>渲染项（源站 :40-41）
 * @param sourceHash 绑定到知识条目源文件版本哈希（源站 :42-43）
 * @param page       1-based PDF 页码（源站 :44-45）
 * @param bbox       页面内的比例区域 {@code [x0,y0,x1,y1]}（0–1，源站 :46-48）
 * @param block      1-based Word 正文级段落/表格序号（源站 :49-50）
 * @param slide      1-based 幻灯片页号（源站 :51-52）
 * @param sheet      工作表名（源站 :53-55）
 * @param rowStart   1-based 起始行（含，源站 :53-57）
 * @param rowEnd     1-based 结束行（含，源站 :53-57）
 * @param start      **码点**起始偏移（含，源站 :58-59）
 * @param end        **码点**结束偏移（不含，源站 :58-59）
 * @param startMs    音频段起始毫秒（源站 :60-63）
 * @param endMs      音频段结束毫秒（源站 :60-63）
 * @param section    1-based EPUB spine 序号（源站 :64-65）
 * @param title      章节/幻灯片标题（源站 :66-67）
 * @param quote      被引用的原文片段（源站 :68-71；{@link #QUOTE_MAX} 截断）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KbSourceLocator(
    @JsonProperty("type") String type,
    @JsonProperty("mapping") String mapping,
    @JsonProperty("partial")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean partial,
    @JsonProperty("source_id") String sourceId,
    @JsonProperty("source_hash") String sourceHash,
    @JsonProperty("page") Integer page,
    @JsonProperty("bbox") double[] bbox,
    @JsonProperty("block") Integer block,
    @JsonProperty("slide") Integer slide,
    @JsonProperty("sheet") String sheet,
    @JsonProperty("row_start") Integer rowStart,
    @JsonProperty("row_end") Integer rowEnd,
    @JsonProperty("start") Integer start,
    @JsonProperty("end") Integer end,
    @JsonProperty("start_ms") Long startMs,
    @JsonProperty("end_ms") Long endMs,
    @JsonProperty("section") Integer section,
    @JsonProperty("title") String title,
    @JsonProperty("quote") String quote
) {

    // ── 7 种类型常量 · 逐字照抄源站 source_locator.go:12-27 ──

    /** PDF 页，可带页面内区域。 */
    public static final String TYPE_PDF = "pdf";
    /** Word 的第 N 个正文级段落或表格。 */
    public static final String TYPE_DOCX = "docx";
    /** 演示文稿的一页。 */
    public static final String TYPE_SLIDE = "slide";
    /** 电子表格某个工作表的行区间。 */
    public static final String TYPE_SHEET = "sheet";
    /** 纯文本（md / txt / json）的码点区间。 */
    public static final String TYPE_TEXT = "text";
    /** 音频的时间段。 */
    public static final String TYPE_TIME = "time";
    /** EPUB spine 项。 */
    public static final String TYPE_SECTION = "section";

    /** 引用片段上限（源站 {@code SourceLocatorQuoteMax}）· 只是展示上限，不是存储的证据长度。 */
    public static final int QUOTE_MAX = 300;

    /** {@code mapping} 的「已验证对齐」取值（源站 align.go:72 用同一个字面量）。 */
    public static final String MAPPING_EXACT = "exact";

    private static final ObjectMapper JSON = new ObjectMapper();

    public KbSourceLocator {
        // ⛔ 不做类型/字段校验：源站允许「只有 type 的 locator」，校验会把合法输入变异常。
        //    但 type 是唯一必填项（源站 :35 无 omitempty）—— 空类型会让前端无从解释该定位
        if (type == null || type.isBlank()) {
            type = "unknown";
        }
        // ⭐ 零值归一为 null：源站全部字段都是 `omitempty`，Go 里「零值」既包括 nil 也包括 0 / ""。
        //    ⛔ 不这么做会让 JSON 里出现 "quote":"" / "start":0 这类源站从不写出的字段，
        //    读回时虽然等价（默认值仍是 0/空），但两边 JSON 逐字对比会不一致。
        mapping = blankToNull(mapping);
        sourceId = blankToNull(sourceId);
        sourceHash = blankToNull(sourceHash);
        sheet = blankToNull(sheet);
        title = blankToNull(title);
        quote = blankToNull(quote);
        page = zeroToNull(page);
        block = zeroToNull(block);
        slide = zeroToNull(slide);
        rowStart = zeroToNull(rowStart);
        rowEnd = zeroToNull(rowEnd);
        start = zeroToNull(start);
        end = zeroToNull(end);
        section = zeroToNull(section);
        startMs = zeroLongToNull(startMs);
        endMs = zeroLongToNull(endMs);
        if (bbox != null && bbox.length > 0) {
            bbox = bbox.clone();
        } else {
            bbox = null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static Integer zeroToNull(Integer v) {
        return v == null || v == 0 ? null : v;
    }

    private static Long zeroLongToNull(Long v) {
        return v == null || v == 0L ? null : v;
    }

    // ── 工厂方法 · 只填该类型有意义的字段，其余保持 null（序列化时被省略）──

    /** 纯文本码点区间（{@code start}/{@code end} 必须是码点偏移）。 */
    public static KbSourceLocator text(int start, int end) {
        return new KbSourceLocator(TYPE_TEXT, null, false, null, null, null, null, null, null,
            null, null, null, start, end, null, null, null, null, null);
    }

    /** Word 正文级第 {@code block} 个段落或表格（1-based）。 */
    public static KbSourceLocator docx(int block) {
        return new KbSourceLocator(TYPE_DOCX, null, false, null, null, null, null, block, null,
            null, null, null, null, null, null, null, null, null, null);
    }

    /** 演示文稿第 {@code slide} 页（1-based），可带标题。 */
    public static KbSourceLocator slide(int slide, String title) {
        return new KbSourceLocator(TYPE_SLIDE, null, false, null, null, null, null, null, slide,
            null, null, null, null, null, null, null, null, title, null);
    }

    /** 电子表格工作表 {@code sheet} 的第 {@code rowStart}–{@code rowEnd} 行（1-based，含两端）。 */
    public static KbSourceLocator sheet(String sheet, int rowStart, int rowEnd) {
        return new KbSourceLocator(TYPE_SHEET, null, false, null, null, null, null, null, null,
            sheet, rowStart, rowEnd, null, null, null, null, null, null, null);
    }

    /** PDF 第 {@code page} 页（1-based），可带比例区域。 */
    public static KbSourceLocator pdf(int page, double[] bbox) {
        return new KbSourceLocator(TYPE_PDF, null, false, null, null, page, bbox, null, null,
            null, null, null, null, null, null, null, null, null, null);
    }

    // ── 复制式改写（record 不可变，折叠/标注 partial 都要产生新实例）──

    /** 附上引用片段。 */
    public KbSourceLocator withQuote(String newQuote) {
        return new KbSourceLocator(type, mapping, partial, sourceId, sourceHash, page, bbox, block,
            slide, sheet, rowStart, rowEnd, start, end, startMs, endMs, section, title, newQuote);
    }

    /** 标注「部分无源映射」· 源站 {@code assign.go:74-78} 会把 partial 打到该块<b>所有</b> locator 上。 */
    public KbSourceLocator withPartial(boolean newPartial) {
        return new KbSourceLocator(type, mapping, newPartial, sourceId, sourceHash, page, bbox, block,
            slide, sheet, rowStart, rowEnd, start, end, startMs, endMs, section, title, quote);
    }

    /** 标注对齐来源（{@link #MAPPING_EXACT} 等）。 */
    public KbSourceLocator withMapping(String newMapping) {
        return new KbSourceLocator(type, newMapping, partial, sourceId, sourceHash, page, bbox, block,
            slide, sheet, rowStart, rowEnd, start, end, startMs, endMs, section, title, quote);
    }

    /** 折叠时扩展行区间（sheet）。 */
    KbSourceLocator withRowRange(int newRowStart, int newRowEnd) {
        return new KbSourceLocator(type, mapping, partial, sourceId, sourceHash, page, bbox, block,
            slide, sheet, newRowStart, newRowEnd, start, end, startMs, endMs, section, title, quote);
    }

    /** 折叠时扩展码点区间（text）。 */
    KbSourceLocator withRuneRange(int newStart, int newEnd) {
        return new KbSourceLocator(type, mapping, partial, sourceId, sourceHash, page, bbox, block,
            slide, sheet, rowStart, rowEnd, newStart, newEnd, startMs, endMs, section, title, quote);
    }

    /**
     * 列表 → 落库用 JSON 文本。
     *
     * <p><b>空列表 ⇒ null</b>（源站 {@code Value()}「An empty list is stored as NULL」）：
     * 写 {@code "[]"} 会让「有没有定位」的判断全变成解析 JSON，且与源站列语义不一致。
     *
     * @param locators 定位列表（可 null）
     * @return JSON 数组文本；空/null 时 null
     */
    public static String toJson(List<KbSourceLocator> locators) {
        if (locators == null || locators.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(locators);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败不该让整篇文档入库失败：定位是补充信息，丢了只是不能跳转
            org.slf4j.LoggerFactory.getLogger(KbSourceLocator.class)
                .warn("[溯源] locator JSON 序列化失败，本条按无定位处理 条数={} 原因={}",
                    locators.size(), e.toString());
            return null;
        }
    }

    /** 防御式拷贝（{@code bbox} 是数组，record 的 equals/hashCode 不比较内容）。 */
    @Override
    public double[] bbox() {
        return bbox == null ? null : bbox.clone();
    }

    /**
     * 内容相等判定 · 对齐源站 {@code assign.go:175-196}（{@code sameLocator}）。
     *
     * <p>⛔ 不用 record 的自动 equals：其中含 {@code double[] bbox}，数组按引用比较会永远不等，
     * 去重就失效。
     */
    boolean sameAs(KbSourceLocator other) {
        if (other == null) {
            return false;
        }
        if (partial != other.partial
            || !java.util.Objects.equals(quote, other.quote)
            || !java.util.Objects.equals(sourceId, other.sourceId)
            || !java.util.Objects.equals(sourceHash, other.sourceHash)
            || !java.util.Objects.equals(mapping, other.mapping)
            || !java.util.Objects.equals(type, other.type)
            || !java.util.Objects.equals(page, other.page)
            || !java.util.Objects.equals(block, other.block)
            || !java.util.Objects.equals(slide, other.slide)
            || !java.util.Objects.equals(sheet, other.sheet)
            || !java.util.Objects.equals(rowStart, other.rowStart)
            || !java.util.Objects.equals(rowEnd, other.rowEnd)
            || !java.util.Objects.equals(start, other.start)
            || !java.util.Objects.equals(end, other.end)
            || !java.util.Objects.equals(startMs, other.startMs)
            || !java.util.Objects.equals(endMs, other.endMs)
            || !java.util.Objects.equals(section, other.section)) {
            return false;
        }
        double[] a = bbox;
        double[] b = other.bbox;
        int la = a == null ? 0 : a.length;
        int lb = b == null ? 0 : b.length;
        if (la != lb) {
            return false;
        }
        for (int i = 0; i < la; i++) {
            if (Double.compare(a[i], b[i]) != 0) {
                return false;
            }
        }
        return true;
    }
}
