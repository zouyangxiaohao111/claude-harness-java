package com.nexusai.application.agent.browser;

import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 1b · C] 浏览器工具截图<b>真正进模型</b>的单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：改前 {@code BrowserMcpTool.persistScreenshotIfPresent}
 * 把截图降级成「已保存到本地路径」的<b>纯文本指针</b>（note 明写「若需模型看该图请走视觉/读文件
 * 通道读取，不要用 base64」）⇒ <b>模型根本看不到画面</b>，浏览器工具的
 * 「截图 → 定位元素 → 点击」这一环是断的。本测试因此锁：
 * <ol>
 *   <li>扩展返回 {@code dataUrl} ⇒ tool_result <b>含 image content block</b>
 *       （base64 可解码回原字节 + PNG magic bytes {@code 89 50 4E 47}）；</li>
 *   <li><b>纯文本结果形状不变</b>（无截图 ⇒ data 仍是原始字符串，content 仍是纯字符串）；</li>
 *   <li><b>既有行为不破坏</b>：扩展未连接 ⇒ fail loud 文案不变；
 *       读类工具自决 Allow / 写类工具 Passthrough 不变。</li>
 * </ol>
 */
class BrowserMcpToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 1x1 PNG 的真实字节（含 PNG magic 89 50 4E 47）。 */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** 固定返回指定文本的假通道。 */
    private static final class FakeChannel implements BrowserChannel {
        private final String response;

        FakeChannel(String response) {
            this.response = response;
        }

        @Override
        public String send(String sessionId, String tool, Map<String, Object> args) {
            return response;
        }
    }

    /** 构造被测工具（用 registry 的真实 spec，避免自造 schema 漂移）。 */
    private static BrowserMcpTool toolFor(String toolName, BrowserChannel channel) {
        BrowserToolSpec spec = BrowserToolRegistry.SPECS.stream()
                .filter(s -> s.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("spec 不存在: " + toolName));
        return new BrowserMcpTool(spec, channel);
    }

    @Test
    @DisplayName("截图结果：tool_result 含 image content block（base64 可解码 + PNG magic bytes）")
    void screenshotResultContainsImageContentBlock() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
        String response = JSON.createObjectNode()
                .put("action", "screenshot")
                .put("dataUrl", dataUrl)
                .toString();

        BrowserMcpTool tool = toolFor("computer", new FakeChannel(response));
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        assertThat(block).isNotNull();
        assertThat(block.content())
                .as("截图结果必须是【块数组】（含 image 块），不能是纯文本指针")
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<ContentBlockParam> blocks = (List<ContentBlockParam>) block.content();
        ContentBlockParam.ImageBlockParam image = null;
        for (ContentBlockParam b : blocks) {
            if (b instanceof ContentBlockParam.ImageBlockParam img) {
                image = img;
            }
        }
        assertThat(image).as("必须有一个 image 块").isNotNull();
        assertThat(image.source().type()).isEqualTo("base64");
        assertThat(image.source().mediaType()).isEqualTo("image/png");

        byte[] decoded = Base64.getDecoder().decode(image.source().data());
        assertThat(decoded).isEqualTo(PNG_BYTES);
        // PNG magic bytes: 89 50 4E 47
        assertThat(decoded[0] & 0xFF).isEqualTo(0x89);
        assertThat(decoded[1] & 0xFF).isEqualTo(0x50);
        assertThat(decoded[2] & 0xFF).isEqualTo(0x4E);
        assertThat(decoded[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    @DisplayName("截图结果的文本摘要不再含 base64（纯噪音 + 巨 token），但保留落盘路径")
    void screenshotTextSummaryHasNoBase64() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
        String response = JSON.createObjectNode()
                .put("action", "screenshot")
                .put("dataUrl", dataUrl)
                .toString();

        BrowserMcpTool tool = toolFor("computer", new FakeChannel(response));
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        Object data = ((ToolResult<?>) result).data();
        assertThat(data).isNotNull();
        String text = data.toString();
        assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(PNG_BYTES));
        assertThat(text).contains("savedTo");
    }

    @Test
    @DisplayName("纯文本结果形状不变（无截图 ⇒ data 仍是原始字符串，content 仍是纯字符串）")
    void plainTextResultShapeUnchanged() {
        String response = JSON.createObjectNode()
                .put("action", "left_click")
                .put("ok", true)
                .toString();

        BrowserMcpTool tool = toolFor("computer", new FakeChannel(response));
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        assertThat(((ToolResult<?>) result).data())
                .as("无截图 ⇒ 仍是原始字符串（形状逐字不变）")
                .isInstanceOf(String.class)
                .isEqualTo(response);

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        assertThat(block.content())
                .as("无截图 ⇒ content 仍是纯字符串（不是块数组）")
                .isInstanceOf(String.class);
    }

    @Test
    @DisplayName("既有 fail-loud 路径不变：扩展未连接 ⇒ 返回「浏览器扩展未连接…」")
    void extensionNotConnectedStillFailsLoud() {
        BrowserMcpTool tool = toolFor("computer", null);
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        assertThat(((ToolResult<?>) result).data())
                .isEqualTo(BrowserMcpTool.EXTENSION_NOT_CONNECTED_MESSAGE);
    }

    @Test
    @DisplayName("既有权限语义不变：读类工具自决 Allow / 写类工具 Passthrough")
    void permissionSemanticsUnchanged() {
        BrowserMcpTool readTool = toolFor("read_page", new FakeChannel("{}"));
        assertThat(readTool.checkPermissions(JSON.createObjectNode(), null))
                .isInstanceOf(PermissionResult.Allow.class);

        BrowserMcpTool writeTool = toolFor("computer", new FakeChannel("{}"));
        assertThat(writeTool.checkPermissions(JSON.createObjectNode(), null))
                .isInstanceOf(PermissionResult.Passthrough.class);
    }

    @Test
    @DisplayName("既有只读/并发语义不变（computer 是写类 ⇒ 不可只读、不可并发）")
    void readOnlyAndConcurrencySemanticsUnchanged() {
        BrowserMcpTool tool = toolFor("computer", new FakeChannel("{}"));
        JsonNode input = JSON.createObjectNode();
        assertThat(tool.isReadOnly(input)).isFalse();
        assertThat(tool.isConcurrencySafe(input)).isFalse();

        BrowserMcpTool readTool = toolFor("read_page", new FakeChannel("{}"));
        assertThat(readTool.isReadOnly(JSON.createObjectNode())).isTrue();
        assertThat(readTool.isConcurrencySafe(JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("落盘仍发生（前端可继续按 savedTo 取图），且摘要 JSON 仍是合法 JSON")
    void screenshotStillPersistedToDisk() throws Exception {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
        String response = JSON.createObjectNode()
                .put("action", "screenshot")
                .put("dataUrl", dataUrl)
                .toString();

        BrowserMcpTool tool = toolFor("computer", new FakeChannel(response));
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        Object data = ((ToolResult<?>) result).data();
        String text = data.toString();
        int brace = text.indexOf('{');
        JsonNode summary = JSON.readTree(text.substring(brace));
        assertThat(summary.path("ok").asBoolean()).isTrue();
        assertThat(summary.path("bytes").asInt()).isEqualTo(PNG_BYTES.length);
        assertThat(summary.path("savedTo").asText()).contains("nexusai-browser-shots");
        // 摘要文本里不应再有「不要用 base64」这类旧指引（已不成立）
        assertThat(summary.path("note").asText()).doesNotContain("不要用 base64");
        assertThat(new String(PNG_BYTES, StandardCharsets.ISO_8859_1).length()).isPositive();
    }
}
