package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.ToolResultStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-4 T5] processMCPResult 大结果截断/落盘 + MAX_MCP_OUTPUT_TOKENS 测试。
 *
 * <p>WHY（规则九）：旧实现大结果（> 阈值）原样进 LLM content（无截断无落盘），模型收到
 * 超大 JSON/文本浪费上下文且易截断。CC processMCPResult（client.ts:2720-2799）+ mcpValidation.ts
 * 要求超阈值 → 落盘 + 读取指令（含图片 → 截断）。
 */
@DisplayName("[impl-I-4 T5] processMCPResult 大结果截断落盘")
class McpOutputProcessorTest {

    private static final int MAX_TOKENS = 100;  // 测试用小阈值：100 token = 400 chars，判定阈值 50

    private McpResultTransformer.TransformedMCPResult textResult(String content) {
        return new McpResultTransformer.TransformedMCPResult(content, "toolResult", null, false);
    }

    // ═══════════ 1. mcpContentNeedsTruncation（mcpValidation.ts:151-178）═══════════

    @Test
    @DisplayName("mcpContentNeedsTruncation: 小结果（estimate <= maxTokens*0.5）→ false")
    void smallContent_noTruncation() {
        // 200 chars → estimate 50 = maxTokens*0.5 → 不截断
        String content = "a".repeat(200);
        assertThat(McpOutputProcessor.mcpContentNeedsTruncation(content, MAX_TOKENS)).isFalse();
    }

    @Test
    @DisplayName("mcpContentNeedsTruncation: 大结果（estimate > maxTokens）→ true")
    void largeContent_needsTruncation() {
        // 1000 chars → estimate 250 > 100 → 截断
        String content = "a".repeat(1000);
        assertThat(McpOutputProcessor.mcpContentNeedsTruncation(content, MAX_TOKENS)).isTrue();
    }

    @Test
    @DisplayName("mcpContentNeedsTruncation: 空 → false")
    void emptyContent_false() {
        assertThat(McpOutputProcessor.mcpContentNeedsTruncation("", MAX_TOKENS)).isFalse();
        assertThat(McpOutputProcessor.mcpContentNeedsTruncation(null, MAX_TOKENS)).isFalse();
    }

    // ═══════════ 2. truncateMcpContentIfNeeded（mcpValidation.ts:200-208）═══════════

    @Test
    @DisplayName("truncateMcpContentIfNeeded: 超阈值 → 前 maxChars + 截断提示")
    void truncate_keepsPrefixAppendsMessage() {
        String content = "a".repeat(1000);
        String result = McpOutputProcessor.truncateMcpContentIfNeeded(content, MAX_TOKENS);
        assertThat(result).startsWith("a".repeat(MAX_TOKENS * 4));
        assertThat(result).contains("[OUTPUT TRUNCATED - exceeded " + MAX_TOKENS + " token limit]");
    }

    // ═══════════ 3. processMCPResult 落盘（client.ts:2720-2799）══════════════

    @Test
    @DisplayName("processMCPResult: 未超阈值 → 原样返回")
    void process_small_unchanged() {
        String content = "a".repeat(200);
        String result = McpOutputProcessor.processMCPResult(textResult(content), "tool", "srv", null, MAX_TOKENS);
        assertThat(result).isEqualTo(content);
    }

    @Test
    @DisplayName("processMCPResult: 大结果 → 落盘 + 读取指令（含 Format: + 顺序读要求）")
    void process_large_persistedWithInstructions() throws Exception {
        Path dir = Files.createTempDirectory("mcp-output-test");
        String content = "a".repeat(1000);
        var ctx = new McpResultTransformer.TransformContext(dir, "sess1");
        String result = McpOutputProcessor.processMCPResult(textResult(content), "tool", "srv", ctx, MAX_TOKENS);
        assertThat(result).contains("输出已保存到");
        assertThat(result).contains("格式：纯文本");
        assertThat(result).contains("offset 和 limit");
        // 文件真实落盘（{dir}/sess1/tool-results/mcp-srv-tool-*.txt）
        assertThat(Files.walk(dir).anyMatch(p -> p.toString().endsWith(".txt"))).isTrue();
    }

    @Test
    @DisplayName("processMCPResult: 含图片 → 截断而非落盘（CC :2758-2765）")
    void process_containsImages_truncatedNotPersisted() throws Exception {
        Path dir = Files.createTempDirectory("mcp-output-image-test");
        String content = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"A\"}}";
        // 超阈值但含图片 → 截断
        String big = content + "a".repeat(1000);
        var transformed = new McpResultTransformer.TransformedMCPResult(big, "contentArray", null, true);
        var ctx = new McpResultTransformer.TransformContext(dir, "sess1");
        String result = McpOutputProcessor.processMCPResult(transformed, "tool", "srv", ctx, MAX_TOKENS);
        assertThat(result).contains("[OUTPUT TRUNCATED");
        // 不应落盘
        assertThat(Files.walk(dir).noneMatch(p -> p.toString().endsWith(".txt"))).isTrue();
    }

    @Test
    @DisplayName("processMCPResult: persist 失败（workspaceDir 是文件）→ 错误文本（CC :2781-2783）")
    void process_persistFailed_errorText() throws Exception {
        // 用「workspaceDir 为普通文件」制造 persist 失败（createDirectories → NotADirectoryException；
        // 跨平台可靠，Windows 上 setWritable(false) 不保证阻止写入）
        Path dir = Files.createTempDirectory("mcp-output-ro-test");
        Path fileAsDir = dir.resolve("not-a-dir");
        Files.writeString(fileAsDir, "I am a file");
        String content = "a".repeat(1000);
        var ctx = new McpResultTransformer.TransformContext(fileAsDir, "sess1");
        String result = McpOutputProcessor.processMCPResult(textResult(content), "tool", "srv", ctx, MAX_TOKENS);
        assertThat(result).contains("超过最大允许 token");
        assertThat(result).contains("输出保存到文件失败");
    }

    // ═══════════ 4. getLargeOutputInstructions（mcpOutputStorage.ts:39-59）═══════════

    @Test
    @DisplayName("getLargeOutputInstructions: 中文指令含 Format + 顺序读 + 完整性自述要求")
    void instructions_structure() {
        String instructions = McpOutputProcessor.getLargeOutputInstructions("/tmp/out.txt", 12345, "JSON，schema: {x: number}");
        assertThat(instructions).contains("输出已保存到 /tmp/out.txt");
        assertThat(instructions).contains("格式：JSON，schema: {x: number}");
        assertThat(instructions).contains("直到 100% 的内容已被读取");
        assertThat(instructions).contains("您必须明确描述您已读取内容的哪些部分");
    }

    // ═══════════ 5. getFormatDescription（mcpOutputStorage.ts:16-28）═══════════

    @Test
    @DisplayName("getFormatDescription: 三类型映射")
    void formatDescription_cases() {
        assertThat(McpOutputProcessor.getFormatDescription("toolResult", null)).isEqualTo("纯文本");
        assertThat(McpOutputProcessor.getFormatDescription("structuredContent", "{a: string}"))
            .isEqualTo("JSON，schema: {a: string}");
        assertThat(McpOutputProcessor.getFormatDescription("contentArray", null)).isEqualTo("JSON 数组");
    }
}
