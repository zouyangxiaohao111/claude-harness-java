package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [Wave2-I] mcp_tool 插值 / 结果文本提取（纯函数，不连 MCP）。 */
class ExecMcpToolHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void interpolateMap_resolvesDotPaths_recursively() throws Exception {
        JsonNode root = MAPPER.readTree("{\"tool_input\":{\"file_path\":\"/tmp/a\"},\"cwd\":\"/w\"}");
        Map<String, Object> input = Map.of(
            "path", "${tool_input.file_path}",
            "cwd", "at ${cwd}",
            "nested", List.of("${tool_input.file_path}", 7));
        Map<String, Object> out = ExecMcpToolHook.interpolateMap(input, root);
        assertThat(out.get("path")).isEqualTo("/tmp/a");
        assertThat(out.get("cwd")).isEqualTo("at /w");
        assertThat(out.get("nested")).isEqualTo(List.of("/tmp/a", 7));
    }

    @Test
    void interpolateString_missingPathBecomesEmpty() throws Exception {
        JsonNode root = MAPPER.readTree("{}");
        assertThat(ExecMcpToolHook.interpolateString("x-${tool_input.nope}-y", root))
            .isEqualTo("x--y");
    }

    @Test
    void interpolateString_withoutPlaceholder_isUnchanged() {
        assertThat(ExecMcpToolHook.interpolateString("plain", MAPPER.createObjectNode()))
            .isEqualTo("plain");
    }

    @Test
    void extractText_joinsTextContentBlocks() throws Exception {
        JsonNode result = MAPPER.readTree(
            "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"},"
                + "{\"type\":\"text\",\"text\":\"tail\"},{\"type\":\"image\",\"data\":\"x\"}]}");
        assertThat(ExecMcpToolHook.extractText(result)).isEqualTo("{\"a\":1}\ntail");
    }

    @Test
    void extractText_fallsBackToWholeJson() throws Exception {
        JsonNode result = MAPPER.readTree("{\"content\":[{\"type\":\"image\",\"data\":\"x\"}]}");
        assertThat(ExecMcpToolHook.extractText(result)).contains("\"image\"");
    }

    @Test
    void display_usesServerSlashTool() {
        McpToolHook hook = new McpToolHook("fs", "read", Map.of(), null, 5, null, null);
        assertThat(ExecMcpToolHook.display(hook)).isEqualTo("fs/read");
    }
}
