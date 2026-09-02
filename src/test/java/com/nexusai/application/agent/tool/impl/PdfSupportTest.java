package com.nexusai.application.agent.tool.impl;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [P-CC-01] {@link PdfSupport} 单元契约 · 对齐 CC {@code utils/pdf.ts} + {@code utils/pdfUtils.ts}。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * PDF 解析是 CC readPDF/extractPDFPages 的 Java 等价物（poppler-utils → pdfbox 进程内）。
 * 错误语义（empty / too_large / corrupted / password_protected / unknown）与检查顺序
 * 是 CC 契约（pdf.ts:44-113/179-300），任一分支漏掉都会让"HTML 改名 .pdf 进对话历史"、
 * "密码保护 PDF 无提示失败"等真实问题重现。本测试直接锁定 PdfSupport 语义；
 * ReadFileToolTest 另行锁定工具层接线（dispatchPdf）。
 */
@DisplayName("P-CC-01 · PdfSupport 对齐 CC utils/pdf.ts 契约")
@ExtendWith(MockitoExtension.class)
class PdfSupportTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    /** 构造 type 指定的 enabled 模型（同 ModelCapabilityResolverTest:37-45 模式，selectListByQuery 命中）。 */
    private static ModelRecord modelOfType(String type) {
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("some-model");
        m.setType(type);
        m.setEnabled(true);
        return m;
    }

    /** [pdf-vision-align] 阈值跨用例隔离：每用例后重置默认 3MB/100MB（既有测试依赖默认值）。 */
    @AfterEach
    void resetThresholds() {
        PdfSupport.setThresholds(3L * 1024 * 1024, 100L * 1024 * 1024);
    }

    private static Path writeRealPdf(Path dir, String name, int pages) throws Exception {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static Path writeEncryptedPdf(Path dir, String name, int pages) throws Exception {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            // user password 非空 → 空密码 load 必抛 InvalidPasswordException（对齐 pdftoppm 密码错误退出）
            doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            doc.save(file.toFile());
        }
        return file;
    }

    // ═════════════ readPDF · CC pdf.ts:34-113 ═════════════

    @Test
    @DisplayName("readPDF: 真实 PDF → 原样 base64（解码后 %PDF- 开头）+ originalSize —— CC pdf.ts:88")
    void readPdfSuccess(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "doc.pdf", 2);

        var result = PdfSupport.readPDF(file);

        assertThat(result.success()).isTrue();
        byte[] decoded = Base64.getDecoder().decode(result.data().base64());
        assertThat(new String(decoded, 0, 5)).isEqualTo("%PDF-");
        assertThat(result.data().originalSize()).isEqualTo(Files.size(file));
        assertThat(result.data().filePath()).isEqualTo(file.toString());
    }

    @Test
    @DisplayName("readPDF: 0 字节 → EMPTY（CC pdf.ts:50-55）")
    void readPdfEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.pdf");
        Files.write(file, new byte[0]);

        var result = PdfSupport.readPDF(file);

        assertThat(result.success()).isFalse();
        assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.EMPTY);
        assertThat(result.error().message()).contains("empty");
    }

    @Test
    @DisplayName("readPDF: 超 20MB → TOO_LARGE（CC apiLimits.ts:54 + pdf.ts:60-68）")
    void readPdfTooLarge(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.pdf");
        // 稀疏文件：只设长度不写内容（Files.size 检查先于读，CC 同样先 stat）
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(PdfSupport.PDF_TARGET_RAW_SIZE + 1);
        }

        var result = PdfSupport.readPDF(file);

        assertThat(result.success()).isFalse();
        assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.TOO_LARGE);
        assertThat(result.error().message()).contains("maximum allowed size");
    }

    @Test
    @DisplayName("readPDF: 缺 %PDF- magic → CORRUPTED（CC pdf.ts:72-86，HTML 改名 .pdf 拒入对话）")
    void readPdfMissingMagic(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fake.pdf");
        Files.writeString(file, "<html>not a pdf</html>");

        var result = PdfSupport.readPDF(file);

        assertThat(result.success()).isFalse();
        assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.CORRUPTED);
        assertThat(result.error().message()).contains("not a valid PDF");
    }

    @Test
    @DisplayName("readPDF: 文件不存在 → UNKNOWN（CC pdf.ts:104-112 errorMessage 等价）")
    void readPdfMissingFile(@TempDir Path dir) {
        var result = PdfSupport.readPDF(dir.resolve("nope.pdf"));

        assertThat(result.success()).isFalse();
        assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.UNKNOWN);
    }

    // ═════════════ getPDFPageCount · CC pdf.ts:119-135（pdfinfo → pdfbox）═════════════

    @Test
    @DisplayName("getPDFPageCount: 真实 PDF → 页数；损坏/密码 → null（pdfinfo 退出码非 0 → null 等价）")
    void getPageCount(@TempDir Path dir) throws Exception {
        Path doc = writeRealPdf(dir, "doc.pdf", 5);

        assertThat(PdfSupport.getPDFPageCount(doc)).isEqualTo(5);

        Path enc = writeEncryptedPdf(dir, "enc.pdf", 2);
        assertThat(PdfSupport.getPDFPageCount(enc))
            .as("密码保护 PDF 页数无法确定 → null（CC pdfinfo 失败语义）")
            .isNull();

        Path junk = dir.resolve("junk.pdf");
        Files.write(junk, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
        assertThat(PdfSupport.getPDFPageCount(junk)).isNull();
    }

    // ═════════════ extractPDFPages · CC pdf.ts:179-300（pdftoppm → pdfbox 渲染）═════════════

    @Test
    @DisplayName("extractPDFPages: 全量渲染 → page-01.jpg… 命名 + count —— CC pdf.ts:222-230/262-290")
    void extractAllPages(@TempDir Path dir) throws Exception {
        Path doc = writeRealPdf(dir, "doc.pdf", 3);
        Path out = dir.resolve("out");

        var result = PdfSupport.extractPDFPages(doc, out, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.data().count()).isEqualTo(3);
        assertThat(result.data().outputDir()).isEqualTo(out.toString());
        List<String> names;
        try (var stream = Files.list(out)) {
            names = stream.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".jpg"))
                .sorted()
                .collect(Collectors.toList());
        }
        assertThat(names).containsExactly("page-01.jpg", "page-02.jpg", "page-03.jpg");
    }

    @Test
    @DisplayName("extractPDFPages: 范围 1-2 → 2 页；open-ended 2- → 到末页（CC -f/-l 1-based 含）")
    void extractPageRange(@TempDir Path dir) throws Exception {
        Path doc = writeRealPdf(dir, "doc.pdf", 3);
        Path out1 = dir.resolve("out1");

        var ranged = PdfSupport.extractPDFPages(doc, out1, 1, 2);
        assertThat(ranged.success()).isTrue();
        assertThat(ranged.data().count()).isEqualTo(2);
        assertThat(Files.exists(out1.resolve("page-01.jpg"))).isTrue();
        assertThat(Files.exists(out1.resolve("page-03.jpg"))).isFalse();

        Path out2 = dir.resolve("out2");
        var openEnded = PdfSupport.extractPDFPages(doc, out2, 2, Integer.MAX_VALUE);
        assertThat(openEnded.success()).isTrue();
        assertThat(openEnded.data().count())
            .as("open-ended 2- → 页 2..3，共 2 页（CC Infinity 语义）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("extractPDFPages: 空文件 → EMPTY；超 100MB → TOO_LARGE（CC pdf.ts:188-203）")
    void extractEmptyAndTooLarge(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.pdf");
        Files.write(empty, new byte[0]);
        var emptyResult = PdfSupport.extractPDFPages(empty, dir.resolve("o1"), null, null);
        assertThat(emptyResult.success()).isFalse();
        assertThat(emptyResult.error().reason()).isEqualTo(PdfSupport.ErrorReason.EMPTY);

        Path big = dir.resolve("big.pdf");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(PdfSupport.PDF_MAX_EXTRACT_SIZE + 1);
        }
        var bigResult = PdfSupport.extractPDFPages(big, dir.resolve("o2"), null, null);
        assertThat(bigResult.success()).isFalse();
        assertThat(bigResult.error().reason()).isEqualTo(PdfSupport.ErrorReason.TOO_LARGE);
    }

    @Test
    @DisplayName("extractPDFPages: 密码保护 → PASSWORD_PROTECTED（CC pdf.ts:237-245 stderr password 等价）")
    void extractEncrypted(@TempDir Path dir) throws Exception {
        Path enc = writeEncryptedPdf(dir, "enc.pdf", 2);

        var result = PdfSupport.extractPDFPages(enc, dir.resolve("out"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.PASSWORD_PROTECTED);
        assertThat(result.error().message()).contains("password-protected");
    }

    @Test
    @DisplayName("extractPDFPages: 损坏字节 → CORRUPTED；范围倒序 → 0 页 CORRUPTED（CC pdf.ts:247-254/267-275 等价）")
    void extractCorrupted(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.pdf");
        Files.write(junk, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});

        var junkResult = PdfSupport.extractPDFPages(junk, dir.resolve("o1"), null, null);
        assertThat(junkResult.success()).isFalse();
        assertThat(junkResult.error().reason())
            .as("pdftoppm stderr /damaged|corrupt|invalid/ → corrupted 等价")
            .isEqualTo(PdfSupport.ErrorReason.CORRUPTED);

        Path doc = writeRealPdf(dir, "doc.pdf", 2);
        var emptyRange = PdfSupport.extractPDFPages(doc, dir.resolve("o2"), 5, 2);
        assertThat(emptyRange.success()).isFalse();
        assertThat(emptyRange.error().reason())
            .as("0 页产出 → corrupted（pdftoppm 无输出等价）")
            .isEqualTo(PdfSupport.ErrorReason.CORRUPTED);
    }

    // ═════════════ isPDFSupported · CC pdfUtils.ts:59-61 ═════════════

    @Test
    @DisplayName("isPDFSupported: 仅 claude-3-haiku 子串禁用（含 provider 前缀/后缀格式）；null/空白 → 支持")
    void isPdfSupportedModelCheck() {
        assertThat(PdfSupport.isPDFSupported(null)).isTrue();
        assertThat(PdfSupport.isPDFSupported("  ")).isTrue();
        assertThat(PdfSupport.isPDFSupported("claude-sonnet-4-5")).isTrue();
        assertThat(PdfSupport.isPDFSupported("claude-3-5-sonnet-20241022")).isTrue();
        assertThat(PdfSupport.isPDFSupported("claude-3-haiku")).isFalse();
        assertThat(PdfSupport.isPDFSupported("claude-3-haiku-20240307")).isFalse();
        assertThat(PdfSupport.isPDFSupported("bedrock/us.anthropic.claude-3-haiku-20240307-v1:0")).isFalse();
        // 大小写不敏感（CC toLowerCase 语义）
        assertThat(PdfSupport.isPDFSupported("CLAUDE-3-HAIKU")).isFalse();
    }

    // ═════════════ [pdf-vision-align] isPDFSupported 3参 · 委托 ModelCapabilityResolver ═════════════

    @Test
    @DisplayName("isPDFSupported 3参: type=chat → false（deepseek=chat 根因用例，文本模型页图注册路由）")
    void isPdfSupportedThreeParam_chatType_false() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType("chat")));
        assertThat(PdfSupport.isPDFSupported(modelMapper, providerMapper, "some-model"))
            .as("type=chat（deepseek）→ 不支持 PDF document 块（改页图注册 + vision_analyze）")
            .isFalse();
    }

    @Test
    @DisplayName("isPDFSupported 3参: type=multimodal → true（模式复制 ModelCapabilityResolverTest:50-62）")
    void isPdfSupportedThreeParam_multimodalType_true() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType("multimodal")));
        assertThat(PdfSupport.isPDFSupported(modelMapper, providerMapper, "some-model"))
            .as("type=multimodal → 支持 PDF document 块直发")
            .isTrue();
    }

    @Test
    @DisplayName("isPDFSupported 3参: modelMapper=null → 回落 1 参 CC 契约（非 Spring 单测不翻文本分支）")
    void isPdfSupportedThreeParam_nullMapper_fallsBackToOneParamContract() {
        assertThat(PdfSupport.isPDFSupported(null, null, "claude-3-haiku"))
            .as("null mappers → 1 参 CC 名字契约（haiku 子串禁用）")
            .isFalse();
        assertThat(PdfSupport.isPDFSupported(null, null, "claude-sonnet-4-5")).isTrue();
        assertThat(PdfSupport.isPDFSupported(null, null, null)).isTrue();
    }

    // ═════════════ [pdf-vision-align] 阈值可配置 · PdfSupportConfig setThresholds ═════════════

    @Test
    @DisplayName("阈值可配置: setThresholds → getter 生效 + extractPDFPages 上限联动")
    void configurableThresholds(@TempDir Path dir) throws Exception {
        PdfSupport.setThresholds(2L * 1024 * 1024, 5L * 1024 * 1024);
        assertThat(PdfSupport.getExtractSizeThreshold()).isEqualTo(2L * 1024 * 1024);
        assertThat(PdfSupport.getMaxExtractSize()).isEqualTo(5L * 1024 * 1024);

        // 稀疏文件 setLength(getMaxExtractSize()+1) → extractPDFPages TOO_LARGE（联动生效上限）
        Path big = dir.resolve("big.pdf");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(PdfSupport.getMaxExtractSize() + 1);
        }
        var bigResult = PdfSupport.extractPDFPages(big, dir.resolve("o"), null, null);
        assertThat(bigResult.success()).isFalse();
        assertThat(bigResult.error().reason()).isEqualTo(PdfSupport.ErrorReason.TOO_LARGE);
        // @AfterEach 重置默认 3MB/100MB（跨用例隔离）
    }

    @Test
    @DisplayName("阈值校验 fail loud: extract=0 / maxExtract<extract → IllegalArgumentException")
    void configurableThresholds_validateFailLoud() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PdfSupport.setThresholds(0, 100L * 1024 * 1024))
            .as("extractSizeThreshold < 1 → 抛异常（配置错值启动期暴露）")
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PdfSupport.setThresholds(5L * 1024 * 1024, 3L * 1024 * 1024))
            .as("maxExtractSize < extractSizeThreshold → 抛异常")
            .isInstanceOf(IllegalArgumentException.class);
    }
}
