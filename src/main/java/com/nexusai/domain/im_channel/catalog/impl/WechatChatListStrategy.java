package com.nexusai.domain.im_channel.catalog.impl;

import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.repository.im_channel.entity.WechatConversationRecord;
import com.nexusai.repository.im_channel.mapper.WechatConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信会话列表策略。
 *
 * <h2>为什么微信「不联网」</h2>
 * 微信 iLink 的 {@code getupdates} 只给**增量**（而且是单消费者语义 —— 读了就没了），
 * 拿不到历史。所以「有哪些聊天」的数据源不是 iLink，而是**本机微信记录的扫描产物**
 * （V301 表 {@code wechat_conversation}，实测 413 个会话）。
 * <p>⇒ 本策略是一次**本地库查询**，不换票、不发网络请求。
 *
 * <h3>⚠️ 关于 {@code instanceId}</h3>
 * {@code wechat_conversation} 表<b>没有实例归属列</b>（只有 {@code account_wxid}）——
 * 本机微信只有一个账号，扫描产物是全局的。所以 {@code instanceId} 在本策略里
 * <b>只用于日志定位，不参与过滤</b>，返回全部会话。
 * ⛔ 不要在这里按 instanceId 过滤：表里根本没有这一列，强行过滤会永远返回空。
 *
 * <h3>⚠️ 群聊目前发不出去（平台限制，注释里说清以免误判）</h3>
 * 微信 iLink 没有群发消息通道；私聊也要等对方先说话（要 {@code context_token}）。
 * 本策略<b>照样把群返回</b> —— 界面与 AI 需要「看得见」，能不能发由发送层判定。
 */
@Component
public class WechatChatListStrategy implements ImChatListStrategy {

    private static final Logger log = LoggerFactory.getLogger(WechatChatListStrategy.class);

    @Autowired private WechatConversationMapper conversationMapper;

    @Override public String platform() { return "wechat"; }

    /**
     * 可以「刷新」：查的是本机扫描产物，能拿到比 {@code im_chat} 目录更新的数据。
     *
     * <p>（与「联网实时」不同，但工具层对这个语义是够用的：要的是「比目录更全的会话来源」。）
     */
    @Override public boolean canRefresh() { return true; }

    @Override
    public List<ImChatCandidate> fetch(String instanceId) throws ImSendException {
        List<WechatConversationRecord> rows;
        try {
            rows = conversationMapper.selectAllOrdered();
        } catch (RuntimeException e) {
            // 表可能还没建（没同步过本机微信）⇒ 如实报错，不要假装「没有会话」
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "读取本机微信会话失败（可能还没同步过本机微信）", null, false);
        }
        if (rows == null || rows.isEmpty()) {
            log.info("[ImChatList] 微信会话为空（还没同步过本机微信）instance={}", instanceId);
            return List.of();
        }
        List<ImChatCandidate> out = new ArrayList<>(rows.size());
        for (WechatConversationRecord c : rows) {
            if (c == null || c.getConversationId() == null || c.getConversationId().isBlank()) {
                continue;
            }
            String peerId = c.getConversationId().trim();
            String peerName = c.getDisplayName() == null || c.getDisplayName().isBlank()
                    ? peerId : c.getDisplayName().trim();
            // isGroup: 1=群；null（老数据）按单聊处理 —— 宁可不误判成群
            String chatKind = (c.getIsGroup() != null && c.getIsGroup() == 1) ? "group" : "p2p";
            out.add(new ImChatCandidate(peerId, peerName, chatKind, toIso(c.getLastMessageTime())));
        }
        log.info("[ImChatList] 微信会话拉取完成 instance={} total={}", instanceId, out.size());
        return out;
    }

    /** epoch 毫秒 → ISO-8601 偏移时间；null/非法 ⇒ null（不猜）。 */
    private static String toIso(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        try {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
