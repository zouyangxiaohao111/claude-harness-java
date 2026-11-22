package com.nexusai.repository.im_message.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_message.entity.WechatMessageRow;

import java.util.List;

/**
 * {@code wechat_message}（V301）只读 mapper · 供 IM 消息拉取层读本机微信记录。
 *
 * <p>⛔ <b>只读</b>：不提供 insert/update/delete。写入方只有 {@code com.nexusai.wechatlog}
 * 的同步任务，这里再开写口就是同一张表两个写者。
 *
 * <p>⚠️ 与既有 {@code WechatConversationMapper.scanMessages} 的区别：那条是<b>扫描</b>用的
 * 投影（带关键词 LIKE + 只取摘要列），这里要的是「某会话某时间窗内的<b>全部</b>消息」，
 * 且要 {@code server_id} / {@code sort_seq}（用于算稳定幂等键）⇒ 另开一条只读查询。
 */
public interface WechatMessageReadMapper extends BaseMapper<WechatMessageRow> {

    /**
     * 拉某会话在时间窗内的消息（按时间<b>倒序</b>，最新在前）。
     *
     * @param conversationId    平台侧会话 id（wxid / xxx@chatroom）
     * @param sinceEpochSeconds 起（含）；{@code null} = 不限
     * @param untilEpochSeconds 止（含）；{@code null} = 不限
     * @param limit             最多多少条
     */
    default List<WechatMessageRow> selectByConversation(String conversationId,
                                                        Long sinceEpochSeconds,
                                                        Long untilEpochSeconds,
                                                        int limit) {
        QueryWrapper q = QueryWrapper.create()
            .select("id", "account_wxid", "conversation_id", "server_id", "sort_seq",
                    "msg_time", "sender", "msg_type", "content_preview", "is_self")
            .where("conversation_id = ?", conversationId);
        if (sinceEpochSeconds != null) {
            q.and("msg_time >= ?", sinceEpochSeconds);
        }
        if (untilEpochSeconds != null) {
            q.and("msg_time <= ?", untilEpochSeconds);
        }
        q.orderBy("msg_time", false)
            .orderBy("sort_seq", false)
            .limit(Math.max(1, limit));
        return selectListByQuery(q);
    }
}
