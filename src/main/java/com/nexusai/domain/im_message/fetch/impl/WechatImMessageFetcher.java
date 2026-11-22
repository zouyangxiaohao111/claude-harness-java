package com.nexusai.domain.im_message.fetch.impl;

import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.repository.im_message.entity.WechatMessageRow;
import com.nexusai.repository.im_message.mapper.WechatMessageReadMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信消息拉取器 —— <b>不联网</b>，查本机微信记录的索引表（V301 {@code wechat_message}）。
 *
 * <h2>为什么微信走本地库</h2>
 * 微信 iLink 的 {@code getupdates} 只给<b>增量</b>，而且是<b>单消费者</b>语义（读了就没了），
 * 拿不到历史 ⇒ 「有哪些消息」的唯一完整来源是本机同步产物。
 * <p>⇒ 本实现是一次**本地 SQL 查询**：不换票、不发网络请求、不会打到任何外部平台。
 *
 * <h2>⚠️ 微信没有「平台消息 id」可用</h2>
 * {@code wechat_message.id} 是 {@code UUID.randomUUID()}（每次同步都变）⇒ 不能当幂等键。
 * 真正稳定的是唯一索引 {@code (account_wxid, conversation_id, server_id, sort_seq)}
 * ⇒ 本实现把它拼成**稳定的合成 messageId**：
 * {@code wx:<account>:<conversation>:<serverId>:<sortSeq>}。
 * 这样 {@code ImMessageService.dedupKey} 走「有平台 id」那条分支 ⇒ 重复拉取不会插第二条。
 *
 * <h2>⚠️ 微信的时间是 epoch 秒</h2>
 * 与飞书（毫秒）、企微（{@code yyyy-MM-dd HH:mm:ss}）都不同 ⇒ 这里统一转成规范形态。
 *
 * <h2>正文只有摘要</h2>
 * V301 的存储策略是「只存摘要」：{@code content_preview} 是截断后给人看的一小段，
 * 全文要回本机解密库取（不在本仓范围）⇒ 本拉取器返回的正文就是摘要。
 */
@Component
public class WechatImMessageFetcher implements ImMessageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WechatImMessageFetcher.class);

    public static final String PLATFORM = "wechat";

    /** 合成 messageId 的前缀（避免与飞书 {@code om_} / 企微（无 id）混淆）。 */
    static final String SYNTHETIC_ID_PREFIX = "wx:";

    @Autowired private WechatMessageReadMapper mapper;

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                      String untilIso, int maxMessages) throws ImMessageFetchException {
        if (chatId == null || chatId.isBlank()) {
            throw new ImMessageFetchException("IM_PARAM_INVALID", "微信拉取消息失败：chatId 为空");
        }
        Long since = epochSeconds(sinceIso);
        Long until = epochSeconds(untilIso);

        List<WechatMessageRow> rows;
        try {
            rows = mapper.selectByConversation(chatId, since, until, Math.max(1, maxMessages));
        } catch (RuntimeException e) {
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "读取本机微信消息失败（可能还没同步过本机微信）: " + e.getClass().getSimpleName(), e);
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<ImMessageEntry> out = new ArrayList<>(rows.size());
        for (WechatMessageRow r : rows) {
            if (r == null || r.getMsgTime() == null) {
                continue;
            }
            String sentAt = ImMessageTime.canonicalFromEpochSeconds(r.getMsgTime());
            out.add(new ImMessageEntry(
                null,                                   // 主键由持久化层生成
                instanceId,
                PLATFORM,
                chatId,
                stableMessageId(r),                    // ⭐ 稳定幂等键（不是随机 UUID）
                r.getSender(),
                null,                                   // 微信消息表里没有发送人昵称
                mapMsgType(r.getMsgType()),
                r.getContentPreview(),
                null,                                   // 微信没有媒体引用列
                sentAt,
                (r.getIsSelf() != null && r.getIsSelf() == 1) ? "out" : "in",
                "official"));
        }
        log.info("[ImFetch] 微信本机消息 instance={} chat={} rows={}", instanceId, chatId, out.size());
        return out;
    }

    /**
     * 稳定 messageId：{@code wx:<account>:<conversation>:<serverId>:<sortSeq>}。
     *
     * <p>⛔ 不用 {@code wechat_message.id}（随机 UUID）—— 那样每次同步都会算出一个新的幂等键
     * ⇒ 同一条微信消息在 {@code im_message} 里越堆越多。
     */
    static String stableMessageId(WechatMessageRow r) {
        return SYNTHETIC_ID_PREFIX
            + nullToEmpty(r.getAccountWxid()) + ":"
            + nullToEmpty(r.getConversationId()) + ":"
            + (r.getServerId() == null ? 0L : r.getServerId()) + ":"
            + (r.getSortSeq() == null ? 0L : r.getSortSeq());
    }

    /**
     * 微信的消息类型（数字）→ {@code im_message.msg_type} 口径。
     *
     * <p>映射依据 V301 注释：1 文本 / 3 图片 / 34 语音 / 49 应用消息 / 10000 系统。
     * 49 的内层子类（文件 / 应用 / 红包）在这张表里看不到 ⇒ 只能当 {@code mixed}，
     * <b>不猜</b>它是文件。
     */
    static String mapMsgType(Integer wechatType) {
        if (wechatType == null) {
            return "mixed";
        }
        return switch (wechatType) {
            case 1 -> "text";        // 文本
            case 3 -> "image";       // 图片
            case 34 -> "voice";      // 语音
            case 43 -> "video";      // 视频
            case 47 -> "text";       // 表情（渲染出来就是文字）
            case 10000 -> "text";    // 系统提示
            default -> "mixed";      // 49 应用消息 / 50 通话 / 42 名片 …
        };
    }

    /** ISO 时间 → epoch 秒；解析不出来返回 {@code null}（不限）。 */
    private static Long epochSeconds(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        Instant at = ImMessageTime.toInstant(iso);
        return at == null ? null : at.getEpochSecond();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
