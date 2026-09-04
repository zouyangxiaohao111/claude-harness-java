package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.PdfAttachmentProcessor;
import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [A4] 主 user 消息图片注入 + 多模态路由 行为测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>无图片附件 → 纯文本</b>（现状不变）：图片注入是增强，不能破坏无附件主 user 消息
 *       的既有纯文本契约（回归防线）——否则全量既有消息流被污染。</li>
 *   <li><b>有图片 + 非多模态模型（无 DB mappers → supportsImage=false）→ 多模态提示路由</b>：
 *       主消息描述附件 + contentId，模型可调 {@code vision_analyze} 工具代理视觉模型分析——否则
 *       图片在非多模态模型下静默丢失（方案定稿「不支持 → 多模态工具路由」）。</li>
 *   <li><b>image content block 结构对齐 AnthropicSdkProvider.appendSdkContentBlock（:2189）</b>：
 *       {@code {type:'image', source:{type:'base64', media_type, data}}}——结构错则 SDK
 *       Base64ImageSource 序列化失败（A4 目标 3）。</li>
 * </ol>
 *
 * <p>直接注入（supportsImage=true）分支依赖 DB ModelRecord.type（vision/multimodal），
 * 需 Mockito-inline mock ModelNameResolver 静态方法，超出本测试范围（生产路径经
 * {@code modelSupportsImage} 已覆盖判定逻辑；块结构经 {@code imageContentBlock} 本文件验证）。
 */
class LlmAgentLoopUserMessageImageRoutingTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final ImageAttachmentStore store = new ImageAttachmentStore();

    @Test
    @DisplayName("无 store（单测/非 Spring）→ 纯文本，contentBlocks 空 · 现状不变")
    void noStore_returnsPureText() {
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            null, null, null, null, "hello", null, null, null, false);
        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.content()).isEqualTo("hello");
        assertThat(msg.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("无待注入图片 → 纯文本，contentBlocks 空 · registry 未登记回落")
    void noPendingImages_returnsPureText() {
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, "hello", null, "sess-1", null, false);
        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.content()).isEqualTo("hello");
        assertThat(msg.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("有图片 + 非多模态模型（无 DB mappers）→ 多模态提示路由：描述附件 + contentId + 工具名")
    void nonVisionModel_routesToMultimodalPrompt() {
        store.registerPendingPromptImages("sess-1", List.of(
            new ImageAttachmentStore.PastedImage(1, PNG_BASE64, "image/png"),
            new ImageAttachmentStore.PastedImage(2, PNG_BASE64, "image/jpeg")));

        // 无 DB mappers → modelSupportsImage=false → 多模态工具路由（方案定稿：不支持 → 读缓存注入）
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, "what is in the image?", null, "sess-1", null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("contentId=1")
            .contains("contentId=2")
            .contains("image/png")
            .contains("image/jpeg")
            // 工具名对齐 VisionAnalyze 注册常量 ToolNameConstants.VISION_ANALYZE_TOOL_NAME
            .contains("vision_analyze")
            // [cd71b583] ToolSearch 懒加载引导已从多模态提示移除（vision_analyze schema 已暴露无需再引导）
            .contains("what is in the image?");
    }

    @Test
    @DisplayName("多模态路由后 registry 已消费：再次构造无待注入图片 → 纯文本（一次性消费不残留）")
    void multimodalRoute_drainsOnce() {
        store.registerPendingPromptImages("sess-1", List.of(
            new ImageAttachmentStore.PastedImage(1, PNG_BASE64, "image/png")));
        ChatMessageDto first = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, "q1", null, "sess-1", null, false);
        assertThat(first.content()).contains("contentId=1");

        // 第二次构造：registry 已 drain → 纯文本（对齐 CC prompt submit 一次性构建）
        ChatMessageDto second = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, "q2", null, "sess-1", null, false);
        assertThat(second.content()).isEqualTo("q2");
        assertThat(second.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("imageContentBlock 结构对齐 AnthropicSdkProvider.appendSdkContentBlock(:2189) 期望形状")
    void imageContentBlock_matchesAnthropicShape() {
        JsonNode block = LlmAgentLoop.imageContentBlock("image/png", PNG_BASE64);
        assertThat(block.get("type").asText()).isEqualTo("image");
        JsonNode source = block.get("source");
        assertThat(source.get("type").asText()).isEqualTo("base64");
        assertThat(source.get("media_type").asText()).isEqualTo("image/png");
        assertThat(source.get("data").asText()).isEqualTo(PNG_BASE64);
    }

    @Test
    @DisplayName("imageContentBlock mediaType null → image/png 兜底 · CC attachments.ts:1117 img.mediaType || 'image/png'")
    void imageContentBlock_nullMediaTypeFallsBackToPng() {
        JsonNode block = LlmAgentLoop.imageContentBlock(null, PNG_BASE64);
        assertThat(block.get("source").get("media_type").asText()).isEqualTo("image/png");
    }

    // ── [F1] 生产链路接线：RunRequest.attachments() → registerRunPromptImages → buildUserMessageWithImages ──
    // WHY（CLAUDE.md 规则 9）：A4 注入此前只在测试手动 register 后生效，生产断线（LlmAgentLoop 不读
    // RunRequest.attachments()）。下列测试走真实生产路径 —— RunRequest.attachments() → F1 映射登记 →
    // drain 消费注入，而非测试侧手动 registerPendingPromptImages 重复 A4 逻辑。

    @Test
    @DisplayName("生产链路：RunRequest.attachments → registerRunPromptImages → buildUserMessageWithImages 实际注入（非手动 register）")
    void productionPath_runRequestAttachments_injectsViaRegistration() {
        ImageAttachmentStore store = new ImageAttachmentStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 与 ChatService.processUserMessage 同构的 RunRequest（发送侧 resolveAttachments +
        // MediaLimitGuard 已补全 base64/mediaType）；video 附件不入图片注入（多模态工具路由另走 A3）
        RunRequest request = RunRequest.session(
            "what is in the image?", sessionId, null, null, "gpt-4o", null,
            null, null, null, false, null, null,
            List.of(
                new AttachmentRequest("image", "1", "photo.png", "image/png", PNG_BASE64, null),
                new AttachmentRequest("image", "2", "photo2.jpg", "image/jpeg", PNG_BASE64, null),
                new AttachmentRequest("video", "3", "clip.mp4", "video/mp4", "AA==", null)));

        // doRun 入口调用的生产接线方法（先于首个 user 消息构造）
        int registered = LlmAgentLoop.registerRunPromptImages(
            store, LlmAgentLoop.imageSessionKey(sessionId), request.attachments());
        assertThat(registered).isEqualTo(2);

        // 无 DB mappers → supportsImage=false → 多模态工具路由（与生产 modelName 解析失败保守回落一致）
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, request.userPrompt(), request.modelName(),
            LlmAgentLoop.imageSessionKey(sessionId), null, false);

        assertThat(msg.role()).isEqualTo(Role.user);
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.content())
            .contains("contentId=1")
            .contains("contentId=2")
            .contains("image/png")
            .contains("image/jpeg")
            .contains("vision_analyze")
            .contains("what is in the image?")
            // video 附件不被图片注入（仅 type=image 提取）
            .doesNotContain("contentId=3");

        // 一次性消费：registry 已 drain，二次构造回落纯文本（对齐 CC prompt submit 一次性构建）
        ChatMessageDto second = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, request.userPrompt(), request.modelName(),
            LlmAgentLoop.imageSessionKey(sessionId), null, false);
        assertThat(second.content()).isEqualTo(request.userPrompt());
        assertThat(second.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("生产链路：直传 base64 无 contentId → 分配不撞号自增 id；视频附件不入图片注入")
    void productionPath_directBase64WithoutContentId_assignsSyntheticId() {
        ImageAttachmentStore store = new ImageAttachmentStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 直传 base64 无 contentId（前端直发，imageStore 不经手）+ 已缓存 contentId 引用并存
        RunRequest request = RunRequest.session(
            "describe", sessionId, null, null, "gpt-4o", null,
            null, null, null, false, null, null,
            List.of(
                new AttachmentRequest("image", null, "paste.png", "image/png", PNG_BASE64, null),
                new AttachmentRequest("image", "5", "stored.png", "image/png", PNG_BASE64, null)));

        List<ImageAttachmentStore.PastedImage> images =
            LlmAgentLoop.pastedImagesFromAttachments(request.attachments());
        assertThat(images).hasSize(2);
        // 无 contentId（paste.png）→ 雪花 id（Hutool 全局唯一，非 contentId 小数字，跨 turn 不撞号）；
        // contentId=5 原样保留（upload/粘贴缓存 id）
        assertThat(images.get(0).id()).isNotEqualTo(5L);
        assertThat(images.get(0).base64()).isEqualTo(PNG_BASE64);
        assertThat(images.get(1).id()).isEqualTo(5L);
        assertThat(images.get(1).base64()).isEqualTo(PNG_BASE64);
        // 雪花 id 与 contentId 不撞（两图 id 唯一）
        assertThat(images.get(0).id()).isNotEqualTo(images.get(1).id());

        // 登记 + drain 消费：直传 base64 不触发 getBase64 兜底（base64 已由发送侧携带）
        int registered = LlmAgentLoop.registerRunPromptImages(
            store, LlmAgentLoop.imageSessionKey(sessionId), request.attachments());
        assertThat(registered).isEqualTo(2);
        ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
            store, null, null, null, request.userPrompt(), request.modelName(),
            LlmAgentLoop.imageSessionKey(sessionId), null, false);
        // multimodal prompt：upload contentId=5 图保留 + 直传图（无 contentId）分配雪花 id（≥10 位巨数）
        //   均带独立 contentId 供 vision_analyze；雪花全局唯一不撞（register 内部二次 pastedImagesFromAttachments
        //   雪花 id 与 images.get(0) 不同，故按位数断言不绑定具体值）
        assertThat(msg.content()).contains("contentId=5");
        assertThat(msg.content()).containsPattern("contentId=\\d{10,}");
    }

    @Test
    @DisplayName("生产链路：store 未注入 / 无附件 / 非 image → no-op（非 Spring 单测回落纯文本不回归）")
    void productionPath_noImageAttachments_isNoOp() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // store null（非 Spring 单测 new LlmAgentLoop 无注入）→ 0，不抛错
        assertThat(LlmAgentLoop.registerRunPromptImages(null,
            LlmAgentLoop.imageSessionKey(sessionId), List.of(
                new AttachmentRequest("image", "1", "p.png", "image/png", PNG_BASE64, null))))
            .isZero();
        // 附件 null → 0
        assertThat(LlmAgentLoop.registerRunPromptImages(
            new ImageAttachmentStore(), LlmAgentLoop.imageSessionKey(sessionId), null))
            .isZero();
        // 仅非 image 附件（video/audio/file）→ 0，不入图片注入
        assertThat(LlmAgentLoop.registerRunPromptImages(
            new ImageAttachmentStore(), LlmAgentLoop.imageSessionKey(sessionId),
            List.of(new AttachmentRequest("video", "3", "clip.mp4", "video/mp4", "AA==", null))))
            .isZero();
    }

    @Test
    @DisplayName("[pdf-vision-align] 文本模型 PDF → 主 user 消息含 contentId + vision_analyze 说明，无 media block")
    void textModelPdf_appendsVisionAnalyzeNote() throws Exception {
        PdfAttachmentProcessor processor = new PdfAttachmentProcessor();
        ImageAttachmentStore imageStore = new ImageAttachmentStore();
        String sessionId = "sess-1";
        String sessionKey = "key-" + UUID.randomUUID();
        java.nio.file.Path file = java.nio.file.Files.createTempFile("pdf-vision-align-", ".pdf");
        try {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                doc.save(file.toFile());
            }
            String base64 = java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file));
            int registered = processor.registerPdfAttachments(sessionId, sessionKey, List.of(
                new AttachmentRequest("pdf", null, "doc.pdf", PdfSupport.PDF_MEDIA_TYPE, base64, null)), true, imageStore);
            assertThat(registered).isEqualTo(1);

            // modelName=claude-3-haiku（null mappers → 1 参回落 false → pdfSupported=false → 走 vision 分支）
            ChatMessageDto msg = LlmAgentLoop.buildUserMessageWithImages(
                imageStore, processor, null, null, "请总结这个 PDF", "claude-3-haiku", sessionKey, null, false);

            assertThat(msg.role()).isEqualTo(Role.user);
            assertThat(msg.contentBlocks()).isEmpty();
            assertThat(msg.content())
                .contains("vision_analyze(type=analyze, contentType=pdf, path=")
                .contains("pages=[要分析的页号数组]")
                .contains("当前模型不支持直接查看 PDF")
                .contains("共 2 页")
                .contains("请总结这个 PDF")
                .doesNotContain("contentId=")
                .doesNotContain("图片缓存");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
