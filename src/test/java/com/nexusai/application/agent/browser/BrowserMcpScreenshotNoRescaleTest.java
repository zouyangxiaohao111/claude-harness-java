package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nexusai.application.agent.computeruse.ComputerUseApiResize;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览器截图<b>不被后端缩放</b>的护栏单测（Phase 1c · item 3 的「停下报告」落地）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：任务要求「浏览器截图缩放到长边 ≤1568，
 * <b>但若浏览器工具依赖原始像素坐标 ⇒ 停下报告，不要盲改</b>」。本仓核对后的结论是
 * <b>浏览器工具确实依赖原始像素坐标</b>：
 * <ul>
 *   <li>工具 schema 明确告诉模型 {@code coordinate} 是
 *       “The x (pixels from the left edge) and y (pixels from the top edge) coordinates”
 *       （{@code BrowserToolRegistry} 的 {@code computer} 工具）；</li>
 *   <li>扩展把这些数字<b>原样</b>喂给 {@code document.elementFromPoint(x, y)}
 *       （{@code front/extension/content.js:417}）—— <b>没有任何「图上坐标 → 页面坐标」的换算层</b>，
 *       也没有缩放因子随截图下发给扩展。</li>
 * </ul>
 *
 * <p>⇒ 若后端把截图缩到 1568，模型读到的坐标就变成了「缩略图空间」的坐标，
 * 而扩展仍按<b>视口 CSS 像素</b>解释 ⇒ <b>点击静默落错位置</b>。
 * 修复它需要改 {@code front/extension}（本批禁改）或新增一套「每张截图的缩放因子」会话态
 * （含 zoom 局部截图的独立因子）—— 都不是本批范围。
 *
 * <p>⇒ 本测试就是那道护栏：<b>断言浏览器截图原样进模型</b>（长边可以 >1568）。
 * 将来若有人给浏览器路径加缩放，本测试会红 ⇒ 必须同时处理坐标契约。
 */
class BrowserMcpScreenshotNoRescaleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** 造一张指定尺寸的 PNG（纯色 ⇒ 压缩后很小）。 */
    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y += 64) {
            for (int x = 0; x < width; x += 64) {
                image.setRGB(x, y, 0x336699);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BrowserMcpTool toolFor(String toolName, BrowserChannel channel) {
        BrowserToolSpec spec = BrowserToolRegistry.SPECS.stream()
                .filter(s -> s.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("spec 不存在: " + toolName));
        return new BrowserMcpTool(spec, channel);
    }

    @Test
    @DisplayName("浏览器截图原样进模型（不被后端缩放）—— 坐标契约的前置条件")
    void browserScreenshotIsNotRescaled() throws Exception {
        byte[] original = png(1920, 1080);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(original);
        String response = JSON.createObjectNode()
                .put("action", "screenshot")
                .put("dataUrl", dataUrl)
                .toString();

        BrowserMcpTool tool = toolFor("computer", new FakeChannel(response));
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        @SuppressWarnings("unchecked")
        List<ContentBlockParam> blocks = (List<ContentBlockParam>) block.content();
        ContentBlockParam.ImageBlockParam image = null;
        for (ContentBlockParam b : blocks) {
            if (b instanceof ContentBlockParam.ImageBlockParam img) {
                image = img;
            }
        }
        assertThat(image).as("必须有一个 image 块").isNotNull();

        byte[] delivered = Base64.getDecoder().decode(image.source().data());
        assertThat(delivered)
                .as("送进模型的字节必须与扩展给的完全一致（没有任何重编码/缩放）")
                .isEqualTo(original);

        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(delivered));
        assertThat(decoded.getWidth()).isEqualTo(1920);
        assertThat(decoded.getHeight()).isEqualTo(1080);
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight()))
                .as("长边确实 >1568 ⇒ 证明后端【没有】缩放（而不是恰好不需要缩）")
                .isGreaterThan(1568);
    }

    @Test
    @DisplayName("若真按 ComputerUseApiResize 缩放，尺寸会变 ⇒ 护栏不是空转（坐标空间会被改）")
    void rescaleWouldChangeTheCoordinateSpace() {
        int[] target = ComputerUseApiResize.targetImageSize(1920, 1080);

        assertThat(target).containsExactly(1456, 819);
        assertThat(target[0])
                .as("缩放比例 ≠ 1 ⇒ 模型读到的坐标与扩展解释的坐标不再同一空间")
                .isNotEqualTo(1920);
    }

    @Test
    @DisplayName("浏览器 computer 工具的坐标契约仍写着「原始像素」（缩放前必须保持）")
    void browserCoordinateContractStaysRawPixels() {
        BrowserMcpTool tool = toolFor("computer", new FakeChannel("{}"));

        JsonNode coordinate = tool.inputSchema().path("properties").path("coordinate");
        String description = coordinate.path("description").asText();

        assertThat(description).contains("pixels from the left edge");
        assertThat(description).contains("pixels from the top edge");
        assertThat(coordinate.path("minItems").asInt()).isEqualTo(2);
        assertThat(coordinate.path("maxItems").asInt()).isEqualTo(2);
    }
}
