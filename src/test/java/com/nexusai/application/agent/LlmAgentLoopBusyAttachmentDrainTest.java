package com.nexusai.application.agent;

import com.nexusai.application.agent.attachment.PdfAttachmentProcessor;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [attach-busy-resolve] busy 队列项携「非图片附件」时的 drain 消费测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>入队侧的修复不落 drain 就完全无效</b>：{@code LlmAgentLoop.drainAndInjectQueued} 的
 *       分流判据原为 {@code hasBase64ImageAttachments(item.attachments())}（只认 ≤5MB base64 图）——
 *       附件进了队列也过不了这道门，落「纯文本 busy」分支（该分支对 {@code item.attachments()}
 *       <b>零引用</b>）⇒ 模型永远看不到 Word/PDF/大图。本测试锁死「非图片附件也能产出模型可见内容」。</li>
 *   <li><b>放开闸门 ≠ 丢掉图片路径</b>：原「有图」判据必须原样保留（图片仍走 registerRunPromptImages
 *       → image block / imagePasteIds），且不能与「有任意附件」判据合并兼任（否则图片项会走错分支）。</li>
 * </ol>
 *
 * <p>本例用 path 通道 Word 附件（busy 入队侧 {@code resolveAttachments} 已注册附件表得 contentId，
 * 但附件表未注入 → 走 {@code att.path()} 直读，与生产同构）。
 */
@DisplayName("[attach-busy-resolve] busy 队列项携非图片附件 → drain 产出模型可见内容")
class LlmAgentLoopBusyAttachmentDrainTest {

    private static LlmProvider stopProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("response");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("response", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    @Test
    @DisplayName("busy 排队项携 path 形态 Word 附件 → drain 后模型侧 user 消息产出「本地路径」说明；DB 侧仍是原文")
    void busyQueuedPathAttachment_drainProducesModelVisiblePathNote() throws IOException {
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        Path docx = Files.createTempFile("nexusai-drain-", ".docx");
        Files.writeString(docx, "word-bytes");
        String absPath = docx.toAbsolutePath().normalize().toString();

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AttachmentRequest word = new AttachmentRequest("file", "42", "季度报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, absPath);
        queue.enqueue(busyQueued("忙时发的 Word", "msg-b-word", sid, word));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        ChatMessageDto m = findById(state, "msg-b-word");
        assertThat(m).as("busy 排队项必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m.queuedOrigin()).isEqualTo("busy-queued");
        assertThat(m.content())
            .as("模型侧必须看到 Word 附件的本地路径说明（否则附件对模型等于不存在）")
            .contains("季度报告.docx")
            .contains("本地路径=" + absPath);
        // DB 侧原文不污染：injected registry 的 content 仍是用户原文（与 busy 图消息两形态一致）
        assertThat(state.injectedQueuedMessages())
            .extracting(AgentState.InjectedQueuedMessage::content)
            .containsExactly("忙时发的 Word");
    }

    @Test
    @DisplayName("回归守卫：纯文本 busy 项（无附件）仍走原文分支，content 不含任何附件说明")
    void busyQueuedPlainText_unchanged() {
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        queue.enqueue(busyQueued("纯文本排队", "msg-b-txt", sid, null));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        ChatMessageDto m = findById(state, "msg-b-txt");
        assertThat(m).isNotNull();
        assertThat(m.content())
            .as("无附件 busy 项行为零变化（原文直发，不拼任何附件说明）")
            .isEqualTo("纯文本排队");
    }

    @Test
    @DisplayName("端到端：ChatService.enqueueBusyPrompt（busy 入队已解析 path Word）→ LlmAgentLoop drain → 模型侧可见路径说明")
    void endToEnd_busyWordAttachmentFromEnqueueToDrain() throws IOException {
        // GIVEN: 真实 NotificationQueue（入队端与 drain 端同一条队列）+ ChatService busy 入队
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        NotificationQueue queue = new NotificationQueue();
        Path docx = Files.createTempFile("nexusai-e2e-", ".docx");
        Files.writeString(docx, "word-bytes");
        String absPath = docx.toAbsolutePath().normalize().toString();

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenReturn(77L);

        ChatService chatService = new ChatService();
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "messageMapper",
            mock(com.nexusai.repository.session.mapper.MessageMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "toolCallMapper",
            mock(com.nexusai.repository.session.mapper.ToolCallMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "messageService",
            mock(com.nexusai.domain.session.MessageService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "notificationQueue", queue);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "queueEventPublisher",
            mock(com.nexusai.application.agent.tasks.QueueEventPublisher.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "attachmentService", attachmentService);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "localRead", true);

        AttachmentRequest word = new AttachmentRequest("file", null, "季度报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, absPath);
        chatService.enqueueBusyPrompt(sid, "msg-e2e-word",
            new SendMessageRequest("忙时发的 Word", null, null, List.of(word), null, null, null, null, null));
        assertThat(queue.hasCommandsInQueue()).as("端到端前提：busy 入队确实入队（未被静默丢弃）").isTrue();

        // WHEN: 当前轮首个工具边界 drain（同一队列，同一 LlmAgentLoop 生产路径）
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // THEN: 模型侧 user 消息含路径说明（附件端到端不丢）
        ChatMessageDto m = findById(state, "msg-e2e-word");
        assertThat(m).isNotNull();
        assertThat(m.content())
            .as("端到端：busy 路径下 Word 附件最终对模型可见（路径说明）")
            .contains("季度报告.docx")
            .contains("本地路径=" + absPath);
        assertThat(m.content()).as("contentId 也应可见（附件表注册产物）").contains("contentId=77");
    }

    @Test
    @DisplayName("[PDF 腿] busy 项携 ≤20 页 path PDF + 多模态模型 → drain 注册 PDF 后 document block 进模型侧 contentBlocks")
    void busyQueuedPdfAttachment_multimodalModel_drainInjectsPdfDocumentBlock() throws IOException {
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        // 多模态模型（models.type=multimodal）→ supportsImage=true ⇒ PDF 走 document block 直注
        //   （registerRunPromptPdfs 的 textModel=false 分支 + buildUserMessageWithImages 的 pdfSupported 分支）。
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelOfType("multimodal")));
        org.springframework.test.util.ReflectionTestUtils.setField(ctxFactory, "modelMapper", modelMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(ctxFactory, "providerMapper",
            mock(ProviderMapper.class));

        // spy 真处理器：既走真实解析/登记（pendingPdfs 落真状态），又能 verify drain 确实调用了它
        PdfAttachmentProcessor pdfProcessor = spy(new PdfAttachmentProcessor());
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        loop.setPdfAttachmentProcessor(pdfProcessor);
        loop.setContextFactory(ctxFactory);

        Path pdf = Files.createTempFile("nexusai-drain-pdf-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        String absPath = pdf.toAbsolutePath().normalize().toString();

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AttachmentRequest pdfAttachment = new AttachmentRequest("pdf", null, "季度报告.pdf",
            "application/pdf", null, absPath);
        queue.enqueue(busyQueued("忙时发的 PDF", "msg-b-pdf", sid, pdfAttachment));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        ChatMessageDto m = findById(state, "msg-b-pdf");
        assertThat(m).as("busy 排队项必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m.content()).as("原文不被附件污染").contains("忙时发的 PDF");
        // ① 登记腿真被调用（不是被静默拆掉）
        verify(pdfProcessor).registerPdfAttachments(anyString(), any(), anyList(), anyBoolean(), any());
        // ② 产物进模型侧 contentBlocks：PDF document block（只有 PDF 登记成功才可能出现在这里 ——
        //    本项无图片、无其它附件，contentBlocks 非空 ⟺ PDF 腿生效）
        assertThat(m.contentBlocks())
            .as("PDF 注册腿的产物必须进模型侧 contentBlocks（模型侧出现 PDF block）")
            .isNotEmpty();
        assertThat(m.contentBlocks().toString())
            .as("多模态模型分支 → document block（media_type=application/pdf）")
            .contains("document")
            .contains("application/pdf");
    }

    @Test
    @DisplayName("[PDF 腿] busy 项携 >20 页 path PDF（回落夹具：无 mapper → pdfSupported=true）→ drain 注册后引导文本进 content")
    void busyQueuedLargePdfAttachment_drainInjectsPdfGuidanceTextIntoContent() throws IOException {
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);

        PdfAttachmentProcessor pdfProcessor = new PdfAttachmentProcessor();
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        loop.setPdfAttachmentProcessor(pdfProcessor);
        loop.setContextFactory(ctxFactory);

        // >20 页（PDF_MAX_PAGES_PER_READ）→ NEEDS_SUBAGENT → 引导文本（本夹具无 mapper，
        //   buildUserMessageWithImages 的 pdfSupported 回落 1 参 CC 契约 = true → 「Read pages 分段」引导）
        Path pdf = Files.createTempFile("nexusai-drain-bigpdf-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 21; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdf.toFile());
        }
        String absPath = pdf.toAbsolutePath().normalize().toString();

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AttachmentRequest pdfAttachment = new AttachmentRequest("pdf", null, "长报告.pdf",
            "application/pdf", null, absPath);
        queue.enqueue(busyQueued("忙时发的大 PDF", "msg-b-bigpdf", sid, pdfAttachment));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        ChatMessageDto m = findById(state, "msg-b-bigpdf");
        assertThat(m).as("busy 排队项必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m.content())
            .as("PDF 注册腿的产物必须进模型侧 content（模型侧出现 PDF 引导文本）")
            .contains("长报告.pdf")
            .contains("超过单次读取上限")
            .contains(absPath)
            .contains("忙时发的大 PDF");
    }

    /** type 指定的 enabled 模型（裸名 → ModelNameResolver 兼容路径 selectListByQuery 命中）。 */
    private static ModelRecord modelOfType(String type) {
        ModelRecord record = new ModelRecord();
        record.setId("m1");
        record.setProviderId("p1");
        record.setName("test-model");
        record.setType(type);
        record.setEnabled(true);
        return record;
    }

    /** 便捷构造 busy-queued QueueItem（13 参 canonical 携附件）。 */
    private static QueueItem busyQueued(String value, String uuid, String sessionId, AttachmentRequest attachment) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null,
            uuid, false, "busy-queued", false, null, sessionId, null, null,
            attachment == null ? List.of() : List.of(attachment));
    }

    private static ChatMessageDto findById(AgentState state, String id) {
        if (state.rawMessages() == null) {
            return null;
        }
        for (ChatMessageDto m : state.rawMessages()) {
            if (m != null && id.equals(m.id())) {
                return m;
            }
        }
        return null;
    }
}
