package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * anydoc 内嵌图片抽取（C19）· 真 DLL 探针式单测。
 *
 * <p><b>为什么不用 {@code @SpringBootTest}</b>：本仓铁律（会迁移用户真库）。解析器本身不依赖
 * Spring 容器，直接 new {@link KbAnydocParser} + {@link KbParseContext} 即可。
 *
 * <p><b>用真实随包 DLL</b>：图片抽取的判据是「原生文档模型里有没有字节」，mock 掉原生层就只剩
 * 自证。非 Windows x64（无随包库）时用 {@link Assumptions#assumeTrue} 跳过，不让跨平台 CI 变红。
 *
 * <p><b>三条用例</b>：
 * <ol>
 *   <li>含 1 张 PNG 的 docx → images 非空、文件确实落盘且是 PNG，正文<b>不含</b> {@code ![](}（没被改写）</li>
 *   <li>无图 docx → images 空</li>
 *   <li>坏文件 → {@link AnydocNative#toMarkdown(Path)} 仍抛原有 {@link AnydocException}；
 *       {@link KbAnydocParser} 仍按既有 fail-soft 返回空产物 + {@code fallbackReason}</li>
 * </ol>
 */
class KbAnydocAssetsTest {

    /** 1x1 PNG（与探针用的是同一张）。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    void oneImageDocxYieldsImageRefAndKeepsTextUnchanged(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(AnydocNative.isAvailable(),
            "anydoc 原生库不可用（非 Windows x64 / 未随包）：跳过真 DLL 用例");
        Path docx = writeDocx(tmp, "one-image.docx", true);

        KbAnydocParser parser = new KbAnydocParser();
        KbParsedDocument result = parser.parse(docx, KbParseContext.defaults().withWorkDir(tmp.resolve("work")));

        List<KbImageRef> images = result.images();
        assertEquals(1, images.size(), "应抽出 1 张内嵌图片，实际=" + images);
        KbImageRef ref = images.get(0);
        assertEquals(KbImageRef.KIND_OFFICE_EMBEDDED, ref.sourceKind());
        assertEquals(0, ref.pageIndex());
        Path imageFile = Path.of(ref.imagePath());
        assertTrue(Files.isRegularFile(imageFile), "图片文件不存在: " + imageFile);
        assertEquals(tmp.resolve("work").resolve("images").toAbsolutePath().normalize(),
            imageFile.toAbsolutePath().normalize().getParent(), "图片应落在 workDir/images 下");
        assertTrue(imageFile.getFileName().toString().startsWith("anydoc-"), "命名应为 anydoc-<hash8>.<ext>");
        assertTrue(imageFile.getFileName().toString().endsWith(".png"), "扩展名应为 .png");
        byte[] written = Files.readAllBytes(imageFile);
        assertTrue(written.length >= 8 && (written[0] & 0xFF) == 0x89 && written[1] == 'P'
            && written[2] == 'N' && written[3] == 'G', "落盘内容不是 PNG");
        assertEquals(PNG.length, written.length, "落盘字节应与源容器里的图片等长");

        // 正文零变化：anydoc_to_markdown 仍只出文字（图片位置不会变成 ![](...)）
        assertTrue(result.text().contains("Before image"), "正文丢了：" + result.text());
        assertTrue(result.text().contains("After image"), "正文丢了：" + result.text());
        assertFalse(result.text().contains("![]("), "正文被图片链接改写了：" + result.text());
        assertEquals("1", result.metadata().get(KbAnydocParser.META_IMAGE_COUNT));
    }

    @Test
    void docxWithoutImageHasNoImageRefs(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(AnydocNative.isAvailable(),
            "anydoc 原生库不可用（非 Windows x64 / 未随包）：跳过真 DLL 用例");
        Path docx = writeDocx(tmp, "no-image.docx", false);

        KbParsedDocument result = new KbAnydocParser()
            .parse(docx, KbParseContext.defaults().withWorkDir(tmp.resolve("work")));

        assertTrue(result.images().isEmpty(), "无图文档不该产出图片引用，实际=" + result.images());
        assertTrue(result.text().contains("noimg"), "正文丢了：" + result.text());
        assertFalse(result.metadata().containsKey(KbAnydocParser.META_IMAGE_COUNT));
    }

    @Test
    void corruptFileKeepsOriginalExceptionAndFailSoft(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(AnydocNative.isAvailable(),
            "anydoc 原生库不可用（非 Windows x64 / 未随包）：跳过真 DLL 用例");
        Path bad = tmp.resolve("corrupt.docx");
        Files.write(bad, "PK\u0003\u0004this is not a real zip".getBytes(StandardCharsets.UTF_8));

        // 原有异常行为不变（AnydocNative 层仍抛 AnydocException）
        AnydocException thrown = assertThrows(AnydocException.class, () -> AnydocNative.toMarkdown(bad));
        assertNotNull(thrown.getMessage());

        // 解析器原有 fail-soft 不变：空文本 + fallbackReason，不抛
        KbParsedDocument result = new KbAnydocParser()
            .parse(bad, KbParseContext.defaults().withWorkDir(tmp.resolve("work")));
        assertEquals("", result.text());
        assertTrue(result.images().isEmpty());
        assertNotNull(result.metadata().get(KbAnydocParser.META_FALLBACK_REASON),
            "坏文件应带 fallbackReason");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 最小 OOXML 夹具
    // ════════════════════════════════════════════════════════════════════════

    private static final String CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Default Extension="png" ContentType="image/png"/>
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>""";

    private static final String ROOT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>""";

    private static final String DOC_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
        </Relationships>""";

    private static final String DRAWING = """
        <w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
        <wp:extent cx="9525" cy="9525"/><wp:docPr id="1" name="Picture 1"/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="image1.png"/><pic:cNvPicPr/></pic:nvPicPr>
        <pic:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
        <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9525" cy="9525"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
        </a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""";

    private static String documentXml(boolean withImage) {
        String body = withImage
            ? "<w:p><w:r><w:t>Before image</w:t></w:r></w:p>" + DRAWING
                + "<w:p><w:r><w:t>After image</w:t></w:r></w:p>"
            : "<w:p><w:r><w:t>Before image</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>noimg</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>After image</w:t></w:r></w:p>";
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
             xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
             xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
             xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <w:body>""" + body + "</w:body></w:document>";
    }

    private static Path writeDocx(Path dir, String name, boolean withImage) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "word/document.xml", documentXml(withImage));
            if (withImage) {
                put(zip, "word/_rels/document.xml.rels", DOC_RELS);
                zip.putNextEntry(new ZipEntry("word/media/image1.png"));
                zip.write(PNG);
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String entry, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
