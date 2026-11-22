package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.MediaKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Adler32;

/**
 * 飞书媒体上传（图片 / 文件 / 大文件分片）——纯 HTTP，无 SDK。
 *
 * <h2>🔴 协议出处（逐字核对官方文档，不靠记忆）</h2>
 * <pre>
 * ① 图片：POST {base}/open-apis/im/v1/images            multipart/form-data
 *      image_type = message
 *      image      = &lt;二进制&gt;
 *      → {"code":0,"msg":"success","data":{"image_key":"img_xxx"}}
 *    限制：图片 ≤ 10 MB，0 字节不收（官方文档「使用限制」原文）。
 *
 * ② 文件：POST {base}/open-apis/im/v1/files              multipart/form-data
 *      file_type = stream        （通用类型；pdf/doc/xls/ppt 是专用枚举，stream 覆盖任意格式）
 *      file_name = &lt;带后缀的文件名&gt;
 *      file      = &lt;二进制&gt;
 *      → {"code":0,"msg":"success","data":{"file_key":"file_xxx"}}
 *    限制：文件 ≤ 30 MB，0 字节不收（官方文档「使用限制」原文）。
 *
 * ③ 大文件分片：POST {base}/open-apis/drive/v1/files/upload_prepare   application/json
 *      {"file_name":"a.bin","parent_type":"explorer","parent_node":"&lt;文件夹 token&gt;","size":123}
 *      → {"code":0,"data":{"upload_id":"...","block_size":4194304,"block_num":3}}
 *    POST {base}/open-apis/drive/v1/files/upload_part                 multipart/form-data
 *      upload_id / seq(从 0 开始) / size(本片字节数) / checksum(Adler-32，可选) / file(本片二进制)
 *      → {"code":0,"data":{}}          （data 可能为 null）
 *    POST {base}/open-apis/drive/v1/files/upload_finish                application/json
 *      {"upload_id":"...","block_num":3}
 *      → {"code":0,"data":{"file_token":"boxcnxxx"}}
 *    官方「上传文件概述」原文：≤ 20 MB 直接 upload_all；&gt; 20 MB 走分片，
 *    「平台固定以 4MB 的大小对文件进行分片」（实际以 prepare 返回的 block_size 为准）。
 * </pre>
 *
 * <h3>⚠️ 为什么分片走的是「云空间（drive）」而不是 IM</h3>
 * <p>飞书开放平台**没有** {@code /open-apis/im/v1/files/upload_prepare} 这个端点
 * （IM 侧只有 {@code images.create} 与 {@code files.create} 两个上传端点）；
 * 全平台唯一的分片上传协议是云空间的三段式
 * {@code upload_prepare → upload_part → upload_finish}。
 * ⇒ 大文件走云空间分片，拿到的 {@code file_token} 作为媒体 key。
 *
 * <p><b>⚠️ 这一段的已知不确定处（务必知悉）</b>：飞书《发送消息内容结构》只说
 * {@code file_key}「通过上传文件接口获取」，并未声明云空间分片拿到的 {@code file_token}
 * 可以直接当 {@code file_key} 用；官方文档里没有这句话，本仓也没有真机验证过。
 * 代码按「可以」实现（把 file_token 放进 {@code content.file_key}），
 * 若平台拒绝，会以飞书返回的业务码原样抛出（不会静默丢附件）。
 *
 * <p><b>⚠️ 分片路径的两个副作用（要知道）</b>：
 * <ol>
 *   <li>文件会真的落到<b>应用云空间</b>（{@code parent_type=explorer}、{@code parent_node}
 *       为空 = 根目录）⇒ 用户的云空间里会多出一个文件。这是平台协议决定的，没有「只传不留」的选项。</li>
 *   <li>需要应用开通<b>云空间</b>权限（{@code drive:file} / {@code drive:file:upload}）；
 *       没开通会回 {@code 1061073 no scope auth}，错误码原样透出（可据此提示用户去开发者后台补权限）。</li>
 * </ol>
 *
 * <h3>为什么分片不是「把文件切好再逐个调 IM 上传」</h3>
 * <p>IM 上传接口本身硬上限 30 MB，切片也没有用（每个切片仍然是走同一个端点）。
 * 真正能绕开上限的只有平台自己的分片协议 ⇒ 只能走 drive 三段式。
 *
 * <h2>失败语义</h2>
 * <ul>
 *   <li>本地校验（文件不存在 / 0 字节 / 超过上限）⇒ {@code IM_PARAM_INVALID}，<b>不发请求</b>；</li>
 *   <li>网络异常 / HTTP 非 2xx ⇒ {@code IM_SEND_PLATFORM_ERROR}（retryable = true）；</li>
 *   <li>平台 {@code code != 0} ⇒ {@code IM_SEND_PLATFORM_ERROR} + platformCode；</li>
 *   <li>token 失效码（99991663 / 99991661 / 99991668）⇒ 作废缓存换新票<b>重试一次</b>，
 *       再失败 ⇒ {@code IM_CREDENTIAL_EXCHANGE_FAILED}。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * ⛔ appSecret / tenant_access_token / <b>文件内容</b>一律不进日志、不进异常消息；
 * 异常里只出现文件名、字节数与平台码。
 *
 * <h2>⛔ 一处已知的重复</h2>
 * 凭证解析（{@code appId}/{@code appSecret}）与 {@link FeishuSendStrategy} 里那份重复。
 * 公共契约文件（{@code ImSendContext} 等）本次冻结，不许改 ⇒ 只能各留一份；
 * 将来若要收敛，应抽成一个「飞书凭证解析」小工具类，而不是动契约。
 */
@Component
public class FeishuMediaUploader implements ImMediaUploader {

    private static final Logger log = LoggerFactory.getLogger(FeishuMediaUploader.class);

    /** 上传图片端点（{@code images.create}）。 */
    static final String IMAGES_PATH = "/open-apis/im/v1/images";

    /** 上传文件端点（{@code files.create}）。 */
    static final String FILES_PATH = "/open-apis/im/v1/files";

    /** 云空间分片：预上传。 */
    static final String DRIVE_UPLOAD_PREPARE_PATH = "/open-apis/drive/v1/files/upload_prepare";

    /** 云空间分片：上传分片。 */
    static final String DRIVE_UPLOAD_PART_PATH = "/open-apis/drive/v1/files/upload_part";

    /** 云空间分片：完成上传。 */
    static final String DRIVE_UPLOAD_FINISH_PATH = "/open-apis/drive/v1/files/upload_finish";

    /** {@code image_type} 取值：发消息用的图片（另一个枚举值是头像，边界不同）。 */
    static final String IMAGE_TYPE_MESSAGE = "message";

    /** {@code file_type} 取值：通用文件流（覆盖 pdf/doc/xls/ppt 之外的任意格式）。 */
    static final String FILE_TYPE_STREAM = "stream";

    /** 云空间 {@code parent_type}：云空间（唯一的合法值）。 */
    static final String DRIVE_PARENT_TYPE = "explorer";

    /**
     * 云空间 {@code parent_node}：目标文件夹 token。
     *
     * <p>默认空串 = 上传到应用云空间的**根目录**（官方 CLI 的 lark-drive 参考文档写明
     * 「{@code explorer} 时传文件夹 token（根目录可为空字符串）」）。
     * 若平台不接受空串，会回 {@code 1061044 parent node not exist}，错误码原样透出。
     */
    static final String DRIVE_PARENT_NODE = "";

    /** 图片上限（官方文档：10 MB）。 */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 单次 IM 文件上传上限（官方文档：30 MB）；超过走分片。 */
    static final long MAX_FILE_BYTES = 30L * 1024 * 1024;

    /** 云空间 {@code file_name} 的长度上限（官方文档：最大 250 字符）。 */
    static final int MAX_FILE_NAME_LENGTH = 250;

    /** 「token 失效」错误码族：换一张新票可能就好了。 */
    private static final Set<String> TOKEN_EXPIRED_CODES = Set.of("99991663", "99991661", "99991668");

    /** 异常文案里允许出现的最长原文长度（防止整段响应体塞进 message）。 */
    private static final int SNIPPET_LIMIT = 300;

    /** 生产基址（国内版）。 */
    static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper json = new ObjectMapper();
    private final FeishuTokenService tokenService;

    /** 基址（末尾不带斜杠）。 */
    private final String baseUrl;

    /** 生产构造（打飞书官方端点）。 */
    @Autowired
    public FeishuMediaUploader(FeishuTokenService tokenService) {
        this(tokenService, DEFAULT_BASE_URL);
    }

    /**
     * 自定义基址（**测试用**：指向本地 stub 服务，才能验证「上传成功/被拒/重试」这类行为）。
     *
     * @param tokenService 票服务（换 tenant_access_token 用）
     * @param baseUrl       基址，如 {@code http://127.0.0.1:1234}；null / 空白 ⇒ 用默认值
     */
    public FeishuMediaUploader(FeishuTokenService tokenService, String baseUrl) {
        if (tokenService == null) {
            throw new IllegalArgumentException("tokenService 必填（上传前要换 tenant_access_token）");
        }
        this.tokenService = tokenService;
        String v = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    // ═════════════════════ 契约实现 ═════════════════════

    @Override
    public String platform() {
        return "feishu";
    }

    /**
     * 飞书支持图片与文件。
     *
     * <p>⚠️ {@link MediaKind#UNKNOWN} 也返回 true：它的语义是「按 {@link MediaKind#FILE} 发」
     * （见 {@code MediaKind#forUpload}），不是「不支持」。模板方法是<b>先</b>问 supports()
     * <b>再</b>归一（{@code kind.forUpload()}），若这里对 UNKNOWN 返回 false，
     * 扩展名认不出的文件（{@code .foo}）会被整个丢掉 —— 而它本来完全能当普通文件发出去。
     */
    @Override
    public boolean supports(MediaKind kind) {
        return kind == MediaKind.IMAGE || kind == MediaKind.FILE || kind == MediaKind.UNKNOWN;
    }

    /**
     * 上传一个本地文件，换回平台侧引用。
     *
     * @param ctx  上下文（含已解密的凭证 JSON）
     * @param file 本地文件（不存在 / 0 字节 / 超限都在这里拦下，不发请求）
     * @param kind 媒体类型（{@code null} 视作文件；{@code UNKNOWN} 视作文件）
     * @return {@code raw = {"kind":"image"|"file","key":"<image_key|file_key|file_token>"}}
     * @throws ImSendException 凭证不可用 / 文件不可读或超限 / 平台拒绝 / 网络失败
     */
    @Override
    public ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind) throws ImSendException {
        if (ctx == null) {
            throw new ImSendException("IM_PARAM_INVALID", "飞书媒体上传缺少发送上下文");
        }
        MediaKind effective = kind == null ? MediaKind.FILE : kind.forUpload();
        Credential credential = parseCredential(ctx.credential());
        long size = validateFile(file, effective);
        String fileName = fileName(file);

        String token = tokenService.tokenFor(ctx.instanceId(), credential.appId(), credential.appSecret());
        try {
            return effective == MediaKind.IMAGE
                    ? uploadImage(token, file, fileName, size)
                    : uploadFile(token, file, fileName, size);
        } catch (ImSendException e) {
            if (!isTokenExpired(e.platformCode())) {
                throw e;
            }
            // 缓存里的票可能已被飞书作废 ⇒ 清缓存换新票，重试一次
            log.warn("[FeishuMedia] tenant_access_token 失效，清缓存后重试一次 instance={} platformCode={}",
                    ctx.instanceId(), e.platformCode());
            tokenService.invalidate(ctx.instanceId());
            String fresh = tokenService.tokenFor(
                    ctx.instanceId(), credential.appId(), credential.appSecret());
            try {
                return effective == MediaKind.IMAGE
                        ? uploadImage(fresh, file, fileName, size)
                        : uploadFile(fresh, file, fileName, size);
            } catch (ImSendException retry) {
                if (isTokenExpired(retry.platformCode())) {
                    // ⛔ 只重试这一次：再失败就如实抛出去（不循环打飞书）
                    throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                            "飞书凭证失效：换新 tenant_access_token 后上传仍被拒（platformCode="
                                    + retry.platformCode() + "）",
                            retry.platformCode(), false);
                }
                throw retry;
            }
        }
    }

    // ═════════════════════ 图片 ═════════════════════

    /** 上传图片（{@code image_type=message}）⇒ {@code data.image_key}。 */
    private ImMediaRef uploadImage(String token, Path file, String fileName, long size)
            throws ImSendException {
        byte[] content = readAll(file);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("image_type", IMAGE_TYPE_MESSAGE);
        JsonNode root = postMultipart(token, IMAGES_PATH, fields,
                "image", fileName, content, guessImageMimeType(fileName), "上传图片");
        String key = requiredText(dataOf(root), "image_key", "上传图片");
        if (log.isInfoEnabled()) {
            // ⛔ 只打类型与字节数：文件内容与文件名不进日志
            log.info("[FeishuMedia] 图片上传完成 bytes={}", size);
        }
        return ref(MediaKind.IMAGE, key);
    }

    // ═════════════════════ 文件 ═════════════════════

    /**
     * 上传文件。
     *
     * <p>≤ 30 MB 走 IM 单次上传（拿 {@code file_key}）；&gt; 30 MB 走云空间分片
     * （拿 {@code file_token}）。分界线就是 IM 接口的硬上限 —— 超了会回
     * {@code 234006 The file size exceed the max value}，所以本地先分流，
     * 不去浪费一次必然失败的请求。
     */
    private ImMediaRef uploadFile(String token, Path file, String fileName, long size)
            throws ImSendException {
        if (size > MAX_FILE_BYTES) {
            String fileToken = uploadChunked(token, file, fileName, size);
            return ref(MediaKind.FILE, fileToken);
        }
        byte[] content = readAll(file);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("file_type", FILE_TYPE_STREAM);
        fields.put("file_name", fileName);
        JsonNode root = postMultipart(token, FILES_PATH, fields,
                "file", fileName, content, "application/octet-stream", "上传文件");
        String key = requiredText(dataOf(root), "file_key", "上传文件");
        if (log.isInfoEnabled()) {
            log.info("[FeishuMedia] 文件上传完成 bytes={}", size);
        }
        return ref(MediaKind.FILE, key);
    }

    // ═════════════════════ 大文件分片（云空间三段式） ═════════════════════

    /**
     * 分片上传（{@code upload_prepare → upload_part × N → upload_finish}）。
     *
     * <p>分片大小<b>不写死</b>：用预上传返回的 {@code block_size}（平台固定 4 MB，
     * 但以返回值为准），分片数用返回的 {@code block_num}；
     * 本地算出来的片数与它不一致 ⇒ 如实报错（平台会回 {@code 1062010 block missing}）。
     *
     * @return 云空间 {@code file_token}（作为媒体 key 使用）
     */
    private String uploadChunked(String token, Path file, String fileName, long size)
            throws ImSendException {
        // ── ① 预上传：拿 upload_id 与分片策略 ──
        ObjectNode prepare = json.createObjectNode();
        prepare.put("file_name", truncate(fileName, MAX_FILE_NAME_LENGTH));
        prepare.put("parent_type", DRIVE_PARENT_TYPE);
        prepare.put("parent_node", DRIVE_PARENT_NODE);
        prepare.put("size", size);
        JsonNode prepareData = dataOf(postJson(token, DRIVE_UPLOAD_PREPARE_PATH, prepare, "分片预上传"));

        String uploadId = optionalText(prepareData, "upload_id");
        int blockSize = prepareData.path("block_size").asInt(0);
        int blockNum = prepareData.path("block_num").asInt(0);
        if (uploadId == null || uploadId.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "分片预上传成功但响应缺少 upload_id", null, false);
        }
        if (blockSize <= 0 || blockNum <= 0) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "分片预上传返回的分片策略不合法（block_size=" + blockSize
                            + " block_num=" + blockNum + "）", null, false);
        }
        long expected = (long) blockNum * blockSize;
        if (expected < size) {
            // 平台给的片数装不下这个文件 ⇒ 后面一定 1062010，先在这里说清楚
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "分片预上传返回的分片数装不下文件（block_num=" + blockNum + " block_size=" + blockSize
                            + "，文件 " + size + " 字节）", null, false);
        }

        // ── ② 逐片上传（seq 从 0 开始）──
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int seq = 0; seq < blockNum; seq++) {
                long offset = (long) seq * blockSize;
                int length = (int) Math.min((long) blockSize, size - offset);
                if (length <= 0) {
                    throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                            "分片读取越界（seq=" + seq + " offset=" + offset + "）", null, false);
                }
                byte[] chunk = readChunk(channel, offset, length);
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("upload_id", uploadId);
                fields.put("seq", String.valueOf(seq));
                fields.put("size", String.valueOf(chunk.length));
                // checksum 是可选项；带上 Adler-32 能让平台逐片自校验（官方文档：Adler-32）
                fields.put("checksum", adler32Hex(chunk));
                postMultipart(token, DRIVE_UPLOAD_PART_PATH, fields,
                        "file", fileName, chunk, "application/octet-stream", "上传分片 " + seq);
            }
        } catch (IOException e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "读取分片文件失败：" + fileName + "：" + e.getClass().getSimpleName(), null, false);
        }

        // ── ③ 完成上传：拿 file_token ──
        ObjectNode finish = json.createObjectNode();
        finish.put("upload_id", uploadId);
        finish.put("block_num", blockNum);
        JsonNode finishData = dataOf(postJson(token, DRIVE_UPLOAD_FINISH_PATH, finish, "分片完成上传"));
        String fileToken = requiredText(finishData, "file_token", "分片完成上传");
        if (log.isInfoEnabled()) {
            log.info("[FeishuMedia] 分片上传完成 bytes={} blocks={}", size, blockNum);
        }
        return fileToken;
    }

    // ═════════════════════ HTTP ═════════════════════

    /** 发一次 multipart 上传并解析响应。 */
    private JsonNode postMultipart(String token, String path, Map<String, String> fields,
                                   String fileField, String fileName, byte[] content,
                                   String contentType, String what) throws ImSendException {
        if (content == null || content.length == 0) {
            throw new ImSendException("IM_PARAM_INVALID", what + "：上传内容为空（飞书不接受 0 字节文件）");
        }
        String boundary = "----NexusAIFormBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = buildMultipart(boundary, fields, fileField, fileName, content, contentType);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return parseResponse(send(req, what), what);
    }

    /** 发一次 JSON 请求并解析响应。 */
    private JsonNode postJson(String token, String path, ObjectNode body, String what)
            throws ImSendException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return parseResponse(send(req, what), what);
    }

    /** 发请求并把网络异常归一成 {@link ImSendException}（retryable = true）。 */
    private HttpResponse<String> send(HttpRequest req, String what) throws ImSendException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", what + "：请求被中断", null, true);
        } catch (Exception e) {
            // ⛔ 不拼 e.getMessage()（可能含请求片段）
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：请求失败：" + e.getClass().getSimpleName(), null, true);
        }
    }

    /**
     * 解析飞书响应。
     *
     * <p>🔴 成功判据是 {@code code == 0}，<b>不是</b> HTTP 200 —— 飞书把业务错误也配成
     * HTTP 200，用状态码当成功判据会把「没发出去」报成「发成功了」。
     */
    private JsonNode parseResponse(HttpResponse<String> resp, String what) throws ImSendException {
        int status = resp.statusCode();
        String raw = resp.body() == null ? "" : resp.body();
        if (status < 200 || status >= 300) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：HTTP " + status + "：" + snippet(raw), null, true);
        }
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：响应不是合法 JSON（HTTP " + status + "）：" + snippet(raw), null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：响应不是 JSON 对象（HTTP " + status + "）：" + snippet(raw), null, false);
        }
        JsonNode codeNode = root.get("code");
        if (codeNode == null || codeNode.isNull()) {
            // 缺 code = 响应形状不对，不能当成功
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：响应缺少 code 字段（HTTP " + status + "）", null, false);
        }
        int code = codeNode.asInt(Integer.MIN_VALUE);
        if (code != 0) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：平台拒绝：code=" + code + " msg=" + snippet(root.path("msg").asText("")),
                    String.valueOf(code), false);
        }
        return root;
    }

    /**
     * 组装 multipart 请求体（RFC 7578）。
     *
     * <pre>
     * --BOUNDARY\r\n
     * Content-Disposition: form-data; name="file_type"\r\n\r\n
     * stream\r\n
     * --BOUNDARY\r\n
     * Content-Disposition: form-data; name="file"; filename="a.pdf"\r\n
     * Content-Type: application/octet-stream\r\n\r\n
     * &lt;bytes&gt;\r\n
     * --BOUNDARY--\r\n
     * </pre>
     *
     * <p>字段顺序按传入 {@link Map} 的迭代顺序（{@link LinkedHashMap}）⇒ 确定性好，便于测试断言。
     */
    static byte[] buildMultipart(String boundary, Map<String, String> fields,
                                 String fileField, String fileName, byte[] content,
                                 String contentType) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length + 1024);
        try {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + sanitizeFileName(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的抛 IOException ⇒ 这里只是兜底
            throw new IllegalStateException("multipart 组装失败", e);
        }
        return out.toByteArray();
    }

    // ═════════════════════ 内部 ═════════════════════

    /**
     * 解析实例凭证 JSON（取 {@code appId} / {@code appSecret}）。
     *
     * <p>⛔ 凭证可能是历史遗留的非法 JSON、也可能缺字段 —— 全部在这里判空并抛
     * {@link ImSendException}，绝不把 {@code null} 传到下游变成 NPE。
     */
    private Credential parseCredential(String credentialJson) throws ImSendException {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED", "飞书凭证为空，无法上传媒体");
        }
        JsonNode root;
        try {
            root = json.readTree(credentialJson);
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证不是合法 JSON，无法上传媒体", null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证不是 JSON 对象，无法上传媒体", null, false);
        }
        // 主键名是扫码建应用时写入的 appId / appSecret；同时兼容下划线写法（历史上手工录入过）
        String appId = text(root, "appId", "app_id");
        String appSecret = text(root, "appSecret", "app_secret");
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证缺少 appId 或 appSecret，无法上传媒体", null, false);
        }
        return new Credential(appId, appSecret);
    }

    /** 本地校验：文件存在 / 非空 / 不超限（不合法就不发请求）。 */
    private long validateFile(Path file, MediaKind kind) throws ImSendException {
        if (file == null) {
            throw new ImSendException("IM_PARAM_INVALID", "飞书媒体文件路径为空");
        }
        if (!Files.isRegularFile(file)) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "飞书媒体文件不存在或不是普通文件：" + fileName(file));
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "读取飞书媒体文件大小失败：" + fileName(file), null, false);
        }
        if (size <= 0) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "飞书媒体文件为空（0 字节），平台不接受：" + fileName(file));
        }
        if (kind == MediaKind.IMAGE && size > MAX_IMAGE_BYTES) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "图片超过 10 MB 上限（实际 " + size + " 字节）：" + fileName(file));
        }
        return size;
    }

    /** 读整个文件（仅用于 ≤ 30 MB 的单次上传）。 */
    private byte[] readAll(Path file) throws ImSendException {
        try {
            byte[] content = Files.readAllBytes(file);
            if (content.length == 0) {
                throw new ImSendException("IM_PARAM_INVALID",
                        "飞书媒体文件为空（0 字节），平台不接受：" + fileName(file));
            }
            return content;
        } catch (IOException e) {
            throw new ImSendException("IM_PARAM_INVALID",
                    "读取飞书媒体文件失败：" + fileName(file) + "：" + e.getClass().getSimpleName(),
                    null, false);
        }
    }

    /** 读一个分片（从 offset 读 length 字节）。 */
    private byte[] readChunk(FileChannel channel, long offset, int length) throws IOException {
        byte[] chunk = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        long position = offset;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) {
                break;
            }
            position += n;
        }
        if (buffer.hasRemaining()) {
            throw new IOException("分片数据不足（期望 " + length + " 字节）");
        }
        return chunk;
    }

    /** 业务数据对象：优先 {@code data}，为空则回落到根对象（飞书两种形态都出现过）。 */
    private JsonNode dataOf(JsonNode root) {
        if (root == null) {
            return json.createObjectNode();
        }
        JsonNode data = root.get("data");
        if (data != null && data.isObject()) {
            return data;
        }
        return root;
    }

    /** 成功响应里必须存在的非空文本字段（缺失 = 平台契约漂移）。 */
    private static String requiredText(JsonNode node, String field, String what) throws ImSendException {
        String v = optionalText(node, field);
        if (v == null || v.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    what + "：成功响应缺少 " + field, null, false);
        }
        return v;
    }

    /** 可选文本（缺失 / 非文本 ⇒ null）。 */
    private static String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** 取第一个非空文本字段（兼容两种键名写法）。 */
    private static String text(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && node.isTextual()) {
                String v = node.asText("").trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return "";
    }

    /** 构造媒体引用（raw 的形状与 {@code ImMediaRef} 契约一致）。 */
    private ImMediaRef ref(MediaKind kind, String key) {
        ObjectNode raw = json.createObjectNode();
        raw.put("kind", kind == MediaKind.IMAGE ? "image" : "file");
        raw.put("key", key);
        return new ImMediaRef(kind, raw);
    }

    /** 平台码是否是「token 失效」这一族（可换票重试）。 */
    private static boolean isTokenExpired(String platformCode) {
        return platformCode != null && TOKEN_EXPIRED_CODES.contains(platformCode.trim());
    }

    /** 只取文件名（⛔ 完整路径会带出本机目录结构）。 */
    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "文件" : name.toString();
    }

    /** 文件名里的引号/换行会打穿 multipart 头 ⇒ 去掉。 */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return name.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    /** 截断到指定长度（云空间 file_name 上限 250 字符）。 */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 截断原文（异常 message 不接受整段响应体）。 */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }

    /** Adler-32 校验和（官方文档：分片 checksum 为 Adler-32，十进制字符串）。 */
    private static String adler32Hex(byte[] data) {
        Adler32 adler = new Adler32();
        adler.update(data, 0, data.length);
        return String.valueOf(adler.getValue());
    }

    /** multipart 的 {@code Content-Type} 行（平台按字节判类型，猜错不影响上传）。 */
    private static String guessImageMimeType(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    /**
     * 飞书应用凭证。
     *
     * <p>⛔ 刻意覆写 {@code toString()}：record 的默认实现会把全部字段打出来，
     * 一旦有人 {@code log.info("{}", credential)} 就会把 appSecret 写进日志。
     */
    private record Credential(String appId, String appSecret) {
        @Override
        public String toString() {
            return "Credential[appId=" + appId + ", appSecret=***]";
        }
    }
}
