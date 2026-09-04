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
        // [pdf-subagent] needsSubagent 结果须保留 resolvePdfBlocks 已解析的磁盘路径（供 LlmAgentLoop 派子代理读原 PDF）
        assertThat(result.pdfPath())
            .as("NEEDS_SUBAGENT 结果必须携带 pdfPath（供主模型自主引导 Read pages / 派子代理引用）")
            .isNotNull()
            .isNotBlank()
            .endsWith(".pdf");
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
    @DisplayName("生产链路: >20 页 PDF 附件 → NEEDS_SUBAGENT → 主 user 消息注入自主引导文本"
        + "（多模态主模型 Read pages 分段，系统不自动 fork），无 media block")
    void productionPath_moreThan20Pages_injectsReadPagesGuidance(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "big.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)));
        assertThat(registered).isEqualTo(1);

        // modelName=null + 无 DB mappers → PdfSupport.isPDFSupported(null)=true → 多模态主模型分支：
        //   U2 自主引导注入 "自行 Read 工具 + pages 分段读取" 文本（系统不自动 fork 子代理）。
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, processor, null, null, "请总结这个 PDF", null, sessionKey, null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        // 无 media block 注入（NEEDS_SUBAGENT）→ 文本引导 + 原始 prompt
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("big.pdf")
            .contains("25")
            .contains("请用 Read 工具 + pages 参数分段读取")
            .contains("超过单次读取上限 20 页")
            .contains("请总结这个 PDF")
            .doesNotContain("已派子代理");   // 系统不自动 fork
    }

    @Test
    @DisplayName("生产链路: >20 页 PDF + 文本主模型（claude-3-haiku）→ 引导调 Agent 派多模态子代理"
        + "（model 提示多模态档位名；系统不自动 fork）")
    void productionPath_moreThan20Pages_textModel_injectsAgentGuidance(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "big.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)));
        assertThat(registered).isEqualTo(1);

        // 9 参重载：modelName=claude-3-haiku（null mappers → 1 参回落含 haiku → pdfSupported=false 文本分支），
        //   multimodalModelName=null → 引导回落默认模型语义（注明 settings.multimodalModelName 未配置）。
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, processor, null, null, "请总结这个 PDF", "claude-3-haiku", sessionKey, null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("big.pdf")
            .contains("25")
            .contains("当前模型不支持直接查看 PDF")
            .contains("Agent 工具")
            .contains("多模态子代理")
            .contains("Read 工具 + pages 参数分段读取")
            .contains("vision_analyze 一次只支持单页 contentId")
            .doesNotContain("已派子代理");
    }

    @Test
    @DisplayName("生产链路: >20 页 PDF + 文本主模型 + 已配置多模态档位名 → 引导注入 model=多模态名")
    void productionPath_moreThan20Pages_textModel_multimodalNameInjected(@TempDir Path dir) throws Exception {
        Path file = writeRealPdf(dir, "big.pdf", 25);
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        String sessionKey = UUID.randomUUID().toString();

        int registered = processor.registerPdfAttachments("sess-1", sessionKey, List.of(
            new AttachmentRequest("pdf", null, "big.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)));
        assertThat(registered).isEqualTo(1);

        // 10 参重载：multimodalModelName="claude-sonnet-4-6"（动态 resolveMultimodalModelName 注入）
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, processor, null, null, "请总结这个 PDF", "claude-3-haiku", sessionKey, null, false,
            "claude-sonnet-4-6");

        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("model=claude-sonnet-4-6")
            .contains("当前模型不支持直接查看 PDF")
            .contains("vision_analyze 一次只支持单页 contentId");
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

    // ═════════════ [vision-cc-align 2026-09-03] 文本模型路径 · 懒渲染引导（不再逐页注册页图）═════════════
    // WHY（CLAUDE.md 规则 9）：deepseek=chat 文本模型发不出 PDF document block（API 400 根因），必须引导
    //   vision_analyze(contentType=pdf, path, pages)。v2 起**不再预注册逐页页图**（省一次全 PDF 渲染）：
    //   resolvePdfBlocks 只透出 pdfPath + pageCount，渲染延迟到 vision_analyze 调用时按 pages 懒做。

    @Test
    @DisplayName("文本模型路径: textModel 小 PDF（2 页）→ 懒渲染引导（pdfPath 透出、无页图注册、不发 document block）")
    void textModelPdf_lazyRendersGuidesToVisionAnalyze(@TempDir Path dir) throws Exception {
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
            .as("v2 不再逐页预注册页图（懒渲染）→ visionContentIds 空")
            .isEmpty();
        assertThat(pdf.pdfPath())
            .as("pdfPath 透出（PDF 附件落盘绝对路径），供 LlmAgentLoop 拼 vision_analyze(contentType=pdf, path=…) 引导")
            .isNotNull()
            .isNotBlank();
        assertThat(Files.exists(Path.of(pdf.pdfPath())))
            .as("pdfPath 指向真实存在的 PDF（vision_analyze resolvePath 直读）")
            .isTrue();
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
        // [pdf-subagent] needsSubagent PendingPdf 须携 pdfPath（resolvePdfBlocks 产物，LlmAgentLoop 派子代理读原 PDF）
        assertThat(pending.get(0).pdfPath())
            .as(">20 页 needsSubagent PendingPdf 必须保留磁盘 pdfPath（自主引导注入用）")
            .isNotNull()
            .isNotBlank()
            .endsWith(".pdf");
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
