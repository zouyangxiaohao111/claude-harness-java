package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sm-boundary-reload] {@code message.insert} 出站序列化契约测试（防事件名/行体字段漂移）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：本事件是「即时层」的唯一载体 —— 前端 useChatSocket 按
 * {@code type === 'message.insert'} 分派，再按行内 {@code id} 做幂等插入（同 id 重拉收敛）；
 * 行体必须携带前端渲染分割线所需的 {@code role}/{@code subtype}（前端据此渲染「已压缩」样式）。
 * 三者任一漂移（事件名改成 message.inserted / 行体被包成 {messages:{rows:[]}} / subtype 丢失）
 * ⇒ 前端静默不插行 ⇒ 分割线又消失（本修复失效）。故此处钉死序列化后的**扁平** JSON 形状。
 *
 * <p>ObjectMapper 带 JavaTimeModule：行体 {@code createdAt} 是 {@code OffsetDateTime}
 * （同仓既有 DTO 序列化测试同款写法，见 {@code CompactBoundaryMessageCcTest:36-37}）。
 */
class MessageInsertEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("出站契约：type=message.insert + sessionId + messages[0] 含 id/role/subtype")
    void serializesBoundaryRow() throws Exception {
        ChatMessageDto boundary = CompactBoundaryMessage
            .createCompactBoundaryMessage("auto", 100, null, null, null)
            .toChatMessageDto();
        MessageInsertEvent evt = new MessageInsertEvent("s1", List.of(boundary));

        String json = mapper.writeValueAsString(evt);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("type").asText())
            .as("事件名 = message.insert（前端 dispatchEvent 的 else-if 分支判据）")
            .isEqualTo("message.insert");
        assertThat(root.get("sessionId").asText())
            .as("会话归属（前端按会话落 store）")
            .isEqualTo("s1");

        JsonNode row = root.get("messages").get(0);
        assertThat(row).as("行体数组非空（前端要按行插入）").isNotNull();
        assertThat(row.get("id").asText())
            .as("行 id 出站（前端靠它做同 id 幂等，与重拉收敛）")
            .isEqualTo(boundary.id());
        assertThat(row.get("role").asText())
            .as("role=system（前端分割线渲染分支判据之一）")
            .isEqualTo("system");
        assertThat(row.get("subtype").asText())
            .as("subtype=compact_boundary（前端「已压缩」样式判据，BoundaryReader 同源）")
            .isEqualTo("compact_boundary");
    }
}
