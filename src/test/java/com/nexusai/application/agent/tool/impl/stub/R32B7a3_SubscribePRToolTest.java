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
 * R32-b7a-3 · SubscribePRTool 真实现行为验证（G11+G32① 重写后新契约）。
 *
 * <p><b>WHY (意图验证)</b>: SubscribePRTool 已按 CC {@code SubscribePRTool.ts} 从 fail-loud 注册桩
 * 重写为真实现，测试须锁定新契约:
 * <ul>
 *   <li><b>name() = PascalCase</b> {@code "SubscribePR"}（CC SubscribePRTool.ts:6
 *       {@code SUBSCRIBE_PR_TOOL_NAME='SubscribePR'}；旧 snake_case {@code "subscribe_pr"} 已废弃）。</li>
 *   <li><b>description() 真实现描述</b>（不再含 "stub"/"未实现" 占位词）。</li>
 *   <li><b>inputSchema additionalProperties=false</b>（CC {@code z.strictObject({repo,pr_number,events?})}）。</li>
 *   <li><b>execute 返回真结果</b>: 无 KAIROS GitHub webhook 子系统 → 成功结果
 *       {@code {"subscribed":false,"subscription_id":"","error":"SubscribePR requires..."}}
 *       （CC SubscribePRTool.ts:72-82 真源回退；不再返回 fail-loud error）。</li>
 * </ul>
 *
 * @see SubscribePRTool
 */
class R32B7a3_SubscribePRToolTest {

    private final SubscribePRTool tool = new SubscribePRTool();

    @Test
    @DisplayName("name() 返回 'SubscribePR' (对齐 CC SubscribePRTool.ts:6 SUBSCRIBE_PR_TOOL_NAME)")
    void nameAlignsWithCc() {
        // WHY: G11 改名后 name() 必须 = CC 真名 'SubscribePR'（PascalCase）.
        assertThat(tool.name())
            .as("SubscribePRTool name 必须 = ToolNameConstants.SUBSCRIBE_PR_TOOL_NAME")
            .isEqualTo(ToolNameConstants.SUBSCRIBE_PR_TOOL_NAME)
            .isEqualTo("SubscribePR");
    }

    @Test
    @DisplayName("description() 真实现描述（非 stub，无未实现占位词）")
    void descriptionIsReal() {
        // WHY: G32① 重写后 description 是真实能力描述，不再提示 "stub/未实现".
        String desc = tool.description();
        assertThat(desc).isNotBlank();
        assertThat(desc).doesNotContain("stub").doesNotContain("未实现");
        assertThat(desc).isEqualTo("Subscribe to pull request events via GitHub webhooks");
    }

    @Test
    @DisplayName("inputSchema() additionalProperties=false (CC z.strictObject)")
    void inputSchemaIsStrict() {
        // WHY: CC SubscribePRTool.ts:8-17 z.strictObject({repo,pr_number,events?}) 拒绝任意键.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.toString()).contains("repo").contains("pr_number");
    }

    @Test
    @DisplayName("isEnabled() 默认 true (Tool 基类 default true, CC descriptor 无 override)")
    void isEnabledDefaultsTrue() {
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("execute → 成功结果含 subscribed:false + KAIROS 子系统不可用（非 error）")
    void executeReturnsSuccessUnavailable() {
        // WHY: CC SubscribePRTool.ts:72-82 call 恒返回 {subscribed:false, subscription_id:'',
        // error:'SubscribePR requires the KAIROS GitHub webhook subsystem.'} — 这是 CC 真源行为
        // （webhook 订阅由 KAIROS 子系统管理，无 KAIROS 即不可用），不是 fail-loud 桩 error.
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("repo", "owner/repo");
        input.put("pr_number", 123);
        ToolUseBlock call = new ToolUseBlock("call-spr-1", "SubscribePR", input);
        AgentToolResult<?> result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("SubscribePR 无 KAIROS 时返回成功结果, 不是 fail-loud error")
            .isFalse();
        assertThat(String.valueOf(tr.data())).contains("\"subscribed\":false");
        assertThat(String.valueOf(tr.data())).contains("KAIROS GitHub webhook subsystem");
    }

    @Test
    @DisplayName("NAME 常量 = 'SubscribePR' public static final")
    void nameConstantExposed() {
        assertThat(SubscribePRTool.NAME)
            .as("SubscribePRTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("SubscribePR");
    }
}
