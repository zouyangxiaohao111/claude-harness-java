package com.nexusai.application.chat;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.entity.AttachmentRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.domain.session.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [attach-busy-resolve] busy 入队「不裁剪 + 校验门同源」测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）</b>：
 * <ol>
 *   <li><b>入队不再按类型裁剪</b>：原 {@code busyQueuedImageAttachments} 只携
 *       {@code type=image + base64 非空 + ≤5MB} 三项 —— 非图片附件（Word/Excel/PDF/大图）在
 *       busy（agent 正在流式输出）时被<b>静默丢弃</b>（连日志都没有），用户发出去的文件永远
 *       不到模型。本测试锁死「busy 入队携带任意已校验附件」。</li>
 *   <li><b>放开裁剪 ≠ 放开校验（本批最易做错处）</b>：入队携带的必须是<b>已解析</b>形态 ——
 *       跑与空闲路径同源的 {@code resolveAttachments}（{@code Files.exists} / 非目录 /
 *       ≤200MB / 防穿越 / 附件表注册得 contentId）。若有人图省事直接透传 {@code req.attachments()}，
 *       不存在的 path 与目录会被带进队列 → 本测试变红（安全回归守卫）。</li>
 *   <li><b>扩展名不再是一道门</b>（用户 2026-09-18 裁定「所有文件都允许走 path 通道」）：原
 *       {@code PATH_ATTACHMENT_ALLOWED_EXTENSIONS} 15 项白名单已由批 ATT-WHITELIST 整条删除，故本类
 *       <b>刻意不再</b>断言 {@code .exe} 被拒（那是过时契约 —— 留着会与用户裁定对撞）。改为
 *       {@link #enqueueBusyPrompt_anyExtensionPassesTheGate()} <b>显式正向</b>断言
 *       {@code .exe} / {@code .zip} / {@code .txt} 均能入队。WHY：若只把过时的那条 path 一删了事，
 *       将来有人把白名单加回来时<b>没有任何测试会红</b> —— 用户亲自裁定过的行为会沦为无守护自由区。</li>
 *   <li><b>{@code ..}（防穿越）不在本类覆盖</b>：{@code normalizeAndValidatePath} 先做
 *       {@code toAbsolutePath().normalize()}，Windows 下 {@code ..} 被<b>词法消解</b> ⇒ 「逐段禁 {@code ..}」
 *       循环结构性不可达，写「含 {@code ..} 的路径被拒」是<b>假绿</b>（测不到那条分支）。该门由同批
 *       {@code ChatServicePathAttachmentAnyExtensionTest.gate3_dotDotNeutralizedByNormalize} 以
 *       「{@code ..} 被消解、解析结果不逃逸」的正确口径覆盖，本类<b>不重复造</b>以免冗余假绿。</li>
 *   <li><b>数量门同源</b>：空闲路径有 {@code MAX_ATTACHMENTS_PER_REQUEST}(50)，busy 路径原本
 *       <b>没有</b> → 单条队列项可携带任意多附件。本测试锁死 50 上限。</li>
 * </ol>
 *
 * <p>测试手法：{@code localRead=true}（模拟 Tauri 前后端同机，path 通道激活）+ mock
 * {@link AttachmentService}（断言 register 调用面 = 已过校验的证据）。
 */
@DisplayName("[attach-busy-resolve] busy 入队携附件：放开裁剪 + 补回校验门 + 补数量门")
class ChatServiceBusyAttachResolveTest {

    private static final String SESSION = "sess-busy-attach";
    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final String DOCX_MEDIA_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private ChatService service;
    private NotificationQueue queue;
    private AttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        queue = mock(NotificationQueue.class);
        attachmentService = mock(AttachmentService.class);
        ReflectionTestUtils.setField(service, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
        ReflectionTestUtils.setField(service, "messageService", mock(MessageService.class));
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
        ReflectionTestUtils.setField(service, "attachmentService", attachmentService);
        // path 通道激活（生产 = nexusai.attachments.local-read=true，Tauri 前后端同机）
        ReflectionTestUtils.setField(service, "localRead", true);
    }

    @Test
    @DisplayName("busy 入队携带 path 形态非图片附件（Word）：过白名单/存在性 → 注册附件表得 contentId 随队列入队（现实现会丢）")
    void enqueueBusyPrompt_carriesResolvedPathAttachment() throws IOException {
        Path docx = Files.createTempFile("nexusai-busy-", ".docx");
        Files.writeString(docx, "word-bytes");
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenReturn(42L);

        AttachmentRequest word = new AttachmentRequest("file", null, "报告.docx", DOCX_MEDIA_TYPE, null,
            docx.toString());
        SendMessageRequest req = new SendMessageRequest("忙时发 Word", null, null, List.of(word),
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-q-word", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        List<AttachmentRequest> carried = captor.getValue().attachments();
        assertThat(carried)
            .as("busy 入队必须携带已解析的 path 非图片附件（原实现按 type≠image 静默丢弃）")
            .hasSize(1);
        assertThat(carried.get(0).filename()).isEqualTo("报告.docx");
        assertThat(carried.get(0).type()).isEqualTo("file");
        assertThat(carried.get(0).contentId())
            .as("path 附件必须经附件表注册拿到 contentId（下游 drain 按 contentId/path 消费）")
            .isEqualTo("42");
        assertThat(carried.get(0).path()).isEqualTo(docx.toAbsolutePath().normalize().toString());
        // 校验门证据：register 被调用一次（= path 过了 Files.exists + 非目录 + ≤200MB + 防穿越）
        verify(attachmentService, times(1))
            .register(eq(SESSION), eq(docx.toAbsolutePath().normalize().toString()),
                eq(DOCX_MEDIA_TYPE), eq("报告.docx"), anyLong(), eq("path"));
    }

    @Test
    @DisplayName("校验门生效：不存在(ghost.docx) / 目录(dir.docx) 的 path 绝不入队，且绝不注册附件表")
    void enqueueBusyPrompt_validationGateRejectsBadPaths() throws IOException {
        Path docx = Files.createTempFile("nexusai-busy-", ".docx");
        Files.writeString(docx, "ok");
        Path ghost = Path.of(System.getProperty("java.io.tmpdir"),
            "nexusai-ghost-" + UUID.randomUUID() + ".docx");
        assertThat(Files.exists(ghost)).as("夹具前提：幽灵文件必须不存在").isFalse();
        // 目录门夹具：故意让目录名以 .docx 结尾 —— 证明「被拦是因为它是目录」而不是「因为扩展名」
        Path dir = Files.createDirectory(Path.of(System.getProperty("java.io.tmpdir"),
            "nexusai-busy-dir-" + UUID.randomUUID() + ".docx"));
        assertThat(Files.isDirectory(dir)).as("夹具前提：该 path 必须真为目录").isTrue();
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenReturn(7L);

        List<AttachmentRequest> atts = List.of(
            new AttachmentRequest("file", null, "报告.docx", DOCX_MEDIA_TYPE, null, docx.toString()),
            new AttachmentRequest("file", null, "ghost.docx", DOCX_MEDIA_TYPE, null, ghost.toString()),
            new AttachmentRequest("file", null, "dir.docx", DOCX_MEDIA_TYPE, null, dir.toString()));
        SendMessageRequest req = new SendMessageRequest("三个 path", null, null, atts,
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-q-3", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attachments())
            .as("只有过校验的 .docx 入队；ghost.docx（不存在）与 dir.docx（目录）必须被拦下")
            .hasSize(1);
        assertThat(captor.getValue().attachments().get(0).filename()).isEqualTo("报告.docx");
        assertThat(captor.getValue().attachments())
            .as("被拦附件的 filename 不得出现在队列里")
            .extracting(AttachmentRequest::filename)
            .doesNotContain("ghost.docx", "dir.docx");
        // 红线守卫：存在性 / 非目录门必须<b>先于</b>附件表注册（未过校验的文件绝不产生附件表行）
        verify(attachmentService, times(1))
            .register(eq(SESSION), anyString(), any(), any(), anyLong(), eq("path"));
        verify(attachmentService, never()).register(eq(SESSION),
            eq(ghost.toAbsolutePath().normalize().toString()), any(), any(), anyLong(), anyString());
        verify(attachmentService, never()).register(eq(SESSION),
            eq(dir.toAbsolutePath().normalize().toString()), any(), any(), anyLong(), anyString());
    }

    /**
     * [全扩展名放行 · 用户裁定 2026-09-18] <b>扩展名不是门</b>：{@code .exe} / {@code .zip} / {@code .txt}
     * 等任意扩展名现在都能走 path 通道入队。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：本类的负例原先靠「{@code .exe} 不在
     * {@code PATH_ATTACHMENT_ALLOWED_EXTENSIONS} 白名单内 → 被拒」成立。批 ATT-WHITELIST 按用户原话
     * 「所有文件都允许走 path 通道」删掉整条白名单后，那条断言只剩「把 path 删掉」一种改法 —— 而一旦
     * 只删不补，<b>「扩展名放行」就失去了任何守护</b>：将来有人把白名单加回来，全仓测试仍然全绿，
     * 用户亲自裁定过的行为被静默回退。故此处必须<b>显式正向</b>断言放行（而非留白）。
     *
     * <p>与 {@link ChatServicePathAttachmentAnyExtensionTest}（直接锁 {@code resolveAttachments} 语义）
     * 的关系：那条测的是「函数本身放行」；本条测的是「busy 入队这条<b>独立消费路径</b>也放行」——
     * busy 路径若被改成自己再判一次扩展名（而绕过同源 {@code resolveAttachments}），只有本条会红。
     */
    @Test
    @DisplayName("扩展名不是门：.exe / .zip / .txt 任意扩展名均经 path 通道入队（用户裁定「所有文件都允许走 path」）")
    void enqueueBusyPrompt_anyExtensionPassesTheGate() throws IOException {
        Path exe = Files.createTempFile("nexusai-busy-any-", ".exe");
        Files.writeString(exe, "MZ");
        Path zip = Files.createTempFile("nexusai-busy-any-", ".zip");
        Files.writeString(zip, "PK");
        Path txt = Files.createTempFile("nexusai-busy-any-", ".txt");
        Files.writeString(txt, "plain");
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenReturn(42L);

        List<AttachmentRequest> atts = List.of(
            new AttachmentRequest("file", null, "evil.exe", "application/octet-stream", null, exe.toString()),
            new AttachmentRequest("file", null, "打包产物.zip", "application/octet-stream", null, zip.toString()),
            new AttachmentRequest("file", null, "笔记.txt", "text/plain", null, txt.toString()));
        SendMessageRequest req = new SendMessageRequest("全扩展名", null, null, atts,
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-q-any-ext", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        List<AttachmentRequest> carried = captor.getValue().attachments();
        assertThat(carried)
            .as("扩展名白名单已删除（用户 2026-09-18 裁定）：.exe/.zip/.txt 必须全部入队，"
                + "若有人把白名单加回来，本条转红")
            .hasSize(3);
        assertThat(carried)
            .as("被放行的是这三个附件本身（不是随机放行）")
            .extracting(AttachmentRequest::filename)
            .containsExactlyInAnyOrder("evil.exe", "打包产物.zip", "笔记.txt");
        assertThat(carried)
            .as("放行 ≠ 未校验：三个都经附件表注册拿到 contentId（source='path'）")
            .extracting(AttachmentRequest::contentId)
            .containsOnly("42");
        verify(attachmentService, times(3))
            .register(eq(SESSION), anyString(), any(), any(), anyLong(), eq("path"));
    }

    @Test
    @DisplayName("数量门补齐：附件 > 50 → 拒绝（与空闲路径同源 ValidationException，不静默截断）")
    void enqueueBusyPrompt_rejectsOverMaxAttachments() {
        List<AttachmentRequest> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(new AttachmentRequest("image", String.valueOf(i), "p" + i + ".png", "image/png",
                PNG_BASE64, null));
        }
        SendMessageRequest req = new SendMessageRequest("超量附件", null, null, many,
            null, null, null, null, null);

        assertThatThrownBy(() -> service.enqueueBusyPrompt(SESSION, "msg-q-many", req))
            .as("busy 路径原本无任何数量门（单条队列项可携带任意多附件）→ 必须补 50 上限")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("50");
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("入队不裁剪：非图片 base64（video/file）+ contentId 路径通道（pdf）同样随队列携带（原实现按类型丢弃）")
    void enqueueBusyPrompt_carriesNonImageChannels() {
        AttachmentRecord pdfRec = new AttachmentRecord();
        pdfRec.setMediaType("application/pdf");
        pdfRec.setPath("D:/docs/paper.pdf");
        when(attachmentService.getContent(3L)).thenReturn(pdfRec);

        List<AttachmentRequest> atts = List.of(
            new AttachmentRequest("image", "1", "photo.png", "image/png", PNG_BASE64, null),
            new AttachmentRequest("video", "2", "clip.mp4", "video/mp4", "AA==", null),
            new AttachmentRequest("file", null, "note.docx", DOCX_MEDIA_TYPE, "QUJD", null),
            new AttachmentRequest("pdf", "3", "paper.pdf", "application/pdf", null, null));
        SendMessageRequest req = new SendMessageRequest("混合附件", null, null, atts,
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-q-mixed", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attachments())
            .as("入队不裁剪（对齐 CC）：image / video / file / pdf 四类都应随队列携带")
            .extracting(AttachmentRequest::type)
            .containsExactly("image", "video", "file", "pdf");
    }
}
