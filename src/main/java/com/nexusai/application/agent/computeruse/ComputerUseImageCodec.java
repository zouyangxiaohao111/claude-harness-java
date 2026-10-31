package com.nexusai.application.agent.computeruse;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 图像编解码工具 · computer-use 截图链共用。
 *
 * <p><b>WHY 独立成类</b>：Windows 侧的同类代码在 {@link Win32ScreenCapturer} 里是
 * <b>private static</b>（与 JNA 读取耦合）；macOS 侧（{@link MacOsScreenCapturer}）
 * 需要同一套「缩放 + JPEG 编码」但走完全不同的取图路径
 * ⇒ 抽成共用工具类，<b>不改 Windows 侧既有代码</b>（避免动到在役路径）。
 *
 * <p><b>参数对齐</b>：JPEG 质量 {@link #SCREENSHOT_JPEG_QUALITY} = 0.75
 * （CCB {@code src/utils/computerUse/executor.ts:57} {@code SCREENSHOT_JPEG_QUALITY = 0.75}），
 * 与 {@link Win32ScreenCapturer#SCREENSHOT_JPEG_QUALITY} 同值。
 */
public final class ComputerUseImageCodec {

    /** CCB {@code src/utils/computerUse/executor.ts:57} {@code SCREENSHOT_JPEG_QUALITY = 0.75}。 */
    public static final float SCREENSHOT_JPEG_QUALITY = 0.75f;

    private ComputerUseImageCodec() {
        throw new AssertionError("utility class");
    }

    /**
     * 双线性缩放到目标尺寸（CCB 用 Pillow resize；本仓用 Graphics2D bilinear）。
     *
     * @param src      源图
     * @param targetW  目标宽
     * @param targetH  目标高
     * @return 缩放后的图像
     */
    public static BufferedImage scaleTo(BufferedImage src, int targetW, int targetH) {
        if (targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException(
                    "[computer-use] 缩放目标尺寸非法: " + targetW + "x" + targetH);
        }
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * JPEG 编码 · 质量 {@link #SCREENSHOT_JPEG_QUALITY}。
     *
     * @param img 图像
     * @return JPEG 字节
     * @throws IOException 编码失败
     */
    public static byte[] encodeJpeg(BufferedImage img) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("[computer-use] 找不到 JPEG ImageWriter（ImageIO 插件缺失）");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(SCREENSHOT_JPEG_QUALITY);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * 解码 PNG / JPEG 字节。
     *
     * @param bytes 图像字节
     * @return 解码后的图像
     * @throws IOException 解码失败
     */
    public static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("[computer-use] 图像字节为空");
        }
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IOException("[computer-use] 图像解码失败（ImageIO 不认识该格式）");
            }
            return img;
        }
    }
}
