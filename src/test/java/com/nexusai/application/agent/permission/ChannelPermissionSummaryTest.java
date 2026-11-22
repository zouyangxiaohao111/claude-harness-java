package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.application.agent.tool.impl.WebFetchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [B2] 渠道推送正文 = 本次调用摘要，不是工具注册期的长说明书。
 *
 * <p>WHY（真机症状）：微信收到的是
 * 「Read the contents of a file. Path is resolved relative to the workspace root…」——
 * 一段给模型看的说明书，既长又没告诉用户**这一次**读的是哪个文件。
 * 口径 = 本次调用的 {@code tool.description(input)}（CC tool.description(input, ctx)），
 * 细节留给 {@code input_preview}。
 */
@DisplayName("[B2] 权限推送摘要：本次调用 · 短句 · 不含说明书")
class ChannelPermissionSummaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ_MANUAL =
            "Path is resolved relative to the workspace root";

    private static ObjectNode input(String key, String value) {
        ObjectNode node = JSON.createObjectNode();
        node.put(key, value);
        return node;
    }

    @Test
    @DisplayName("Read ⇒ 固定标题句（CC 口径），不含路径/说明书")
    void readSummaryIsCallSpecific() {
        ReadFileTool tool = new ReadFileTool(mock(PathGuard.class));
        String desc = tool.description(input("file_path", "C:/Users/WIN/.nexusai/settings.json"));

        assertThat(desc).isEqualTo("Read a file from the local filesystem.");
        assertThat(desc).doesNotContain(READ_MANUAL).doesNotContain("\n");
        assertThat(desc).doesNotContain("settings.json");
    }

    @Test
    @DisplayName("Edit ⇒ 固定标题句（CC 口径）")
    void editSummaryIsCallSpecific() {
        EditFileTool tool = new EditFileTool(mock(PathGuard.class));
        String desc = tool.description(input("file_path", "D:/code/ai_project/nexusai/README.md"));

        assertThat(desc).isEqualTo("A tool for editing files");
        assertThat(desc).doesNotContain("old_string").doesNotContain("\n");
    }

    @Test
    @DisplayName("Write ⇒ 固定标题句（CC 口径）")
    void writeSummaryIsCallSpecific() {
        WriteFileTool tool = new WriteFileTool(mock(PathGuard.class));
        String desc = tool.description(input("file_path", "D:/tmp/out.txt"));

        assertThat(desc).isEqualTo("Write a file to the local filesystem.");
        assertThat(desc).doesNotContain("Parent directories are auto-created");
    }

    @Test
    @DisplayName("WebFetch ⇒ 带 host 的标题句；WebSearch ⇒ 带 query 的标题句")
    void webTitlesCarryOnlyTarget() {
        assertThat(new WebFetchTool().description(input("url", "https://example.com/a?b=1")))
                .isEqualTo("Claude wants to fetch content from example.com");
        assertThat(new WebSearchTool().description(input("query", "maven 打包")))
                .isEqualTo("Claude wants to search the web for: maven 打包");
    }

    @Test
    @DisplayName("Bash ⇒ 仍是短句（有 description 用 description，否则 Run shell command）")
    void bashSummaryStaysShort() {
        BashTool tool = new BashTool();

        assertThat(tool.description(input("command", "git status"))).isEqualTo("Run shell command");
        ObjectNode withDesc = input("command", "git status");
        withDesc.put("description", "查看工作区状态");
        assertThat(tool.description(withDesc)).isEqualTo("查看工作区状态");
    }

    @Test
    @DisplayName("推送正文：标题句单行；路径细节只出现在 input_preview")
    void pushedTextUsesShortTitleLine() {
        ReadFileTool read = new ReadFileTool(mock(PathGuard.class));
        String desc = read.description(input("file_path", "C:/Users/WIN/.nexusai/settings.json"));
        ObjectNode toolInput = input("file_path", "C:/Users/WIN/.nexusai/settings.json");

        String text = ChannelPermissionPayload.toText("abcde", "Read", desc, toolInput);

        assertThat(text).contains("[权限确认] Read");
        assertThat(text).contains("Read a file from the local filesystem.");
        assertThat(text)
            .as("不能把工具说明书当正文发出去")
            .doesNotContain(READ_MANUAL);
        // 分工：路径细节在 input_preview 里（不在标题句里）；长路径按脱敏规则保留末两段
        assertThat(text).contains("输入预览:");
        assertThat(text).contains("settings.json");
    }

    @Test
    @DisplayName("description 超 3500 码点 ⇒ 头 2000 + 尾 1500（input_preview 另一套预算）")
    void descriptionHeadTailTruncation() {
        String head = "H".repeat(2000);
        String tail = "T".repeat(1500);
        String middle = "M".repeat(1000);
        String desc = head + middle + tail;

        String truncated = ChannelPermissionPayload.truncateDescription(desc);

        assertThat(truncated).startsWith(head).endsWith(tail).contains("…");
        assertThat(truncated).doesNotContain(middle);
        assertThat(truncated.codePointCount(0, truncated.length())).isLessThanOrEqualTo(3501);
    }
}
