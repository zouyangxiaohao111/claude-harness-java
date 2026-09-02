package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9 · Phase 1 · ChatMessageDto 加 acceptFeedback/contentBlocks/imagePasteIds 字段.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 强制要求
 * {@code Open-ClaudeCode/src/utils/messages.ts:460-523} createUserMessage 签名中的
 * {@code imagePasteIds?: number[]} 字段 + {@code acceptFeedback}/{@code contentBlocks}
 * 透传到 ChatMessageDto。此测试验证:
 * <ul>
 *   <li>17-arg 构造器正常实例化字段(向后兼容 14-arg 模式已废弃,必须传全部 17 参)</li>
 *   <li>{@code acceptFeedback} + {@code contentBlocks} + {@code imagePasteIds} 三个新字段
 *       在 record accessors 中能正确读取(对齐 CC PermissionAllowDecision 字段语义)</li>
 *   <li>空 list 与 null list 都被 ChatMessageDto 接受(不强求非空,brief 未规定)</li>
 * </ul>
 *
 * @see com.nexusai.model.session.dto.ChatMessageDto
 */
class R32B9_ChatMessageDtoImagePasteIdsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("ChatMessageDto 17 字段构造: acceptFeedback + contentBlocks + imagePasteIds 可读")
    void allFieldsAccessible() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"fakepng\"}}"));

        ChatMessageDto msg = new ChatMessageDto(
            "msg-1", "sess-1", Role.user, "user", "hello world",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            "请注意:这张图有问题",  // R32-b9 acceptFeedback
            blocks,                  // R32-b9 contentBlocks
            List.of("1", "2")        // R32-b9 imagePasteIds
        );

        assertThat(msg.acceptFeedback()).isEqualTo("请注意:这张图有问题");
        assertThat(msg.contentBlocks()).hasSize(1);
        assertThat(msg.imagePasteIds()).containsExactly("1", "2");
        assertThat(msg.id()).isEqualTo("msg-1");
        assertThat(msg.role()).isEqualTo(Role.user);
    }

    @Test
    @DisplayName("ChatMessageDto 17 字段构造: null/empty 也允许(向后兼容)")
    void nullableFieldsAllowed() {
        // b9 brief 不要求 imagePasteIds 必须有值; ToolResult / 普通 user 消息走 null + 空 list
        ChatMessageDto msg = new ChatMessageDto(
            "msg-2", null, Role.tool, "tool", "tool result text",
            null, null, FinishReason.tool_calls, null, null, null,
            OffsetDateTime.now(), "call-id-1", null,
            null,
            List.of(),
            List.of()
        );

        assertThat(msg.acceptFeedback()).isNull();
        assertThat(msg.contentBlocks()).isEmpty();
        assertThat(msg.imagePasteIds()).isEmpty();
    }
}
