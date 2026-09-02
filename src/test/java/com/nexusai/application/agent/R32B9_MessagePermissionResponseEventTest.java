package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.eventbus.ws.MessagePermissionResponseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9 · Phase 6 · MessagePermissionResponseEvent 4 字段序列化 + 向后兼容.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 要求 STOMP 事件加 acceptFeedback + contentBlocks 字段;
 * 对齐 CC addToolResult allow 路径,让前端弹窗可携带这两个字段. 验证:
 * <ul>
 *   <li>4 参构造器正常填字段(JSON getter 可读)</li>
 *   <li>2 参构造器(向后兼容旧前端):feedback/blocks getter 返回 null(不抛 NPE)</li>
 *   <li>JSON 序列化用 {@code @JsonInclude(NON_NULL)} → null 字段不序列化到 STOMP payload</li>
 * </ul>
 *
 * <p>WHY 测 STOMP 序列化: 前端桥接时要读 acceptFeedback/contentBlocks;不为 null 省略
 * 字段让旧前端(不识别新字段)能继续工作(Jackson 默认忽略未知字段).
 *
 * @see MessagePermissionResponseEvent
 */
class R32B9_MessagePermissionResponseEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("4 参构造: acceptFeedback + contentBlocks getter 可读")
    void fourArgConstructor() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{}}"));
        MessagePermissionResponseEvent event =
            new MessagePermissionResponseEvent("req-1", "allow", "用户反馈:这条命令请重写", blocks);

        assertThat(event.getRequestId()).isEqualTo("req-1");
        assertThat(event.getDecision()).isEqualTo("allow");
        assertThat(event.getAcceptFeedback()).isEqualTo("用户反馈:这条命令请重写");
        assertThat(event.getContentBlocks()).hasSize(1);
    }

    @Test
    @DisplayName("2 参构造 (向后兼容): feedback/blocks getter 返回 null 不抛 NPE")
    void twoArgConstructorBackwardsCompatible() {
        MessagePermissionResponseEvent event =
            new MessagePermissionResponseEvent("req-2", "allow");

        assertThat(event.getRequestId()).isEqualTo("req-2");
        assertThat(event.getDecision()).isEqualTo("allow");
        assertThat(event.getAcceptFeedback()).isNull();
        assertThat(event.getContentBlocks()).isNull();
    }

    @Test
    @DisplayName("JSON 序列化: NULL 字段被 @JsonInclude(NON_NULL) 省略")
    void jsonSerializationNullFieldsOmitted() throws Exception {
        MessagePermissionResponseEvent event =
            new MessagePermissionResponseEvent("req-3", "allow");   // 旧形式

        String json = JSON.writeValueAsString(event);
        JsonNode node = JSON.readTree(json);

        assertThat(node.get("requestId").asText()).isEqualTo("req-3");
        assertThat(node.get("decision").asText()).isEqualTo("allow");
        assertThat(node.has("acceptFeedback")).isFalse();  // null 字段省略
        assertThat(node.has("contentBlocks")).isFalse();
    }

    @Test
    @DisplayName("JSON 序列化: 4 参全部字段被序列化")
    void jsonSerializationFourArgs() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"url\":\"http://x/y.png\"}}"));
        MessagePermissionResponseEvent event =
            new MessagePermissionResponseEvent("req-4", "deny", "用户拒绝", blocks);

        String json = JSON.writeValueAsString(event);
        JsonNode node = JSON.readTree(json);

        assertThat(node.get("requestId").asText()).isEqualTo("req-4");
        assertThat(node.get("decision").asText()).isEqualTo("deny");
        assertThat(node.get("acceptFeedback").asText()).isEqualTo("用户拒绝");
        assertThat(node.get("contentBlocks").isArray()).isTrue();
        assertThat(node.get("contentBlocks").size()).isEqualTo(1);
    }
}
