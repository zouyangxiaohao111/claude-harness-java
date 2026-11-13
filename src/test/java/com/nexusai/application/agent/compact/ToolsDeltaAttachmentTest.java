package com.nexusai.application.agent.compact;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tools_delta} · 全量内联路径的工具增减通知。
 *
 * <p>覆盖两条「没有 ToolSearch」的路：非 anthropic provider、anthropic 但模型不支持
 * {@code tool_reference}（haiku 等）。这两条路上工具是全量内联的，中途开关工具时模型
 * 只能靠「tools 数组变长」自己察觉 —— 本附件补上显式通知。
 */
class ToolsDeltaAttachmentTest {

    private static Tool tool(String name) {
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
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static List<Tool> pool(String... names) {
        List<Tool> out = new ArrayList<>();
        for (String n : names) {
            out.add(tool(n));
        }
        return out;
    }

    @Test
    @DisplayName("首轮写不可见基线：附件存在，但渲染给模型的内容是 null")
    void firstRoundWritesInvisibleBaseline() {
        ChatMessageDto baseline = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash"), List.of());

        assertThat(baseline).isNotNull();
        assertThat(baseline.subtype()).isEqualTo(PostCompactAttachmentRestorer.DELTA_TYPE_TOOLS);
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta(baseline.content()))
            .as("首轮 models 本来就能看到完整 tools 数组，再列一遍名字是噪声")
            .isNull();
        assertThat(JSON.parseObject(baseline.content()).getBooleanValue("baseline")).isTrue();
    }

    @Test
    @DisplayName("中途打开工具 → 产出 added 通知，渲染含 now available")
    void newlyEnabledToolIsAnnounced() {
        ChatMessageDto baseline = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash"), List.of());
        List<ChatMessageDto> history = List.of(baseline);

        ChatMessageDto delta = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash", "mcp__computer-use__activate_window"), history);

        assertThat(delta).isNotNull();
        JSONObject p = JSON.parseObject(delta.content());
        assertThat(p.getJSONArray("addedNames")).containsExactly("mcp__computer-use__activate_window");
        assertThat(p.getJSONArray("removedNames")).isEmpty();

        String rendered = PostCompactAttachmentRestorer.renderToolsDelta(delta.content());
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("The following tools are now available");
        assertThat(rendered).contains("mcp__computer-use__activate_window");
        assertThat(rendered).startsWith("<system-reminder>").endsWith("</system-reminder>");
    }

    @Test
    @DisplayName("中途关闭工具 → 产出 removed 通知（模型必须知道它不能调了）")
    void disabledToolIsAnnouncedAsRemoved() {
        ChatMessageDto baseline = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash", "Write"), List.of());
        List<ChatMessageDto> history = List.of(baseline);

        ChatMessageDto delta = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash"), history);

        assertThat(delta).isNotNull();
        JSONObject p = JSON.parseObject(delta.content());
        assertThat(p.getJSONArray("removedNames")).containsExactly("Write");
        assertThat(p.getJSONArray("addedNames")).isEmpty();

        String rendered = PostCompactAttachmentRestorer.renderToolsDelta(delta.content());
        assertThat(rendered).contains("no longer available").contains("Write");
    }

    @Test
    @DisplayName("工具集无变化 → null（不产生噪声通知）")
    void unchangedProducesNothing() {
        ChatMessageDto baseline = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash"), List.of());

        assertThat(PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash"), List.of(baseline))).isNull();
    }

    @Test
    @DisplayName("基线之后又加又减 → 同一条通知里两段都有")
    void addedAndRemovedTogether() {
        ChatMessageDto baseline = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash", "Write"), List.of());

        ChatMessageDto delta = PostCompactAttachmentRestorer.toolsDeltaAttachment(
            pool("Read", "Bash", "mcp__computer-use__screenshot"), List.of(baseline));

        String rendered = PostCompactAttachmentRestorer.renderToolsDelta(delta.content());
        assertThat(rendered).contains("now available").contains("mcp__computer-use__screenshot");
        assertThat(rendered).contains("no longer available").contains("Write");
    }

    @Test
    @DisplayName("空工具集 → null，不写基线也不误报")
    void emptyPoolIsNoop() {
        assertThat(PostCompactAttachmentRestorer.toolsDeltaAttachment(List.of(), List.of())).isNull();
        assertThat(PostCompactAttachmentRestorer.toolsDeltaAttachment(null, List.of())).isNull();
    }

    @Test
    @DisplayName("渲染：baseline / 空载荷 / 非法 JSON 一律 null，不抛")
    void renderIsFailSoft() {
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta(null)).isNull();
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta("")).isNull();
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta("not json")).isNull();
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta("{\"baseline\":true}")).isNull();
        assertThat(PostCompactAttachmentRestorer.renderToolsDelta("{\"addedNames\":[],\"removedNames\":[]}")).isNull();
    }
}
