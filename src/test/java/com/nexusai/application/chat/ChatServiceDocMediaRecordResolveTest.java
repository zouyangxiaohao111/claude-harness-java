package com.nexusai.application.chat;

import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.entity.AttachmentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [attach-doc-resolve] 文档类 MIME 走 media contentId 通道必须能解析 · 撞号门只拒 {@code image/*} 与
 * {@code application/pdf} 测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）</b>：批 ATT-UPLOAD-DOC（master
 * {@code 914d243e}）按用户裁定把 upload 白名单放开到文档类（{@code doc/docx/xls/xlsx}）——前端
 * {@code planAttachmentChannel} 对 {@code type='file'} <b>只留 upload 一条腿</b>，传的是真实
 * {@code File.type}（OOXML MIME / {@code application/msword} / {@code application/vnd.ms-excel}），
 * 拿回附件表 contentId 后随消息发 {@code type=file + contentId}（无 base64、无 path）。
 *
 * <p>该形态在 {@code resolveAttachments} 落进「媒体 contentId 分支」，而门 {@code isMediaRecordType}
 * 当时是<b>正向白名单</b>（只认 {@code video/*} · {@code audio/*} · {@code application/octet-stream}）
 * ⇒ 文档 MIME 被判「非媒体」⇒ 按撞号当未命中 ⇒ 回退 {@code MediaAttachmentStore.get(sessionId, id)}
 * —— 但这里传进去的是<b>附件表 id</b>，而 store 的 id 是 {@code nextId(sessionId)} <b>每会话从 1
 * 重算的独立 id 空间</b> ⇒ 一旦同会话有 path 注册或其它 upload 产生过更小的 id，该 get 就<b>结构性
 * 未命中</b>；更坏的一面是偶然命中同号的旧媒体 ⇒ <b>把别的文件送给模型</b>。
 *
 * <p>⇒ 用户红线「<b>绝不允许显示 chip 但模型收不到</b>」被破：前端芯片在、模型收不到（或收到错文件）。
 * 本类锁定修好后的两条硬验收：
 * <ol>
 *   <li><b>验收 1</b>：{@code docx/xlsx/application.msword/application.vnd.ms-excel} 四种合法文档 MIME
 *       经 upload 注册后，{@code resolveAttachments} <b>必须解析出来</b>（resolved 非空 + contentId）；
 *       {@link #busyPath_carriesDocAttachment()} 额外锁死 <b>busy 入队路径</b>（
 *       {@code enqueueBusyPrompt → busyQueuedResolvedAttachments → resolveAttachments} 是<b>同源</b>
 *       解析器 ⇒ 两侧必须同时验，只验一侧会漏掉另一条消费链）。</li>
 *   <li><b>验收 2</b>：<b>撞号防御不得失效</b> —— 附件表里一条 {@code image/*} / 一条
 *       {@code application/pdf} 记录被 media-cache 旧 id 命中时，<b>仍须按未命中处理</b>（回退 store，
 *       store 是旧 id 的真源）。这是该门存在的<b>唯一理由</b>；只测「放开」会让这道防御被顺手删掉时
 *       全仓仍然全绿。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类刻意不写的一条（写下来即「假绿」）</b>：无法既满足验收 1 又拒绝「附件表 id 撞上一条
 * docx 行」——两者在请求里是<b>同一个比特形态</b>（{@code type=file + contentId=N}，无 base64/path），
 * 不存在能区分它们的实现。同理，{@code ChatServicePathAttachmentAnyExtensionTest} 已覆盖的 path 通道
 * 与三道门（存在性/200MB/防穿越）本类<b>不重复造</b>。
 *
 * <p><b>断言口径</b>：{@code resolveAttachments} 为 private → 反射注入依赖 + 反射调用（同
 * {@link ChatServicePathAttachmentAnyExtensionTest} 的 setField 范式）；busy 侧走公开
 * {@code enqueueBusyPrompt} + {@link NotificationQueue} mock 捕获队列项。
 */
@DisplayName("[attach-doc-resolve] 文档类 MIME 走 media contentId 通道可解析 · 撞号门只拒 image/* 与 pdf")
class ChatServiceDocMediaRecordResolveTest {

    private static final String SESSION = "sess-doc-resolve";

    /** 真 OOXML（前端 docx 上传时 File.type 的实测值）。 */
    private static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /** 真 OOXML（xlsx）。 */
    private static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** 老 Office 二进制格式（doc）。 */
    private static final String DOC_MIME = "application/msword";
    /** 老 Office 二进制格式（xls）。 */
    private static final String XLS_MIME = "application/vnd.ms-excel";

    private ChatService service;
    private AttachmentService attachmentService;
    private MediaAttachmentStore mediaStore;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        attachmentService = mock(AttachmentService.class);
        mediaStore = mock(MediaAttachmentStore.class);
        ReflectionTestUtils.setField(service, "attachmentService", attachmentService);
        ReflectionTestUtils.setField(service, "mediaAttachmentStore", mediaStore);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 附件表记录（upload 注册形态：mediaType = 真实 File.type，path = store 落盘路径）。 */
    private static AttachmentRecord tableRec(long id, String mediaType, String path) {
        AttachmentRecord rec = new AttachmentRecord();
        rec.setId(id);
        rec.setSessionId(SESSION);
        rec.setPath(path);
        rec.setMediaType(mediaType);
        rec.setFilename("rec-" + id);
        rec.setSourceType("upload");
        return rec;
    }

    /** media-cache 旧 id 空间的记录（与附件表 id 独立，每会话从 1 重算）。 */
    private static MediaAttachmentStore.StoredMedia legacyMedia(long id, String mediaType) {
        return new MediaAttachmentStore.StoredMedia(id,
            "D:/config/media-cache/" + SESSION + "/" + id + ".bin", "legacy-" + id + ".bin", 9L, mediaType);
    }

    @SuppressWarnings("unchecked")
    private List<AttachmentRequest> resolve(List<AttachmentRequest> raw) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("resolveAttachments", String.class, List.class);
        m.setAccessible(true);
        return (List<AttachmentRequest>) m.invoke(service, SESSION, raw);
    }

    /** 前端 upload 成功后随消息发的形态：type=file + contentId=附件表 id，无 base64、无 path。 */
    private static AttachmentRequest uploadedDoc(String contentId, String filename, String mediaType) {
        return new AttachmentRequest("file", contentId, filename, mediaType, null, null);
    }

    // ─────────────────── 验收 1：文档类 MIME 必须能解析 ───────────────────

    @ParameterizedTest(name = "验收1 · {0} 经 upload 注册 → resolved 非空 + contentId（改前红）")
    @DisplayName("验收 1 · 空闲路径：四种合法文档 MIME 经 upload 注册后 resolveAttachments 必须解析出来")
    @ValueSource(strings = {DOCX_MIME, XLSX_MIME, DOC_MIME, XLS_MIME})
    void documentMimeThroughUpload_isResolved(String docMime) throws Exception {
        when(attachmentService.getContent(21L)).thenReturn(tableRec(21L, docMime, "D:/config/media-cache/" + SESSION + "/21.docx"));

        List<AttachmentRequest> resolved = resolve(
            List.of(uploadedDoc("21", "季度报告.docx", docMime)));

        assertThat(resolved)
            .as("文档 MIME [%s] 不是「明显非媒体」——撞号门只该拒 image/* 与 application/pdf。"
                + "此处若为空 ⇒ 上传成功的 docx 被当撞号处理 → 回退 get(sessionId, 附件表id) 未命中 "
                + "→ warn + continue ⇒ 静默丢弃（chip 在、模型收不到）", docMime)
            .hasSize(1);
        AttachmentRequest out = resolved.get(0);
        assertThat(out.contentId())
            .as("必须带 contentId（下游按 contentId 读附件表盘上真源）").isEqualTo("21");
        assertThat(out.base64()).as("media 路径通道 base64 恒 null（不内存直发）").isNull();
        assertThat(out.mediaType())
            .as("mediaType 沿用请求体声明的文档 MIME（下游按实际 mediaType 分流）").isEqualTo(docMime);
        assertThat(out.type()).as("type 沿用前端 type=file（Composer 契约）").isEqualTo("file");
        verify(mediaStore, never()).get(anyString(), anyLong());
    }

    @Test
    @DisplayName("验收 1 · 独立 id 空间错位（更坏的一面）：附件表 id 同号于 media-cache 旧媒体时，必须取附件表 docx，不得误送旧媒体")
    void docRowWinsOverSameNumberLegacyMedia() throws Exception {
        when(attachmentService.getContent(5L))
            .thenReturn(tableRec(5L, DOCX_MIME, "D:/config/media-cache/" + SESSION + "/5.docx"));
        // media-cache 的 5 号是另一条历史视频（存储 id 与附件表 id 各自从 1 自增 ⇒ 同号无关联）
        when(mediaStore.get(SESSION, 5L)).thenReturn(legacyMedia(5L, "video/mp4"));

        List<AttachmentRequest> resolved = resolve(List.of(uploadedDoc("5", "季度报告.docx", DOCX_MIME)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).mediaType())
            .as("必须解析为附件表的 docx 行（upload 真源）；若为 video/mp4 ⇒ 把同号的历史视频送给了模型（错文件）")
            .isEqualTo(DOCX_MIME);
        verify(mediaStore, never())
            .get(SESSION, 5L);
    }

    // ─────────────────── 验收 2：撞号防御仍生效 ───────────────────

    @Test
    @DisplayName("验收 2 · 撞号防御：附件表 id 命中 image/* 行 ⇒ 仍按未命中，回退 media-cache store 的旧媒体")
    void imageRowIsStillTreatedAsMiss() throws Exception {
        when(attachmentService.getContent(1L)).thenReturn(tableRec(1L, "image/png", "D:/config/img/1.png"));
        when(mediaStore.get(SESSION, 1L)).thenReturn(legacyMedia(1L, "video/mp4"));

        List<AttachmentRequest> resolved = resolve(List.of(uploadedDoc("1", "clip.mp4", "video/mp4")));

        assertThat(resolved)
            .as("附件表命中一条 image/* 行 = 撞号（旧 media-cache id 撞到别的类型行）⇒ 必须按未命中回退 store")
            .hasSize(1);
        assertThat(resolved.get(0).mediaType())
            .as("命中的必须是 store 里的旧媒体 video/mp4，绝不能是附件表的 image/png 行")
            .isEqualTo("video/mp4");
        verify(mediaStore).get(SESSION, 1L);
    }

    @Test
    @DisplayName("验收 2 · 撞号防御：附件表 id 命中 application/pdf 行 ⇒ 仍按未命中（回退 store）")
    void pdfRowIsStillTreatedAsMiss() throws Exception {
        when(attachmentService.getContent(2L))
            .thenReturn(tableRec(2L, "application/pdf", "D:/config/pdf-cache/2.pdf"));
        when(mediaStore.get(SESSION, 2L)).thenReturn(legacyMedia(2L, "audio/mpeg"));

        List<AttachmentRequest> resolved = resolve(List.of(uploadedDoc("2", "voice.mp3", "audio/mpeg")));

        assertThat(resolved)
            .as("附件表命中一条 application/pdf 行 = 撞号 ⇒ 必须按未命中回退 store")
            .hasSize(1);
        assertThat(resolved.get(0).mediaType())
            .as("命中的必须是 store 里的旧媒体 audio/mpeg，绝不能是附件表的 application/pdf 行")
            .isEqualTo("audio/mpeg");
        verify(mediaStore).get(SESSION, 2L);
    }

    @Test
    @DisplayName("验收 2 · 撞号防御（store 也无）：image/* 与 pdf 行均按未命中 ⇒ 跳过（不消费错行、不静默吞）")
    void mismatchedRowsAreSkippedWhenStoreAlsoMisses() throws Exception {
        when(attachmentService.getContent(3L)).thenReturn(tableRec(3L, "image/jpeg", "D:/config/img/3.jpg"));
        when(attachmentService.getContent(4L))
            .thenReturn(tableRec(4L, "application/pdf", "D:/config/pdf-cache/4.pdf"));
        // mediaStore.get 默认返回 null（store 也未命中）

        List<AttachmentRequest> resolved = resolve(List.of(
            uploadedDoc("3", "a.mp4", "video/mp4"),
            uploadedDoc("4", "b.mp4", "video/mp4")));

        assertThat(resolved)
            .as("两行都是撞号 ⇒ 附件表按未命中 + store 也未命中 ⇒ 两条都不进 resolved（fail loud 已 warn）")
            .isEmpty();
        verify(mediaStore).get(SESSION, 3L);
        verify(mediaStore).get(SESSION, 4L);
    }

    // ─────────── 回归钉：既有可接受集合不得被本次收窄 ───────────

    @ParameterizedTest(name = "回归钉 · mediaType={0} 仍接受")
    @DisplayName("回归钉：video/* · audio/* · application/octet-stream · null（未标注）仍接受，未被本次改动收窄")
    @NullSource
    @ValueSource(strings = {"video/mp4", "audio/mpeg", "application/octet-stream", "VIDEO/MP4"})
    void existingAcceptedMediaTypesUnchanged(String mediaType) throws Exception {
        when(attachmentService.getContent(9L)).thenReturn(tableRec(9L, mediaType, "D:/config/media-cache/9.bin"));

        List<AttachmentRequest> resolved = resolve(List.of(uploadedDoc("9", "clip.mp4", "video/mp4")));

        assertThat(resolved)
            .as("mediaType=[%s] 本就该接受（正向白名单的既有行为）—— 改门的形状不得把这一侧收窄", mediaType)
            .hasSize(1);
        assertThat(resolved.get(0).contentId()).isEqualTo("9");
    }

    // ─────────────── 验收 1 · busy 侧（同源解析器的另一条消费链） ───────────────

    @Test
    @DisplayName("验收 1 · busy 路径：会话 busy 时发带 docx 的消息 → 入队携带非空 resolved 附件（同源 resolveAttachments）")
    void busyPath_carriesDocAttachment() throws Exception {
        NotificationQueue queue = mock(NotificationQueue.class);
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
        when(attachmentService.getContent(33L))
            .thenReturn(tableRec(33L, DOCX_MIME, "D:/config/media-cache/" + SESSION + "/33.docx"));

        SendMessageRequest req = new SendMessageRequest("忙时发 Word", null, null,
            List.of(uploadedDoc("33", "季度报告.docx", DOCX_MIME)),
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-busy-doc", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        List<AttachmentRequest> carried = captor.getValue().attachments();
        assertThat(carried)
            .as("busy 入队与空闲路径同源走 resolveAttachments ⇒ docx 也必须被解析；"
                + "此处为空 = busy 时发的 Word 静默丢弃（drain 拿不到任何东西注入模型）")
            .hasSize(1);
        assertThat(carried.get(0).contentId()).isEqualTo("33");
        assertThat(carried.get(0).filename()).isEqualTo("季度报告.docx");
        assertThat(captor.getValue().userAttachments())
            .as("非图片附件随队列带 user_attachments 快照（F5 重拉气泡胶囊 + 预览 url 靠 contentId）")
            .hasSize(1);
        assertThat(captor.getValue().userAttachments().get(0).contentId()).isEqualTo("33");
    }

    @Test
    @DisplayName("验收 2 · busy 路径：撞号（image/* 行）仍按未命中 ⇒ 入队不带错行（busy 侧与空闲侧同一门）")
    void busyPath_mismatchedRowStillMiss() throws Exception {
        NotificationQueue queue = mock(NotificationQueue.class);
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
        when(attachmentService.getContent(1L)).thenReturn(tableRec(1L, "image/png", "D:/config/img/1.png"));
        // store 未命中 ⇒ 该附件应被拦下（不静默消费附件表的 image 行）

        SendMessageRequest req = new SendMessageRequest("忙时发视频", null, null,
            List.of(uploadedDoc("1", "clip.mp4", "video/mp4")),
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-busy-miss", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attachments())
            .as("撞号 + store 未命中 ⇒ 不入队（busy 侧不得把附件表的 image 行当视频送给模型）")
            .isEmpty();
        assertThat(captor.getValue().userAttachments())
            .as("无附件入队 ⇒ 无附件快照").isNull();
    }
}
