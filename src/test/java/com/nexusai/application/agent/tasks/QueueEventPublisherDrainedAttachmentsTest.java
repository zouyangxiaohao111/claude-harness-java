package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.model.session.dto.ChatMessageDto.UserAttachmentInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [busy 气泡附件胶囊] {@code queue.drained} 出站载荷必须携带后端权威附件快照。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：busy（agent 正在流式输出）时发的消息，前端
 * 「入队 → 工具边界 drained 再 append 气泡」，而 drained 载荷原只有 {@code {uuid, content, mode,
 * streamTopic}} ⇒ 气泡上<b>没有附件胶囊</b>，用户必须按 F5（读侧 GET /messages 出站 user_attachments）
 * 才看得到刚发的 Word/Excel/视频。数据一直在队列项里（{@code ChatService.enqueueBusyPrompt} 第 14 参），
 * 只是没出站。本测试锚定「队列项快照 → 出站载荷」这一段。
 *
 * <p><b>⭐ 为什么 url 必须由后端拼、不能交给前端自算</b>：{@code path} 附件（local-read 通道）在
 * <b>原始请求体</b>里只有 path、<b>没有 contentId</b>（contentId 要 {@code resolveAttachments} 注册
 * 附件表之后才有）⇒ 前端按请求体自算必得 {@code contentId=null/url=null}（胶囊点了没反应），
 * 而 F5 后又可点（读侧回填 url）—— 两态不一致。故本测试用 <b>path 附件</b>（contentId 由解析链补出）
 * 作为夹具，并断言出站 url 已拼好、与读侧
 * {@code MessageService.resolveAttachmentUrls} 同规则。
 */
@DisplayName("[busy 气泡附件胶囊] queue.drained 出站载荷携带权威附件快照（path 附件 url 已拼）")
class QueueEventPublisherDrainedAttachmentsTest {

    private static final String SESSION_ID = "sess-drained-att";
    private static final String DOCX_MEDIA =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private SimpMessagingTemplate wsTemplate;
    private QueueEventPublisher publisher;

    @BeforeEach
    void setUp() {
        wsTemplate = mock(SimpMessagingTemplate.class);
        publisher = new QueueEventPublisher(wsTemplate, new NotificationQueue());
    }

    /** 捕获 {@code convertAndSend(topic, payload)} 的载荷（本测试唯一观察点 = 出站 JSON 形状）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> emitAndCapturePayload(String sessionId, List<QueueItem> drained) {
        publisher.emitDrained(sessionId, drained);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate).convertAndSend(eq("/topic/sessions/" + sessionId + "/queue"), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> drainedOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("drained");
    }

    @Test
    @DisplayName("⭐ path 附件（已解析）：出站快照 contentId 非空 + url 已拼（与 GET /messages 读侧同规则）")
    void emitDrained_pathAttachment_carriesContentIdAndUrl() {
        // path 附件在**已解析列表**里的形态：path 已消费、contentId 由附件表注册得出
        List<UserAttachmentInfo> snapshot = List.of(
            new UserAttachmentInfo("file", "季度报表.docx", DOCX_MEDIA, "4711", null));
        QueueItem cmd = new QueueItem("忙时带附件追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-att", false, "busy-queued", false, null, SESSION_ID,
            null, null, null, snapshot);

        Map<String, Object> payload = emitAndCapturePayload(SESSION_ID, List.of(cmd));

        List<Map<String, Object>> drained = drainedOf(payload);
        assertEquals(1, drained.size());
        Object wire = drained.get(0).get("userAttachments");
        assertNotNull(wire, "出站载荷必须携带 userAttachments（前端 live 气泡附件胶囊的唯一来源）");
        List<UserAttachmentInfo> out = (List<UserAttachmentInfo>) wire;
        assertEquals(1, out.size());
        assertEquals("4711", out.get(0).contentId(), "path 附件必须带 contentId（前端据此可点预览）");
        assertEquals("/attachments/content/" + SESSION_ID + "/4711", out.get(0).url(),
            "url 必须由后端拼好（前端按请求体自算会得 null ⇒ 不可点，且与 F5 后不一致）");
        assertEquals("季度报表.docx", out.get(0).filename());
        // 既有四键零变化（前端排队框移除 + 气泡 id 仍靠它）
        assertEquals("msg-queued-att", drained.get(0).get("uuid"));
        assertEquals("忙时带附件追问", drained.get(0).get("content"));
        assertEquals("/topic/sessions/" + SESSION_ID + "/stream", drained.get(0).get("streamTopic"));
    }

    @Test
    @DisplayName("无快照（纯文本 busy / 非 busy workload）⇒ 键缺失且不抛 NPE（Map.of 不收 null 值）")
    void emitDrained_noSnapshot_omitsKeyWithoutNpe() {
        QueueItem plain = new QueueItem("忙时纯文本追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-plain", false, "busy-queued", false, null, SESSION_ID);

        Map<String, Object> payload = emitAndCapturePayload(SESSION_ID, List.of(plain));

        List<Map<String, Object>> drained = drainedOf(payload);
        assertFalse(drained.get(0).containsKey("userAttachments"),
            "无快照 ⇒ 键缺失（前端「缺键 = 无附件」，不伪造空数组）");
        assertEquals("msg-queued-plain", drained.get(0).get("uuid"));
    }

    @Test
    @DisplayName("快照存在但无 contentId（历史行 / 未回补）⇒ url 为 null（不拼坏 url），条目仍出站")
    void emitDrained_snapshotWithoutContentId_keepsItemWithNullUrl() {
        List<UserAttachmentInfo> snapshot = List.of(
            new UserAttachmentInfo("file", "old.docx", DOCX_MEDIA, null, null));
        QueueItem cmd = new QueueItem("追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-old", false, "busy-queued", false, null, SESSION_ID,
            null, null, null, snapshot);

        Map<String, Object> payload = emitAndCapturePayload(SESSION_ID, List.of(cmd));

        @SuppressWarnings("unchecked")
        List<UserAttachmentInfo> out = (List<UserAttachmentInfo>) drainedOf(payload).get(0).get("userAttachments");
        assertEquals(1, out.size(), "无 contentId 的附件仍要出站（chip 降级纯文本，不静默丢附件）");
        assertNull(out.get(0).url());
        assertNull(out.get(0).contentId());
    }

    @Test
    @DisplayName("多会话隔离：只出站本会话被 drain 的项（快照随项走，不串味）")
    void emitDrained_snapshotFollowsItsOwnItem() {
        List<UserAttachmentInfo> snapA = List.of(new UserAttachmentInfo("file", "a.docx", DOCX_MEDIA, "1", null));
        List<UserAttachmentInfo> snapB = List.of(new UserAttachmentInfo("file", "b.docx", DOCX_MEDIA, "2", null));
        QueueItem a = new QueueItem("A", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "uuid-a", false, "busy-queued", false, null, SESSION_ID, null, null, null, snapA);
        QueueItem b = new QueueItem("B", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "uuid-b", false, "busy-queued", false, null, SESSION_ID, null, null, null, snapB);

        Map<String, Object> payload = emitAndCapturePayload(SESSION_ID, List.of(a, b));

        @SuppressWarnings("unchecked")
        List<UserAttachmentInfo> outA = (List<UserAttachmentInfo>) drainedOf(payload).get(0).get("userAttachments");
        @SuppressWarnings("unchecked")
        List<UserAttachmentInfo> outB = (List<UserAttachmentInfo>) drainedOf(payload).get(1).get("userAttachments");
        assertTrue(outA.get(0).filename().equals("a.docx") && outA.get(0).contentId().equals("1"));
        assertTrue(outB.get(0).filename().equals("b.docx") && outB.get(0).contentId().equals("2"));
    }

    @Test
    @DisplayName("wsTemplate 缺失 ⇒ 静默跳过（fail-soft 不变，不因新增字段改变）")
    void emitDrained_withoutWsTemplate_skipsSilently() {
        new QueueEventPublisher(null, new NotificationQueue())
            .emitDrained(SESSION_ID, List.of(new QueueItem("x", NotificationQueue.MODE_PROMPT, Priority.NEXT,
                null, "u", false, "busy-queued", false, null, SESSION_ID)));
        verify(wsTemplate, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }
}
