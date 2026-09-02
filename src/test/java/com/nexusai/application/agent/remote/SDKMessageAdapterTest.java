package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [W9-02 OPD-TS-31] SDKMessageAdapter 入站 tool_use_summary 联动测试。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则九 · 测试验证意图):
 * <ol>
 *   <li><b>入站不再丢弃</b> — 用户拍板 OPD-TS-31 超越 CC（CC remote/sdkMessageAdapter.ts:258-260
 *       仍 ignored）：后端收到远端回传的 tool_use_summary 时必须解析 summary +
 *       preceding_tool_use_ids 而非静默丢弃，否则出站（OPD-TS-29）接通后反向链路断裂。</li>
 *   <li><b>SDK snake_case 契约</b> — coreSchemas.ts:1769-1778 出站 shape 是
 *       {@code preceding_tool_use_ids}（snake_case），入站解析必须认同一 shape，避免双向契约漂移。</li>
 *   <li><b>可注入上下文</b> — 解析结果必须能经 {@link AttachmentMessageDto#toolUseSummary} 转
 *       attachment 注入 AgentState（对齐出站附件通道语义，仅 transcript/UI 可观测，不喂 LLM）。</li>
 *   <li><b>回归护栏</b> — auth_status / rate_limit_event 保持 ignored（CC 语义不变）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert case 到 ignored / 解析字段改名 → 测试 1-3 红。
 */
class SDKMessageAdapterTest {

    private final SDKMessageAdapter adapter = new SDKMessageAdapter();

    /**
     * 构造<em>扁平</em> tool_use_summary 消息（真实 wire shape · coreSchemas.ts:1769-1778）：
     * summary / preceding_tool_use_ids / session_id 顶层，message=null。
     * 契约漂移（读回嵌套信封）→ 此 helper 构造的扁平消息解析为空 → 断言红。
     */
    private static SDKMessageAdapter.SDKMessage sdkToolUseSummary(String summary,
                                                                  List<String> precedingToolUseIds) {
        return SDKMessageAdapter.SDKMessage.toolUseSummary(
            summary, precedingToolUseIds, "uuid-1", "2026-08-11T00:00:00Z", "session-1");
    }

    @Test
    @DisplayName("tool_use_summary 入站解析：非 ignored + summary/precedingToolUseIds 正确（OPD-TS-31）")
    void toolUseSummary_isParsed_notIgnored() {
        // 扁平 wire（CC 契约 shape）：summary/preceding_tool_use_ids 顶层，无 message 信封
        SDKMessageAdapter.SDKMessage msg = sdkToolUseSummary(
            "Ran failing tests, Fixed NPE in UserService", List.of("toolu_1", "toolu_2"));

        SDKMessageAdapter.ConvertedMessage cm =
            adapter.convertSDKMessage(msg, new SDKMessageAdapter.ConvertOptions(false, false));

        // 核心意图：不再被忽略，产出可消费 message
        assertThat(cm.type()).isEqualTo("message");
        assertThat(cm).isInstanceOf(SDKMessageAdapter.ConvertedMessageWrapper.class);
        Map<?, ?> inner = (Map<?, ?>) ((SDKMessageAdapter.ConvertedMessageWrapper) cm).message();
        assertThat(inner.get("type")).isEqualTo("tool_use_summary");
        assertThat(inner.get("summary")).isEqualTo("Ran failing tests, Fixed NPE in UserService");
        // SDK snake_case → Java camelCase（对齐 CC snake_case → camelCase 规则）
        assertThat(inner.get("precedingToolUseIds")).isEqualTo(List.of("toolu_1", "toolu_2"));
        assertThat(inner.get("uuid")).isEqualTo("uuid-1");
    }

    @Test
    @DisplayName("tool_use_summary 缺字段优雅降级：null summary 不抛 NPE，precedingToolUseIds 空列表")
    void toolUseSummary_missingFields_graceful() {
        SDKMessageAdapter.SDKMessage msg = sdkToolUseSummary(null, null);

        SDKMessageAdapter.ConvertedMessage cm =
            adapter.convertSDKMessage(msg, new SDKMessageAdapter.ConvertOptions(false, false));

        assertThat(cm.type()).isEqualTo("message");
        Map<?, ?> inner = (Map<?, ?>) ((SDKMessageAdapter.ConvertedMessageWrapper) cm).message();
        assertThat(inner.get("type")).isEqualTo("tool_use_summary");
        assertThat(inner.get("summary")).isNull();
        assertThat(inner.get("precedingToolUseIds")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("解析结果可注入上下文：经 AttachmentMessageDto.toolUseSummary → AgentState.attachments")
    void parsedSummary_injectsIntoAgentState() {
        SDKMessageAdapter.SDKMessage msg =
            sdkToolUseSummary("Fixed NPE in UserService", List.of("toolu_9"));
        SDKMessageAdapter.ConvertedMessage cm =
            adapter.convertSDKMessage(msg, new SDKMessageAdapter.ConvertOptions(false, false));
        Map<?, ?> inner = (Map<?, ?>) ((SDKMessageAdapter.ConvertedMessageWrapper) cm).message();

        // 注入点：ConvertedMessage → AttachmentMessageDto(type='tool_use_summary') → AgentState.appendAttachment
        AttachmentMessageDto att = AttachmentMessageDto.toolUseSummary(
            (String) inner.get("summary"), (List<String>) inner.get("precedingToolUseIds"));
        AgentState state = new AgentState("test");
        state.appendAttachment(att);

        assertThat(state.attachments()).hasSize(1);
        AttachmentMessageDto stored = state.attachments().get(0);
        assertThat(stored.type()).isEqualTo("tool_use_summary");
        assertThat(stored.content()).isEqualTo("Fixed NPE in UserService");
        assertThat(stored.precedingToolUseIds()).isEqualTo(List.of("toolu_9"));
    }

    @Test
    @DisplayName("回归：auth_status / rate_limit_event 仍 ignored（CC 语义不变）")
    void authStatus_andRateLimitEvent_stillIgnored() {
        for (String type : List.of("auth_status", "rate_limit_event")) {
            SDKMessageAdapter.SDKMessage msg =
                SDKMessageAdapter.SDKMessage.of(type);
            SDKMessageAdapter.ConvertedMessage cm =
                adapter.convertSDKMessage(msg, new SDKMessageAdapter.ConvertOptions(false, false));
            assertThat(cm.type()).isEqualTo("ignored");
        }
    }
}
