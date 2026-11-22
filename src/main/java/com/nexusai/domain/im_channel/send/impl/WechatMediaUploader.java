package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.MediaKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 微信 iLink 媒体上传（**四步，纯 JDK HttpClient，零第三方 SDK**）。
 *
 * <h2>协议出处</h2>
 * <ul>
 *   <li>上游开源 SDK {@code github.com/CloudSen/wx-aibot-java-sdk}（Apache-2.0）的
 *       {@code WeixinGetUploadUrlRequest} + {@code WeixinUploadMediaRequest}；</li>
 *   <li>本仓另一份<b>逐字核对过</b>的等价实现
 *       {@code /usr/local/code/nexusai-im-channel} 的
 *       {@code com.nexusai.im.weixin.WeixinMediaUploader}（182 行）
 *       与 {@code com.nexusai.im.weixin.WeixinHttpClient#getUploadUrl/uploadToCdn}。</li>
 * </ul>
 *
 * <h2>🔴 为什么微信的"上传"不是一个 key</h2>
 * 飞书给 {@code file_key}、企微给 {@code media_id}，都能塞进消息体就完事。
 * 微信 iLink 给的是<b>一整套 CDN 下载描述</b>
 * （{@code url / aes_key / file_size / file_md5}），
 * 必须原样放进 {@code sendmessage} 的 {@code item_list} 节点里 ——
 * 所以本类返回的 {@link ImMediaRef#raw()} 是**完整的 item 节点**（含 {@code type}），
 * 发送侧不需要再理解任何字段。
 *
 * <h2>四步（实测通过，顺序不可换）</h2>
 * <ol>
 *   <li>{@code POST {baseUrl}/ilink/bot/getuploadurl} —— 字段<b>全小写</b>，
 *       {@code aeskey} 是 32 位 hex，{@code filesize} 是<b>填充后</b>的大小；</li>
 *   <li>AES-128-ECB/PKCS5 加密文件内容（与企微的 AES-256-CBC <b>不是一回事</b>，别混用）；</li>
 *   <li>{@code POST {cdn}/upload?encrypted_query_param=&filekey=} —— body 是密文，
 *       下载参数在<b>响应头 {@code x-encrypted-param}</b> 里（不在 body 里！）；</li>
 *   <li>组装 item 节点（{@code type} + {@code image_item/file_item} + {@code media} 下载描述）。</li>
 * </ol>
 *
 * <h2>坑点清单（每条都是真踩过的）</h2>
 * <ul>
 *   <li><b>PIT-WX-013</b>：{@code getuploadurl} 的 {@code rawfilemd5} 必须<b>全小写</b>。
 *       上游 SDK 就是栽在这（Jackson 把 {@code rawFileMd5} 序列化成 camelCase）⇒ 服务端
 *       回 {@code {"ret":-2}}，<b>上传必失败</b>。</li>
 *   <li><b>PIT-WX-019</b>：{@code media.aes_key} 是「<b>hex 文本</b>的 base64」，
 *       不是 raw key 的 base64。写错 ⇒ 对方下载后解不开（附件变成乱码/打不开）。</li>
 *   <li><b>PIT-WX-021</b>：{@code file_item.md5} 必须是<b>明文字节</b>的 MD5 小写 hex，
 *       <b>不是 {@code filekey} 的 MD5</b> —— {@code filekey} 只是随机 16 位 hex，
 *       与文件内容毫无关系。</li>
 * </ul>
 *
 * <h2>为什么不用 SDK</h2>
 * 上游 SDK 的 {@code WeixinGetUploadUrlRequest} 漏了 {@code rawfilemd5} 的
 * {@code @JsonProperty} 注解 ⇒ 序列化出 camelCase ⇒ 平台直接拒收。
 * 修 SDK 比重写 200 行更折腾 ⇒ 这里全自实现（纯 JDK HttpClient，无新依赖）。
 *
 * <p>⛔ <b>敏感值</b>：{@code botToken}、{@code aes_key}、文件内容<b>绝不进日志与异常消息</b>。
 * 本类所有异常只带 HTTP 状态码 / 平台码 / 一句人能懂的原因。
 */
@Component
public class WechatMediaUploader implements ImMediaUploader {

    private static final Logger log = LoggerFactory.getLogger(WechatMediaUploader.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 申请上传地址的端点（相对 baseUrl）。 */
    static final String GET_UPLOAD_URL_PATH = "/ilink/bot/getuploadurl";

    /**
     * CDN 基址（**平台常量，不从凭证取**）。
     *
     * <p>与 {@code WeixinConfig.DEFAULT_CDN} 同值 —— 微信 iLink 的媒体 CDN 与
     * API 基址不是同一台机器，凭证里也没有这个字段（扫码只给 baseUrl）。
     * 留成常量是为了将来测试时能用构造器替换成 stub 地址。
     */
    static final String DEFAULT_CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    /** {@code base_info.channel_version}：上游 SDK 每个端点都带这个字段。 */
    private static final String CHANNEL_VERSION = "1.0.0";

    /** 连接与请求超时（与上游 SDK 的 apiTimeout 同量级）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** CDN 上传超时（文件大，给得宽一点；与参考实现一致）。 */
    private static final Duration CDN_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 本地读取上限（<b>内存保护，不是微信平台上限</b>）。
     *
     * <p>🔴 为什么需要它：协议要求先算出「明文 md5」与「填充后大小」才能申请上传地址，
     * 所以内容**必须整读进内存**（明文 + 密文两份）⇒ 不设闸的话，
     * 一个 1GB 的附件就能把后端堆爆。100 MiB 与企微 CLI 的
     * {@code range_size 100 MiB} 同量级。
     *
     * <p>微信自己真正的上限未知 ⇒ 超限时由平台报错，我们不替它猜。
     */
    static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http;

    /** CDN 基址（可被构造器替换，便于将来对着 stub 服务测试）。 */
    private final String cdnBaseUrl;

    /**
     * 生产构造：自建连接池（HttpClient 线程安全，共享一个即可）。
     *
     * <p>🔴 本类有<b>两个</b>构造器 ⇒ 必须显式标 {@code @Autowired}，
     * 否则 Spring 只能靠"有默认构造器就用默认的"这条兜底规则 ——
     * 将来有人删掉默认构造器就会变成装配期 {@code NoSuchMethodException}（启动才炸）。
     */
    @Autowired
    public WechatMediaUploader() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_CDN_BASE_URL);
    }

    /** 测试接缝：注入替身客户端与 CDN 基址。 */
    WechatMediaUploader(HttpClient http, String cdnBaseUrl) {
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.cdnBaseUrl = stripTrailingSlash(
                cdnBaseUrl == null || cdnBaseUrl.isBlank() ? DEFAULT_CDN_BASE_URL : cdnBaseUrl.trim());
    }

    @Override
    public String platform() {
        return "wechat";
    }

    /**
     * 支持的媒体类型：图片与文件。
     *
     * <p>🔴 <b>{@code UNKNOWN} 也返回 true</b>：模板方法先问 {@code supports(kind)}，
     * 再用 {@link MediaKind#forUpload()} 归一（{@code UNKNOWN → FILE}）去上传。
     * 这里若对 {@code UNKNOWN} 返回 false，未知扩展名的文件（{@code .bin} 等）
     * 会在**上传之前**被判成"平台不支持"而直接丢弃 —— 那不是平台的限制，是我们自己加的。
     *
     * <p>语音 / 视频<b>不声明</b>：{@link MediaKind} 里根本没有这两个类型，
     * 且参考实现的语音/视频 item 字段（{@code encode_type/bits_per_sample/playtime}）
     * 依赖真实的音视频参数（采样率、时长），我们拿不到 ⇒ 不能"声明支持但实际抛异常"。
     */
    @Override
    public boolean supports(MediaKind kind) {
        return kind == MediaKind.IMAGE || kind == MediaKind.FILE || kind == MediaKind.UNKNOWN;
    }

    @Override
    public ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind) throws ImSendException {
        // ── ① 入参与凭证（⛔ 任何一步都不许 NPE）──
        if (ctx == null) {
            throw new ImSendException("IM_PARAM_INVALID", "发送上下文为空");
        }
        if (file == null) {
            throw new ImSendException("IM_PARAM_INVALID", "附件路径为空");
        }
        MediaKind effective = (kind == null ? MediaKind.FILE : kind).forUpload();
        if (!supports(effective)) {
            throw new ImSendException("IM_PARAM_INVALID", "微信不支持该媒体类型");
        }

        WechatCredential cred = WechatCredential.parse(ctx.credential());
        // 🔴 与发消息不同：上传端点没有 context_token 可用，botToken 是**唯一**凭证。
        //    缺了它必然被平台拒 ⇒ 这里明确失败，别发一个注定 401 的请求。
        if (cred.botToken() == null || cred.botToken().isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID",
                    "微信凭证缺少 botToken（媒体上传必须带机器人令牌）");
        }

        byte[] plain = readFile(file);

        // ── ② 准备上传所需的三件套（filekey / aeskey / 明文 md5）──
        String aesKeyHex = randomHex(16);      // 32 位 hex = 16 字节（AES-128）
        byte[] aesKey = hexToBytes(aesKeyHex);
        String filekey = randomHex(8);          // 16 位 hex（与文件内容无关的随机数）
        String rawMd5 = md5Hex(plain);          // ★ 明文字节的 md5（小写 hex）
        int padded = paddedSize(plain.length);  // ★ 填充**后**的大小

        // ── ③ 申请上传地址 ──
        ObjectNode body = JSON.createObjectNode();
        body.put("filekey", filekey);
        body.put("media_type", mediaTypeCode(effective));   // 1=图 3=文件
        body.put("to_user_id", ctx.peerId());
        body.put("rawsize", plain.length);
        body.put("rawfilemd5", rawMd5);        // ⛔ 全小写（PIT-WX-013），不能写成 rawFileMd5
        body.put("filesize", padded);          // ⛔ 填充后的大小，不是明文长度
        body.put("no_need_thumb", true);
        body.put("aeskey", aesKeyHex);
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        JsonNode resp = post(cred.baseUrl() + GET_UPLOAD_URL_PATH, body, cred.botToken());
        String uploadParam = resp.path("upload_param").asText(null);
        if (uploadParam == null || uploadParam.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 getuploadurl 未返回 upload_param（平台会因 rawfilemd5 字段名/大小写不对而返回 ret=-2）");
        }

        // ── ④ 加密 → 上传 CDN → 取响应头里的下载参数 ──
        byte[] cipher = encrypt(plain, aesKey);
        String downloadParam = uploadToCdn(uploadParam, filekey, cipher);

        // ── ⑤ 组 item（发送侧直接塞进 item_list，不需要再理解任何字段）──
        ObjectNode item = buildItem(effective, downloadParam, aesKeyHex,
                cipher.length, plain.length, rawMd5, fileNameOf(file));

        if (log.isDebugEnabled()) {
            // ⛔ 只打类型 / 大小 / 对端，不打 aes_key、botToken、文件名内容。
            log.debug("[WechatUpload] 上传完成 kind={} size={} peer={}", effective, plain.length, ctx.peerId());
        }
        return new ImMediaRef(effective, item);
    }

    // ───────────────────────── HTTP ─────────────────────────

    /** 申请上传地址的 POST（返回原始 JSON 节点，交给调用方取 {@code upload_param}）。 */
    private JsonNode post(String url, ObjectNode body, String botToken) throws ImSendException {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    // 上游 SDK 的通用信封：AuthorizationType + 防重放的 X-WECHAT-UIN。
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", randomUin())
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 请求地址不合法");
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 请求体序列化失败");
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 网络类失败值得重试（超时/连接重置/对端抖动）。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 getuploadurl 网络异常：" + e.getClass().getSimpleName(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 被中断", null, true);
        }

        int status = resp.statusCode();
        if (status / 100 != 2) {
            // 5xx = 对端临时故障（可重试）；4xx = 请求本身有问题（重试无意义）。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 getuploadurl HTTP " + status, String.valueOf(status), status >= 500);
        }

        JsonNode node = parseBody(resp.body());
        int err = node.path("errcode").asInt(0);
        int ret = node.path("ret").asInt(0);
        int code = err != 0 ? err : ret;
        if (code != 0) {
            String detail = node.path("errmsg").asText("");
            // -2 = 参数被拒（SDK 的 camelCase 坑就是它）⇒ 重试无用，当永久错误。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 getuploadurl 被平台拒绝（code=" + code
                            + (detail.isBlank() ? "" : "：" + truncate(detail, 120)) + "）",
                    String.valueOf(code), false);
        }
        return node;
    }

    /**
     * 上传密文到 CDN，返回**响应头**里的 {@code x-encrypted-param}。
     *
     * <p>🔴 下载参数<b>不在 body 里</b>，只在响应头 —— 读 body 会拿到空串，
     * 然后对方下载时必然失败。
     */
    private String uploadToCdn(String uploadParam, String filekey, byte[] cipher) throws ImSendException {
        String url = cdnBaseUrl + "/upload?encrypted_query_param="
                + enc(uploadParam) + "&filekey=" + enc(filekey);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/octet-stream")
                    .timeout(CDN_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(cipher))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 CDN 上传地址不合法");
        }

        HttpResponse<Void> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 CDN 上传网络异常：" + e.getClass().getSimpleName(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 CDN 上传被中断", null, true);
        }

        int status = resp.statusCode();
        if (status / 100 != 2) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 CDN 上传 HTTP " + status, String.valueOf(status), status >= 500);
        }
        String downloadParam = resp.headers().firstValue("x-encrypted-param").orElse(null);
        if (downloadParam == null || downloadParam.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 CDN 上传成功但未返回 x-encrypted-param（下载参数在响应头里，不在 body 里）");
        }
        return downloadParam;
    }

    /** 解析响应体；不是 JSON ⇒ 抛异常（不返回 null 让上层 NPE）。 */
    private static JsonNode parseBody(String raw) throws ImSendException {
        if (raw == null || raw.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 响应为空");
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 响应不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 getuploadurl 响应不是 JSON 对象");
        }
        return node;
    }

    // ───────────────────────── item 组装 ─────────────────────────

    /**
     * 组装可直接放进 {@code item_list} 的节点。
     *
     * <p>结构（与参考实现逐字一致）：
     * <pre>
     * 图片：{"type":2,"image_item":{"media":{"encrypt_query_param":…,"aes_key":…,"encrypt_type":1},
     *                            "aeskey":…,"mid_size":…}}
     * 文件：{"type":4,"file_item":{"media":{同上},"file_name":…,"md5":…,"len":"…"}}
     * </pre>
     */
    private static ObjectNode buildItem(MediaKind kind, String downloadParam, String aesKeyHex,
                                        int cipherSize, long rawSize, String rawMd5, String fileName) {
        ObjectNode item = JSON.createObjectNode();
        item.put("type", itemTypeCode(kind));    // 2=图片 4=文件（与上传码不同！）

        ObjectNode content = item.putObject(kind == MediaKind.IMAGE ? "image_item" : "file_item");
        ObjectNode media = content.putObject("media");
        media.put("encrypt_query_param", downloadParam);
        // ★ PIT-WX-019：aes_key 是「hex 文本的 base64」，不是 raw key 的 base64。
        media.put("aes_key", Base64.getEncoder().encodeToString(
                aesKeyHex.getBytes(StandardCharsets.US_ASCII)));
        media.put("encrypt_type", 1);

        if (kind == MediaKind.IMAGE) {
            content.put("aeskey", aesKeyHex);
            content.put("mid_size", cipherSize);
        } else {
            content.put("file_name", fileName);
            // ★ PIT-WX-021：md5 必须是**明文**的 md5，不是 filekey 的 md5。
            content.put("md5", rawMd5);
            content.put("len", String.valueOf(rawSize));
        }
        return item;
    }

    /** {@code media_type} 上传码：1=图 2=视频 3=文件 4=语音（我们只用到 1 与 3）。 */
    static int mediaTypeCode(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> 1;
            case FILE -> 3;
            case UNKNOWN -> 3;   // forUpload() 之后不会到这里，兜底成文件
        };
    }

    /** {@code item_list[].type} 发送码：1=文本 2=图 3=语音 4=文件 5=视频（**与上传码不同**）。 */
    static int itemTypeCode(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> 2;
            case FILE -> 4;
            case UNKNOWN -> 4;
        };
    }

    // ───────────────────────── 本地文件 ─────────────────────────

    /** 读文件（不存在 / 太大 / 读失败 ⇒ 明确失败，绝不 NPE）。 */
    private static byte[] readFile(Path file) throws ImSendException {
        byte[] plain;
        try {
            if (!Files.isRegularFile(file)) {
                throw new ImSendException("IM_PARAM_INVALID", "附件不存在或不是普通文件");
            }
            long size = Files.size(file);
            if (size > MAX_UPLOAD_BYTES) {
                throw new ImSendException("IM_PARAM_INVALID",
                        "附件超过本地读取上限（" + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MiB）");
            }
            plain = Files.readAllBytes(file);
        } catch (ImSendException e) {
            throw e;
        } catch (IOException e) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "读取附件失败：" + e.getClass().getSimpleName());
        }
        // TOCTOU 兜底：stat 与 read 之间文件可能变大。
        if (plain.length > MAX_UPLOAD_BYTES) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "附件超过本地读取上限（" + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MiB）");
        }
        return plain;
    }

    /** 附件展示名（拿不到就用固定名，别让 item 里出现 null）。 */
    private static String fileNameOf(Path file) {
        Path name = file.getFileName();
        return name == null || name.toString().isBlank() ? "file.bin" : name.toString();
    }

    // ───────────────────────── 加密 / 摘要 ─────────────────────────

    /**
     * AES-128-ECB + PKCS5 加密。
     *
     * <p>🔴 与企微的 AES-256-CBC <b>不是一回事</b>，别混用（换算法 ⇒ 对方解不开）。
     */
    private static byte[] encrypt(byte[] plain, byte[] key16) throws ImSendException {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
            return c.doFinal(plain);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信媒体加密失败");
        }
    }

    /** PKCS5 填充后的长度（{@code getuploadurl} 的 {@code filesize} 要这个值）。 */
    static int paddedSize(int plainSize) {
        return ((plainSize / 16) + 1) * 16;
    }

    /** 明文的 MD5（<b>小写 hex</b>）。 */
    static String md5Hex(byte[] data) throws ImSendException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return bytesToHex(md.digest(data)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信媒体摘要计算失败");
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return bytesToHex(b);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }

    /** X-WECHAT-UIN = base64(十进制随机数) —— 每次随机，防重放（与上游 SDK 同做法）。 */
    private static String randomUin() {
        long v = Integer.toUnsignedLong(ThreadLocalRandom.current().nextInt());
        return Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? DEFAULT_CDN_BASE_URL : out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
