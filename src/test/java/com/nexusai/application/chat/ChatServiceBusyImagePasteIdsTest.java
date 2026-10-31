package com.nexusai.application.chat;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [busy 气泡图片] 入队期 imagePasteIds 定稿 + 与 drain 落库同源。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：busy 排队消息的气泡由前端在 {@code queue.drained}
 * 时点构造，靠 imagePasteIds 去 image-cache 拉图。直传 base64 图在请求体里没有 contentId，id 原本在
 * drain 时由 {@code pastedImagesFromAttachments} 现分配（雪花）。若入队另分配一份，两次雪花不同 ⇒
 * 前端拿到不存在的 id（图仍不显示）。本测试锚定：<b>入队写进 QueueItem 的 id == drain 侧解析出的 id</b>
 * —— 同一份 id 源（附件 contentId），落库列与出站字段因此逐字相同。
 */
@DisplayName("[busy 气泡图片] 入队定稿 imagePasteIds（与 drain/落库同源）")
class ChatServiceBusyImagePasteIdsTest {

    private static final String SESSION = "sess-busy-img-ids";
    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private ChatService service;
    private NotificationQueue queue;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        queue = mock(NotificationQueue.class);
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
    }

    private NotificationQueue.QueueItem enqueueAndCapture(SendMessageRequest req) {
        service.enqueueBusyPrompt(SESSION, "msg-q-img-1", req);
        ArgumentCaptor<NotificationQueue.QueueItem> captor =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("⭐ base64 直传图（无 contentId）：入队定稿雪花 id 并写回 contentId；drain 的 pastedImagesFromAttachments 解析出同一 id")
    void enqueueBusyPrompt_assignsImageId_once_drainReusesIt() {
        // 缺陷现场：前端粘贴图 = base64 直传 + 无 contentId（请求体形态）
        List<AttachmentRequest> attachments = List.of(
            new AttachmentRequest("image", null, "photo.png", "image/png", PNG_BASE64, null));
        SendMessageRequest req = new SendMessageRequest("忙时带图消息", null, null, attachments,
            null, null, null, null, null);

        NotificationQueue.QueueItem item = enqueueAndCapture(req);

        // 入队：imagePasteIds 已定稿，且与附件 contentId 一致（唯一 id 源）
        assertThat(item.imagePasteIds()).as("入队必须定稿 imagePasteIds").hasSize(1);
        String id = item.imagePasteIds().get(0);
        assertThat(item.attachments().get(0).contentId())
            .as("id 必须写回附件 contentId（drain 复用的锚点）")
            .isEqualTo(id);
        assertThat(item.attachments().get(0).base64()).as("base64 原样保留").isEqualTo(PNG_BASE64);

        // drain 侧：pastedImagesFromAttachments 从同一附件列表解析出同一 id
        List<com.nexusai.application.agent.attachment.ImageAttachmentStore.PastedImage> drainImages =
            LlmAgentLoop.pastedImagesFromAttachments(item.attachments());
        assertThat(drainImages).hasSize(1);
        assertThat(String.valueOf(drainImages.get(0).id()))
            .as("drain 落库的 image_paste_ids 必须与本字段逐字相同（否则前端拉到不存在的 id）")
            .isEqualTo(id);
    }

    @Test
    @DisplayName("数字 contentId（粘贴缓存 / 附件表 id）原样保留：不重分配 id")
    void enqueueBusyPrompt_keepsNumericContentId() {
        List<AttachmentRequest> attachments = List.of(
            new AttachmentRequest("image", "1", "photo.png", "image/png", PNG_BASE64, null));
        SendMessageRequest req = new SendMessageRequest("忙时带图消息", null, null, attachments,
            null, null, null, null, null);

        NotificationQueue.QueueItem item = enqueueAndCapture(req);

        assertThat(item.attachments().get(0).contentId()).isEqualTo("1");
        assertThat(item.imagePasteIds()).containsExactly("1");
    }

    @Test
    @DisplayName("无图片（纯文本 / 仅非图片附件）⇒ imagePasteIds 为 null（缺键 = 无图片，不伪造空数组）")
    void enqueueBusyPrompt_noImage_nullIds() {
        SendMessageRequest plain = new SendMessageRequest("纯文本忙时消息", null, null, List.of(),
            null, null, null, null, null);

        NotificationQueue.QueueItem item = enqueueAndCapture(plain);

        assertThat(item.imagePasteIds()).isNull();
    }
}
