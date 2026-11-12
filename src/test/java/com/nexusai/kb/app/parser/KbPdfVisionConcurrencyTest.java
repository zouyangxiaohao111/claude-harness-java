package com.nexusai.kb.app.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 视觉补强的<b>并发行为</b>单测（纯单测，不启 Spring）。
 *
 * <p><b>WHY 必须有这组用例</b>：识别阶段从「逐页串行」改成「有界线程池并发」后，两件事很容易悄悄坏掉：
 * <ol>
 *   <li><b>并发没真的发生</b> —— 线程池大小被算成 1、或者 callable 被串行执行，性能收益为 0；
 *       这种「看起来并发、实际串行」的降级不会报错，只能靠「同时进入调用的人数 &gt; 1」这条判据抓住。</li>
 *   <li><b>回填页序漂移</b> —— 并发完成顺序随机；若按完成顺序写 {@code pageTexts}/{@code images}，
 *       正文页序会随调度抖动，同一份 PDF 两次解析结果不同。故必须断言「页号升序」。</li>
 * </ol>
 *
 * <p><b>并发判据怎么做到不 flaky</b>：不是「睡一会儿再看计数」，而是要求至少两个调用方在
 * {@link CyclicBarrier} 上会合 —— 串行执行时第一个调用只能等到超时才返回（最大并发恒为 1），
 * 并发执行时两个调用立刻会合（最大并发 ≥2）。这样判定是<b>由调用方行为驱动</b>的，不依赖机器快慢。
 */
@DisplayName("[B2b] PDF 视觉补强 · 有界并发 + 回填页序")
class KbPdfVisionConcurrencyTest {

    @Test
    @DisplayName("多页扫描件：视觉调用确实并发（最大同时调用数 >= 2 且不超过并发度）且正文/图片按页号升序回填")
    void visionCallsRunConcurrentlyAndFillPagesInOrder(@TempDir Path dir) throws Exception {
        int pages = 4;
        int concurrency = 3;
        Path pdf = writeScannedPdf(dir, pages);

        AtomicInteger current = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        // 两个调用方会合才放行：串行时第一个调用只能等超时 ⇒ maxConcurrent 恒为 1
        CyclicBarrier rendezvous = new CyclicBarrier(2);
        AtomicIntegerArray startedPerPage = new AtomicIntegerArray(pages + 1);
        java.util.Set<String> threadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

        KbVisionCaller caller = new KbVisionCaller() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                int pageNo = pageOf(imageFile);
                // 线程工厂必须带名字（否则线程转储里认不出是谁在调模型）
                threadNames.add(Thread.currentThread().getName());
                startedPerPage.incrementAndGet(pageNo);
                int now = current.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    // 并发：两两会合立刻通过；串行（变异 / 池大小=1）时等到超时再继续
                    rendezvous.await(800, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // 超时/中断都继续：本轮判据只看 maxConcurrent
                } finally {
                    current.decrementAndGet();
                }
                return "第" + pageNo + "页视觉正文";
            }
        };

        KbParsedDocument result = new KbPdfParser(caller, concurrency)
            .parse(pdf, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(maxConcurrent.get())
            .as("视觉调用必须真的并发：最大同时调用数应 >= 2（串行实现恒为 1）")
            .isGreaterThanOrEqualTo(2)
            .as("并发度必须有界：同时调用数不得超过注入的并发度 %s", concurrency)
            .isLessThanOrEqualTo(concurrency);
        assertThat(threadNames)
            .as("池线程必须来自带名字的线程工厂（实例名 kb-pdf-vision）")
            .isNotEmpty()
            .allMatch(name -> name.startsWith("kb-pdf-vision"));
        for (int p = 1; p <= pages; p++) {
            assertThat(startedPerPage.get(p)).as("第 %s 页必须恰好调用一次视觉", p).isEqualTo(1);
        }

        assertThat(result.metadata())
            .containsEntry(KbParsedDocument.META_VISION_PAGES, String.valueOf(pages))
            .doesNotContainKey(KbParsedDocument.META_VISION_FAILED);

        // 页序：每页标题后紧跟该页自己的描述，且描述顺序严格递增（= 页号升序）
        int prev = -1;
        for (int p = 1; p <= pages; p++) {
            int idx = result.text().indexOf("第" + p + "页视觉正文");
            assertThat(idx).as("第 %s 页描述必须写进正文", p).isGreaterThan(-1);
            assertThat(idx).as("页序必须升序（并发完成顺序不得影响回填）page=%s", p).isGreaterThan(prev);
            prev = idx;
        }

        assertThat(result.images())
            .as("图片引用必须按页号升序追加，与串行版一致")
            .extracting(KbImageRef::pageIndex)
            .containsExactly(1, 2, 3, 4);
        assertThat(result.images())
            .extracting(KbImageRef::sourceKind)
            .containsOnly(KbImageRef.KIND_PDF_PAGE);
    }

    @Test
    @DisplayName("并发路径下 fail-soft 不变：单页失败只丢该页补强，其余页正常回填且页序不乱")
    void singlePageFailureStaysFailSoft(@TempDir Path dir) throws Exception {
        int pages = 3;
        Path pdf = writeScannedPdf(dir, pages);

        KbVisionCaller caller = new KbVisionCaller() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                int pageNo = pageOf(imageFile);
                if (pageNo == 2) {
                    throw new IllegalStateException("模拟第 2 页上游 500");
                }
                return "第" + pageNo + "页视觉正文";
            }
        };

        KbParsedDocument result = new KbPdfParser(caller, 2)
            .parse(pdf, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.metadata())
            .as("发生过失败必须留痕，且原因是第 2 页")
            .containsEntry(KbParsedDocument.META_VISION_FAILED, "true");
        assertThat(result.metadata().get(KbParsedDocument.META_VISION_FAILURE_REASON))
            .contains("第 2 页")
            .contains("模拟第 2 页上游 500");

        assertThat(result.text())
            .as("失败页保留文本层（扫描页为空），其余页补强照常")
            .contains("第1页视觉正文")
            .contains("第3页视觉正文")
            .contains("## 第 2 页");

        List<Integer> pagesInImages = result.images().stream().map(KbImageRef::pageIndex).toList();
        assertThat(pagesInImages)
            .as("失败页仍保留图片引用，且页号升序")
            .containsExactly(1, 2, 3);
    }

    /** 从渲染出的 {@code page-0N.jpg} 文件名反解页号。 */
    private static int pageOf(Path jpg) {
        String name = jpg.getFileName().toString();
        int dash = name.lastIndexOf("page-");
        String digits = name.substring(dash + 5, dash + 7);
        return Integer.parseInt(digits);
    }

    /**
     * 造一个 N 页「扫描件」PDF：每页整页一张图 + 无文本层 ⇒ 每页都判定为扫描页走视觉。
     */
    private static Path writeScannedPdf(Path dir, int pages) throws IOException {
        Path file = dir.resolve("扫描件-" + pages + "页.pdf");
        PDRectangle a4 = new PDRectangle(595f, 842f);
        BufferedImage pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(a4);
                doc.addPage(page);
                PDImageXObject img = LosslessFactory.createFromImage(doc, pixels);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, 0f, 0f, 595f, 842f);
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }
}
