package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EPUB 专用解析器 · 章节级溯源意图测试。
 *
 * <p><b>WHY 这些断言</b>：
 * <ol>
 *   <li><b>spine 顺序而不是 manifest 顺序</b>：夹具故意让 manifest 先列第二章、spine 先 c1；若实现按
 *       manifest 顺序拼正文，断言「第一章在第二章之前」必红 —— 这是 EPUB 阅读顺序的唯一权威来源。</li>
 *   <li><b>每章一个 section 块且 can 被 KbSourceIndex 查到</b>：只断言块数量会漏掉「块存在但区间不对」，
 *       故再用索引按块区间查询，要求取回 section locator（序号 + 标题 + 引用片段）。</li>
 *   <li><b>标题来源两级</b>：第一章 {@code <title>} 与 h1 故意写得不同（元标题 vs 正文标题），要求
 *       locator.title 取 {@code <title>}；第二章没有 {@code <title>}，要求回落到 h1。</li>
 *   <li><b>坏 zip / 缺 container 必须抛</b>：⛔ 返回空文本会被上层当「解析成功但无内容」入库。</li>
 * </ol>
 *
 * <p>⛔ 不用 {@code @SpringBootTest}（本仓铁律：会迁移用户真库）：解析器直接 new。
 */
@DisplayName("[C22] EPUB 解析器（spine 顺序 + 章节级 section 溯源）")
class KbEpubParserTest {

    private static final String CH1_TITLE = "第一章 元标题";
    private static final String CH1_BODY = "第一章的正文内容，甲。";
    private static final String CH2_BODY = "第二章的正文内容，乙。";

    private final KbEpubParser parser = new KbEpubParser();

    @Test
    @DisplayName("类型识别：只认 epub 扩展名；无后缀时认 application/epub+zip")
    void supportsEpubOnly() {
        assertThat(parser.supports("电子书.epub", null)).isTrue();
        assertThat(parser.supports("电子书.EPUB", null)).isTrue();
        assertThat(parser.supports("无后缀", "application/epub+zip")).isTrue();
        assertThat(parser.supports("无后缀", "application/epub+zip; charset=binary")).isTrue();
        assertThat(parser.supports("a.docx", null)).isFalse();
        assertThat(parser.supports("a.xmind", null)).isFalse();
        assertThat(parser.supports("a.zip", "application/zip")).isFalse();
        assertThat(parser.supportedExtensions()).containsExactly("epub");
        assertThat(parser.displayName()).isEqualTo("内置 EPUB 解析");
    }

    @Test
    @DisplayName("两章：正文按 spine 顺序拼接、两个换行分隔、两章各一个 section 块")
    void twoChaptersKeepSpineOrderAndSectionBlocks(@TempDir Path dir) throws Exception {
        Path epub = writeEpub(dir);
        KbParsedDocument doc = parser.parse(epub, KbParseContext.defaults().withWorkDir(dir.resolve("work")));

        String text = doc.text();
        int ch1At = text.indexOf("第一章的正文内容");
        int ch2At = text.indexOf("第二章的正文内容");
        assertThat(ch1At).as("第一章正文缺失: %s", text).isGreaterThanOrEqualTo(0);
        assertThat(ch2At).as("第二章正文缺失: %s", text).isGreaterThan(ch1At);

        List<KbSourceBlock> blocks = doc.sourceBlocks();
        assertThat(blocks).as("两个有正文的章节应各产一个 section 块").hasSize(2);

        int totalCp = text.codePointCount(0, text.length());
        KbSourceBlock first = blocks.get(0);
        KbSourceBlock second = blocks.get(1);
        assertThat(first.start()).isZero();
        assertThat(first.end()).isGreaterThan(first.start());
        assertThat(second.start()).isGreaterThan(first.end());
        assertThat(second.end()).isEqualTo(totalCp);

        // 章节间隔恰好是两个换行，且不属于任何块
        assertThat(second.start()).isEqualTo(first.end() + 2);
        assertThat(new String(text.codePoints().toArray(), first.end(), 2)).isEqualTo("\n\n");

        // 块区间内的文本确实是对应章节
        String firstSlice = slice(text, first.start(), first.end());
        String secondSlice = slice(text, second.start(), second.end());
        assertThat(firstSlice).contains("第一章的正文内容").doesNotContain("第二章的正文内容");
        assertThat(secondSlice).contains("第二章的正文内容").doesNotContain("第一章的正文内容");

        // 标题：第一章取 <title>，第二章回落 h1
        assertThat(first.locator().type()).isEqualTo(KbSourceLocator.TYPE_SECTION);
        assertThat(first.locator().section()).isEqualTo(1);
        assertThat(first.locator().title()).isEqualTo(CH1_TITLE);
        assertThat(second.locator().type()).isEqualTo(KbSourceLocator.TYPE_SECTION);
        assertThat(second.locator().section()).isEqualTo(2);
        assertThat(second.locator().title()).isEqualTo("第二章 正文标题");

        // 图片本批不抽
        assertThat(doc.images()).isEmpty();
        assertThat(doc.metadata().get(KbEpubParser.META_CHAPTER_COUNT)).isEqualTo("2");
        assertThat(doc.metadata().get(KbParsedDocument.META_SOURCE_BLOCKS)).isEqualTo("2");
        assertThat(doc.metadata().get(KbParsedDocument.META_PARSER)).isEqualTo("KbEpubParser");
    }

    @Test
    @DisplayName("KbSourceIndex 能按块区间查到 section locator（序号 / 标题 / 引用片段）")
    void sourceIndexFindsSectionLocatorByRange(@TempDir Path dir) throws Exception {
        KbParsedDocument doc = parser.parse(writeEpub(dir), KbParseContext.defaults());
        List<KbSourceBlock> blocks = doc.sourceBlocks();
        KbSourceIndex index = KbSourceIndex.of(doc.text(), blocks);

        List<KbSourceLocator> first = index.locators(blocks.get(0).start(), blocks.get(0).end());
        assertThat(first).hasSize(1);
        assertThat(first.get(0).type()).isEqualTo(KbSourceLocator.TYPE_SECTION);
        assertThat(first.get(0).section()).isEqualTo(1);
        assertThat(first.get(0).title()).isEqualTo(CH1_TITLE);
        assertThat(first.get(0).partial()).isFalse();
        assertThat(first.get(0).quote()).contains("第一章的正文内容");

        List<KbSourceLocator> second = index.locators(blocks.get(1).start(), blocks.get(1).end());
        assertThat(second).hasSize(1);
        assertThat(second.get(0).section()).isEqualTo(2);
        assertThat(second.get(0).title()).isEqualTo("第二章 正文标题");
        assertThat(second.get(0).quote()).contains("第二章的正文内容");

        // 位置在第二章内部（不带 quote 的查询）也应落到 section=2
        List<KbSourceLocator> at = index.locatorsAt(blocks.get(1).start() + 1);
        assertThat(at).hasSize(1);
        assertThat(at.get(0).section()).isEqualTo(2);
    }

    @Test
    @DisplayName("坏 zip ⇒ KbParseException（不返回空文本）")
    void corruptZipThrows(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("broken.epub");
        Files.write(bad, "PK\u0003\u0004这不是一个真正的 zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(bad, KbParseContext.defaults()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("EPUB");
    }

    @Test
    @DisplayName("缺 META-INF/container.xml ⇒ KbParseException（不返回空文本）")
    void missingContainerThrows(@TempDir Path dir) throws Exception {
        Path epub = dir.resolve("no-container.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            putStoredMimetype(zip);
            put(zip, "OEBPS/ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>x</p></body></html>");
        }

        assertThatThrownBy(() -> parser.parse(epub, KbParseContext.defaults()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("container.xml");
    }

    // ════════════════════════════════════════════════════════════════════
    // 夹具：2 章 EPUB（manifest 顺序故意与 spine 相反）
    // ════════════════════════════════════════════════════════════════════

    private static Path writeEpub(Path dir) throws IOException {
        Path file = dir.resolve("两章.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            putStoredMimetype(zip);

            put(zip, "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" "
                    + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                    + "<rootfile full-path=\"OEBPS/content.opf\" "
                    + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");

            // manifest 先列 c2、再列 c1；spine 则是 c1 → c2（阅读顺序只能看 spine）
            put(zip, "OEBPS/content.opf",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" "
                    + "unique-identifier=\"uid\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:identifier id=\"uid\">urn:uuid:22222222-2222-2222-2222-222222222222</dc:identifier>"
                    + "<dc:title>两章电子书</dc:title><dc:language>zh</dc:language></metadata>"
                    + "<manifest>"
                    + "<item id=\"c2\" href=\"text/ch2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "<item id=\"c1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "</manifest>"
                    + "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");

            put(zip, "OEBPS/text/ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + CH1_TITLE
                    + "</title></head><body><h1>第一章 正文标题</h1><p>" + CH1_BODY
                    + "</p></body></html>");

            put(zip, "OEBPS/text/ch2.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head>"
                    + "<body><h1>第二章 正文标题</h1><p>" + CH2_BODY + "</p></body></html>");
        }
        return file;
    }

    /** {@code mimetype} 必须是 zip 第一项且不压缩（EPUB 规范要求）。 */
    private static void putStoredMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    @Test
    @DisplayName("默认路由：epub 落到本类而不是 Tika 的 KbOfficeParser（@Order 18 先于 20）")
    void winsDefaultRoutingOverOfficeParser() {
        KbParserRegistry registry = new KbParserRegistry(List.of(
            new KbPdfParser(null), new KbAnydocParser(), new KbImageParser(null),
            new KbEpubParser(), new KbOfficeParser(), new KbTextParser()));

        assertThat(registry.select("book.epub", null).name())
            .as("本类的 section 溯源只有默认路由下才生效；落回 Office 就全白做")
            .isEqualTo("KbEpubParser");
        // 对照组：xlsx 仍归 Office（本类不扩张到别的类型）
        assertThat(registry.select("sheet.xlsx", null).name()).isEqualTo("KbOfficeParser");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 按码点切片（⛔ 不用 char 下标）。 */
    private static String slice(String text, int start, int end) {
        int[] cps = text.codePoints().toArray();
        return new String(cps, start, end - start);
    }
}
