package com.nexusai.domain.im_channel.send;

import java.nio.file.Path;

/**
 * 要发出去的一个附件（本地文件 + 类型）。
 *
 * @param path     本地文件路径（必须存在；不存在由上传器抛 {@code ImSendException}）
 * @param kind     媒体类型；null 时按文件名推断
 * @param fileName 展示用文件名（可空 ⇒ 取 {@code path.getFileName()}）
 */
public record ImSendAttachment(Path path, MediaKind kind, String fileName) {

    public ImSendAttachment {
        if (path == null) {
            throw new IllegalArgumentException("附件路径不能为空");
        }
        // kind 允许 null（调用方懒得推断），由 name() 兜底；fileName 同理
    }

    /** 实际使用的类型（null 名按扩展名推断）。 */
    public MediaKind kindOrDefault() {
        return kind != null ? kind : MediaKind.fromFileName(name());
    }

    /** 实际使用的文件名。 */
    public String name() {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        Path f = path.getFileName();
        return f == null ? "file" : f.toString();
    }

    public static ImSendAttachment of(Path path) {
        return new ImSendAttachment(path, null, null);
    }

    public static ImSendAttachment of(Path path, MediaKind kind) {
        return new ImSendAttachment(path, kind, null);
    }
}
