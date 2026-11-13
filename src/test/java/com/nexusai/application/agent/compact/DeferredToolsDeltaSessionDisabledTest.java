package com.nexusai.application.agent.compact;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code deferred_tools_delta} 的「工具增减」宣布 · 会话禁用必须参与 diff。
 *
 * <p><b>WHY</b>：这个机制的职责就是告诉模型「哪些 deferred 工具现在可以搜、哪些已经搜不到了」。
 * 如果 diff 用<b>原始池</b>，被会话禁用的工具仍算「在池里」⇒
 * <ul>
 *   <li>{@code removed} 永不成立 ⇒ 关闭工具后模型<b>不会</b>收到「别去搜」的通知，继续对已下架
 *       工具发 {@code tool_reference} ⇒ 下一轮 400；</li>
 *   <li>打开工具时 {@code added} 也会多算/少算，模型对「现在能搜到什么」判断失准。</li>
 * </ul>
 *
 * <p>本测试用同一个「已 announce」基线，分别喂原始池与过滤池，钉住差异必须出现。
 */
class DeferredToolsDeltaSessionDisabledTest {

    private static final String MODEL = "claude-sonnet-4-5";
    private static final String MCP_TOOL = "mcp__testserver__do_thing";

    private SessionMapper mapper;
    private SessionRecord record;

    private static Tool tool(String name, boolean mcp) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "t " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return null;
            }

            @Override
            public boolean isMcp() {
                return mcp;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private List<Tool> pool() {
        return List.of(
            tool(ToolNameConstants.TOOL_SEARCH_TOOL_NAME, false),
            tool(MCP_TOOL, true));
    }

    @BeforeEach
    void setUp() {
        SessionToolCategory.reset();
        SessionToolDisableConfig.reset();

        mapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId("sess-1");
        when(mapper.selectOneById(any())).thenReturn(record);
        SessionToolDisableConfig.setSessionMapper(mapper);
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionToolCategory.reset();
    }

    private static JSONObject payload(ChatMessageDto msg) {
        return JSON.parseObject(msg.content());
    }

    @Test
    @DisplayName("首轮：MCP 工具以 added 被宣布（机制本身在工作）")
    void firstRoundAnnouncesMcpTool() {
        record.setDisabledTools("[]"); // 显式全开，不引入禁用干扰

        ChatMessageDto dtd = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            SessionToolDisableConfig.filterDisabled(pool(), "sess-1"), MODEL, "anthropic", List.of());

        assertThat(dtd).as("gate 未开或 ToolSearch 不在池 ⇒ 机制不生效").isNotNull();
        assertThat(payload(dtd).getJSONArray("addedNames")).contains(MCP_TOOL);
    }

    @Test
    @DisplayName("关闭工具后必须宣布 removed（否则模型继续搜已下架工具）")
    void disablingToolProducesRemovedEntry() {
        record.setDisabledTools("[]");
        List<Tool> all = pool();
        ChatMessageDto announced = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            SessionToolDisableConfig.filterDisabled(all, "sess-1"), MODEL, "anthropic", List.of());
        assertThat(announced).isNotNull();
        List<ChatMessageDto> history = List.of(announced);

        // ① 原始池（修复前的行为）：工具仍在池里 ⇒ 无变化 ⇒ null
        ChatMessageDto unchanged = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            all, MODEL, "anthropic", history);
        assertThat(unchanged)
            .as("原始池下 removed 判定永不成立 —— 这正是修复前模型不知道工具已下架的根因")
            .isNull();

        // ② 过滤池（修复后）：工具不在池里 ⇒ removed 命中
        record.setDisabledTools("[\"" + MCP_TOOL + "\"]");
        ChatMessageDto afterDisable = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            SessionToolDisableConfig.filterDisabled(all, "sess-1"), MODEL, "anthropic", history);

        assertThat(afterDisable).isNotNull();
        assertThat(payload(afterDisable).getJSONArray("removedNames"))
            .as("必须告诉模型：这个工具已经搜不到了，别再 tool_reference 它")
            .contains(MCP_TOOL);
    }

    @Test
    @DisplayName("关闭时不把「仍在可用」的工具误报成 removed")
    void stillAvailableToolIsNotRemoved() {
        record.setDisabledTools("[]");
        List<Tool> all = pool();
        ChatMessageDto announced = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            SessionToolDisableConfig.filterDisabled(all, "sess-1"), MODEL, "anthropic", List.of());
        assertThat(announced).isNotNull();

        // 禁用一个不存在的（不影响的）名字 → 池里两个工具都在 ⇒ 无变化
        record.setDisabledTools("[\"mcp__testserver__other\"]");
        ChatMessageDto dtd = PostCompactAttachmentRestorer.deferredToolsDeltaAttachment(
            SessionToolDisableConfig.filterDisabled(all, "sess-1"), MODEL, "anthropic",
            List.of(announced));

        assertThat(dtd).isNull();
    }
}
