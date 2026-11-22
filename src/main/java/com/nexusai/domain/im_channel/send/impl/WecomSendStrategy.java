package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMediaApi;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.MediaKind;
import com.nexusai.domain.im_channel.send.SendCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 企业微信主动发送策略。
 *
 * <h2>发一条消息要三步（缺一不可）</h2>
 * <ol>
 *   <li>把库里的 bot 凭证（{@code botId} + {@code botSecret}）换成 CLI token
 *       —— 由 {@link WecomCliTokenService} 负责（它还管缓存与单飞）；</li>
 *   <li>用 CLI token 调发送端点 —— 由 {@link WecomMessageApi} 负责；</li>
 *   <li>企微不返回 message_id ⇒ 本策略返回 {@code null}（不编造 id）。</li>
 * </ol>
 *
 * <h2>🔴 两个端点 = 两种身份（别混）</h2>
 * <table border="1">
 *   <caption>企微发送端点与身份</caption>
 *   <tr><th>端点</th><th>身份</th><th>msg_type</th></tr>
 *   <tr><td>{@code /cli/message/aibot/send}</td><td><b>机器人</b></td>
 *       <td>markdown / image / file / voice / video</td></tr>
 *   <tr><td>{@code /cli/message/send}</td><td><b>授权真人</b></td><td>只有 text</td></tr>
 * </table>
 * <p>⇒ <b>默认（机器人身份）必须走 {@code aibot/send}</b>：{@code message/send} 是以真人的名义发，
 * AI 若走它，对方看到的发送者是**用户本人** —— 那是冒充本人发消息。
 * <p>⇒ 同一个 {@code chat_id} 两个端点通用（真机已验）。
 *
 * <h2>带附件时多两段（两段式，PIT-WC-025）</h2>
 * <ol>
 *   <li>先上传：{@code POST /cli/media/upload} 拿 {@code media_id}
 *       —— 由 {@link WecomMediaApi} 负责（见 {@link #mediaUploader()}）；</li>
 *   <li>再发：{@code POST /cli/message/aibot/send}，{@code msg_type=image|file}
 *       + {@code media_id}。</li>
 * </ol>
 * ⚠️ <b>附件永远是机器人身份</b>：用户身份端点（{@code message/send}）的 {@code msg_type}
 * enum 只有 {@code text}，发不了媒体。⇒ 「正文用用户身份 + 附件用机器人身份」是**混合身份**，
 * 结果里的 {@code identityNote} 会写明这一点。
 *
 * <h2>为什么能力是「可主动发 + 支持群聊」</h2>
 * <ul>
 *   <li><b>可主动发</b>：企微的发消息端点不需要入站上下文
 *       （与微信 iLink 必须带 {@code reply_token} 完全不同）；</li>
 *   <li><b>支持群聊</b>：{@code chat_id} 字段单聊/群聊通用（schema 原文：「单聊传接收成员的
 *       userid，群聊传群会话 ID」）；</li>
 *   <li><b>文本上限 2048</b>：schema {@code TextContent.content.maxLength}。</li>
 * </ul>
 *
 * <h2>token 失效怎么办（853004）</h2>
 * 企微的 CLI token 会失效。平台回 {@code 853004} 时，本策略：
 * <b>作废缓存 → 重新换票 → 重放 1 次</b>。⛔ <b>只重放一次</b>：官方 CLI 的
 * {@code auth/session.rs} 就是这个策略（用同源 bot 凭证静默重换），循环重换会在凭证本身失效时
 * 把企微接口打爆。
 * <p>文本 / 上传 / 发附件三条路共用这一套（见 {@link #withTokenReplay}）。
 */
@Component
public class WecomSendStrategy extends AbstractImSendStrategy {

    private static final Logger log = LoggerFactory.getLogger(WecomSendStrategy.class);

    /** 平台标识（与 {@code ImChannelService.PLATFORMS} 对齐）。 */
    private static final String PLATFORM = "wecom";

    /** 文本上限：schema {@code TextContent.content.maxLength}。 */
    private static final int MAX_TEXT_CHARS = 2048;

    /** 平台码：token 失效（官方 CLI {@code auth/session.rs} 的重换信号）。 */
    private static final String PLATFORM_CODE_TOKEN_EXPIRED = "853004";

    /** 凭证里 bot id 的字段名（{@code WecomQrStrategy} 落库时用的键）。 */
    private static final String CREDENTIAL_FIELD_BOT_ID = "botId";
    /** 凭证里 bot secret 的字段名（{@code WecomQrStrategy} 落库时用的键）。 */
    private static final String CREDENTIAL_FIELD_BOT_SECRET = "botSecret";

    private final WecomCliTokenService tokenService;
    private final WecomMessageApi messageApi;
    /** 媒体（图片 / 文件）上传与发送；附件通道的协议实现。 */
    private final WecomMediaApi mediaApi;
    private final ObjectMapper json = new ObjectMapper();

    /** 构造注入。 */
    public WecomSendStrategy(WecomCliTokenService tokenService, WecomMessageApi messageApi,
                              WecomMediaApi mediaApi) {
        if (tokenService == null || messageApi == null || mediaApi == null) {
            throw new IllegalArgumentException("WecomCliTokenService、WecomMessageApi 与 WecomMediaApi 必填");
        }
        this.tokenService = tokenService;
        this.messageApi = messageApi;
        this.mediaApi = mediaApi;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public SendCapability capability() {
        // 可主动发 + 支持群聊（chat_id 单聊/群聊同字段）
        return SendCapability.proactive(MAX_TEXT_CHARS, true);
    }

    /**
     * 企微<b>能</b>以用户身份发。
     *
     * <p>官方 schema 逐字：
     * <ul>
     *   <li>{@code /cli/message/aibot/send} = 「<b>以智能机器人身份</b>向指定单聊/群聊会话发送消息」</li>
     *   <li>{@code /cli/message/send} = 以<b>授权真人</b>身份发（对方看到的发送者是本人）</li>
     * </ul>
     * ⇒ 企微是当前唯一有「用户身份端点」的平台。
     *
     * <p>⚠️ 但用户身份端点的 {@code msg_type} enum <b>只有 text</b> ⇒ 发不了媒体
     * （见 {@link #userIdentitySupportsMedia()} 的默认 false）。
     */
    @Override
    public boolean canSendAsUser() {
        return true;
    }

    /**
     * 真正发送（按<b>发送身份</b>走不同端点）。
     *
     * <h3>🔴 为什么机器人身份不能走 {@code /cli/message/send}</h3>
     * {@code /cli/message/send} 是「<b>授权真人</b>身份」发消息 —— 对方在 IM 里看到的发送者
     * 是**用户本人**，不是机器人。而 {@code /cli/message/aibot/send} 才是
     * 「<b>以智能机器人身份</b>发送」。⇒ AI 的默认通知必须走 aibot/send，
     * 否则用户会在自己的聊天记录里看到"自己发的"消息 —— 那是冒充本人。
     *
     * @param ctx  只读上下文（{@code credential} 是解密后的根凭证 JSON；{@code identity} 已由模板收敛为实际身份）
     * @param text 已按能力上限归一过的文本（保证非空、≤ 2048）
     * @return 恒为 {@code null}（企微不返回消息 id）
     * @throws ImSendException 凭证非法 / 换票失败 / 平台拒绝
     */
    @Override
    public String doSend(ImSendContext ctx, String text) throws ImSendException {
        // ① 解析凭证：库里存的是根凭证 {"botId":..., "botSecret":...}
        WecomCredential credential = parseCredential(ctx.credential());

        // ② 换 CLI token（带缓存与单飞；⛔ token 不进日志/异常）→ 发消息
        withTokenReplay(ctx, credential, "企微发送消息", token -> {
            if (ctx.asUser()) {
                // 用户身份：/cli/message/send（msg_type 只有 text）
                messageApi.sendText(token, ctx.peerId(), text);
            } else {
                // 机器人身份：/cli/message/aibot/send（msg_type=markdown）
                messageApi.sendMarkdown(token, ctx.peerId(), text);
            }
            return null;
        });
        // ③ 企微不返回 message_id ⇒ 不编造 id（编出来的 id 在平台侧根本不存在）
        return null;
    }

    // ═════════════════════ 附件（图片 / 文件） ═════════════════════

    /**
     * 附件上传器。
     *
     * <p>为什么用适配器而不是让 {@link WecomMediaApi} 直接实现 {@link ImMediaUploader}：
     * 换票与「token 失效重放一次」这套逻辑归本策略管（它是<b>唯一一个</b>持有
     * {@link WecomCliTokenService} 的地方），协议层只负责「拿 token 之后怎么调」。
     * 这样「凭证怎么解析」「票怎么换」「失效怎么重试」只有一处实现。
     */
    @Override
    protected ImMediaUploader mediaUploader() {
        return new WecomMediaUploader();
    }

    /**
     * 发「正文 + 附件」。
     *
     * <h3>顺序：先正文，再逐条附件</h3>
     * <p>正文是主内容 ⇒ 正文发不出去就是整体失败（{@link ImSendException} 直接上抛）。
     * 附件则是**逐条独立**：一条挂了不影响其余（一条 PDF 被平台拒不该让同批的图片也发不出去）。
     *
     * <h3>⚠️ 正文与附件的身份可能不同</h3>
     * <p>正文走 {@link #doSend} —— 它按 {@code ctx} 的**实际身份**选端点
     * （机器人身份 ⇒ {@code /cli/message/aibot/send} + markdown；用户身份 ⇒
     * {@code /cli/message/send} + text）。
     * <p>附件**一律**走 {@code /cli/message/aibot/send}（机器人身份）——
     * 因为用户身份端点的 {@code msg_type} enum 只有 {@code text}，发不了媒体。
     * ⇒ 「正文用户身份 + 附件机器人身份」是**混合身份**，由模板方法的
     * {@code identityNote} 在结果里写明（⛔ 不静默混用）。
     *
     * @param ctx   只读上下文
     * @param text  正文（可为空 —— 纯附件投递时为空串）
     * @param media 已上传成功的媒体引用（{@link ImMediaRef#key()} = media_id）
     * @return 恒为 {@code null}（企微不返回消息 id）
     * @throws ImSendException 正文发送失败，或（无正文时）附件全部发送失败
     */
    @Override
    protected String doSendWithMedia(ImSendContext ctx, String text, List<ImMediaRef> media)
            throws ImSendException {
        boolean hasText = text != null && !text.isBlank();

        // ① 正文优先：正文失败 ⇒ 整体失败（正文比附件重要）
        if (hasText) {
            doSend(ctx, text);
        }

        // ② 逐条发附件：单条失败不中断其余
        List<ImMediaRef> refs = media == null ? List.of() : media;
        List<String> failures = new ArrayList<>();
        int sent = 0;
        if (!refs.isEmpty()) {
            WecomCredential credential = parseCredential(ctx.credential());
            for (ImMediaRef ref : refs) {
                if (ref == null) {
                    continue;
                }
                String mediaId = ref.key();
                if (mediaId == null || mediaId.isBlank()) {
                    failures.add("缺少 media_id");
                    continue;
                }
                try {
                    withTokenReplay(ctx, credential, "企微发送媒体", token -> {
                        mediaApi.sendMedia(token, ctx.peerId(), mediaId, ref.kind());
                        return null;
                    });
                    sent++;
                } catch (ImSendException e) {
                    // 单条附件失败：记下来、继续发下一条（⛔ 不中断）
                    failures.add(e.getMessage());
                    if (log.isWarnEnabled()) {
                        log.warn("[ImSend] 企微附件发送失败 instance={} kind={} code={} reason={}",
                                ctx.instanceId(), ref.kind(), e.code(), e.getMessage());
                    }
                }
            }
        }

        // ③ 没有正文、附件又一条没发出去 ⇒ 不能报「已发送」（否则是假成功）
        if (!hasText && sent == 0) {
            String why = failures.isEmpty() ? "没有可发送的附件" : String.join("；", failures);
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "企微附件全部发送失败：" + why);
        }
        if (!failures.isEmpty() && log.isWarnEnabled()) {
            log.warn("[ImSend] 企微部分附件发送失败 instance={} sent={} failed={}",
                    ctx.instanceId(), sent, failures.size());
        }
        // ④ 企微不返回 message_id ⇒ 恒为 null
        return null;
    }

    /**
     * 企微附件上传适配器：把 {@link ImMediaUploader} 的「凭证 + 本地文件」语义
     * 翻译成协议层的「CLI token + media_id」。
     */
    private final class WecomMediaUploader implements ImMediaUploader {

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public boolean supports(MediaKind kind) {
            return mediaApi.supports(kind);
        }

        /**
         * 上传一个本地文件，换回平台侧的 media_id。
         *
         * <p>⛔ 文件内容、botSecret、token 都不进日志与异常消息。
         */
        @Override
        public ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind) throws ImSendException {
            WecomCredential credential = parseCredential(ctx.credential());
            String mediaId = withTokenReplay(ctx, credential, "企微上传媒体",
                    token -> mediaApi.upload(token, file, kind));

            // raw 的形状与 ImMediaRef 的契约一致：{"kind":"image"|"file","key":"<media_id>"}
            ObjectNode raw = json.createObjectNode();
            raw.put("kind", kind == MediaKind.IMAGE ? WecomMediaApi.TYPE_IMAGE : WecomMediaApi.TYPE_FILE);
            raw.put("key", mediaId);
            return new ImMediaRef(kind, raw);
        }
    }

    /**
     * 带「token 失效重放一次」的调用模板。
     *
     * <h3>为什么要有这一层</h3>
     * <p>企微 CLI token 会失效（平台码 853004）。官方 CLI 的 {@code auth/session.rs} 的策略是
     * 「用同源 bot 凭证静默重换后重放 1 次」。<b>文本发送、上传附件、发附件</b>三条路都要这个策略，
     * 集中在这里 ⇒ 只有一处实现，不会出现「有的路重试、有的路不重试」。
     *
     * <p>⛔ <b>只重放一次</b>：凭证本身失效时循环重换会把企微接口打爆。
     *
     * @param ctx        上下文（用于换票与日志定位）
     * @param credential 已解析的 bot 凭证
     * @param what       动作描述（失败原因前缀）
     * @param call       真正的调用（入参是 CLI token）
     * @return 调用结果
     * @throws ImSendException 调用失败（非 token 失效时原样上抛；重放后仍失败也上抛）
     */
    private <T> T withTokenReplay(ImSendContext ctx, WecomCredential credential, String what,
                                  TokenCall<T> call) throws ImSendException {
        // 换 CLI token（带缓存与单飞；⛔ token 不进日志/异常）
        String token = tokenService.tokenFor(ctx.instanceId(), credential.botId(), credential.botSecret());
        try {
            return call.apply(token);
        } catch (ImSendException e) {
            // token 失效（853004）⇒ 作废缓存、重新换票、重放 1 次。⛔ 只重放一次，不循环。
            if (!isTokenExpired(e)) {
                throw e;
            }
            if (log.isWarnEnabled()) {
                log.warn("[ImSend] 企微 token 失效，重新换票后重放一次 instance={} what={} platformCode={}",
                        ctx.instanceId(), what, e.platformCode());
            }
            tokenService.invalidate(ctx.instanceId());
            String freshToken = tokenService.tokenFor(
                    ctx.instanceId(), credential.botId(), credential.botSecret());
            return call.apply(freshToken);
        }
    }

    /** 「拿 token 去调平台」的函数式接口（允许抛 {@link ImSendException}）。 */
    @FunctionalInterface
    private interface TokenCall<T> {
        T apply(String token) throws ImSendException;
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 是否是「token 失效」信号。
     *
     * <p>判据 = 错误码 {@code IM_CREDENTIAL_EXCHANGE_FAILED} <b>且</b> 平台码 {@code 853004}。
     * 两个条件都要，因为 {@code IM_CREDENTIAL_EXCHANGE_FAILED} 也可能来自换票环节本身
     * （网络失败）—— 那种情况重放没有意义。
     */
    private static boolean isTokenExpired(ImSendException e) {
        return e != null
                && "IM_CREDENTIAL_EXCHANGE_FAILED".equals(e.code())
                && PLATFORM_CODE_TOKEN_EXPIRED.equals(e.platformCode());
    }

    /**
     * 解析根凭证 JSON，取 {@code botId} / {@code botSecret}。
     *
     * <p>⛔ 任何一步不合法都抛 {@link ImSendException}（{@code IM_PARAM_INVALID}），
     * 绝不 NPE、也绝不在异常消息里带上 secret。
     *
     * <p>字段名：主用 {@code botId}/{@code botSecret}（{@code WecomQrStrategy} 落库时的键）；
     * 兼容平台侧的另一种拼写 {@code botid}/{@code secret}（{@code bot_info} 里的原始键名）。
     */
    private WecomCredential parseCredential(String credentialJson) throws ImSendException {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企业微信凭证为空（实例未配置凭证）");
        }
        JsonNode root;
        try {
            root = json.readTree(credentialJson);
        } catch (Exception e) {
            // ⛔ 不把原始 JSON 贴进消息（里面可能有别的敏感字段）。
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企业微信凭证不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企业微信凭证不是 JSON 对象");
        }
        String botId = text(root, CREDENTIAL_FIELD_BOT_ID);
        if (botId == null) {
            botId = text(root, "botid");
        }
        String botSecret = text(root, CREDENTIAL_FIELD_BOT_SECRET);
        if (botSecret == null) {
            botSecret = text(root, "secret");
        }
        if (botId == null || botId.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企业微信凭证缺少 botId");
        }
        if (botSecret == null || botSecret.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "企业微信凭证缺少 botSecret");
        }
        return new WecomCredential(botId.trim(), botSecret.trim());
    }

    /** 取字符串字段（空白视为没有）。 */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String v = node.asText();
        return v == null || v.isBlank() ? null : v;
    }

    /** 凭证里的两个字段（⛔ 不覆写 toString，避免 secret 被顺手打出来）。 */
    private record WecomCredential(String botId, String botSecret) {
    }
}
