package com.nexusai.kb.app.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Office 文档解析器 · Word / Excel / PPT + 电子书（epub）/ 网页归档（mhtml）
 * （P-2「word 能直接读取」+ P-3「excel 接入 Apache Tika 2.x」）。
 *
 * <p><b>为什么走 Tika 而不是直接用 POI</b>：用户裁定 Excel 用 Tika 2.x；Word/PPT 用 Tika 能一并覆盖
 * 老格式（.doc 的 OLE2 容器、.ppt），直接用 POI 要自己判两套文件头。Tika 底层本来就是 POI。
 * 电子书/网页归档同理：epub 由 {@code tika-parser-miscoffice-module} 的 {@code EpubParser}
 * （解 zip → container.xml → spine 顺序拼 XHTML），mhtml 由 {@code tika-parser-mail-module} 的
 * MIME 解析器处理 —— 两者产出的都是 XHTML 事件流，正好复用本文的 Markdown 转换。
 *
 * <p><b>2026-11-12 起本类是 Word/PPT/电子书/RTF/ODF 的默认引擎</b>（此前默认是 {@link KbAnydocParser}）。
 * 用户裁定的理由：需要 <b>source block</b>（页码/段落/行号/单元格溯源，见
 * {@link KbOfficeSourceBlocks}）与<b>内嵌图片</b>（{@link ImageCollector}），anydoc 只出 markdown、
 * 两者都不提供。anydoc 退为「{@code parser_engine_rules} 显式点名才用」。
 *
 * <p><b>⭐ OOXML 表格（{@code xlsx/xlsm/xltx/xlt}）已让给 {@link KbXlsxParser}</b>
 * （{@code @Order(16)}，见 {@link #XLSX_OWNED_EXT}）：Tika 给不出 Excel 行号，只有逐行渲染
 * 才能产出 {@link KbSourceLocator#sheet}。本类只剩 {@code .xls}（OLE2）与「被规则点名到本类」的
 * 兼容路径；{@link #parse} 里的 Excel 物化通道保留，但不再是默认路由。
 *
 * <p><b>为什么不用 Tika 的纯文本输出</b>：实测（本机 Tika 2.9.2 对 POI 生成的 .docx/.xlsx）纯文本会把
 * 表格拍平成 {@code \t项目\t金额\t}，表格结构不可恢复。故走 XHTML 事件流 → {@link KbMarkdownHandler}
 * 转成 Markdown（表格 → {@code | 项目 | 金额 |} + 分隔行；Excel 的 sheet 名 → {@code ## 名称}；
 * PPT 的 {@code slide-content} → {@code ## Slide N}）。
 *
 * <p><b>内嵌图片</b>：自定义 {@link EmbeddedDocumentExtractor} 拦截 Tika 的内嵌资源流；只有能
 * {@link ImageIO#read} 成功的才落盘（EMF/WMF 这类矢量嵌入 read 会 null，直接跳过，不写空文件）。
 *
 * <p><b>Excel 值物化（{@link ExcelValueMaterializer}）</b>：Tika 只渲染「XML 里真实存在的单元格」，
 * 合并区域里除左上角外的格子是空的 ⇒ 表格行缺值；百分比/日期这类格式在 Tika 侧虽已按
 * {@code DataFormatter} 出显示值，但依赖 Tika 内部行为、不受本仓控制。故 xlsx/xlsm 在交给 Tika
 * <b>之前</b>先用 POI 把「合并区域每个格子填上左上角显示值」「百分比格式格的显示文本落成字符串」
 * 写进一份临时工作簿，再让 Tika 原样渲染 —— 行/列/表格渲染逻辑一行不动，只有单元格值被补齐。
 *
 * <p><b>上限</b>：单文档 ≤ {@value #MAX_TEXT_CHARS} 字符，超出截断并记 {@code truncated=true}。
 */
@Component
@Order(20)
public class KbOfficeParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbOfficeParser.class);

    static final int MAX_TEXT_CHARS = 2_000_000;

    /** Word / Excel / PPT 的新旧格式（宏文档按同族处理）。 */
    static final Set<String> WORD_EXT = Set.of("doc", "docx", "docm", "dot", "dotx");
    /** Excel 全部扩展名（含 OOXML）· {@link ExcelValueMaterializer} 与 heading 偏移仍按它判定。 */
    static final Set<String> EXCEL_EXT = Set.of("xls", "xlsx", "xlsm", "xlt", "xltx");
    /**
     * 2026-11-12 起让给 {@link KbXlsxParser} 的扩展名。
     *
     * <p>让出的原因：只有自己逐行渲染才能带上 Excel 行号（{@link KbSourceLocator#sheet}），
     * Tika 的事件流给不出「第几行」。本类<b>不再认领</b>这批扩展名（{@code supports} /
     * {@code supportedExtensions} 都不含），但 {@link #parse} 的 Excel 通道保留 ——
     * 老单测与被 {@code parser_engine_rules} 点名到本类的路径仍能工作，且不构成第二条默认路由。
     */
    static final Set<String> XLSX_OWNED_EXT = Set.of("xlsx", "xlsm", "xlt", "xltx");
    static final Set<String> PPT_EXT = Set.of("ppt", "pptx", "pptm", "pot", "potx");
    /** 电子书与网页归档（源站由 builtin 引擎覆盖；Tika 同一套解析器即可处理）。 */
    static final Set<String> EBOOK_WEB_EXT = Set.of("epub", "mhtml");
    /**
     * RTF / OpenDocument 文本·表格·演示。
     *
     * <p><b>为什么在本类</b>（2026-11-12 让出后收口）：这批扩展名原先由 {@link KbAnydocParser}
     * 默认接管，随 Office 一起移出后若无接管者，{@code .rtf/.odt/.ods/.odp} 上传会「没有匹配的解析器」。
     * Tika 本来就能解析它们（RTF 走 {@code RTFParser}，ODF 走 {@code OpenDocumentParser}，同样产出
     * XHTML 事件流 → {@link KbMarkdownHandler}），故并入本类的 Tika 通道 —— 只加扩展名，不加新代码路径。
     * ⚠️ {@link KbOfficeSourceBlocks#supports} 不含这四个 ⇒ 这些类型<b>不产出 source block</b>（如实为空）。
     */
    static final Set<String> RTF_ODF_EXT = Set.of("rtf", "odt", "ods", "odp");
    private static final Set<String> OFFICE_EXT = union();

    private static Set<String> union() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.addAll(WORD_EXT);
        all.addAll(EXCEL_EXT);
        all.addAll(PPT_EXT);
        all.addAll(EBOOK_WEB_EXT);
        all.addAll(RTF_ODF_EXT);
        all.removeAll(XLSX_OWNED_EXT);   // 已让给 KbXlsxParser，本类不声明也不认领
        return Set.copyOf(all);
    }

    /**
     * docx 纵向合并物化开关 · 默认开。
     *
     * <p>源站同一能力在 {@code docx_merge.py}，由 {@code markitdown_parser.py:55} 调用；
     * 关掉后 docx 直接交 Tika（合并区域里的续行单元格会像以前一样为空）。
     */
    @Value("${nexusai.knowledge.excel.docx-vmerge-fill:true}")
    boolean docxVmergeFill = true;

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null && OFFICE_EXT.contains(ext)) {
            return true;
        }
        String mime = KbParsers.normalizeMime(mimeType);
        return mime.contains("wordprocessingml")
            || mime.contains("spreadsheetml")
            || mime.contains("presentationml")
            || mime.equals("application/msword")
            || mime.equals("application/vnd.ms-excel")
            || mime.equals("application/vnd.ms-powerpoint")
            || mime.equals("application/x-tika-msoffice")
            || mime.equals("application/x-tika-ooxml")
            // 电子书 / 网页归档：扩展名被改掉（无 .epub / .mhtml 后缀）时靠 MIME 兜底。
            // Tika 对本仓这两个格式的实际探测值见 {@code KbOfficeParserTest} 的探针断言。
            || mime.equals("application/epub+zip")
            || mime.startsWith("multipart/related")
            || mime.equals("application/x-mimearchive")
            || mime.equals("message/rfc2557");
    }

    /**
     * 能力声明 · 直接复用 {@link #supports} 用的 {@code OFFICE_EXT}
     * （Word/Excel({@code .xls})/PPT 三族 + 电子书/网页归档的并集，已扣除 {@link #XLSX_OWNED_EXT}）。
     *
     * <p>同一批扩展名也在 {@code supports} 里判过，故声明与实际路由必然一致。
     */
    @Override
    public Set<String> supportedExtensions() {
        return OFFICE_EXT;
    }

    /** 展示名 · 前端「解析引擎」下拉用。 */
    @Override
    public String displayName() {
        return "内置 Office 解析";
    }

    /**
     * 引擎族名别名 · 只收 {@code office}。自身类名由 {@code name()} 提供，不必重复列出。
     *
     * <p><b>⛔ 为什么不带 {@code anydoc}</b>（2026-11-12 让出后收口）：本类现在是 Office 类格式的
     * <b>默认</b>解析器，而 anydoc 只在 {@code parser_engine_rules} 显式点名时使用。若本类也声称
     * {@code anydoc} 族名，{@link KbParserEngineRouter} 的族名一对多消歧会按 {@code supports()} 认文件 ——
     * {@link KbAnydocParser#supports} 现在恒 false，于是「规则写 anydoc」会被解析成落到本类，
     * 显式点名 anydoc 就失效了。族名只归它自己，点名才准。
     */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("office");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName().toString();
        String ext = KbParsers.extensionOf(fileName);
        // Excel 的 sheet 名 Tika 发成 h1；规格要求二级标题 ⇒ 整体降一级
        int headingOffset = ext != null && EXCEL_EXT.contains(ext) ? 1 : 0;

        Metadata md = KbTikaSupport.newMetadata(fileName);
        KbMarkdownHandler handler = new KbMarkdownHandler(headingOffset);
        Path imageDir = null;
        ImageCollector collector = null;
        // docx 纵向合并物化：把 vMerge=restart 的单元格内容复制进续行，Tika 才渲染得出重复值。
        // 返回 null = 不是 Word / 无需物化 / 失败已 fail-soft（见 DocxVMergeFiller）
        Path vmergeFixed = DocxVMergeFiller.fill(file, fileName, docxVmergeFill);
        // 结构抽取必须与 Tika 读同一份输入：docx 被物化时用副本，否则续行文本与 Markdown 对不上
        Path structureSource = vmergeFixed != null ? vmergeFixed : file;
        Path materialized = null;
        try {
            // Excel 值物化：合并区域填值 + 百分比格式落文本。返回 null = 无需物化（或物化失败已 fail-soft）
            if (isExcelExtension(ext)) {
                materialized = ExcelValueMaterializer.materialize(structureSource, fileName);
            }
            Path source = materialized != null ? materialized : structureSource;
            ParseContext parseCtx = new ParseContext();
            imageDir = KbImageSink.resolveImageDir(file, ctx);
            collector = new ImageCollector(imageDir);
            parseCtx.set(EmbeddedDocumentExtractor.class, collector);
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(source))) {
                KbTikaSupport.parser().parse(in, handler, md, parseCtx);
            }

            String markdown = handler.markdown();
            KbParsers.Truncation t = KbParsers.truncate(markdown, MAX_TEXT_CHARS);

            Map<String, String> meta = KbParsers.newMeta();
            meta.putAll(KbTikaSupport.toMetadataMap(md));
            KbParsers.fillCommonMetadata(meta, fileName, md, this);
            if (t.truncated()) {
                meta.put(KbParsedDocument.META_TRUNCATED, "true");
            }
            meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(t.text().length()));

            List<KbImageRef> images = collector == null ? List.of() : collector.images();
            if (!images.isEmpty()) {
                meta.put("imageCount", String.valueOf(images.size()));
            }

            // ⭐ 源位置块：用 POI 抽「真结构」再对齐到 Tika 产出的 Markdown。
            //   WHY 不在此「边拼边记账」：docx 段落序号 / xlsx 行号 / pptx 页号都不在 XHTML 事件里，
            //   靠事件计数遇空段落立刻错位且不报错（见 KbOfficeSourceBlocks 类注释）。
            List<KbSourceBlock> sourceBlocks = List.of();
            if (KbOfficeSourceBlocks.supports(ext)) {
                List<KbSourceIndex.Unit> units = KbOfficeSourceBlocks.units(ext, structureSource);
                if (!units.isEmpty()) {
                    sourceBlocks = KbSourceIndex.align(t.text(), units);
                    log.info("[溯源] {} 产出 {} 个 source block（类型={} 覆盖码点={}/{}）",
                        fileName, sourceBlocks.size(), blocksTypeOf(ext),
                        coveredCodePoints(sourceBlocks), codePointLength(t.text()));
                    if (sourceBlocks.isEmpty()) {
                        log.warn("[溯源] {} 结构单元 {} 个但一个都没对齐上（Markdown 与源结构文本不一致）",
                            fileName, units.size());
                    }
                } else {
                    log.warn("[溯源] {} 未抽到结构单元（文件为空或格式不受支持）类型={}", fileName, ext);
                }
            } else {
                log.info("[溯源] {} 类型={} 暂无结构抽取能力，本文件不产出 source block", fileName, ext);
            }
            meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(sourceBlocks.size()));

            if (log.isInfoEnabled()) {
                log.info("KbOfficeParser: 解析完成 file={} mime={} chars={} images={} truncated={} sourceBlocks={}",
                    fileName, meta.get(KbParsedDocument.META_MIME_TYPE), t.text().length(), images.size(),
                    t.truncated(), sourceBlocks.size());
            }
            return new KbParsedDocument(t.text(), images, meta, sourceBlocks);
        } catch (Exception e) {
            throw KbTikaSupport.wrap("Office 文档解析", file, e);
        } finally {
            deleteQuietly(materialized);
            deleteQuietly(vmergeFixed);
        }
    }

    /** 扩展名 → 该文件产出的 locator 类型（仅用于日志，让排查时一眼看出走的是哪条规则）。 */
    private static String blocksTypeOf(String ext) {
        if (ext == null) {
            return "unknown";
        }
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "docx", "docm" -> KbSourceLocator.TYPE_DOCX;
            case "pptx", "pptm" -> KbSourceLocator.TYPE_SLIDE;
            case "xlsx", "xlsm" -> KbSourceLocator.TYPE_SHEET;
            default -> "unknown";
        };
    }

    /** 源块覆盖的码点总数（仅用于日志）。 */
    private static int coveredCodePoints(List<KbSourceBlock> blocks) {
        int n = 0;
        for (KbSourceBlock b : blocks) {
            n += Math.max(0, b.length());
        }
        return n;
    }

    /** 码点长度（⛔ 不用 {@code length()}）。 */
    private static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /**
     * 内嵌资源收集器 · 只收「ImageIO 能解码」的流。
     *
     * <p>Tika 会把 Office 里的所有嵌入二进制（图片、字体、嵌入的 Excel 表…）都走
     * {@link EmbeddedDocumentExtractor}。判断依据只能是内容：能解码成 {@link BufferedImage} 才是图片。
     *
     * <p><b>失败不阻断</b>：单张图写盘/解码失败只记 warn，继续解析正文（图片是补充，不是正文）。
     *
     * <p><b>⛔ 回落必须带上 Parser（真机缺陷，勿删）</b>：非图片内嵌要交回 Tika 继续解析成文字。
     * {@link ParsingEmbeddedDocumentExtractor} 内部用的是 {@code DelegatingParser}，
     * 它从 {@code ParseContext} 取 {@code Parser.class}，<b>取不到就退回 {@code EmptyParser}</b>
     * （{@code DelegatingParser#getDelegateParser} 的默认值），于是正文被<b>静默丢成 0 字符</b>、
     * 不报错、不留痕。AutoDetectParser 自己创建的默认 extractor 是先把 {@code Parser} 塞进 context 的
     * （{@code initializeEmbeddedDocumentExtractor}），本类替换成自定义 extractor 后必须自己补上。
     *
     * <p><b>实测影响面（不要扩大也不要缩小）</b>：<b>MHTML 正文整段走这条路</b> ——
     * 修复前 {@code .mhtml} 抽出 0 字符，修复后正文正常。EPUB 的章节由 {@code EpubParser}
     * 自己解析并写进 handler，只有 {@code nav.xhtml}（目录）等附属文件走本回落，
     * 故本修复对 epub 只是「多出目录文字」这种小差别。
     */
    static final class ImageCollector implements EmbeddedDocumentExtractor {

        private final Path imageDir;
        private final List<KbImageRef> collected = new ArrayList<>();
        private int counter;

        ImageCollector(Path imageDir) {
            this.imageDir = imageDir;
        }

        List<KbImageRef> images() {
            return List.copyOf(collected);
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml)
                throws SAXException, IOException {
            // 读成图片；不是图片（字体/嵌入表/内嵌 HTML 正文）→ 当正文继续解析，交给默认逻辑
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) {
                return;
            }
            BufferedImage image;
            try {
                image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            } catch (IOException e) {
                image = null;
            }
            if (image == null) {
                // 非位图嵌入（含 EMF/WMF 矢量）→ 不落盘；其文本内容仍交给默认解析器。
                // ⚠️ context 必须带 Parser，否则正文静默变空（见类 javadoc）；带上 this 让内嵌里的
                //    图片仍能继续被收集（与 Tika 默认 extractor 的递归语义一致）。
                ParseContext inner = new ParseContext();
                inner.set(org.apache.tika.parser.Parser.class, KbTikaSupport.parser());
                inner.set(EmbeddedDocumentExtractor.class, this);
                new ParsingEmbeddedDocumentExtractor(inner)
                    .parseEmbedded(new java.io.ByteArrayInputStream(bytes), handler, metadata, outputHtml);
                return;
            }
            try {
                counter++;
                Path out = imageDir.resolve(String.format("office-image-%03d.png", counter));
                if (!ImageIO.write(image, "png", out.toFile())) {
                    throw new IOException("PNG writer unavailable");
                }
                String name = metadata == null ? null : metadata.get("resourceName");
                collected.add(new KbImageRef(0, out.toAbsolutePath().toString(),
                    name == null ? "" : name, KbImageRef.KIND_OFFICE_EMBEDDED));
            } catch (IOException e) {
                log.warn("KbOfficeParser: 内嵌图片落盘失败（继续解析正文）file={} 第{}张 原因={}",
                    imageDir, counter, e.getMessage());
            } finally {
                image.flush();
            }
        }
    }

    /**
     * docx 纵向合并（{@code w:vMerge}）物化 · 对齐源站 {@code docx_merge.py:24-70}
     * （调用点仅 {@code markitdown_parser.py:55}）。
     *
     * <p><b>为什么必须在 XML 层做</b>：Word 的纵向合并是「restart 单元格 + 若干 continue 空单元格」；
     * 续行在 XML 里<b>没有内容</b>。Mammoth/markitdown 出 {@code rowspan}，而 Markdown 表格表达不了
     * {@code rowspan} ⇒ 续行整行丢值。源站的做法就是先把 restart 的内容复制进每个 continue，
     * 再交给下游渲染 —— 本类照做。
     *
     * <p><b>边界</b>：
     * <ul>
     *   <li>只处理 {@code word/*.xml} 里含 {@code vMerge} 的 part；其余 part 原样透传。</li>
     *   <li>zip 条目顺序与压缩方式保持原样（只替换需要改的条目字节）。</li>
     *   <li>续行单元格<b>已有内容</b>时不追加（Word 里 continue 本就应为空；非空时追加会变成重复）。</li>
     *   <li><b>失败一律 fail-soft</b>：返回 {@code null}，调用方回退原文件交给 Tika。</li>
     * </ul>
     */
    static final class DocxVMergeFiller {

        private static final Set<String> TARGET_EXT = Set.of("docx", "docm", "dotx", "dot");
        private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        private static final String VMERGE = "vMerge";

        private DocxVMergeFiller() {
        }

        /**
         * 产出「vMerge 已填充」的临时副本。
         *
         * @return 临时文件路径；不适用（非 Word / 无 vMerge / 开关关闭）或失败 → {@code null}
         */
        static Path fill(Path file, String fileName, boolean enabled) {
            if (!enabled) {
                return null;
            }
            String ext = KbParsers.extensionOf(fileName);
            if (ext == null || !TARGET_EXT.contains(ext.toLowerCase(Locale.ROOT))) {
                return null;
            }
            Path created = null;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                List<String> targets = new ArrayList<>();
                Enumeration<? extends ZipEntry> scan = zip.entries();
                while (scan.hasMoreElements()) {
                    ZipEntry entry = scan.nextElement();
                    if (isWordXml(entry.getName()) && containsVMerge(readEntry(zip, entry))) {
                        targets.add(entry.getName());
                    }
                }
                if (targets.isEmpty()) {
                    return null;   // 无纵向合并：原文件直接交 Tika，零额外行为
                }
                created = Files.createTempFile("kb-docx-vmerge-", ".docx");
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(created))) {
                    Enumeration<? extends ZipEntry> all = zip.entries();
                    while (all.hasMoreElements()) {
                        ZipEntry entry = all.nextElement();
                        byte[] data = readEntry(zip, entry);
                        if (targets.contains(entry.getName())) {
                            byte[] filled = fillXml(data, fileName);
                            if (filled != null) {
                                data = filled;
                            }
                        }
                        writeEntry(out, entry, data);
                    }
                }
                log.info("[docx 物化] file={} 含 vMerge 的 part {} 个，已把 restart 内容复制进续行: {}",
                    fileName, targets.size(), targets);
                return created;
            } catch (Exception | LinkageError e) {
                // fail-soft：物化只是补值，失败就退回原文件走 Tika（正文照常，最多续行缺值）
                deleteQuietly(created);
                log.warn("KbOfficeParser: docx vMerge 物化失败，回退原文件 file={} 原因={}",
                    fileName, e.toString());
                return null;
            }
        }

        private static boolean isWordXml(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("word/") && lower.endsWith(".xml");
        }

        private static boolean containsVMerge(byte[] xml) {
            return new String(xml, StandardCharsets.UTF_8).contains(VMERGE);
        }

        private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }

        /** 按原条目名/时间/压缩方式写回；STORED 条目要自己补 size + crc。 */
        private static void writeEntry(ZipOutputStream out, ZipEntry original, byte[] data) throws IOException {
            ZipEntry copy = new ZipEntry(original.getName());
            if (original.getTime() > 0) {
                copy.setTime(original.getTime());
            }
            copy.setComment(original.getComment());
            if (original.getMethod() == ZipEntry.STORED) {
                CRC32 crc = new CRC32();
                crc.update(data);
                copy.setMethod(ZipEntry.STORED);
                copy.setSize(data.length);
                copy.setCompressedSize(data.length);
                copy.setCrc(crc.getValue());
            } else {
                copy.setMethod(ZipEntry.DEFLATED);
            }
            out.putNextEntry(copy);
            out.write(data);
            out.closeEntry();
        }

        /**
         * 重写一个含 vMerge 的 Word XML part。
         *
         * <p>倒序处理表格：先填内层表，父表复制内容时拿到的就是「已填好」的内层副本。
         *
         * @return 新字节；解析/序列化失败 → {@code null}（调用方保留原字节）
         */
        private static byte[] fillXml(byte[] xml, String fileName) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setExpandEntityReferences(false);
                try {
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                } catch (Exception ignored) {
                    // 老解析器不支持该 feature：上面的 namespaceAware + 无外部实体已足够本用途
                }
                Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                List<Element> tables = new ArrayList<>();
                NodeList list = doc.getElementsByTagNameNS(W_NS, "tbl");
                for (int i = 0; i < list.getLength(); i++) {
                    tables.add((Element) list.item(i));
                }
                for (int i = tables.size() - 1; i >= 0; i--) {
                    fillTable(tables.get(i));
                }
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length + 256);
                transformer.transform(new DOMSource(doc), new StreamResult(out));
                return out.toByteArray();
            } catch (Exception e) {
                log.warn("KbOfficeParser: docx XML 重写失败，该 part 保留原样 file={} 原因={}",
                    fileName, e.toString());
                return null;
            }
        }

        /** 单表：restart 记到「起始列 → tc」，continue 复制同列最近一次 restart 的内容。 */
        private static void fillTable(Element table) {
            Map<Integer, Element> restartByColumn = new HashMap<>();
            for (Element tr : childElements(table, "tr")) {
                int column = gridBefore(tr);
                for (Element tc : childElements(tr, "tc")) {
                    int span = gridSpan(tc);
                    String merge = vMergeValue(tc);
                    if ("restart".equals(merge)) {
                        restartByColumn.put(column, tc);
                    } else if ("continue".equals(merge)) {
                        Element source = restartByColumn.get(column);
                        if (source != null) {
                            copyCellContent(source, tc);
                        }
                    }
                    column += Math.max(1, span);
                }
            }
        }

        /** 续行单元格：保留自己的 {@code tcPr}，正文替换为 restart 单元格正文的深拷贝。 */
        private static void copyCellContent(Element source, Element target) {
            if (!cellText(target).isBlank()) {
                return;   // 已有内容不追加（避免重复文本）
            }
            List<Node> keepProperties = new ArrayList<>();
            for (Node n = target.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (isElement(n, "tcPr")) {
                    keepProperties.add(n);
                }
            }
            while (target.getFirstChild() != null) {
                target.removeChild(target.getFirstChild());
            }
            for (Node n : keepProperties) {
                target.appendChild(n);
            }
            for (Node n = source.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!isElement(n, "tcPr")) {
                    target.appendChild(n.cloneNode(true));
                }
            }
        }

        /** {@code w:vMerge} 取值：无该元素 → null；有元素但无 val → "continue"（OOXML 语义）。 */
        private static String vMergeValue(Element tc) {
            Element tcPr = firstChild(tc, "tcPr");
            Element vMerge = tcPr == null ? null : firstChild(tcPr, "vMerge");
            if (vMerge == null) {
                return null;
            }
            String val = attr(vMerge, "val");
            return val == null || val.isBlank() ? "continue" : val;
        }

        private static int gridSpan(Element tc) {
            Element tcPr = firstChild(tc, "tcPr");
            Element span = tcPr == null ? null : firstChild(tcPr, "gridSpan");
            return span == null ? 1 : parseIntOr(attr(span, "val"), 1);
        }

        private static int gridBefore(Element tr) {
            Element trPr = firstChild(tr, "trPr");
            Element before = trPr == null ? null : firstChild(trPr, "gridBefore");
            return before == null ? 0 : parseIntOr(attr(before, "val"), 0);
        }

        private static int parseIntOr(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static String cellText(Element tc) {
            StringBuilder sb = new StringBuilder();
            NodeList texts = tc.getElementsByTagNameNS(W_NS, "t");
            for (int i = 0; i < texts.getLength(); i++) {
                sb.append(texts.item(i).getTextContent());
            }
            return sb.toString();
        }

        private static List<Element> childElements(Element parent, String localName) {
            List<Element> out = new ArrayList<>();
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (isElement(n, localName)) {
                    out.add((Element) n);
                }
            }
            return out;
        }

        private static Element firstChild(Element parent, String localName) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (isElement(n, localName)) {
                    return (Element) n;
                }
            }
            return null;
        }

        private static boolean isElement(Node node, String localName) {
            return node.getNodeType() == Node.ELEMENT_NODE
                && W_NS.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName());
        }

        private static String attr(Element element, String localName) {
            return element.hasAttributeNS(W_NS, localName)
                ? element.getAttributeNS(W_NS, localName)
                : null;
        }
    }

    /**
     * Excel 值物化 · 在交给 Tika 渲染<b>之前</b>把两类「只在格式里、不在值里」的信息落成显式单元格值：
     *
     * <ol>
     *   <li><b>合并单元格</b>：Tika（POI 的事件模型）只吐 XML 里真实存在的单元格，{@code A1:B1}
     *       这类合并区域除左上角外都是空的 ⇒ 每行数据不完整，下游按行取某一列会取到空。
     *       这里把左上角的<b>显示值</b>写进区域内其余每个格子。</li>
     *   <li><b>百分比格式</b>：{@code 0.125} + {@code 0.0%} 的单元格显示值是 {@code 12.5%}。
     *       把显示文本直接落成字符串，下游拿到的就是用户看到的值（否则只拿到 {@code 0.125}，差 100 倍）。</li>
     * </ol>
     *
     * <p><b>为什么回写整份工作簿而不是自己渲染表格</b>：行/列/表格渲染全在 {@link KbMarkdownHandler}
     * + Tika 里，自己再写一套会立刻产生「两条渲染路径对同一份文件给出不同 Markdown」的分叉。
     * 物化后仍交回 Tika 原样渲染 ⇒ 输出结构与既有行为一致，差别只有「本该有值的格子现在有值了」。
     *
     * <p><b>边界</b>：
     * <ul>
     *   <li>只做 {@code xlsx/xlsm}（OOXML）。{@code .xls} 是 OLE2 二进制，回写风险大于本批收益，不动。</li>
     *   <li>没有任何合并区域 / 百分比格时<b>不</b>回写，原文件直接交回 Tika（零额外行为）。</li>
     *   <li>合并区域左上角为空 ⇒ 不填（填进去也是空，没有信息可物化）。</li>
     *   <li><b>失败一律 fail-soft</b>：物化失败只记 warn 并回退原文件 —— 补值不能变成新的解析失败源。</li>
     * </ul>
     */
    static final class ExcelValueMaterializer {

        /** 物化目标后缀（小写）：只覆盖 OOXML 表格，{@code .xls} 不在本批范围内。 */
        private static final Set<String> TARGET_EXT = Set.of("xlsx", "xlsm");

        private ExcelValueMaterializer() {
        }

        /**
         * 产出「值已物化」的临时工作簿。
         *
         * @param file     原文件
         * @param fileName 原文件名（只用来取扩展名）
         * @return 临时文件路径；无需物化（无合并区域且无百分比格）或物化失败（已 fail-soft）→ {@code null}
         */
        static Path materialize(Path file, String fileName) {
            String ext = KbParsers.extensionOf(fileName);
            if (ext == null || !TARGET_EXT.contains(ext.toLowerCase(Locale.ROOT))) {
                return null;
            }
            Path created = null;
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file));
                 Workbook wb = WorkbookFactory.create(in)) {
                // 与 Tika 内部（POI 的 XSSFSheetXMLHandler）同一套显示值语义：默认 Locale 的 DataFormatter
                DataFormatter formatter = new DataFormatter();
                int totalRegions = 0;
                int totalFilled = 0;
                int totalPercent = 0;
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    Sheet sheet = wb.getSheetAt(i);
                    int[] stat = materializeSheet(sheet, formatter);
                    if (stat[0] == 0 && stat[2] == 0) {
                        continue;
                    }
                    totalRegions += stat[0];
                    totalFilled += stat[1];
                    totalPercent += stat[2];
                    log.info("[Excel 物化] file={} sheet={} 合并区域={} 填充单元格={} 百分比单元格={}",
                        fileName, sheet.getSheetName(), stat[0], stat[1], stat[2]);
                }
                if (totalRegions == 0 && totalPercent == 0) {
                    return null;
                }
                created = Files.createTempFile("kb-office-materialized-", ".xlsx");
                try (OutputStream out = Files.newOutputStream(created)) {
                    wb.write(out);
                }
                log.info("[Excel 物化] file={} 汇总 合并区域={} 填充单元格={} 百分比单元格={}",
                    fileName, totalRegions, totalFilled, totalPercent);
                return created;
            } catch (Exception | LinkageError e) {
                // fail-soft：物化只补值，失败就退回原文件走 Tika（正文照常，最多缺合并/格式）
                deleteQuietly(created);
                log.warn("KbOfficeParser: Excel 值物化失败，回退原文件解析 file={} 原因={}",
                    fileName, e.toString());
                return null;
            }
        }

        /** 单 sheet 物化 · 返回 {@code [合并区域数, 填充单元格数, 百分比单元格数]}。 */
        private static int[] materializeSheet(Sheet sheet, DataFormatter formatter) {
            int[] merged = fillMergedRegions(sheet, formatter);
            int percent = materializePercentCells(sheet, formatter);
            return new int[]{merged[0], merged[1], percent};
        }

        /**
         * 合并区域物化：区域内<b>除左上角外</b>的每个格子都写入左上角的显示值。
         *
         * <p>写的是 {@link DataFormatter} 出的「显示值」而不是原始数值/公式：Tika 渲染也走格式串，
         * 用显示值才能保证「填进去的文本」与「本来渲染出来的文本」逐字相同（日期、百分比都靠这点）。
         */
        private static int[] fillMergedRegions(Sheet sheet, DataFormatter formatter) {
            List<CellRangeAddress> regions = sheet.getMergedRegions();
            if (regions.isEmpty()) {
                return new int[]{0, 0};
            }
            int filled = 0;
            for (CellRangeAddress region : regions) {
                Row sourceRow = sheet.getRow(region.getFirstRow());
                Cell topLeft = sourceRow == null ? null : sourceRow.getCell(region.getFirstColumn());
                if (topLeft == null) {
                    continue;
                }
                String text = formatter.formatCellValue(topLeft);
                if (text == null || text.isEmpty()) {
                    continue;   // 左上角本来就是空 ⇒ 没有可物化的值
                }
                for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        row = sheet.createRow(r);
                    }
                    for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                        if (r == region.getFirstRow() && c == region.getFirstColumn()) {
                            continue;
                        }
                        Cell target = row.getCell(c);
                        if (target == null) {
                            target = row.createCell(c);
                        }
                        target.setCellValue(text);
                        filled++;
                    }
                }
            }
            return new int[]{regions.size(), filled};
        }

        /**
         * 百分比格式物化：把 {@link DataFormatter} 出的显示文本（{@code 12.5%}）落成字符串。
         *
         * <p>只处理百分比格式：普通数字/日期保持原样，交给 Tika。
         */
        private static int materializePercentCells(Sheet sheet, DataFormatter formatter) {
            int count = 0;
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (!isPercentFormatted(cell)) {
                        continue;
                    }
                    String text = formatter.formatCellValue(cell);
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    cell.setCellValue(text);
                    count++;
                }
            }
            return count;
        }

        private static boolean isPercentFormatted(Cell cell) {
            CellStyle style = cell.getCellStyle();
            if (style == null) {
                return false;
            }
            return isPercentFormat(style.getDataFormatString());
        }

        /**
         * 格式串是否表示百分比。
         *
         * <p>不能直接 {@code contains("%")}：格式串里引号段是字面文本（如 {@code 0.00"%"}-这类），
         * 转义字符 {@code \%} 与宽度占位 {@code _} / {@code *} 也不参与含义，必须先剥掉再判。
         */
        static boolean isPercentFormat(String format) {
            if (format == null || format.isEmpty()) {
                return false;
            }
            StringBuilder effective = new StringBuilder(format.length());
            boolean quoted = false;
            for (int i = 0; i < format.length(); i++) {
                char ch = format.charAt(i);
                if (ch == '"') {
                    quoted = !quoted;
                    continue;
                }
                if (quoted) {
                    continue;
                }
                if (ch == '\\' || ch == '_' || ch == '*') {
                    i++;   // 转义 / 宽度占位：连下一个字符一起跳过
                    continue;
                }
                effective.append(ch);
            }
            return effective.indexOf("%") >= 0;
        }
    }

    /** 临时工作簿清理（失败只记 warn：残留临时文件比让解析失败轻得多）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("KbOfficeParser: 临时工作簿清理失败（不影响解析结果）file={} 原因={}", p, e.getMessage());
        }
    }

    /** 供 KbParsers 日志/测试引用（避免魔数散落）。 */
    static boolean isExcelExtension(String ext) {
        return ext != null && EXCEL_EXT.contains(ext.toLowerCase(Locale.ROOT));
    }
}
