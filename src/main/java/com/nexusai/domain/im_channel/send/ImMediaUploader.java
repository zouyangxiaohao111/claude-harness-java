package com.nexusai.domain.im_channel.send;

import java.nio.file.Path;

/**
 * 「把本地文件上传到这个平台，拿到能发消息的引用」的策略接口（每平台一个实现）。
 *
 * <h2>为什么上传和发送分成两个策略</h2>
 * 上传（拿 image_key / media_id / CDN 描述）和发送（把 key 放进消息体）是**两次独立的
 * HTTP 往返**，失败语义也不同：上传失败 → 这条附件发不出去，但文本仍该发；
 * 发送失败 → 整条消息都没了。分开之后模板方法可以做到「附件传失败就不要把它塞进消息」，
 * 而不是让整条消息因为一张图挂掉。
 *
 * <h2>一个必须知道的约束：同一文件发多个平台要分别上传</h2>
 * 飞书的 {@code file_key}、企微的 {@code media_id} 互不通用（都绑定各自租户）。
 * 所以 {@code attachments × targets} 会触发**多次上传** —— 这是平台限制，不是实现缺陷。
 * 代价：同一个 5MB 文件发给 3 个平台 = 传 3 次。缓存按 (instanceId, 文件哈希) 做是后续优化，
 * 本期不做（避免引入"文件内容变了但哈希没变"之类的新坑）。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>文件不存在 / 读失败 / 超过平台上限 ⇒ 抛 {@link ImSendException}（带人能懂的原因）</li>
 *   <li>⛔ 不把文件内容或平台返回的 aes_key 写日志</li>
 * </ul>
 */
public interface ImMediaUploader {

    /** 平台标识，与 {@link ImSendStrategy#platform()} 对齐。 */
    String platform();

    /** 本平台是否支持这种媒体（不查库、不发请求的静态声明）。 */
    boolean supports(MediaKind kind);

    /**
     * 上传一个文件。
     *
     * @param ctx  上下文（含已解密的凭证；用 instanceId 做缓存/日志定位）
     * @param file 本地文件（调用方保证存在，但实现仍要兜住 IO 异常）
     * @param kind 媒体类型（调用方已归一到 {@link MediaKind#forUpload()} 的意义上；IMAGE / FILE）
     * @return 平台侧引用（飞书 key / 企微 media_id / 微信 CDN 描述）
     * @throws ImSendException 上传失败（网络 / 平台拒绝 / 文件不可读 / 超限）
     */
    ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind) throws ImSendException;
}
