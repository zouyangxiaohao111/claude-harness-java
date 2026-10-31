package com.nexusai.eventbus.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload;
import com.nexusai.application.agent.permission.PermissionDecisionReason;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * [Phase 1b · D] 权限弹窗事件的 <b>computer-use 面板数据</b>字段单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：前端要显示「哪些应用、每个授权到什么程度、
 * 模型还请求了哪些额外权限、为什么」⇒ 后端必须把这份数据<b>成形状</b>推下去。
 * 三条语义必须锁死：
 * <ol>
 *   <li><b>字段形状固定</b>：{@code computerUse.apps[].{appId,displayName,tier,tierLabel,
 *       alreadyGranted,notInstalled}} + {@code reason} + {@code requestedFlags} + {@code flagLabels}
 *       —— 前端按此实现，形状漂移 = 前端渲染错。</li>
 *   <li><b>label 由后端给</b>（前端不翻译业务语义）：{@code tierLabel} / {@code flagLabels}。</li>
 *   <li><b>既有构造器与既有调用不受影响</b>：老构造器仍可编译、仍产出 {@code computerUse == null}
 *       ⇒ 序列化时<b>省略该字段</b>（前端向后兼容）。</li>
 * </ol>
 */
class MessagePermissionRequestEventComputerUseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ComputerUseApprovalPayload samplePayload() {
        return new ComputerUseApprovalPayload(
                List.of(new ComputerUseApprovalPayload.App(
                        "chrome", "Chrome", "read", "只能查看", false, false)),
                "模型给的理由",
                List.of("clipboardRead"),
                Map.of("clipboardRead", "允许读取剪贴板"));
    }

    @Test
    @DisplayName("computerUse 字段形状固定（前端按此实现）")
    void computerUseFieldHasFixedShape() {
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
                "sess-1", "req-1", "tool-1", "mcp__computer-use__request_access",
                JSON.createObjectNode(), new PermissionDecisionReason.Other("cu"),
                "描述", List.of(), null, null, null,
                samplePayload(), false);

        JsonNode node = JSON.valueToTree(event);
        JsonNode cu = node.get("computerUse");
        assertThat(cu).isNotNull();

        JsonNode app = cu.path("apps").get(0);
        assertThat(app.path("appId").asText()).isEqualTo("chrome");
        assertThat(app.path("displayName").asText()).isEqualTo("Chrome");
        assertThat(app.path("tier").asText()).isEqualTo("read");
        assertThat(app.path("tierLabel").asText()).isEqualTo("只能查看");
        assertThat(app.path("alreadyGranted").asBoolean()).isFalse();
        assertThat(app.path("notInstalled").asBoolean()).isFalse();

        assertThat(cu.path("reason").asText()).isEqualTo("模型给的理由");
        assertThat(cu.path("requestedFlags").get(0).asText()).isEqualTo("clipboardRead");
        assertThat(cu.path("flagLabels").path("clipboardRead").asText()).isEqualTo("允许读取剪贴板");
    }

    @Test
    @DisplayName("computerUse = null ⇒ 序列化时省略该字段（前端向后兼容）")
    void nullComputerUseIsOmittedFromJson() {
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
                "sess-1", "req-1", "tool-1", "Bash",
                JSON.createObjectNode(), new PermissionDecisionReason.Other("x"),
                "描述", List.of(), null, null, null, null, false);

        JsonNode node = JSON.valueToTree(event);
        assertThat(node.has("computerUse")).isFalse();
        assertThat(event.getComputerUse()).isNull();
    }

    @Test
    @DisplayName("既有构造器不受影响（computerUse 缺省 = null）")
    void existingConstructorsStillWork() {
        // 既有 7 参构造器
        MessagePermissionRequestEvent legacy = new MessagePermissionRequestEvent(
                "sess-1", "req-1", "tool-1", "Bash", JSON.createObjectNode(),
                new MessagePermissionRequestEvent.PermissionDecisionReasonDto("r"), 123L);
        assertThat(legacy.getComputerUse()).isNull();
        assertThat(legacy.getRequestId()).isEqualTo("req-1");
        assertThat(legacy.getTimestampMs()).isEqualTo(123L);

        // 既有 12 参构造器（含 warning + workerBadgeColor）
        MessagePermissionRequestEvent full = new MessagePermissionRequestEvent(
                "sess-1", "req-1", "tool-1", "Bash", JSON.createObjectNode(),
                new MessagePermissionRequestEvent.PermissionDecisionReasonDto("r"),
                "描述", List.of(), null, "警告", "#fff", 456L);
        assertThat(full.getComputerUse()).isNull();
        assertThat(full.getWarning()).isEqualTo("警告");
        assertThat(full.getWorkerBadgeColor()).isEqualTo("#fff");

        // 既有静态工厂（12 参）
        MessagePermissionRequestEvent viaFactory = MessagePermissionRequestEvent.of(
                "sess-1", "req-1", "tool-1", "Bash", JSON.createObjectNode(),
                new PermissionDecisionReason.Other("x"), "描述", List.of(), null, "警告", "#fff", false);
        assertThat(viaFactory.getComputerUse()).isNull();
    }

    @Test
    @DisplayName("序列化样例（真实 JSON 输出，供前端对齐）")
    void serializedSample() throws Exception {
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
                "sess-1", "req-1", "tool-1", "mcp__computer-use__request_access",
                JSON.createObjectNode(), new PermissionDecisionReason.Other("cu"),
                "描述", List.of(), null, null, null,
                samplePayload(), false);

        System.out.println("[D] MessagePermissionRequestEvent 序列化样例 = "
                + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event));
        assertThat(JSON.writeValueAsString(event)).contains("\"computerUse\"");
    }
}
