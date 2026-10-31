package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 截图工具「结果形态」单测 · computer-use Phase 0 的验收关键。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：本仓既有缺陷
 * {@code BrowserMcpTool.java:242-248} 把截图降级成「已保存到本地路径」的文本指针
 * （其 note 明写「若需模型看该图请走视觉/读文件通道读取，不要用 base64」）
 * ⇒ <b>模型根本看不到那张图</b>。computer-use 的截图必须以 <b>image content block</b> 送达，
 * 否则「截屏 → 模型看得见」这一环就是断的。本测试因此锁：
 * <ol>
 *   <li>工具名精确为 {@code mcp__computer-use__screenshot}（CCB {@code setup.ts:17-21} 硬约束）；</li>
 *   <li>tool_result 的 content 是 <b>image block 数组</b>，且 {@code source.type='base64'}、
 *       {@code media_type='image/jpeg'}；</li>
 *   <li>base64 可解码回原字节，且首 3 字节是 JPEG magic {@code FF D8 FF}；</li>
 *   <li>截图后<b>几何快照入 store</b>（点击换算要用「截图时刻」的几何）。</li>
 * </ol>
 */
class ComputerUseScreenshotToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 假截屏器：返回固定 JPEG 字节 + 固定几何（不碰真实桌面 ⇒ 纯单测）。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        private final ComputerUseCaptureResult result;

        FakeCapturer(ComputerUseCaptureResult result) {
            this.result = result;
        }

        @Override
        public ComputerUseCaptureResult capture() {
            return result;
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    private static byte[] sampleJpeg() throws Exception {
        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, 32, 24);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("工具名精确为 mcp__computer-use__screenshot（CCB setup.ts:17-21 硬约束）")
    void toolNameIsExact() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                new FakeCapturer(new ComputerUseCaptureResult(new byte[]{1, 2, 3}, "image/jpeg",
                        10, 10, 100, 100, 0, 0, 1.0d, 100, 100)), store);
        assertThat(tool.name()).isEqualTo("mcp__computer-use__screenshot");
    }

    @Test
    @DisplayName("结果形态：tool_result content 为 image block（base64 + image/jpeg），base64 可解码且是 JPEG")
    void resultIsImageBlockWithDecodableJpeg() throws Exception {
        byte[] jpeg = sampleJpeg();
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                new FakeCapturer(new ComputerUseCaptureResult(jpeg, "image/jpeg",
                        1456, 819, 1920, 1080, 0, 0, 1.0d, 1920, 1080)), store);

        ToolUseBlock call = new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode());
        var result = tool.execute(call);

        assertThat(result).isInstanceOf(ToolResult.class);
        Object data = ((ToolResult<?>) result).data();
        assertThat(data).isInstanceOf(ComputerUseScreenshotResult.class);

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        assertThat(block).isNotNull();
        assertThat(block.content()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<ContentBlockParam> blocks = (List<ContentBlockParam>) block.content();
        assertThat(blocks).hasSize(1);
        ContentBlockParam.ImageBlockParam image = (ContentBlockParam.ImageBlockParam) blocks.get(0);
        assertThat(image.type()).isEqualTo("image");
        assertThat(image.source().type()).isEqualTo("base64");
        assertThat(image.source().mediaType()).isEqualTo("image/jpeg");

        byte[] decoded = Base64.getDecoder().decode(image.source().data());
        assertThat(decoded).isEqualTo(jpeg);
        // JPEG magic bytes: FF D8 FF
        assertThat(decoded[0] & 0xFF).isEqualTo(0xFF);
        assertThat(decoded[1] & 0xFF).isEqualTo(0xD8);
        assertThat(decoded[2] & 0xFF).isEqualTo(0xFF);
    }

    @Test
    @DisplayName("截图后几何快照入 store（点击换算必须用截图时刻的几何）")
    void geometrySnapshotStoredForClick() throws Exception {
        byte[] jpeg = sampleJpeg();
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                new FakeCapturer(new ComputerUseCaptureResult(jpeg, "image/jpeg",
                        1456, 819, 1920, 1080, 7, 9, 1.0d, 1920, 1080)), store);

        tool.execute(new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()));

        // 本用例走 ctx-less 路径（execute(call) ⇒ sessionId=null）⇒ 落全局桶
        ComputerUseScreenshotGeometry geometry = store.get(null);
        assertThat(geometry).isNotNull();
        assertThat(geometry.width()).isEqualTo(1456);
        assertThat(geometry.height()).isEqualTo(819);
        assertThat(geometry.displayWidth()).isEqualTo(1920);
        assertThat(geometry.displayHeight()).isEqualTo(1080);
        assertThat(geometry.originX()).isEqualTo(7);
        assertThat(geometry.originY()).isEqualTo(9);
    }

    @Test
    @DisplayName("载荷 toString 不含 base64（避免 base64 混进 tool_result 文本载荷）")
    void payloadToStringHasNoBase64() throws Exception {
        byte[] jpeg = sampleJpeg();
        ComputerUseScreenshotResult payload = new ComputerUseScreenshotResult(
                List.of(ComputerUseMcpContent.image(Base64.getEncoder().encodeToString(jpeg), "image/jpeg")),
                "image/jpeg", 1456, 819, 1920, 1080, 0, 0, jpeg.length, 1.0d);
        assertThat(payload.toString()).doesNotContain(Base64.getEncoder().encodeToString(jpeg));
        assertThat(payload.toString()).contains("1456x819");
    }
}
