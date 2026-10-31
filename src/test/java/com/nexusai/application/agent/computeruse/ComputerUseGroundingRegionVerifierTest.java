package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 2026-10-31 · 护栏②（点击前落点校验）的<b>像素比对</b>单测（纯单测 · 不碰真实桌面）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：护栏② 是「<b>点了才知错</b>」的最后一道闸 ——
 * 它必须做到两件事，缺一不可：
 * <ol>
 *   <li><b>没变 ⇒ 放行</b>（否则每次点击都被拦 ⇒ 工具不可用）；</li>
 *   <li><b>变了 ⇒ 拦下</b>（否则它只是个装饰）。</li>
 * </ol>
 * <p>⇒ 本测试用「纯白 vs 纯黑」构造<b>确定的</b>差异（不依赖真实桌面、不依赖视觉模型）。
 */
class ComputerUseGroundingRegionVerifierTest {

    /** 纯色图（{@code w}×{@code h}）。 */
    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** 假截屏器：整屏不实现，区域抓取返回固定内容。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        private final BufferedImage regionImage;

        FakeCapturer(BufferedImage regionImage) {
            this.regionImage = regionImage;
        }

        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("护栏② 单测不需要整屏截图");
        }

        @Override
        public ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) {
            byte[] jpeg;
            try {
                jpeg = ComputerUseImageCodec.encodeJpeg(regionImage);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ComputerUseCaptureResult(jpeg, "image/jpeg",
                    regionImage.getWidth(), regionImage.getHeight(),
                    regionImage.getWidth(), regionImage.getHeight(),
                    x, y, 1.0d, regionImage.getWidth(), regionImage.getHeight());
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    /** 定位帧：1456×819 的纯白图（= 1920 屏预缩放后的真实尺寸）。 */
    private static ComputerUseGroundingFrame frame(BufferedImage image) {
        byte[] jpeg;
        try {
            jpeg = ComputerUseImageCodec.encodeJpeg(image);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ComputerUseCaptureResult capture = new ComputerUseCaptureResult(
                jpeg, "image/jpeg", image.getWidth(), image.getHeight(),
                1920, 1080, 0, 0, 1.0d, 1920, 1080);
        return new ComputerUseGroundingFrame(capture, 730d, 410d, "保存按钮");
    }

    // ─────────────────── 区域矩形 ───────────────────

    @Test
    @DisplayName("区域矩形以目标点为中心、边长固定")
    void regionIsCenteredOnTarget() {
        ComputerUseGroundingRegionVerifier.RegionRect rect =
                ComputerUseGroundingRegionVerifier.regionAround(730, 410, 1456, 819, 60);

        assertThat(rect.x()).isEqualTo(700);
        assertThat(rect.y()).isEqualTo(380);
        assertThat(rect.width()).isEqualTo(60);
        assertThat(rect.height()).isEqualTo(60);
    }

    @Test
    @DisplayName("区域矩形必须夹在图像边界内（目标贴边时不得越界）")
    void regionIsClampedToImageBounds() {
        ComputerUseGroundingRegionVerifier.RegionRect rect =
                ComputerUseGroundingRegionVerifier.regionAround(3, 2, 1456, 819, 60);

        assertThat(rect.x()).isZero();
        assertThat(rect.y()).isZero();

        ComputerUseGroundingRegionVerifier.RegionRect far =
                ComputerUseGroundingRegionVerifier.regionAround(1455, 818, 1456, 819, 60);
        assertThat(far.x() + far.width()).isEqualTo(1456);
        assertThat(far.y() + far.height()).isEqualTo(819);
    }

    @Test
    @DisplayName("图像空间的区域 → 屏幕空间的区域（用截图时刻几何，比例方向不可反）")
    void screenRectUsesCaptureTimeGeometry() {
        ComputerUseScreenshotGeometry geometry =
                new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0);
        ComputerUseGroundingRegionVerifier.RegionRect imageRect =
                new ComputerUseGroundingRegionVerifier.RegionRect(700, 380, 60, 60);

        ComputerUseGroundingRegionVerifier.RegionRect screen =
                ComputerUseGroundingRegionVerifier.screenRect(imageRect, geometry);

        assertThat(screen.x()).isEqualTo((int) Math.round(700 * 1920.0d / 1456.0d));
        assertThat(screen.y()).isEqualTo((int) Math.round(380 * 1080.0d / 819.0d));
        assertThat(screen.width()).isEqualTo((int) Math.round(60 * 1920.0d / 1456.0d));
        assertThat(screen.height()).isEqualTo((int) Math.round(60 * 1080.0d / 819.0d));
    }

    // ─────────────────── 差异度量 ───────────────────

    @Test
    @DisplayName("两块完全相同的图 ⇒ MAD=0、差异占比=0 ⇒ 不判「变了」")
    void identicalImagesAreNotChanged() {
        BufferedImage a = solid(60, 60, Color.WHITE);
        BufferedImage b = solid(60, 60, Color.WHITE);

        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.compare(a, b);

        assertThat(diff.meanAbsDiff()).isLessThan(2.0d);
        assertThat(diff.diffPixelRatio()).isZero();
        assertThat(diff.changed(ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("内容彻底变了（白 vs 黑）⇒ MAD 与差异占比都拉满 ⇒ 判「变了」")
    void completelyDifferentImagesAreChanged() {
        BufferedImage a = solid(60, 60, Color.WHITE);
        BufferedImage b = solid(60, 60, Color.BLACK);

        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.compare(a, b);

        assertThat(diff.meanAbsDiff()).isGreaterThan(200.0d);
        assertThat(diff.diffPixelRatio()).isEqualTo(1.0d);
        assertThat(diff.changed(ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("差异像素占比：只有单像素灰度差 ≥ 40 才算「变了」")
    void diffPixelRatioCountsLargeDeltasOnly() {
        BufferedImage a = solid(10, 10, Color.WHITE);
        BufferedImage b = solid(10, 10, Color.WHITE);
        // 100 个像素里改 25 个为黑（灰度差 255 ≥ 40）
        for (int i = 0; i < 25; i++) {
            b.setRGB(i % 10, i / 10, Color.BLACK.getRGB());
        }

        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.compare(a, b);

        assertThat(diff.diffPixelRatio()).isCloseTo(0.25d, org.assertj.core.data.Offset.offset(0.01d));
    }

    @Test
    @DisplayName("阈值可配：把阈值抬到极高 ⇒ 即使内容全变也不再判「变了」")
    void thresholdsAreConfigurable() {
        BufferedImage a = solid(60, 60, Color.WHITE);
        BufferedImage b = solid(60, 60, Color.BLACK);
        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.compare(a, b);

        assertThat(diff.changed(255.5d, 1.01d)).isFalse();
        assertThat(diff.changed(15.0d, 0.35d)).isTrue();
    }

    // ─────────────────── 端到端：measure（首帧 ↔ 此刻） ───────────────────

    @Test
    @DisplayName("measure：区域没变 ⇒ 不判「变了」（⇒ 点击放行）")
    void measureDetectsNoChange() throws Exception {
        ComputerUseGroundingFrame f = frame(solid(1456, 819, Color.WHITE));
        FakeCapturer capturer = new FakeCapturer(solid(79, 79, Color.WHITE));

        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.measure(capturer, f, 730d, 410d, 60);

        assertThat(diff.changed(ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("measure：区域变了 ⇒ 判「变了」（⇒ 点击被拒绝）")
    void measureDetectsChange() throws Exception {
        ComputerUseGroundingFrame f = frame(solid(1456, 819, Color.WHITE));
        FakeCapturer capturer = new FakeCapturer(solid(79, 79, Color.BLACK));

        ComputerUseGroundingRegionVerifier.RegionDiff diff =
                ComputerUseGroundingRegionVerifier.measure(capturer, f, 730d, 410d, 60);

        assertThat(diff.changed(ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD)).isTrue();
    }
}
