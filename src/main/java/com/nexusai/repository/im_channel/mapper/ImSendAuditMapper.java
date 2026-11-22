package com.nexusai.repository.im_channel.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_channel.entity.ImSendAuditRecord;

import java.util.List;

/**
 * {@code im_send_audit} mapper（V312）· IM 对外发言审计。
 *
 * <p>具名 default 方法，不把 {@link QueryWrapper} 泄到服务层（对齐本包其它 mapper）。
 */
public interface ImSendAuditMapper extends BaseMapper<ImSendAuditRecord> {

    /** 最近的审计（倒序），用于「最近对外做过什么」的查询。 */
    default List<ImSendAuditRecord> selectRecent(int limit) {
        return selectListByQuery(QueryWrapper.create()
            .orderBy("created_at", false)
            .limit(limit));
    }

    /** 某目标聊天的审计历史。 */
    default List<ImSendAuditRecord> selectListByTargetChatId(String chatId) {
        return selectListByQuery(QueryWrapper.create()
            .where("target_chat_id = ?", chatId)
            .orderBy("created_at", false));
    }
}
