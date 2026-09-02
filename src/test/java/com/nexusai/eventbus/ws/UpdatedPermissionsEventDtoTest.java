package com.nexusai.eventbus.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Session S16] updatedPermissions 事件 DTO 测试。
 *
 * <p>验证：
 * <ul>
 *   <li>{@link MessagePermissionResponseEvent}（本地弹窗批准）可反序列化
 *       updatedPermissions（CC PermissionUpdateSchema 判别联合形状）并透传到
 *       {@link WebSocketPermissionPrompter#parseUpdatedPermissions}；</li>
 *   <li>{@link BridgePermissionResponseEvent}（远程表面批准）同；</li>
 *   <li>旧形状（无 updatedPermissions 字段）向后兼容；</li>
 *   <li>序列化时 null 字段省略（{@code @JsonInclude(NON_NULL)}）。</li>
 * </ul>
 */
@DisplayName("[S16] updatedPermissions 事件 DTO 序列化/反序列化")
class UpdatedPermissionsEventDtoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String addRulesPayload(String requestId) {
        return "{"
            + "\"requestId\":\"" + requestId + "\","
            + "\"decision\":\"allow\","
            + "\"updatedPermissions\":[{"
            + "  \"type\":\"addRules\","
            + "  \"destination\":\"userSettings\","
            + "  \"behavior\":\"allow\","
            + "  \"rules\":[{\"toolName\":\"Bash\",\"ruleContent\":\"git status\"}]"
            + "}]"
            + "}";
    }

    @Test
    @DisplayName("MessagePermissionResponseEvent 反序列化 updatedPermissions（CC 判别联合形状）")
    void messageResponseEvent_deserializesUpdatedPermissions() throws Exception {
        MessagePermissionResponseEvent event = JSON.readValue(
            addRulesPayload("req-up-1"), MessagePermissionResponseEvent.class);

        assertThat(event.getRequestId()).isEqualTo("req-up-1");
        assertThat(event.getDecision()).isEqualTo("allow");
        assertThat(event.getUpdatedPermissions()).hasSize(1);

        List<PermissionUpdate> updates =
            WebSocketPermissionPrompter.parseUpdatedPermissions(event.getUpdatedPermissions());
        assertThat(updates).hasSize(1);
        PermissionUpdate update = updates.get(0);
        assertThat(update).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules add = (PermissionUpdate.AddRules) update;
        assertThat(add.destination()).isEqualTo(PermissionUpdate.Destination.USER_SETTINGS);
        assertThat(add.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(add.rules()).hasSize(1);
        assertThat(add.rules().get(0).ruleValue().toolName()).isEqualTo("Bash");
        assertThat(add.rules().get(0).ruleValue().ruleContent()).isEqualTo("git status");
        // [OPD-PERM-08] 解析层规则 source 与目标桶一致
        assertThat(add.rules().get(0).source()).isEqualTo(PermissionRuleSource.USER_SETTINGS);
    }

    @Test
    @DisplayName("BridgePermissionResponseEvent 反序列化 updatedPermissions")
    void bridgeResponseEvent_deserializesUpdatedPermissions() throws Exception {
        String payload = "{"
            + "\"requestId\":\"br-up-1\","
            + "\"behavior\":\"allow\","
            + "\"message\":null,"
            + "\"updatedInput\":{\"path\":\"/remote/x\"},"
            + "\"updatedPermissions\":[{"
            + "  \"type\":\"addRules\","
            + "  \"destination\":\"projectSettings\","
            + "  \"behavior\":\"allow\","
            + "  \"rules\":[{\"toolName\":\"Bash\",\"ruleContent\":\"npm publish\"}]"
            + "}]"
            + "}";
        BridgePermissionResponseEvent event = JSON.readValue(payload, BridgePermissionResponseEvent.class);

        assertThat(event.getRequestId()).isEqualTo("br-up-1");
        assertThat(event.getBehavior()).isEqualTo("allow");
        assertThat(event.getUpdatedInput().path("path").asText()).isEqualTo("/remote/x");
        assertThat(event.getUpdatedPermissions()).hasSize(1);

        List<PermissionUpdate> updates =
            WebSocketPermissionPrompter.parseUpdatedPermissions(event.getUpdatedPermissions());
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).isInstanceOf(PermissionUpdate.AddRules.class);
    }

    @Test
    @DisplayName("旧形状（无 updatedPermissions）向后兼容 → null")
    void oldShape_backwardCompatible() throws Exception {
        MessagePermissionResponseEvent messageEvent = JSON.readValue(
            "{\"requestId\":\"req-old\",\"decision\":\"deny\"}", MessagePermissionResponseEvent.class);
        assertThat(messageEvent.getUpdatedPermissions()).isNull();

        BridgePermissionResponseEvent bridgeEvent = JSON.readValue(
            "{\"requestId\":\"br-old\",\"behavior\":\"deny\",\"message\":\"no\"}",
            BridgePermissionResponseEvent.class);
        assertThat(bridgeEvent.getUpdatedPermissions()).isNull();
        // 旧前端响应仍可被新后端完整消费（prompter 解析 null → 空列表）
        assertThat(WebSocketPermissionPrompter.parseUpdatedPermissions(
            bridgeEvent.getUpdatedPermissions())).isEmpty();
    }

    @Test
    @DisplayName("序列化省略 null updatedPermissions（@JsonInclude NON_NULL）")
    void serializationOmitsNull() throws Exception {
        MessagePermissionResponseEvent event = new MessagePermissionResponseEvent("req-n1", "allow");
        String json = JSON.writeValueAsString(event);
        JsonNode node = JSON.readTree(json);
        assertThat(node.has("updatedPermissions"))
            .as("null updatedPermissions 必须省略（旧前端兼容）")
            .isFalse();

        BridgePermissionResponseEvent bridgeEvent = new BridgePermissionResponseEvent(
            "br-n1", "allow", null, null, null);
        JsonNode bridgeNode = JSON.readTree(JSON.writeValueAsString(bridgeEvent));
        assertThat(bridgeNode.has("updatedPermissions")).isFalse();
    }

    @Test
    @DisplayName("4 参兼容构造器 → updatedPermissions=null")
    void fourArgConstructorCompat() {
        MessagePermissionResponseEvent event = new MessagePermissionResponseEvent(
            "req-4", "allow", "反馈", List.of());
        assertThat(event.getUpdatedPermissions()).isNull();
        assertThat(event.getAcceptFeedback()).isEqualTo("反馈");
    }
}
