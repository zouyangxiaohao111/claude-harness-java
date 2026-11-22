package com.nexusai.domain.im_channel.send;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板方法：把「发一条消息」的固定流程钉死，只把协议差异留给子类。
 *
 * <h2>七步流程（子类不可改）</h2>
 * <ol>
 *   <li>入参判空（{@code ctx == null} / {@code text} 空白 ⇒ 直接 FAILED，不抛异常）</li>
 *   <li><b>能力检查</b>：平台不能主动发 ⇒ SKIPPED；微信缺 token ⇒ SKIPPED
 *       —— ⛔ 此时**一个请求都不发**</li>
 *   <li>文本归一：超长按 {@link SendCapability#maxTextChars()} 截断并标注</li>
 *   <li>{@link #doSend}（子类唯一实现点）</li>
 *   <li>成功 ⇒ SENT；{@link ImSendException} ⇒ FAILED（用异常自带的 code）</li>
 *   <li>其它异常 ⇒ FAILED（兜底，防一个 Runtime 异常炸掉整个批量投递）</li>
 * </ol>
 *
 * <h2>为什么 send() 不抛异常</h2>
 * 批量投递里一个目标失败不能中断其余目标（设计 R1）。若 {@code send()} 抛异常，
 * 调用方每个目标都得包 try/catch，很容易漏 ⇒ 漏了就是一个目标炸掉整批。
 * 这里统一返回 {@link ImSendResult}，调用方无需 try。
 */
public abstract class AbstractImSendStrategy implements ImSendStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractImSendStrategy.class);

    /** 截断后追加的提示（让收到消息的人知道内容不完整）。 */
    private static final String TRUNCATED_SUFFIX = "\n\n…（内容超长已截断）";

    /**
     * 发送（final：流程固定）。
     *
     * @param ctx  上下文（可 null —— 会返回 FAILED 而不是 NPE）
     * @param text 正文（可 null/空白 —— 与附件都不存在时才 FAILED）
     * @return 永远非 null
     */
    @Override
    public final ImSendResult send(ImSendContext ctx, String text) {
        return send(ctx, text, java.util.List.of());
    }

    /**
     * 带附件的发送（final：流程固定）。
     *
     * <p>相比纯文本多一步「上传附件」，其余七步完全一样。上传失败**不阻断**正文
     * （见 {@link ImSendResult#attachmentErrors()}）。
     */
    @Override
    public final ImSendResult send(ImSendContext ctx, String text, java.util.List<ImSendAttachment> attachments) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasAttachment = attachments != null && !attachments.isEmpty();

        // ── 1. 入参判空（文本与附件至少一个）──
        if (ctx == null) {
            return ImSendResult.failed("IM_PARAM_INVALID", "发送上下文为空（内部错误）");
        }
        if (!hasText && !hasAttachment) {
            return ImSendResult.failed("IM_PARAM_INVALID", "消息内容为空，未发送");
        }

        // ── 2. 能力检查（发请求之前）──
        SendCapability cap;
        try {
            cap = capability();
        } catch (RuntimeException e) {
            // capability() 是静态声明，抛异常说明实现有 bug；不能让它炸掉整批。
            log.error("[ImSend] capability() 异常 platform={}", platform(), e);
            return ImSendResult.failed("IM_SEND_PLATFORM_ERROR", "平台能力声明异常");
        }
        if (cap == null) {
            log.error("[ImSend] capability() 返回 null platform={}", platform());
            return ImSendResult.failed("IM_SEND_PLATFORM_ERROR", "平台能力未声明");
        }
        if (!cap.canSendProactively()) {
            return ImSendResult.skipped("IM_SEND_UNSUPPORTED", cap.unsupportedReason());
        }
        if (cap.needsReplyToken() && !ctx.hasReplyToken()) {
            return ImSendResult.skipped("IM_REPLY_TOKEN_MISSING",
                    "需要对方先给机器人发一条消息（当前会话没有可用的回复令牌）");
        }

        // ── 2.5 身份收敛（请求「以用户名义」但平台不支持 ⇒ 降级为机器人身份）──
        //   ⛔ 降级必须**告知**：ImSendResult.identity() 报的是**实际**身份，
        //      identityNote 说明为什么降级 —— 否则调用方（AI）会以为「我以用户身份发出去了」，
        //      而对方看到的其实是机器人：用户会背一个自己没说过的话。
        SendIdentity effective = effectiveIdentity(ctx, hasText, hasAttachment);
        String identityNote = identityNote(ctx, effective, hasText, hasAttachment);
        // 下游（doSend / doSendWithMedia）看到的必须是**实际**身份
        ImSendContext sendCtx = effective == ctx.identity() ? ctx : ctx.withIdentity(effective);

        // ── 3. 文本归一 ──
        String body = hasText ? normalizeText(text, cap.maxTextChars()) : "";

        // ── 4. 上传附件（逐条；单条失败不阻断其余，也不阻断正文）──
        List<ImMediaRef> refs = new java.util.ArrayList<>();
        List<String> mediaErrors = new java.util.ArrayList<>();
        if (hasAttachment) {
            ImMediaUploader uploader = mediaUploader();
            if (uploader == null) {
                // 平台没有媒体通道：不能假装支持。文本照发，附件记失败。
                for (ImSendAttachment a : attachments) {
                    if (a != null) {
                        mediaErrors.add(a.name() + "：该平台不支持发送附件");
                    }
                }
            } else {
                for (ImSendAttachment a : attachments) {
                    if (a == null) {
                        continue;
                    }
                    MediaKind kind = a.kindOrDefault();
                    if (!uploader.supports(kind)) {
                        mediaErrors.add(a.name() + "：该平台不支持这种媒体类型");
                        continue;
                    }
                    try {
                        ImMediaRef ref = uploader.upload(sendCtx, a.path(), kind.forUpload());
                        if (ref == null) {
                            mediaErrors.add(a.name() + "：上传后未返回媒体引用");
                        } else {
                            refs.add(ref);
                        }
                    } catch (ImSendException e) {
                        mediaErrors.add(a.name() + "：" + e.getMessage());
                        if (log.isWarnEnabled()) {
                            log.warn("[ImSend] 附件上传失败 platform={} instance={} file={} code={} reason={}",
                                    platform(), ctx.instanceId(), a.name(), e.code(), e.getMessage());
                        }
                    } catch (RuntimeException e) {
                        mediaErrors.add(a.name() + "：上传过程出现内部错误");
                        log.error("[ImSend] 附件上传抛未预期异常 platform={} instance={} file={}",
                                platform(), ctx.instanceId(), a.name(), e);
                    }
                }
            }
        }

        // ── 5. 既没有正文也没有任何附件上传成功 ⇒ 没什么可发的 ──
        if (!hasText && refs.isEmpty()) {
            String why = mediaErrors.isEmpty() ? "没有可发送的内容" : String.join("；", mediaErrors);
            return failedWithIdentity("IM_SEND_PLATFORM_ERROR",
                    "附件全部失败，未发送：" + why, effective, identityNote);
        }

        // ── 6. 发送 + 归一 ──
        try {
            String messageId = refs.isEmpty()
                    ? doSend(sendCtx, body)
                    : doSendWithMedia(sendCtx, body, refs);
            if (log.isInfoEnabled()) {
                log.info("[ImSend] 发送成功 platform={} instance={} peer={} msgId={} attachments={} mediaErrors={} identity={}",
                        platform(), ctx.instanceId(), ctx.peerId(), messageId, refs.size(), mediaErrors.size(), effective);
            }
            return ImSendResult.sent(messageId, mediaErrors, effective, identityNote);
        } catch (ImSendException e) {
            if (log.isWarnEnabled()) {
                // ⛔ 不打印 body / 凭证，只打平台码与原因
                log.warn("[ImSend] 发送失败 platform={} instance={} code={} platformCode={} reason={}",
                        platform(), ctx.instanceId(), e.code(), e.platformCode(), e.getMessage());
            }
            // ⚠️ 失败也要带上身份：调用方可能正在排查"这条到底是谁说的"，
            //    若失败路径丢了 identity，就只能靠猜。
            return failedWithIdentity(e.code(), e.getMessage(), effective, identityNote);
        } catch (RuntimeException e) {
            // 兜底：策略实现里的空指针 / 解析异常不能中断批量投递
            log.error("[ImSend] 发送抛未预期异常 platform={} instance={}",
                    platform(), ctx.instanceId(), e);
            return failedWithIdentity("IM_SEND_PLATFORM_ERROR", "发送过程出现内部错误", effective, identityNote);
        }
    }

    /**
     * 构造一个**带身份**的失败结果。
     *
     * <p>为什么失败也要带身份：身份是在「发请求之前」就收敛的（可能已发生降级），
     * 失败路径丢掉它 ⇒ 调用方看到"失败"却不知道"本来想以谁的身份发、实际会用谁的身份"，
     * 排查时会怀疑错方向。
     */
    private static ImSendResult failedWithIdentity(String code, String reason,
                                                   SendIdentity identity, String identityNote) {
        return new ImSendResult(ImSendResult.Status.FAILED, null, code, reason,
                List.of(), identity, identityNote);
    }

    /**
     * 本平台的上传器；默认 {@code null} = 该平台没有媒体通道。
     *
     * <p>子类覆写并在构造期注入自家实现（飞书/企微/微信各一个）。
     */
    protected ImMediaUploader mediaUploader() {
        return null;
    }

    /**
     * 本次发送<b>实际</b>使用的身份。
     *
     * <h2>判定顺序</h2>
     * <ol>
     *   <li>请求的不是「用户身份」⇒ 原样（机器人身份）；</li>
     *   <li>请求用户身份但平台<b>没有</b>用户身份端点（{@link ImSendStrategy#canSendAsUser()}
     *       为 false）⇒ 降级为机器人身份；</li>
     *   <li>请求用户身份、平台支持，但<b>只有附件没有正文</b>⇒ 降级为机器人身份
     *       （平台的用户身份端点通常只支持纯文本，见 {@link #userIdentitySupportsMedia()}）。</li>
     * </ol>
     *
     * <p>⚠️ 「有正文 + 有附件」时**不降级**：正文保持用户身份，附件那部分由
     * {@link #identityNote} 说明是机器人身份发的（企微的消息/端点只有 text）。
     *
     * @param ctx          原始上下文（含请求的身份）
     * @param hasText      本次是否带正文
     * @param hasAttachment 本次是否带附件
     */
    protected SendIdentity effectiveIdentity(ImSendContext ctx, boolean hasText, boolean hasAttachment) {
        SendIdentity want = ctx.identity() == null ? SendIdentity.BOT : ctx.identity();
        if (!want.isUser()) {
            return want;
        }
        if (!canSendAsUser()) {
            return SendIdentity.BOT;
        }
        if (!hasText && hasAttachment && !userIdentitySupportsMedia()) {
            // 只有附件：用户身份端点发不了媒体 ⇒ 整条按机器人身份发
            return SendIdentity.BOT;
        }
        return want;
    }

    /**
     * 本平台的「用户身份端点」是否支持带媒体。
     *
     * <p>默认 false —— 绝大多数平台的用户身份端点只支持纯文本
     * （企微 {@code /cli/message/send} 的 {@code msg_type} enum 只有 {@code text}）。
     */
    protected boolean userIdentitySupportsMedia() {
        return false;
    }

    /**
     * 身份相关的说明（写进 {@link ImSendResult#identityNote()}）。
     *
     * <p>默认行为：请求用户身份但被降级 ⇒ 说明降级原因；正常用户身份 ⇒ 无说明。
     */
    protected String identityNote(ImSendContext ctx, SendIdentity effective,
                                  boolean hasText, boolean hasAttachment) {
        boolean wantedUser = ctx.identity() != null && ctx.identity().isUser();
        if (!wantedUser) {
            return null;
        }
        if (effective == SendIdentity.BOT) {
            if (!canSendAsUser()) {
                return "该平台不支持以用户名义发送，已降级为机器人身份";
            }
            return "只有附件时按机器人身份发出（用户身份端点只支持纯文本）";
        }
        // effective == USER
        if (hasAttachment && !userIdentitySupportsMedia()) {
            return "文本以用户身份发出；附件以机器人身份发出（平台限制：用户身份端点只支持纯文本）";
        }
        return null;
    }

    /**
     * 带媒体引用发送；默认退回纯文本（子类必须覆写才能发附件）。
     *
     * <p>为什么默认是「丢附件只发文本」而不是抛异常：附件传成功了但发送侧不支持组合，
     * 说明实现有 bug —— 这时**正文比附件重要**，先发正文，把问题暴露在日志里。
     */
    protected String doSendWithMedia(ImSendContext ctx, String text, List<ImMediaRef> media)
            throws ImSendException {
        if (log.isWarnEnabled()) {
            log.warn("[ImSend] 策略未实现 doSendWithMedia，附件被丢弃 platform={} count={}",
                    platform(), media == null ? 0 : media.size());
        }
        return doSend(ctx, text);
    }

    /**
     * 超长截断（保留开头，追加提示）。
     *
     * <p>⛔ 不静默丢弃：截断必须让收信人看出来内容不完整。
     * 若上限小到放不下提示语，就只截断不追加（避免"提示语比正文还长"）。
     */
    protected String normalizeText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int keep = maxChars - TRUNCATED_SUFFIX.length();
        if (keep <= 0) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, keep) + TRUNCATED_SUFFIX;
    }

    /** 平台中文名（给 SKIPPED 原因用；子类可覆写）。 */
    protected String platformLabel() {
        String p = platform();
        if (p == null) {
            return "该平台";
        }
        return switch (p) {
            case "wechat" -> "微信";
            case "feishu" -> "飞书";
            case "wecom" -> "企业微信";
            case "dingtalk" -> "钉钉";
            default -> p;
        };
    }
}
