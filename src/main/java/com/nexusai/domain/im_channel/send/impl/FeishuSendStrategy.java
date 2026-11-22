package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.MediaKind;
import com.nexusai.domain.im_channel.send.SendCapability;
import com.nexusai.domain.im_channel.send.SendIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 飞书「主动发消息」策略（纯 HTTP，无 SDK）。
 *
 * <h2>协议</h2>
 * <pre>
 * POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id
 *   Authorization: Bearer &lt;tenant_access_token&gt;
 *   Content-Type: application/json
 *   body: {"receive_id":"oc_xxx","msg_type":"text","content":"{\"text\":\"你好\"}"}
 *   → {"code":0,"msg":"success","data":{"message_id":"om_xxx"}}
 * </pre>
 *
 * <h3>{@code receive_id_type} 按 peerId 形态推断，不写死</h3>
 * {@code oc_xxx} → {@code chat_id}（群 / 已建立的单聊）；
 * {@code ou_xxx} → {@code open_id}（通讯录里搜到、还没跟机器人说过话的人）；
 * 含 {@code @} → {@code email}。见 {@link #receiveIdTypeOf}。
 *
 * <h3>身份：只有应用（机器人）身份</h3>
 * 飞书<b>协议层支持</b>用户身份发消息（{@code user_access_token} + 权限
 * {@code im:message} + {@code im:message.send_as_user}），但那需要走 OAuth 拿票；
 * 本仓飞书凭证只有 {@code appId/appSecret}（一键建应用产出），换出来的只能是
 * {@code tenant_access_token} ⇒ {@link #canSendAsUser()} 保持 false。
 *
 * <h3>🔴 content 必须是「JSON 字符串」，不是对象</h3>
 * 请求体里的 {@code content} 是「JSON 序列化后再转义」的**字符串**：
 * <pre>
 *   "content": "{\"text\":\"你好\"}"
 * </pre>
 * 直接塞对象（{@code "content": {"text":"你好"}}）会被服务端拒成 230001，
 * 而且报错信息与真因无关（这是飞书侧最容易踩的一个坑）。
 * 所以本类只经 {@link #buildBody} 一处构造请求体，绝不手工拼字符串。
 *
 * <h3>判成功看 {@code code == 0}，不看 HTTP 200</h3>
 * 飞书业务错误常配 HTTP 200，所以 HTTP 状态码不能当成功判据。
 *
 * <h3>token 失效要重试一次</h3>
 * 缓存里的票可能被飞书提前作废（凭证轮换、人工吊销）。收到
 * {@code 99991663 / 99991661 / 99991668} 时清缓存换新票<b>重试一次</b>；
 * 再失败就如实抛错 —— ⛔ 不循环，避免对着一个坏凭证无限打飞书。
 *
 * <h2>带附件时（图片 / 文件 / 大文件分片）</h2>
 * <p>飞书的媒体是<b>两段式</b>：先上传拿 key，再把 key 放进消息体。
 * <pre>
 * 上传（{@link FeishuMediaUploader}）：
 *   图片 → POST /open-apis/im/v1/images → data.image_key
 *   文件 → POST /open-apis/im/v1/files  → data.file_key（≤ 30 MB）
 *   大文件 → 云空间分片 upload_prepare/upload_part/upload_finish → data.file_token
 * 发送（本类）：
 *   msg_type=image → content = {"image_key":"&lt;image_key&gt;"}
 *   msg_type=file  → content = {"file_key":"&lt;file_key|file_token&gt;"}
 * </pre>
 * ⚠️ 媒体消息的 {@code content} 同样是「JSON 字符串」，不是对象 —— 同类注释那个坑。
 * <p>正文先发、附件逐条发：一条附件失败不中断其余，也不让整条消息失败。
 */
@Component
public class FeishuSendStrategy extends AbstractImSendStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuSendStrategy.class);

    /** 发消息端点路径。 */
    static final String SEND_PATH = "/open-apis/im/v1/messages";

    /** 接收方类型：会话 id（{@code oc_xxx}）—— 群聊与「已建立的单聊」都用它。 */
    static final String RECEIVE_ID_TYPE_CHAT = "chat_id";

    /** 接收方类型：用户在该应用内的 open id（{@code ou_xxx}）—— 给「从没跟机器人说过话的人」发消息只能走它。 */
    static final String RECEIVE_ID_TYPE_OPEN = "open_id";

    /** 接收方类型：用户真实邮箱（含 {@code @} 时回落）。 */
    static final String RECEIVE_ID_TYPE_EMAIL = "email";

    /**
     * 按 peerId 的**形态**推断 {@code receive_id_type}。
     *
     * <h2>为什么要推断，而不是写死 chat_id</h2>
     * 写死 {@code chat_id} 只能发给「机器人已加入的会话」——也就是对方**先跟机器人说过话**。
     * 从通讯录搜到的人（{@code SearchImContacts} 落库的 {@code peer_id}）是 {@code ou_xxx}，
     * 拿它当 {@code chat_id} 会被飞书拒（{@code 230001 / 230013}），
     * 于是「搜到人却发不出去」——正是企微那边踩过的同一类问题。
     *
     * <p>飞书 id 前缀是稳定的（官方文档：{@code oc_} 群/会话、{@code ou_} 用户 open_id），
     * 因此按形态选类型比在调用方多传一个参数可靠，也不会漏改调用点。
     *
     * @param peerId 平台侧接收者 id
     * @return {@code chat_id} / {@code open_id} / {@code email}
     */
    static String receiveIdTypeOf(String peerId) {
        String v = peerId == null ? "" : peerId.trim();
        if (v.startsWith("ou_")) {
            return RECEIVE_ID_TYPE_OPEN;
        }
        if (v.indexOf('@') > 0 || v.endsWith("@")) {
            return RECEIVE_ID_TYPE_EMAIL;
        }
        // oc_ 以及无法判别的一律按 chat_id（保持历史行为，不因判别失败而更糟）
        return RECEIVE_ID_TYPE_CHAT;
    }

    /** 生产基址（国内版）。 */
    static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** 「token 失效」错误码：换一张新票可能就好了。 */
    private static final Set<String> TOKEN_EXPIRED_CODES = Set.of("99991663", "99991661", "99991668");

    /** 异常文案里允许出现的最长原文长度（防止整个响应体塞进 message）。 */
    private static final int SNIPPET_LIMIT = 300;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper json = new ObjectMapper();
    private final FeishuTokenService tokenService;

    /**
     * 渠道实例（读「这个实例有没有 OAuth 授权过的用户令牌」）。
     *
     * <p>用字段注入而不是构造器参数：本类有多个测试用构造器，加参数会把
     * 所有调用点一起改掉；而这里的依赖只用于「用户身份」这一条支路，
     * 没有它照样能以机器人身份发消息（{@code required = false} + 判空）。
     */
    @Autowired(required = false)
    private com.nexusai.domain.im_channel.ImChannelService channelService;

    /**
     * 媒体上传器（图片 / 文件 / 大文件分片）。
     *
     * <p>允许为 null：null ⇒ 本策略没有附件通道（模板方法会把附件记成失败并照发正文）。
     */
    private final ImMediaUploader mediaUploader;

    /** 发消息基址（末尾不带斜杠）。 */
    private final String baseUrl;

    /**
     * 生产构造（打飞书官方端点）。
     *
     * <p>⚠️ 上传器标了 {@link Nullable}：没有它，装配期就要求 {@code FeishuMediaUploader}
     * 这个 Bean 必须在场 —— 只注册 {@code FeishuSendStrategy} 的装配测试会直接起不来。
     * 标了之后「缺上传器」退化成「本平台不支持附件」，而不是整个应用起不来。
     */
    @Autowired
    public FeishuSendStrategy(FeishuTokenService tokenService,
                               @Nullable FeishuMediaUploader mediaUploader) {
        this(tokenService, mediaUploader, DEFAULT_BASE_URL);
    }

    /**
     * 自定义基址（**测试用**：指向本地 stub 服务，才能验证「token 失效后重试一次」这类行为）。
     *
     * @param tokenService 票服务
     * @param mediaUploader 上传器；**可传 null**（= 该策略不支持附件）
     * @param baseUrl      基址，如 {@code http://127.0.0.1:1234}；null / 空白 ⇒ 用默认值
     */
    public FeishuSendStrategy(FeishuTokenService tokenService,
                               @Nullable FeishuMediaUploader mediaUploader,
                               String baseUrl) {
        if (tokenService == null) {
            throw new IllegalArgumentException("tokenService 必填（发消息前要换 tenant_access_token）");
        }
        this.tokenService = tokenService;
        this.mediaUploader = mediaUploader;
        String v = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    @Override
    public String platform() {
        return "feishu";
    }

    /**
     * 飞书**协议层**支持以用户本人名义发（{@code Authorization: Bearer <user_access_token>}）。
     *
     * <p>⚠️ 这是平台能力声明，不是「这个实例现在就能发」：
     * 能不能真发取决于该实例有没有做过 OAuth 授权（见 {@link #effectiveIdentity}）。
     * 两者必须分开 —— 能力是静态的，授权状态是动态的。
     */
    @Override
    public boolean canSendAsUser() {
        return true;
    }

    /**
     * 本实例是否已授权可用的 user token。
     *
     * <p>没有 {@code channelService}（纯 POJO 单测）⇒ false（当成没授权，走机器人身份）。
     */
    private boolean hasUserToken(String instanceId) {
        if (channelService == null || instanceId == null || instanceId.isBlank()) {
            return false;
        }
        try {
            return channelService.userTokenOf(instanceId).isPresent();
        } catch (RuntimeException e) {
            log.warn("[FeishuSend] 读用户令牌失败（按未授权处理）instance={} err={}",
                    instanceId, e.toString());
            return false;
        }
    }

    /**
     * 请求用户身份但本实例没授权 ⇒ 降级为机器人身份。
     *
     * <p>为什么降级而不是报错：用户可能忘了重新授权，但**这条消息本身还是要发出去**
     * （尤其是扫描结果、提醒这类不可丢的内容）。降级在 {@link #identityNote} 里
     * 明确写给模型看，不会变成「冒充用户」的静默行为。
     */
    @Override
    protected SendIdentity effectiveIdentity(ImSendContext ctx, boolean hasText, boolean hasAttachment) {
        SendIdentity base = super.effectiveIdentity(ctx, hasText, hasAttachment);
        if (base.isUser() && !hasUserToken(ctx.instanceId())) {
            return SendIdentity.BOT;
        }
        return base;
    }

    @Override
    protected String identityNote(ImSendContext ctx, SendIdentity effective,
                                  boolean hasText, boolean hasAttachment) {
        boolean wantedUser = ctx.identity() != null && ctx.identity().isUser();
        if (wantedUser && effective == SendIdentity.BOT && !hasUserToken(ctx.instanceId())) {
            return "该飞书实例还没有做过用户授权，已降级为机器人身份发出"
                    + "（去 IM 频道 › 设置 › 允许以用户本人名义发送 › 去授权）";
        }
        return super.identityNote(ctx, effective, hasText, hasAttachment);
    }

    /**
     * 飞书可以主动发（不需要对方先说话），也支持群聊；单条文本上限按飞书侧的宽松值给。
     */
    @Override
    public SendCapability capability() {
        return SendCapability.proactive(100_000, true);
    }

    /**
     * 发一条文本。
     *
     * @param ctx  上下文（含已解密的凭证 JSON）
     * @param text 正文（模板方法已保证非空且已截断）
     * @return 飞书 {@code message_id}；平台没给时返回 null（允许）
     * @throws ImSendException 凭证不可用 / 平台拒绝 / 网络失败
     */
    @Override
    public String doSend(ImSendContext ctx, String text) throws ImSendException {
        Credential credential = parseCredential(ctx.credential());
        String token = tokenFor(ctx, credential);
        try {
            return postMessage(token, ctx.peerId(), text);
        } catch (ImSendException e) {
            if (!isTokenExpired(e.platformCode())) {
                throw e;
            }
            // 缓存里的票可能已被飞书作废 ⇒ 清缓存换新票，重试一次
            log.warn("[FeishuSend] tenant_access_token 失效，清缓存后重试一次 instance={} platformCode={}",
                    ctx.instanceId(), e.platformCode());
            tokenService.invalidate(ctx.instanceId());
            String fresh = tokenService.tokenFor(ctx.instanceId(), credential.appId(), credential.appSecret());
            // ⛔ 只重试这一次：再失败就如实抛出去（不循环打飞书）
            return postMessage(fresh, ctx.peerId(), text);
        }
    }

    // ═════════════════════ 附件（图片 / 文件） ═════════════════════

    /**
     * 附件上传器。
     *
     * <p>为什么上传器是<b>构造注入</b>而不是在这里 new：
     * 上传器要复用 {@link FeishuTokenService} 的换票与缓存（否则会出现第二份票缓存，
     * 轮换时两处不一致），所以它必须是被注入的 Bean。
     */
    @Override
    protected ImMediaUploader mediaUploader() {
        return mediaUploader;
    }

    /**
     * 发「正文 + 附件」。
     *
     * <h3>顺序：先正文，再逐条附件</h3>
     * <p>正文是主内容 ⇒ 正文发不出去就是整体失败（{@link ImSendException} 直接上抛）；
     * 附件则是<b>逐条独立</b>：一张图被平台拒了，不该让同批的 PDF 也发不出去。
     *
     * <h3>⚠️ content 是「JSON 字符串」，不是对象</h3>
     * <pre>
     *   msg_type=image → content = {"image_key":"img_xxx"}
     *   msg_type=file  → content = {"file_key":"file_xxx"}
     * </pre>
     * 直接塞对象会被平台拒成 230001（见类注释）。这里让 Jackson 做转义，绝不手工拼字符串。
     *
     * @param ctx   只读上下文
     * @param text  正文（可为空 —— 纯附件投递时为空串）
     * @param media 已上传成功的媒体引用（{@link ImMediaRef#key()} = image_key / file_key / file_token）
     * @return 最后一条成功消息的 {@code message_id}（正文或附件的）
     * @throws ImSendException 正文发送失败，或（无正文时）附件一条都没发出去
     */
    @Override
    protected String doSendWithMedia(ImSendContext ctx, String text, List<ImMediaRef> media)
            throws ImSendException {
        boolean hasText = text != null && !text.isBlank();

        // ① 正文优先：正文失败 ⇒ 整体失败（正文比附件重要）
        String lastMessageId = null;
        if (hasText) {
            lastMessageId = doSend(ctx, text);
        }

        // ② 逐条发附件：单条失败不中断其余
        List<ImMediaRef> refs = media == null ? List.of() : media;
        List<String> failures = new ArrayList<>();
        int sent = 0;
        for (ImMediaRef ref : refs) {
            if (ref == null) {
                continue;
            }
            String key = ref.key();
            if (key == null || key.isBlank()) {
                failures.add("缺少媒体 key");
                continue;
            }
            try {
                String id = sendMedia(ctx, key, ref.kind());
                if (id != null) {
                    lastMessageId = id;
                }
                sent++;
            } catch (ImSendException e) {
                // 单条附件失败：记下来、继续发下一条（⛔ 不中断）
                failures.add(e.getMessage());
                if (log.isWarnEnabled()) {
                    log.warn("[ImSend] 飞书附件发送失败 instance={} kind={} code={} reason={}",
                            ctx.instanceId(), ref.kind(), e.code(), e.getMessage());
                }
            }
        }

        // ③ 没有正文、附件又一条没发出去 ⇒ 不能报「已发送」（否则是假成功）
        if (!hasText && sent == 0) {
            String why = failures.isEmpty() ? "没有可发送的附件" : String.join("；", failures);
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "飞书附件全部发送失败：" + why);
        }
        if (!failures.isEmpty() && log.isWarnEnabled()) {
            log.warn("[ImSend] 飞书部分附件发送失败 instance={} sent={} failed={}",
                    ctx.instanceId(), sent, failures.size());
        }
        return lastMessageId;
    }

    /**
     * 发一条媒体消息（{@code msg_type=image|file}）。
     *
     * <p>token 失效的处理与正文一致：清缓存换新票<b>重试一次</b>，再失败就如实抛出去。
     */
    private String sendMedia(ImSendContext ctx, String key, MediaKind kind) throws ImSendException {
        MediaKind effective = kind == null ? MediaKind.FILE : kind.forUpload();
        Credential credential = parseCredential(ctx.credential());
        String msgType = effective == MediaKind.IMAGE ? "image" : "file";
        String content = effective == MediaKind.IMAGE
                ? buildImageContent(key)
                : buildFileContent(key);

        String token = tokenService.tokenFor(ctx.instanceId(), credential.appId(), credential.appSecret());
        try {
            return postMessage(token, ctx.peerId(), msgType, content);
        } catch (ImSendException e) {
            if (!isTokenExpired(e.platformCode())) {
                throw e;
            }
            log.warn("[FeishuSend] tenant_access_token 失效（发媒体），清缓存后重试一次 instance={} platformCode={}",
                    ctx.instanceId(), e.platformCode());
            tokenService.invalidate(ctx.instanceId());
            String fresh = tokenService.tokenFor(
                    ctx.instanceId(), credential.appId(), credential.appSecret());
            try {
                return postMessage(fresh, ctx.peerId(), msgType, content);
            } catch (ImSendException retry) {
                if (isTokenExpired(retry.platformCode())) {
                    throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                            "飞书凭证失效：换新 tenant_access_token 后发送媒体仍被拒（platformCode="
                                    + retry.platformCode() + "）",
                            retry.platformCode(), false);
                }
                throw retry;
            }
        }
    }

    // ═════════════════════ 内部 ═════════════════════

    /**
     * 解析实例凭证 JSON（取 appId / appSecret）。
     *
     * <p>⛔ 凭证可能是历史遗留的非法 JSON、也可能缺字段 —— 全部在这里判空并抛
     * {@link ImSendException}，绝不把 {@code null} 传到下游变成 NPE。
     */
    private Credential parseCredential(String credentialJson) throws ImSendException {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED", "飞书凭证为空，无法发送");
        }
        JsonNode root;
        try {
            root = json.readTree(credentialJson);
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证不是合法 JSON，无法发送", null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证不是 JSON 对象，无法发送", null, false);
        }
        // 主键名是扫码建应用时写入的 appId / appSecret；同时兼容下划线写法
        // （历史上手工录入过凭证，键名不统一）。
        String appId = text(root, "appId", "app_id");
        String appSecret = text(root, "appSecret", "app_secret");
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书凭证缺少 appId 或 appSecret，无法发送", null, false);
        }
        return new Credential(appId, appSecret);
    }

    /**
     * 按**本次实际身份**取票。
     *
     * <p>用户身份 ⇒ 用 OAuth 拿到的 user_access_token
     * （它只能代表授权的那个人；没有它就只能机器人身份，见 {@link #effectiveIdentity}）。
     * 机器人身份 ⇒ tenant_access_token。
     *
     * <p>⛔ 走向 {@code userTokenOf} 之前已经由 {@link #effectiveIdentity} 保证「有票」，
     * 这里再判一次只为防并发撤销（用户刚好在这一瞬间点了「取消授权」）。
     */
    private String tokenFor(ImSendContext ctx, Credential credential) throws ImSendException {
        if (ctx.asUser()) {
            String userToken = channelService == null ? null
                    : channelService.userTokenOf(ctx.instanceId()).orElse(null);
            if (userToken != null && !userToken.isBlank()) {
                return userToken;
            }
            throw new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED",
                    "飞书用户授权已失效（可能在别处被取消），请重新授权后再以用户身份发送", null, false);
        }
        return tokenService.tokenFor(ctx.instanceId(), credential.appId(), credential.appSecret());
    }

    /** 发一条文本消息并解析响应。 */
    private String postMessage(String token, String chatId, String text) throws ImSendException {
        return postMessage(token, chatId, "text", buildTextContent(text));
    }

    /**
     * 发一条消息并解析响应。
     *
     * @param msgType {@code text} / {@code image} / {@code file}
     * @param content <b>JSON 字符串</b>（不是对象！见类注释）
     */
    private String postMessage(String token, String chatId, String msgType, String content)
            throws ImSendException {
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(HttpRequest.newBuilder(URI.create(sendUrl(receiveIdTypeOf(chatId))))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    buildBody(chatId, msgType, content), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "飞书发送请求被中断", null, true);
        } catch (Exception e) {
            // 网络类故障值得重试；⛔ 不拼 e.getMessage()（可能含请求片段）
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书发送请求失败：" + e.getClass().getSimpleName(), null, true);
        }

        int status = resp.statusCode();
        String raw = resp.body() == null ? "" : resp.body();

        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书响应不是合法 JSON（HTTP " + status + "）：" + snippet(raw), null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书响应不是 JSON 对象（HTTP " + status + "）：" + snippet(raw), null, false);
        }

        JsonNode codeNode = root.get("code");
        if (codeNode == null || codeNode.isNull()) {
            // 缺 code = 响应形状不对，不能当成功（否则会把"没发出去"报成已发送）
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书响应缺少 code 字段（HTTP " + status + "）", null, false);
        }
        int code = codeNode.asInt(Integer.MIN_VALUE);
        if (code != 0) {
            String msg = root.path("msg").asText("");
            // 原始码保留在 platformCode（重试/定位依赖它），另附一句中文可执行提示：
            // 用户看到的不能只有「code=99991672」，得知道下一步去开权限还是重新授权。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书拒绝发送：code=" + code + " msg=" + snippet(msg) + sendHint(code),
                    String.valueOf(code), false);
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            // code=0 却没有 data：响应形状异常，如实报错而不是假装成功
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "飞书返回 code=0 但响应缺少 data 字段（HTTP " + status + "）", "0", false);
        }
        JsonNode messageId = data.get("message_id");
        if (messageId == null || messageId.isNull()) {
            // 消息已发出，只是平台没回 id —— 允许返回 null
            return null;
        }
        String id = messageId.asText("");
        return id.isEmpty() ? null : id;
    }

    /**
     * 完整发送 URL（基址 + 路径 + 接收方类型）。
     *
     * @param receiveIdType 由 {@link #receiveIdTypeOf} 按 peerId 形态推断
     */
    private String sendUrl(String receiveIdType) {
        return baseUrl + SEND_PATH + "?receive_id_type=" + receiveIdType;
    }

    /**
     * 构造请求体。
     *
     * <p>🔴 {@code content} 是「JSON 序列化后的字符串」，不是对象 —— 见类注释。
     * 这里让 Jackson 做转义（换行、引号、Unicode 都不会破 JSON）。
     *
     * @param msgType {@code text} / {@code image} / {@code file}
     * @param content 已经是 JSON 字符串的内容体
     */
    private String buildBody(String chatId, String msgType, String content) {
        ObjectNode body = json.createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", msgType);
        body.put("content", content == null ? "{}" : content);
        return body.toString();
    }

    /** 文本消息的 content（JSON 字符串）。 */
    private String buildTextContent(String text) {
        ObjectNode inner = json.createObjectNode();
        inner.put("text", text == null ? "" : text);
        return inner.toString();
    }

    /** 图片消息的 content（JSON 字符串）：{@code {"image_key":"..."}}。 */
    private String buildImageContent(String imageKey) {
        ObjectNode inner = json.createObjectNode();
        inner.put("image_key", imageKey);
        return inner.toString();
    }

    /** 文件消息的 content（JSON 字符串）：{@code {"file_key":"..."}}。 */
    private String buildFileContent(String fileKey) {
        ObjectNode inner = json.createObjectNode();
        inner.put("file_key", fileKey);
        return inner.toString();
    }

    /**
     * 常见拒绝码 → 一句「该怎么办」的中文提示（拼在原始 code/msg 之后）。
     *
     * <p>只做解释，不改判定：能否重试、要不要清缓存仍由 {@link #isTokenExpired} 等逻辑决定。
     * 未列出的码返回空串（原文保留，便于对飞书文档）。
     */
    private static String sendHint(int code) {
        return switch (code) {
            case 99991672 -> "（缺少所需权限 scope：请让管理员在开放平台为应用开通 "
                    + "im:message.send_as_user 等权限；若用本人身份发送，开通后需重新点「授权本人身份」刷新授权范围）";
            case 99991663, 99991661, 99991668 -> "（令牌无效或已过期：机器人身份会自动换票重试一次；"
                    + "本人身份请重新授权）";
            case 230001, 230013 -> "（接收方 id 无效：私聊/群请用 oc_ 开头的 chat_id，"
                    + "通讯录里的人请用 ou_ 开头的 open_id）";
            case 230002 -> "（机器人不在该会话里：请把机器人拉进群，或让对方先跟机器人发起会话）";
            default -> "";
        };
    }

    /** 平台码是否是「token 失效」这一族（可换票重试）。 */
    private static boolean isTokenExpired(String platformCode) {
        return platformCode != null && TOKEN_EXPIRED_CODES.contains(platformCode.trim());
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

    /** 截断原文（异常 message 不接受整段响应体）。 */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
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
