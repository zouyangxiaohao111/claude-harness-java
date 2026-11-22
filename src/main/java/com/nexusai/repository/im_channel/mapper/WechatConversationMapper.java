package com.nexusai.repository.im_channel.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_channel.entity.WechatConversationRecord;
import com.nexusai.repository.im_channel.entity.WechatMessageHit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * {@code wechat_conversation} / {@code wechat_message} 只读 mapper（V301 表，wechatlog 域）。
 *
 * <p>⛔ <b>只读</b>：不提供 insert/update/delete 的封装。写入方只有 wechatlog 的同步任务，
 * 这里再开写口就是同一张表两个写者。
 */
public interface WechatConversationMapper extends BaseMapper<WechatConversationRecord> {

    /** 全部会话，按最后消息时间倒序（扫描规则的监控会话选择器用）。 */
    default List<WechatConversationRecord> selectAllOrdered() {
        return selectListByQuery(QueryWrapper.create()
            .orderBy("last_message_time", false));
    }

    /**
     * 一次 GROUP BY 拿到「每个会话在 since 之后的消息数」。
     *
     * <p>为什么不用逐个 count：真实库有 413 个会话，逐个查 = 414 次往返（N+1）。
     */
    @Select("SELECT conversation_id AS conversationId, COUNT(*) AS cnt "
        + "FROM wechat_message WHERE msg_time >= #{since} GROUP BY conversation_id")
    List<Map<String, Object>> countRecentGrouped(@Param("since") long since);

    /**
     * 按账号聚合（「微信同步状态」面板用）。
     *
     * <p>统计口径：会话数 = 该账号的会话行数；消息数 = 各会话 {@code message_count} 之和；
     * 最近同步 = 该账号所有会话里最大的 {@code synced_at}。
     */
    @Select("SELECT account_wxid AS wxid, COUNT(*) AS convCount, "
        + "COALESCE(SUM(message_count), 0) AS msgCount, MAX(synced_at) AS lastSyncedAt "
        + "FROM wechat_conversation GROUP BY account_wxid ORDER BY lastSyncedAt DESC")
    List<Map<String, Object>> aggregateByAccount();

    /**
     * 扫描执行：取某会话在 since 之后、命中任一关键词的消息。
     *
     * <p>用 {@code search_text}（截断后的可检索文本）而不是 {@code content_preview}：
     * 后者只为人眼准备、可能被截断，拿它做匹配会漏命中。
     *
     * <p>参数 {@code kwPatterns} 是已拼好的 LIKE 模式（如 {@code %合同%}），
     * 由服务层用 {@code OR} 连接 —— ⛔ 不要把用户输入直接拼进 SQL。
     */
    @Select("<script>SELECT m.id AS id, m.conversation_id AS conversationId, "
        + "c.display_name AS displayName, m.sender AS sender, m.msg_time AS msgTime, "
        + "m.content_preview AS contentPreview, m.search_text AS searchText "
        + "FROM wechat_message m LEFT JOIN wechat_conversation c ON c.conversation_id = m.conversation_id "
        + "WHERE m.conversation_id IN "
        + "<foreach collection='conversationIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> "
        + "AND m.msg_time &gt;= #{since} "
        + "<if test='patterns != null and patterns.size() > 0'>AND ("
        + "<foreach collection='patterns' item='p' separator=' OR '>m.search_text LIKE #{p} ESCAPE '\\'</foreach>"
        + ") </if>"
        + "ORDER BY m.msg_time DESC LIMIT #{limit}</script>")
    List<WechatMessageHit> scanMessages(@Param("conversationIds") List<String> conversationIds,
                                        @Param("since") long since,
                                        @Param("patterns") List<String> patterns,
                                        @Param("limit") int limit);
}
