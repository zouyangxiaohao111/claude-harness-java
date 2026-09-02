package com.nexusai.application.agent.memory;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-1（IMP-MV2-25）· A3 ?-1 tool_result 表示形态运行断言（AnthropicSdkProvider 序列化侧 +
 * SessionMemoryService.getToolResultIds 漏检判定）。
 *
 * <p>探查证据（raw/session-memory/探查-mm-a3-session-memory-compact.md §9 ?-1 / EV-29）：
 * Java getToolResultIds 依赖「生产消息流中 tool_result 以 role=tool + toolCallId 独立消息存储」
 * （SessionMemoryService:1811-1819）；若生产合并入 user 消息 contentBlocks（CC 形态）则检测失效。
 * 本测试给出运行证据三件套：
 *   <li><b>生产主形态</b>：LlmAgentLoop.toolResultMessage 恒产出 Role.tool + toolCallId
 *       （:8006-8021 核心构造；全部 5 处调用位置 4113/4692/6503 + ProductionForkedQuery:302
 *       + AgentLoopContext:1732/1740 共用同一工厂）。</li>
 *   <li><b>序列化侧</b>：AnthropicSdkProvider.buildSdkMessages 把 Role.tool + toolCallId 序列化为
 *       <b>user 角色 + tool_result 块（tool_use_id=toolCallId）</b>——与 CC 线上形态一致
 *       （:1432-1479 工具分支；方法起点 :1419），即 Java 会话内 DTO 形态是忠实内部表示，
 *       线上形态与 CC 全同。</li>
 *   <li><b>漏检判定</b>：getToolResultIds 对 role=tool 主形态全检；对「user 包裹 tool_result 块」
 *       形态漏检可达（adjustIndexToPreserveAPIInvariants 保留段边界偏移）——但全仓
 *       ChatMessageDto 主形态构造点共 3 处且全部产出 Role.tool + toolCallId（无
 *       user 包裹形态产出方）：① LlmAgentLoop.toolResultMessage（:8006-8021，5 调用位置）；
 *       ② SubagentExecutor.convertToChatMessageDto（:4304-4307，fork 前缀恢复路径）；
 *       ③ SubagentExecutor.toolResultMessage 影子工厂（:4425-4443，子 Agent 独立构造）。
 *       （另有 ChatService.newToolMessage :743-757 写 MessageRecord 持久化层，同样
 *       Role.tool + toolCallId 形态；loadRecentHistory :770 对 Role.tool 记录直接
 *       continue 跳过，持久化 tool 消息不重回会话 DTO 流——恢复路径仅含 user/assistant。）
 *       残余面收敛为「外部数据注入面」，主路径全称排除。</li>
 * </ol>
 */
@DisplayName("[E4-1] tool_result 表示形态：生产主形态 + 序列化侧 + 漏检面判定")
class E4ToolResultFormCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** ChatMessageDto 便捷构造（user 或 assistant 文本）。 */
    private static ChatMessageDto msg(Role role, String id, String content) {
        return new ChatMessageDto(id, null, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 会话内 tool_result 主形态：Role.tool + toolCallId（LlmAgentLoop.toolResultMessage 产物）。 */
    private static ChatMessageDto toolResultDto(String id, String toolCallId) {
        return new ChatMessageDto(id, null, Role.tool, "tool", "result payload", null,
            null, null, null, null, null, null, toolCallId, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** CC 形态（user 包裹）：user 消息 contentBlocks 含 type=tool_result 块。 */
    private static ChatMessageDto userWrappedToolResult(String id, String toolUseId) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", "result payload");
        return new ChatMessageDto(id, null, Role.user, "user", null, null,
            null, null, null, null, null, null, null, null, null,
            List.of(block), List.of());
    }

    @Test
    @DisplayName("生产主形态：toolResultMessage 恒产出 Role.tool + toolCallId（CC tool_result 的 Java 会话内 DTO 表示）")
    void productionMainForm_roleToolWithToolCallId() {
        // [IMP-C2 合并适配] ToolResult 已收敛为 CC 4 字段 record（data/newMessages/contextModifier/
        //   mcpMeta），toolUseId/isError 不再存于 result —— 改由工具调用块推导后经 mapper 参数透传。
        //   toolUseId 显式传入，断言语义（Role.tool + toolCallId）不变。
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage(
            new ToolResult<>("ok", null, null, null), "toolu_e4_1", false, null,
            null, null, java.util.List.of(), java.util.List.of(), java.util.Map.of());

        assertThat(dto.role()).as("会话内主形态 = Role.tool").isEqualTo(Role.tool);
        assertThat(dto.toolCallId()).as("toolCallId = toolUseId（getToolResultIds 检测面）")
            .isEqualTo("toolu_e4_1");
    }

    @Test
    @DisplayName("序列化侧：Role.tool + toolCallId → SDK user 角色 + tool_result 块（tool_use_id）——线上形态与 CC 全同")
    void serializationSide_roleToolBecomesUserWrappedToolResultOnWire() {
        List<ChatMessageDto> history = List.of(
            msg(Role.user, "u0", "turn1"),
            toolResultDto("tr1", "toolu_e4_1"));

        MessageCreateParams params = AnthropicSdkProviderShim.buildMessageParams(history);
        List<MessageParam> msgs = params.messages();
        assertThat(msgs).hasSize(2);

        MessageParam last = msgs.get(1);
        // anthropic-java：content 为 Union（String | List<ContentBlockParam>）——tool 消息必然块形态
        assertThat(last.role()).as("线上角色 = user（SDK 要求 tool_result 挂在 user 消息）")
            .isEqualTo(MessageParam.Role.USER);
        List<ContentBlockParam> blocks = last.content().asBlockParams();
        boolean foundToolResult = false;
        for (ContentBlockParam block : blocks) {
            java.util.Optional<ToolResultBlockParam> trbOpt = block.toolResult();
            if (trbOpt.isPresent()) {
                ToolResultBlockParam trb = trbOpt.get();
                assertThat(trb.toolUseId()).as("tool_result.tool_use_id = toolCallId（CC 线上形态）")
                    .isEqualTo("toolu_e4_1");
                foundToolResult = true;
            }
        }
        assertThat(foundToolResult).as("线上载荷含 tool_result 块").isTrue();
    }

    @Test
    @DisplayName("漏检判定：role=tool 主形态全检；user 包裹形态漏检可达（但生产无写入方）")
    void missedDetection_userWrappedFormVsMainForm() {
        SessionMemoryService svc = new SessionMemoryService(java.nio.file.Path.of("unused"));
        // 场景：保留段起始 = tool_result 所在索引（tool_use 在段前）——
        //   CC getToolResultIds 扫描保留段 [1..] 命中 tu1 → 回移纳入 tool_use。
        // 主形态：tool_result(role=tool, toolCallId=tu1) 在保留段内 → 检测 → 回移至 0。
        List<ChatMessageDto> mainForm = List.of(
            assistantWithToolUse("a0", "tu1"),
            toolResultDto("tr1", "tu1"));
        assertThat(svc.adjustIndexToPreserveAPIInvariants(mainForm, 1))
            .as("role=tool 主形态：getToolResultIds 命中 tu1 → 回移至 tool_use 所在索引 0")
            .isEqualTo(0);

        // CC 形态：tool_result 包裹在 user 消息 contentBlocks（type=tool_result JsonNode 块）。
        // Java getToolResultIds 仅认 role=tool → 漏检 → 不回移（CC 会回移，保留段边界偏移）。
        List<ChatMessageDto> wrappedForm = List.of(
            assistantWithToolUse("a0", "tu1"),
            userWrappedToolResult("tr1", "tu1"));
        assertThat(svc.adjustIndexToPreserveAPIInvariants(wrappedForm, 1))
            .as("user 包裹形态：getToolResultIds 漏检（返回空）→ adjustedIndex 不回移，保留段边界偏移")
            .isEqualTo(1);

        // 生产写入方全称排除：全仓 ChatMessageDto 主形态构造点共 3 处且全部产出
        // Role.tool + toolCallId（见 javadoc）：① LlmAgentLoop.toolResultMessage
        // （:8006-8021，5 处调用位置 LlmAgentLoop:4113/4692/6503 + ProductionForkedQuery:302
        // + AgentLoopContext:1732/1740）；② SubagentExecutor.convertToChatMessageDto
        // （:4304-4307，fork 前缀恢复路径）；③ SubagentExecutor.toolResultMessage 影子工厂
        // （:4425-4443，子 Agent 独立构造）——漏检面收敛为外部数据注入
        // （持久化恢复/外部 API 注入 user 包裹形态），主路径全称排除。
    }

    private static ChatMessageDto assistantWithToolUse(String id, String toolUseId) {
        ToolCallDto call = new ToolCallDto(toolUseId, "Bash", "{\"command\":\"ls\"}", null, null);
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "let me check",
            null, List.of(call), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /**
     * 最小 shim：AnthropicSdkProvider.buildMessageParams 为 public static（12 参重载），
     * 测试经最小参数集调用，避免直接依赖全部 SDK 参数类型。
     */
    private static final class AnthropicSdkProviderShim {
        static MessageCreateParams buildMessageParams(List<ChatMessageDto> history) {
            return com.nexusai.infra.llm.AnthropicSdkProvider.buildMessageParams(
                "test-model", List.of(), history, null, null, null, null, null, null, null, true,
                com.nexusai.infra.llm.ProviderConfig.empty());
        }
    }

}
