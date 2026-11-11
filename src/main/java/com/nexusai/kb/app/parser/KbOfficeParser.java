package com.nexusai.kb.app.parser;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * <p><b>为什么不用 Tika 的纯文本输出</b>：实测（本机 Tika 2.9.2 对 POI 生成的 .docx/.xlsx）纯文本会把
 * 表格拍平成 {@code \t项目\t金额\t}，表格结构不可恢复。故走 XHTML 事件流 → {@link KbMarkdownHandler}
 * 转成 Markdown（表格 → {@code | 项目 | 金额 |} + 分隔行；Excel 的 sheet 名 → {@code ## 名称}；
 * PPT 的 {@code slide-content} → {@code ## Slide N}）。
 *
 * <p><b>内嵌图片</b>：自定义 {@link EmbeddedDocumentExtractor} 拦截 Tika 的内嵌资源流；只有能
 * {@link ImageIO#read} 成功的才落盘（EMF/WMF 这类矢量嵌入 read 会 null，直接跳过，不写空文件）。
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
    static final Set<String> EXCEL_EXT = Set.of("xls", "xlsx", "xlsm", "xlt", "xltx");
    static final Set<String> PPT_EXT = Set.of("ppt", "pptx", "pptm", "pot", "potx");
    /** 电子书与网页归档（源站由 builtin 引擎覆盖；Tika 同一套解析器即可处理）。 */
    static final Set<String> EBOOK_WEB_EXT = Set.of("epub", "mhtml");
    private static final Set<String> OFFICE_EXT = union();

    private static Set<String> union() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.addAll(WORD_EXT);
        all.addAll(EXCEL_EXT);
        all.addAll(PPT_EXT);
        all.addAll(EBOOK_WEB_EXT);
        return Set.copyOf(all);
    }

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
     * （Word/Excel/PPT 三族 + 电子书/网页归档的并集）。
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
        try {
            ParseContext parseCtx = new ParseContext();
            imageDir = KbImageSink.resolveImageDir(file, ctx);
            collector = new ImageCollector(imageDir);
            parseCtx.set(EmbeddedDocumentExtractor.class, collector);
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
                KbTikaSupport.parser().parse(in, handler, md, parseCtx);
            }
        } catch (Exception e) {
            throw KbTikaSupport.wrap("Office 文档解析", file, e);
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
            List<KbSourceIndex.Unit> units = KbOfficeSourceBlocks.units(ext, file);
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

    /** 供 KbParsers 日志/测试引用（避免魔数散落）。 */
    static boolean isExcelExtension(String ext) {
        return ext != null && EXCEL_EXT.contains(ext.toLowerCase(Locale.ROOT));
    }
}
