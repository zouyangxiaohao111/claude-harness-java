package com.nexusai.domain.im_channel.send;

/**
 * 附件的媒体类型（**故意只三种**）。
 *
 * <h2>为什么不做 audio / video</h2>
 * 三个平台对音视频的差异太大（企微要 media_id + 标题/描述、飞书要 file_key + 时长、
 * 微信要 CDN + AES），而实际投递场景里 99% 是**图片和文档**。
 * 少一个类型 = 少三条分支 = 少一批踩坑点。需要时再加，不影响既有调用。
 *
 * <p>{@code UNKNOWN} 存在的意义：文件扩展名不认识时**不要**猜成图片 ——
 * 猜错会让平台按图片解析二进制，直接报错或发出去打不开。按 {@code FILE} 发即可。
 */
public enum MediaKind {

    /** 图片（png / jpg / gif / webp…）。 */
    IMAGE,

    /** 其它文件（pdf / docx / xlsx / zip…）。 */
    FILE,

    /** 扩展名无法判断 —— 按 {@link #FILE} 处理，但保留原值便于日志区分。 */
    UNKNOWN;

    /** 发给平台时统一用这个（UNKNOWN 归到 FILE）。 */
    public MediaKind forUpload() {
        return this == UNKNOWN ? FILE : this;
    }

    /** 按扩展名推断（不读文件内容：读内容要 IO，判断错了代价也不高）。 */
    public static MediaKind fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UNKNOWN;
        }
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return UNKNOWN;
        }
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> IMAGE;
            case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                 "txt", "md", "csv", "zip", "rar", "7z", "json", "xml", "log" -> FILE;
            default -> UNKNOWN;
        };
    }
}
