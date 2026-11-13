package com.nexusai.domain.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 多模式兼容] RAG 线历史附件清单（`<attachments>`）· 只挂 {@code MessageService.listForModel}。
 *
 * <p><b>为什么需要</b>：{@code KbQaPipeline.loadHistory} 对 user 行显式 {@code if (m.isMeta()) continue}，
 * ReAct 线写入的合成消息（{@code file_read_synthetic}，isMeta=true）在 RAG 历史里不可见；同一 KB 会话
 * 可能 RAG / ReAct 两模式并存，RAG 轮仍要知道「上一轮带过哪些附件」⇒ 清单必须挂在真实 user 消息 content。
 *
 * <p><b>真实记录</b>：附件快照直接取自本机 DB 的真实行
 * {@code msg-b5ae9a91}（docx，contentId=4）、{@code msg-fa052e55}（pdf，contentId=1）、
 * {@code msg-2d1fc1ab}（image，无 contentId）。
 */
class MessageServiceAttachmentHistoryPromptTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 真实行 msg-b5ae9a91 的 user_attachments。 */
    private static final String LIVE_DOCX_ATTACHMENTS =
        "[{\"type\":\"file\",\"filename\":\"【260719】浙江财经大学数智金融产教融合实训基地和学生宿舍项目建议书.docx\","
            + "\"mediaType\":\"application/octet-stream\",\"contentId\":\"4\",\"url\":null}]";

    /** 真实行 msg-fa052e55 的 user_attachments。 */
    private static final String LIVE_PDF_ATTACHMENTS =
        "[{\"type\":\"pdf\",\"filename\":\"美利奴羊毛证书.pdf\","
            + "\"mediaType\":\"application/pdf\",\"contentId\":\"1\",\"url\":null}]";

    /** 真实行 msg-2d1fc1ab 的 user_attachments（纯图片）。 */
    private static final String LIVE_IMAGE_ATTACHMENTS =
        "[{\"type\":\"image\",\"filename\":\"image.png\",\"mediaType\":\"image/png\","
            + "\"contentId\":null,\"url\":null}]";

    @Test
    @DisplayName("真实 docx 快照 → user content 末尾追加 <attachments> name/type/document_id")
    void docxAttachment_appended() throws Exception {
        ChatMessageDto in = userMessage("这个项目的建议书讲什么", LIVE_DOCX_ATTACHMENTS);
        List<ChatMessageDto> out = append(List.of(in));

        assertThat(out).hasSize(1);
        String content = out.get(0).content();
        assertThat(content).startsWith("这个项目的建议书讲什么\n\n<attachments>");
        assertThat(content).contains("name=\"【260719】浙江财经大学数智金融产教融合实训基地和学生宿舍项目建议书.docx\"");
        assertThat(content).contains("type=\"file\"");
        assertThat(content).contains("document_id=\"4\"");
        assertThat(content).endsWith("</attachments>");
        assertThat(out.get(0).id()).isEqualTo(in.id());
        assertThat(out.get(0).role()).isEqualTo(Role.user);
        assertThat(out.get(0).isMeta()).isFalse();
    }

    @Test
    @DisplayName("真实 pdf 快照 → type=pdf + document_id 原样")
    void pdfAttachment_appended() throws Exception {
        List<ChatMessageDto> out = append(List.of(userMessage("看看这份证书", LIVE_PDF_ATTACHMENTS)));

        String content = out.get(0).content();
        assertThat(content).contains("name=\"美利奴羊毛证书.pdf\"");
        assertThat(content).contains("type=\"pdf\"");
        assertThat(content).contains("document_id=\"1\"");
    }

    @Test
    @DisplayName("纯图片快照 → 不生成清单（RAG 线没有图片块，image.png 只是噪声）")
    void imageOnly_notAppended() throws Exception {
        ChatMessageDto in = userMessage("这张图是什么", LIVE_IMAGE_ATTACHMENTS);
        List<ChatMessageDto> out = append(List.of(in));

        assertThat(out.get(0)).isSameAs(in);
        assertThat(out.get(0).content()).doesNotContain("<attachments>");
    }

    @Test
    @DisplayName("isMeta 合成消息（file_read_synthetic）→ 不重复追加清单")
    void metaSynthetic_notAppended() throws Exception {
        ChatMessageDto in = userMessage("Result of calling the Read tool:\n正文", LIVE_DOCX_ATTACHMENTS)
            .withIsMeta(true);
        List<ChatMessageDto> out = append(List.of(in));

        assertThat(out.get(0)).isSameAs(in);
        assertThat(out.get(0).content()).doesNotContain("<attachments>");
    }

    @Test
    @DisplayName("assistant 消息 → 不追加清单")
    void assistant_notAppended() throws Exception {
        ChatMessageDto in = message(Role.assistant, "回答", LIVE_DOCX_ATTACHMENTS);
        List<ChatMessageDto> out = append(List.of(in));

        assertThat(out.get(0)).isSameAs(in);
    }

    @Test
    @DisplayName("文件名含 XML 特殊字符 → 属性转义（不让 & / < / > / \" 破坏标签）")
    void filenameEscaped() throws Exception {
        String attachments = "[{\"type\":\"file\",\"filename\":\"a&b<c>d\\\"e.md\","
            + "\"mediaType\":\"text/markdown\",\"contentId\":null,\"url\":null}]";
        List<ChatMessageDto> out = append(List.of(userMessage("看这个", attachments)));

        String content = out.get(0).content();
        assertThat(content).contains("name=\"a&amp;b&lt;c&gt;d&quot;e.md\"");
    }

    // ─────────── helpers ───────────

    private static ChatMessageDto userMessage(String content, String attachmentsJson) throws Exception {
        return message(Role.user, content, attachmentsJson);
    }

    private static ChatMessageDto message(Role role, String content, String attachmentsJson) throws Exception {
        List<ChatMessageDto.UserAttachmentInfo> attachments = JSON.readValue(
            attachmentsJson, new TypeReference<List<ChatMessageDto.UserAttachmentInfo>>() { });
        ChatMessageDto base = new ChatMessageDto(
            "msg-test", "sess-test", role, null, content,
            null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of(), null);
        return base.withUserAttachments(attachments);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessageDto> append(List<ChatMessageDto> in) throws Exception {
        Method m = MessageService.class.getDeclaredMethod("appendHistoryAttachments", List.class);
        m.setAccessible(true);
        return (List<ChatMessageDto>) m.invoke(null, in);
    }
}
