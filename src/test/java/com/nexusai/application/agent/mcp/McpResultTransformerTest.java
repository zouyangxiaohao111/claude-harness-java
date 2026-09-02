package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [impl-I-4 T4] transformMCPResult / inferCompactSchema / transformResultContent 测试。
 *
 * <p>WHY（规则九）：旧实现把 structuredContent 直接 JSON 原文进 LLM（无 schema 标记）、content 数组
 * 非 audio 块原样透传（无 image/resource_link 分支）。CC transformMCPResult（client.ts:2662-2706）
 * 三类型正确分类 + schema 标记，LLM 才能理解结构化结果格式。
 */
@DisplayName("[impl-I-4 T4] transformMCPResult + inferCompactSchema + transformResultContent")
class McpResultTransformerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpResultTransformer.TransformContext CTX = null;

    // ═══════════ 1. transformMCPResult 三类型（CC client.ts:2662-2706）═══════════

    @Test
    @DisplayName("toolResult → {content:String(...), type:'toolResult'}（CC :2668-2672）")
    void toolResult_branch() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("toolResult", "plain text output");
        McpResultTransformer.TransformedMCPResult t =
            McpResultTransformer.transformMCPResult(result, "search", "slack", CTX);
        assertThat(t.type()).isEqualTo("toolResult");
        assertThat(t.content()).isEqualTo("plain text output");
        assertThat(t.schema()).isNull();
    }

    @Test
    @DisplayName("structuredContent → {content:json, type:'structuredContent', schema}（CC :2674-2680）")
    void structuredContent_branch() {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode sc = result.putObject("structuredContent");
        sc.put("title", "Hello");
        sc.putArray("tags").add("a").add("b");
        McpResultTransformer.TransformedMCPResult t =
            McpResultTransformer.transformMCPResult(result, "search", "slack", CTX);
        assertThat(t.type()).isEqualTo("structuredContent");
        assertThat(t.content()).contains("\"title\"");
        assertThat(t.schema()).contains("title: string");
        assertThat(t.schema()).contains("tags: [string]");
    }

    @Test
    @DisplayName("content 数组 → contentArray + transformResultContent 平铺 + schema（CC :2682-2692）")
    void contentArray_branch_flatTransformed() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", "hello");
        ObjectNode resourceLink = content.addObject();
        resourceLink.put("type", "resource_link");
        resourceLink.put("name", "doc");
        resourceLink.put("uri", "file:///a/b.txt");
        McpResultTransformer.TransformedMCPResult t =
            McpResultTransformer.transformMCPResult(result, "read", "server", CTX);
        assertThat(t.type()).isEqualTo("contentArray");
        assertThat(t.content()).contains("[Resource link: doc] file:///a/b.txt");
        assertThat(t.schema()).startsWith("[");
    }

    @Test
    @DisplayName("格式不符 → 抛「MCP tool unexpected response format」（CC :2694-2706）")
    void unexpectedFormat_throws() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("foo", "bar");
        assertThatThrownBy(() -> McpResultTransformer.transformMCPResult(result, "tool", "srv", CTX))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unexpected response format")
            .hasMessageContaining("srv").hasMessageContaining("tool");
    }

    // ═══════════ 2. inferCompactSchema（CC client.ts:2644-2660）═══════════

    @Test
    @DisplayName("inferCompactSchema: null→'null' / 数组→[schema] / 对象前10键+', ...' / 标量→typeof")
    void inferCompactSchema_cases() {
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.nullNode(), 2)).isEqualTo("null");
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.createArrayNode(), 2)).isEqualTo("[]");
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.createArrayNode().add("a"), 2))
            .isEqualTo("[string]");
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.createObjectNode().put("k", 1), 2))
            .isEqualTo("{k: number}");
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.getNodeFactory().textNode("x"), 2))
            .isEqualTo("string");
        assertThat(McpResultTransformer.inferCompactSchema(MAPPER.getNodeFactory().booleanNode(true), 2))
            .isEqualTo("boolean");
        // 对象 >10 键 → 前 10 + ', ...'
        ObjectNode big = MAPPER.createObjectNode();
        for (int i = 0; i < 12; i++) {
            big.put("k" + i, i);
        }
        String schema = McpResultTransformer.inferCompactSchema(big, 2);
        assertThat(schema).endsWith(", ...}");
        assertThat(schema).contains("k0").contains("k9");
        assertThat(schema).doesNotContain("k10");
    }

    @Test
    @DisplayName("inferCompactSchema: depth 耗尽 → '{...}'（CC :2651）")
    void inferCompactSchema_depthExhausted() {
        ObjectNode nested = MAPPER.createObjectNode();
        nested.putObject("a").putObject("b").put("c", 1);
        String schema = McpResultTransformer.inferCompactSchema(nested, 1);
        // depth=1 → a: {...}（b 层 depth=0 → '{...}'）
        assertThat(schema).contains("a: {...}");
    }

    // ═══════════ 3. transformResultContent 各分支（CC client.ts:2478-2591）═══════════

    @Test
    @DisplayName("transformResultContent: text → text 块（CC :2481-2485）")
    void content_text() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text").put("text", "hi");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", CTX);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("transformResultContent: resource text → 前缀拼接（CC :2513-2518）")
    void content_resourceText() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "resource");
        ObjectNode res = block.putObject("resource");
        res.put("uri", "mock://x").put("text", "content");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", CTX);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText()).isEqualTo("[Resource from srv at mock://x] content");
    }

    @Test
    @DisplayName("transformResultContent: resource_link → 文本（CC :2554-2565）")
    void content_resourceLink() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "resource_link").put("name", "doc").put("uri", "file:///a").put("description", "desc");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", CTX);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText()).isEqualTo("[Resource link: doc] file:///a (desc)");
    }

    @Test
    @DisplayName("transformResultContent: image → base64 直通块（受控偏差，Java 无 resize 等价）")
    void content_image() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "image").put("data", "aGVsbG8=").put("mimeType", "image/png");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", CTX);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("image");
        assertThat(blocks.get(0).path("source").path("data").asText()).isEqualTo("aGVsbG8=");
        assertThat(blocks.get(0).path("source").path("media_type").asText()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("transformResultContent: 未知类型 → 空（CC :2567-2569 default []）")
    void content_unknownType_empty() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "weird");
        assertThat(McpResultTransformer.transformResultContent(block, "srv", CTX)).isEmpty();
    }

    @Test
    @DisplayName("transformResultContent: audio 无上下文 → 失败提示不悬挂")
    void content_audioNoContext_failureHint() {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "audio").put("data", "aGVsbG8=").put("mimeType", "audio/wav");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", null);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText())
            .contains("could not be saved to disk");
    }

    @Test
    @DisplayName("transformResultContent: audio 有上下文 → 落盘文本提示（成功模板）")
    void content_audioWithContext_persisted() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("mcp-transformer-test");
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "audio").put("data", "aGVsbG8=").put("mimeType", "audio/wav");
        var ctx = new McpResultTransformer.TransformContext(dir, "sess1");
        var blocks = McpResultTransformer.transformResultContent(block, "srv", ctx);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText()).contains("saved to");
        // 验证文件真实落盘（{dir}/{sessionId}/tool-results/mcp-srv-blob-*.wav）
        assertThat(java.nio.file.Files.walk(dir).anyMatch(p -> p.toString().endsWith(".wav"))).isTrue();
    }
}
