package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code read_clipboard} / {@code write_clipboard} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：这两个授权位（{@code clipboardRead} / {@code clipboardWrite}）
 * 在本批<b>首次被消费</b>（此前无处消费 ⇒ 授权位形同虚设）。
 * <ul>
 *   <li><b>未授权 ⇒ 硬拒绝且不碰剪贴板</b>：CCB {@code toolCalls.ts:3553-3557} / {@code 3586-3590}
 *       ⇒ 断言「未授权时 readCount/writeCount 为 0」（不是读了再报错）；</li>
 *   <li><b>已授权 ⇒ 正常读写</b>：返回形状 {@code {"text": ...}}（CCB {@code okJson({ text })}）。</li>
 *   <li><b>读是纯读</b>：{@code isReadOnly} = true（不进门控）；<b>写改系统状态</b> ⇒ false。</li>
 * </ul>
 */
class ComputerUseClipboardToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ComputerUseGrantStore grantStore(boolean read, boolean write) {
        return ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "full")),
                        new ComputerUseGrantFlags(read, write, false)).toJson());
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:873-875 / 884-886")
    void namesAndDescriptionsAreVerbatim() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        ComputerUseReadClipboardTool read = new ComputerUseReadClipboardTool(clipboard, null);
        ComputerUseWriteClipboardTool write = new ComputerUseWriteClipboardTool(clipboard, null);

        assertThat(read.name()).isEqualTo("mcp__computer-use__read_clipboard");
        assertThat(read.description()).isEqualTo(
                "Read the current clipboard contents as text. Requires the `clipboardRead` grant.");
        assertThat(write.name()).isEqualTo("mcp__computer-use__write_clipboard");
        assertThat(write.description()).isEqualTo(
                "Write text to the clipboard. Requires the `clipboardWrite` grant.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:876-879 / 887-892")
    void schemasAreVerbatim() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        JsonNode readSchema = new ComputerUseReadClipboardTool(clipboard, null).inputSchema();
        assertThat(readSchema.path("type").asText()).isEqualTo("object");
        assertThat(readSchema.path("properties").isEmpty()).isTrue();
        assertThat(readSchema.path("required").isEmpty()).isTrue();

        JsonNode writeSchema = new ComputerUseWriteClipboardTool(clipboard, null).inputSchema();
        assertThat(writeSchema.path("type").asText()).isEqualTo("object");
        assertThat(writeSchema.path("properties").path("text").path("type").asText()).isEqualTo("string");
        assertThat(writeSchema.path("required").get(0).asText()).isEqualTo("text");
        assertThat(writeSchema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("只读 / 改状态：read 是只读工具，write 不是")
    void readOnlySemantics() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        assertThat(new ComputerUseReadClipboardTool(clipboard, null)
                .isReadOnly(JSON.createObjectNode())).isTrue();
        assertThat(new ComputerUseWriteClipboardTool(clipboard, null)
                .isReadOnly(JSON.createObjectNode())).isFalse();
    }

    @Test
    @DisplayName("未授权 clipboardRead ⇒ 拒绝且不读剪贴板")
    void readWithoutGrantIsRejected() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        ComputerUseReadClipboardTool tool = new ComputerUseReadClipboardTool(clipboard, grantStore(false, false));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Clipboard read is not granted. Request `clipboardRead` via request_access.");
        assertThat(clipboard.readCount).as("⛔ 未授权 ⇒ 不得碰剪贴板").isZero();
    }

    @Test
    @DisplayName("已授权 clipboardRead ⇒ 读到文本（返回形状 {text}）")
    void readWithGrantReturnsText() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        clipboard.content = "hello";
        ComputerUseReadClipboardTool tool = new ComputerUseReadClipboardTool(clipboard, grantStore(true, false));

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("text").asText()).isEqualTo("hello");
        assertThat(clipboard.readCount).isEqualTo(1);
    }

    @Test
    @DisplayName("未授权 clipboardWrite ⇒ 拒绝且不写剪贴板")
    void writeWithoutGrantIsRejected() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        ComputerUseWriteClipboardTool tool = new ComputerUseWriteClipboardTool(clipboard, grantStore(true, false));

        ObjectNode input = JSON.createObjectNode();
        input.put("text", "secret");
        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Clipboard write is not granted. Request `clipboardWrite` via request_access.");
        assertThat(clipboard.writeCount).as("⛔ 未授权 ⇒ 不得写剪贴板").isZero();
        assertThat(clipboard.content).isEmpty();
    }

    @Test
    @DisplayName("已授权 clipboardWrite ⇒ 写入成功（成功文案逐字）")
    void writeWithGrantWrites() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        ComputerUseWriteClipboardTool tool = new ComputerUseWriteClipboardTool(clipboard, grantStore(false, true));

        ObjectNode input = JSON.createObjectNode();
        input.put("text", "hello");
        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clipboard written.");
        assertThat(clipboard.content).isEqualTo("hello");
    }

    @Test
    @DisplayName("text 非字符串 ⇒ 报错（CCB toolCalls.ts:3592-3593 逐字文案）")
    void nonStringTextIsRejected() {
        ComputerUsePhase2aFakes.FakeClipboard clipboard = new ComputerUsePhase2aFakes.FakeClipboard();
        ComputerUseWriteClipboardTool tool = new ComputerUseWriteClipboardTool(clipboard, grantStore(false, true));

        ObjectNode input = JSON.createObjectNode();
        input.put("text", 42);
        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).isEqualTo("\"text\" must be a string.");
        assertThat(clipboard.writeCount).isZero();
    }
}
