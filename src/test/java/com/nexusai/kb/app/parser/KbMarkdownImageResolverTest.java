package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KbMarkdownImageResolver} 的行为测试。
 *
 * <p>覆盖派单要求的 6 条：markdown data URI / HTML img data URI / 相对路径（存在与不存在）/
 * 同一图引用两次只落一份 / 内网 URL 被拒 / 无图片时返回空列表且正文不变。
 *
 * <p>判据都落在「磁盘上有没有文件、文件字节是否等于原图」这类可观测事实上，
 * ⛔ 不靠「代码里有没有某个分支」这类文本断言（那种断言改动前后都绿）。
 */
class KbMarkdownImageResolverTest {

    /**
     * 1x1 的真实 PNG（不是伪造字节）：
     * 走 base64 解码与魔数嗅探时才不会因「不是图片」被误判。
     */
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");

    /** 另一张不同的 PNG（仅改 IEND 前的一个字节不够安全，直接换一张 1x1 灰度图）。 */
    private static final byte[] PNG2 = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    @TempDir
    Path tmp;

    private static String dataUri(byte[] image) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
    }

    @Test
    @DisplayName("① markdown 图片 data URI → 落盘 1 张，正文不被重写")
    void markdownDataUriIsStored() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        String markdown = "# 标题\n\n![示意图](" + dataUri(PNG) + ")\n\n正文";
        String before = markdown;

        List<KbImageRef> refs = KbMarkdownImageResolver.resolve(markdown, null, work);

        assertEquals(1, refs.size(), "data URI 应抽出 1 张图");
        KbImageRef ref = refs.get(0);
        assertEquals(0, ref.pageIndex(), "不属于 PDF 页 ⇒ pageIndex=0");
        assertEquals("示意图", ref.caption(), "caption 取 alt 文本");
        assertEquals(KbMarkdownImageResolver.KIND_MARKDOWN, ref.sourceKind());
        Path stored = Path.of(ref.imagePath());
        assertTrue(Files.isRegularFile(stored), "图片必须真的落盘: " + stored);
        assertTrue(stored.getFileName().toString().startsWith("md-"),
            "文件名应为 md-<序号>-<hash8>.<ext>: " + stored.getFileName());
        assertTrue(stored.getFileName().toString().endsWith(".png"));
        assertArrayEquals(PNG, Files.readAllBytes(stored), "落盘字节应与 data URI 里的原图一致");
        assertEquals(1, countFiles(work), "images 目录下应恰好 1 个文件");
        assertEquals(before, markdown, "⛔ 正文不能被重写");
    }

    @Test
    @DisplayName("② HTML img data URI → 落盘 1 张")
    void htmlImgDataUriIsStored() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        String markdown = "<p>前文</p><img class=\"pic\" src=\"" + dataUri(PNG2) + "\" alt=\"HTML图\"/>";

        List<KbImageRef> refs = KbMarkdownImageResolver.resolve(markdown, null, work);

        assertEquals(1, refs.size(), "HTML img data URI 应抽出 1 张图");
        assertEquals("HTML图", refs.get(0).caption(), "caption 取 HTML alt 属性");
        assertTrue(Files.isRegularFile(Path.of(refs.get(0).imagePath())));
        assertArrayEquals(PNG2, Files.readAllBytes(Path.of(refs.get(0).imagePath())));
        assertEquals(1, countFiles(work));
    }

    @Test
    @DisplayName("③ 相对路径：文件存在 → 1 张；不存在 → 0 张且不抛")
    void relativePathNeedsExistingFile() throws IOException {
        Path srcDir = Files.createDirectories(tmp.resolve("src"));
        Path imgDir = Files.createDirectories(srcDir.resolve("imgs"));
        Files.write(imgDir.resolve("a.png"), PNG);
        Path source = srcDir.resolve("doc.docx");
        Files.writeString(source, "doc", StandardCharsets.UTF_8);
        Path work = Files.createDirectories(tmp.resolve("work"));

        List<KbImageRef> existing = KbMarkdownImageResolver.resolve(
            "![相对](imgs/a.png)", source, work);
        assertEquals(1, existing.size(), "存在的相对路径应抽出 1 张");
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(existing.get(0).imagePath())));

        Path work2 = Files.createDirectories(tmp.resolve("work2"));
        List<KbImageRef> missing = KbMarkdownImageResolver.resolve(
            "![缺失](imgs/not-there.png)\n\n<img src=\"imgs/also-missing.png\">", source, work2);
        assertTrue(missing.isEmpty(), "相对路径不存在时应收 0 张");
        assertEquals(0, countFiles(work2), "不该写出任何文件");
    }

    @Test
    @DisplayName("④ 同一张图引用两次 → 只落 1 份")
    void duplicateReferenceStoredOnce() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        String uri = dataUri(PNG);
        String markdown = "![一](" + uri + ")\n\n![二](" + uri + ")";

        List<KbImageRef> refs = KbMarkdownImageResolver.resolve(markdown, null, work);

        assertEquals(1, refs.size(), "同一路径的引用应合并为 1 条");
        assertEquals(1, countFiles(work), "同内容只写一份文件");
    }

    @Test
    @DisplayName("⑤ 内网 / 回环 / localhost 的远程图一律被拒")
    void internalRemoteUrlsAreRejected() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        String markdown = """
            ![a](http://127.0.0.1/a.png)
            ![b](http://192.168.1.10/b.png)
            ![c](http://10.0.0.5/c.png)
            ![d](http://172.16.0.1/d.png)
            ![e](http://172.31.255.254/e.png)
            ![f](http://169.254.1.1/f.png)
            ![g](http://localhost:8080/g.png)
            <img src="http://[::1]/h.png">
            """;

        List<KbImageRef> refs = KbMarkdownImageResolver.resolve(markdown, null, work);

        assertTrue(refs.isEmpty(), "内网地址必须全部被拒，实际抽出: " + refs);
        assertEquals(0, countFiles(work));
    }

    @Test
    @DisplayName("⑥ 没有图片 → 空列表")
    void noImagesYieldsEmptyList() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        String markdown = "# 纯文本\n\n这一段没有任何图片引用。\n";

        List<KbImageRef> refs = KbMarkdownImageResolver.resolve(markdown, null, work);

        assertNotNull(refs);
        assertTrue(refs.isEmpty());
        assertEquals(0, countFiles(work), "无图时不该创建任何文件");
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
