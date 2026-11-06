package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 解析器意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>文本层够的页不能白花钱调视觉</b> —— 每次视觉调用都是真金白银；判据写松了会把整份电子版
 *       PDF 全送模型。故「阈值边界」必须逐条钉死。</li>
 *   <li><b>扫描件必须被识别为「需要视觉」</b> —— 否则入库结果是空的，用户以为文档没内容。</li>
 *   <li><b>视觉失败不能让整份解析失败</b> —— 硬约束（派单书 §3.3）。一次超时不该让上传白费。</li>
 *   <li><b>页数上限 200 + truncated 标记</b> —— 静默只解析一部分会让用户以为索引完整。</li>
 * </ol>
 */
@DisplayName("[B2b] PDF 解析器（文本层 + 视觉补强判据 + fail-soft）")
class KbPdfParserTest {

    @Nested
    @DisplayName("视觉触发判据（派单书 §3.3 三条）")
    class VisionCriteria {

        @Test
        @DisplayName("文本为 0 → 需要视觉（扫描件）")
        void emptyTextNeedsVision() {
            assertThat(KbPdfParser.needsVision("", 0)).isTrue();
            assertThat(KbPdfParser.needsVision(null, 0)).isTrue();
            assertThat(KbPdfParser.needsVision("   \n  ", 3)).isTrue();
        }

        @Test
        @DisplayName("文本 < 100 字符 → 需要视觉（无论有没有图）")
        void shortTextNeedsVision() {
            assertThat(KbPdfParser.needsVision("九十九个字符".repeat(10), 0))
                .as("恰好 50 字符（< 100）应触发").isTrue();
            assertThat(KbPdfParser.needsVision(repeat('a', 99), 0)).isTrue();
            assertThat(KbPdfParser.needsVision(repeat('a', 99), 5)).isTrue();
        }

        @Test
        @DisplayName("文本 ≥ 100 且无图 → 不需要视觉")
        void enoughTextWithoutImagesSkipsVision() {
            assertThat(KbPdfParser.needsVision(repeat('a', 100), 0))
                .as("边界值 100 恰好不触发（判据是 < 100）").isFalse();
            assertThat(KbPdfParser.needsVision(repeat('a', 500), 0)).isFalse();
        }

        @Test
        @DisplayName("有图且文本 < 500 → 需要视觉（图里才可能是正文）")
        void imageWithThinTextNeedsVision() {
            assertThat(KbPdfParser.needsVision(repeat('a', 100), 1))
                .as("100 字符 + 有图 → 图里可能是正文，触发").isTrue();
            assertThat(KbPdfParser.needsVision(repeat('a', 499), 2)).isTrue();
        }

        @Test
        @DisplayName("有图但文本 ≥ 500 → 不需要视觉（正文已足够）")
        void imageWithRichTextSkipsVision() {
            assertThat(KbPdfParser.needsVision(repeat('a', 500), 3)).isFalse();
            assertThat(KbPdfParser.needsVision(repeat('a', 5000), 10)).isFalse();
        }

        private static String repeat(char c, int n) {
            return String.valueOf(c).repeat(n);
        }
    }

    @Nested
    @DisplayName("连续页合并（视觉调用按区间渲染）")
    class ContiguousRanges {

        @Test
        @DisplayName("[1,2,3,7] → [[1,3],[7,7]]")
        void mergesAdjacentPages() {
            var ranges = KbPdfParser.contiguousRanges(java.util.List.of(1, 2, 3, 7));
            assertThat(ranges).hasSize(2);
            assertThat(ranges.get(0)).containsExactly(1, 3);
            assertThat(ranges.get(1)).containsExactly(7, 7);
        }

        @Test
        @DisplayName("乱序输入按页号排序后合并")
        void sortsBeforeMerging() {
            var ranges = KbPdfParser.contiguousRanges(java.util.List.of(5, 1, 2, 3));
            assertThat(ranges).hasSize(2);
            assertThat(ranges.get(0)).containsExactly(1, 3);
            assertThat(ranges.get(1)).containsExactly(5, 5);
        }

        @Test
        @DisplayName("空输入 → 空区间（不抛）")
        void emptyInput() {
            assertThat(KbPdfParser.contiguousRanges(java.util.List.of())).isEmpty();
            assertThat(KbPdfParser.contiguousRanges(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("文本层解析（真实 PDF）")
    class TextLayer {

        @Test
        @DisplayName("电子版 PDF：抽出文本 + 每页 Markdown 标题，不触发视觉")
        void extractsTextLayer(@TempDir Path dir) throws Exception {
            Path pdf = KbTestDocuments.writeTextPdf(dir, 2);
            KbPdfParser parser = new KbPdfParser(null);

            KbParsedDocument result = parser.parse(pdf,
                KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .contains("## 第 1 页")
                .contains("## 第 2 页")
                .contains("Page 1")
                .contains("Page 2");
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_PAGE_COUNT, "2")
                .containsEntry(KbParsedDocument.META_PARSER, "KbPdfParser");
            assertThat(result.metadata())
                .as("文本层足够 ⇒ 不记视觉失败")
                .doesNotContainKey(KbParsedDocument.META_VISION_FAILED);
        }

        @Test
        @DisplayName("扫描件（无文本层）在没有视觉能力时：不失败、不抛，但标记需要视觉")
        void scannedPdfWithoutVisionIsSoftDegraded(@TempDir Path dir) throws Exception {
            Path pdf = KbTestDocuments.writeScannedPdf(dir);
            KbPdfParser parser = new KbPdfParser(null);

            KbParsedDocument result = parser.parse(pdf,
                KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("仍然产出页标题，文本层为空")
                .contains("## 第 1 页");
            assertThat(result.metadata())
                .as("必须留痕：为什么这页没内容")
                .containsEntry("visionSkipped", "true")
                .containsKey(KbParsedDocument.META_VISION_FAILURE_REASON);
        }
    }

    @Nested
    @DisplayName("视觉补强（fail-soft）")
    class VisionChannel {

        @Test
        @DisplayName("视觉调用抛异常 → 整份解析仍成功，metadata 记 visionFailed=true + 原因")
        void visionFailureIsFailSoft(@TempDir Path dir) throws Exception {
            Path pdf = KbTestDocuments.writeScannedPdf(dir);
            KbVisionCaller broken = new KbVisionCaller() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                    throw new IllegalStateException("模拟上游 500");
                }
            };
            KbParsedDocument result = new KbPdfParser(broken).parse(pdf,
                KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_FAILED, "true");
            assertThat(result.metadata().get(KbParsedDocument.META_VISION_FAILURE_REASON))
                .contains("第 1 页")
                .contains("模拟上游 500");
            assertThat(result.text()).as("失败不吞掉页标题").contains("## 第 1 页");
        }

        @Test
        @DisplayName("视觉成功 → 描述写进正文，图片引用带 caption")
        void visionSuccessFillsText(@TempDir Path dir) throws Exception {
            Path pdf = KbTestDocuments.writeScannedPdf(dir);
            KbVisionCaller fake = new KbVisionCaller() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                    return "这是扫描件的正文：合同编号 A-001。";
                }
            };
            KbParsedDocument result = new KbPdfParser(fake).parse(pdf,
                KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text()).contains("这是扫描件的正文：合同编号 A-001。");
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_VISION_PAGES, "1")
                .doesNotContainKey(KbParsedDocument.META_VISION_FAILED);
            assertThat(result.images())
                .as("整页渲染图必须记进 images，来源 pdf-page")
                .anySatisfy(ref -> {
                    assertThat(ref.sourceKind()).isEqualTo(KbImageRef.KIND_PDF_PAGE);
                    assertThat(ref.pageIndex()).isEqualTo(1);
                    assertThat(ref.imagePath()).endsWith(".jpg");
                    assertThat(ref.caption()).contains("合同编号 A-001");
                });
        }

        @Test
        @DisplayName("视觉回 NO_TEXT → 不把哨兵写进正文")
        void noTextSentinelIsNotWritten() {
            assertThat(KbPdfParser.isNoTextSentinel("NO_TEXT")).isTrue();
            assertThat(KbPdfParser.isNoTextSentinel("  no_text \n")).isTrue();
            assertThat(KbPdfParser.isNoTextSentinel("合同正文")).isFalse();
            assertThat(KbPdfParser.isNoTextSentinel(null)).isFalse();
        }

        @Test
        @DisplayName("ctx.visionEnabled=false → 不调视觉（省钱 + 离线可用）")
        void visionDisabledByContext(@TempDir Path dir) throws Exception {
            Path pdf = KbTestDocuments.writeScannedPdf(dir);
            int[] calls = {0};
            KbVisionCaller counting = new KbVisionCaller() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                    calls[0]++;
                    return "不该被调用";
                }
            };
            KbParsedDocument result = new KbPdfParser(counting)
                .parse(pdf, KbParseContext.defaults().withoutVision().withWorkDir(dir.resolve("imgs")));

            assertThat(calls[0]).as("关闭视觉时不允许发起任何视觉调用").isZero();
            assertThat(result.text()).doesNotContain("不该被调用");
        }
    }

    @Nested
    @DisplayName("失败语义与上限")
    class FailureAndLimits {

        @Test
        @DisplayName("不是 PDF 的文件 → KbParseException（不返回半成品）")
        void nonPdfThrows(@TempDir Path dir) throws Exception {
            Path fake = dir.resolve("假.pdf");
            java.nio.file.Files.writeString(fake, "not a pdf at all");
            assertThatThrownBy(() -> new KbPdfParser(null).parse(fake, KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class);
        }

        @Test
        @DisplayName("页标题格式单一来源")
        void pageHeadingFormat() {
            assertThat(KbPdfParser.pageHeading(3)).isEqualTo("## 第 3 页");
        }
    }
}
