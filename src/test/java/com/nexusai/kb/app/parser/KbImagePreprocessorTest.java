package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描页 / 图片预处理单测（纯 JDK 图像运算，不启 Spring）。
 *
 * <p>四条硬判据：
 * <ol>
 *   <li>白边必须裁到内容包围盒（用「白底 + 中间 200×100 黑块 + 四周 50px 白边」的可算真值）；</li>
 *   <li>3° 倾斜的文本条必须被转回来（残角 ≤ 1°）—— 判据用<b>独立的</b>投影方差峰值测量，
 *       ⛔ 不拿被测代码自己的 {@code deskew} 结果当答案（否则只是自洽）；</li>
 *   <li>全白图不许产出 0×0 空图（视觉模型拿到空图必失败）；</li>
 *   <li>开关关闭时不许读像素、不许落盘。</li>
 * </ol>
 */
@DisplayName("[B2c] 扫描页图片预处理 · 白边裁剪 + 小角度纠偏")
class KbImagePreprocessorTest {

    /** 每个用例后恢复开关，避免静态字段把状态漏给别的用例。 */
    @AfterEach
    void restoreSwitch() {
        KbImagePreprocessor.setEnabledForTest(true);
    }

    @Test
    @DisplayName("① 白底 + 中间 200x100 黑块 + 四周 50px 白边 ⇒ trim 后约 200x100（±2）")
    void trimWhiteBorderKeepsOnlyContent() {
        BufferedImage src = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 300, 200);
            g.setColor(Color.BLACK);
            g.fillRect(50, 50, 200, 100);
        } finally {
            g.dispose();
        }

        BufferedImage out = KbImagePreprocessor.trimWhiteBorder(
            src, KbImagePreprocessor.DEFAULT_WHITE_THRESHOLD);

        assertThat(out)
            .as("有白边可裁时必须返回新图（返回原图 = 没裁）")
            .isNotSameAs(src);
        assertThat(out.getWidth()).as("裁剪后宽 ≈ 200").isBetween(198, 202);
        assertThat(out.getHeight()).as("裁剪后高 ≈ 100").isBetween(98, 102);
        // 内容本身不能被裁掉：中心仍是黑
        assertThat((out.getRGB(out.getWidth() / 2, out.getHeight() / 2) >> 16) & 0xFF)
            .as("裁剪后中心像素必须仍是黑（内容没被切）")
            .isLessThan(32);
    }

    @Test
    @DisplayName("② 合成倾斜 3° 的文本条图 ⇒ deskew 后残角 <= 1°")
    void deskewStraightensTiltedTextLines() {
        BufferedImage tilted = textStrip(3.0);
        double before = bestProjectionAngle(tilted);
        assertThat(Math.abs(before))
            .as("合成图必须真的歪了，否则本用例没判别力（实测峰值角=%s）", before)
            .isGreaterThan(2.0);

        BufferedImage out = KbImagePreprocessor.deskew(tilted, 6.0, 0.5);

        assertThat(out)
            .as("3° 倾斜必须触发旋转（返回原图说明没纠偏）")
            .isNotSameAs(tilted);
        double after = bestProjectionAngle(out);
        assertThat(Math.abs(after))
            .as("纠偏后残角必须 <= 1°（实测=%s，纠偏前=%s）", after, before)
            .isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("③ 全白图 ⇒ trim 返回原图（尺寸不变，不产 0x0 空图）")
    void trimKeepsAllWhiteImage() {
        BufferedImage src = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 120, 80);
        } finally {
            g.dispose();
        }

        BufferedImage out = KbImagePreprocessor.trimWhiteBorder(
            src, KbImagePreprocessor.DEFAULT_WHITE_THRESHOLD);

        assertThat(out).as("全白图必须原样返回（同一个对象）").isSameAs(src);
        assertThat(out.getWidth()).as("全白图尺寸不变").isEqualTo(120);
        assertThat(out.getHeight()).as("全白图尺寸不变").isEqualTo(80);
    }

    @Test
    @DisplayName("④ 开关关闭 ⇒ preprocess 不做处理：返回原路径、不产目录/文件")
    void preprocessIsNoOpWhenDisabled(@TempDir Path dir) throws Exception {
        Path in = writeScannedPng(dir);
        Path outDir = dir.resolve("pre");
        long sizeBefore = Files.size(in);

        KbImagePreprocessor.setEnabledForTest(false);
        Path out = KbImagePreprocessor.preprocess(in, outDir);

        assertThat(KbImagePreprocessor.isEnabled()).as("开关应为关闭").isFalse();
        assertThat(out).as("关闭时必须返回同一个路径").isEqualTo(in);
        assertThat(Files.exists(outDir)).as("关闭时不许创建产物目录").isFalse();
        assertThat(Files.size(in)).as("关闭时原图不许被改写").isEqualTo(sizeBefore);
    }

    @Test
    @DisplayName("⑤ 开关开启 ⇒ 产物为 <原名>-pre.jpg，可读回且白边已裁")
    void preprocessWritesPreJpeg(@TempDir Path dir) throws Exception {
        Path in = writeScannedPng(dir);
        Path out = KbImagePreprocessor.preprocess(in, dir.resolve("pre"));

        assertThat(out).as("开启时应返回预处理产物路径").isNotEqualTo(in);
        assertThat(out.getFileName().toString()).as("命名规则 = 原名主干 + -pre.jpg").isEqualTo("scan-pre.jpg");
        assertThat(Files.size(out)).as("产物必须真的落盘").isGreaterThan(0L);

        BufferedImage back = ImageIO.read(out.toFile());
        assertThat(back).as("产物必须是可解码的 JPEG").isNotNull();
        // 夹具内容 = 200 宽 × 98 高 的横条区（见 writeScannedPng）；没有倾斜 ⇒ 不该被转 ⇒ 尺寸保持内容框
        assertThat(back.getWidth()).as("落盘产物也已裁掉白边（宽 ≈ 200）").isBetween(198, 202);
        assertThat(back.getHeight()).as("落盘产物也已裁掉白边（高 ≈ 98，±2 内）").isBetween(96, 102);
    }

    @Test
    @DisplayName("⑥ 已对齐图 ⇒ deskew 返回原图（不为 <step/2 的误差付重采样）")
    void deskewKeepsAlignedImage() {
        BufferedImage aligned = textStrip(0.0);
        BufferedImage out = KbImagePreprocessor.deskew(aligned, 6.0, 0.5);
        assertThat(out).as("0° 图必须原样返回").isSameAs(aligned);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用例夹具 / 独立测量
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 造「白底 + 白边 + 横条内容区」的 PNG（trim 的真值可算：200 宽 × 98 高）。
     *
     * <p>⛔ 内容不能用一整块实心黑：实心块的「投影方差」会随旋转角单调上升（转角越大、投影越宽），
     * 最优角必然落到搜索边界 —— 那种图本来就不该被纠偏（见实现里的边界守卫）。横条才有
     * 「行对齐」的方差尖峰。
     */
    private static Path writeScannedPng(Path dir) throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 300);
            g.setColor(Color.BLACK);
            for (int k = 0; k < 10; k++) {
                g.fillRect(100, 100 + k * 10, 200, 8);
            }
        } finally {
            g.dispose();
        }
        Path f = dir.resolve("scan.png");
        ImageIO.write(img, "png", f.toFile());
        return f;
    }

    /**
     * 造一张整体旋转 {@code angleDeg} 度的「文本条」图（12 条 400×8 的黑条，行距 24px）。
     *
     * <p>条高 8px &lt; 行距 24px ⇒ 对齐时投影方差有明显尖峰；倾斜 3° 时 400px 跨度上漂移约 21px，
     * 已能压过行距，方差峰值会明确离开 0°。
     */
    private static BufferedImage textStrip(double angleDeg) {
        int w = 600;
        int h = 400;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.translate(w / 2.0, h / 2.0);
            if (angleDeg != 0.0) {
                g.rotate(Math.toRadians(angleDeg));
            }
            g.setColor(Color.BLACK);
            for (int i = -6; i < 6; i++) {
                g.fillRect(-200, i * 24, 400, 8);
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /**
     * <b>独立</b>测量「让水平投影方差最大的角度」（-6°～+6°，0.1° 步长）。
     *
     * <p>⛔ 不复用 {@code KbImagePreprocessor.deskewMeasured}：残角判据必须与被测实现分离，
     * 否则「实现错、测量也错」时会一起绿。
     */
    private static double bestProjectionAngle(BufferedImage img) {
        double best = 0;
        double bestScore = -1;
        for (int k = -60; k <= 60; k++) {
            double angle = k * 0.1;
            double score = projectionVariance(img, angle);
            if (score > bestScore) {
                bestScore = score;
                best = angle;
            }
        }
        return best;
    }

    /** 行投影方差（采样步长 2px，够 0.1° 精度）。 */
    private static double projectionVariance(BufferedImage img, double angleDeg) {
        int w = img.getWidth();
        int h = img.getHeight();
        double rad = Math.toRadians(angleDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double minY = Math.min(0, (w - 1) * sin) + Math.min(0, (h - 1) * cos);
        double maxY = Math.max(0, (w - 1) * sin) + Math.max(0, (h - 1) * cos);
        int rows = (int) Math.floor(maxY - minY) + 1;
        int[] hist = new int[rows];
        int n = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                if (((img.getRGB(x, y) >> 16) & 0xFF) >= 128) {
                    continue;
                }
                int r = (int) Math.round(x * sin + y * cos - minY);
                if (r < 0) {
                    r = 0;
                } else if (r >= rows) {
                    r = rows - 1;
                }
                hist[r]++;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        double mean = (double) n / rows;
        double sum = 0;
        for (int r = 0; r < rows; r++) {
            double d = hist[r] - mean;
            sum += d * d;
        }
        return sum / rows;
    }
}
