package com.nexusai.apis.session;

import com.nexusai.application.chat.ChatService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * [纯附件消息] content 组合校验（对齐 DSH：允许「只上传文件、不写文字」）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：{@code SendMessageRequest.content} 原先标 {@code @NotBlank}，
 * 前端纯附件时发空串 ⇒ 请求层 400，用户感知「不许只发文件」。放开后必须同时锁死两件相反的事：
 * <ol>
 *   <li>文本空白 <b>且</b> 无附件 ⇒ 仍 400（不能变成「空消息也能发」）；</li>
 *   <li>文本空白 <b>但</b> 有附件 ⇒ 放行（本次放开的目标行为）。</li>
 * </ol>
 * 两个方向同批测，避免把校验整段删掉后测试仍绿。
 */
@DisplayName("[纯附件消息] content 仅在无附件时必填")
class ChatControllerEmptyContentTest {

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        // 判据在 send 最前面，正常不会触达 chatService；注入 mock 防后续顺序变化时 NPE
        ReflectionTestUtils.setField(controller, "chatService", mock(ChatService.class));
    }

    @Test
    @DisplayName("空 content + 无附件 → 非法（保持原 400 语义）")
    void blankContent_noAttachments_invalid() {
        SendMessageRequest req = new SendMessageRequest("", null, null, null, null, null, null, null, null);
        assertThat(ChatController.isEmptyContentWithoutAttachments(req)).isTrue();
    }

    @Test
    @DisplayName("空白 content（空格/换行）+ 无附件 → 非法")
    void whitespaceContent_noAttachments_invalid() {
        SendMessageRequest req = new SendMessageRequest(" \n\t ", null, null, null, null, null, null, null, null);
        assertThat(ChatController.isEmptyContentWithoutAttachments(req)).isTrue();
    }

    @Test
    @DisplayName("空 content + 有附件 → 合法（纯附件消息）")
    void blankContent_withAttachment_valid() {
        SendMessageRequest req = new SendMessageRequest("", null, null,
            List.of(attachment("notes.txt")), null, null, null, null, null);
        assertThat(ChatController.isEmptyContentWithoutAttachments(req)).isFalse();
    }

    @Test
    @DisplayName("有文本 + 无附件 → 合法（原行为不回归）")
    void textContent_noAttachments_valid() {
        SendMessageRequest req = new SendMessageRequest("看下这个报错", null, null, null, null, null, null, null, null);
        assertThat(ChatController.isEmptyContentWithoutAttachments(req)).isFalse();
    }

    @Test
    @DisplayName("send 路径：空 content + 无附件 → ValidationException（400），不进入发送流程")
    void send_blankWithoutAttachments_throwsValidation() {
        SendMessageRequest req = new SendMessageRequest("", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.send("sess-empty", req))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("消息内容不能为空");
    }

    private static AttachmentRequest attachment(String filename) {
        return new AttachmentRequest("file", "1", filename, "text/plain", null, null);
    }
}
