package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R1] PdfAttachmentProcessor 单元契约 · PDF 分页决策 + document/image block 注入（≤20 页直接注入）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：附件 PDF 在 LlmAgentLoop 主 user 消息注入前
 * 必须先做<b>分页决策</b>（≤{@link PdfSupport#PDF_MAX_PAGES_PER_READ} 直接注入 / &gt;20 页
 * 标记 NEEDS_SUBAGENT 交 R2），再按<b>三态</b>解析（≤3MB → document block / &gt;3MB → 页图
 * image block）。任一决策漏掉都会让"超大 PDF 整份 base64 进 API 击穿 32MB 请求上限"或
 * ">20 页 PDF 强注入导致 API 400"等真实问题重现。
 */
@DisplayName("[R1] PdfAttachmentProcessor · PDF 分页决策 + block 注入契约")
class PdfAttachmentProcessorTest {

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

    // ═════════════ resolvePdfBlocks · 分页决策 + 三态 ═════════════

    @Test
    @DisplayName("resolvePdfBlocks: base64 通道 ≤3MB 小 PDF（2 页）→ INJECT 单 document block")
    void base64SmallPdf_resolvesToDocumentBlock(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "doc.pdf", 2);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();

        PdfAttachmentProcessor.PdfBlocksResult result =
            processor.resolvePdfBlocks("sess-1", null, base64);

        assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.INJECT);
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0)).isInstanceOf(ContentBlockParam.DocumentBlockParam.class);
        ContentBlockParam.DocumentBlockParam doc = (ContentBlockParam.DocumentBlockParam) result.blocks().get(0);
        assertThat(doc.source().mediaType()).isEqualTo(PdfSupport.PDF_MEDIA_TYPE);
        assertThat(doc.source().data()).isEqualTo(base64);
    }

    @Test
    @DisplayName("resolvePdfBlocks: 分页决策 25 页 > 20 → NEEDS_SUBAGENT（R2 标记，不注入 blocks）")
    void moreThan20Pages_returnsNeedsSubagent(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();

        PdfAttachmentProcessor.PdfBlocksResult result =
            processor.resolvePdfBlocks("sess-1", null, base64);

        assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.NEEDS_SUBAGENT);
        assertThat(result.pageCount()).isEqualTo(25);
        assertThat(result.blocks()).isEmpty();
    }

    @Test
    @DisplayName("resolvePdfBlocks: 无 contentId 且无 base64 → ERROR（fail loud）")
    void noSource_returnsError() {
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();

        PdfAttachmentProcessor.PdfBlocksResult result =
            processor.resolvePdfBlocks("sess-1", null, null);

        assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.ERROR);
        assertThat(result.error()).isNotNull();
        assertThat(result.blocks()).isEmpty();
    }

    @Test
    @DisplayName("resolvePdfBlocks: 路径通道 contentId → PdfAttachmentStore 磁盘路径解析 → document block"
        + "（>5MB 上传落盘，base64=null，CC 路径通道语义）")
    void pathChannel_contentId_resolvesFromDisk(@TempDir Path configHome) throws Exception {
        byte[] bytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        PdfAttachmentStore store = new PdfAttachmentStore();
        // G5 适配：PdfAttachmentStore 写 nexusai 自有根 → 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        try {
            PdfAttachmentStore.StoredPdf stored =
                store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, "report.pdf");
            assertThat(stored).isNotNull();
            PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
            processor.setPdfAttachmentStore(store);

            PdfAttachmentProcessor.PdfBlocksResult result =
                processor.resolvePdfBlocks("sess-1", String.valueOf(stored.id()), null);

            assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.INJECT);
            assertThat(result.blocks()).hasSize(1);
            assertThat(result.blocks().get(0)).isInstanceOf(ContentBlockParam.DocumentBlockParam.class);
            ContentBlockParam.DocumentBlockParam doc =
                (ContentBlockParam.DocumentBlockParam) result.blocks().get(0);
            assertThat(doc.source().data()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        } finally {
            ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    // ═════════════ 生产链路接线：registerPdfAttachments → buildUserMessageWithImages 注入 ═════════════

    @Test
    @DisplayName("生产链路: base64 小 PDF 附件 → registerPdfAttachments → A4 主 user 消息 contentBlocks=[text, document block]")
    void productionPath_registersAndInjectsDocumentBlock(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "doc.pdf", 2);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "doc.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)));
        assertThat(registered).isEqualTo(1);

        // 主 user 消息构造（A4 注入 · 对齐 CC attachments.ts:1062-1071 + FileReadTool.ts:1001-1015）
        // modelName=null → PdfSupport.isPDFSupported(null)=true → document block 直接注入
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, processor, null, null, "请查看这个 PDF", null, sessionKey, null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.contentBlocks()).hasSize(2);  // [text, document]
        com.fasterxml.jackson.databind.JsonNode textBlock =
            (com.fasterxml.jackson.databind.JsonNode) msg.contentBlocks().get(0);
        com.fasterxml.jackson.databind.JsonNode docBlock =
            (com.fasterxml.jackson.databind.JsonNode) msg.contentBlocks().get(1);
        assertThat(textBlock.get("type").asText()).isEqualTo("text");
        assertThat(docBlock.get("type").asText()).isEqualTo("document");
        assertThat(docBlock.get("source").get("media_type").asText())
            .isEqualTo(PdfSupport.PDF_MEDIA_TYPE);
        assertThat(docBlock.get("source").get("data").asText()).isEqualTo(base64);
    }

    @Test
    @DisplayName("生产链路: >20 页 PDF 附件 → NEEDS_SUBAGENT → 主 user 消息注入文本说明，无 media block")
    void productionPath_moreThan20Pages_injectsTextNote(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "big.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)));
        assertThat(registered).isEqualTo(1);

        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, processor, null, null, "请总结这个 PDF", null, sessionKey, null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        // 无 media block 注入（NEEDS_SUBAGENT，R2 subagent 解析）→ 文本说明 + 原始 prompt
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("big.pdf")
            .contains("25")
            .contains(String.valueOf(PdfSupport.PDF_MAX_PAGES_PER_READ))
            .contains("子代理")
            .contains("请总结这个 PDF");
    }

    @Test
    @DisplayName("生产链路: registerPdfAttachments 无 PDF 附件 → 0（no-op）")
    void noPdfAttachments_isNoOp() {
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        assertThat(processor.registerPdfAttachments("sess-1", "key", List.of(
            new AttachmentRequest("image", "1", "p.png", "image/png", "AA==", null))))
            .isZero();
        assertThat(processor.registerPdfAttachments("sess-1", "key", null)).isZero();
    }

    // ═════════════ [pdf-vision-align] 文本模型路径 · 页图注册 visionContentIds ═════════════
    // WHY（CLAUDE.md 规则 9）：deepseek=chat 文本模型发不出 PDF document block（API 400 根因），
    //   必须改为逐页 JPEG 注册到 ImageAttachmentStore（contentId 列表），否则文本模型下 PDF 附件静默丢失。

    @Test
    @DisplayName("文本模型路径: textModel 小 PDF（2 页）→ 页图注册 visionContentIds==2、不发 document block")
    void textModelPdf_registersPagesAsImages(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "doc.pdf", 2);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        ImageAttachmentStore imageStore = new ImageAttachmentStore();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "doc.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)), true, imageStore);
        assertThat(registered).isEqualTo(1);

        List<PdfAttachmentProcessor.PendingPdf> pending = processor.drainPendingPdfs(sessionKey);
        assertThat(pending).hasSize(1);
        PdfAttachmentProcessor.PendingPdf pdf = pending.get(0);
        assertThat(pdf.needsSubagent()).isFalse();
        assertThat(pdf.blocks())
            .as("文本模型不发 document block（deepseek 400 根因防线）")
            .isEmpty();
        assertThat(pdf.visionContentIds())
            .as("2 页 → 2 个页图注册 contentId")
            .hasSize(2);
        for (long id : pdf.visionContentIds()) {
            ImageAttachmentStore.Base64Content content = imageStore.getBase64OrDisk("sess-1", id);
            assertThat(content)
                .as("页图须可经 ImageAttachmentStore 读回（vision_analyze 消费路径）")
                .isNotNull();
            byte[] img = java.util.Base64.getDecoder().decode(content.base64());
            assertThat((img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8)
                .as("页图必须是可解码 JPEG（FFD8 magic）")
                .isTrue();
        }
    }

    @Test
    @DisplayName("文本模型路径: 25 页 >20 仍 NEEDS_SUBAGENT（分页决策优先，不页图注册）")
    void textModel_moreThan20Pages_stillNeedsSubagent(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "big.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)), true,
            new ImageAttachmentStore());
        assertThat(registered).isEqualTo(1);

        List<PdfAttachmentProcessor.PendingPdf> pending = processor.drainPendingPdfs(sessionKey);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).needsSubagent()).isTrue();
        assertThat(pending.get(0).visionContentIds()).isEmpty();
    }

    @Test
    @DisplayName("阈值消费: setThresholds(1,1) → 任意真实 PDF textModel → TOO_LARGE ERROR（getMaxExtractSize 生效）")
    void textModel_thresholdConfigured_tooLarge(@TempDir Path dir) throws Exception {
        // 阈值压到 1B：任意真实 PDF（>1B）必过 maxExtract → 证明 textModel 分支消费 getMaxExtractSize()
        //（默认 100MB 时该 PDF 是正常页图注册，不会 TOO_LARGE）。实测 pdfbox 2 页空 PDF ≈ 数百字节 < 1KB，
        //   故 1024B 阈值不触发；1B 阈值对任意真实 PDF 确定性触发。
        PdfSupport.setThresholds(1, 1);
        try {
            Path file = writeRealPdf(dir, "doc.pdf", 2);
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            PdfAttachmentProcessor processor = new PdfAttachmentProcessor();

            PdfAttachmentProcessor.PdfBlocksResult result =
                processor.resolvePdfBlocks("sess-1", null, base64, true, new ImageAttachmentStore());

            assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.ERROR);
            assertThat(result.error().reason()).isEqualTo(PdfSupport.ErrorReason.TOO_LARGE);
            assertThat(result.visionContentIds()).isEmpty();
        } finally {
            PdfSupport.setThresholds(3L * 1024 * 1024, 100L * 1024 * 1024);
        }
    }

    @Test
    @DisplayName("多模态默认（textModel=false）→ visionContentIds 空、≤3MB 仍 DocumentBlockParam（回归防线）")
    void multimodalDefault_visionContentIdsEmpty_documentBlock(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "doc.pdf", 2);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();

        PdfAttachmentProcessor.PdfBlocksResult result =
            processor.resolvePdfBlocks("sess-1", null, base64, false, new ImageAttachmentStore());

        assertThat(result.resolution()).isEqualTo(PdfAttachmentProcessor.Resolution.INJECT);
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0)).isInstanceOf(ContentBlockParam.DocumentBlockParam.class);
        assertThat(result.visionContentIds()).isEmpty();
    }
}
