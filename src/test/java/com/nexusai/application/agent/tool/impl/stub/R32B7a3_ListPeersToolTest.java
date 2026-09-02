package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-3 · ListPeersTool 真实现行为验证（G11+G32① 重写后新契约）。
 *
 * <p><b>WHY (意图验证)</b>: ListPeersTool 已按 CC {@code ListPeersTool.ts} 从 fail-loud 注册桩
 * 重写为真实现，测试须锁定新契约:
 * <ul>
 *   <li><b>name() = PascalCase</b> {@code "ListPeers"}（CC ListPeersTool.ts:6
 *       {@code LIST_PEERS_TOOL_NAME='ListPeers'}；旧 snake_case {@code "list_peers"} 已废弃）。</li>
 *   <li><b>description() 真实现描述</b>（不再含 "stub"/"未实现" 占位词）。</li>
 *   <li><b>inputSchema additionalProperties=false</b>（CC {@code z.strictObject({include_self?})}）。</li>
 *   <li><b>execute 返回真结果</b>: 无 UDS 消息基础设施 → 成功结果 {@code {"peers":[]}}
 *       （CC ListPeersTool.ts:106-130 无 socket 时为空数组；不再返回 error）。</li>
 * </ul>
 *
 * @see ListPeersTool
 */
class R32B7a3_ListPeersToolTest {

    private final ListPeersTool tool = new ListPeersTool();

    @Test
    @DisplayName("name() 返回 'ListPeers' (对齐 CC ListPeersTool.ts:6 LIST_PEERS_TOOL_NAME)")
    void nameAlignsWithCc() {
        // WHY: G11 改名后 name() 必须 = CC 真名 'ListPeers'（PascalCase）.
        assertThat(tool.name())
            .as("ListPeersTool name 必须 = ToolNameConstants.LIST_PEERS_TOOL_NAME")
            .isEqualTo(ToolNameConstants.LIST_PEERS_TOOL_NAME)
            .isEqualTo("ListPeers");
    }

    @Test
    @DisplayName("description() 真实现描述（非 stub，无未实现占位词）")
    void descriptionIsReal() {
        // WHY: G32① 重写后 description 是真实能力描述，不再提示 "stub/未实现".
        String desc = tool.description();
        assertThat(desc).isNotBlank();
        assertThat(desc).doesNotContain("stub").doesNotContain("未实现");
        assertThat(desc).isEqualTo("Discover other Claude Code sessions for cross-session messaging");
    }

    @Test
    @DisplayName("inputSchema() additionalProperties=false (CC z.strictObject)")
    void inputSchemaIsStrict() {
        // WHY: CC ListPeersTool.ts:8-17 z.strictObject({include_self?}) 拒绝任意键.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").has("include_self")).isTrue();
    }

    @Test
    @DisplayName("isEnabled() 默认 true (Tool 基类 default true, CC descriptor 无 override)")
    void isEnabledDefaultsTrue() {
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("execute → 成功结果含 peers 字段（非 error）")
    void executeReturnsSuccessWithPeers() {
        // WHY: CC ListPeersTool.ts:85-135 call 发现对端; Java 无 UDS 消息基础设施 → peers 空数组
        // （CC :106-130 无 socket 时为空数组等价）. 必须是成功结果而非 error — 这是真实现与
        // 旧 fail-loud 桩的关键差异.
        ToolUseBlock call = new ToolUseBlock("call-lp-1", "ListPeers",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult<?> result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("ListPeers 无 UDS 时返回成功空 peers, 不是 error")
            .isFalse();
        assertThat(String.valueOf(tr.data())).contains("peers");
    }

    @Test
    @DisplayName("NAME 常量 = 'ListPeers' public static final")
    void nameConstantExposed() {
        assertThat(ListPeersTool.NAME)
            .as("ListPeersTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("ListPeers");
    }
}
