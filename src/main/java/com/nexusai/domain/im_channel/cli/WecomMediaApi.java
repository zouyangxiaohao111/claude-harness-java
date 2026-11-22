package com.nexusai.domain.im_channel.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.MediaKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 企业微信媒体（图片 / 文件）的**上传**与**发送**（纯 HTTP，走 {@link WecomCliClient}）。
 *
 * <h2>🔴 协议出处（逐字核对，不靠记忆）</h2>
 * <p>协议来自官方 CLI 的服务目录 schema（{@code service_media.json} / {@code service_message.json}，
 * 即 CLI 本地缓存的 {@code ~/.config/wecom/cache/service_*.json}），
 * 以及开源实现 {@code github.com/WecomTeam/wecom-cli}。
 * <pre>
 * ① 上传：POST /cli/media/upload
 *    业务体（会被 {@link WecomCliClient} 包成 payload 信封）：
 *    {"file_path":"&lt;文件内容的 base64&gt;","type":"image"|"file"}
 *    → result = {"type":"image","media_id":"mci...","created_at":"YYYY-MM-DD HH:MM:SS"}
 *
 * ② 发媒体：POST /cli/message/aibot/send
 *    业务体：{"chat_id":"&lt;会话 id&gt;","msg_type":"image"|"file",
 *             "image"|"file":{"media_id":"&lt;上一步拿到的 media_id&gt;"}}
 *    → {"success":true}
 * </pre>
 *
 * <h3>为什么上传体里<b>没有</b> file_name</h3>
 * <p>schema {@code UploadFileReq} 只有两个字段：{@code file_path}（带
 * {@code x-wecom-octet-stream} 标记 = 文件二进制）与 {@code type}。
 * <b>没有 {@code file_name} 字段</b> —— 文件名的展示由平台侧按上传时的原始文件名决定，
 * 本端不再另传（schema {@code AibotFileContent.media_id} 原文：「文件名取上传时的原始文件名」）。
 * ⛔ 凭空加一个 {@code file_name} 属于自造字段，平台不认。
 *
 * <h3>为什么 {@code file_path} 放的是 base64 而不是本地路径</h3>
 * <p>官方 CLI 是本地进程，{@code file_path} 由它自己读文件再上传；
 * 本仓是纯 Java 直连（没有 CLI 子进程），而真机取证（<b>PIT-WC-034</b>）确认
 * 该端点走 <b>payload 信封</b>，文件字节以 <b>base64 字符串</b>放进 {@code file_path} 即可，
 * 不需要 multipart。
 *
 * <h3>为什么发媒体走 {@code /cli/message/aibot/send} 而不是 {@code /cli/message/send}</h3>
 * <p>schema {@code SendMessageReq}（= {@code /message/send}）的 {@code msg_type} enum
 * <b>只有 {@code text}</b>；带媒体的 {@code msg_type=image|file} 只存在于
 * {@code SendAibotMessageReq}（= {@code /message/aibot/send}，机器人身份）。
 * ⇒ 文本走 {@code message/send}、媒体走 {@code message/aibot/send}，<b>不是同一个端点</b>。
 *
 * <h3>两段式（PIT-WC-025）</h3>
 * <p>媒体消息**不能一步到位**：先上传拿 {@code media_id}，再把 {@code media_id} 放进消息体。
 *
 * <h3>大小上限</h3>
 * <p>schema {@code media.upload} 的 {@code range_size} = {@code 104857600} = 100 MiB，
 * 即单文件上限。超限**明确报错**，⛔ 不静默截断（截断后的文件对收件人是坏的，
 * 还不如让调用方知道「这个文件太大」）。
 *
 * <h2>安全</h2>
 * ⛔ botSecret / CLI token / <b>文件内容</b>一律不进日志、不进异常消息。
 * 日志里只出现媒体类型与字节数。
 *
 * <h2>失败语义</h2>
 * <p>平台 / 业务失败统一抛 {@link ImSendException}，错误码映射集中在
 * {@link WecomCliClient#failureOf}（853004 token 失效 / 853008 不在最近会话 / -14 登录态失效），
 * 本类<b>不另写一套</b>映射。
 */
@Component
public class WecomMediaApi {

    private static final Logger log = LoggerFactory.getLogger(WecomMediaApi.class);

    /** 上传端点（schema {@code media.upload} ⇒ {@code /media/upload}）。 */
    public static final String PATH_UPLOAD = "/cli/media/upload";

    /** 机器人身份发消息端点（schema {@code message.aibot.send} ⇒ {@code /message/aibot/send}）。 */
    public static final String PATH_AIBOT_SEND = "/cli/message/aibot/send";

    /** 上传与发送用的媒体类型字面量（schema enum 逐字，小写）。 */
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_FILE = "file";

    /** 单文件上传上限：schema {@code media.upload} 的 {@code range_size} = 100 MiB。 */
    public static final long MAX_UPLOAD_BYTES = 104_857_600L;

    private final WecomCliClient client;
    private final ObjectMapper json = new ObjectMapper();

    /** 构造注入（不 new 客户端：HttpClient 要复用）。 */
    public WecomMediaApi(WecomCliClient client) {
        if (client == null) {
            throw new IllegalArgumentException("WecomCliClient 必填");
        }
        this.client = client;
    }

    // ───────────────────────── 1. media.upload ─────────────────────────

    /**
     * 上传一个媒体文件，返回 {@code media_id}（第二段式发送要用）。
     *
     * <p>业务体逐字：{@code {"file_path":"<base64(文件内容)>","type":"image"|"file"}}。
     *
     * @param token CLI token（由 {@link WecomCliTokenService} 换得）
     * @param file  本地文件（必须存在、非空、≤ {@value #MAX_UPLOAD_BYTES}）
     * @param kind  媒体类型（{@link MediaKind#IMAGE} / {@link MediaKind#FILE}；null 视作 file）
     * @return 平台侧 {@code media_id}
     * @throws ImSendException 文件不可读 / 超限（本地校验，不发请求）或平台失败
     */
    public String upload(String token, Path file, MediaKind kind) throws ImSendException {
        if (file == null) {
            throw new ImSendException("IM_PARAM_INVALID", "企微媒体文件路径为空");
        }
        if (!Files.isRegularFile(file)) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "企微媒体文件不存在或不是普通文件：" + fileName(file));
        }
        String type = wireType(kind);

        byte[] content;
        try {
            long size = Files.size(file);
            if (size <= 0) {
                throw new ImSendException("IM_PARAM_INVALID", "企微媒体文件为空：" + fileName(file));
            }
            if (size > MAX_UPLOAD_BYTES) {
                // ⛔ 不截断：截断后的文件对收件人是坏的，明确报错让调用方决定怎么办。
                throw new ImSendException("IM_PARAM_INVALID",
                        "企微媒体文件超过上传上限：" + size + " > " + MAX_UPLOAD_BYTES
                                + " 字节（schema range_size 100 MiB）：" + fileName(file));
            }
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            // ⛔ 只带文件名与异常类型名：路径目录结构与文件内容都不进异常消息。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "读取企微媒体文件失败：" + fileName(file) + "：" + e.getClass().getSimpleName(),
                    null, false);
        }
        // size 与实际读到的字节数可能不一致（读的过程中文件被改写）⇒ 以实读为准再判一次。
        if (content.length == 0) {
            throw new ImSendException("IM_PARAM_INVALID", "企微媒体文件为空：" + fileName(file));
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "企微媒体文件超过上传上限：" + content.length + " > " + MAX_UPLOAD_BYTES
                            + " 字节（schema range_size 100 MiB）：" + fileName(file));
        }

        ObjectNode body = json.createObjectNode();
        // PIT-WC-034：文件字节以 base64 放进 file_path（不是路径、不是 octet-stream）。
        body.put("file_path", Base64.getEncoder().encodeToString(content));
        body.put("type", type);

        WecomCliClient.Result result = client.call(token, PATH_UPLOAD, body);
        if (!result.ok()) {
            // 语义映射（853004 / 853008 / -14 …）集中在客户端一处，避免各调用点各写一套。
            throw WecomCliClient.failureOf(result, "企微上传媒体");
        }

        JsonNode payload = result.result();
        if (payload == null || !payload.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微上传媒体成功但响应缺少 result 对象", null, false);
        }
        String mediaId = text(payload, "media_id");
        if (mediaId == null) {
            // 成功却拿不到 media_id ⇒ 后续那一步（发消息）无从谈起 ⇒ 如实失败，不编造 id。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "企微上传媒体成功但响应缺少 media_id", null, false);
        }
        if (log.isInfoEnabled()) {
            // ⛔ 只打类型与字节数：文件内容与文件名不进日志。
            log.info("[WecomMedia] 上传完成 type={} bytes={}", type, content.length);
        }
        return mediaId;
    }

    // ───────────────────────── 2. message.aibot.send ─────────────────────────

    /**
     * 把已上传的 {@code media_id} 作为一条媒体消息发出去。
     *
     * <p>业务体逐字：{@code {"chat_id":"...","msg_type":"image"|"file",
     * "image"|"file":{"media_id":"..."}}}。
     *
     * @param token   CLI token
     * @param chatId  会话 id（单聊 userid / 群会话 id，同一个字段）
     * @param mediaId 上传拿到的 media_id
     * @param kind    媒体类型（决定 {@code msg_type} 与内容键）
     * @throws ImSendException 入参不合法（不发请求）或平台失败
     */
    public void sendMedia(String token, String chatId, String mediaId, MediaKind kind)
            throws ImSendException {
        if (chatId == null || chatId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微 chat_id 为空");
        }
        if (mediaId == null || mediaId.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "企微 media_id 为空");
        }
        String type = wireType(kind);

        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("msg_type", type);
        // 内容键与 msg_type 同名（schema：msg_type=image ⇒ image 必填；msg_type=file ⇒ file 必填）。
        body.putObject(type).put("media_id", mediaId);

        WecomCliClient.Result result = client.call(token, PATH_AIBOT_SEND, body);
        if (!result.ok()) {
            // aibot 端点成功返回 {"success":true}；success=false 会被 ok() 判成失败（PIT-WC-018）。
            throw WecomCliClient.failureOf(result, "企微发送媒体");
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 本平台支持的媒体类型。
     *
     * <p>⚠️ {@link MediaKind#UNKNOWN} 也返回 true：它的语义是「按 {@link MediaKind#FILE} 发」
     * （见 {@code MediaKind#forUpload}），不是「不支持」。若这里返回 false，
     * 扩展名认不出的文件会被整个丢掉 —— 而它本来是能当普通文件发出去的。
     */
    public boolean supports(MediaKind kind) {
        return kind == MediaKind.IMAGE || kind == MediaKind.FILE || kind == MediaKind.UNKNOWN;
    }

    /** 媒体类型 → 线上字面量（schema enum：小写 image / file）。 */
    private static String wireType(MediaKind kind) {
        MediaKind effective = kind == null ? MediaKind.FILE : kind.forUpload();
        return effective == MediaKind.IMAGE ? TYPE_IMAGE : TYPE_FILE;
    }

    /** 取字符串字段（空白 / 非文本 ⇒ null）。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 只取文件名（⛔ 完整路径会带出本机目录结构）。 */
    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "文件" : name.toString();
    }
}
