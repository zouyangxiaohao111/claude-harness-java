package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片解析器意图测试。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>弱降级不抛异常</b> —— 未配置多模态模型 / 调用方关闭视觉 / 模型超时，三种情况都必须
 *       「返回空文本 + visionFailed=true」，而不是让整份入库失败。这是「用户上传白费」与
 *       「少一段内容」的区别；⛔ 若改成抛异常，测试必须红。</li>
 *   <li><b>路由边界</b> —— 图片排在 PDF(10) 之后 Office(20) 之前，且不能抢走 pdf/docx。
 *       位置错了照常「能解析」，只是顺序语义被破坏，没有任何报错 —— 只能靠断言钉住。</li>
 *   <li><b>BMP/TIFF 必须先转 PNG</b> —— 视觉通道按扩展名判 mediaType，不认识的当 JPEG 发出去。
 *       这条断言直接检查「视觉调用方收到的文件名」，而不是只看最终文本。</li>
 * </ol>
 */
@DisplayName("[B2b] 图片解析器（视觉模型 → Markdown）")
class KbImageParserTest {

    /** 造一张真实 PNG/BMP/TIFF。 */
    private static Path writeImage(Path dir, String fmt) throws Exception {
        Path file = dir.resolve("样例." + fmt);
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0xFF336699);
        boolean ok = ImageIO.write(img, fmt, file.toFile());
        img.flush();
        assertThat(ok).as("本机 ImageIO 必须支持 %s（否则夹具无效）", fmt).isTrue();
        return file;
    }

    /** 可控的假视觉调用方。 */
    private static KbVisionCaller fake(boolean available, String reply, RuntimeException boom,
                                       AtomicReference<Path> received) {
        return new KbVisionCaller() {
            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                if (received != null) {
                    received.set(imageFile);
                }
                if (boom != null) {
                    throw boom;
                }
                return reply;
            }
        };
    }

    private static KbParseContext ctx(Path dir) {
        return KbParseContext.defaults().withWorkDir(dir.resolve("imgs"));
    }

    @Nested
    @DisplayName("类型识别")
    class Supports {

        @Test
        @DisplayName("图片扩展名全部识别（大小写不敏感）")
        void recognisesImageExtensions() {
            KbImageParser parser = new KbImageParser(null);
            for (String ext : KbImageParser.IMAGE_EXTENSIONS) {
                assertThat(parser.supports("a." + ext, null)).as("扩展名 %s", ext).isTrue();
            }
            assertThat(parser.supports("A.JPG", null)).as("扩展名大小写不敏感").isTrue();
        }

        @Test
        @DisplayName("扩展名缺失时按 image/* MIME 识别")
        void recognisesImageMime() {
            KbImageParser parser = new KbImageParser(null);
            assertThat(parser.supports("noext", "image/png")).isTrue();
            assertThat(parser.supports("noext", "IMAGE/JPEG")).isTrue();
            assertThat(parser.supports("noext", "text/plain")).isFalse();
            assertThat(parser.supports("noext", null)).isFalse();
        }

        @Test
        @DisplayName("⛔ SVG / HEIC 不接管（ImageIO 读不出，二进制发给模型只会拿到垃圾描述）")
        void rejectsUnsupportedImageSubtypes() {
            KbImageParser parser = new KbImageParser(null);
            assertThat(parser.supports("logo.svg", "image/svg+xml")).isFalse();
            assertThat(parser.supports("noext", "image/svg+xml")).isFalse();
            assertThat(parser.supports("photo.heic", "image/heic")).isFalse();
            assertThat(parser.supports("noext", "image/heic")).isFalse();
        }

        @Test
        @DisplayName("⛔ 不抢 PDF/Office/文本")
        void doesNotClaimOtherFormats() {
            KbImageParser parser = new KbImageParser(null);
            assertThat(parser.supports("a.pdf", "application/pdf")).isFalse();
            assertThat(parser.supports("a.docx", null)).isFalse();
            assertThat(parser.supports("a.csv", "text/csv")).isFalse();
        }

        @Test
        @DisplayName("能力声明与 supports 同源")
        void declaresSameExtensionsItRoutes() {
            KbImageParser parser = new KbImageParser(null);
            assertThat(parser.supportedExtensions()).isEqualTo(KbImageParser.IMAGE_EXTENSIONS);
            assertThat(parser.displayName()).isEqualTo("视觉模型解析");
        }
    }

    @Nested
    @DisplayName("正常解析")
    class HappyPath {

        @Test
        @DisplayName("视觉描述作为正文；metadata 齐备且无 visionFailed")
        void returnsDescription(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            KbImageParser parser = new KbImageParser(
                fake(true, "## 图片内容\n\n| a | b |\n| --- | --- |", null, null));

            KbParsedDocument result = parser.parse(png, ctx(dir));

            assertThat(result.text())
                .contains("## 图片内容")
                .contains("| a | b |");
            assertThat(result.images()).as("源图本身不重复登记为副产物").isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbImageParser")
                .containsEntry(KbParsedDocument.META_FILE_NAME, png.getFileName().toString())
                .containsEntry(KbParsedDocument.META_MIME_TYPE, "image/png")
                .containsEntry(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(result.text().length()))
                .doesNotContainKey(KbParsedDocument.META_VISION_FAILED);
        }

        @Test
        @DisplayName("BMP / TIFF / GIF 先转 PNG 再交给视觉模型（否则被当成 JPEG 发出去）")
        void convertsBmpAndTiffToPng(@TempDir Path dir) throws Exception {
            for (String fmt : new String[]{"bmp", "tiff", "gif"}) {
                Path img = writeImage(dir, fmt);
                AtomicReference<Path> received = new AtomicReference<>();
                KbImageParser parser = new KbImageParser(fake(true, "描述", null, received));

                KbParsedDocument result = parser.parse(img, ctx(dir));

                assertThat(received.get()).as("%s 应被转码后再送模型", fmt).isNotNull();
                assertThat(received.get().getFileName().toString())
                    .as("视觉通道只认 png/webp/gif 的文件名后缀，%s 必须转成 .png", fmt)
                    .endsWith(".png");
                assertThat(received.get()).isNotEqualTo(img);
                assertThat(result.text()).isEqualTo("描述");
                assertThat(Files.exists(received.get())).as("临时转码文件用完必须删掉").isFalse();
            }
        }

        @Test
        @DisplayName("PNG / JPEG 原样透传（不产生多余转码文件）")
        void passesPngAndJpegThrough(@TempDir Path dir) throws Exception {
            // ⚠️ 不含 webp：本机 JDK 的 ImageIO 没有 webp <b>编码器</b>（只能读不能写），造不出夹具。
            //    路由层面的 webp 已在 Supports 用例覆盖。
            for (String fmt : new String[]{"png", "jpg"}) {
                Path img = writeImage(dir, fmt);
                AtomicReference<Path> received = new AtomicReference<>();
                new KbImageParser(fake(true, "描述", null, received)).parse(img, ctx(dir));
                assertThat(received.get()).as("%s 不该被转码", fmt).isEqualTo(img);
            }
        }

        @Test
        @DisplayName("超过上限的图片 → 降级并说明原因（不把超大字节能塞给模型）")
        void oversizedImageDegrades(@TempDir Path dir) throws Exception {
            Path img = dir.resolve("huge.png");
            java.nio.file.Files.write(img, new byte[(int) KbImageParser.MAX_IMAGE_BYTES + 1]);
            AtomicReference<Path> received = new AtomicReference<>();
            KbParsedDocument result = new KbImageParser(fake(true, "不该被调用", null, received))
                .parse(img, ctx(dir));

            assertThat(received.get()).as("⛔ 超大图不该发出请求").isNull();
            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true");
            assertThat(result.metadata().get(KbParsedDocument.META_VISION_FAILURE_REASON))
                .contains("图片过大");
        }
    }

    @Nested
    @DisplayName("弱降级（fail-soft：不抛异常）")
    class Degrade {

        @Test
        @DisplayName("视觉能力未配置 → 空文本 + visionFailed + 原因")
        void unavailableVision(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            KbParsedDocument result = new KbImageParser(fake(false, null, null, null)).parse(png, ctx(dir));

            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true")
                .containsEntry(KbParsedDocument.META_VISION_FAILURE_REASON, "视觉能力未配置")
                .containsEntry(KbParsedDocument.META_TEXT_LENGTH, "0")
                .containsEntry(KbParsedDocument.META_PARSER, "KbImageParser");
        }

        @Test
        @DisplayName("调用方关闭视觉（visionEnabled=false）→ 不调用模型，直接降级")
        void visionDisabledByCaller(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            AtomicReference<Path> received = new AtomicReference<>();
            KbParsedDocument result = new KbImageParser(fake(true, "不该被调用", null, received))
                .parse(png, ctx(dir).withoutVision());

            assertThat(received.get()).as("关闭视觉后 ⛔ 一次模型调用都不该发生").isNull();
            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true")
                .containsEntry(KbParsedDocument.META_VISION_FAILURE_REASON, "调用方已关闭视觉补强");
        }

        @Test
        @DisplayName("构造器传 null（离线单测）→ 降级而非 NPE")
        void nullCallerDegrades(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            KbParsedDocument result = new KbImageParser(null).parse(png, ctx(dir));

            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true");
        }

        @Test
        @DisplayName("模型调用抛异常 → catch 住，原因写进 metadata")
        void exceptionIsSwallowed(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            KbParsedDocument result = new KbImageParser(
                fake(true, null, new IllegalStateException("provider 429"), null)).parse(png, ctx(dir));

            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true")
                .containsEntry(KbParsedDocument.META_VISION_FAILURE_REASON, "provider 429");
        }

        @Test
        @DisplayName("模型返回空/空白 → 降级且原因可区分（不是「失败」也不是「未配置」）")
        void emptyReplyDegrades(@TempDir Path dir) throws Exception {
            Path png = writeImage(dir, "png");
            KbParsedDocument result = new KbImageParser(fake(true, "   ", null, null)).parse(png, ctx(dir));

            assertThat(result.text()).isEmpty();
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true")
                .containsEntry(KbParsedDocument.META_VISION_FAILURE_REASON, "视觉模型返回空内容");
        }
    }

    @Nested
    @DisplayName("注册表路由")
    class Registry {

        @Test
        @DisplayName("@Order(15)：PDF(10) → 图片(15) → Office(20) → 文本(30)")
        void orderedBetweenPdfAndOffice() {
            KbParserRegistry registry = new KbParserRegistry(java.util.List.of(
                new KbTextParser(), new KbPdfParser(null), new KbOfficeParser(), new KbImageParser(null)));
            assertThat(registry.parsers())
                .extracting(KbDocumentParser::name)
                .containsExactly("KbPdfParser", "KbImageParser", "KbOfficeParser", "KbTextParser");
        }

        @Test
        @DisplayName("图片扩展名路由到图片解析器，PDF/Office 不被抢")
        void routesImages() {
            KbParserRegistry registry = new KbParserRegistry(java.util.List.of(
                new KbTextParser(), new KbPdfParser(null), new KbOfficeParser(), new KbImageParser(null)));
            assertThat(registry.select("a.png", null)).isInstanceOf(KbImageParser.class);
            assertThat(registry.select("a.webp", null)).isInstanceOf(KbImageParser.class);
            assertThat(registry.select("noext", "image/gif")).isInstanceOf(KbImageParser.class);
            assertThat(registry.select("a.pdf", null)).isInstanceOf(KbPdfParser.class);
            assertThat(registry.select("a.docx", null)).isInstanceOf(KbOfficeParser.class);
        }
    }
}
