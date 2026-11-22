package com.nexusai.repository.im_message.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_message.entity.ImMessageRecord;

import java.util.List;

/**
 * {@code im_message} mapper（V315）· 三平台统一消息表。
 *
 * <p>具名 default 方法，不把 {@link QueryWrapper} 泄到服务层（对齐本包其它 mapper）。
 *
 * <p>⚠️ 时间过滤与排序都按<b>文本字典序</b>比较 {@code sent_at} ⇒ 写入方必须统一 ISO 形态
 * （同一时区偏移），否则区间过滤会静默漏数据。
 */
public interface ImMessageMapper extends BaseMapper<ImMessageRecord> {

    /**
     * 按会话 + 时间区间查（工具主路径）。
     *
     * <p>排序：{@code sent_at} <b>倒序</b>（最新在前）+ {@code fetched_at} 正序作同秒内的次序。
     * 为什么倒序：limit 的语义是「别把库撑爆」⇒ 取<b>最近</b> N 条比取最早 N 条有用；
     * 要按时间正序展示由调用方 reverse。
     *
     * <p>为什么要 {@code fetched_at} 作次级排序：企微的 {@code send_time} 只到<b>秒</b>
     * ⇒ 同一秒内多条消息的 sent_at 完全相同 ⇒ 没有次序的话顺序是随机的（每页可能不一样）。
     *
     * @param sinceIso 起（不含）；null = 不限
     * @param untilIso 止（含）；null = 不限
     */
    default List<ImMessageRecord> selectByChat(String instanceId, String chatId,
                                                String sinceIso, String untilIso, int limit) {
        QueryWrapper q = QueryWrapper.create()
            .where("instance_id = ?", instanceId)
            .and("chat_id = ?", chatId);
        if (sinceIso != null && !sinceIso.isBlank()) {
            q.and("sent_at >= ?", sinceIso);
        }
        if (untilIso != null && !untilIso.isBlank()) {
            q.and("sent_at <= ?", untilIso);
        }
        q.orderBy("sent_at", false)
            .orderBy("fetched_at", true)
            .orderBy("id", true)
            .limit(Math.max(1, limit));
        return selectListByQuery(q);
    }

    /**
     * 按发送者筛（「看群里某人的消息」）。三平台统一走这条 SQL。
     *
     * <p>为什么不用平台侧接口：飞书 {@code /im/v1/messages/search} 要额外 scope 且限流
     * 100 次/分钟；企微 {@code chat/messages/list} <b>根本没有</b> sender 参数
     * ⇒ 三平台统一在本地表上筛，才有一致的语义。
     */
    default List<ImMessageRecord> selectBySender(String instanceId, String chatId, String senderId,
                                                  String sinceIso, int limit) {
        QueryWrapper q = QueryWrapper.create()
            .where("instance_id = ?", instanceId)
            .and("chat_id = ?", chatId)
            .and("sender_id = ?", senderId);
        if (sinceIso != null && !sinceIso.isBlank()) {
            q.and("sent_at >= ?", sinceIso);
        }
        q.orderBy("sent_at", false)
            .orderBy("fetched_at", true)
            .orderBy("id", true)
            .limit(Math.max(1, limit));
        return selectListByQuery(q);
    }

    /**
     * 该会话已有的最大 {@code sent_at}（用于算增量游标）；没有则 null。
     *
     * <p>⚠️ 游标语义 = 「已经落库的最新一条消息时间」⇒ 下次只拉这个时间之后的。
     * 用 MAX 而不是「最后一行的 sent_at」：并发落库时两者可能不一致，MAX 才是真上界。
     */
    default String selectMaxSentAt(String instanceId, String chatId) {
        Object v = selectObjectByQuery(QueryWrapper.create()
            .select("max(sent_at)")
            .where("instance_id = ?", instanceId)
            .and("chat_id = ?", chatId));
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 幂等前置检查：该 (instance_id, dedup_key) 是否已存在。
     *
     * <p>走唯一索引 {@code uq_im_message_dedup} ⇒ 一次索引查找，与表大小无关。
     */
    default long countByDedupKey(String instanceId, String dedupKey) {
        return selectCountByQuery(QueryWrapper.create()
            .where("instance_id = ?", instanceId)
            .and("dedup_key = ?", dedupKey));
    }
}
