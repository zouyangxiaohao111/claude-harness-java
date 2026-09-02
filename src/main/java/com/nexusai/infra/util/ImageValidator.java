package com.nexusai.infra.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ImageValidator · 对齐 CC utils/imageValidation.ts.
 *
 * <p>L1 语义: API 边界 image base64 大小校验 — 检测所有 base64 image block 是否超过 API 5MB 限制。
 * <ul>
 *   <li>{@link #validateImagesForAPI(List, long)} — throws ImageSizeError if any > limit
 *       （Java 真实数据形态为 {@code List<ChatMessageDto>}，image 块在 user 消息的 contentBlocks JsonNode）</li>
 *   <li>{@link #ImageSizeError} — exception with list of oversized images</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: validateImagesForAPI(List&lt;ChatMessageDto&gt;, long) + ImageSizeError class + OversizedImage record</li>
 *   <li><b>A2 Golden Trace</b>: image.type=image source.type=base64 data=string → true;mixed messages (user/assistant) 仅 user 校验;throw ImageSizeError with list of sizes;maxSize from API limit</li>
 *   <li><b>A3 副作用</b>: throw ImageSizeError</li>
 *   <li><b>A4 边界</b>: empty messages→no-op;non-user messages skipped;non-image blocks skipped;null block→false</li>
 *   <li><b>A5 业务场景</b>: 5MB image → throw at API boundary before send;prevent corruption</li>
 * </ul>
 *
 * <p>L3 升级: TS Message → Java {@link ChatMessageDto};TS content 数组 → Java
 * {@code contentBlocks}（JsonNode 数组）;TS Error extends → Java extends RuntimeException。
 * <p>[ER-IMP-12] 删 Map 影子路径（List&lt;?&gt; 版 + isBase64ImageBlock(Object)）：CC 输入是
 * typed Message，Java 真实数据形态是 List&lt;ChatMessageDto&gt;（image 在 contentBlocks JsonNode），
 * Map 版无数据源、全仓零调用。buildMessage 改 CC {@code formatFileSize} 人类可读（非裸字节）。
 */
public final class ImageValidator {

    /** 对齐 CC apiLimits.ts:22 API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024（base64 字符串长度，非解码字节）。 */
    public static final long API_IMAGE_MAX_BASE64_SIZE = 5L * 1024L * 1024L;

    private static final Logger log = LoggerFactory.getLogger(ImageValidator.class);

    public record OversizedImage(int index, long sizeBytes) {}

    public static class ImageSizeError extends RuntimeException {
        private final List<OversizedImage> oversizedImages;
        private final long maxSize;

        public ImageSizeError(List<OversizedImage> oversizedImages, long maxSize) {
            super(buildMessage(oversizedImages, maxSize));
            this.oversizedImages = oversizedImages;
            this.maxSize = maxSize;
        }

        public List<OversizedImage> oversizedImages() { return oversizedImages; }
        public long maxSize() { return maxSize; }
    }

    /** CC imageValidation.ts:20-31 ImageSizeError message · formatFileSize 人类可读（format.ts:9-20）。 */
    private static String buildMessage(List<OversizedImage> imgs, long maxSize) {
        if (imgs == null || imgs.isEmpty()) return "No oversized images";
        if (imgs.size() == 1) {
            OversizedImage first = imgs.get(0);
            return "Image base64 size (" + formatFileSize(first.sizeBytes()) + ") exceeds API limit ("
                + formatFileSize(maxSize) + "). Please resize the image before sending.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(imgs.size()).append(" images exceed the API limit (").append(formatFileSize(maxSize)).append("): ");
        for (int i = 0; i < imgs.size(); i++) {
            if (i > 0) sb.append(", ");
            OversizedImage img = imgs.get(i);
            sb.append("Image ").append(img.index()).append(": ").append(formatFileSize(img.sizeBytes()));
        }
        sb.append(". Please resize these images before sending.");
        return sb.toString();
    }

    /**
     * CC format.ts:9-20 formatFileSize · KB/MB/GB 一位小数去 .0，无空格。
     * <pre>formatFileSize(1536) → "1.5KB"</pre>
     */
    private static String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            return trimTrailingZero(String.format(Locale.ROOT, "%.1f", kb)) + "KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return trimTrailingZero(String.format(Locale.ROOT, "%.1f", mb)) + "MB";
        }
        double gb = mb / 1024.0;
        return trimTrailingZero(String.format(Locale.ROOT, "%.1f", gb)) + "GB";
    }

    /** CC format.ts:9-20 `.toFixed(1).replace(/\.0$/, '')` · 去尾 .0。 */
    private static String trimTrailingZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private ImageValidator() {}

    /** Type guard: 是否为 base64 image block · 对齐 CC imageValidation.ts:40-49 isBase64ImageBlock。 */
    private static boolean isBase64ImageBlock(JsonNode block) {
        if (block == null || !block.isObject()) return false;
        if (!"image".equals(block.path("type").asText())) return false;
        JsonNode source = block.path("source");
        if (!source.isObject()) return false;
        return "base64".equals(source.path("type").asText())
            && source.path("data").isTextual();
    }

    /**
     * Validate all user-message image blocks in {@code messages} are within
     * {@code maxBase64Size} bytes (raw base64 length). 对齐 CC imageValidation.ts:65-104
     * validateImagesForAPI — 仅查 user 消息，content 数组内 type=image 且 source.type=base64 的
     * block，data 字符串长度（base64 编码长度，非解码字节）> limit → ImageSizeError。
     *
     * @param messages     ChatMessageDto 列表（仅 role=user 处理）
     * @param maxBase64Size CC: API_IMAGE_MAX_BASE64_SIZE = 5MB
     * @throws ImageSizeError if any image exceeds the limit
     */
    public static void validateImagesForAPI(List<ChatMessageDto> messages, long maxBase64Size) {
        List<OversizedImage> oversized = new ArrayList<>();
        int imageIndex = 0;
        if (messages == null) {
            return;
        }
        for (ChatMessageDto msg : messages) {
            // CC imageValidation.ts:76 only check user messages
            if (msg == null || msg.role() != Role.user) continue;
            // CC imageValidation.ts:82 content 数组（Java: contentBlocks JsonNode）
            List<?> blocks = msg.contentBlocks();
            if (blocks == null || blocks.isEmpty()) continue;
            for (Object blockObj : blocks) {
                if (!(blockObj instanceof JsonNode block)) continue;
                if (!isBase64ImageBlock(block)) continue;
                imageIndex++;
                JsonNode data = block.path("source").path("data");
                long size = data.isTextual() ? data.asText().length() : 0;
                if (size > maxBase64Size) {
                    // [✗-3 · P-25 轮] 超限抛错前 warn（等价 CC imageValidation.ts:91-94
                    //   logEvent('tengu_image_api_validation_failed', {base64_size_bytes, max_bytes})）·
                    //   每条超限 image 一条，与 CC 逐 block 打点一致
                    log.warn("ImageValidator: tengu_image_api_validation_failed 等价 "
                            + "{{base64_size_bytes={}, max_bytes={}}} · CC imageValidation.ts:91-94",
                        size, maxBase64Size);
                    oversized.add(new OversizedImage(imageIndex, size));
                }
            }
        }
        if (!oversized.isEmpty()) {
            throw new ImageSizeError(oversized, maxBase64Size);
        }
    }
}
