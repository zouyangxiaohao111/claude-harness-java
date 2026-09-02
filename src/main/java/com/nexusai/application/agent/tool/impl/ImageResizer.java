package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.ImageDimensions;
import com.nexusai.infra.util.ImageResizeError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * 图片缩放/压缩管道 · 镜像 CC {@code utils/imageResizer.ts} + {@code FileReadTool.ts:1097-1183
 * readImageWithTokenBudget}。让 Read 工具 image 分支在大图时加 token 预算 + 标准缩放 + 超预算激进
 * 压缩 + magic-byte 格式探测 + display 尺寸填充，防止大图 base64 击穿上下文窗口。
 *
 * <p>JDK 约束（无 sharp 等价 API，压缩率可能不及 CC）：
 * <ul>
 *   <li>PNG palette / compressionLevel 9 / colors 64 → JDK ImageIO 只能写默认 PNG，激进 PNG
 *       分支简化为默认 PNG 编码（CC imageResizer.ts:687-691/:703-716 阶梯保留但参数降级）。</li>
 *   <li>WebP 解码：magic bytes 可探测 {@code image/webp}，但 ImageIO.read 返回 null → 走
 *       catch→fallback 原样直发（CC 用 sharp 可压缩 webp，大 webp 仍可能击穿预算，受控残留）。</li>
 * </ul>
 */
public final class ImageResizer {

    private static final Logger log = LoggerFactory.getLogger(ImageResizer.class);

    // CC constants/apiLimits.ts:22
    public static final int API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024;
    // CC constants/apiLimits.ts:29 — (5MB * 3) / 4
    public static final long IMAGE_TARGET_RAW_SIZE = (API_IMAGE_MAX_BASE64_SIZE * 3L) / 4L;
    // CC constants/apiLimits.ts:42-43
    public static final int IMAGE_MAX_WIDTH = 2000;
    public static final int IMAGE_MAX_HEIGHT = 2000;

    // CC FileReadTool.ts:1137 — 8 个 base64 字符 ≈ 1 token
    private static final double TOKEN_PER_BASE64_CHAR = 0.125;
    // CC imageResizer.ts:591 — maxBytes = maxBase64Chars * 0.75
    private static final double BYTES_PER_BASE64_CHAR = 0.75;

    private ImageResizer() {}

    /**
     * 确定性 token 估算 · 对齐 CC FileReadTool.ts:1137 {@code Math.ceil(base64.length * 0.125)}
     * —— 8 个 base64 字符 ≈ 1 token（无真实 tokenizer 的近似，与 CC 同款）。
     */
    public static int estimateTokens(String base64) {
        return (int) Math.ceil(base64.length() * TOKEN_PER_BASE64_CHAR);
    }

    /**
     * token 预算 → 字节预算 · 对齐 CC imageResizer.ts:590-591
     * {@code maxBase64Chars = floor(maxTokens/0.125); maxBytes = floor(maxBase64Chars * 0.75)}。
     * 两常量 0.125 / 0.75 逐字复刻。
     */
    public static long maxBytesForTokens(int maxTokens) {
        long maxBase64Chars = (long) Math.floor(maxTokens / TOKEN_PER_BASE64_CHAR);
        return (long) Math.floor(maxBase64Chars * BYTES_PER_BASE64_CHAR);
    }

    /**
     * 图片处理结果 · 对齐 CC {@code ImageResult.file}（FileReadTool.ts:774-782）：
     * base64 + mediaType(全 MIME {@code image/png}) + originalSize + dimensions(可 null)。
     */
    public record Result(String base64, String mediaType, long originalSize, ImageDimensions dimensions) {}

    /** 内部：处理后的 buffer + 短 mediaType（无 {@code image/} 前缀）+ dimensions。 */
    private record ResizedBuffer(byte[] buffer, String mediaType, ImageDimensions dimensions) {}

    /** 内部：激进压缩结果（CC CompressedImageResult imageResizer.ts:159-163）。 */
    private record Compressed(String base64, String mediaType, long originalSize) {}

    /**
     * 读图 + token 预算编排 · 对齐 CC FileReadTool.ts:1097-1183 {@code readImageWithTokenBudget}。
     *
     * <p>流程：读 buffer 一次 → originalSize==0 抛 {@code Image file is empty} →
     * magic-byte 探测真实格式 → 标准缩放 {@link #maybeResizeAndDownsampleImageBuffer} →
     * token 估算超预算则 {@link #compressImageBufferWithTokenLimit} 激进压缩 → 压缩失败
     * fallback 400x400 jpeg q20 → 再失败原样直发。
     *
     * @param imageBuffer 图片原始字节（CC 用 readFileBytes 一次读入，未传 maxBytes=全量）
     * @param maxTokens   token 预算（默认 FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS=25000）
     */
    public static Result readImageWithTokenBudget(byte[] imageBuffer, int maxTokens) {
        long originalSize = imageBuffer.length;
        if (originalSize == 0) {
            // CC FileReadTool.ts:1109-1111 throw new Error('Image file is empty')
            throw new ImageResizeError("Image file is empty");
        }

        // CC FileReadTool.ts:1113-1114
        String detectedMediaType = detectImageFormatFromBuffer(imageBuffer);
        String detectedFormat = detectedMediaType.substring("image/".length());

        // 标准缩放（CC :1118-1134 try maybeResize / catch ImageResizeError rethrow / 其余 fallback 原样）
        Result result;
        try {
            ResizedBuffer resized = maybeResizeAndDownsampleImageBuffer(imageBuffer, originalSize, detectedFormat);
            result = new Result(
                Base64.getEncoder().encodeToString(resized.buffer()),
                toFullMediaType(resized.mediaType()),
                originalSize,
                resized.dimensions());
        } catch (ImageResizeError e) {
            throw e;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ImageResizer: 标准缩放异常, 原样直发: cause={}", e.toString());
            }
            result = new Result(Base64.getEncoder().encodeToString(imageBuffer), detectedMediaType, originalSize, null);
        }

        // CC FileReadTool.ts:1137 确定性 token 估算（8 base64 字符 ≈ 1 token）
        int estimatedTokens = estimateTokens(result.base64());
        if (log.isDebugEnabled()) {
            log.debug("ImageResizer: token 估算={} (base64 长度={}, 预算={}) 超预算={}",
                estimatedTokens, result.base64().length(), maxTokens, estimatedTokens > maxTokens);
        }

        if (estimatedTokens > maxTokens) {
            // CC FileReadTool.ts:1138-1153 超预算激进压缩（同一 buffer，不重读）
            try {
                Compressed compressed = compressImageBufferWithTokenLimit(imageBuffer, maxTokens, detectedMediaType);
                return new Result(compressed.base64(), compressed.mediaType(), originalSize, null);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("ImageResizer: 激进压缩失败, 走 400x400 jpeg q20 fallback: cause={}", e.toString());
                }
                // CC FileReadTool.ts:1157-1174 fallback: sharp.resize(400,400,fit inside).jpeg(q20)
                try {
                    byte[] fallbackBuffer = resizeToJpeg(imageBuffer, 400, 400, 20);
                    return new Result(Base64.getEncoder().encodeToString(fallbackBuffer), "image/jpeg", originalSize, null);
                } catch (Exception error) {
                    if (log.isDebugEnabled()) {
                        log.debug("ImageResizer: 400x400 fallback 也失败, 原样直发: cause={}", error.toString());
                    }
                    return new Result(Base64.getEncoder().encodeToString(imageBuffer), detectedMediaType, originalSize, null);
                }
            }
        }

        return result;
    }

    /**
     * 标准缩放 + 采样 · 对齐 CC imageResizer.ts:169-433 {@code maybeResizeAndDownsampleImageBuffer}。
     *
     * <p>空 buffer 抛 ImageResizeError；ImageIO 解码失败（corrupt / 不支持的 webp）走 catch→fallback；
     * 原始尺寸 ≤ 3.75MB 且宽高 ≤ 2000 → 原样（附 display 尺寸）；否则按 CC 阶梯压缩 / 缩放。
     */
    private static ResizedBuffer maybeResizeAndDownsampleImageBuffer(byte[] imageBuffer, long originalSize, String ext) {
        if (imageBuffer.length == 0) {
            // CC imageResizer.ts:174-180
            throw new ImageResizeError("Image file is empty (0 bytes)");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBuffer));
            if (img == null) {
                // CC sharp 对 corrupt/不支持格式会 throw（"unable to determine image format"）→ 走 catch
                throw new IOException("Unable to determine image format");
            }

            // CC imageResizer.ts:186-188 mediaType = metadata.format ?? ext
            String normalizedMediaType = normalizeFormat(ext);

            int originalWidth = img.getWidth();
            int originalHeight = img.getHeight();
            int width = originalWidth;
            int height = originalHeight;

            // CC imageResizer.ts:212-227 原图直接可用 → 原样 + display 尺寸
            if (originalSize <= IMAGE_TARGET_RAW_SIZE && width <= IMAGE_MAX_WIDTH && height <= IMAGE_MAX_HEIGHT) {
                return new ResizedBuffer(imageBuffer, normalizedMediaType,
                    ImageDimensions.resized(originalWidth, originalHeight, width, height));
            }

            boolean needsDimensionResize = width > IMAGE_MAX_WIDTH || height > IMAGE_MAX_HEIGHT;
            boolean isPng = "png".equals(normalizedMediaType);

            // CC imageResizer.ts:235-275 尺寸不超但字节超限 → 先压缩（保留全分辨率）
            if (!needsDimensionResize && originalSize > IMAGE_TARGET_RAW_SIZE) {
                if (isPng) {
                    byte[] pngCompressed = toPng(img);
                    if (pngCompressed.length <= IMAGE_TARGET_RAW_SIZE) {
                        return new ResizedBuffer(pngCompressed, "png",
                            ImageDimensions.resized(originalWidth, originalHeight, width, height));
                    }
                }
                for (int quality : new int[]{80, 60, 40, 20}) {
                    byte[] compressedBuffer = toJpeg(img, quality);
                    if (compressedBuffer.length <= IMAGE_TARGET_RAW_SIZE) {
                        return new ResizedBuffer(compressedBuffer, "jpeg",
                            ImageDimensions.resized(originalWidth, originalHeight, width, height));
                    }
                }
                // 质量降级仍不足 → 落到 resize
            }

            // CC imageResizer.ts:278-286 尺寸约束（保宽高比）
            if (width > IMAGE_MAX_WIDTH) {
                height = (int) Math.round((height * IMAGE_MAX_WIDTH) / (double) width);
                width = IMAGE_MAX_WIDTH;
            }
            if (height > IMAGE_MAX_HEIGHT) {
                width = (int) Math.round((width * IMAGE_MAX_HEIGHT) / (double) height);
                height = IMAGE_MAX_HEIGHT;
            }

            if (log.isDebugEnabled()) {
                log.debug("ImageResizer: 缩放到 {}x{}", width, height);
            }
            byte[] resizedImageBuffer = resizePreserveFormat(img, width, height, normalizedMediaType);

            // CC imageResizer.ts:301-371 resize 后仍超限 → 逐级压缩
            if (resizedImageBuffer.length > IMAGE_TARGET_RAW_SIZE) {
                if (isPng) {
                    byte[] pngCompressed = toPng(resize(img, width, height));
                    if (pngCompressed.length <= IMAGE_TARGET_RAW_SIZE) {
                        return new ResizedBuffer(pngCompressed, "png",
                            ImageDimensions.resized(originalWidth, originalHeight, width, height));
                    }
                }
                for (int quality : new int[]{80, 60, 40, 20}) {
                    byte[] compressedBuffer = toJpeg(resize(img, width, height), quality);
                    if (compressedBuffer.length <= IMAGE_TARGET_RAW_SIZE) {
                        return new ResizedBuffer(compressedBuffer, "jpeg",
                            ImageDimensions.resized(originalWidth, originalHeight, width, height));
                    }
                }
                int smallerWidth = Math.min(width, 1000);
                int smallerHeight = (int) Math.round((height * smallerWidth) / (double) Math.max(width, 1));
                byte[] compressedBuffer = toJpeg(resize(img, smallerWidth, smallerHeight), 20);
                if (log.isDebugEnabled()) {
                    log.debug("ImageResizer: JPEG 压缩后大小: {} 字节", compressedBuffer.length);
                }
                return new ResizedBuffer(compressedBuffer, "jpeg",
                    ImageDimensions.resized(originalWidth, originalHeight, smallerWidth, smallerHeight));
            }

            return new ResizedBuffer(resizedImageBuffer, normalizedMediaType,
                ImageDimensions.resized(originalWidth, originalHeight, width, height));
        } catch (ImageResizeError e) {
            throw e;
        } catch (Exception e) {
            // CC imageResizer.ts:383-432 catch：magic-byte 探测 → base64 尺寸 ≤ 5MB 且非超尺寸 PNG 头 → 原样；否则抛
            if (log.isDebugEnabled()) {
                log.debug("ImageResizer: 图片标准缩放失败, 走 magic-byte fallback: cause={}", e.toString());
            }
            String detected = detectImageFormatFromBuffer(imageBuffer);
            String normalizedExt = detected.substring("image/".length());
            long base64Size = (long) Math.ceil((originalSize * 4) / 3.0);
            boolean overDim = imageBuffer.length >= 24
                && (imageBuffer[0] & 0xff) == 0x89 && (imageBuffer[1] & 0xff) == 0x50
                && (imageBuffer[2] & 0xff) == 0x4e && (imageBuffer[3] & 0xff) == 0x47
                && (readUInt32BE(imageBuffer, 16) > IMAGE_MAX_WIDTH || readUInt32BE(imageBuffer, 20) > IMAGE_MAX_HEIGHT);

            if (base64Size <= API_IMAGE_MAX_BASE64_SIZE && !overDim) {
                return new ResizedBuffer(imageBuffer, normalizedExt, null);
            }
            throw new ImageResizeError(overDim
                ? "Unable to resize image — dimensions exceed the " + IMAGE_MAX_WIDTH + "x" + IMAGE_MAX_HEIGHT
                    + "px limit and image processing failed. Please resize the image to reduce its pixel dimensions."
                : "Unable to resize image (" + originalSize + " bytes raw). The image exceeds the 5MB API limit and "
                    + "compression failed. Please resize the image manually or use a smaller image.");
        }
    }

    /**
     * 激进压缩 · 对齐 CC imageResizer.ts:583-594 {@code compressImageBufferWithTokenLimit}。
     * token↔字节换算两常量 0.125 / 0.75 逐字复刻。
     */
    private static Compressed compressImageBufferWithTokenLimit(byte[] imageBuffer, int maxTokens, String originalMediaType) {
        return compressImageBuffer(imageBuffer, maxBytesForTokens(maxTokens), originalMediaType);
    }

    /**
     * 多策略阶梯压缩 · 对齐 CC imageResizer.ts:498-577 {@code compressImageBuffer}。
     *
     * <p>originalSize ≤ maxBytes 原样 → tryProgressiveResizing(1.0/0.75/0.5/0.25) →
     * PNG tryPalettePNG(800x800) → tryJPEGConversion(600x600 q50) → 兜底 createUltraCompressedJPEG(400x400 q20)。
     */
    private static Compressed compressImageBuffer(byte[] imageBuffer, long maxBytes, String originalMediaType) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBuffer));
            if (img == null) {
                throw new IOException("Unable to determine image format");
            }
            // CC imageResizer.ts:508-510 format = metadata.format || normalizedFallback
            String format = normalizeFormat(detectImageFormatFromBuffer(imageBuffer).substring("image/".length()));
            long originalSize = imageBuffer.length;

            // CC imageResizer.ts:522-524 已达标 → 原样
            if (originalSize <= maxBytes) {
                return new Compressed(Base64.getEncoder().encodeToString(imageBuffer), toFullMediaType(format), originalSize);
            }

            // CC imageResizer.ts:527-530 tryProgressiveResizing
            Compressed progressive = tryProgressiveResizing(img, format, maxBytes, originalSize);
            if (progressive != null) {
                return progressive;
            }

            // CC imageResizer.ts:533-538 PNG tryPalettePNG
            if ("png".equals(format)) {
                Compressed palettized = tryPalettePNG(img, maxBytes, originalSize);
                if (palettized != null) {
                    return palettized;
                }
            }

            // CC imageResizer.ts:541-544 tryJPEGConversion(q50)
            Compressed jpegResult = tryJPEGConversion(img, 50, maxBytes, originalSize);
            if (jpegResult != null) {
                return jpegResult;
            }

            // CC imageResizer.ts:547 兜底 createUltraCompressedJPEG
            return createUltraCompressedJPEG(img, originalSize);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ImageResizer: 激进压缩异常: cause={}", e.toString());
            }
            // CC imageResizer.ts:561-569 原始字节仍达标 → 原样（magic-byte 探测格式）；否则抛
            if (imageBuffer.length <= maxBytes) {
                String detected = detectImageFormatFromBuffer(imageBuffer);
                return new Compressed(Base64.getEncoder().encodeToString(imageBuffer), detected, imageBuffer.length);
            }
            throw new ImageResizeError("Unable to compress image (" + imageBuffer.length
                + " bytes) to fit within " + maxBytes + " bytes. Please use a smaller image.");
        }
    }

    /**
     * 渐进缩放（保格式）· 对齐 CC imageResizer.ts:646-680 {@code tryProgressiveResizing}。
     * scalingFactors [1.0, 0.75, 0.5, 0.25]，fit inside + withoutEnlargement，格式优化按 format 分流。
     */
    private static Compressed tryProgressiveResizing(BufferedImage img, String format, long maxBytes, long originalSize) throws IOException {
        for (double scalingFactor : new double[]{1.0, 0.75, 0.5, 0.25}) {
            int newWidth = Math.max(1, (int) Math.round(img.getWidth() * scalingFactor));
            int newHeight = Math.max(1, (int) Math.round(img.getHeight() * scalingFactor));
            BufferedImage resized = resize(img, newWidth, newHeight);
            byte[] resizedBuffer = applyFormatOptimizations(resized, format);
            if (resizedBuffer.length <= maxBytes) {
                return new Compressed(Base64.getEncoder().encodeToString(resizedBuffer), toFullMediaType(format), originalSize);
            }
        }
        return null;
    }

    /**
     * 格式优化编码 · 对齐 CC imageResizer.ts:682-700 {@code applyFormatOptimizations}。
     * png→默认 PNG（JDK 无 compressionLevel9+palette）；jpeg→q80；gif→gif；其余(webp)→PNG 降级。
     */
    private static byte[] applyFormatOptimizations(BufferedImage img, String format) throws IOException {
        switch (format) {
            case "png":
                return toPng(img);
            case "jpeg":
            case "jpg":
                return toJpeg(img, 80);
            case "gif":
                ByteArrayOutputStream gifOut = new ByteArrayOutputStream();
                ImageIO.write(img, "gif", gifOut);
                return gifOut.toByteArray();
            default:
                return toPng(img);
        }
    }

    /**
     * PNG 调色板优化 · 对齐 CC imageResizer.ts:702-723 {@code tryPalettePNG}（800x800）。
     * JDK 无法 colors 64 调色板，退化为默认 PNG 编码。
     */
    private static Compressed tryPalettePNG(BufferedImage img, long maxBytes, long originalSize) throws IOException {
        BufferedImage resized = resize(img, 800, 800);
        byte[] palettePng = toPng(resized);
        if (palettePng.length <= maxBytes) {
            return new Compressed(Base64.getEncoder().encodeToString(palettePng), "image/png", originalSize);
        }
        return null;
    }

    /**
     * JPEG 转换 · 对齐 CC imageResizer.ts:725-743 {@code tryJPEGConversion}（600x600 q50）。
     */
    private static Compressed tryJPEGConversion(BufferedImage img, int quality, long maxBytes, long originalSize) throws IOException {
        BufferedImage resized = resize(img, 600, 600);
        byte[] jpegBuffer = toJpeg(resized, quality);
        if (jpegBuffer.length <= maxBytes) {
            return new Compressed(Base64.getEncoder().encodeToString(jpegBuffer), "image/jpeg", originalSize);
        }
        return null;
    }

    /**
     * 兜底超激进 JPEG · 对齐 CC imageResizer.ts:745-762 {@code createUltraCompressedJPEG}（400x400 q20）。
     */
    private static Compressed createUltraCompressedJPEG(BufferedImage img, long originalSize) throws IOException {
        BufferedImage resized = resize(img, 400, 400);
        byte[] ultra = toJpeg(resized, 20);
        return new Compressed(Base64.getEncoder().encodeToString(ultra), "image/jpeg", originalSize);
    }

    /**
     * magic-byte 格式探测 · 对齐 CC imageResizer.ts:769-812 {@code detectImageFormatFromBuffer}。
     * PNG 0x89 50 4E 47 / JPEG FF D8 FF / GIF 47 49 46 / WEBP RIFF+WEBP，默认 image/png。
     */
    public static String detectImageFormatFromBuffer(byte[] buffer) {
        if (buffer.length < 4) {
            return "image/png";
        }
        int b0 = buffer[0] & 0xff, b1 = buffer[1] & 0xff, b2 = buffer[2] & 0xff, b3 = buffer[3] & 0xff;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47) {
            return "image/png";
        }
        if (b0 == 0xff && b1 == 0xd8 && b2 == 0xff) {
            return "image/jpeg";
        }
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) {
            return "image/gif";
        }
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
            if (buffer.length >= 12 && (buffer[8] & 0xff) == 0x57 && (buffer[9] & 0xff) == 0x45
                && (buffer[10] & 0xff) == 0x42 && (buffer[11] & 0xff) == 0x50) {
                return "image/webp";
            }
        }
        return "image/png";
    }

    /** 短格式归一化：jpg→jpeg。 */
    private static String normalizeFormat(String fmt) {
        return "jpg".equals(fmt) ? "jpeg" : fmt;
    }

    /** 短格式 → 全 MIME（{@code png} → {@code image/png}）。 */
    private static String toFullMediaType(String shortFormat) {
        return "image/" + shortFormat;
    }

    /** 大端无符号 32 位读取（CC Buffer.readUInt32BE，用于 PNG IHDR 尺寸探测）。 */
    private static long readUInt32BE(byte[] buf, int off) {
        return ((buf[off] & 0xffL) << 24) | ((buf[off + 1] & 0xffL) << 16) | ((buf[off + 2] & 0xffL) << 8) | (buf[off + 3] & 0xffL);
    }

    /** 缩放（fit inside + withoutEnlargement）· 对齐 CC sharp resize 默认。 */
    private static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
        double scale = Math.min(
            Math.min(1.0, targetW / (double) src.getWidth()),
            targetH / (double) src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(w, h, type);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 缩放后保格式编码（CC imageResizer.ts:293-298 resize().toBuffer() 无显式格式）。 */
    private static byte[] resizePreserveFormat(BufferedImage img, int w, int h, String format) throws IOException {
        BufferedImage resized = resize(img, w, h);
        switch (format) {
            case "jpeg":
            case "jpg":
                return toJpeg(resized, 80);
            case "gif":
                ByteArrayOutputStream gifOut = new ByteArrayOutputStream();
                ImageIO.write(resized, "gif", gifOut);
                return gifOut.toByteArray();
            case "png":
            default:
                return toPng(resized);
        }
    }

    /** 编码 JPEG（指定质量）· JDK ImageWriter 等价 sharp jpeg({quality})。 */
    private static byte[] toJpeg(BufferedImage img, int quality) throws IOException {
        BufferedImage flattened = flattenAlpha(img);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(flattened, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** 编码 PNG（默认压缩）· JDK ImageIO 无 compressionLevel/palette 参数。 */
    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** JPEG 不支持 alpha，透明像素合成白底。 */
    private static BufferedImage flattenAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** 400x400 jpeg q20 fallback（CC FileReadTool.ts:1166-1172 二次兜底）。 */
    private static byte[] resizeToJpeg(byte[] imageBuffer, int w, int h, int quality) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBuffer));
        if (img == null) {
            throw new IOException("Unable to determine image format");
        }
        return toJpeg(resize(img, w, h), quality);
    }

    /**
     * MCP prompt 结果 image 分支缩放结果 · 对齐 CC {@code ResizeResult.buffer/mediaType}
     * （imageResizer.ts:160-163；CC client.ts:2516-2519 用 base64 + {@code image/${mediaType}}）。
     *
     * @param base64    resize 后图片 base64（CC {@code resized.buffer.toString('base64')} client.ts:2516）
     * @param mediaType 全 MIME（{@code image/png} / {@code image/jpeg} 等，CC {@code image/${resized.mediaType}}）
     */
    public record ResizedMcpImage(String base64, String mediaType) {}

    /**
     * MCP prompt 结果 image 分支标准缩放 · 对齐 CC client.ts:2505-2511
     * {@code maybeResizeAndDownsampleImageBuffer(Buffer.from(data,'base64'), buffer.length, ext)}
     * （imageResizer.ts:169-433 标准缩放）。注意：只走标准缩放（无 token 预算激进压缩）——
     * CC transformResultContent image 分支（:2503-2523）与 resource-blob-image 分支（:2535-2563）
     * 均只调 {@code maybeResizeAndDownsampleImageBuffer}，生产路径无 readImageWithTokenBudget。
     *
     * <p>空 buffer / 超限且处理失败 → {@link ImageResizeError} 上抛（CC imageResizer.ts:174-180/:383-432
     * throw 语义），由调用方 executePrompt catch 统一 fail-loud。
     *
     * @param imageBuffer 图片原始字节（base64 解码后；CC Buffer.from(data,'base64') lenient，
     *                    Java 严格解码由调用方先行）
     * @param mimeType    MCP 返回的 mimeType（CC {@code resultContent.mimeType?.split('/')[1] || 'png'}）
     * @return resize 后 base64 + 全 MIME（{@code image/png} 等）
     */
    public static ResizedMcpImage resizeMcpImage(byte[] imageBuffer, String mimeType) {
        String ext = "png";
        if (mimeType != null && !mimeType.isBlank()) {
            int slash = mimeType.indexOf('/');
            if (slash >= 0 && slash + 1 < mimeType.length()) {
                ext = mimeType.substring(slash + 1);
            }
        }
        ResizedBuffer resized = maybeResizeAndDownsampleImageBuffer(imageBuffer, imageBuffer.length, ext);
        return new ResizedMcpImage(Base64.getEncoder().encodeToString(resized.buffer()), "image/" + resized.mediaType());
    }
}
