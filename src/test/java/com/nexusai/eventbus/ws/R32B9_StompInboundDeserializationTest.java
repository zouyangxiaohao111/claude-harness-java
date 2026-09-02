package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R32-b9-fix · Phase 7 · STOMP inbound JSON 反序列化 + 校验.
 *
 * <p><b>WHY (意图验证)</b>: b9 reviewer P2-1 发现 STOMP inbound 的
 * {@code MessagePermissionResponseEvent.contentBlocks} 字段无校验:
 * <ul>
 *   <li>size 上限缺失 → 异常 payload 可塞超大 list(性能/资源)</li>
 *   <li>null element → Provider 序列化 NPE</li>
 *   <li>非 ObjectNode → 序列化产出无效 provider payload</li>
 *   <li>超长 acceptFeedback → 用户错误/恶意输入无防御</li>
 * </ul>
 *
 * <p>Fix B 加 {@code @Size(max=20)} + getter 运行时校验(Fail loud · CLAUDE.md 规则 12)。
 * 本测试覆盖:
 * <ul>
 *   <li>正常 inbound: 合法 image/text contentBlocks → 反序列化 OK + 字段透传</li>
 *   <li>空/缺失字段: 旧前端不传 contentBlocks → null 容忍(向后兼容)</li>
 *   <li>超长 list: 21 个 block → getter 抛 IllegalArgumentException</li>
 *   <li>null element: list 含 null → getter 抛 IllegalArgumentException</li>
 *   <li>非 ObjectNode: array/string/数值 → getter 抛 IllegalArgumentException</li>
 *   <li>畸形 JSON: Jackson 反序列化失败(期望清晰错误)</li>
 * </ul>
 *
 * <p>WHY 覆盖各种畸形 inbound: 防御 STOMP 客户端 BUG / 恶意 payload / 前端版本不匹配。
 * 后端必须 fail loud(CLAUDE.md 规则 12)而非默默吞掉或 NPE。
 *
 * @see MessagePermissionResponseEvent
 */
class R32B9_StompInboundDeserializationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("正常 inbound: 2 个 image block + acceptFeedback → 反序列化 + 校验 OK")
    void normalInboundImageBlocks() throws Exception {
        String payload = """
            {
              "requestId": "req-stomp-1",
              "decision": "allow",
              "acceptFeedback": "用户允许并附反馈",
              "contentBlocks": [
                {"type":"image","source":{"type":"url","url":"http://x/a.png"}},
                {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"ABCD"}}
              ]
            }
            """;
        MessagePermissionResponseEvent event =
            JSON.readValue(payload, MessagePermissionResponseEvent.class);

        assertThat(event.getRequestId()).isEqualTo("req-stomp-1");
        assertThat(event.getDecision()).isEqualTo("allow");
        assertThat(event.getAcceptFeedback()).isEqualTo("用户允许并附反馈");
        List<JsonNode> blocks = event.getContentBlocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("image");
        assertThat(blocks.get(1).get("source").get("data").asText()).isEqualTo("ABCD");
    }

    @Test
    @DisplayName("空 contentBlocks / 缺字段: 向后兼容(旧前端),getter 返 null 不抛")
    void missingContentBlocksBackwardsCompatible() throws Exception {
        // 旧前端只发 requestId + decision
        String payload = """
            {"requestId":"req-old","decision":"allow"}
            """;
        MessagePermissionResponseEvent event =
            JSON.readValue(payload, MessagePermissionResponseEvent.class);

        assertThat(event.getAcceptFeedback()).isNull();
        assertThat(event.getContentBlocks()).isNull();
    }

    @Test
    @DisplayName("空 list contentBlocks: 反序列化 OK,getter 返空 list")
    void emptyContentBlocksAllowed() throws Exception {
        String payload = """
            {"requestId":"req-empty","decision":"allow","contentBlocks":[]}
            """;
        MessagePermissionResponseEvent event =
            JSON.readValue(payload, MessagePermissionResponseEvent.class);
        assertThat(event.getContentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("[Fix B] 21 个 block 超限 → getter 抛 IllegalArgumentException")
    void oversizeContentBlocksRejected() throws Exception {
        // 手写 21 个 image block
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"req-big\",\"decision\":\"allow\",\"contentBlocks\":[");
        for (int i = 0; i < MessagePermissionResponseEvent.MAX_CONTENT_BLOCKS + 1; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/").append(i).append(".png\"}}");
        }
        sb.append("]}");
        MessagePermissionResponseEvent event = JSON.readValue(sb.toString(),
            MessagePermissionResponseEvent.class);

        assertThatThrownBy(event::getContentBlocks)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds limit");
    }

    @Test
    @DisplayName("[Fix B] 恰好 20 个 block (上限) → 校验通过,getter 不抛")
    void exactlyMaxBlocksAllowed() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"req-max\",\"decision\":\"allow\",\"contentBlocks\":[");
        for (int i = 0; i < MessagePermissionResponseEvent.MAX_CONTENT_BLOCKS; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/").append(i).append(".png\"}}");
        }
        sb.append("]}");
        MessagePermissionResponseEvent event = JSON.readValue(sb.toString(),
            MessagePermissionResponseEvent.class);
        assertThat(event.getContentBlocks()).hasSize(MessagePermissionResponseEvent.MAX_CONTENT_BLOCKS);
    }

    @Test
    @DisplayName("[Fix B] contentBlocks 含 null element → getter 抛 IAE (Fail loud · 防 NPE)")
    void nullElementInContentBlocksRejected() throws Exception {
        // Jackson 默认会反序列化 null 元素
        String payload = """
            {"requestId":"req-null","decision":"allow","contentBlocks":[{"type":"image"},null]}
            """;
        MessagePermissionResponseEvent event = JSON.readValue(payload,
            MessagePermissionResponseEvent.class);

        assertThatThrownBy(event::getContentBlocks)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null element");
    }

    @Test
    @DisplayName("[Fix B] contentBlocks 元素非 ObjectNode (string/array/数值) → IAE")
    void nonObjectElementRejected() throws Exception {
        // 字符串元素 (实际畸形 STOMP payload)
        String payload = """
            {"requestId":"req-str","decision":"allow","contentBlocks":["just a string"]}
            """;
        MessagePermissionResponseEvent event = JSON.readValue(payload,
            MessagePermissionResponseEvent.class);

        assertThatThrownBy(event::getContentBlocks)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be JSON object");
    }

    @Test
    @DisplayName("[Fix B] contentBlocks 元素为 array → IAE (非 ObjectNode)")
    void arrayElementRejected() throws Exception {
        String payload = """
            {"requestId":"req-arr","decision":"allow","contentBlocks":[[1,2,3]]}
            """;
        MessagePermissionResponseEvent event = JSON.readValue(payload,
            MessagePermissionResponseEvent.class);

        assertThatThrownBy(event::getContentBlocks)
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("防御性 copy: 修改 getter 返回的 list 不影响 event 内部状态")
    void defensiveCopyOnGetter() throws Exception {
        String payload = """
            {"requestId":"req-copy","decision":"allow","contentBlocks":[{"type":"image"}]}
            """;
        MessagePermissionResponseEvent event = JSON.readValue(payload,
            MessagePermissionResponseEvent.class);

        List<JsonNode> first = event.getContentBlocks();
        first.clear();  // 尝试修改 defensive copy
        List<JsonNode> second = event.getContentBlocks();
        assertThat(second).hasSize(1);  // 内部状态未受影响
    }

    @Test
    @DisplayName("畸形 JSON: 缺右括号 → Jackson 反序列化失败")
    void malformedJsonFails() {
        String payload = "{\"requestId\":\"req-bad\",\"decision\":\"allow\"";  // 缺 }
        assertThatThrownBy(() -> JSON.readValue(payload, MessagePermissionResponseEvent.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    @DisplayName("畸形 JSON: 顶层为数组而非对象 → Jackson 期望对象,实际数组")
    void wrongFieldTypeFails() {
        // 顶层为 array — Jackson 无法映射到 Object 类型,期望明确失败
        String payload = "[{\"requestId\":\"req-1\",\"decision\":\"allow\"}]";
        assertThatThrownBy(() -> JSON.readValue(payload, MessagePermissionResponseEvent.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}