package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-3 · PushNotificationTool 真实现行为验证（G11+G32① 重写后新契约）。
 *
 * <p><b>WHY (意图验证)</b>: PushNotificationTool 已按 CC {@code PushNotificationTool.ts} 从
 * fail-loud 注册桩重写为真实现，测试须锁定新契约:
 * <ul>
 *   <li><b>name() = PascalCase</b> {@code "PushNotification"}（CC PushNotificationTool.ts:9
 *       {@code PUSH_NOTIFICATION_TOOL_NAME='PushNotification'}；旧 snake_case
 *       {@code "push_notification"} 已废弃）。</li>
 *   <li><b>description() 真实现描述</b>（不再含 "stub"/"未实现" 占位词）。</li>
 *   <li><b>inputSchema additionalProperties=false</b>（CC {@code z.strictObject({title,body,priority?})}）。</li>
 *   <li><b>isEnabled() = bridgeEnabled</b>（CC isBridgeEnabled() 等价; 默认 false 不暴露）。</li>
 *   <li><b>execute 返回真结果</b>: 无 Remote Control bridge → 成功结果
 *       {@code {"sent":false,"error":"No Remote Control bridge configured..."}}
 *       （CC PushNotificationTool.ts:138-147 无 bridge 回退；不再返回 error）。</li>
 * </ul>
 *
 * @see PushNotificationTool
 */
class R32B7a3_PushNotificationToolTest {

    private final PushNotificationTool tool = new PushNotificationTool();

    @Test
    @DisplayName("name() 返回 'PushNotification' (对齐 CC PushNotificationTool.ts:9 PUSH_NOTIFICATION_TOOL_NAME)")
    void nameAlignsWithCc() {
        // WHY: G11 改名后 name() 必须 = CC 真名 'PushNotification'（PascalCase）.
        assertThat(tool.name())
            .as("PushNotificationTool name 必须 = ToolNameConstants.PUSH_NOTIFICATION_TOOL_NAME")
            .isEqualTo(ToolNameConstants.PUSH_NOTIFICATION_TOOL_NAME)
            .isEqualTo("PushNotification");
    }

    @Test
    @DisplayName("description() 真实现描述（非 stub，无未实现占位词）")
    void descriptionIsReal() {
        // WHY: G32① 重写后 description 是真实能力描述，不再提示 "stub/未实现".
        String desc = tool.description();
        assertThat(desc).isNotBlank();
        assertThat(desc).doesNotContain("stub").doesNotContain("未实现");
        assertThat(desc).isEqualTo("Send a push notification to the user's mobile device");
    }

    @Test
    @DisplayName("inputSchema() additionalProperties=false (CC z.strictObject)")
    void inputSchemaIsStrict() {
        // WHY: CC PushNotificationTool.ts:11-22 z.strictObject({title,body,priority?}) 拒绝任意键.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        // title/body 必填
        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.toString()).contains("title").contains("body");
    }

    @Test
    @DisplayName("isEnabled() = bridgeEnabled（默认 false, 无 bridge 不暴露）")
    void isEnabledIsBridgeEnabled() {
        // WHY: 对齐 CC PushNotificationTool.ts:52-54 isEnabled() = isBridgeEnabled().
        // Java 单配置 nexusai.bridge.enabled 默认 false → 工具默认不暴露给 LLM.
        assertThat(tool.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("execute → 成功结果含 sent 字段 + No Remote Control bridge（非 error）")
    void executeReturnsSuccessWithoutBridge() {
        // WHY: CC PushNotificationTool.ts:138-147 无 bridge 时返回成功结果
        // {sent:false, error:'No Remote Control bridge configured. Notification not delivered.'}.
        // 这是 CC 真源回退行为，不是 fail-loud error — 与旧桩的关键差异.
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("title", "任务完成");
        input.put("body", "后台任务已完成");
        ToolUseBlock call = new ToolUseBlock("call-pn-1", "PushNotification", input);
        AgentToolResult<?> result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("无 bridge 时返回成功结果, 不是 error")
            .isFalse();
        assertThat(String.valueOf(tr.data())).contains("sent");
        assertThat(String.valueOf(tr.data())).contains("No Remote Control bridge");
    }

    @Test
    @DisplayName("NAME 常量 = 'PushNotification' public static final")
    void nameConstantExposed() {
        assertThat(PushNotificationTool.NAME)
            .as("PushNotificationTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("PushNotification");
    }
}
