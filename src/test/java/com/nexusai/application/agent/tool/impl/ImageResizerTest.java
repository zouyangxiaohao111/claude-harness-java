package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.ImageDimensions;
import com.nexusai.infra.util.ImageResizeError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageResizer} CC 对齐验证（OPD-TOOL-06-3b）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：本测试验证的是 Read 工具 image 分支
 * <b>为何</b>需要 token 预算 + 缩放 + 压缩——大图 base64 若不压缩会击穿上下文窗口（Anthropic
 * API 对单张图 base64 硬限 5MB，token 预算默认 25000）。因此断言的是"压缩后 token 估算 ≤ 25000
 * 防击穿"，而非仅断言 base64 非空。
 */
public class ImageResizerTest {

    // ── 确定性 token 估算公式（8 base64 字符 ≈ 1 token） ──────────────────────

    @Test
    @DisplayName("token 估算公式: base64.length * 0.125 —— 8 个 base64 字符 ≈ 1 token（变异 0.125→0.25 应红）")
    void tokenEstimateFormula() {
        // 8 个 base64 字符 ≈ 1 token；16 字符 ≈ 2 token；17 字符向上取整 ≈ 3 token
        assertThat(ImageResizer.estimateTokens("AAAAAAAA"))            // 8 字符
            .isEqualTo(1);
        assertThat(ImageResizer.estimateTokens("AAAAAAAAAAAAAAAA"))    // 16 字符
            .isEqualTo(2);
        assertThat(ImageResizer.estimateTokens("AAAAAAAAAAAAAAAAA"))   // 17 字符 → ceil(2.125)=3
            .isEqualTo(3);
    }

    @Test
    @DisplayName("token→字节换算: maxBytesForTokens(25000) = 150000 —— floor(floor(25000/0.125)*0.75)（变异 0.75→0.8 应红）")
    void maxBytesForTokensFormula() {
        assertThat(ImageResizer.maxBytesForTokens(25_000)).isEqualTo(150_000);
    }

    // ── magic-byte 格式探测 ──────────────────────────────────────────────────

    @Test
    @DisplayName("magic-byte 探测: PNG/JPEG/GIF/WEBP 签名 + 未知默认 image/png")
    void detectImageFormatByMagicBytes() {
        assertThat(ImageResizer.detectImageFormatFromBuffer(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}))
            .isEqualTo("image/png");
        assertThat(ImageResizer.detectImageFormatFromBuffer(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}))
            .isEqualTo("image/jpeg");
        assertThat(ImageResizer.detectImageFormatFromBuffer(new byte[]{0x47, 0x49, 0x46, 0x38}))
            .isEqualTo("image/gif");
        assertThat(ImageResizer.detectImageFormatFromBuffer(
            new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50}))
            .isEqualTo("image/webp");
        // 未知/过短 → 默认 image/png（CC imageResizer.ts:770/:811）
        assertThat(ImageResizer.detectImageFormatFromBuffer(new byte[]{1, 2, 3}))
            .isEqualTo("image/png");
    }

    // ── 空图 / corrupt 图 fallback（镜像 CC catch→fallback 原样直发） ─────────

    @Test
    @DisplayName("空图抛 ImageResizeError —— CC FileReadTool.ts:1109-1111 throw 'Image file is empty'")
    void emptyImageThrows() {
        assertThatThrownBy(() -> ImageResizer.readImageWithTokenBudget(new byte[0], 25_000))
            .isInstanceOf(ImageResizeError.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("corrupt 图(非合法图) fallback 原样直发 + magic-byte 格式 —— CC imageResizer.ts:414-420")
    void corruptImageFallsBackToOriginalBytes() {
        // 12 字节假 PNG（非合法图，ImageIO 解析必失败）→ CC 同款 catch→fallback
        byte[] fakePng = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        ImageResizer.Result r = ImageResizer.readImageWithTokenBudget(fakePng, 25_000);
        assertThat(r.mediaType()).isEqualTo("image/png");
        assertThat(r.base64()).isEqualTo(Base64.getEncoder().encodeToString(fakePng));
        assertThat(r.dimensions()).isNull();
        assertThat(r.originalSize()).isEqualTo(12);
    }

    // ── display 尺寸填充 + 2000x2000 缩放（防击穿坐标映射契约） ─────────────

    @Test
    @DisplayName("小图(≤2000 且 ≤3.75MB) 原样直发 + display 尺寸填充 == 原始 —— CC imageResizer.ts:212-227")
    void smallImageFillsDisplayDimensionsEqualToOriginal() throws Exception {
        byte[] png = solidPng(50, 40, Color.RED);
        ImageResizer.Result r = ImageResizer.readImageWithTokenBudget(png, 25_000);
        ImageDimensions d = r.dimensions();
        assertThat(d).isNotNull();
        assertThat(d.originalWidth()).isEqualTo(50);
        assertThat(d.originalHeight()).isEqualTo(40);
        assertThat(d.displayWidth()).isEqualTo(50);
        assertThat(d.displayHeight()).isEqualTo(40);
        // 无缩放 → 原字节直发
        assertThat(r.base64()).isEqualTo(Base64.getEncoder().encodeToString(png));
    }

    @Test
    @DisplayName("超 2000 宽图缩放到 ≤2000 保宽高比 + display 尺寸非 null —— CC imageResizer.ts:278-286")
    void oversizedImageResizedToWithin2000AndFillsDisplayDimensions() throws Exception {
        byte[] png = solidPng(2100, 1050, Color.BLUE);
        ImageResizer.Result r = ImageResizer.readImageWithTokenBudget(png, 25_000);
        ImageDimensions d = r.dimensions();
        assertThat(d).isNotNull();
        assertThat(d.originalWidth()).isEqualTo(2100);
        assertThat(d.originalHeight()).isEqualTo(1050);
        // 缩放到 ≤2000（保宽高比：2100→2000, 1050→1000）
        assertThat(d.displayWidth()).isEqualTo(2000);
        assertThat(d.displayHeight()).isEqualTo(1000);
        assertThat(d.displayWidth()).isLessThanOrEqualTo(ImageResizer.IMAGE_MAX_WIDTH);
        assertThat(d.displayHeight()).isLessThanOrEqualTo(ImageResizer.IMAGE_MAX_HEIGHT);
    }

    // ── 超预算激进压缩（核心意图：防击穿上下文窗口） ─────────────────────────

    @Test
    @DisplayName("大图超预算经压缩后 token 估算 ≤ 25000 防击穿上下文窗口 —— CC FileReadTool.ts:1136-1153")
    void overBudgetImageCompressedToFitTokenBudget() throws Exception {
        byte[] noise = noisePng(600, 600);
        String originalBase64 = Base64.getEncoder().encodeToString(noise);
        // 原始字节 base64 必须超 25000 token 预算（否则测不到压缩路径）
        assertThat(ImageResizer.estimateTokens(originalBase64)).isGreaterThan(25_000);

        ImageResizer.Result r = ImageResizer.readImageWithTokenBudget(noise, 25_000);

        // 意图断言：压缩后 token 估算 ≤ 25000（防击穿上下文窗口）
        assertThat(ImageResizer.estimateTokens(r.base64()))
            .as("压缩后 token 估算必须落在预算内，防大图 base64 击穿上下文窗口")
            .isLessThanOrEqualTo(25_000);
        // 压缩确实生效（比原字节 base64 小）
        assertThat(r.base64().length()).isLessThan(originalBase64.length());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static byte[] solidPng(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return encodePng(img);
    }

    private static byte[] noisePng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        return encodePng(img);
    }

    private static byte[] encodePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
