package com.nexusai.domain.im_channel;

import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.repository.im_channel.entity.ImSendAuditRecord;
import com.nexusai.repository.im_channel.mapper.ImSendAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * IM 对外发言审计（V312）。
 *
 * <h2>为什么「以用户名义」的发言必须留痕</h2>
 * 机器人通知发错了只是通知发错；<b>以用户本人名义</b>发错了，对方会以为「这句话是用户本人说的」
 * ⇒ 那是用户的社会信誉问题，不是技术问题。事后必须能回答：
 * <ul>
 *   <li>这条是谁让 AI 发的？</li>
 *   <li>发给了谁（哪个聊天 / 哪个平台）？</li>
 *   <li>发的什么（摘要）？</li>
 *   <li>什么时间？</li>
 *   <li><b>实际</b>是什么身份（请求 USER 但被降级成 BOT 的场景最关键）？</li>
 * </ul>
 *
 * <h2>为什么写审计不能拖垮投递</h2>
 * 审计失败（库挂了、表不存在）**不能**让已经发出去的消息变成「失败」⇒
 * 异常一律吞掉并记日志。投递已经发生了，审计写不进去只是少一条记录，
 * 而把投递报成失败会让 AI 重发 ⇒ 对方收到重复消息。
 */
@Component
public class ImSendAuditService {

    private static final Logger log = LoggerFactory.getLogger(ImSendAuditService.class);

    /** 正文摘要最大长度（审计表不是内容仓库）。 */
    public static final int PREVIEW_MAX_CHARS = 200;

    @Autowired(required = false)
    private ImSendAuditMapper mapper;

    /** [测试注入] mapper。 */
    public void setMapper(ImSendAuditMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记一条「以用户名义」的对外发言。
     *
     * @param chat       目标聊天（可空：聊天不存在时投递失败也要留痕）
     * @param requested  请求的身份（通常是 {@link SendIdentity#USER}）
     * @param actual     实际使用的身份（从 {@link ImSendResult#identity()} 取；可空 ⇒ 视为 BOT）
     * @param text       正文（只留前 {@value #PREVIEW_MAX_CHARS} 字）
     * @param attachments 附件数量
     * @param result     投递结果（可空）
     */
    public void record(ImChatEntry chat, SendIdentity requested, SendIdentity actual,
                       String text, int attachments, ImSendResult result) {
        if (mapper == null) {
            return;   // 未注入（测试 / 孤立运行）⇒ 不影响主链
        }
        try {
            ImSendAuditRecord r = new ImSendAuditRecord();
            r.setId("imsa-" + UUID.randomUUID().toString().substring(0, 8));
            if (chat != null) {
                r.setTargetChatId(chat.id());
                r.setPeerId(chat.peerId());
                r.setPlatform(chat.platform());
            }
            r.setRequestedIdentity(requested == null ? SendIdentity.BOT.name() : requested.name());
            r.setIdentity(actual == null ? SendIdentity.BOT.name() : actual.name());
            r.setTextPreview(preview(text));
            r.setAttachmentCount(attachments);
            if (result != null) {
                r.setStatus(result.status().name());
                r.setErrorCode(result.errorCode());
                r.setReason(result.reason() != null ? result.reason() : result.identityNote());
            }
            r.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            mapper.insert(r);
        } catch (RuntimeException e) {
            // ⛔ 审计失败**绝不能**让已发出的投递变成失败（否则 AI 会重发 ⇒ 对方收到重复消息）
            log.warn("[ImSendAudit] 写审计失败（不影响投递结果）: {}", e.toString());
        }
    }

    /** 正文摘要（截断 + 去换行，避免审计表被多行文本撑开）。 */
    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_MAX_CHARS ? flat : flat.substring(0, PREVIEW_MAX_CHARS) + "…";
    }

    /** 最近若干条审计（供 UI / 排障查询）。 */
    public List<ImSendAuditRecord> recent(int limit) {
        if (mapper == null) {
            return List.of();
        }
        return mapper.selectRecent(Math.max(1, Math.min(limit, 200)));
    }
}
