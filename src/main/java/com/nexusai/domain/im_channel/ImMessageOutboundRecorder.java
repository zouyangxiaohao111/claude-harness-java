package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.MediaKind;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.domain.im_message.ImMessageService;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我们发出去的消息」补写进统一消息表（V315 {@code im_message}）· {@code direction='out'}。
 *
 * <h2>🔴 为什么必须有这个类（这是本仓 IM 链路最关键的缺口）</h2>
 * 企微的 {@code POST /cli/chat/messages/list} 返回的是「<b>用户视角的消息流</b>」，
 * 而我们通过 {@code /cli/message/aibot/send} 发出去的机器人消息<b>不在这个流里</b>（实测结论）。
 * ⇒ 如果不自己留一份，对话就是<b>断的</b>：用户看到别人的问题，看不到我们自己的回答；
 * agent 读历史时也只能看到「对方说了什么」，无法把「我们答了什么」接上。
 * <p>飞书/微信的读接口能读到自己发的（飞书还有 {@code om_xxx} id），
 * 但<b>统一视图</b>必须由本地表兜住 —— 读侧是「本地 ∪ 官方」（见
 * {@link com.nexusai.domain.im_message.ImMessageQueryService}），
 * 本地这份 {@code direction='out'} 就是「官方读不到的那一半」。
 *
 * <h2>为什么放在 {@code domain.im_channel} 而不是 {@code domain.im_message}</h2>
 * 依赖方向：{@code domain.im_message} → {@code domain.im_channel}（拉取层要读聊天目录 / 实例）
 * 是<b>既有的</b>单向依赖。把本类放进 {@code im_message} 会立刻形成<b>包级循环</b>
 * （{@code im_channel} 要调它写库、它又要用 {@code im_channel} 的发送结果与渠道服务）。
 * ⇒ 本类属于「<b>发送路径的收尾</b>」，与 {@link ImSendService} 同包，依赖单向无环。
 *
 * <h2>⛔ 本类<b>永不抛异常</b>（对外契约）</h2>
 * 消息<b>已经发出去了</b>（外部副作用已发生，且不可回滚）。
 * 本地记账失败只能是「我们少了一行历史」，绝不能变成「用户看到发送失败」，
 * 更不能把已经发出去的消息重发一遍。
 * ⇒ 整个方法体包在 try/catch 里，异常只 {@code log.warn}。
 *
 * <h2>三条硬规则（改这个类之前先读完）</h2>
 * <ol>
 *   <li><b>只在 {@code SENT} 时写</b>：{@code SKIPPED}（我们主动没发）/ {@code FAILED}（平台拒绝）
 *       都<b>没发出去</b> ⇒ 写进去就是假数据（agent 会读到一条根本没发生过的消息）。</li>
 *   <li><b>必须用平台返回的 {@code messageId}</b>：{@code ImMessageService.dedupKey(...)}
 *       在「有平台 id」时直接拿 id 当幂等键，在「没有」时算
 *       {@code sha256(chat_id|sent_at|sender_id|content)}。
 *       ⇒ 用错分支 ⇒ 同一条消息在库里出现两行（本地一行 + 官方拉回来一行），<b>且没有任何报错</b>。</li>
 *   <li><b>{@code sent_at} 必须走 {@link ImMessageTime}</b>：该列是 TEXT，区间过滤与排序全靠
 *       <b>字典序</b>比较 ⇒ 形态不统一（{@code +08:00} vs {@code Z}）会让排序/过滤
 *       <b>静默错乱</b>（不报任何错）。</li>
 * </ol>
 */
@Service
public class ImMessageOutboundRecorder {

    private static final Logger log = LoggerFactory.getLogger(ImMessageOutboundRecorder.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImMessageService messageService;
    @Autowired private ImChannelService channelService;

    /**
     * 补写一条 {@code direction='out'} 的本地消息。
     *
     * <p>⚠️ <b>本方法不抛异常</b>：写库失败只记日志，发送结果不受影响。
     *
     * @param chat        目标聊天（提供 instanceId / platform / <b>peerId</b>）
     * @param result      平台返回的结果（只有 {@code SENT} 才会写）
     * @param text        正文原文（可空 —— 纯附件投递时为空）
     * @param attachments 本次带的附件（可空；只存引用，⛔ 不存二进制）
     */
    public void record(ImChatEntry chat, ImSendResult result, String text,
                       List<ImSendAttachment> attachments) {
        // ① 只记「真的发出去了」的：SKIPPED / FAILED 一律不写（写了就是假数据）
        if (chat == null || result == null || !result.ok()) {
            return;
        }
        try {
            ImMessageEntry entry = build(chat, result, text, attachments);
            if (entry == null) {
                return;
            }
            // dedupKey 传 null ⇒ 由 ImMessageService 按统一规则推导
            //（有平台 id 用平台 id，没有用 hash）——⛔ 不在这里自己拼，
            //  拼错一个字段 ⇒ 幂等失效 ⇒ 同一句话存两行。
            boolean inserted = messageService.upsert(entry, null);
            log.info("[ImMessageOutbound] 已补写本地消息 instance={} platform={} chat={} msgId={} inserted={}",
                    entry.instanceId(), entry.platform(), entry.chatId(), entry.messageId(), inserted);
        } catch (RuntimeException e) {
            // ⛔ 记账失败绝不改判发送结果（消息已经发出去了，外部副作用不可回滚）
            log.warn("[ImMessageOutbound] 补写本地消息失败（发送结果不受影响）instance={} chat={} err={}",
                    chat.instanceId(), chat.peerId(), e.toString());
        }
    }

    // ═══════════════════════ 内部 ═══════════════════════

    private ImMessageEntry build(ImChatEntry chat, ImSendResult result, String text,
                                 List<ImSendAttachment> attachments) {
        String instanceId = chat.instanceId();
        // ⚠️ chat_id 存的是**平台侧会话 id**（peerId），不是本地目录 id（imch-xxx）：
        //    读侧（ImMessageQueryService）就是按 peerId 查本表的，存错 ⇒ 读不到自己写的这行。
        String chatId = chat.peerId();
        if (isBlank(instanceId) || isBlank(chatId)) {
            log.warn("[ImMessageOutbound] 缺 instanceId/chatId，跳过补写 chat={}", chat.id());
            return null;
        }

        // ── 发送者身份：按**实际**身份（result.identity()），不按请求身份 ──
        //    平台不支持用户身份时策略会降级成 BOT（ImSendResult.identity() 报的是实际身份）
        //    ⇒ 按请求身份写会把「机器人发的」标成「本人发的」（用户会背一句自己没说过的话）。
        SendIdentity actual = result.identity() == null ? SendIdentity.BOT : result.identity();
        String senderId;
        String senderName;
        if (actual.isUser()) {
            // 以授权真人名义 ⇒ 发送者就是他本人；没授权信息就留 null（不编造）
            senderId = channelService.userOpenIdOf(instanceId).orElse(null);
            senderName = channelService.userNameOf(instanceId).orElse(null);
        } else {
            // 机器人名义 ⇒ 发送者是「这个实例」，不是实例的主人
            senderId = instanceId;
            senderName = channelService.findById(instanceId)
                    .map(ImInstanceEntry::displayName)
                    .orElse(null);
        }

        boolean hasText = text != null && !text.isBlank();
        String content = hasText ? text : null;

        // ⚠️ messageId 必须用**平台返回的那个**（飞书 om_xxx / 微信 msg_id）。
        //    为空时（企微恒为 null —— 官方不返回消息 id）只能走 hash 分支，
        //    ⛔ 已知重复风险：hash 里含 sent_at（本地毫秒时间）与 content，
        //    所以「同一条消息被我们记两次」会算出两个键 ⇒ 库里两行。
        //    这在企微可接受：企微的读接口本来就读不到 bot 自己发的消息，不会再撞上官方那一份。
        String messageId = isBlank(result.messageId()) ? null : result.messageId();

        // ⭐ 只把**真正发出去的附件**算进去（上传失败的没到）
        //    ⇒ msg_type 与 media_json 用同一份清单，不会出现「mixed 但没有任何附件引用」的矛盾行
        List<ImSendAttachment> delivered = delivered(attachments, result.attachmentErrors());

        return new ImMessageEntry(
                null,                                   // 主键由持久化层生成
                instanceId,
                chat.platform(),
                chatId,
                messageId,
                senderId,
                senderName,
                msgType(hasText, delivered),
                content,
                mediaJson(delivered),
                // ⭐ sent_at 走 ImMessageTime 的规范形态（+08:00 定宽 29 字符）：
                //    canonicalNow() 与 canonical(...) 用的是同一套 formatter + 偏移 ⇒ 形态必然一致。
                ImMessageTime.canonicalNow(),
                ImMessageService.DIRECTION_OUT,
                ImMessageService.ORIGIN_LOCAL);
    }

    /**
     * 真正发出去的附件（把上传失败的剔掉）。
     *
     * @see #failed(ImSendAttachment, List)
     */
    private static List<ImSendAttachment> delivered(List<ImSendAttachment> attachments,
                                                     List<String> attachmentErrors) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ImSendAttachment> out = new ArrayList<>();
        for (ImSendAttachment a : attachments) {
            if (a != null && !failed(a, attachmentErrors)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 消息类型（V315 口径：text / image / file / voice / video / mixed）。
     *
     * <p>为什么自己算而不用平台值：出站没有平台的 {@code msg_type} 可抄。
     * 口径：有正文 + 有附件 ⇒ {@code mixed}；只有附件 ⇒ 按附件类型给 {@code image} / {@code file}；
     * 没附件 ⇒ {@code text}。
     *
     * @param attachments <b>已发出去的</b>附件（见 {@link #delivered}）
     */
    private static String msgType(boolean hasText, List<ImSendAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "text";
        }
        boolean anyImage = false;
        boolean anyOther = false;
        for (ImSendAttachment a : attachments) {
            if (a == null) {
                continue;
            }
            if (a.kindOrDefault() == MediaKind.IMAGE) {
                anyImage = true;
            } else {
                anyOther = true;
            }
        }
        if (hasText) {
            return "mixed";
        }
        if (anyImage && !anyOther) {
            return "image";
        }
        if (anyOther && !anyImage) {
            return "file";
        }
        return "mixed";
    }

    /**
     * 附件引用 → {@code media_json}（JSON 数组，元素同 {@code InboundMessage.Media}：kind/fileName/ref）。
     *
     * <p>⛔ <b>只存引用，绝不存二进制</b>。
     * ⚠️ 这里的 {@code ref} 是<b>本地路径</b>（不是平台侧的 image_key / media_id）：
     * 平台引用由策略内部上传后持有，{@link ImSendResult} 并未把它暴露出来
     * ⇒ 读侧<b>不能</b>拿这个 ref 去调平台接口，只能当「当时发了哪个文件」的线索。
     *
     * <p>⭐ <b>上传失败的附件不记</b>：那部分根本没发出去（正文到了、附件没到），
     * 记进去就是「我们发过这张图」的假事实。
     *
     * @param attachments <b>已发出去的</b>附件（见 {@link #delivered}）
     */
    private static String mediaJson(List<ImSendAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<Map<String, String>> items = new ArrayList<>();
        for (ImSendAttachment a : attachments) {
            if (a == null) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("kind", a.kindOrDefault() == MediaKind.IMAGE ? "image" : "file");
            m.put("fileName", a.name());
            m.put("ref", a.path() == null ? null : a.path().toAbsolutePath().toString());
            items.add(m);
        }
        if (items.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(items);
        } catch (Exception e) {
            // ⛔ 序列化失败不能影响发送（而且只丢附件引用，不丢消息本身）
            log.warn("[ImMessageOutbound] 附件引用序列化失败（只丢附件引用）err={}", e.toString());
            return null;
        }
    }

    /**
     * 这个附件是不是「上传失败、没发出去」。
     *
     * <p>判据：{@link ImSendResult#attachmentErrors()} 的每条文本都是
     * {@code "<文件名>：<原因>"}（由 {@code AbstractImSendStrategy} 拼）⇒ 按前缀匹配。
     *
     * <p>⚠️ 耦合说明：那个前缀格式在 {@code AbstractImSendStrategy} 里；
     * 格式若变了，这里最多退化成「把失败附件也记上」（多记一条附件引用），
     * <b>不会</b>反过来把已送达的附件丢掉 —— 宁可多记，也不可漏记已送达的附件。
     */
    private static boolean failed(ImSendAttachment a, List<String> attachmentErrors) {
        if (attachmentErrors == null || attachmentErrors.isEmpty()) {
            return false;
        }
        String prefix = a.name() + "：";
        for (String e : attachmentErrors) {
            if (e != null && e.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
