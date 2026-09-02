package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.tool.impl.ImageResizer;
import com.nexusai.infra.util.ImageResizeError;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 媒体限额闸门 · 对齐 CC 三处限额逻辑（constants/apiLimits.ts + imageValidation.ts + claude.ts）。
 *
 * <p>两道闸：
 * <ul>
 *   <li><b>单图 base64 硬校验</b>：base64 长度 &gt; {@link MediaLimitConstants#API_IMAGE_MAX_BASE64_SIZE}
 *       （5MB，Anthropic API 硬限制，apiLimits.ts:19）→ 经 {@link ImageResizer}（Java 等价
 *       imageResizer.ts:445-481 {@code maybeResizeAndDownsampleImageBlock} 的 java.awt.ImageIO 实现）
 *       压缩降采样；压缩后 base64 仍超 5MB 或压缩失败 → 拒绝该图并 warn
 *       （对齐 imageValidation.ts:90-102 {@code validateImagesForAPI} 超限抛错语义，此处落为
 *       丢弃 + warn 而非抛错中断整个请求，避免单图超限打挂整轮对话）。</li>
 *   <li><b>单请求数量裁剪</b>：媒体项 &gt; {@link MediaLimitConstants#API_MAX_MEDIA_PER_REQUEST}
 *       （100，apiLimits.ts:94）→ 裁剪保留<b>最新</b> 100 项（对齐 claude.ts:956-1015
 *       {@code stripExcessMediaItems}「silently drop the oldest media items」——丢最早保最新）。</li>
 * </ul>
 *
 * <p><b>调用入口</b>：{@code ChatService.processUserMessage} 消费附件处（A1 {@code resolveAttachments}
 * 补全 base64 后、透传 {@code RunRequest.attachments} 前）调用 {@link #guard(List)}。
 *
 * <p><b>与非图片附件的关系</b>：视频/音频本期走惰性外挂工具路由（方案定稿 P1 登记），不构成
 * base64 image content block，故<b>大小门</b>仅作用于 image 附件；<b>数量门</b>作用于全部附件
 * （CC 按「image + pdf」媒体计数，claude.ts:964 {@code isMedia}）。
 *
 * <p><b>顺序</b>：先数量裁剪后逐图校验 —— 与 CC 一致（claude.ts:1306-1316 stripExcessMediaItems
 * 在 API 调用前裁剪，imageValidation.ts validateImagesForAPI 在其后兜底）；裁剪后再校验减少无谓压缩开销。
 */
public final class MediaLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(MediaLimitGuard.class);

    private MediaLimitGuard() {
        // 工具类不可实例化
    }

    /**
     * 执行媒体限额门控：先数量裁剪（&gt;100 保最新），后逐图大小校验（&gt;5MB 压缩，失败拒绝）。
     *
     * @param attachments 已解析附件列表（A1 {@code resolveAttachments} 输出，含 base64 + mediaType）；
     *                    null/空 → 空列表
     * @return 门控后附件列表（数量 ≤ 100，逐图 base64 ≤ 5MB；超限且压缩失败项已剔除）
     */
    public static List<AttachmentRequest> guard(List<AttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<AttachmentRequest> afterTrim = trimToMediaLimit(attachments);
        List<AttachmentRequest> guarded = new ArrayList<>(afterTrim.size());
        for (AttachmentRequest att : afterTrim) {
            AttachmentRequest processed = gateSingleImage(att);
            if (processed != null) {
                guarded.add(processed);
            }
        }
        return guarded;
    }

    /**
     * 数量裁剪 · 对齐 CC claude.ts:956-1015 {@code stripExcessMediaItems}：媒体项 &gt; 100 时
     * 丢<b>最早</b>，保留最新 100 项。本列表按请求序 = 插入序（contentId 自增 → 列表尾部为最新）。
     *
     * @param attachments 附件列表（size 可 > 100）
     * @return 裁剪后列表（size ≤ 100）；未超限原样返回
     */
    private static List<AttachmentRequest> trimToMediaLimit(List<AttachmentRequest> attachments) {
        int limit = MediaLimitConstants.API_MAX_MEDIA_PER_REQUEST;
        int size = attachments.size();
        if (size <= limit) {
            return attachments;
        }
        int drop = size - limit;
        List<AttachmentRequest> kept = new ArrayList<>(attachments.subList(drop, size));
        log.warn("媒体限额：请求媒体项 {} 超过单请求上限 {}，裁剪保留最新 {} 项，丢弃最早 {} 项（对齐 CC claude.ts:956 stripExcessMediaItems 丢最早保最新）",
            size, limit, kept.size(), drop);
        return kept;
    }

    /**
     * 单图大小门控 · 对齐 CC imageValidation.ts:90-102（5MB base64 硬校验）+ imageResizer.ts:445-481
     * （超限压缩）。仅作用于 image 附件（base64 image content block 受 5MB API 硬限制）；
     * 非 image 附件（视频/音频/file）原样通过（工具路由，无 base64 块）。
     *
     * @param att 单条附件（base64 已由 A1 补全）
     * @return 门控后附件（超限且压缩失败 / 非法 base64 → null，调用方剔除）
     */
    private static AttachmentRequest gateSingleImage(AttachmentRequest att) {
        if (!isImage(att)) {
            return att;
        }
        String base64 = att.base64();
        if (base64 == null || base64.isBlank()) {
            return att;
        }
        // 未超 5MB base64 硬限制 → 原样直发（CC imageResizer.ts:212-227 pass-through 语义）
        if (base64.length() <= MediaLimitConstants.API_IMAGE_MAX_BASE64_SIZE) {
            return att;
        }
        // 超 5MB → 尝试压缩（Java 等价 CC imageResizer.ts:445-481，java.awt.ImageIO resize）
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            ImageResizer.ResizedMcpImage resized = ImageResizer.resizeMcpImage(raw, att.mediaType());
            if (resized.base64().length() <= MediaLimitConstants.API_IMAGE_MAX_BASE64_SIZE) {
                log.warn("图片附件 base64 超限（{} 字符 &gt; 5MB），已压缩为 {} 后直发：contentId={} filename={}",
                    base64.length(), resized.mediaType(), att.contentId(), att.filename());
                return new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                    resized.mediaType(), resized.base64(), att.path());
            }
            log.warn("图片附件 base64 超限（{} 字符 &gt; 5MB），压缩后仍超限，拒绝该图：contentId={} filename={}",
                base64.length(), att.contentId(), att.filename());
            return null;
        } catch (ImageResizeError e) {
            log.warn("图片附件 base64 超限（{} 字符 &gt; 5MB），压缩失败，拒绝该图：contentId={} filename={} 原因={}",
                base64.length(), att.contentId(), att.filename(), e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("图片附件 base64 非法，拒绝该图：contentId={} filename={} 原因={}",
                att.contentId(), att.filename(), e.getMessage());
            return null;
        }
    }

    /** 是否为 image 附件（type=image 或 mediaType=image/*）。 */
    private static boolean isImage(AttachmentRequest att) {
        String type = att.type();
        if (type != null && "image".equalsIgnoreCase(type)) {
            return true;
        }
        String mediaType = att.mediaType();
        return mediaType != null && mediaType.startsWith("image/");
    }
}
