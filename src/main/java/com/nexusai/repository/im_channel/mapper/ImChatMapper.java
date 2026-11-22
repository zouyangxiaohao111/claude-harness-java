package com.nexusai.repository.im_channel.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_channel.entity.ImChatRecord;

import java.util.List;

/**
 * {@code im_chat} mapper（V305）· 产品层聊天目录。
 *
 * <p>具名 default 方法，不把 {@link QueryWrapper} 泄到服务层（对齐本包其它 mapper）。
 */
public interface ImChatMapper extends BaseMapper<ImChatRecord> {

    /** 全部聊天，按最后消息时间倒序（「任务」tab 的默认顺序）。 */
    default List<ImChatRecord> selectAllOrdered() {
        return selectListByQuery(QueryWrapper.create()
            .orderBy("last_message_at", false)
            .orderBy("created_at", false));
    }

    /** 某实例下的聊天。 */
    default List<ImChatRecord> selectListByInstanceId(String instanceId) {
        return selectListByQuery(QueryWrapper.create().where("instance_id = ?", instanceId));
    }

    /** 某任务接的聊天（左栏「IM 会话」块用）。 */
    default List<ImChatRecord> selectListByBoundSessionId(String sessionId) {
        return selectListByQuery(QueryWrapper.create().where("bound_session_id = ?", sessionId));
    }

    /** 适配器 upsert 入口：按 (instance_id, peer_id) 查已有行。 */
    default ImChatRecord selectOneByInstanceAndPeer(String instanceId, String peerId) {
        return selectOneByQuery(QueryWrapper.create()
            .where("instance_id = ?", instanceId)
            .and("peer_id = ?", peerId));
    }

    default ImChatRecord selectOneById(String id) {
        return selectOneByQuery(QueryWrapper.create().where("id = ?", id));
    }

    /**
     * 只取「已有回复令牌」的行（激活状态批量查询用）。
     *
     * <p>为什么单查一次而不是逐行 {@code selectOneById}：聊天目录可能有几百行
     * （本机微信同步会批量 upsert），逐行查就是 N+1；这里只关心
     * {@code reply_token} 非空的行，一次拿回来建 Set 即可。
     */
    default List<ImChatRecord> selectListWithReplyToken() {
        return selectListByQuery(QueryWrapper.create()
            .where("reply_token is not null and reply_token <> ''"));
    }
}
