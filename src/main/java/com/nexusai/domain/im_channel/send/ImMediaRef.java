package com.nexusai.domain.im_channel.send;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 上传成功后拿到的「平台侧媒体引用」。
 *
 * <h2>为什么是 {@code JsonNode} 而不是统一成 {@code String key}</h2>
 * 三个平台拿到的东西形态完全不同：
 * <ul>
 *   <li><b>飞书</b>：{@code {"kind":"image","key":"img_v2_xxx"}} —— 发消息时塞 {@code image_key}/{@code file_key}</li>
 *   <li><b>企微</b>：{@code {"kind":"file","key":"MEDIA_ID"}} —— 发消息时塞 {@code media_id}</li>
 *   <li><b>微信</b>：不是 key，而是<b>整套 CDN 下载描述</b>
 *       （{@code {"url","aes_key","file_size","file_md5"}}），要原样放进 {@code item_list}</li>
 * </ul>
 * 硬统一成 String 会逼微信把结构序列化成字符串再在发送侧反序列化 —— 多一次转换、多一处出错点。
 *
 * <p>⛔ {@code raw} 里可能有 AES key 之类的敏感值：不要原样写日志（只会打 kind 与大小）。
 *
 * @param kind 媒体类型（IMAGE / FILE）
 * @param raw  平台原始结构（发送策略按自己平台的约定取值）
 */
public record ImMediaRef(MediaKind kind, JsonNode raw) {

    public ImMediaRef {
        if (kind == null) {
            kind = MediaKind.FILE;
        }
    }

    /** 方便飞书/企微这类「就是一个 key」的平台取值；微信这种结构化的不要用。 */
    public String key() {
        return raw == null ? null : raw.path("key").asText(null);
    }

    @Override
    public String toString() {
        // ⛔ 不打印 raw：微信的 raw 里有 aes_key
        return "ImMediaRef[kind=" + kind + "]";
    }
}
