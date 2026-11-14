package com.nexusai.application.chat;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 尾部 meta 消息落库前补齐 {@code session_id} · 纯函数，不启动 Spring。
 *
 * <p>防的线上故障（2026-11-14）：{@code PostCompactAttachmentRestorer.buildAttachmentMessage}
 * 造的 DTO sessionId=null，而 {@code messages.session_id} 是 TEXT NOT NULL ⇒
 * {@code deferred_tools_delta} 等附件插入必败（日志里被 best-effort warn 吞掉），
 * 跨 run 重建历史时这条上下文就没了。
 */
class ChatServiceSessionBackfillTest {

    private static ChatMessageDto attachment(String sessionId) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.user, "attachment",
            "{\"addedNames\":[]}", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            "deferred_tools_delta");
    }

    @Test
    @DisplayName("sessionId 为空 → 补成当前会话（正是线上 deferred_tools_delta 的形状）")
    void fillsMissingSessionId() {
        ChatMessageDto filled = ChatService.withSessionIfAbsent(attachment(null), "sess-a4a11222");
        assertEquals("sess-a4a11222", filled.sessionId());
        assertEquals("deferred_tools_delta", filled.subtype(), "其余字段必须原样透传");
        assertEquals("attachment", filled.author());
        assertEquals("{\"addedNames\":[]}", filled.content());
    }

    @Test
    @DisplayName("已有 sessionId → 原样返回（不覆盖调用方给的值）")
    void keepsExistingSessionId() {
        ChatMessageDto original = attachment("sess-own");
        assertSame(original, ChatService.withSessionIfAbsent(original, "sess-other"));
    }

    @Test
    @DisplayName("null 入参安全：dto null / sessionId null 都不抛")
    void nullSafe() {
        assertNull(ChatService.withSessionIfAbsent(null, "sess-x"));
        ChatMessageDto m = attachment(null);
        assertSame(m, ChatService.withSessionIfAbsent(m, null));
    }

    @Test
    @DisplayName("空白 sessionId 也视为缺失（避免 \" \" 撞 NOT NULL 之外的脏数据）")
    void blankSessionIdTreatedAsMissing() {
        assertEquals("sess-x", ChatService.withSessionIfAbsent(attachment("   "), "sess-x").sessionId());
    }
}
