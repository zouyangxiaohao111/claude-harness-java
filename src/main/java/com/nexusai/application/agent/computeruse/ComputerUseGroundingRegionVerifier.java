package com.nexusai.application.agent.computeruse;

import java.awt.image.BufferedImage;

/**
 * 护栏 ② · <b>落点区域像素比对</b>（grounding 报出坐标后、真正点击之前的二次核对）。
 *
 * <h2>WHY 需要它（PoC 实测暴露的失效模式）</h2>
 * <p>grounding 给出坐标到真正点击之间隔着<b>模型思考 + 工具往返</b> ⇒ 这段时间里屏幕可能已经变了
 * （窗口被移动 / 被遮挡 / 弹出提示 / 页面刷新）⇒ 此时按旧坐标点击 = <b>点在了一个模型没看过的地方</b>。
 * <p>⇒ 点击前对「目标坐标周边区域」<b>二次截图</b>，与<b>首次截图的同一区域</b>做像素级比对；
 * 差异超阈值 ⇒ <b>拒绝点击</b>并回明确错误（模型可见 ⇒ 模型可以重新截图再试）。
 *
 * <h2>比对口径</h2>
 * <ul>
 *   <li><b>区域</b>：目标点周围 {@value #DEFAULT_REGION_SIZE} × {@value #DEFAULT_REGION_SIZE}
 *       <b>图像像素</b>（默认 60×60）—— 太小会被光标 / 动画误报，太大则一个控件的变化被稀释；</li>
 *   <li><b>分辨率对齐</b>：首次截图是<b>预缩放</b>后的图（如 1920→1456），二次截图是
 *       <b>原生分辨率</b>的区域 ⇒ 比对前把二次截图<b>双线性缩到首次截图的分辨率</b>
 *       （{@link ComputerUseImageCodec#scaleTo}）；</li>
 *   <li><b>度量</b>：两帧同区域转<b>灰度</b>后算 ①<b>平均绝对差</b>（MAD，0–255）
 *       ②<b>差异像素占比</b>（单像素灰度差 ≥ {@value #DIFF_LEVEL} 记为「变了」）；</li>
 *   <li><b>判定</b>：MAD ≥ 阈值 <b>或</b> 差异占比 ≥ 阈值 ⇒ 判「区域已变化」⇒ 拒绝点击。</li>
 * </ul>
 *
 * <h2>CCB 对照</h2>
 * <p>CCB 2.8.4 有 {@code packages/@ant/computer-use-mcp/src/pixelCompare.ts}
 * （9×9 逐字节<b>完全相等</b>、冷启动跳过、内部异常跳过、Windows 侧 {@code cropRawPatch} 恒 null
 * ⇒ <b>该校验在 Windows 上永不生效</b>）。本仓实现与其<b>形态不同</b>：
 * 区域更大（60×60）、度量是<b>容差</b>（MAD + 差异占比）而不是逐字节相等、
 * 二次取图走<b>真区域抓取</b>（{@link ComputerUseScreenCapturer#captureRegion}）而不是裁剪旧图。
 * ⇒ 属<b>本仓增强</b>（CCB 的 9×9 严格相等在 JPEG 缩放链上必然误报）。
 */
public final class ComputerUseGroundingRegionVerifier {

    /** 默认区域边长（<b>图像像素</b>）· {@value}。 */
    public static final int DEFAULT_REGION_SIZE = 60;

    /** 单像素「算变了」的灰度差级别（0–255）· {@value}。 */
    public static final int DIFF_LEVEL = 40;

    /** 默认 MAD（平均绝对差）阈值 · {@value}（灰度级，0–255）。 */
    public static final double DEFAULT_MAD_THRESHOLD = 15.0d;

    /** 默认「差异像素占比」阈值 · {@value}。 */
    public static final double DEFAULT_DIFF_RATIO_THRESHOLD = 0.35d;

    private ComputerUseGroundingRegionVerifier() {
        throw new AssertionError("utility class");
    }

    /**
     * 图像空间里的区域矩形（以目标点为中心，边长 {@code size}，<b>夹在图像边界内</b>）。
     *
     * @param imageX      目标点的图像像素 X
     * @param imageY      目标点的图像像素 Y
     * @param imageWidth  图像宽
     * @param imageHeight 图像高
     * @param size        区域边长（图像像素）
     * @return 区域矩形（图像空间）
     */
    public static RegionRect regionAround(double imageX, double imageY,
                                           int imageWidth, int imageHeight, int size) {
        int w = Math.min(Math.max(1, size), Math.max(1, imageWidth));
        int h = Math.min(Math.max(1, size), Math.max(1, imageHeight));
        int cx = (int) Math.round(imageX);
        int cy = (int) Math.round(imageY);
        int x = Math.max(0, Math.min(imageWidth - w, cx - w / 2));
        int y = Math.max(0, Math.min(imageHeight - h, cy - h / 2));
        return new RegionRect(x, y, w, h);
    }

    /**
     * 图像空间的区域 → 屏幕空间的区域（<b>用截图时刻的几何</b>，与
     * {@link ComputerUseCoordinateScaler} 同源）。
     *
     * @param imageRect 图像空间区域
     * @param geometry  截图时刻几何快照
     * @return 屏幕空间区域（虚拟屏幕坐标）
     */
    public static RegionRect screenRect(RegionRect imageRect, ComputerUseScreenshotGeometry geometry) {
        int x = (int) Math.round(imageRect.x() * geometry.scaleX()) + geometry.originX();
        int y = (int) Math.round(imageRect.y() * geometry.scaleY()) + geometry.originY();
        int w = Math.max(1, (int) Math.round(imageRect.width() * geometry.scaleX()));
        int h = Math.max(1, (int) Math.round(imageRect.height() * geometry.scaleY()));
        return new RegionRect(x, y, w, h);
    }

    /**
     * 实测：把「首次截图的该区域」与「此刻屏幕的该区域」比一遍。
     *
     * @param capturer   截屏器（<b>真区域抓取</b>）
     * @param frame      首次定位帧
     * @param imageX     目标点的图像像素 X
     * @param imageY     目标点的图像像素 Y
     * @param regionSize 区域边长（图像像素）
     * @return 差异度量
     * @throws Exception 二次抓图失败（由调用方决定「跳过」还是「拒绝」）
     */
    public static RegionDiff measure(ComputerUseScreenCapturer capturer,
                                     ComputerUseGroundingFrame frame,
                                     double imageX, double imageY,
                                     int regionSize) throws Exception {
        ComputerUseCaptureResult capture = frame.capture();
        ComputerUseScreenshotGeometry geometry = frame.geometry();
        RegionRect rect = regionAround(imageX, imageY,
                capture.imageWidth(), capture.imageHeight(), regionSize);

        BufferedImage frameImage = ComputerUseImageCodec.decode(capture.jpegBytes());
        BufferedImage patch1 = frameImage.getSubimage(
                rect.x(), rect.y(), rect.width(), rect.height());

        RegionRect screen = screenRect(rect, geometry);
        ComputerUseCaptureResult fresh = capturer.captureRegion(
                screen.x(), screen.y(), screen.width(), screen.height());
        BufferedImage freshImage = ComputerUseImageCodec.decode(fresh.jpegBytes());
        BufferedImage patch2 = ComputerUseImageCodec.scaleTo(
                freshImage, rect.width(), rect.height());

        return compare(patch1, patch2);
    }

    /**
     * 两块同尺寸图像的灰度差异度量。
     *
     * @param a 图 a
     * @param b 图 b
     * @return 差异度量
     */
    public static RegionDiff compare(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("[computer-use] 区域比对：图像为空");
        }
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("[computer-use] 区域比对：图像尺寸非法");
        }
        long sum = 0L;
        int changed = 0;
        int total = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int diff = Math.abs(gray(a.getRGB(x, y)) - gray(b.getRGB(x, y)));
                sum += diff;
                if (diff >= DIFF_LEVEL) {
                    changed++;
                }
            }
        }
        return new RegionDiff((double) sum / (double) total, (double) changed / (double) total);
    }

    /** RGB → 灰度（ITU-R BT.601 luma）。 */
    private static int gray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (int) Math.round(0.299d * r + 0.587d * g + 0.114d * b);
    }

    /**
     * 区域矩形（图像空间或屏幕空间，取决于上下文）。
     *
     * @param x      左上 X
     * @param y      左上 Y
     * @param width  宽
     * @param height 高
     */
    public record RegionRect(int x, int y, int width, int height) {
    }

    /**
     * 区域差异度量。
     *
     * @param meanAbsDiff    平均绝对差（灰度 0–255）
     * @param diffPixelRatio 「变了」的像素占比（0.0–1.0）
     */
    public record RegionDiff(double meanAbsDiff, double diffPixelRatio) {

        /**
         * 是否判「区域已变化」。
         *
         * @param madThreshold        MAD 阈值
         * @param diffRatioThreshold  差异占比阈值
         * @return true = 已变化（应拒绝点击）
         */
        public boolean changed(double madThreshold, double diffRatioThreshold) {
            return meanAbsDiff >= madThreshold || diffPixelRatio >= diffRatioThreshold;
        }

        /** 人类可读摘要（进错误文案 / 日志）。 */
        public String describe() {
            return "平均绝对差 " + Math.round(meanAbsDiff * 100.0d) / 100.0d
                    + "（阈值 " + Math.round(DEFAULT_MAD_THRESHOLD * 100.0d) / 100.0d + "），差异像素占比 "
                    + Math.round(diffPixelRatio * 10000.0d) / 100.0d + "%"
                    + "（阈值 " + Math.round(DEFAULT_DIFF_RATIO_THRESHOLD * 10000.0d) / 100.0d + "%）";
        }
    }
}
